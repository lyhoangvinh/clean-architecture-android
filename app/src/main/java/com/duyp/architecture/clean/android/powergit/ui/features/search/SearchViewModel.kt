package com.duyp.architecture.clean.android.powergit.ui.features.search

import android.support.annotation.MainThread
import android.support.v7.util.DiffUtil
import com.duyp.architecture.clean.android.powergit.domain.entities.IssueEntity
import com.duyp.architecture.clean.android.powergit.domain.entities.ListEntity
import com.duyp.architecture.clean.android.powergit.domain.entities.repo.RepoEntity
import com.duyp.architecture.clean.android.powergit.domain.usecases.GetUser
import com.duyp.architecture.clean.android.powergit.domain.usecases.issue.GetIssue
import com.duyp.architecture.clean.android.powergit.domain.usecases.issue.GetRecentIssue
import com.duyp.architecture.clean.android.powergit.domain.usecases.issue.SearchIssue
import com.duyp.architecture.clean.android.powergit.domain.usecases.repo.GetRecentRepos
import com.duyp.architecture.clean.android.powergit.domain.usecases.repo.GetRepo
import com.duyp.architecture.clean.android.powergit.domain.usecases.repo.SearchPublicRepo
import com.duyp.architecture.clean.android.powergit.domain.usecases.user.GetRecentUser
import com.duyp.architecture.clean.android.powergit.onErrorResumeEmpty
import com.duyp.architecture.clean.android.powergit.onErrorReturnEmptyList
import com.duyp.architecture.clean.android.powergit.printStacktraceIfDebug
import com.duyp.architecture.clean.android.powergit.ui.Event
import com.duyp.architecture.clean.android.powergit.ui.base.BaseViewModel
import com.duyp.architecture.clean.android.powergit.ui.base.adapter.AdapterData
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class SearchViewModel @Inject constructor(
        private val mGetRepo: GetRepo,
        private val mGetIssue: GetIssue,
        private val mGetUser: GetUser,
        private val mGetRecentRepos: GetRecentRepos,
        private val mGetRecentIssue: GetRecentIssue,
        private val mGetRecentUser: GetRecentUser,
        private val mSearchPublicRepo: SearchPublicRepo,
        private val mSearchIssues: SearchIssue
): BaseViewModel<SearchState, SearchIntent>(), AdapterData<SearchItem> {

    companion object {
        private const val MIN_SEARCH_TERM_LENGTH = 3
    }

    private var mSearchDisposable: Disposable? = null

    private var mIsLoading = false

    private var mSearchTerm: String = ""

    private var mCurrentTab: SearchTab = SearchTab.REPO

    private var mRecentRepoIds: List<Long> = emptyList()

    private var mRecentIssueIds: List<Long> = emptyList()

    private var mRecentUserIds: List<Long> = emptyList()

    private var mRepoSearchResult: SearchResult<RepoEntity> = SearchResult()

    private var mIssueSearchResult: SearchResult<IssueEntity> = SearchResult()

    private var mDataList: ListEntity<SearchItem> = ListEntity()

    override fun initState() = SearchState()

    override fun getItemAtPosition(position: Int): SearchItem? {
        if (mDataList.items.isEmpty() || position < 0 || position >= getTotalCount())
            return null
        return mDataList.items[position]
    }

    override fun getItemTypeAtPosition(position: Int): Int {
        return getItemAtPosition(position)?.viewType() ?: 0
    }

    override fun getTotalCount(): Int {
        return mDataList.items.size
    }

    override fun composeIntent(intentSubject: Observable<SearchIntent>) {

        addDisposable {
            intentSubject.ofType(SearchIntent.SelectTab::class.java)
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnNext { mCurrentTab = it.tab }
                    .processDataUpdate()
                    .filter {
                        mSearchTerm.length >= MIN_SEARCH_TERM_LENGTH && when (mCurrentTab) {
                            SearchTab.REPO -> mRepoSearchResult.data.items.isEmpty()
                            else -> mIssueSearchResult.data.items.isEmpty()
                        }
                    }
                    .switchMap { loadSearchResults(true) }
                    .subscribe()
        }

        // subscribe this to set current search term and clear data if needed for any on going search intent
        addDisposable {
            intentSubject.ofType(SearchIntent.Search::class.java)
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnNext {
                        mSearchTerm = it.term
                        clearSearchResults(it.term.isEmpty())
                    }
                    .observeOn(Schedulers.io())
                    .filter { !it.term.isEmpty() }
                    .switchMap {
                        return@switchMap when (mCurrentTab) {
                            SearchTab.REPO -> Observable.concatArray(loadRecentRepos(), loadRecentIssues(), loadRecentUsers())
                            SearchTab.ISSUE -> Observable.concatArray(loadRecentIssues(), loadRecentRepos(), loadRecentUsers())
                            else -> Observable.concatArray(loadRecentUsers(), loadRecentRepos(), loadRecentIssues())
                        }
                    }
                    .subscribe()
        }

        // search public repos for on going search intent debounced with 1 second and only if search term length equal
        // or greater than [MIN_SEARCH_TERM_LENGTH]
        addDisposable {
            intentSubject.ofType(SearchIntent.Search::class.java)
                    .debounce(1, TimeUnit.SECONDS)
                    .filter { !mIsLoading }
                    .doOnNext { mSearchTerm = it.term }
                    .filter { mSearchTerm.length >= MIN_SEARCH_TERM_LENGTH }
                    .switchMap { loadSearchResults(true) }
                    .subscribe()
        }

        // load more search result, only for public repos, not for recent repos
        addDisposable {
            intentSubject.ofType(SearchIntent.LoadMore::class.java)
                    .filter { !mIsLoading && getCurrentResultList().data.canLoadMore() }
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnNext { setState { copy(loadingMore = Event.empty()) } }
                    .switchMap { loadSearchResults(false) }
                    .subscribe()
        }

        // reload search result
        addDisposable {
            intentSubject.ofType(SearchIntent.ReloadResult::class.java)
                    .filter { !mIsLoading }
                    .switchMap { loadSearchResults(true) }
                    .subscribe()
        }
    }

    fun initCurrentTab(tab: SearchTab?) {
        mCurrentTab = tab ?: SearchTab.REPO
    }

    /**
     * Load recent properties which match current search term
     */
    private fun loadRecentRepos(): Observable<out Any> {
        return mGetRecentRepos.search(mSearchTerm)
                .subscribeOn(Schedulers.io())
                .doOnSuccess { mRecentRepoIds = it }
                .toObservable()
                .onErrorReturnEmptyList()
                .processDataUpdate()
    }

    /**
     * Load recent issues which match current search term
     */
    private fun loadRecentIssues(): Observable<out Any> {
        return mGetRecentIssue.getRecentIssueIds(mSearchTerm)
                .subscribeOn(Schedulers.io())
                .doOnSuccess { mRecentIssueIds = it }
                .toObservable()
                .onErrorReturnEmptyList()
                .processDataUpdate()
    }

    /**
     * Load recent issues which match current search term
     */
    private fun loadRecentUsers(): Observable<out Any> {
        return mGetRecentUser.getRecentUserIds(mSearchTerm)
                .subscribeOn(Schedulers.io())
                .doOnSuccess { mRecentUserIds = it }
                .toObservable()
                .onErrorReturnEmptyList()
                .processDataUpdate()
    }

    /**
     * Load search result of public properties which match current search term
     */
    private fun loadSearchResults(refresh: Boolean): Observable<out Any> {
        val currentList = if (refresh) ListEntity() else mDataList

        return when (mCurrentTab) {
            SearchTab.REPO -> mSearchPublicRepo.search(currentList.copyWith(mRepoSearchResult.data), mSearchTerm)
                    .processSearch(mRepoSearchResult)
                    .doOnNext { mRepoSearchResult = it }
                    .processDataUpdate()
            else -> mSearchIssues.search(currentList.copyWith(mIssueSearchResult.data), mSearchTerm)
                    .processSearch(mIssueSearchResult)
                    .doOnNext { mIssueSearchResult = it }
                    .processDataUpdate()
        }
    }

    /**
     * Process search for repos, issues, users... with following actions:
     * - store current search disposable
     * - show loading prior to executing search
     * - emit search data
     * - handle error
     */
    private fun <T> Single<ListEntity<T>>.processSearch(currentResultData: SearchResult<T>):
            Observable<SearchResult<T>> {
        return this
                .subscribeOn(Schedulers.io())
                .doOnSubscribe { mSearchDisposable = it }
                .map { currentResultData.copy(data = it, isSearching = false, error = null) }
                .toObservable()
                .startWith {
                    it.onNext(currentResultData.copy(isSearching = true, error = null))
                    it.onComplete()
                }
                .onErrorResumeNext { throwable: Throwable ->
                    Observable.fromCallable { currentResultData.copy(isSearching = false, error = throwable) }
                }
    }

    /**
     * Process an observable ([loadRecentRepos] or [loadSearchResults])
     * Result of both [loadRecentRepos] and [loadSearchResults] will be mixed and process to have adapter data to be
     * rendered, as well as calculating diff result and updating state accordingly as result of the loaders
     */
    private fun Observable<out Any>.processDataUpdate(): Observable<out Any> {
        return this.doOnSubscribe {
            mIsLoading = true
        }
                .observeOn(Schedulers.computation())
                .map { createAdapterData() }
                .map { newList ->
                    val diffResult = SearchDiffUtils.calculateDiffResult(mDataList, newList)
                    mDataList = newList
                    return@map diffResult
                }
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext {
                    setState {
                        copy(dataUpdated = Event(it))
                    }
                }
                .doOnError {
                    it.printStacktraceIfDebug()
                    mIsLoading = false
                    setState { copy(errorMessage = Event(it.message ?: ""), loadCompleted = Event.empty()) }
                }
                .doOnComplete {
                    mIsLoading = false
                    setState {
                        copy(loadCompleted = Event.empty())
                    }
                }
                .onErrorResumeEmpty()
    }

    @MainThread
    private fun clearSearchResults(clearRecentList: Boolean) {
        if (clearRecentList) {
            mRecentRepoIds = emptyList()
            mRecentIssueIds = emptyList()
            mRecentUserIds = emptyList()
        }
        mRepoSearchResult = SearchResult()
        mIssueSearchResult = SearchResult()
        val newList = createAdapterData()
        setState { copy(dataUpdated = Event(SearchDiffUtils.calculateDiffResult(mDataList, newList))) }
        mSearchDisposable?.dispose()
        mDataList = newList
    }

    private fun createAdapterData(): ListEntity<SearchItem> {
        val list = ArrayList<SearchItem>()

        if (!mSearchTerm.isEmpty()) {
            list.add(SearchItem.RecentHeader(mRecentRepoIds.size, mRecentIssueIds.size, mRecentUserIds.size,
                    mCurrentTab, mSearchTerm))
        }

        when (mCurrentTab) {
            SearchTab.REPO -> // recent repos
                if (!mRecentRepoIds.isEmpty()) {
                    list.addAll(mRecentRepoIds.map { SearchItem.RecentRepo(it, mGetRepo) })
                }
            SearchTab.ISSUE -> // recent issues
                if (!mRecentIssueIds.isEmpty()) {
                    list.addAll(mRecentIssueIds.map { SearchItem.RecentIssue(it, mGetIssue) })
                }
            SearchTab.USER -> // recent users
                if (!mRecentUserIds.isEmpty()) {
                    list.addAll(mRecentUserIds.map { SearchItem.RecentUser(it, mGetUser) })
                }
        }

        val result = getCurrentResultList()
        if (addResultHeaderIfNeeded(list, result)) {
            list.addAll(result.data.items.map {
                if (it is RepoEntity) {
                    return@map SearchItem.SearchResultRepo(it)
                }
                return@map SearchItem.SearchResultIssue(it as IssueEntity)
            })
        }

        return result.data.copyWith(list)
    }

    /**
     * Add search result header into adapter data if needed
     *
     * @return true if the header is added
     */
    private fun <T> addResultHeaderIfNeeded(currentAdapterData: MutableList<SearchItem>, result: SearchResult<T>):
            Boolean {
        val emptyResult = result.data.items.isEmpty()
        if (result.isSearching || result.error != null || !emptyResult) {
            currentAdapterData.add(
                    SearchItem.ResultHeader(
                            totalCount = result.data.totalCount ?: 0,
                            currentSearchTerm = mSearchTerm,
                            loading = result.isSearching,
                            errorMessage = result.error?.message,
                            currentTab = mCurrentTab
                    )
            )
        }
        return !emptyResult
    }

    private fun getCurrentResultList() = when (mCurrentTab) {
        SearchTab.REPO -> mRepoSearchResult
        else -> mIssueSearchResult
    }
}

enum class SearchTab(val position: Int) {
    REPO(0),
    ISSUE(1),
    USER(2);

    companion object {
        fun of(position: Int) = SearchTab.values()[position]
    }
}

interface SearchIntent {
    data class Search(val term: String): SearchIntent
    data class SelectTab(val tab: SearchTab): SearchIntent
    object ReloadResult: SearchIntent
    object LoadMore: SearchIntent
}

data class SearchState(
        val errorMessage: Event<String>? = null,
        val loadingMore: Event<Unit>? = null,
        val loadCompleted: Event<Unit>? = null,
        val dataUpdated: Event<DiffUtil.DiffResult>? = null
)

private data class SearchResult<T>(
        val data: ListEntity<T> = ListEntity(),
        val isSearching: Boolean = false,
        val error: Throwable? = null
)
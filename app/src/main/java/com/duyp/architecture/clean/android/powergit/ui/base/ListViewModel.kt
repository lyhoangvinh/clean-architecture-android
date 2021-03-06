package com.duyp.architecture.clean.android.powergit.ui.base

import android.support.annotation.CallSuper
import android.support.annotation.MainThread
import android.support.v7.util.DiffUtil
import com.duyp.architecture.clean.android.powergit.domain.entities.ListEntity
import com.duyp.architecture.clean.android.powergit.domain.entities.exception.AuthenticationException
import com.duyp.architecture.clean.android.powergit.onErrorResumeEmpty
import com.duyp.architecture.clean.android.powergit.printStacktraceIfDebug
import com.duyp.architecture.clean.android.powergit.ui.Event
import com.duyp.architecture.clean.android.powergit.ui.base.adapter.AdapterData
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers

/**
 * ViewModel for [ListFragment] to show a collection in RecyclerView.
 *
 * Receives [ListIntent] from the view to perform data loading (refreshing or loading more page) and updates the view
 * by [ListState] which contains some basic states for every list.
 *
 * It also implements [AdapterData] that any Adapter can access and render data on RecyclerView. The idea is in
 * Adapter we don't have any reference to list of data, all should be controlled by ViewModel to be persisted against
 * screen rotating, and more important: TESTABLE.
 *
 * [DiffUtil] is used for calculating the changes to update adapter's items
 *
 * The data is wrapped in [ListEntity] for pagination (and lazy load in some cases), see below explanation:
 *
 * @param [EntityType] type of data to be shown in adapter
 *
 * See some examples:
 *  - Basic [com.duyp.architecture.clean.android.powergit.ui.features.event.EventViewModel]
 *  - Extended [com.duyp.architecture.clean.android.powergit.ui.features.repo.list.RepoListViewModel]
 *
 * @param [S] View State
 * @param [I] View Intent, must extends [ListIntent]
 */
abstract class ListViewModel<S, I: ListIntent, EntityType>: BaseViewModel<S, I>(), AdapterData<EntityType> {

    private var mListEntity: ListEntity<EntityType> = ListEntity()

    private var mIsLoading: Boolean = false

    private var mLoadDisposable: Disposable? = null

    @CallSuper
    override fun composeIntent(intentSubject: Observable<I>) {
        // force view to refresh
        if (refreshAtStartup()) {
            withListState {
                // only refresh one time (no refresh if UI get rotated)
                if (refresh == null) {
                    setListState { copy(refresh = Event.empty()) }
                }
            }
        }

        // refresh intent
        addDisposable {
            intentSubject.ofType(getRefreshIntent()::class.java)
                    // do nothing if loading is in progress
                    .filter { !mIsLoading }
                    .switchMap { loadData(true) }
                    .subscribe()
        }

        // load more intent
        addDisposable {
            intentSubject.ofType(getLoadMoreIntent()::class.java)
                    // do nothing if loading is in progress and can't load more with current list
                    .filter { !mIsLoading && mListEntity.canLoadMore() }
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnNext {
                        setListState { copy(loadingMore = Event.empty()) }
                    }
                    .switchMap { loadData(false) }
                    .subscribe()
        }
    }

    override fun getTotalCount(): Int = mListEntity.items.size

    override fun getItemTypeAtPosition(position: Int): Int {
        if (mListEntity.items.isEmpty() || position < 0 || position >= getTotalCount())
            return -1
        return getItemType(mListEntity.items[position])
    }

    override fun getItemAtPosition(position: Int): EntityType? {
        if (mListEntity.items.isEmpty() || position < 0 || position >= getTotalCount())
            return null
        return mListEntity.items[position]
    }

    open protected fun getItemType(listItem: EntityType): Int = 0

    protected abstract fun refreshAtStartup(): Boolean

    /**
     * Set new list state based on current state.
     *
     * The final view model (implementer) might have the state is combination of [ListState] and other states such as
     * sorting... So it has to implement this function to set the list state properly
     */
    protected abstract fun setListState(s: ListState.() -> ListState)

    /**
     * Access current list state based on current state
     */
    protected abstract fun withListState(s: ListState.() -> Unit)

    /**
     * Implement this to load specific page
     */
    protected abstract fun loadList(currentList: ListEntity<EntityType>): Observable<ListEntity<EntityType>>

    /**
     * Compares two items for diff utils, see [calculateDiffResult]
     *
     * @return true if they are equal. By default we use [equals] and either the [ListType] should override equals()
     * method or the child view model should override this method to compare 2 items
     */
    protected abstract fun areItemsTheSame(old: EntityType, new: EntityType): Boolean

    /**
     * In some cases the final view model might have more intents than basic [ListIntent], so it has to specific
     * refresh intent as well as load more intent to make this class's functionality works
     */
    abstract fun getRefreshIntent(): I

    /**
     * see [getRefreshIntent]
     */
    abstract fun getLoadMoreIntent(): I

    @MainThread
    protected fun clearResults() {
        setListState {
            copy(refreshable = checkRefreshable(), dataUpdated = Event(calculateDiffResult(ListEntity())))
        }
        mLoadDisposable?.dispose()
        mListEntity = ListEntity()
    }

    /**
     * Calculate differences between current list and new list
     *
     * @return [DiffUtil.DiffResult] reflect the changes
     */
    open protected fun calculateDiffResult(newList: ListEntity<EntityType>): DiffUtil.DiffResult {
        return DiffUtil.calculateDiff(object : DiffUtil.Callback() {

            override fun getOldListSize() = mListEntity.items.size

            override fun getNewListSize() = newList.items.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return areItemsTheSame(mListEntity.items[oldItemPosition], newList.items[newItemPosition])
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return areItemContentsTheSame(mListEntity.items[oldItemPosition], newList.items[newItemPosition])
            }

            override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? {
                return getChangePayload(mListEntity.items[oldItemPosition], newList.items[newItemPosition])
            }
        }, false)
    }

    /**
     * Compares content of two items for diff utils, see [calculateDiffResult].
     *
     * @return true if content is the same. By default we use [equals] and either the [ListType] should override equals()
     * method or the child view model should override this method to compare 2 items. If our entity is data class,
     * there is no need to override equals() since it already done by Kotlin
     */
    open protected fun areItemContentsTheSame(old: EntityType, new: EntityType): Boolean {
        return old?.equals(new) ?: false
    }

    /**
     * get change payload of the old and the new item, used for partial updating item in recycler view
     * see [DiffUtil.Callback.getChangePayload]
     */
    open protected fun getChangePayload(old: EntityType, new: EntityType): Any? {
        return null
    }

    open protected fun checkRefreshable(): Boolean {
        return true
    }

    /**
     * Load specific page and set corresponding state (show loading, load completed, offline notice if data is came
     * from offline storage...)
     */
    @MainThread
    protected fun loadData(refresh: Boolean): Observable<out Any> {
        return loadList(if (refresh) ListEntity() else mListEntity)
                .subscribeOn(Schedulers.io())
                .doOnSubscribe {
                    mLoadDisposable = it
                    mIsLoading = true
                    setListState {
                        copy(
                                showLoading = refresh,
                                showEmptyView = false,
                                requireLogin = false,
                                refreshable = false
                        )
                    }
                }
                .observeOn(Schedulers.computation())
                .map {
                    val diffResult = calculateDiffResult(it)
                    mListEntity = it
                    return@map diffResult
                }
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext {
                    mListEntity.apiError?.printStacktraceIfDebug()
                    val err = mListEntity.apiError?.message ?: ""
                    setListState {
                        copy(
                                showOfflineNotice = mListEntity.isOfflineData,
                                dataUpdated = Event(it),
                                errorMessage = if (err.isEmpty()) null else Event(err)
                        )
                    }
                }
                .doOnError {
                    it.printStacktraceIfDebug()
                    mIsLoading = false
                    val isAuthError = it is AuthenticationException
                    if (isAuthError) {
                        mListEntity = ListEntity()
                    }
                    setListState {
                        copy(
                                showLoading = false,
                                showEmptyView = getTotalCount() == 0,
                                showOfflineNotice = false,
                                errorMessage = Event(it.message ?: ""),
                                requireLogin = isAuthError,
                                refreshable = !isAuthError && checkRefreshable()
                        )
                    }
                }
                .doOnComplete {
                    mIsLoading = false
                    setListState {
                        copy(
                                showLoading = false,
                                showEmptyView = getTotalCount() == 0,
                                refreshable = checkRefreshable(),
                                loadCompleted = Event.empty()
                        )
                    }
                }
                .onErrorResumeEmpty()
    }
}

data class ListState(
        val showLoading: Boolean = false,
        val showOfflineNotice: Boolean = false,
        val showEmptyView: Boolean = false,
        val requireLogin: Boolean = false,
        val refreshable: Boolean = true,
        val refresh: Event<Unit>? = null,
        val errorMessage: Event<String>? = null,
        val loadingMore: Event<Unit>? = null,
        val loadCompleted: Event<Unit>? = null,
        val dataUpdated: Event<DiffUtil.DiffResult>? = null
)

interface ListIntent {
    object Refresh: ListIntent
    object LoadMore: ListIntent
}
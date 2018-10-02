package com.duyp.architecture.clean.android.powergit.ui.base

import android.os.Bundle
import android.support.v4.widget.SwipeRefreshLayout
import android.support.v7.widget.RecyclerView
import android.view.View
import com.duyp.architecture.clean.android.powergit.R
import com.duyp.architecture.clean.android.powergit.event
import com.duyp.architecture.clean.android.powergit.showToastMessage
import com.duyp.architecture.clean.android.powergit.ui.widgets.recyclerview.scroll.InfiniteScroller
import com.duyp.architecture.clean.android.powergit.ui.widgets.recyclerview.scroll.RecyclerViewFastScroller
import kotlinx.android.synthetic.main.refresh_recycler_view.*

/**
 * Fragment shows a collection of data in [RecyclerView] with a [LoadMoreAdapter] and
 * [ListViewModel]
 *
 * This fragment contains a [RecyclerView] to show data, a [SwipeRefreshLayout] allowing user to pull down
 * to refresh, a [InfiniteScroller] allowing user to scroll down to load next page, a [RecyclerViewFastScroller]
 * allowing user to fast-scroll with a vertical scroller
 *
 * Please see [ListViewModel] to understand how data is stored and retrieved to be displayed in adapter, how intent
 * is sent to view model and how the view model manage view state of a list
 */
abstract class ListFragment<EntityType, ListType, A: LoadMoreAdapter, I: ListIntent, S, VM: ListViewModel<S, I, EntityType, ListType>>:
        ViewModelFragment<S, I, VM>() {

    private lateinit var mAdapter: A

    private lateinit var mInfiniteScroller: InfiniteScroller

    private var mIsRefreshing = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mAdapter = createAdapter(mViewModel)
        mInfiniteScroller = InfiniteScroller(mAdapter) { onIntent(mViewModel.getLoadMoreIntent()) }

        recyclerView.adapter = mAdapter
        recyclerView.setEmptyView(stateLayout, refreshLayout)
        recyclerView.addOnScrollListener(mInfiniteScroller)
        fastScroller.attachRecyclerView(recyclerView)
        refreshLayout.setOnRefreshListener { refresh() }
    }

    override fun getLayoutResource() = R.layout.refresh_recycler_view

    protected abstract fun createAdapter(data: AdapterData<EntityType>): A

    /**
     * Call this to update [ListState] for this fragment, normally called inside [withState]
     */
    protected fun onListStateUpdated(s: ListState) {
        setUiRefreshing(s.showLoading)

        event(s.refresh) { refresh() }

        event(s.loadCompleted) { onLoadCompleted() }

        event(s.loadingMore) { onLoadingMore() }

        event(s.errorMessage) { showErrorMessage(this) }

        if (s.showOfflineNotice) {
            showOfflineNotice()
        }
    }

    protected fun showOfflineNotice() {
        showToastMessage("Showing offline data")
    }

    protected fun showErrorMessage(message: String) {
        showToastMessage(message)
    }

    protected fun onLoadingMore() {
        mInfiniteScroller.setLoading()
    }

    private fun refresh() {
        if (!mIsRefreshing) {
            onIntent(mViewModel.getRefreshIntent())
            mInfiniteScroller.reset()
            mIsRefreshing = true
        }
    }

    private fun onLoadCompleted() {
        mInfiniteScroller.reset()
        mAdapter.notifyDataSetChanged()
        mIsRefreshing = false
    }

    private fun setUiRefreshing(refreshing: Boolean) {
        refreshLayout.post {
            refreshLayout.isRefreshing = refreshing
        }
    }
}
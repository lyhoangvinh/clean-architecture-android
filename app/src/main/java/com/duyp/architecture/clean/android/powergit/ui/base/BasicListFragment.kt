package com.duyp.architecture.clean.android.powergit.ui.base

import android.os.Bundle
import android.view.View
import com.duyp.architecture.clean.android.powergit.ui.base.adapter.BaseAdapter

/**
 * Basic fragment to shows a list on RecyclerView. Please see [BasicListViewModel] for more understanding.
 *
 * Used for a fragment which only show a list of data without any other states and intents except [ListState] and
 * [ListIntent]
 *
 * for more understanding, please see [ListFragment]
 */
abstract class BasicListFragment<
        EntityType,
        A: BaseAdapter<EntityType>,
        VM: BasicListViewModel<EntityType>>
    : ListFragment<EntityType, A, ListIntent, ListState, VM>() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        withState { onListStateUpdated(this) }
    }
}
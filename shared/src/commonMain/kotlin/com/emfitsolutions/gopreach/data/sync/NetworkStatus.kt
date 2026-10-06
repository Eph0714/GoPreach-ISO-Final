package com.emfitsolutions.gopreach.data.sync

import kotlinx.coroutines.flow.Flow

/** What repositories need to know about connectivity and sync progress (implemented per platform). */
interface NetworkStatus {
    /** True while a sync run is in progress. */
    val isSyncing: Flow<Boolean>
    fun isOnline(): Boolean
}

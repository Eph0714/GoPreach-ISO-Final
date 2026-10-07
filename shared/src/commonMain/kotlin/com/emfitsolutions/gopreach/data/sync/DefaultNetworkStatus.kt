package com.emfitsolutions.gopreach.data.sync

import kotlinx.coroutines.flow.Flow

/** [NetworkStatus] for every platform: sync progress from [SyncStatusCenter], reachability from the platform's [ConnectivityObserver]. */
class DefaultNetworkStatus(
    private val syncStatusCenter: SyncStatusCenter,
    private val connectivityObserver: ConnectivityObserver,
) : NetworkStatus {
    override val isSyncing: Flow<Boolean> get() = syncStatusCenter.isSyncing
    override fun isOnline(): Boolean = connectivityObserver.isOnline()
}

package com.emfitsolutions.gopreach.data.sync


/** Android-only: tells [SyncStatusCenter]/[SyncScheduler] about a queued write (the shared store can't know about them). */
class AndroidWriteQueuedListener(
    private val syncScheduler: SyncScheduler,
    private val syncStatusCenter: SyncStatusCenter,
    private val connectivityObserver: ConnectivityObserver,
) : WriteQueuedListener {
    override fun onWriteQueued() {
        syncStatusCenter.onWriteQueued(connectivityObserver.isOnline())
        syncScheduler.triggerSyncIfOnline()
    }
}


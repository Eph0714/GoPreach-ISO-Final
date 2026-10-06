package com.emfitsolutions.gopreach.data.sync

import kotlinx.coroutines.flow.Flow

/**
 * The app's one source of truth for "does this device currently have real, usable
 * internet". [isOnline] is always a fresh query (never cached); [observe] emits
 * every change. Android impl: `AndroidConnectivityObserver` (ConnectivityManager).
 */
interface ConnectivityObserver {
    fun isOnline(): Boolean
    fun observe(): Flow<Boolean>
}

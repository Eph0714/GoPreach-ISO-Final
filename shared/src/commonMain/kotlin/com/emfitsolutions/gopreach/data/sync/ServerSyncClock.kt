package com.emfitsolutions.gopreach.data.sync

import com.emfitsolutions.gopreach.platform.KeyValueStores
import com.emfitsolutions.gopreach.platform.edit
import com.emfitsolutions.gopreach.platform.nowMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * When this device last received data from the **server** (not just from its own cache). The Circuit Report shows it so
 * an overseer working offline knows how old the figures are. Persisted, so it survives a restart while offline.
 */
class ServerSyncClock(stores: KeyValueStores) {
    private val prefs = stores.open("gopreach_server_sync_clock")
    private val _lastSyncAt = MutableStateFlow(prefs.getLong(KEY, 0L))
    val lastSyncAt: StateFlow<Long> = _lastSyncAt

    /** Called whenever a server snapshot arrives; writes to disk at most every 15 seconds. */
    fun mark() {
        val now = nowMillis()
        if (now - _lastSyncAt.value < 15_000L) return
        _lastSyncAt.value = now
        prefs.edit { putLong(KEY, now) }
    }

    private companion object { const val KEY = "last_sync_at" }
}

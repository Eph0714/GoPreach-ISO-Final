package com.emfitsolutions.gopreach.data.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_get_main_queue

/**
 * Online/offline from the system's own path monitor (NWPathMonitor): "satisfied" means a usable route to the internet
 * exists. [isOnline] always returns the latest value the system reported; [observe] emits every change.
 */
class IosConnectivityObserver : ConnectivityObserver {
    private val state = MutableStateFlow(true)
    private val monitor = nw_path_monitor_create()

    init {
        nw_path_monitor_set_update_handler(monitor) { path ->
            state.value = path != null && nw_path_get_status(path) == nw_path_status_satisfied
        }
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_start(monitor)
    }

    override fun isOnline(): Boolean = state.value

    override fun observe(): Flow<Boolean> = state.asStateFlow()
}

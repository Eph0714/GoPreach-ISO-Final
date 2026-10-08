package com.emfitsolutions.gopreach.data.sync

/** True while one of the app's screens is visible. Background work that only matters to a person looking at the app (online presence) checks this so it stops costing server reads and writes the moment the app is not on screen. */
object AppForeground {
    @Volatile
    var visible: Boolean = false
}

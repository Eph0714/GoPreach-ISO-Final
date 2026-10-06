package com.emfitsolutions.gopreach.data.sync

/** Told whenever a local edit was queued for upload, so the platform can show "saved locally" and nudge a sync. */
fun interface WriteQueuedListener {
    fun onWriteQueued()
}

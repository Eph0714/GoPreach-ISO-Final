package com.emfitsolutions.gopreach.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** iOS has no system toast; messages are queued here and drawn by the shared `ToastHost` (added with the iOS app host). */
object ToastQueue {
    val pending = mutableListOf<String>()
}

@Composable
actual fun rememberToaster(): Toaster = remember { Toaster { message, _ -> ToastQueue.pending += message } }

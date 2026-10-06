package com.emfitsolutions.gopreach.platform

import androidx.compose.runtime.Composable

/** Shows a short, non-blocking message ("Saved", "Couldn't copy"). Android uses the system Toast. */
class Toaster(private val show: (message: String, long: Boolean) -> Unit) {
    operator fun invoke(message: String, long: Boolean = false) = show(message, long)
}

@Composable
expect fun rememberToaster(): Toaster

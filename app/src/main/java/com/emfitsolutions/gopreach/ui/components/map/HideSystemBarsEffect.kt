package com.emfitsolutions.gopreach.ui.components.map

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** While [hidden], hides the status and navigation bars (a swipe from the edge
 * shows them briefly); restores them when [hidden] turns false or this leaves
 * the composition. Works inside a `Dialog` (uses the dialog's own window) as
 * well as directly in the Activity — what "full screen" map modes use. */
@Composable
fun HideSystemBarsEffect(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(hidden, view) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (hidden) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if (hidden) controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

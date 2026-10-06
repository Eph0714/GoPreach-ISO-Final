package com.emfitsolutions.gopreach.platform

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberToaster(): Toaster {
    val context = LocalContext.current
    return remember(context) {
        Toaster { message, long -> Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
    }
}

package com.emfitsolutions.gopreach.ui.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * "Make a confirmation message to all action like 'Saving', editing,
 * deleting, sending" — a plain, non-suspend `(String) -> Unit` any
 * composable can call straight from a button's `onClick`, with no Scaffold/
 * SnackbarHost wiring needed per screen (a plain [Toast], not a Snackbar —
 * this app has dozens of independent Scaffolds, most without a
 * `snackbarHost` today, so a Toast is what reaches every one of them for
 * free). Every write in this app is local-first and succeeds instantly (see
 * [com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository]'s own
 * doc comments) — sync to the server happens later — so confirming right
 * after the ViewModel call, rather than waiting on a suspend result, matches
 * how the rest of the app already treats a save/delete/send as done.
 */
@Composable
fun rememberActionToast(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { message: String -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
}

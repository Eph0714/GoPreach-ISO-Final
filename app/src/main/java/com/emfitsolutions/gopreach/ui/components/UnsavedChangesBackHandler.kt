package com.emfitsolutions.gopreach.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties

/**
 * "Proper Back Button and Page Navigation Behavior" spec §4 — a form/enrollment
 * screen with unsaved data should never silently discard it on Back. This
 * intercepts **both** the system/gesture back action (via [BackHandler]) and
 * whatever in-screen "Back" affordance the caller wires to the returned
 * [GuardedBack.onBackPressed] (typically the top app bar's back arrow — routing
 * both through the same guard is the point, so a toolbar tap and the hardware
 * button behave identically), and only actually navigates back once the user
 * has explicitly chosen to via the "Unsaved Changes" dialog (or there was
 * nothing unsaved to begin with, in which case it navigates back immediately
 * with no dialog at all).
 *
 * [onSave] is optional — a screen only offers a "Save" action here when it has
 * one ready to call directly (its own already-validated save function); pass
 * null to show just Discard/Cancel, matching spec §4's dialog exactly when a
 * safe one-tap save isn't available.
 */
data class GuardedBack(val onBackPressed: () -> Unit)

@Composable
fun rememberUnsavedChangesBackHandler(
    hasUnsavedChanges: Boolean,
    onDiscard: () -> Unit,
    onSave: (() -> Unit)? = null,
): GuardedBack {
    var showDialog by remember { mutableStateOf(false) }

    val onBackPressed: () -> Unit = {
        if (hasUnsavedChanges) showDialog = true else onDiscard()
    }

    BackHandler(enabled = hasUnsavedChanges) { showDialog = true }

    if (showDialog) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { showDialog = false },
            title = { Text("Unsaved Changes") },
            text = { Text("You have changes that have not been saved.") },
            confirmButton = {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showDialog = false }) { Text("CANCEL") }
                    TextButton(onClick = { showDialog = false; onDiscard() }) { Text("DISCARD") }
                    if (onSave != null) {
                        Button(onClick = { showDialog = false; onSave() }) { Text("SAVE") }
                    }
                }
            },
        )
    }

    return GuardedBack(onBackPressed)
}

package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * The "Admin Record Deletion and Inactive Status" spec's replacement for the
 * old `Delete -> Immediately Delete` pattern used across every Manage screen
 * (Admins, Elders, Publishers, Groups, Congregations, Users, Interested
 * People): tapping Delete now always asks *what kind* of deletion is meant,
 * and Permanent Delete always gets its own second, harder-to-hit
 * confirmation.
 *
 * [canPermanentlyDelete] gates the option entirely (spec §7: a user without
 * permanent-delete permission must not be able to reach it at all, not just
 * have it hidden-but-reachable) — callers pass `isSuperAdmin` today, since
 * permanent deletion is deliberately restricted to the top of the role
 * hierarchy across every record type in this pass (see BUILD_PLAN.md for
 * that scoping decision). [permanentDeleteBlockedReason], when non-null,
 * replaces the second step's delete button with an explanation instead —
 * used when a relationship check (spec §4) found dependent records that
 * would otherwise be silently destroyed. [permanentDeleteImpactSummary],
 * when non-null, is purely informational — appended to the second step's
 * message to name what else gets cascaded away — and never disables the
 * Delete Permanently button the way [permanentDeleteBlockedReason] does.
 */
@Composable
fun DeleteChoiceDialog(
    recordLabel: String,
    canPermanentlyDelete: Boolean,
    onDismiss: () -> Unit,
    onMoveToInactive: () -> Unit,
    onDeletePermanently: () -> Unit,
    permanentDeleteBlockedReason: String? = null,
    permanentDeleteImpactSummary: String? = null,
    /** Non-null turns the second confirmation into a strong warning: this title, [permanentDeleteImpactSummary] as the
     * whole body, and [permanentConfirmLabel] on the confirm button. */
    permanentWarningTitle: String? = null,
    permanentConfirmLabel: String? = null,
) {
    var showPermanentConfirm by remember { mutableStateOf(false) }
    // "Prevent Double Submission" — every Manage screen's Delete flow goes
    // through this one shared dialog, so the guard lives here once. Latches
    // on the first tap of either action (a fresh instance of this composable
    // is created each time a caller shows it, so this always starts unarmed);
    // there's nothing to "re-arm on failure" here the way FormDialog's Save
    // needs — [onMoveToInactive]/[onDeletePermanently] are fire-and-forget
    // local-cache writes that don't report failure back to this dialog, and
    // it dismisses itself immediately after either one anyway.
    var hasActed by remember { mutableStateOf(false) }
    val showToast = rememberActionToast()

    if (!showPermanentConfirm) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = onDismiss,
            title = { Text("What would you like to do with \"$recordLabel\"?") },
            text = {
                Text(
                    "Move to Inactive keeps the record and its history, hidden from normal active lists, and can be restored later. " +
                        "Delete moves the record to Deleted Records, where it can be restored or permanently deleted.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    Button(
                        onClick = {
                            if (!hasActed) {
                                hasActed = true
                                onMoveToInactive()
                                showToast("\"$recordLabel\" moved to Inactive.")
                                onDismiss()
                            }
                        },
                        enabled = !hasActed,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Move to Inactive") }
                    if (canPermanentlyDelete) {
                        OutlinedButton(
                            onClick = { showPermanentConfirm = true },
                            enabled = !hasActed,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) { Text("Delete") }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss, enabled = !hasActed) { Text("Cancel") }
            },
        )
    } else {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = onDismiss,
            title = { Text(permanentWarningTitle ?: "Move this record to Deleted Records?") },
            text = {
                Text(
                    permanentDeleteBlockedReason
                        ?: if (permanentWarningTitle != null) permanentDeleteImpactSummary.orEmpty() else listOfNotNull(
                            "\"$recordLabel\" will be moved to Deleted Records. You can restore it, or delete it permanently, from there.",
                            permanentDeleteImpactSummary,
                        ).joinToString(" "),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            // Both buttons live in this one slot, stacked and centered, rather
            // than the default confirm/dismiss split (confirm right, dismiss
            // left) — Delete Permanently on top, Cancel/Close beneath it at
            // bottom center, so the safer action reads as the deliberate,
            // separate last step it is rather than a same-row afterthought.
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (permanentDeleteBlockedReason == null) {
                        Button(
                            onClick = {
                                if (!hasActed) {
                                    hasActed = true
                                    onDeletePermanently()
                                    showToast(if (permanentWarningTitle != null) "\"$recordLabel\" deleted. Their records are now unassigned." else "\"$recordLabel\" moved to Deleted Records.")
                                    onDismiss()
                                }
                            },
                            enabled = !hasActed,
                        ) { Text(permanentConfirmLabel ?: "Move to Deleted Records") }
                    }
                    TextButton(
                        onClick = { if (permanentDeleteBlockedReason != null) onDismiss() else showPermanentConfirm = false },
                        enabled = !hasActed,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(if (permanentDeleteBlockedReason != null) "Close" else "Cancel")
                    }
                }
            },
        )
    }
}

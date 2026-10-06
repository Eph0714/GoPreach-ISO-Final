package com.emfitsolutions.gopreach.ui.components

import com.emfitsolutions.gopreach.shared.resources.Res
import com.emfitsolutions.gopreach.shared.resources.action_cancel
import com.emfitsolutions.gopreach.shared.resources.action_discard
import com.emfitsolutions.gopreach.shared.resources.action_keep_editing
import com.emfitsolutions.gopreach.shared.resources.action_save
import com.emfitsolutions.gopreach.shared.resources.discard_changes_message
import com.emfitsolutions.gopreach.shared.resources.discard_changes_title
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * "Do not allow the keyboard to override the button when saving. Always
 * show all the text and button at the top of keyboard" — Material3's own
 * `AlertDialog` renders `confirmButton`/`dismissButton` in a footer
 * *outside* the scrollable `text` slot, so a `Modifier.imePadding()` on
 * that slot alone (this app's original per-dialog keyboard fix, still
 * correct for the fields themselves) keeps text fields clear of the
 * keyboard but does nothing for those buttons — nothing ties their
 * position to the keyboard's height, so a tall form can still push Save
 * down behind it. [FormDialog] folds the action row into the *same*
 * imePadding-aware, scrollable Column as the fields (Material3's own
 * confirmButton/dismissButton slots are left empty), so the buttons are
 * always part of the one region guaranteed to sit above the IME — scroll to
 * see them on a long form, but they're never hidden behind the keyboard.
 *
 * [errorMessage] — "make an action message for every required text field
 * that is not filled up when saving" — shown just above the action row
 * (e.g. via [requiredFieldsMessage]); null hides it entirely. Confirm stays
 * enabled even while it's showing (unlike a hard [confirmEnabled] gate) so
 * tapping Save with something missing always produces this message instead
 * of just silently doing nothing — the caller's onConfirm is expected to
 * re-check and only actually save when there's nothing left to report.
 *
 * "Prevent Double Submission" — every one of this app's dozens of Save/Add/
 * Update dialogs goes through this one Confirm button, so the guard lives
 * here once instead of being re-implemented per call site: the first tap
 * disables the button (via [hasConfirmed], reset fresh every time this
 * composable enters composition — i.e. every time the dialog is newly
 * shown) so a rapid double-tap can never fire [onConfirm] twice, even
 * though most callers' own save is a synchronous, effectively-instant
 * write to the local offline cache and simply dismiss this dialog
 * immediately afterward rather than awaiting a network round-trip (see
 * OfflineFirestoreRepository's own doc comments) — that near-instant
 * dismissal still leaves a real, if narrow, multi-tap-in-one-frame window
 * this closes. A validation failure ([errorMessage] appearing) is not a
 * real submission, so [hasConfirmed] only latches once [onConfirm] itself
 * is actually invoked — the caller is still free to tap Save again right
 * away after fixing the flagged field.
 *
 * "Cancel Action" spec §5 — [hasUnsavedChanges] is the one thing only the
 * caller can know (it owns every field's `remember`ed state, this composable
 * owns none of it): pass a live `true`/`false` — typically "does any field
 * still differ from what it started as" — and tapping Cancel (or dismissing
 * via backdrop tap/system back, both of which already route through
 * [onDismissRequest] the exact same way) shows a "Discard changes?" /
 * "Keep Editing" | "Discard" confirmation first instead of closing straight
 * away; [onDismissRequest] itself only ever runs once that's confirmed (or
 * was never needed because nothing changed). Defaults to `false` — every
 * existing call site that doesn't pass it keeps its exact old
 * dismiss-immediately behavior, opting in is additive.
 */
@Composable
fun FormDialog(
    onDismissRequest: () -> Unit,
    title: String,
    onConfirm: () -> Unit,
    // Sourced from strings.xml rather than a literal, so every one of this
    // app's dozens of FormDialog call sites that don't override these stays
    // in sync with a single copy of the label text.
    confirmLabel: String = stringResource(Res.string.action_save),
    dismissLabel: String = stringResource(Res.string.action_cancel),
    confirmEnabled: Boolean = true,
    errorMessage: String? = null,
    maxContentHeight: Dp = 480.dp,
    hasUnsavedChanges: Boolean = false,
    content: @Composable () -> Unit,
) {
    var hasConfirmed by remember { mutableStateOf(false) }
    // [errorMessage] here is the CALLER's own state — it only actually
    // changes to non-null on the recomposition *after* [onConfirm] set it
    // (checking it synchronously inside the onClick below would only ever
    // see the value from before this click, since Compose state updates
    // apply on the next recomposition, not mid-callback). Re-arming here
    // reacts to that later recomposition correctly.
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) hasConfirmed = false
    }

    var showDiscardConfirm by remember { mutableStateOf(false) }
    fun requestDismiss() {
        if (hasUnsavedChanges) showDiscardConfirm = true else onDismissRequest()
    }

    AlertDialog(
        onDismissRequest = ::requestDismiss,
        // "Outside clicks must be ignored for dismissal purposes" — every
        // Add/Edit form in the app goes through this one composable, so this
        // is the single highest-leverage fix: Compose's own default
        // (`DialogProperties()`) treats a tap outside the dialog exactly
        // like tapping Cancel, which used to silently discard whatever the
        // Publisher had typed (or, worse, sometimes skip the "Discard
        // changes?" prompt entirely — see [requestDismiss]). `dismissOnBackPress`
        // stays `true`: the system Back gesture/button is still a legitimate,
        // *intentional* way to leave a form (spec's own "Back, where
        // appropriate"), and still goes through the exact same
        // [requestDismiss] → unsaved-changes-confirmation path as the
        // Cancel button always has.
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    // Never taller than the screen allows (landscape / small phones): scroll inside instead of overflowing.
                    .heightIn(max = minOf(maxContentHeight, (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.62f).dp))
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
                if (errorMessage != null) {
                    Text(errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = ::requestDismiss, enabled = !hasConfirmed) { Text(dismissLabel) }
                    TextButton(
                        onClick = {
                            if (!hasConfirmed) {
                                hasConfirmed = true
                                onConfirm()
                            }
                        },
                        enabled = confirmEnabled && !hasConfirmed,
                    ) { Text(confirmLabel) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
    )

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            // Same rule applied to the confirmation prompt itself — an
            // accidental outside tap here must not silently pick either
            // side of "Discard changes? / Keep Editing"; it just re-arms
            // for an explicit tap on one of those two buttons (the
            // underlying form's data is untouched either way).
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            title = { Text(stringResource(Res.string.discard_changes_title)) },
            text = { Text(stringResource(Res.string.discard_changes_message)) },
            confirmButton = {
                TextButton(onClick = { showDiscardConfirm = false; onDismissRequest() }) {
                    Text(stringResource(Res.string.action_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) { Text(stringResource(Res.string.action_keep_editing)) }
            },
        )
    }
}

/**
 * "Make an action message for every required text field that is not filled
 * up when saving" — pass each required field as (human-readable label, is
 * it actually filled in); returns "X is required."/"X, Y are required." for
 * whichever ones aren't, or null once every one of them is. Meant to be
 * recomputed on every Save tap (see [FormDialog.onConfirm]) rather than
 * live on every keystroke, so the message only appears once the publisher
 * actually tries to save, not while they're still mid-typing the first field.
 */
fun requiredFieldsMessage(vararg fields: Pair<String, Boolean>): String? {
    val missing = fields.filter { !it.second }.map { it.first }
    if (missing.isEmpty()) return null
    return "${missing.joinToString(", ")} ${if (missing.size == 1) "is" else "are"} required."
}

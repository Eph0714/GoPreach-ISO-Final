package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.emfitsolutions.gopreach.data.sync.ConnectivityObserver
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.SyncScheduler
import com.emfitsolutions.gopreach.data.sync.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.ui.window.DialogProperties

/** What the "SYNC TO SERVER" button is doing right now — separate from the passive
 * pending-count indicator ([SyncStatusButton]), this only reflects a run the user
 * explicitly triggered by tapping the button. */
sealed class ManualSyncState {
    data object Idle : ManualSyncState()
    data object NoNetwork : ManualSyncState()
    data class Syncing(val done: Int, val total: Int) : ManualSyncState()
    data class Summary(val uploaded: Int, val failed: Int) : ManualSyncState()
    /** Couldn't run at all (e.g. the connection dropped mid-sync) — distinct from
     * [Summary] with a nonzero [Summary.failed], which means it ran but some
     * individual records were rejected. */
    data object Failed : ManualSyncState()
}

/** "There is 1 change" / "There are 5 changes" (spec §10/§11 — the wording is
 * explicitly singular/plural, not just "N change(s)"). */
fun pendingChangesPhrase(count: Int): String =
    if (count == 1) "There is 1 change made that needs to sync into the server."
    else "There are $count changes made that need to sync into the server."

class ManualSyncViewModel(
    private val connectivityObserver: ConnectivityObserver,
    private val syncScheduler: SyncScheduler,
    private val offlineFirestoreRepository: OfflineFirestoreRepository,
) : ViewModel() {

    val pendingCount: StateFlow<Int> = offlineFirestoreRepository.observePendingSyncCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _uiState = MutableStateFlow<ManualSyncState>(ManualSyncState.Idle)
    val uiState: StateFlow<ManualSyncState> = _uiState

    fun dismissDialog() {
        _uiState.value = ManualSyncState.Idle
    }

    /** Spec §5/§8: check connectivity and reject immediately (no silent queueing
     * for later) if offline; a click while already syncing is a no-op — the
     * button itself is disabled for that same reason, but this guards the
     * ViewModel side too in case something else ever calls it directly. Then
     * kicks off [SyncScheduler.requestSyncNow] and follows *that exact request's
     * id* — not "whatever the unique work name currently shows" — until it
     * reports finished (see [SyncScheduler.requestSyncNow]'s doc comment for
     * why the id-based tracking is what actually fixes the stuck-forever bug,
     * not just switching to `REPLACE`). */
    fun syncToServer() {
        if (_uiState.value is ManualSyncState.Syncing) return
        if (!connectivityObserver.isOnline()) {
            _uiState.value = ManualSyncState.NoNetwork
            return
        }
        _uiState.value = ManualSyncState.Syncing(done = 0, total = 0)
        viewModelScope.launch {
            // "Fix it at once in clicking Sync to Server" — previously-rejected
            // changes (e.g. PERMISSION_DENIED before a rules fix) go back into
            // the queue so this one tap re-attempts them along with the rest.
            offlineFirestoreRepository.retryAllPermanentSyncFailures()
            val requestId = syncScheduler.requestSyncNow()
            syncScheduler.observeWorkInfo(requestId).collect { info ->
                if (info == null) return@collect
                val progress = info.progress
                val finished = progress.getBoolean(SyncWorker.KEY_FINISHED, false)
                if (finished) {
                    // SyncWorker's own mandatory connectivity gate skipped this run
                    // (no internet at start, or connectivity dropped mid-run) --
                    // show the same "no internet" state the pre-flight check above
                    // would have, never a false "Sync Complete"/"0 failed" summary
                    // for a run that never actually reached the server.
                    _uiState.value = if (progress.getBoolean(SyncWorker.KEY_SKIPPED_OFFLINE, false)) {
                        ManualSyncState.NoNetwork
                    } else {
                        ManualSyncState.Summary(
                            uploaded = progress.getInt(SyncWorker.KEY_UPLOADED, 0),
                            failed = progress.getInt(SyncWorker.KEY_FAILED, 0),
                        )
                    }
                    return@collect
                }
                when (info.state) {
                    WorkInfo.State.RUNNING -> _uiState.value = ManualSyncState.Syncing(
                        done = progress.getInt("done", 0),
                        total = progress.getInt(SyncWorker.KEY_TOTAL, 0),
                    )
                    WorkInfo.State.CANCELLED, WorkInfo.State.FAILED -> _uiState.value = ManualSyncState.Failed
                    // A defensive fallback, not the normal path: SUCCEEDED should
                    // already have been caught by the KEY_FINISHED progress check
                    // above. If it's ever reached here anyway (progress data missing
                    // for some reason), still resolve the UI out of Syncing rather
                    // than leaving the button stuck forever on "Checking for pending
                    // changes..." — this was a real bug (a stale WorkInfo from a past
                    // run, before requestSyncNow() switched to ExistingWorkPolicy
                    // .REPLACE, could report SUCCEEDED with none of this run's data).
                    WorkInfo.State.SUCCEEDED -> _uiState.value = ManualSyncState.Summary(uploaded = 0, failed = 0)
                    else -> Unit
                }
            }
        }
    }
}

/**
 * The Main Form's primary, explicit "[ SYNC TO SERVER ]" action — kept
 * completely independent of Refresh and of app-update checking (spec §18).
 * The app also syncs automatically now whenever internet/mobile data is
 * available (see [com.emfitsolutions.gopreach.data.sync.SyncScheduler
 * .ensureAutomaticSyncStarted]); this button is the tracked, user-visible
 * path — the one that shows real, measured progress ("SYNCING 47%", spec
 * §6) computed from actual completed/total operations, and is disabled so a
 * second tap can't start an overlapping run (spec §8) — not the only thing
 * that ever uploads a pending change anymore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncToServerButton(
    viewModel: ManualSyncViewModel = koinViewModel(),
    /** False when the caller places its own [SyncStatusIndicator] elsewhere
     * (e.g. PublisherHomeScreen puts it at the very top of the "Keep Your
     * Data Safe" card, above the title, so the Online/Offline + Online
     * Users badges are the first thing visible there instead of sitting
     * just above the button, lower in the card) — this button then renders
     * only the actual "SYNC TO SERVER" control, never a second copy of the
     * same indicator. */
    showStatusIndicator: Boolean = true,
    /** "Make the sync to server smaller, put it on the right upper side,
     * make it simple" — a single small icon button instead of the full
     * width/title/status-line/progress-bar layout, for placing directly in
     * a header's icon row. Still the same [viewModel]/dialogs underneath —
     * only the everyday-visible chrome shrinks, not the actual sync
     * behavior or its error/summary feedback. */
    compact: Boolean = false,
    tint: Color = Color.White,
) {
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val syncing = state as? ManualSyncState.Syncing

    if (compact) {
        Box {
            IconButton(onClick = viewModel::syncToServer, enabled = syncing == null) {
                if (syncing != null) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = tint, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.CloudUpload, contentDescription = "Sync to Server", tint = tint)
                }
            }
            // A small dot instead of a numeric badge — "make it simple,"
            // just enough to notice there's something pending without the
            // full pendingChangesPhrase() sentence this button shows
            // elsewhere.
            if (pendingCount > 0 && syncing == null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 6.dp)
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                )
            }
        }
        SyncFeedbackDialogs(state = state, pendingCount = pendingCount, onDismiss = viewModel::dismissDialog, onRetry = viewModel::syncToServer)
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Real-time 🟢/🔴/🟡/⚠️ status — reflects the sync system as a whole
        // (including automatic background syncs), not just this button's own
        // manually-triggered runs.
        if (showStatusIndicator) {
            SyncStatusIndicator(modifier = Modifier.padding(bottom = 4.dp))
        }

        Text(
            "Hostinger Server Used for this version",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )

        Button(
            onClick = viewModel::syncToServer,
            enabled = syncing == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (syncing == null) {
                Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            }
            Text(
                if (syncing != null && syncing.total > 0) {
                    "SYNCING ${(syncing.done * 100 / syncing.total)}%"
                } else if (syncing != null) {
                    "SYNCING..."
                } else {
                    "SYNC TO SERVER"
                },
            )
        }

        // Spec §7/§13 — a small status line under the button: live upload
        // progress while syncing, otherwise the dynamic pending-changes count
        // (or a plain "synced" confirmation when there's nothing pending).
        val statusText = when {
            syncing != null && syncing.total > 0 -> "Uploading changes: ${syncing.done} / ${syncing.total}"
            syncing != null -> "Checking for pending changes..."
            state is ManualSyncState.Failed -> "Sync Failed – Try Again"
            pendingCount > 0 -> pendingChangesPhrase(pendingCount)
            else -> "✓ All changes synced"
        }
        Text(
            statusText,
            style = MaterialTheme.typography.bodySmall,
            color = if (state is ManualSyncState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
        )
        if (syncing != null && syncing.total > 0) {
            LinearProgressIndicator(
                progress = { syncing.done.toFloat() / syncing.total.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }

    SyncFeedbackDialogs(state = state, pendingCount = pendingCount, onDismiss = viewModel::dismissDialog, onRetry = viewModel::syncToServer)
}

/** The NoNetwork/Summary feedback dialogs — shared by both the full and
 * [SyncToServerButton]'s `compact` layout, so shrinking the everyday-visible
 * button never means losing the error/result feedback underneath it. */
@Composable
private fun SyncFeedbackDialogs(state: ManualSyncState, pendingCount: Int, onDismiss: () -> Unit, onRetry: () -> Unit) {
    when (val s = state) {
        ManualSyncState.NoNetwork -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Sync Failed") },
            text = {
                Text(
                    "No internet connection.\n\n" +
                        "${pendingChangesPhrase(pendingCount).removeSuffix(".")} stored safely on this device and " +
                        "will sync automatically once you're back online.",
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
        is ManualSyncState.Syncing -> Unit // reflected in the button/status line above, no blocking dialog
        ManualSyncState.Failed -> Unit // reflected in the status line above
        is ManualSyncState.Summary -> AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    when {
                        s.failed == 0 -> "Sync Complete"
                        s.uploaded == 0 -> "Sync Failed"
                        else -> "Sync Partially Complete"
                    },
                )
            },
            text = {
                Text(
                    if (s.failed == 0) {
                        "All changes have been successfully synchronized with the server."
                    } else {
                        "${s.uploaded} changes synchronized.\n${s.failed} changes still need synchronization."
                    },
                    color = if (s.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
            dismissButton = if (s.failed > 0) {
                { TextButton(onClick = { onDismiss(); onRetry() }) { Text("Retry") } }
            } else null,
        )
        ManualSyncState.Idle -> Unit
    }
}

package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.local.PendingSyncOperationEntity
import com.emfitsolutions.gopreach.data.sync.ConnectivityObserver
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.SyncScheduler
import com.emfitsolutions.gopreach.data.sync.SyncStatusCenter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncStatusIndicatorViewModel @Inject constructor(
    connectivityObserver: ConnectivityObserver,
    syncStatusCenter: SyncStatusCenter,
    private val offlineFirestoreRepository: OfflineFirestoreRepository,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {
    /** "GoPreach — Fix Online/Offline Status and Sync Indicator" spec §3/§4 —
     * real, validated internet connectivity, independent of whatever the sync
     * queue is doing (spec §6). */
    val isOnline: StateFlow<Boolean> = connectivityObserver.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), connectivityObserver.isOnline())

    /** A completely separate concern from [isOnline] — see this file's own
     * [SyncStatusIndicator] doc comment for why these are two distinct pieces
     * of UI rather than one conflated badge. */
    val permanentFailureCount: StateFlow<Int> = syncStatusCenter.permanentFailureCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Bug fix — "Sync Error" used to be a dead end with nothing to actually
     * look at; this is the list the recovery dialog below shows. */
    val permanentFailures: StateFlow<List<PendingSyncOperationEntity>> = offlineFirestoreRepository.observePermanentSyncFailures()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun retry(operation: PendingSyncOperationEntity) {
        viewModelScope.launch {
            offlineFirestoreRepository.retryPermanentSyncFailure(operation)
            // Same trigger a fresh write already gets — no reason to make the
            // Publisher separately open "Sync to Server" after choosing Retry.
            syncScheduler.triggerSyncIfOnline()
        }
    }

    fun discard(operation: PendingSyncOperationEntity) {
        viewModelScope.launch { offlineFirestoreRepository.discardPermanentSyncFailure(operation) }
    }
}

/**
 * The Main Form's real-time connection indicator — "GoPreach — Fix Online/Offline
 * Status and Sync Indicator" spec: shows **only** whether this device currently
 * has validated internet connectivity ([ConnectivityObserver]), updating
 * automatically the instant that changes (spec §4), never derived from — or
 * described in terms of — sync completion (spec §6/§8: "do not use the
 * synchronization result as the online/offline indicator"; "do not show Online
 * merely because Wi-Fi/mobile data is turned on"). This replaces the old badge
 * that rendered "✓ All changes synchronized" / "Sync temporarily unavailable" /
 * etc. here — that text conflated two different questions ("is there a
 * connection" vs. "is local data synced with the server") into one line.
 *
 * A permanent sync failure ("Sync Error") still needs a way back to the
 * recovery dialog (see [SyncErrorDetailsDialog]) — that's kept, but as its own,
 * clearly separate, secondary bit of text next to the Online/Offline indicator,
 * not folded into it, so the two states can never be mistaken for each other.
 */
@Composable
fun SyncStatusIndicator(
    modifier: Modifier = Modifier,
    viewModel: SyncStatusIndicatorViewModel = hiltViewModel(),
) {
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val permanentFailureCount by viewModel.permanentFailureCount.collectAsStateWithLifecycle()
    var showDetails by remember { mutableStateOf(false) }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(if (isOnline) "🟢" else "🔴", modifier = Modifier.padding(end = 6.dp))
        Text(
            if (isOnline) "Online" else "Offline",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // "Put the Online Users just next to Online Indicator" — same Row,
        // right alongside the connectivity badge, rather than its own
        // separate line elsewhere on the Main Form.
        OnlineUsersIndicator(modifier = Modifier.padding(start = 16.dp))
        if (permanentFailureCount > 0) {
            Text(
                "  ⚠️ Sync error — $permanentFailureCount need${if (permanentFailureCount == 1) "s" else ""} attention (tap for details)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable { showDetails = true },
            )
        }
    }

    if (showDetails) {
        SyncErrorDetailsDialog(viewModel = viewModel, onDismiss = { showDetails = false })
    }
}

@Composable
private fun SyncErrorDetailsDialog(viewModel: SyncStatusIndicatorViewModel, onDismiss: () -> Unit) {
    val failures by viewModel.permanentFailures.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
        title = { Text("Sync Error") },
        text = {
            if (failures.isEmpty()) {
                Text("Nothing needs attention anymore.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "These changes were saved on this device but the server rejected them for a reason that won't fix itself by simply retrying (for example, a permission or data problem). Retry if you believe the underlying issue has been fixed; Discard if this change should be given up on for good.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(failures, key = { it.id }) { failure ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("${failure.collectionPath} — ${failure.operationType}", style = MaterialTheme.typography.labelMedium)
                                    Text(
                                        failure.lastError ?: "Unknown error",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                        TextButton(onClick = { viewModel.discard(failure) }) { Text("Discard") }
                                        TextButton(onClick = { viewModel.retry(failure) }) { Text("Retry") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

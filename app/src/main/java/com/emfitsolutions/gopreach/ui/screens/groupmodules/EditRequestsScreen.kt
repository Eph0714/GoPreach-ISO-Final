package com.emfitsolutions.gopreach.ui.screens.groupmodules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.MonthlyReport
import com.emfitsolutions.gopreach.ui.components.Scaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.koin.compose.viewmodel.koinViewModel

private val stamp get() = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
private val monthFmt get() = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

/**
 * FS Group Report Edit Requests (Congregation Management modules): the pending requests of the publishers in scope with Approve / Reject and remarks, and the request history. An FS Group
 * officer sees their own group's publishers only; [readsAll] (Admin, Coordinator Elder, Service Overseer, Secretary, Super Admin) keeps the whole congregation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditRequestsScreen(
    currentPersonId: String,
    congregationId: String?,
    readsAll: Boolean,
    onBack: () -> Unit,
    viewModel: EditRequestsViewModel = koinViewModel(),
) {
    val flow = remember(currentPersonId, congregationId, readsAll) { viewModel.state(currentPersonId, congregationId, readsAll) }
    val state by flow.collectAsStateWithLifecycle(initialValue = EditRequestsState())
    var tab by rememberSaveable { mutableStateOf(0) }
    var deciding by remember { mutableStateOf<Pair<MonthlyReport, Boolean>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FS Group Report Edit Requests") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        if (state.slot == null && !readsAll) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(
                    if (state.loaded) "Report edit requests are kept by the Overseer, Servant and Assistant of an FS Group for their own group, and by the Admin, Coordinator Elder, Service Overseer and Secretary."
                    else "Loading...",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.slot?.takeIf { !readsAll }?.let {
                            Text(it.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text(it.group.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        }
                        Text(
                            "A publisher asked to edit a Field Service Report that was already sent. Approving only opens the report for its publisher: nothing in it is changed.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Pending (${state.pending.size})") })
                    FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("History") })
                }
            }
            if (tab == 0) {
                if (state.pending.isEmpty()) item { Text("No pending edit requests.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(state.pending, key = { it.id }) { r ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(state.names[r.publisherPersonId].orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Report: ${monthFmt.format(Date(r.periodMonth))}", style = MaterialTheme.typography.bodyMedium)
                            r.accessRequestedAt?.let { Text("Requested ${stamp.format(Date(it))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text("Reason: ${r.accessRequestReason.orEmpty().ifBlank { "—" }}", style = MaterialTheme.typography.bodyMedium)
                            if (r.publisherPersonId == currentPersonId) Text("Your own request: another officer or the congregation roles decide it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else Row {
                                TextButton(onClick = { deciding = r to true }) { Text("Approve") }
                                TextButton(onClick = { deciding = r to false }) { Text("Reject", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            } else {
                if (state.history.isEmpty()) item { Text("No requests yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(state.history, key = { it.timestamp.toString() + it.what + it.publisher }) { h ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${h.what} · ${h.publisher}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("${monthFmt.format(Date(h.month))} · ${stamp.format(Date(h.timestamp))} · ${h.by}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (h.text.isNotBlank()) Text(h.text, style = MaterialTheme.typography.bodyMedium)
                            Text("Now: ${h.status.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }

    deciding?.let { (report, approve) ->
        var remarks by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { deciding = null },
            title = { Text(if (approve) "Approve the request of ${state.names[report.publisherPersonId].orEmpty()}?" else "Reject the request of ${state.names[report.publisherPersonId].orEmpty()}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (approve) "The publisher will be able to correct the ${monthFmt.format(Date(report.periodMonth))} report and send it again. Nothing in the report is changed now."
                        else "The report stays submitted and locked.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(value = remarks, onValueChange = { remarks = it.take(500) }, label = { Text("Remarks (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deciding = null
                    viewModel.decide(report, approve, remarks, currentPersonId) { error ->
                        message = error ?: if (approve) "Edit access was granted. The publisher can now correct the report." else "The request was rejected. The report stays submitted."
                    }
                }) { Text(if (approve) "Approve" else "Reject") }
            },
            dismissButton = { TextButton(onClick = { deciding = null }) { Text("Cancel") } },
        )
    }
}

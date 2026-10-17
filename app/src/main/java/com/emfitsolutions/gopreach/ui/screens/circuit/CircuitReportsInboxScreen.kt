package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.compose.foundation.layout.Arrangement
import com.emfitsolutions.gopreach.ui.components.RecordViewToggle
import com.emfitsolutions.gopreach.ui.components.UniversalColumn
import com.emfitsolutions.gopreach.ui.components.recordItems
import com.emfitsolutions.gopreach.ui.components.rememberRecordViewMode
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import com.emfitsolutions.gopreach.ui.components.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.emfitsolutions.gopreach.ui.components.Scaffold
import com.emfitsolutions.gopreach.ui.components.HideInFullScreen
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.CoMonthStatus
import com.emfitsolutions.gopreach.data.model.CoReportStatus
import com.emfitsolutions.gopreach.data.repository.messageOrNull
import com.emfitsolutions.gopreach.ui.components.co.CoButton
import com.emfitsolutions.gopreach.ui.components.co.CoCard
import com.emfitsolutions.gopreach.ui.components.co.CoKind
import com.emfitsolutions.gopreach.ui.components.co.CoStatusBadge
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun monthName(millis: Long): String = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(millis))
private fun stamp(millis: Long): String = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(millis))

private enum class InboxFilter(val label: String, val matches: (CoReportStatus) -> Boolean) {
    ALL("All Reports", { true }),
    AWAITING("Awaiting Receipt", { it == CoReportStatus.SUBMITTED }),
    RECEIVED("Received", { it == CoReportStatus.RECEIVED }),
    RETURNED("Returned for Editing", { it == CoReportStatus.RETURNED }),
    CANCELLED("Cancelled", { it == CoReportStatus.CANCELLED }),
}

internal fun badgeKind(status: CoReportStatus): CoKind = when (status) {
    CoReportStatus.RECEIVED -> CoKind.Success
    CoReportStatus.RETURNED -> CoKind.Warning
    CoReportStatus.CANCELLED -> CoKind.Danger
    else -> CoKind.Primary
}

/**
 * Circuit Overseer -> Circuit Reports: every report the overseer's congregations sent, one card per congregation month (its CURRENT
 * version), with filters and search, the read-only snapshot viewer, and the actions Mark as Received / Return for Editing / Cancel
 * Submission. The snapshot is what the congregation sent - never its live records.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircuitReportsInboxScreen(
    currentPersonId: String,
    onBack: () -> Unit,
    onOpenSnapshot: (reportId: String) -> Unit,
    viewModel: CircuitReportsInboxViewModel = koinViewModel(),
    people: CircuitPeopleViewModel = koinViewModel(),
) {
    val congregations by remember(currentPersonId) { people.congregations(currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val ids = remember(congregations) { congregations.map { it.id }.toSet() }
    val allItems by remember(ids, currentPersonId) { viewModel.items(ids, currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    // A congregation is ALWAYS chosen first, every time this section is opened: nothing is listed (or loaded for display) before that, and
    // only the chosen congregation's reports are ever shown, so reports of different congregations can never be mixed.
    var chosenId by rememberSaveable { mutableStateOf<String?>(null) }
    val chosen = congregations.firstOrNull { it.id == chosenId }
    val items = if (chosen == null) emptyList() else allItems.filter { it.status.congregationId == chosen.id }
    var filter by rememberSaveable { mutableStateOf(InboxFilter.ALL.name) }
    var query by rememberSaveable { mutableStateOf("") }
    val nameOf = { id: String -> congregations.firstOrNull { it.id == id }?.name ?: id }

    // Newest reporting period first, or the most recently submitted / acted-on first.
    var sortByPeriod by rememberSaveable { mutableStateOf(true) }
    val shown = items.filter { item ->
        InboxFilter.valueOf(filter).matches(item.status.status) &&
            (query.isBlank() || listOf(nameOf(item.status.congregationId), monthName(item.status.periodMonth), item.status.status.label, item.status.status.workflowLabel)
                .any { it.contains(query.trim(), ignoreCase = true) })
    }.let { list ->
        if (sortByPeriod) list.sortedWith(compareByDescending<InboxItem> { it.status.periodMonth }.thenByDescending { it.lastActionAt })
        else list.sortedByDescending { it.lastActionAt }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Field Service Report") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        if (chosen == null) {
            SelectCongregationPrompt(
                congregations = congregations, onSelect = { chosenId = it; filter = InboxFilter.ALL.name; query = "" },
                modifier = Modifier.padding(padding),
                hint = "Choose a congregation to review the Field Service Reports it sent you.",
            )
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            HideInFullScreen(true) {
            SelectedCongregationBar(
                chosen.name, onChange = { chosenId = null; filter = InboxFilter.ALL.name; query = "" },
                title = "Field Service Report", modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            }
            HideInFullScreen(false) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Search congregation, month, year or status") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") } },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            }
            HideInFullScreen(true) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InboxFilter.entries.forEach { f ->
                    val count = items.count { f.matches(it.status.status) }
                    FilterChip(selected = filter == f.name, onClick = { filter = f.name }, label = { Text(f.label + " (" + count + ")", maxLines = 1, softWrap = false) })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sortByPeriod, onClick = { sortByPeriod = true }, label = { Text("Sort: Reporting period", maxLines = 1, softWrap = false) })
                FilterChip(selected = !sortByPeriod, onClick = { sortByPeriod = false }, label = { Text("Sort: Latest submission", maxLines = 1, softWrap = false) })
            }
            }
            if (shown.isEmpty()) {
                Text(
                    if (items.isEmpty()) "No Field Service Report has been submitted by your congregations yet." else "No report matches this filter or search.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                val viewMode = rememberRecordViewMode("co_field_service_inbox")
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { RecordViewToggle(viewMode) }
                    recordItems(
                        viewMode, shown, { it.status.id },
                        listOf(
                                UniversalColumn("Congregation", 200.dp) { nameOf(it.status.congregationId) },
                                UniversalColumn("Reporting Month", 150.dp) { monthName(it.status.periodMonth) },
                                UniversalColumn("Status", 150.dp) { it.status.status.workflowLabel },
                                UniversalColumn("Submitted", 180.dp) { stamp(it.report?.submittedAt ?: it.status.submittedAt) },
                                UniversalColumn("Last Action", 180.dp) { stamp(it.lastActionAt) },
                        ),
                        onRowClick = { item -> item.report?.let { r -> onOpenSnapshot(r.id) } },
                    ) { item ->
                        val s = item.status
                        CoCard(Modifier.fillMaxWidth(), accent = if (item.unread) com.emfitsolutions.gopreach.ui.components.co.coPalette().danger else null) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(nameOf(s.congregationId), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    if (item.unread) CoStatusBadge("NEW", CoKind.Danger)
                                    CoStatusBadge(s.status.workflowLabel, badgeKind(s.status))
                                }
                                Text("Field Service Report - " + monthName(s.periodMonth), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Submitted " + stamp(item.report?.submittedAt ?: s.submittedAt) + " by " + (item.report?.submittedByName?.takeIf { it.isNotBlank() } ?: s.submittedByName),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text("Last action " + stamp(item.lastActionAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (s.status == CoReportStatus.RETURNED && !s.returnReason.isNullOrBlank()) Text("Returned: " + s.returnReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                if (s.status == CoReportStatus.CANCELLED && !s.cancelReason.isNullOrBlank()) Text("Cancelled: " + s.cancelReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (item.report != null) CoButton("Open Snapshot", { onOpenSnapshot(item.report.id) }, kind = CoKind.Secondary)
                                }
                                // A month sent by an older version of the app has no frozen copy on the server. The congregation's own records are not
                                // readable by the overseer, so showing them would be misleading - the fix is to have it sent again.
                                if (item.report == null) CoCard(Modifier.fillMaxWidth(), accent = com.emfitsolutions.gopreach.ui.components.co.coPalette().warning) {
                                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("Snapshot not available", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                        Text(
                                            monthName(s.periodMonth) + " was sent from an older version of the app, which did not save the report's frozen copy, " +
                                                "so there is nothing here to open. Use Return to Congregation: the congregation sends " + monthName(s.periodMonth) + " again from the current app, " +
                                                "and that new send arrives here with its full snapshot.",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                                SnapshotActions(s, nameOf(s.congregationId), currentPersonId, viewModel)
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class PendingAction { RECEIVE, RETURN, CANCEL }

/**
 * Mark as Received / Return for Editing / Cancel Submission for ONE month's CURRENT snapshot, each behind a confirmation that names the
 * congregation, the month and the snapshot version. Return and Cancel need a reason. A newer version sent meanwhile makes the action refuse.
 */
@Composable
fun SnapshotActions(
    status: CoMonthStatus,
    congregationName: String,
    currentPersonId: String,
    viewModel: CircuitReportsInboxViewModel,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val showToast = rememberActionToast()
    var pending by remember(status.id, status.version) { mutableStateOf<PendingAction?>(null) }
    var reason by remember(status.id, status.version) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status.status == CoReportStatus.SUBMITTED) {
            CoButton("Mark as Received", { reason = ""; pending = PendingAction.RECEIVE }, kind = CoKind.Success, icon = Icons.Rounded.CheckCircle, enabled = !busy, fillWidth = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (status.status.canReturn) {
                CoButton("Return to Congregation", { reason = ""; pending = PendingAction.RETURN }, modifier = Modifier.weight(1f), kind = CoKind.Warning, icon = Icons.AutoMirrored.Rounded.Undo, enabled = !busy)
            }
            if (status.status.canCancel) {
                CoButton("Cancel Submission", { reason = ""; pending = PendingAction.CANCEL }, modifier = Modifier.weight(1f), kind = CoKind.Secondary, icon = Icons.Rounded.Block, enabled = !busy)
            }
        }
    }

    pending?.let { action ->
        val needsReason = action != PendingAction.RECEIVE
        AlertDialog(
        scrollBody = true,
            onDismissRequest = { if (!busy) pending = null },
            title = {
                Text(
                    when (action) {
                        PendingAction.RECEIVE -> "Mark as Received?"
                        PendingAction.RETURN -> "Return to Congregation?"
                        PendingAction.CANCEL -> "Cancel Submission?"
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Congregation: $congregationName", fontWeight = FontWeight.SemiBold)
                    Text("Reporting month: " + monthName(status.periodMonth))
                    Text(
                        when (action) {
                            PendingAction.RECEIVE -> "Once accepted, this month is locked for the congregation's admins and publishers until you return it."
                            PendingAction.RETURN -> "The congregation is told why, and its live report is unlocked so it can be corrected and sent again as a new report. This snapshot stays on record unchanged."
                            PendingAction.CANCEL -> "The submission is cancelled but its snapshot and history are kept. This does not unlock the congregation's report; to have it corrected, use Return for Editing."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = reason, onValueChange = { reason = it },
                        label = { Text(if (needsReason) "Reason (required)" else "Remarks (optional)") },
                        minLines = 2, modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && (!needsReason || reason.isNotBlank()), onClick = {
                    busy = true
                    scope.launch {
                        val result = when (action) {
                            PendingAction.RECEIVE -> viewModel.receive(status, reason, currentPersonId)
                            PendingAction.RETURN -> viewModel.returnForEditing(status, reason, currentPersonId)
                            PendingAction.CANCEL -> viewModel.cancel(status, reason, currentPersonId)
                        }
                        val problem = result.messageOrNull()
                        showToast(
                            problem ?: when (action) {
                                PendingAction.RECEIVE -> "Report marked as received. The month is now locked."
                                PendingAction.RETURN -> "Report returned to the congregation."
                                PendingAction.CANCEL -> "Submission cancelled."
                            },
                        )
                        busy = false
                        if (problem == null) pending = null
                    }
                }) { Text("Confirm") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { pending = null }) { Text("Back") } },
        )
    }
}

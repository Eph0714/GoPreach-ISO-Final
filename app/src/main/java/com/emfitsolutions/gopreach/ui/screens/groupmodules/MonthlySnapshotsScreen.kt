package com.emfitsolutions.gopreach.ui.screens.groupmodules

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.FsGroupReportSnapshot
import com.emfitsolutions.gopreach.data.repository.SnapshotSaveResult
import com.emfitsolutions.gopreach.ui.components.Scaffold
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.FieldServiceReportExporter
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.FieldServiceReportViewModel
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.FieldServiceSummary
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.SummaryCard
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.formatHours
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.sheetItems
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.summaryRows
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.toSheet
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.toSnapshotReport
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.koin.compose.viewmodel.koinViewModel

private val stampFormat get() = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
private val monthNames = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
private fun monthLabel(year: Int, month: Int) = "${monthNames.getOrElse(month - 1) { "" }} $year"

/** Midnight on the 1st of the month [monthsAgo] before this one, with its year and month number (1-12). */
private data class MonthChoice(val start: Long, val year: Int, val month: Int) {
    val label get() = monthLabel(year, month)
}

private fun monthChoices(count: Int = 36): List<MonthChoice> = (0 until count).map { ago ->
    val c = Calendar.getInstance().apply { add(Calendar.MONTH, -ago); set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    MonthChoice(c.timeInMillis, c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
}

/**
 * Monthly Report Snapshots, the second section of the Field Service Report module. An officer of an FS Group saves a permanent copy of THEIR group's month and browses their group's
 * snapshots; the Admin, Coordinator Elder, Service Overseer and Secretary read every snapshot of their congregation. A saved snapshot is shown, printed and exported from the stored
 * values only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthlySnapshotsScreen(
    currentPersonId: String,
    currentPersonName: String,
    congregationId: String?,
    /** Admin / Coordinator Elder / Service Overseer / Secretary (or Super Admin): reads all snapshots of the congregation. */
    readsAll: Boolean,
    onBack: () -> Unit,
    viewModel: MonthlySnapshotsViewModel = koinViewModel(),
    report: FieldServiceReportViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.startSync().collect { } }
    val flow = remember(currentPersonId, congregationId, readsAll) { viewModel.state(currentPersonId, congregationId, readsAll) }
    val state by flow.collectAsStateWithLifecycle(initialValue = SnapshotsState())
    val months = remember { monthChoices() }
    var picked by rememberSaveable { mutableStateOf(0) }
    var viewingId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var fMonth by rememberSaveable { mutableStateOf(0) }
    var fYear by rememberSaveable { mutableStateOf(0) }
    var fGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<FsGroupReportSnapshot?>(null) }
    val slot = state.slot
    val viewing = state.snapshots.firstOrNull { it.id == viewingId }
    BackHandler(enabled = viewing != null) { viewingId = null }

    if (viewing != null) {
        SnapshotDetail(viewing, onBack = { viewingId = null })
        return
    }
    val choice = months[picked.coerceIn(0, months.lastIndex)]
    val live by remember(slot?.group?.id, choice.start) {
        if (slot == null) kotlinx.coroutines.flow.flowOf(null) else viewModel.liveSheet(report, slot, choice.start)
    }.collectAsStateWithLifecycle(initialValue = null)
    val existing = slot?.let { s -> state.snapshots.firstOrNull { it.id == FsGroupReportSnapshot.idFor(s.group.id, choice.year, choice.month) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved Field Service Records") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        if (slot == null && !readsAll) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(
                    if (state.loaded) "Saved Field Service Records are saved by the Overseer, Servant and Assistant of an FS Group, and read by the Admin, Coordinator Elder, Service Overseer and Secretary."
                    else "Loading...",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        val groupChoices = state.snapshots.map { it.groupId to it.groupName }.distinct().sortedBy { it.second.lowercase() }
        val years = state.snapshots.map { it.year }.distinct().sortedDescending()
        val shown = state.snapshots.filter { s ->
            (query.isBlank() || s.groupName.contains(query.trim(), ignoreCase = true)) && (fMonth == 0 || s.month == fMonth) && (fYear == 0 || s.year == fYear) && (fGroup == null || s.groupId == fGroup)
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (slot != null) item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(slot.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text(slot.group.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        Choice("Reporting month", choice.label, months.mapIndexed { i, m -> i to m.label }) { picked = it }
                        val t = live?.toSnapshotReport()?.totals
                        if (t == null) Text("Loading the report...", style = MaterialTheme.typography.bodySmall)
                        else {
                            Text("Total publishers ${t.publishers} · Reports submitted ${t.participated} · Pending ${t.pending}", style = MaterialTheme.typography.bodyMedium)
                            Text("Total reports ${t.reports} · Hours (pioneers) ${formatHours(t.hours)} · Bible studies ${t.studies}", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (existing != null) {
                            Text(
                                "The Field Service Record for ${choice.label} was last saved ${stampFormat.format(Date(existing.savedAt))} by ${existing.savedByName}.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { viewingId = existing.id }) { Text("View Saved Field Service Record") }
                                OutlinedButton(onClick = { replacing = true }, enabled = live != null && !saving) { Text("Update Saved Field Service Record") }
                            }
                        } else {
                            Button(onClick = { confirm = true }, enabled = live != null && !saving) { Text(if (saving) "Saving..." else "Save Field Service Record") }
                        }
                        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
            item {
                if (readsAll) OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Search FS Group") })
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (readsAll && groupChoices.size > 1) Box { Choice("FS Group", groupChoices.firstOrNull { it.first == fGroup }?.second ?: "All groups", listOf<Pair<String?, String>>(null to "All groups") + groupChoices) { fGroup = it } }
                    Box { Choice("Month", if (fMonth == 0) "All months" else monthNames[fMonth - 1], listOf(0 to "All months") + monthNames.mapIndexed { i, n -> (i + 1) to n }) { fMonth = it } }
                    Box { Choice("Year", if (fYear == 0) "All years" else fYear.toString(), listOf(0 to "All years") + years.map { it to it.toString() }) { fYear = it } }
                }
            }
            if (shown.isEmpty()) item { Text("No saved Field Service Records yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(shown, key = { it.id }) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(monthLabel(s.year, s.month), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(s.groupName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("Saved ${stampFormat.format(Date(s.savedAt))} by ${s.savedByName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (readsAll) {
                            val t = s.report.totals
                            Text("Publishers ${t.publishers} · Reports ${t.reports} · Hours ${formatHours(t.hours)} · Studies ${t.studies}", style = MaterialTheme.typography.bodySmall)
                        }
                        Row {
                            TextButton(onClick = { viewingId = s.id }) { Text("View") }
                            TextButton(onClick = { FieldServiceReportExporter.shareExcel(context, listOf(s.toSheet())) }) { Text("Excel") }
                            if (slot != null && s.groupId == slot.group.id) {
                                TextButton(onClick = { deleting = s }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }

    deleting?.let { target ->
        val label = monthLabel(target.year, target.month)
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete the saved record of $label?") },
            text = { Text("Delete the saved Field Service Record for $label? Only this saved record is removed. The submitted reports are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.delete(target) { failure -> message = failure ?: "The saved Field Service Record for $label has been deleted." }
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    if (replacing && slot != null && existing != null) {
        AlertDialog(
            onDismissRequest = { replacing = false },
            title = { Text("Update the saved record of ${choice.label}?") },
            text = {
                Text(
                    "A saved Field Service Record for ${choice.label} already exists (saved ${stampFormat.format(Date(existing.savedAt))} by ${existing.savedByName}).\n\n" +
                        "Do you want to replace the previously saved record with the latest one? The earlier copy stays in the earlier saved copies.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        replacing = false
                        saving = true
                        viewModel.replace(report, slot, existing, "Saved again with the latest figures", currentPersonId, currentPersonName, System.currentTimeMillis()) { result ->
                            saving = false
                            when (result) {
                                is SnapshotSaveResult.Saved -> { message = "Your Field Service Record for ${choice.label} has been successfully updated."; viewingId = result.snapshot.id }
                                is SnapshotSaveResult.AlreadyExists -> message = "The saved Field Service Record for ${choice.label} was not replaced."
                                is SnapshotSaveResult.Failed -> message = result.reason
                            }
                        }
                    },
                ) { Text("Yes") }
            },
            dismissButton = { TextButton(onClick = { replacing = false }) { Text("No") } },
        )
    }

    if (confirm && slot != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Save the Field Service Record?") },
            text = { Text("Save the Field Service Record of ${choice.label} for ${slot.group.name}?") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    saving = true
                    viewModel.save(report, slot, choice.start, choice.year, choice.month, currentPersonId, currentPersonName, System.currentTimeMillis()) { result ->
                        saving = false
                        when (result) {
                            is SnapshotSaveResult.Saved -> { message = "Your Field Service Record for ${choice.label} has been successfully saved."; viewingId = result.snapshot.id }
                            is SnapshotSaveResult.AlreadyExists -> { message = "A saved Field Service Record for ${choice.label} already exists. It was not replaced."; viewingId = result.snapshot.id }
                            is SnapshotSaveResult.Failed -> message = result.reason
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun <T> Choice(label: String, value: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text("$label: $value", style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(key) }) }
        }
    }
}

/** One saved snapshot, drawn from its stored values with the same table, summary, print and exports as the live report. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnapshotDetail(s: FsGroupReportSnapshot, onBack: () -> Unit) {
    val context = LocalContext.current
    // only the current saved record is shown (its earlier copies stay inside it as an internal history)
    val sheet = remember(s.id, s.version) { s.toSheet() }
    val hScroll = rememberScrollState()
    val summary = remember(s.id, s.version) { s.summaryRows() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved Field Service Record · ${monthLabel(s.year, s.month)}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("SAVED FIELD SERVICE RECORD", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text("Congregation: ${s.congregationName}", style = MaterialTheme.typography.bodyMedium)
                        Text("FS Group: ${s.groupName}", style = MaterialTheme.typography.bodyMedium)
                        Text("Reporting month: ${monthLabel(s.year, s.month)}", style = MaterialTheme.typography.bodyMedium)
                        Text("Saved ${stampFormat.format(Date(s.savedAt))} by ${s.savedByName}${if (s.savedByRole.isNotBlank()) " (${s.savedByRole})" else ""}", style = MaterialTheme.typography.bodyMedium)
                        Row {
                            TextButton(onClick = { FieldServiceReportExporter.printPdf(context, listOf(sheet), null, snapshotFooter(s, summary)) }) { Text("Print") }
                            TextButton(onClick = {
                                android.widget.Toast.makeText(context, "In the print window choose \"Save as PDF\".", android.widget.Toast.LENGTH_LONG).show()
                                FieldServiceReportExporter.printPdf(context, listOf(sheet), null, snapshotFooter(s, summary))
                            }) { Text("PDF") }
                            TextButton(onClick = { FieldServiceReportExporter.shareExcel(context, listOf(sheet)) }) { Text("Excel") }
                        }
                    }
                }
            }
            sheetItems(listOf(sheet), hScroll, keyPrefix = "snap")
            item { SummaryCard(summary) }
        }
    }
}

private fun snapshotFooter(s: FsGroupReportSnapshot, summary: List<List<String>>): String {
    fun e(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return FieldServiceSummary.html(summary) +
        "<p class=\"gp-note\">Field Service Record saved ${e(stampFormat.format(Date(s.savedAt)))} by ${e(s.savedByName)} · ${e(s.congregationName)} · ${e(s.groupName)} · ${e(monthLabel(s.year, s.month))}</p>"
}

/**
 * The "Save this Field Service Record" button of the Field Service Report itself: the officer's OWN group's report for the month on screen, saved as the group's Field Service Record
 * of that month (asking first before an earlier saved record is replaced). The group is the person's own slot; the server checks every save again.
 */
@Composable
fun SaveSnapshotRow(
    slot: OfficerSlot,
    monthStart: Long,
    currentPersonId: String,
    report: FieldServiceReportViewModel,
    onOpenSnapshots: (() -> Unit)?,
    modifier: Modifier = Modifier,
    viewModel: MonthlySnapshotsViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.startSync().collect { } }
    val people by org.koin.compose.koinInject<com.emfitsolutions.gopreach.data.repository.PersonRepository>().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val currentPersonName = people.firstOrNull { it.id == currentPersonId }?.fullName.orEmpty()
    val flow = remember(currentPersonId, slot.group.congregationId) { viewModel.state(currentPersonId, slot.group.congregationId, false) }
    val state by flow.collectAsStateWithLifecycle(initialValue = SnapshotsState())
    val cal = remember(monthStart) { Calendar.getInstance().apply { timeInMillis = monthStart } }
    val year = cal.get(Calendar.YEAR)
    val month = cal.get(Calendar.MONTH) + 1
    val existing = state.snapshots.firstOrNull { it.id == FsGroupReportSnapshot.idFor(slot.group.id, year, month) }
    var confirm by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val label = monthLabel(year, month)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(
            onClick = {
                if (existing != null) confirm = true
                else {
                    saving = true
                    viewModel.save(report, slot, monthStart, year, month, currentPersonId, currentPersonName, System.currentTimeMillis()) { result ->
                        saving = false
                        message = when (result) {
                            is SnapshotSaveResult.Saved -> "Your Field Service Record for $label has been successfully saved."
                            is SnapshotSaveResult.AlreadyExists -> "A saved Field Service Record for $label already exists. It was not replaced."
                            is SnapshotSaveResult.Failed -> result.reason
                        }
                    }
                }
            },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (saving) "Saving..." else "Save this Field Service Record for the month of $label".uppercase(), fontWeight = FontWeight.ExtraBold) }
        if (existing != null) {
            Text("Saved: $label was last saved ${stampFormat.format(Date(existing.savedAt))} by ${existing.savedByName}.", style = MaterialTheme.typography.bodySmall)
            onOpenSnapshots?.let { open -> TextButton(onClick = open) { Text("View Saved Field Service Record") } }
        } else {
            Text("Nothing is saved yet for $label.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
    if (confirm && existing != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Replace the saved record?") },
            text = {
                Text(
                    "A saved Field Service Record for $label already exists (saved ${stampFormat.format(Date(existing.savedAt))} by ${existing.savedByName}).\n\n" +
                        "Do you want to replace the previously saved record with this one?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    saving = true
                    viewModel.replace(report, slot, existing, "Saved again with the latest figures", currentPersonId, currentPersonName, System.currentTimeMillis()) { result ->
                        saving = false
                        message = when (result) {
                            is SnapshotSaveResult.Saved -> "Your Field Service Record for $label has been successfully updated."
                            is SnapshotSaveResult.AlreadyExists -> "The saved Field Service Record for $label was not replaced."
                            is SnapshotSaveResult.Failed -> result.reason
                        }
                    }
                }) { Text("Yes") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("No") } },
        )
    }
}

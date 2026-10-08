package com.emfitsolutions.gopreach.ui.screens.attendance

import com.emfitsolutions.gopreach.ui.components.co.CoButton
import com.emfitsolutions.gopreach.ui.components.co.CoKind
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.export.ComparativeReportData
import com.emfitsolutions.gopreach.data.export.ComparativeStatisticsExporter
import com.emfitsolutions.gopreach.data.model.AttendanceRounding
import com.emfitsolutions.gopreach.data.model.COMPARATIVE_DUPLICATE_MESSAGE
import com.emfitsolutions.gopreach.data.model.ComparativeReport
import com.emfitsolutions.gopreach.data.model.ComparativeSnapshot
import com.emfitsolutions.gopreach.data.model.ComparativeStatus
import com.emfitsolutions.gopreach.data.model.CongregationMonthlyStatistics
import com.emfitsolutions.gopreach.data.model.snapshot
import com.emfitsolutions.gopreach.data.repository.CircuitResult
import com.emfitsolutions.gopreach.data.repository.ComparativePeriodsInput
import com.emfitsolutions.gopreach.data.repository.ComparativeSaveResult
import com.emfitsolutions.gopreach.domain.ComparativePeriods
import com.emfitsolutions.gopreach.domain.ComparativeReportBuilder
import com.emfitsolutions.gopreach.ui.screens.circuit.CircuitPeopleViewModel
import com.emfitsolutions.gopreach.ui.screens.circuit.CircuitScopeStore
import com.emfitsolutions.gopreach.ui.screens.circuit.SelectCongregationPrompt
import com.emfitsolutions.gopreach.ui.screens.circuit.SelectedCongregationBar
import com.emfitsolutions.gopreach.ui.screens.circuit.validCongregation
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.SimpleDropdown
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun monthAt(offsetFromNow: Int): Long = Calendar.getInstance().apply {
    add(Calendar.MONTH, offsetFromNow); set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun nextMonth(m: Long): Long = Calendar.getInstance().apply { timeInMillis = m; add(Calendar.MONTH, 1) }.timeInMillis

private val monthFmt get() = SimpleDateFormat("MMM yyyy", Locale.getDefault())
private fun monthLabel(m: Long): String = monthFmt.format(Date(m))
private fun stamp(ms: Long?): String = if (ms == null || ms == 0L) "—" else SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(ms))

private fun ComparativeReport.periodA() = ComparativeReportBuilder.periodLabel(periodAStart, periodAEnd, ::monthLabel)
private fun ComparativeReport.periodB() = ComparativeReportBuilder.periodLabel(periodBStart, periodBEnd, ::monthLabel)

private fun statusColor(s: ComparativeStatus): Color = when (s) {
    ComparativeStatus.DRAFT -> Color(0xFF757575)
    ComparativeStatus.SUBMITTED -> Color(0xFF1E88E5)
    ComparativeStatus.RETURNED -> Color(0xFFE65100)
    ComparativeStatus.RECEIVED -> Color(0xFF2E7D32)
}

@Composable
private fun StatusPill(s: ComparativeStatus) {
    Text(
        s.label, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(statusColor(s), MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun ReportCell(text: String, width: Dp, bold: Boolean = false, fill: Color = Color.Transparent, left: Boolean = false) {
    Box(
        modifier = Modifier.width(width).height(32.dp).background(fill).border(0.5.dp, Color(0xFF444444)).padding(horizontal = 6.dp),
        contentAlignment = if (left) Alignment.CenterStart else Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = Color.Black, maxLines = 1) }
}

/** A saved table (first row = headings). The first column stays put while the rest scrolls sideways. */
@Composable
private fun SnapshotTable(rows: List<List<String>>, firstWidth: Dp = 150.dp, cellWidth: Dp = 96.dp) {
    if (rows.isEmpty()) return
    val head = Color(0xFFDDE6F2)
    Row {
        Column {
            rows.forEachIndexed { i, r -> ReportCell(r.firstOrNull().orEmpty(), firstWidth, bold = i == 0, fill = if (i == 0) head else Color.Transparent, left = true) }
        }
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            rows.forEachIndexed { i, r ->
                Row { r.drop(1).forEach { c -> ReportCell(c, cellWidth, bold = i == 0, fill = if (i == 0) head else Color.Transparent) } }
            }
        }
    }
}

private fun buildSnapshot(
    congregationName: String, stats: List<CongregationMonthlyStatistics>, p: ComparativePeriodsInput, mode: AttendanceRounding, by: String,
) = ComparativeReportBuilder.build(
    congregationName, stats, p.aStart, p.aEnd, p.bStart, p.bEnd, mode, by,
    SimpleDateFormat("MMMM d, yyyy h:mm a", Locale.getDefault()).format(Date()), ::monthLabel, ::nextMonth,
)

/**
 * Congregation Comparative Reports: the congregation prepares a report from its historical monthly snapshots, saves it as a draft, and
 * submits it to the Circuit Overseer, who reviews, remarks, returns it for correction or receives it (after which it is locked for good).
 * The Circuit Overseer only ever sees submitted reports of the congregations in their circuit.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComparativeReportsScreen(
    fixedCongregationId: String?,
    circuitScope: String?,
    currentPersonId: String,
    roleName: String,
    canEdit: Boolean,
    onBack: () -> Unit,
    viewModel: ComparativeReportsViewModel = koinViewModel(),
    people: CircuitPeopleViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by viewModel.congregations.collectAsStateWithLifecycle(initialValue = emptyList())
    val circuitCongregations by remember(circuitScope) { people.congregations(circuitScope ?: "") }.collectAsStateWithLifecycle(initialValue = emptyList())
    var pickedId by rememberSaveable { mutableStateOf<String?>(null) }
    val congregation = when {
        circuitScope != null -> validCongregation(circuitCongregations, CircuitScopeStore.congregation)
        fixedCongregationId != null -> all.firstOrNull { it.id == fixedCongregationId }
        else -> all.firstOrNull { it.id == pickedId }
    }
    val isCo = circuitScope != null
    val reports by remember(congregation?.id) { viewModel.reportsFor(congregation?.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val stats by remember(congregation?.id) { viewModel.statisticsFor(congregation?.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val rounding by remember(congregation?.id) { viewModel.rounding(congregation?.id) }.collectAsStateWithLifecycle(initialValue = AttendanceRounding.ROUNDED)

    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var editPeriods by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("ALL") }
    var sort by rememberSaveable { mutableStateOf("NEWEST") }
    var query by rememberSaveable { mutableStateOf("") }
    var table by rememberSaveable { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var duplicate by remember { mutableStateOf<ComparativeReport?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) } // submit / receive / delete
    var dialog by remember { mutableStateOf<String?>(null) } // remark / return
    var dialogText by remember { mutableStateOf("") }
    val year = Calendar.getInstance().get(Calendar.YEAR)
    val open = reports.firstOrNull { it.id == openId }

    fun report(result: CircuitResult, okMessage: String) {
        notice = when (result) {
            is CircuitResult.Success -> okMessage
            is CircuitResult.Conflict -> result.message
            is CircuitResult.Offline -> result.message
            is CircuitResult.Error -> result.message
        }
    }

    fun handleSave(r: ComparativeSaveResult, okMessage: String, openAfter: Boolean = true) {
        when (r) {
            is ComparativeSaveResult.Saved -> { notice = okMessage; if (openAfter) { openId = r.report.id }; creating = false; editPeriods = false }
            is ComparativeSaveResult.Duplicate -> duplicate = r.existing
            is ComparativeSaveResult.Refused -> notice = r.message
        }
    }

    fun doExport(r: ComparativeReport, kind: String) {
        val snap = r.snapshot() ?: run { notice = "This report has no saved content to export."; return }
        scope.launch {
            val data = ComparativeReportData(
                congregationName = snap.congregationName, periodA = snap.periodA, periodB = snap.periodB, generatedBy = snap.generatedBy, generatedAt = snap.generatedAt,
                calculationMode = snap.calculationMode, comparison = snap.comparison, monthly = snap.monthly, notes = snap.notes, missingMonths = snap.missingMonths, summary = snap.summary,
                details = listOfNotNull(
                    "Report Number" to r.reportNumber.ifBlank { "—" },
                    "Status" to r.status.label + " (version ${r.version})",
                    "Submitted By" to (r.submittedByName ?: "—") + (r.submittedAt?.let { " on " + stamp(it) } ?: ""),
                    "Circuit Overseer" to (r.receivedByName ?: r.returnedByName ?: "—"),
                    "Received" to stamp(r.receivedAt),
                    "CO Remarks" to (r.currentCoRemarks ?: "—"),
                ),
            )
            if (kind == "Excel") ComparativeStatisticsExporter.shareExcel(context, data) else ComparativeStatisticsExporter.print(context, data)
            runCatching { viewModel.logExport(r, kind, currentPersonId, roleName) }
        }
    }

    var fullScreen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    com.emfitsolutions.gopreach.ui.components.co.CoFullScreenEffect(fullScreen)
    androidx.activity.compose.BackHandler(enabled = fullScreen) { fullScreen = false }
    Scaffold(
        topBar = {
            TopAppBar(
                actions = { com.emfitsolutions.gopreach.ui.components.co.CoFullScreenButton(fullScreen) { fullScreen = !fullScreen } },
                title = { Text(if (open != null) "Comparative Report ${open.reportNumber}" else if (creating) "Create Comparative Report" else "Comparative Reports") },
                navigationIcon = {
                    IconButton(onClick = { when { creating -> { creating = false; editPeriods = false }; openId != null -> { openId = null; editPeriods = false }; else -> onBack() } }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isCo && congregation == null ->
                    SelectCongregationPrompt(circuitCongregations, onSelect = { CircuitScopeStore.selectCongregation(it) }, hint = "Choose a congregation under your assigned Circuit to review its Comparative Reports.")
                isCo -> SelectedCongregationBar(congregation!!.name, onChange = { CircuitScopeStore.selectCongregation(null); openId = null }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                fixedCongregationId == null -> SimpleDropdown("Congregation", congregation?.name ?: "Select a congregation", all.map { it.id to it.name }, { pickedId = it; openId = null })
                else -> Text("Congregation: ${congregation?.name.orEmpty()}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (congregation == null) return@Column

            notice?.let { n ->
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                        Text(n, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { notice = null }) { Text("OK") }
                    }
                }
            }

            val by = "" // resolved when a report is generated
            when {
                // ---------- create / edit the periods ----------
                creating || (editPeriods && open != null) -> {
                    PeriodEditor(
                        initial = open?.takeIf { editPeriods }?.let { ComparativePeriodsInput(it.periodAStart, it.periodAEnd, it.periodBStart, it.periodBEnd) }
                            ?: viewModel.savedRange(currentPersonId, roleName, congregation.id)?.let { (f, t) -> splitRange(f, t) },
                        stats = stats,
                        onSaveDraft = { p ->
                            scope.launch {
                                val gen = viewModel.generatedBy(currentPersonId)
                                val (snap, ids) = buildSnapshot(congregation.name, stats, p, rounding, gen)
                                val r = if (editPeriods && open != null) viewModel.changePeriods(open, p, snap, ids, rounding, currentPersonId, roleName, year)
                                else viewModel.createDraft(congregation.id, p, snap, ids, rounding, currentPersonId, roleName, year)
                                handleSave(r, "Draft saved.")
                            }
                        },
                        onSubmit = { p ->
                            scope.launch {
                                val gen = viewModel.generatedBy(currentPersonId)
                                val (snap, ids) = buildSnapshot(congregation.name, stats, p, rounding, gen)
                                val saved = if (editPeriods && open != null) viewModel.changePeriods(open, p, snap, ids, rounding, currentPersonId, roleName, year)
                                else viewModel.createDraft(congregation.id, p, snap, ids, rounding, currentPersonId, roleName, year)
                                if (saved is ComparativeSaveResult.Saved) {
                                    report(viewModel.submit(saved.report, currentPersonId, roleName), "Comparative Report Submitted to Circuit Overseer")
                                    openId = saved.report.id; creating = false; editPeriods = false
                                } else handleSave(saved, "")
                            }
                        },
                    )
                }
                // ---------- one report ----------
                open != null -> {
                    val snap = open.snapshot()
                    val remarks by remember(open.id) { viewModel.remarks(open.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
                    val history by remember(open.id) { viewModel.history(open.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusPill(open.status)
                            Text("Version ${open.version}", style = MaterialTheme.typography.labelMedium)
                        }
                        Text(open.status.notice, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        if (open.status == ComparativeStatus.RETURNED && !open.returnReason.isNullOrBlank()) {
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE9D6))) {
                                Text("Reason: ${open.returnReason}", modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Text("Period A: ${open.periodA()}   ·   Period B: ${open.periodB()}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Created by ${open.createdByName.ifBlank { "—" }}" + (open.submittedAt?.let { " · Submitted ${stamp(it)} by ${open.submittedByName ?: "—"}" } ?: "") +
                                (open.receivedAt?.let { " · Received ${stamp(it)} by ${open.receivedByName ?: "—"}" } ?: ""),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!isCo && canEdit && open.status.congregationCanEdit) {
                                if (open.status == ComparativeStatus.DRAFT) OutlinedButton(onClick = { editPeriods = true }) { Text("Edit Period") }
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        val gen = viewModel.generatedBy(currentPersonId)
                                        val p = ComparativePeriodsInput(open.periodAStart, open.periodAEnd, open.periodBStart, open.periodBEnd)
                                        val (s, ids) = buildSnapshot(congregation.name, stats, p, rounding, gen)
                                        handleSave(viewModel.updateSnapshot(open, s, ids, rounding, currentPersonId, roleName, "Regenerated"), "Report regenerated from the latest historical statistics.", openAfter = false)
                                    }
                                }) { Text("Regenerate") }
                                Button(onClick = { confirm = "submit" }) { Text(if (open.status == ComparativeStatus.RETURNED) "Resubmit" else "Submit to CO") }
                                if (open.status == ComparativeStatus.DRAFT) OutlinedButton(onClick = { confirm = "delete" }) { Text("Delete Draft") }
                            }
                            if (isCo && open.status == ComparativeStatus.SUBMITTED) {
                                Button(onClick = { confirm = "receive" }) { Text("Receive Report") }
                                OutlinedButton(onClick = { dialogText = ""; dialog = "return" }) { Text("Return for Correction") }
                            }
                            if (isCo && (open.status == ComparativeStatus.SUBMITTED || open.status == ComparativeStatus.RETURNED)) {
                                OutlinedButton(onClick = { dialogText = ""; dialog = "remark" }) { Text("Add Remarks") }
                            }
                            OutlinedButton(onClick = { doExport(open, "Print") }) { Text("Print") }
                            OutlinedButton(onClick = { doExport(open, "PDF") }) { Text("PDF") }
                            CoButton("Excel", { doExport(open, "Excel") }, kind = CoKind.Secondary, icon = Icons.Rounded.TableChart)
                        }

                        if (snap == null) Text("This report's saved content could not be read.", color = MaterialTheme.colorScheme.error)
                        else {
                            Text("Period Comparison", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            SnapshotTable(snap.comparison, firstWidth = 170.dp, cellWidth = 100.dp)
                            snap.notes.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            if (snap.missingMonths.isNotEmpty()) Text("Missing data: ${snap.missingMonths.joinToString(", ")}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                            Text("Monthly Details", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            SnapshotTable(snap.monthly, firstWidth = 150.dp, cellWidth = 78.dp)
                            if (snap.summary.isNotEmpty()) {
                                Text("Summary", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                SnapshotTable(snap.summary, firstWidth = 200.dp, cellWidth = 150.dp)
                            }
                            Text("Generated ${snap.generatedAt} by ${snap.generatedBy} · ${snap.calculationMode}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        Text("Circuit Overseer Remarks", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        if (remarks.isEmpty()) Text("No remarks yet.", style = MaterialTheme.typography.bodySmall)
                        remarks.forEach { Text("${stamp(it.createdAt)} · ${it.authorName}: ${it.remark}", style = MaterialTheme.typography.bodySmall) }

                        Text("History", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        history.forEach {
                            Text(
                                "${stamp(it.at)} · ${it.action} · ${it.userName}" + (it.userRole.takeIf { r -> r.isNotBlank() }?.let { r -> " ($r)" } ?: "") + (it.remarks?.let { r -> " — $r" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                // ---------- the list ----------
                else -> {
                    val visible = reports.filter { filter == "ALL" || it.status.name == filter }.filter { r ->
                        query.isBlank() || listOf(r.reportNumber, r.periodA(), r.periodB(), r.status.label, r.currentCoRemarks.orEmpty(), r.submittedByName.orEmpty()).any { it.contains(query, ignoreCase = true) }
                    }.let { l ->
                        when (sort) {
                            "OLDEST" -> l.sortedBy { it.submittedAt ?: it.createdAt }
                            "PENDING" -> l.sortedWith(compareBy<ComparativeReport> { it.status != ComparativeStatus.SUBMITTED }.thenByDescending { it.submittedAt ?: it.createdAt })
                            else -> l.sortedByDescending { it.submittedAt ?: it.createdAt }
                        }
                    }
                    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!isCo && canEdit) Button(onClick = { creating = true }) { Text("Create Comparative Report") }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val filters = if (isCo) listOf("ALL" to "All", "SUBMITTED" to "Submitted", "RETURNED" to "Returned", "RECEIVED" to "Received")
                            else listOf("ALL" to "All", "DRAFT" to "Draft", "SUBMITTED" to "Submitted", "RETURNED" to "Returned", "RECEIVED" to "Received")
                            filters.forEach { (k, l) -> FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text(l) }) }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("NEWEST" to "Newest", "OLDEST" to "Oldest", "PENDING" to "Pending Review").forEach { (k, l) ->
                                FilterChip(selected = sort == k, onClick = { sort = k }, label = { Text(l) })
                            }
                        }
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search reports...") }, modifier = Modifier.fillMaxWidth(),
                            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(androidx.compose.material.icons.Icons.Rounded.Close, contentDescription = "Clear search") } },
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                            if (filter != "ALL") FilterChip(selected = true, onClick = { filter = "ALL" }, label = { Text("${filter.lowercase().replaceFirstChar { it.uppercase() }} ×") })
                            if (filter != "ALL" || query.isNotBlank()) OutlinedButton(onClick = { filter = "ALL"; query = "" }) { Text("Clear All") }
                            FilterChip(selected = !table, onClick = { table = false }, label = { Text("List View") })
                            FilterChip(selected = table, onClick = { table = true }, label = { Text("Table View") })
                        }
                        Text(
                            if (visible.size == reports.size) "Records Found: ${reports.size}" else "Records Found: ${visible.size} of ${reports.size}",
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                        )
                        // Print / PDF / Excel of exactly the reports listed (current search, status filter and sort), ending with the Summary.
                        fun listExport(): com.emfitsolutions.gopreach.data.export.TableReportData = com.emfitsolutions.gopreach.data.export.TableReportData(
                            title = "Comparative Reports",
                            details = listOf("Congregation" to congregation.name, "Status Filter" to (if (filter == "ALL") "All" else filter.lowercase().replaceFirstChar { it.uppercase() })) +
                                (if (query.isNotBlank()) listOf("Search" to query) else emptyList()),
                            recordsFound = if (visible.size == reports.size) visible.size.toString() else "${visible.size} of ${reports.size}",
                            table = listOf(listOf("Report No.", "Period A", "Period B", "Status", "Version", "Submitted", "Received", "CO Remarks")) + visible.map { r ->
                                listOf(r.reportNumber.ifBlank { "—" }, r.periodA(), r.periodB(), r.status.label, r.version.toString(), stamp(r.submittedAt), stamp(r.receivedAt), r.currentCoRemarks ?: "—")
                            },
                            summary = listOf(listOf("Metric", "Value"), listOf("Total Reports", visible.size.toString())) +
                                ComparativeStatus.entries.filter { !(isCo && it == ComparativeStatus.DRAFT) }.map { s -> listOf(s.label, visible.count { it.status == s }.toString()) } +
                                listOf(listOf("Awaiting CO Review", visible.count { it.status == ComparativeStatus.SUBMITTED }.toString())),
                            generatedBy = roleName.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                            generatedAt = SimpleDateFormat("MMMM d, yyyy h:mm a", Locale.getDefault()).format(Date()),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = visible.isNotEmpty(), onClick = { com.emfitsolutions.gopreach.data.export.TableReportExporter.print(context, listExport()) }) { Text("Print") }
                            OutlinedButton(enabled = visible.isNotEmpty(), onClick = { com.emfitsolutions.gopreach.data.export.TableReportExporter.print(context, listExport()) }) { Text("PDF") }
                            CoButton("Excel", { com.emfitsolutions.gopreach.data.export.TableReportExporter.shareExcel(context, listExport()) }, kind = CoKind.Secondary, icon = Icons.Rounded.TableChart, enabled = visible.isNotEmpty())
                        }
                    }
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (visible.isEmpty()) Text(if (isCo) "No Comparative Reports have been submitted by this congregation yet." else "No Comparative Reports yet. Tap Create Comparative Report.", style = MaterialTheme.typography.bodyMedium)
                        if (table && visible.isNotEmpty()) {
                            Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                val head = Color(0xFFDDE6F2)
                                Row { ReportCell("Report No.", 110.dp, true, head, true); ReportCell("Period A", 130.dp, true, head); ReportCell("Period B", 130.dp, true, head); ReportCell("Status", 120.dp, true, head); ReportCell("Submitted", 150.dp, true, head); ReportCell("Received", 150.dp, true, head) }
                                visible.forEach { r ->
                                    Row(modifier = Modifier.clickable { openId = r.id }) {
                                        ReportCell(r.reportNumber.ifBlank { "—" }, 110.dp, left = true); ReportCell(r.periodA(), 130.dp); ReportCell(r.periodB(), 130.dp)
                                        ReportCell(r.status.label, 120.dp); ReportCell(stamp(r.submittedAt), 150.dp); ReportCell(stamp(r.receivedAt), 150.dp)
                                    }
                                }
                            }
                        }
                        if (!table) visible.forEach { r ->
                            Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().clickable { openId = r.id }) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(r.reportNumber.ifBlank { "Report" }, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                        StatusPill(r.status)
                                    }
                                    Text("Period A: ${r.periodA()}", style = MaterialTheme.typography.bodySmall)
                                    Text("Period B: ${r.periodB()}", style = MaterialTheme.typography.bodySmall)
                                    Text("Submitted: ${stamp(r.submittedAt)}", style = MaterialTheme.typography.labelSmall)
                                    Text("CO Remarks: ${r.currentCoRemarks ?: "—"}", style = MaterialTheme.typography.labelSmall, maxLines = 2)
                                    Text("Received: ${stamp(r.receivedAt)}", style = MaterialTheme.typography.labelSmall)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        val editable = !isCo && canEdit
                                        when (r.status) {
                                            ComparativeStatus.DRAFT -> {
                                                OutlinedButton(onClick = { openId = r.id }) { Text("Open") }
                                                if (editable) {
                                                    OutlinedButton(onClick = { openId = r.id; editPeriods = true }) { Text("Edit") }
                                                    OutlinedButton(onClick = { openId = r.id; confirm = "delete" }) { Text("Delete") }
                                                    Button(onClick = { openId = r.id; confirm = "submit" }) { Text("Submit") }
                                                }
                                            }
                                            ComparativeStatus.SUBMITTED -> OutlinedButton(onClick = { openId = r.id }) { Text(if (isCo) "Review" else "View") }
                                            ComparativeStatus.RETURNED -> {
                                                OutlinedButton(onClick = { openId = r.id }) { Text(if (isCo) "View" else "Open") }
                                                if (editable) Button(onClick = { openId = r.id; confirm = "submit" }) { Text("Resubmit") }
                                            }
                                            ComparativeStatus.RECEIVED -> {
                                                OutlinedButton(onClick = { openId = r.id }) { Text("View") }
                                                OutlinedButton(onClick = { doExport(r, "Print") }) { Text("Print") }
                                                OutlinedButton(onClick = { doExport(r, "PDF") }) { Text("PDF") }
                                                CoButton("Excel", { doExport(r, "Excel") }, kind = CoKind.Secondary, icon = Icons.Rounded.TableChart)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // The mandatory end-of-report Summary: only the reports listed above.
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Summary", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text("Total Reports: ${visible.size}")
                                ComparativeStatus.entries.forEach { s -> if (!(isCo && s == ComparativeStatus.DRAFT)) Text("${s.label}: ${visible.count { it.status == s }}", style = MaterialTheme.typography.bodySmall) }
                                Text("Needs Congregation Action: ${visible.count { it.status.congregationCanEdit }}", style = MaterialTheme.typography.bodySmall)
                                Text("Awaiting CO Review: ${visible.count { it.status == ComparativeStatus.SUBMITTED }}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    duplicate?.let { existing ->
        AlertDialog(
            onDismissRequest = { duplicate = null },
            title = { Text("Report already exists") },
            text = { Text("$COMPARATIVE_DUPLICATE_MESSAGE\n\n${existing.reportNumber} · ${existing.status.label}") },
            confirmButton = { TextButton(onClick = { openId = existing.id; creating = false; editPeriods = false; duplicate = null }) { Text("Open Existing Report") } },
            dismissButton = { TextButton(onClick = { duplicate = null }) { Text("Cancel") } },
        )
    }

    confirm?.let { kind ->
        val target = open
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(when (kind) { "submit" -> "Submit to Circuit Overseer?"; "receive" -> "Receive this report?"; else -> "Delete this draft?" }) },
            text = {
                Text(
                    when (kind) {
                        "submit" -> "Submit this Comparative Report to the Circuit Overseer? You will not be able to edit it unless the Circuit Overseer returns it for correction."
                        "receive" -> "Receive this Comparative Report? Once received, the congregation can no longer edit, delete, regenerate, replace or resubmit it."
                        else -> "Delete this draft Comparative Report? Its history is kept."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    if (target == null) return@TextButton
                    scope.launch {
                        when (kind) {
                            "submit" -> report(viewModel.submit(target, currentPersonId, roleName), "Comparative Report Submitted to Circuit Overseer")
                            "receive" -> report(viewModel.receive(target.id, currentPersonId, roleName), "Comparative Report Received by Circuit Overseer")
                            else -> { handleSave(viewModel.deleteDraft(target, currentPersonId, roleName), "Draft deleted.", openAfter = false); openId = null }
                        }
                    }
                }) { Text(when (kind) { "submit" -> "Submit"; "receive" -> "Receive"; else -> "Delete" }) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }

    dialog?.let { kind ->
        val target = open
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (kind == "return") "Return for Correction" else "Add Remarks") },
            text = {
                OutlinedTextField(
                    value = dialogText, onValueChange = { dialogText = it }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                    label = { Text(if (kind == "return") "Reason (required)" else "Remarks") },
                )
            },
            confirmButton = {
                TextButton(enabled = dialogText.isNotBlank(), onClick = {
                    val text = dialogText; dialog = null
                    if (target == null) return@TextButton
                    scope.launch {
                        if (kind == "return") report(viewModel.returnForCorrection(target.id, text, currentPersonId, roleName), "Returned for Correction")
                        else report(viewModel.addRemark(target.id, text, currentPersonId, roleName), "Remarks added.")
                    }
                }) { Text(if (kind == "return") "Return" else "Save Remarks") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
}

/** The Report Submission range split in two halves (older half = Period A); null when it is a single month. */
private fun splitRange(from: Long, to: Long): ComparativePeriodsInput? {
    val months = ComparativeReportBuilder.monthsBetween(from, to, ::nextMonth)
    if (months.size < 2) return null
    val half = months.size / 2
    return ComparativePeriodsInput(months.first(), months[half - 1], months[half], months.last())
}

/** Period A / Period B month ranges with live validation, the missing months flagged and a preview of the figures. */
@Composable
private fun PeriodEditor(
    initial: ComparativePeriodsInput?,
    stats: List<CongregationMonthlyStatistics>,
    onSaveDraft: (ComparativePeriodsInput) -> Unit,
    onSubmit: (ComparativePeriodsInput) -> Unit,
) {
    val options = remember { (0 downTo -47).map { monthAt(it) } }
    var aFrom by rememberSaveable { mutableStateOf(initial?.aStart ?: monthAt(-11)) }
    var aTo by rememberSaveable { mutableStateOf(initial?.aEnd ?: monthAt(-6)) }
    var bFrom by rememberSaveable { mutableStateOf(initial?.bStart ?: monthAt(-5)) }
    var bTo by rememberSaveable { mutableStateOf(initial?.bEnd ?: monthAt(0)) }
    val periods = ComparativePeriodsInput(aFrom, aTo, bFrom, bTo)
    val problem = ComparativePeriods.validate(aFrom, aTo, bFrom, bTo, monthAt(0))
    val months = ComparativeReportBuilder.monthsBetween(aFrom, aTo, ::nextMonth) + ComparativeReportBuilder.monthsBetween(bFrom, bTo, ::nextMonth)
    val missing = months.filter { m -> stats.none { it.serviceMonth == m } }
    val preview = if (problem == null) ComparativeReportBuilder.build("", stats, aFrom, aTo, bFrom, bTo, AttendanceRounding.ROUNDED, "", "", ::monthLabel, ::nextMonth).first else null

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Period A", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { SimpleDropdown("From", monthLabel(aFrom), options.map { it.toString() to monthLabel(it) }, { aFrom = it.toLong() }) }
            Box(Modifier.weight(1f)) { SimpleDropdown("To", monthLabel(aTo), options.map { it.toString() to monthLabel(it) }, { aTo = it.toLong() }) }
        }
        Text("Period B", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { SimpleDropdown("From", monthLabel(bFrom), options.map { it.toString() to monthLabel(it) }, { bFrom = it.toLong() }) }
            Box(Modifier.weight(1f)) { SimpleDropdown("To", monthLabel(bTo), options.map { it.toString() to monthLabel(it) }, { bTo = it.toLong() }) }
        }
        if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        else if (missing.isNotEmpty()) Text("Missing data (no saved statistics): ${missing.joinToString(", ") { monthLabel(it) }}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = problem == null, onClick = { onSaveDraft(periods) }) { Text("Save Draft") }
            Button(enabled = problem == null, onClick = { onSubmit(periods) }) { Text("Submit to CO") }
        }
        if (preview != null) {
            Text("Preview", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            SnapshotTable(preview.comparison, firstWidth = 170.dp, cellWidth = 100.dp)
            preview.notes.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

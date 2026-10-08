package com.emfitsolutions.gopreach.ui.screens.attendance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.domain.ReportSubmissionPlanner
import com.emfitsolutions.gopreach.domain.SubmissionItem
import com.emfitsolutions.gopreach.domain.SubmissionKind
import com.emfitsolutions.gopreach.domain.SubmissionStatus
import com.emfitsolutions.gopreach.ui.screens.circuit.CircuitPeopleViewModel
import com.emfitsolutions.gopreach.ui.screens.circuit.CircuitScopeStore
import com.emfitsolutions.gopreach.ui.screens.circuit.SelectCongregationPrompt
import com.emfitsolutions.gopreach.ui.screens.circuit.SelectedCongregationBar
import com.emfitsolutions.gopreach.ui.screens.circuit.validCongregation
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.SimpleDropdown
import org.koin.compose.viewmodel.koinViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun monthStart(offset: Int = 0, year: Int? = null): Long = Calendar.getInstance().apply {
    if (year != null) set(Calendar.MONTH, Calendar.JANUARY)
    add(Calendar.MONTH, offset); set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun shiftMonth(m: Long, n: Int): Long = Calendar.getInstance().apply { timeInMillis = m; add(Calendar.MONTH, n) }.timeInMillis
private fun monthName(m: Long): String = SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date(m))
private fun fullMonthName(m: Long): String = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(m))
private fun stamp(ms: Long): String = if (ms == 0L) "—" else SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))

/** The one set of status colours every report and the folder share. */
fun submissionStatusColor(s: SubmissionStatus): Color = when (s) {
    SubmissionStatus.NOT_STARTED -> Color(0xFF757575)
    SubmissionStatus.OPEN -> Color(0xFF90A4AE)
    SubmissionStatus.DRAFT -> Color(0xFF8E24AA)
    SubmissionStatus.READY -> Color(0xFFF9A825)
    SubmissionStatus.SUBMITTED -> Color(0xFF1E88E5)
    SubmissionStatus.RETURNED -> Color(0xFFE65100)
    SubmissionStatus.RECEIVED -> Color(0xFF2E7D32)
}

@Composable
private fun StatusBadge(s: SubmissionStatus) {
    Text(
        (if (s.locked) "🔒 " else "") + s.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(submissionStatusColor(s), MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun TCell(text: String, width: Dp, head: Boolean = false, right: Boolean = false) {
    Box(
        modifier = Modifier.width(width).height(34.dp).background(if (head) Color(0xFFDDE6F2) else Color.Transparent).border(0.5.dp, Color(0xFF444444)).padding(horizontal = 6.dp),
        contentAlignment = if (right) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = if (head) FontWeight.Bold else FontWeight.Normal, color = Color.Black, maxLines = 1) }
}

/**
 * Report Submission: the user's workspace of every report they have to prepare or follow for the chosen month range. The reports are found
 * automatically from the Active Role and congregation; the range is chosen once, saved per user + role + congregation, and handed to the
 * reports that are opened from here. A Circuit Overseer only follows what the congregation has sent.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReportSubmissionScreen(
    fixedCongregationId: String?,
    circuitScope: String?,
    userId: String,
    roleName: String,
    canPrepare: Boolean,
    onBack: () -> Unit,
    onOpen: (SubmissionItem) -> Unit,
    viewModel: ReportSubmissionViewModel = koinViewModel(),
    people: CircuitPeopleViewModel = koinViewModel(),
) {
    val all by viewModel.congregations.collectAsStateWithLifecycle(initialValue = emptyList())
    val circuitCongregations by remember(circuitScope) { people.congregations(circuitScope ?: "") }.collectAsStateWithLifecycle(initialValue = emptyList())
    var pickedId by rememberSaveable { mutableStateOf<String?>(null) }
    val isCo = circuitScope != null
    val congregation = when {
        isCo -> validCongregation(circuitCongregations, CircuitScopeStore.congregation)
        fixedCongregationId != null -> all.firstOrNull { it.id == fixedCongregationId }
        else -> all.firstOrNull { it.id == pickedId }
    }
    val sources by remember(congregation?.id) { viewModel.sources(congregation?.id) }.collectAsStateWithLifecycle(initialValue = SubmissionSources())

    // The saved range for this user + Active Role + congregation scope; re-read whenever one of those changes.
    val thisMonth = remember { monthStart() }
    val scopeKey = congregation?.id
    var from by remember(userId, roleName, scopeKey) { mutableStateOf(viewModel.preferences.range(userId, roleName, scopeKey)?.first ?: monthStart(year = 0)) }
    var to by remember(userId, roleName, scopeKey) { mutableStateOf(viewModel.preferences.range(userId, roleName, scopeKey)?.second ?: thisMonth) }
    var applied by remember(userId, roleName, scopeKey) { mutableStateOf(from to to) }
    var preset by rememberSaveable { mutableStateOf("Custom Range") }
    var notice by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var kindFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var statusFilter by rememberSaveable { mutableStateOf("ALL") }
    var table by rememberSaveable { mutableStateOf(false) }
    val options = remember { (0 downTo -59).map { monthStart(it) } }

    val months = remember(applied) { generateSequence(applied.first) { shiftMonth(it, 1) }.takeWhile { it <= applied.second }.take(120).toList() }
    val planned = ReportSubmissionPlanner.plan(
        congregation?.id.orEmpty(), months, thisMonth, ::monthName, sources.statuses, sources.attendance, sources.statistics, sources.comparative,
        includeAttendance = !isCo,
    ).let { l -> if (isCo) l.filter { it.status in setOf(SubmissionStatus.SUBMITTED, SubmissionStatus.RETURNED, SubmissionStatus.RECEIVED) } else l }
    val folderSummary = ReportSubmissionPlanner.summarize(planned)

    val visible = planned.filter { i ->
        (kindFilter == null || i.kind.name == kindFilter) &&
            (statusFilter == "ALL" || (statusFilter == "ACTION" && i.status.needsAction) || i.status.name == statusFilter) &&
            (query.isBlank() || listOf(i.title, i.periodLabel, i.status.label, i.detail, i.remark.orEmpty()).any { it.contains(query, ignoreCase = true) })
    }
    val shownSummary = ReportSubmissionPlanner.summarize(visible)
    val attention = planned.filter { it.status.needsAction }

    fun apply() {
        if (from > to) { notice = "The From month cannot be after the To month."; return }
        if (to > thisMonth) { notice = "Future months cannot be selected."; return }
        applied = from to to
        viewModel.preferences.saveRange(userId, roleName, scopeKey, from, to)
        notice = "Month range saved: ${fullMonthName(from)} – ${fullMonthName(to)}"
    }

    fun actionLabel(i: SubmissionItem) = when {
        isCo -> if (i.status == SubmissionStatus.SUBMITTED) "Review" else "View"
        i.status == SubmissionStatus.RETURNED -> "Correct Report"
        i.status == SubmissionStatus.READY || i.status == SubmissionStatus.DRAFT || i.status == SubmissionStatus.NOT_STARTED -> if (canPrepare) "Open" else "View"
        else -> "View"
    }

    var fullScreen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    com.emfitsolutions.gopreach.ui.components.co.CoFullScreenEffect(fullScreen)
    androidx.activity.compose.BackHandler(enabled = fullScreen) { fullScreen = false }
    Scaffold(
        topBar = {
            TopAppBar(
                actions = { com.emfitsolutions.gopreach.ui.components.co.CoFullScreenButton(fullScreen) { fullScreen = !fullScreen } },
                title = { Text("Report Submission") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isCo && congregation == null ->
                    SelectCongregationPrompt(circuitCongregations, onSelect = { CircuitScopeStore.selectCongregation(it) }, hint = "Choose a congregation, then set the month range to see the reports it has sent.")
                isCo -> SelectedCongregationBar(congregation!!.name, onChange = { CircuitScopeStore.selectCongregation(null) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                fixedCongregationId == null -> SimpleDropdown("Congregation", congregation?.name ?: "Select a congregation", all.map { it.id to it.name }, { pickedId = it })
                else -> Text("Congregation: ${congregation?.name.orEmpty()}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (congregation == null) return@Column

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // ---- month range ----
                Text("Report Month Range", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { SimpleDropdown("From", fullMonthName(from), options.map { it.toString() to fullMonthName(it) }, { from = it.toLong(); preset = "Custom Range" }) }
                    Box(Modifier.weight(1f)) { SimpleDropdown("To", fullMonthName(to), options.map { it.toString() to fullMonthName(it) }, { to = it.toLong(); preset = "Custom Range" }) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReportSubmissionPlanner.presetNames.forEach { p ->
                        FilterChip(selected = preset == p, onClick = {
                            preset = p
                            ReportSubmissionPlanner.preset(p, thisMonth, monthStart(year = 0), ::shiftMonth)?.let { (f, t) -> from = f; to = t; apply() }
                        }, label = { Text(p) })
                    }
                }
                Button(onClick = { apply() }) { Text("Apply Month Range") }
                notice?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }

                // ---- folder summary ----
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Report Submission Summary", fontWeight = FontWeight.Bold)
                        Text("Month Range: ${fullMonthName(applied.first)} – ${fullMonthName(applied.second)}", style = MaterialTheme.typography.bodySmall)
                        Text("Total Reports: ${folderSummary.total}   ·   Needs Action: ${folderSummary.needsAction}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            SubmissionStatus.entries.filter { folderSummary.count(it) > 0 }.joinToString("  ·  ") { "${it.label}: ${folderSummary.count(it)}" },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }

                // ---- action required ----
                if (attention.isNotEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)), border = BorderStroke(1.dp, Color(0xFFE65100))) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Action Required — ${attention.size} Report${if (attention.size == 1) "" else "s"} Need Your Attention", fontWeight = FontWeight.Bold, color = Color(0xFFBF360C))
                            attention.take(8).forEach { Text("• ${it.title} — ${it.status.label}", style = MaterialTheme.typography.bodySmall) }
                            if (attention.size > 8) Text("…and ${attention.size - 8} more", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                // ---- search / filters / view ----
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search reports...") },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") } },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = kindFilter == null, onClick = { kindFilter = null }, label = { Text("All Types") })
                    SubmissionKind.entries.filter { !(isCo && it == SubmissionKind.ATTENDANCE) }.forEach { k ->
                        FilterChip(selected = kindFilter == k.name, onClick = { kindFilter = k.name; viewModel.preferences.saveKind(userId, roleName, k.name) }, label = { Text(k.title) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("ALL" to "All", "ACTION" to "Needs Action", "SUBMITTED" to "Submitted", "RETURNED" to "Correction Required", "RECEIVED" to "Received").forEach { (k, l) ->
                        FilterChip(selected = statusFilter == k, onClick = { statusFilter = k }, label = { Text(l) })
                    }
                }
                // removable chips for the active filters
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    kindFilter?.let { k -> FilterChip(selected = true, onClick = { kindFilter = null }, label = { Text("${SubmissionKind.valueOf(k).title} ×") }) }
                    if (statusFilter != "ALL") FilterChip(selected = true, onClick = { statusFilter = "ALL" }, label = { Text("$statusFilter ×") })
                    if (kindFilter != null || statusFilter != "ALL" || query.isNotBlank()) OutlinedButton(onClick = { kindFilter = null; statusFilter = "ALL"; query = "" }) { Text("Clear All") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !table, onClick = { table = false }, label = { Text("List View") })
                    FilterChip(selected = table, onClick = { table = true }, label = { Text("Table View") })
                    Text(
                        if (visible.size == planned.size) "Records Found: ${planned.size}" else "Records Found: ${visible.size} of ${planned.size}",
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    )
                }

                if (visible.isEmpty()) {
                    Text(
                        if (planned.isEmpty()) (if (isCo) "No reports have been sent by this congregation in this range." else "No reports are due in this range.") else "No records found. Try clearing the filters.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (planned.isNotEmpty()) OutlinedButton(onClick = { kindFilter = null; statusFilter = "ALL"; query = "" }) { Text("Clear Filters") }
                } else if (table) {
                    Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        Row { TCell("Report", 150.dp, true); TCell("Period", 130.dp, true); TCell("Status", 140.dp, true); TCell("Updated", 70.dp, true); TCell("Action", 110.dp, true) }
                        visible.forEach { i ->
                            Row {
                                TCell(i.kind.title.removeSuffix("s"), 150.dp); TCell(i.periodLabel, 130.dp); TCell(i.status.label, 140.dp); TCell(stamp(i.updatedAt), 70.dp); TCell(actionLabel(i), 110.dp)
                            }
                        }
                    }
                    // tap a row's action through the list below the table is not needed: the card list is the actionable view
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        visible.filter { it.status.needsAction || isCo }.take(12).forEach { i -> OutlinedButton(onClick = { onOpen(i) }) { Text("${actionLabel(i)}: ${i.periodLabel}") } }
                    }
                } else {
                    var lastKind: SubmissionKind? = null
                    visible.forEach { i ->
                        if (i.kind != lastKind) { lastKind = i.kind; Text(i.kind.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
                        Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(i.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    StatusBadge(i.status)
                                }
                                Text("Congregation: ${congregation.name}", style = MaterialTheme.typography.labelMedium)
                                if (i.kind == SubmissionKind.COMPARATIVE) Text("Period: ${i.periodLabel}", style = MaterialTheme.typography.labelMedium)
                                if (i.detail.isNotBlank()) Text(i.detail, style = MaterialTheme.typography.labelMedium)
                                if (i.status == SubmissionStatus.RETURNED && !i.remark.isNullOrBlank()) Text("CO Remark: ${i.remark}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFBF360C))
                                if (i.status.locked) Text("This report has been received and is locked.", style = MaterialTheme.typography.labelSmall)
                                OutlinedButton(onClick = { onOpen(i) }) { Text(actionLabel(i)) }
                            }
                        }
                    }
                }

                // ---- mandatory end-of-report summary (filter-aware) ----
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Summary", fontWeight = FontWeight.Bold)
                        Text("Total Records: ${shownSummary.total}")
                        Text("Needs Action: ${shownSummary.needsAction}")
                        SubmissionStatus.entries.filter { shownSummary.count(it) > 0 }.forEach { Text("${it.label}: ${shownSummary.count(it)}", style = MaterialTheme.typography.bodySmall) }
                        SubmissionKind.entries.forEach { k -> visible.count { it.kind == k }.takeIf { it > 0 }?.let { Text("${k.title}: $it", style = MaterialTheme.typography.bodySmall) } }
                        Text("Month Range: ${fullMonthName(applied.first)} – ${fullMonthName(applied.second)}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

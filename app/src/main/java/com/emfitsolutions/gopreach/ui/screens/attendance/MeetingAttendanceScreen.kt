package com.emfitsolutions.gopreach.ui.screens.attendance

import com.emfitsolutions.gopreach.ui.components.co.CoButton
import com.emfitsolutions.gopreach.ui.components.co.CoKind
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.TableChart
import android.app.DatePickerDialog
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.AttendanceRounding
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.MeetingAttendance
import com.emfitsolutions.gopreach.data.model.MeetingType
import com.emfitsolutions.gopreach.data.repository.AttendanceResult
import com.emfitsolutions.gopreach.data.repository.MeetingAttendanceInput
import com.emfitsolutions.gopreach.data.export.TableReportData
import com.emfitsolutions.gopreach.data.export.TableReportExporter
import com.emfitsolutions.gopreach.data.repository.ReportSubmissionPreferences
import com.emfitsolutions.gopreach.domain.AttendanceReportSummary
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.SummaryCard
import org.koin.compose.koinInject
import com.emfitsolutions.gopreach.domain.AttendanceCalculator
import com.emfitsolutions.gopreach.domain.AttendanceSummaries
import com.emfitsolutions.gopreach.domain.AttendanceSummary
import com.emfitsolutions.gopreach.domain.MonthBounds
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

private val W_DATE = 78.dp
private val W_TYPE = 84.dp
private val W_PART = 92.dp
private val W_OFFICIAL = 96.dp
private val W_ACTIONS = 168.dp
private val HEAD_FILL = Color(0xFFDDE6F2)
private val OFFICIAL_FILL = Color(0xFFFFF59D)

private fun monthStartOf(year: Int, month: Int): Long = Calendar.getInstance().apply {
    clear(); set(year, month, 1, 0, 0, 0)
}.timeInMillis

@Composable
private fun Cell(text: String, width: Dp, bold: Boolean = false, fill: Color = Color.Transparent, left: Boolean = false) {
    Box(
        modifier = Modifier.width(width).height(36.dp).background(fill).border(0.5.dp, Color(0xFF444444)).padding(horizontal = 6.dp),
        contentAlignment = if (left) Alignment.CenterStart else Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = Color.Black, maxLines = 1) }
}

/**
 * Meeting Attendance: the weekly Midweek and Weekend headcounts of one congregation, in a table like the Field Service Report's —
 * with Add / Edit / Delete / History for the congregation-wide roles (and the Super-Admin), and read-only for the Circuit Overseer.
 * The mean is always calculated from the original counts and rounded only afterwards, by the congregation's own setting.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun MeetingAttendanceScreen(
    /** The signed-in manager's own congregation (null for the Super-Admin and the Circuit Overseer, who choose). */
    fixedCongregationId: String?,
    /** Non-null for the Circuit Overseer (their circuit's scope id): view only, congregation chosen first. */
    circuitScope: String?,
    currentPersonId: String,
    roleName: String,
    canEdit: Boolean,
    onBack: () -> Unit,
    onOpenComparative: (() -> Unit)? = null,
    viewModel: MeetingAttendanceViewModel = koinViewModel(),
    people: CircuitPeopleViewModel = koinViewModel(),
) {
    val scope = rememberCoroutineScope()
    val allCongregations by viewModel.congregations.collectAsStateWithLifecycle(initialValue = emptyList())
    val circuitCongregations by remember(circuitScope) { people.congregations(circuitScope ?: "") }.collectAsStateWithLifecycle(initialValue = emptyList())
    var pickedId by rememberSaveable { mutableStateOf<String?>(null) }

    val congregation: Congregation? = when {
        circuitScope != null -> validCongregation(circuitCongregations, CircuitScopeStore.congregation)
        fixedCongregationId != null -> allCongregations.firstOrNull { it.id == fixedCongregationId }
        else -> allCongregations.firstOrNull { it.id == pickedId }
    }
    val congregationId = congregation?.id

    val records by remember(congregationId) { viewModel.recordsFor(congregationId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val rounding by remember(congregationId) { viewModel.rounding(congregationId) }.collectAsStateWithLifecycle(initialValue = AttendanceRounding.ROUNDED)
    val frozen by remember(congregationId) { viewModel.frozenMonths(congregationId) }.collectAsStateWithLifecycle(initialValue = emptySet())

    val now = remember { Calendar.getInstance() }
    var year by rememberSaveable { mutableStateOf(now.get(Calendar.YEAR)) }
    var month by rememberSaveable { mutableStateOf<Int?>(now.get(Calendar.MONTH)) } // null = every month
    var typeFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var showDeleted by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf("NEWEST") }
    var filtersOpen by remember { mutableStateOf(false) }
    // The Report Submission month range (when one was saved) is the default period; choosing a Year / Month replaces it.
    val prefs = koinInject<ReportSubmissionPreferences>()
    var range by remember(congregationId) { mutableStateOf(prefs.range(currentPersonId, roleName, congregationId)) }
    val context = LocalContext.current

    var editing by remember { mutableStateOf<MeetingAttendance?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<MeetingAttendance?>(null) }
    var historyOf by remember { mutableStateOf<MeetingAttendance?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val dateFmt = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
    val monthFmt = remember { SimpleDateFormat("MMMM", Locale.getDefault()) }
    val years = remember { (now.get(Calendar.YEAR) downTo 2020).toList() }

    fun nextMonthStart(m: Long): Long = Calendar.getInstance().apply { timeInMillis = m; add(Calendar.MONTH, 1) }.timeInMillis
    val filtered = remember(records, year, month, typeFilter, query, showDeleted, range, sort) {
        records.filter { r ->
            val c = Calendar.getInstance().apply { timeInMillis = r.meetingDate }
            (showDeleted || !r.deleted) &&
                (range?.let { (f, t) -> r.meetingDate >= f && r.meetingDate < nextMonthStart(t) } ?: (c.get(Calendar.YEAR) == year && (month == null || c.get(Calendar.MONTH) == month))) &&
                (typeFilter == null || r.meetingType.name == typeFilter) &&
                (query.isBlank() || r.remarks.orEmpty().contains(query, true) || dateFmt.format(Date(r.meetingDate)).contains(query, true) || r.meetingType.label.contains(query, true))
        }.let { l ->
            when (sort) {
                "OLDEST" -> l.sortedBy { it.meetingDate }
                "HIGHEST" -> l.sortedByDescending { it.officialAttendance }
                "LOWEST" -> l.sortedBy { it.officialAttendance }
                else -> l.sortedByDescending { it.meetingDate }
            }
        }
    }
    val hScroll = rememberScrollState()

    // ---- the Universal Report pieces: period, summary (of exactly what is shown), export data ----
    val periodFmt = remember { SimpleDateFormat("MMM yyyy", Locale.getDefault()) }
    val periodLabel = range?.let { (f, t) -> if (f == t) periodFmt.format(Date(f)) else periodFmt.format(Date(f)) + " – " + periodFmt.format(Date(t)) }
        ?: (month?.let { SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(monthStartOf(year, it))) } ?: year.toString())
    val shown = filtered.filter { !it.deleted }
    val summaryTypes = typeFilter?.let { listOf(MeetingType.valueOf(it)) } ?: MeetingType.entries
    // Missing meetings can only be told when every meeting of whole months is in view (no search, past or current months only).
    val expectedByType: Map<MeetingType, Int>? = run {
        if (query.isNotBlank()) return@run null
        val thisMonth = monthStartOf(now.get(Calendar.YEAR), now.get(Calendar.MONTH))
        val months = range?.let { (f, t) -> generateSequence(f) { nextMonthStart(it) }.takeWhile { it <= t }.toList() }
            ?: month?.let { listOf(monthStartOf(year, it)) }
        if (months.isNullOrEmpty() || months.any { it > thisMonth }) return@run null
        summaryTypes.associateWith { months.sumOf { m -> AttendanceSummaries.expectedMeetings(m) } }
    }
    val summaryRows = AttendanceReportSummary.rows(shown, summaryTypes, expectedByType) { v ->
        v?.let { AttendanceCalculator.display(if (rounding == AttendanceRounding.ROUNDED) AttendanceCalculator.roundHalfUp(it) else it, rounding) } ?: "—"
    }
    fun exportData(): TableReportData {
        val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        return TableReportData(
            title = "Meeting Attendance Report",
            details = listOf("Congregation" to congregation?.name.orEmpty(), "Reporting Period" to periodLabel, "Attendance Calculation" to rounding.label) +
                (typeFilter?.let { listOf("Meeting" to MeetingType.valueOf(it).label) } ?: emptyList()) +
                (if (query.isNotBlank()) listOf("Search" to query) else emptyList()),
            recordsFound = shown.size.toString(),
            table = listOf(listOf("Date", "Meeting", "Treasures", "Apply Yourself", "Living as Christians", "Public Meeting", "Watchtower Study", "Official Attendance")) +
                shown.sortedBy { it.meetingDate }.map { r ->
                    listOf(
                        fmt.format(Date(r.meetingDate)), r.meetingType.label,
                        r.treasuresAttendance?.toString() ?: "—", r.applyYourselfAttendance?.toString() ?: "—", r.livingAsChristiansAttendance?.toString() ?: "—",
                        r.publicMeetingAttendance?.toString() ?: "—", r.watchtowerStudyAttendance?.toString() ?: "—", AttendanceCalculator.display(r.officialAttendance, r.roundingMode),
                    )
                },
            summary = summaryRows,
            generatedBy = roleName.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
            generatedAt = SimpleDateFormat("MMMM d, yyyy h:mm a", Locale.getDefault()).format(Date()),
        )
    }

    var fullScreen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    com.emfitsolutions.gopreach.ui.components.co.CoFullScreenEffect(fullScreen)
    androidx.activity.compose.BackHandler(enabled = fullScreen) { fullScreen = false }
    Scaffold(
        topBar = {
            TopAppBar(
                actions = { com.emfitsolutions.gopreach.ui.components.co.CoFullScreenButton(fullScreen) { fullScreen = !fullScreen } },
                title = { Text("Meeting Attendance") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // ---- congregation ----
            when {
                circuitScope != null && congregation == null ->
                    SelectCongregationPrompt(circuitCongregations, onSelect = { CircuitScopeStore.selectCongregation(it) }, hint = "Choose a congregation under your assigned Circuit to see its meeting attendance.")
                circuitScope != null -> SelectedCongregationBar(congregation!!.name, onChange = { CircuitScopeStore.selectCongregation(null) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                fixedCongregationId == null -> SimpleDropdown(
                    label = "Congregation", selectedLabel = congregation?.name ?: "Select a congregation",
                    options = allCongregations.map { it.id to it.name }, onSelected = { pickedId = it },
                    // keeps the dropdown clear of the screen edge
                )
                else -> Text("Congregation: ${congregation?.name.orEmpty()}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (congregationId == null) return@Column

            // ---- filters ----
            Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, label = { Text("Search records...") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(androidx.compose.material.icons.Icons.Rounded.Close, contentDescription = "Clear search") } },
                )
                val activeFilters = (if (range != null || month != null || year != now.get(Calendar.YEAR)) 1 else 0) + (if (typeFilter != null) 1 else 0) + (if (showDeleted) 1 else 0)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { filtersOpen = true }) { Text(if (activeFilters > 0) "Filters ($activeFilters)" else "Filters") }
                    Box(Modifier.weight(1f)) {
                        SimpleDropdown(
                            "Sort", when (sort) { "OLDEST" -> "Oldest First"; "HIGHEST" -> "Highest Attendance"; "LOWEST" -> "Lowest Attendance"; else -> "Newest First" },
                            listOf("NEWEST" to "Newest First", "OLDEST" to "Oldest First", "HIGHEST" to "Highest Attendance", "LOWEST" to "Lowest Attendance"), { sort = it },
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = true, onClick = { filtersOpen = true }, label = { Text(periodLabel) })
                    typeFilter?.let { FilterChip(selected = true, onClick = { typeFilter = null }, label = { Text(MeetingType.valueOf(it).label + " ×") }) }
                    if (showDeleted) FilterChip(selected = true, onClick = { showDeleted = false }, label = { Text("Deleted shown ×") })
                    if (activeFilters > 0 || query.isNotBlank()) OutlinedButton(onClick = {
                        range = null; month = now.get(Calendar.MONTH); year = now.get(Calendar.YEAR); typeFilter = null; showDeleted = false; query = ""
                    }) { Text("Clear All") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoButton("Print / PDF", { TableReportExporter.print(context, exportData()) }, kind = CoKind.Secondary, icon = Icons.Rounded.Print, enabled = shown.isNotEmpty())
                    CoButton("Excel", { TableReportExporter.shareExcel(context, exportData()) }, kind = CoKind.Secondary, icon = Icons.Rounded.TableChart, enabled = shown.isNotEmpty())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canEdit) {
                        Button(onClick = { adding = true }) { Text("+ Add Attendance") }
                        OutlinedButton(onClick = { settingsOpen = true }) { Text("Calculation") }
                    }
                    onOpenComparative?.let { OutlinedButton(onClick = it) { Text("Comparative Report") } }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }

            // ---- table ----
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                item(key = "found") {
                    Text(
                        "$periodLabel · Records Found: " + shown.size + (if (query.isNotBlank() || typeFilter != null) " (filtered)" else ""),
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                stickyHeader(key = "head") {
                    Row(modifier = Modifier.background(MaterialTheme.colorScheme.surface).horizontalScroll(hScroll)) {
                        Cell("Date", W_DATE, true, HEAD_FILL); Cell("Meeting", W_TYPE, true, HEAD_FILL)
                        Cell("Treasures", W_PART, true, HEAD_FILL); Cell("Apply Yourself", W_PART, true, HEAD_FILL); Cell("Living as Christians", W_PART, true, HEAD_FILL)
                        Cell("Public Meeting", W_PART, true, HEAD_FILL); Cell("Watchtower Study", W_PART, true, HEAD_FILL); Cell("Official Attendance", W_OFFICIAL, true, HEAD_FILL)
                        if (canEdit) Cell("Actions", W_ACTIONS, true, HEAD_FILL)
                    }
                }
                if (filtered.isEmpty()) {
                    item(key = "none") { Text("No attendance recorded for this selection.", modifier = Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium) }
                }
                items(filtered, key = { it.id }) { r ->
                    val locked = r.serviceMonth in frozen
                    val dash = "—"
                    Row(modifier = Modifier.horizontalScroll(hScroll)) {
                        Cell(dateFmt.format(Date(r.meetingDate)), W_DATE)
                        Cell(r.meetingType.label + if (r.deleted) " (deleted)" else "", W_TYPE)
                        Cell(r.treasuresAttendance?.toString() ?: dash, W_PART); Cell(r.applyYourselfAttendance?.toString() ?: dash, W_PART)
                        Cell(r.livingAsChristiansAttendance?.toString() ?: dash, W_PART); Cell(r.publicMeetingAttendance?.toString() ?: dash, W_PART)
                        Cell(r.watchtowerStudyAttendance?.toString() ?: dash, W_PART)
                        Cell(AttendanceCalculator.display(r.officialAttendance, r.roundingMode), W_OFFICIAL, true, OFFICIAL_FILL)
                        if (canEdit) {
                            Row(modifier = Modifier.width(W_ACTIONS).height(36.dp).border(0.5.dp, Color(0xFF444444)), verticalAlignment = Alignment.CenterVertically) {
                                val pad = PaddingValues(horizontal = 5.dp)
                                if (!r.deleted && !locked) {
                                    TextButton(onClick = { editing = r }, contentPadding = pad) { Text("Edit", style = MaterialTheme.typography.labelSmall) }
                                    TextButton(onClick = { deleting = r }, contentPadding = pad) { Text("Delete", style = MaterialTheme.typography.labelSmall) }
                                }
                                TextButton(onClick = { historyOf = r }, contentPadding = pad) { Text(if (locked) "History 🔒" else "History", style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                }
                item(key = "foot") {
                    Text(
                        "Attendance is " + rounding.label.lowercase() + ". A missing meeting is shown as missing — never as zero.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
                    )
                }
                // The mandatory end-of-report Summary: only what is shown above (after the filters and search).
                item(key = "end-summary") { SummaryCard(summaryRows, Modifier.padding(top = 12.dp)) }
            }
        }
    }

    // ---- dialogs ----
    if (filtersOpen) {
        var dYear by remember { mutableStateOf(year) }
        var dMonth by remember { mutableStateOf(month) }
        var dType by remember { mutableStateOf(typeFilter) }
        var dDeleted by remember { mutableStateOf(showDeleted) }
        var dKeepRange by remember { mutableStateOf(range != null) }
        AlertDialog(
            onDismissRequest = { filtersOpen = false },
            title = { Text("Filters") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (range != null) FilterChip(selected = dKeepRange, onClick = { dKeepRange = !dKeepRange }, label = { Text("Report Submission range: $periodLabel") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { SimpleDropdown("Year", dYear.toString(), years.map { it.toString() to it.toString() }, { dYear = it.toInt(); dKeepRange = false }) }
                        Box(Modifier.weight(1.4f)) {
                            SimpleDropdown(
                                "Month", dMonth?.let { monthFmt.format(Date(monthStartOf(dYear, it))) } ?: "All months",
                                listOf("" to "All months") + (0..11).map { it.toString() to monthFmt.format(Date(monthStartOf(dYear, it))) },
                                { dMonth = it.toIntOrNull(); dKeepRange = false },
                            )
                        }
                    }
                    SimpleDropdown(
                        "Meeting Type", dType?.let { MeetingType.valueOf(it).label } ?: "All meetings",
                        listOf("" to "All meetings", "MIDWEEK" to "Midweek", "WEEKEND" to "Weekend"), { dType = it.ifBlank { null } },
                    )
                    FilterChip(selected = dDeleted, onClick = { dDeleted = !dDeleted }, label = { Text("Show deleted records") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    year = dYear; month = dMonth; typeFilter = dType; showDeleted = dDeleted
                    if (!dKeepRange) range = null
                    filtersOpen = false
                }) { Text("Apply Filters") }
            },
            dismissButton = {
                TextButton(onClick = {
                    range = null; month = now.get(Calendar.MONTH); year = now.get(Calendar.YEAR); typeFilter = null; showDeleted = false; filtersOpen = false
                }) { Text("Clear All") }
            },
        )
    }
    if (canEdit && congregationId != null && (adding || editing != null)) {
        AttendanceFormDialog(
            congregationId = congregationId, editing = editing, rounding = editing?.roundingMode ?: rounding,
            onDismiss = { adding = false; editing = null },
            onSave = { input, reason ->
                val result = viewModel.save(input, editing, currentPersonId, roleName, reason)
                if (result is AttendanceResult.Refused) result.message else { adding = false; editing = null; null }
            },
        )
    }
    deleting?.let { r ->
        var reason by remember(r.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete attendance record?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${r.meetingType.label} Meeting, ${SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(r.meetingDate))}. The record is kept in the history and the audit trail.")
                    OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text("Reason (optional)") }, minLines = 1, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { (viewModel.delete(r, currentPersonId, roleName, reason.ifBlank { null }) as? AttendanceResult.Refused)?.let { message = it.message } }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    historyOf?.let { r -> AttendanceHistoryDialog(r, viewModel) { historyOf = null } }
    if (settingsOpen && congregationId != null) {
        RoundingDialog(
            current = rounding, onDismiss = { settingsOpen = false },
            onApply = { mode, recalc ->
                settingsOpen = false
                scope.launch {
                    val changed = viewModel.setRounding(congregationId, mode, recalc, currentPersonId, roleName)
                    message = if (recalc) "Calculation changed to ${mode.label.lowercase()}; $changed historical record(s) recalculated." else null
                }
            },
        )
    }
}

/** Add / Edit: meeting type, date, the counts of that meeting type, and the calculation shown at once. */
@Composable
private fun AttendanceFormDialog(
    congregationId: String,
    editing: MeetingAttendance?,
    rounding: AttendanceRounding,
    onDismiss: () -> Unit,
    onSave: suspend (MeetingAttendanceInput, String?) -> String?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(editing?.meetingType ?: MeetingType.MIDWEEK) }
    var date by remember { mutableLongStateOf(editing?.meetingDate ?: System.currentTimeMillis()) }
    val texts = remember { mutableStateOf(editing?.parts?.map { it.toString() } ?: List(3) { "" }) }
    var remarks by remember { mutableStateOf(editing?.remarks.orEmpty()) }
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val labels = if (type == MeetingType.MIDWEEK) listOf("Treasures From God's Word", "Apply Yourself to Field Ministry", "Living as Christians") else listOf("Public Meeting", "Watchtower Study")
    val values = texts.value.take(type.partCount).let { l -> l + List((type.partCount - l.size).coerceAtLeast(0)) { "" } }
    val parsed = values.map { AttendanceCalculator.parseCount(it) }
    val allValid = parsed.all { it != null }
    val avg = if (allValid) AttendanceCalculator.average(parsed.filterNotNull()) else null
    val official = avg?.let { AttendanceCalculator.official(it, rounding) }
    val dateLabel = SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()).format(Date(date))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing != null) "Edit Attendance" else "Add Attendance") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (editing == null) {
                    Text("Meeting Type", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MeetingType.entries.forEach { m ->
                            FilterChip(selected = type == m, onClick = { type = m; texts.value = List(m.partCount) { "" } }, label = { Text(m.label + " Meeting") })
                        }
                    }
                } else Text("${type.label} Meeting", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Meeting Date: $dateLabel", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (editing == null) OutlinedButton(onClick = {
                        val c = Calendar.getInstance().apply { timeInMillis = date }
                        DatePickerDialog(context, { _, y, m, d -> date = Calendar.getInstance().apply { clear(); set(y, m, d, 12, 0, 0) }.timeInMillis }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                    }) { Text("Choose") }
                }
                labels.forEachIndexed { i, label ->
                    val bad = values[i].isNotEmpty() && parsed[i] == null
                    OutlinedTextField(
                        value = values[i],
                        onValueChange = { v -> texts.value = values.toMutableList().also { it[i] = v.filter { ch -> ch.isDigit() }.take(6) } },
                        label = { Text(label) }, singleLine = true, isError = bad,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { if (bad) Text("Whole numbers only") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Calculated Average: " + (avg?.let { AttendanceCalculator.formatTwo(it) } ?: "—"), style = MaterialTheme.typography.bodyMedium)
                        Text("Calculation: " + rounding.label, style = MaterialTheme.typography.bodySmall)
                        Text("Official Attendance: " + (official?.let { AttendanceCalculator.display(it, rounding) } ?: "—"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedTextField(value = remarks, onValueChange = { remarks = it }, label = { Text("Remarks (optional)") }, minLines = 1, modifier = Modifier.fillMaxWidth())
                if (editing != null) OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text("Reason for change (optional)") }, minLines = 1, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                enabled = allValid && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val problem = onSave(MeetingAttendanceInput(congregationId, type, date, parsed.filterNotNull(), remarks.ifBlank { null }), reason.ifBlank { null })
                        busy = false
                        if (problem != null) error = problem
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The rounding preference, and whether it applies to new records only (default) or also recalculates historical ones. */
@Composable
private fun RoundingDialog(current: AttendanceRounding, onDismiss: () -> Unit, onApply: (AttendanceRounding, Boolean) -> Unit) {
    var mode by remember { mutableStateOf(current) }
    var recalc by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attendance Calculation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AttendanceRounding.entries.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = mode == m, onClick = { mode = m }); Text(m.label) }
                }
                Text("Apply to:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = !recalc, onClick = { recalc = false }); Text("New records only (default)") }
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = recalc, onClick = { recalc = true }); Text("Also recalculate historical records") }
                Text("Records of months already received by the Circuit Overseer are history and never change.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = mode != current, onClick = { if (recalc) confirm = true else onApply(mode, false) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Recalculate historical records?") },
            text = { Text("Every saved weekly record that is not yet frozen will be recalculated from its original counts using \"${mode.label}\". This is recorded in the audit trail.") },
            confirmButton = { TextButton(onClick = { confirm = false; onApply(mode, true) }) { Text("Recalculate") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

/** Every change of one record: who, when, what it was and what it became (kept even after the record was deleted). */
@Composable
private fun AttendanceHistoryDialog(record: MeetingAttendance, viewModel: MeetingAttendanceViewModel, onDismiss: () -> Unit) {
    val events by remember(record.id) { viewModel.history(record.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val fmt = remember { SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("History — ${record.meetingType.label}, ${SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(record.meetingDate))}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (events.isEmpty()) Text("No history is available on this device yet.", style = MaterialTheme.typography.bodySmall)
                events.forEach { e ->
                    Column {
                        Text("${fmt.format(Date(e.at))} — ${e.action} (${e.userName})", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        e.previousValues?.let { Text("Before: $it", style = MaterialTheme.typography.labelSmall) }
                        e.newValues?.let { Text("After: $it", style = MaterialTheme.typography.labelSmall) }
                        e.reason?.let { Text("Reason: $it", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

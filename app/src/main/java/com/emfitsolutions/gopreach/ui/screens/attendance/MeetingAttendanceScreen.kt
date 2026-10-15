package com.emfitsolutions.gopreach.ui.screens.attendance

import com.emfitsolutions.gopreach.ui.components.co.CoButton
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.sp
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
import androidx.compose.material.icons.automirrored.rounded.Send
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
    var sendOpen by remember { mutableStateOf(false) }
    val submission: AttendanceSubmissionViewModel = koinViewModel()
    if (sendOpen && congregationId != null && canEdit && circuitScope == null) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { sendOpen = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                SendAttendancePanel(congregationId = congregationId, actorPersonId = currentPersonId, viewModel = submission)
            }
        }
    }

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

    val viewMode = com.emfitsolutions.gopreach.ui.components.rememberRecordViewMode("meeting_attendance")
    var menuOpen by remember { mutableStateOf(false) }
    val cardDateFmt = remember { SimpleDateFormat("EEE, MMM d", Locale.getDefault()) }
    val atCurrentPeriod = if (month == null) year >= now.get(Calendar.YEAR) else year * 12 + month!! >= now.get(Calendar.YEAR) * 12 + now.get(Calendar.MONTH)
    fun stepPeriod(delta: Int) {
        range = null
        val m = month
        if (m == null) year += delta
        else {
            val index = year * 12 + m + delta
            year = index / 12; month = index % 12
        }
    }
    // A filter counts only when it differs from the default view (this month, every meeting, no deleted records).
    val activeFilters = (if (range != null || month != now.get(Calendar.MONTH) || year != now.get(Calendar.YEAR)) 1 else 0) + (if (typeFilter != null) 1 else 0) + (if (showDeleted) 1 else 0) + (if (query.isNotBlank()) 1 else 0)

    var fullScreen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    com.emfitsolutions.gopreach.ui.components.co.CoFullScreenEffect(fullScreen)
    androidx.activity.compose.BackHandler(enabled = fullScreen) { fullScreen = false }
    Scaffold(
        topBar = {
            TopAppBar(
                actions = {
                    com.emfitsolutions.gopreach.ui.components.co.CoFullScreenButton(fullScreen) { fullScreen = !fullScreen }
                    if (congregationId != null) Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More options") }
                        androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Print / PDF") }, enabled = shown.isNotEmpty(),
                                leadingIcon = { Icon(Icons.Rounded.Print, contentDescription = null) },
                                onClick = { menuOpen = false; TableReportExporter.print(context, exportData()) },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Share as Excel") }, enabled = shown.isNotEmpty(),
                                leadingIcon = { Icon(Icons.Rounded.TableChart, contentDescription = null) },
                                onClick = { menuOpen = false; TableReportExporter.shareExcel(context, exportData()) },
                            )
                            onOpenComparative?.let { open ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Comparative Report") },
                                    leadingIcon = { Icon(Icons.Rounded.CompareArrows, contentDescription = null) },
                                    onClick = { menuOpen = false; open() },
                                )
                            }
                            if (canEdit && circuitScope == null) androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Send to Circuit Overseer") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null) },
                                onClick = { menuOpen = false; sendOpen = true },
                            )
                            if (canEdit) androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Attendance calculation") },
                                leadingIcon = { Icon(Icons.Rounded.Calculate, contentDescription = null) },
                                onClick = { menuOpen = false; settingsOpen = true },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(if (showDeleted) "Hide deleted records" else "Show deleted records") },
                                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                onClick = { menuOpen = false; showDeleted = !showDeleted },
                            )
                        }
                    }
                },
                title = { Text("Meeting Attendance") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            if (canEdit && congregationId != null) {
                androidx.compose.material3.ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("Add Attendance") },
                )
            }
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
                )
                else -> Text(congregation?.name.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (congregationId == null) return@Column

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // ---- 1. which month: previous / next, and the filters ----
                item(key = "period") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { stepPeriod(-1) }) { Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous period") }
                        Text(periodLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
                        IconButton(enabled = !atCurrentPeriod || range != null, onClick = { stepPeriod(1) }) { Icon(Icons.Rounded.ChevronRight, contentDescription = "Next period") }
                        androidx.compose.material3.BadgedBox(badge = { if (activeFilters > 0) androidx.compose.material3.Badge { Text(activeFilters.toString()) } }) {
                            IconButton(onClick = { filtersOpen = true }) { Icon(Icons.Rounded.FilterList, contentDescription = "Filters") }
                        }
                    }
                }
                // ---- 2. which meeting ----
                item(key = "types") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = typeFilter == null, onClick = { typeFilter = null }, label = { Text("All meetings") })
                        FilterChip(selected = typeFilter == "MIDWEEK", onClick = { typeFilter = "MIDWEEK" }, label = { Text("Midweek") })
                        FilterChip(selected = typeFilter == "WEEKEND", onClick = { typeFilter = "WEEKEND" }, label = { Text("Weekend") })
                    }
                }
                // ---- 3. the answer at a glance: average, recorded and missing for each meeting ----
                item(key = "glance") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        summaryTypes.forEach { type ->
                            val s = AttendanceSummaries.of(shown.filter { it.meetingType == type }, expectedByType?.get(type) ?: 0)
                            val expected = expectedByType?.get(type)
                            val missing = expected?.let { (it - s.recorded).coerceAtLeast(0) }
                            val color = attendanceColor(type)
                            Card(
                                modifier = Modifier.weight(1f),
                                colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.35f)),
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(type.label + " Meeting", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = color)
                                    Text(
                                        s.average?.let { AttendanceCalculator.display(if (rounding == AttendanceRounding.ROUNDED) AttendanceCalculator.roundHalfUp(it) else it, rounding) } ?: "—",
                                        fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 32.sp,
                                    )
                                    Text("average attendance", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        "Recorded: ${s.recorded}" + (expected?.let { " of $it" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
                                    )
                                    if (missing != null && missing > 0) Text("$missing missing", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                // ---- 4. the records: List View (a card per meeting) or Table View ----
                item(key = "toolbar") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "${shown.size} meeting${if (shown.size == 1) "" else "s"}" + (if (query.isNotBlank()) " matching “$query”" else ""),
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                        )
                        if (query.isNotBlank() || showDeleted) TextButton(onClick = { query = ""; showDeleted = false }) { Text("Clear") }
                    }
                    com.emfitsolutions.gopreach.ui.components.RecordViewToggle(viewMode)
                }
                message?.let { text -> item(key = "message") { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }
                if (filtered.isEmpty()) {
                    item(key = "none") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.EventBusy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                            Text("No attendance recorded for $periodLabel", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (canEdit) "Tap “Add Attendance” to record a Midweek or Weekend meeting." else "Nothing has been recorded for this period yet.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                } else if (viewMode.table) {
                    stickyHeader(key = "head") {
                        Row(modifier = Modifier.background(MaterialTheme.colorScheme.surface).horizontalScroll(hScroll)) {
                            Cell("Date", W_DATE, true, HEAD_FILL); Cell("Meeting", W_TYPE, true, HEAD_FILL)
                            Cell("Treasures", W_PART, true, HEAD_FILL); Cell("Apply Yourself", W_PART, true, HEAD_FILL); Cell("Living as Christians", W_PART, true, HEAD_FILL)
                            Cell("Public Meeting", W_PART, true, HEAD_FILL); Cell("Watchtower Study", W_PART, true, HEAD_FILL); Cell("Official Attendance", W_OFFICIAL, true, HEAD_FILL)
                            if (canEdit) Cell("Actions", W_ACTIONS, true, HEAD_FILL)
                        }
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
                                    TextButton(onClick = { historyOf = r }, contentPadding = pad) { Text(if (locked) "History (locked)" else "History", style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                        }
                    }
                } else {
                    items(filtered, key = { it.id }) { r ->
                        MeetingCard(
                            r = r, locked = r.serviceMonth in frozen, canEdit = canEdit, dateText = cardDateFmt.format(Date(r.meetingDate)),
                            onEdit = { editing = r }, onDelete = { deleting = r }, onHistory = { historyOf = r },
                        )
                    }
                }
                item(key = "foot") {
                    Text(
                        "Attendance is " + rounding.label.lowercase() + ". A missing meeting is shown as missing — never as zero.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // The mandatory end-of-report Summary: only what is shown above (after the filters and search).
                item(key = "end-summary") { SummaryCard(summaryRows, Modifier.padding(top = 2.dp)) }
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
        var dQuery by remember { mutableStateOf(query) }
        AlertDialog(
            onDismissRequest = { filtersOpen = false },
            title = { Text("Filters") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dQuery, onValueChange = { dQuery = it }, label = { Text("Search date, meeting or remarks") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { if (dQuery.isNotEmpty()) IconButton(onClick = { dQuery = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") } },
                    )
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
                    year = dYear; month = dMonth; typeFilter = dType; showDeleted = dDeleted; query = dQuery
                    if (!dKeepRange) range = null
                    filtersOpen = false
                }) { Text("Apply Filters") }
            },
            dismissButton = {
                TextButton(onClick = {
                    range = null; month = now.get(Calendar.MONTH); year = now.get(Calendar.YEAR); typeFilter = null; showDeleted = false; query = ""; filtersOpen = false
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

/** The colour that tells the two meetings apart everywhere on this screen. */
private fun attendanceColor(type: MeetingType): Color = if (type == MeetingType.MIDWEEK) Color(0xFF1E88E5) else Color(0xFF8E24AA)

/**
 * One meeting as a card: when, which meeting, the attendance that counts (large), the counts it was made from, and - for the roles that
 * manage attendance - Edit / History / Delete. A month already received by the Circuit Overseer is locked: only its history can be opened.
 */
@Composable
private fun MeetingCard(
    r: MeetingAttendance,
    locked: Boolean,
    canEdit: Boolean,
    dateText: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onHistory: () -> Unit,
) {
    val color = attendanceColor(r.meetingType)
    val partLabels = if (r.meetingType == MeetingType.MIDWEEK) listOf("Treasures", "Apply", "Living") else listOf("Public Talk", "Watchtower")
    val counts = partLabels.mapIndexed { i, label -> label + " " + (r.parts.getOrNull(i)?.toString() ?: "—") }.joinToString("  ·  ")
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        r.meetingType.label,
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color,
                        modifier = Modifier.background(color.copy(alpha = 0.12f), androidx.compose.foundation.shape.CircleShape).padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                    Text(
                        dateText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        textDecoration = if (r.deleted) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    )
                    Text(counts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    r.remarks?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(AttendanceCalculator.display(r.officialAttendance, r.roundingMode), fontSize = 32.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, color = color)
                    Text("attendance", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (r.deleted) Text("Deleted — kept in the history", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            if (locked) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Month received by the Circuit Overseer — locked", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (canEdit) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!r.deleted && !locked) {
                    TextButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp)); Text("  Edit") }
                    TextButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp)); Text("  Delete") }
                }
                TextButton(onClick = onHistory) { Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(18.dp)); Text("  History") }
            }
        }
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

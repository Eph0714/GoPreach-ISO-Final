package com.emfitsolutions.gopreach.ui.screens.fieldservicereport

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Add
import com.emfitsolutions.gopreach.ui.components.ViewSegments
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import com.emfitsolutions.gopreach.data.model.ReportStatus as RS
import com.emfitsolutions.gopreach.platform.SimpleDateFormat
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.sp
import com.emfitsolutions.gopreach.platform.Locale
import com.emfitsolutions.gopreach.platform.Date
import androidx.compose.foundation.background
import com.emfitsolutions.gopreach.ui.components.RecordFound
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.ui.components.DualPeriodFilter
import com.emfitsolutions.gopreach.ui.screens.circuit.CircuitScopeStore
import com.emfitsolutions.gopreach.ui.screens.circuit.SelectedCongregationBar
import com.emfitsolutions.gopreach.ui.components.ComparativeLineGraph
import com.emfitsolutions.gopreach.ui.components.comparativeGraphHtml
import com.emfitsolutions.gopreach.ui.components.MonthRange
import kotlinx.coroutines.flow.map
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.SimpleDropdown
import kotlinx.coroutines.flow.emptyFlow
import com.emfitsolutions.gopreach.platform.Calendar

// Approximations of the sample's theme fills, shared with the print layout.
internal val FILL_REPORTS = Color(0xFFFBE3D6)
internal val FILL_HOURS = Color(0xFFC1D3EA)
internal val FILL_BIBLE = Color(0xFFCBEBCD)
internal val FILL_TOTAL = Color(0xFFFFFF00)

internal val W_NO = 36.dp
internal val W_STATUS = 64.dp
internal val W_NAME = 250.dp
internal val W_NUM = 56.dp
internal val W_REMARKS = 200.dp
internal val W_ACTIONS = 130.dp

/** Dropdown key for the "All FS Groups" choice. */
private const val ALL_GROUPS = "__all__"

/** The Circuit Overseer has nothing to search until a month has been submitted. */
private fun coModeEmpty(coMode: Boolean, visibleMonthCount: Int) = coMode && visibleMonthCount == 0

/** Month starts, newest first: this month back through the previous 35. */
private fun monthChoices(): List<Long> = (0 until 36).map { fieldServiceMonthStart(it) }

/**
 * Field Service Report — an FS Group's (or every FS Group's) report from its publishers' own
 * monthly reports, laid out like the reference "FIELD SERVICE REPORT SAMPLE.xlsx": a number under
 * the publisher's status in No. of Reports, hours under AP/RP, Bible Studies under the publisher's
 * own status, totals at the bottom. Pick one month, or a From–To range (e.g. September 2026 to
 * March 2027), which is totalled per publisher. Exports to Excel (one sheet per group, same
 * layout and styles) and prints / saves as PDF.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FieldServiceReportScreen(
    fixedCongregationId: String?,
    currentPersonId: String,
    /** A Circuit Overseer sees only these congregations in the picker (null = no restriction). Never widened by the screen. */
    allowedCongregationIds: Set<String>? = null,
    onBack: () -> Unit,
    /** Whether this role may send the month's report to the Circuit Overseer (Admin, Coordinator Elder, Service Overseer, Secretary). */
    canSendToCircuit: Boolean = false,
    /** The Circuit Overseer: sees only the months the congregation submitted, and receives / returns them with remarks. */
    coMode: Boolean = false,
    /** What the ACTIVE role may do with publishers' records here (null = view only): whole congregation, one FS Group, or everything. */
    recordScope: com.emfitsolutions.gopreach.domain.RecordScope? = null,
    /** The active role in words, shown beside the Table View. */
    activeRoleLabel: String? = null,
    currentRole: com.emfitsolutions.gopreach.data.model.AdminRole? = null,
    /** Whether this role may Lock / Unlock publisher reports in the List View. */
    canManageLocks: Boolean = false,
    /** Opens the second section of this module, Monthly Report Snapshots (null = this person has no snapshots to open). */
    onOpenSnapshots: (() -> Unit)? = null,
    viewModel: FieldServiceReportViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    // The Circuit Overseer's "View Report" / "Print" hand-off: open straight to that congregation's whole report for the month.
    val handoff = remember { ReportHandoff.consume() }
    val savedRange = org.koin.compose.koinInject<com.emfitsolutions.gopreach.data.repository.ReportSubmissionPreferences>()
        .range(currentPersonId, currentRole?.name.orEmpty(), fixedCongregationId)
    val startMonth = handoff.month ?: savedRange?.second?.coerceAtMost(fieldServiceMonthStart(0)) ?: fieldServiceMonthStart(0)
    val allCongregations by viewModel.congregations.collectAsStateWithLifecycle(initialValue = emptyList())
    val congregations = if (allowedCongregationIds == null) allCongregations else allCongregations.filter { it.id in allowedCongregationIds }
    // A Circuit Overseer (restricted picker) must choose a congregation first — nothing is auto-selected and no records
    // are shown until then. The choice is shared with Publishers and Elders & MS (CircuitScopeStore) and can be changed.
    val restricted = allowedCongregationIds != null
    var pickedCongregationId by rememberSaveable { mutableStateOf<String?>(null) }
    val chosenCongregationId = if (restricted) CircuitScopeStore.congregation else pickedCongregationId
    // A restricted picker never keeps a congregation outside its allowed set (e.g. after an assignment was removed).
    val congregationId = (fixedCongregationId ?: chosenCongregationId)
        ?.takeIf { allowedCongregationIds == null || it in allowedCongregationIds }
    val groupsAll by remember(congregationId) { viewModel.groupsFor(congregationId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    // A group role sees (and can pick) ONLY its own FS Group — the dropdown cannot be used to reach another one.
    val groupScopeId = recordScope?.groupId
    val groups = if (groupScopeId != null) groupsAll.filter { it.id == groupScopeId } else groupsAll
    val myGroupId by remember(currentPersonId) { viewModel.myGroupId(currentPersonId) }.collectAsStateWithLifecycle(initialValue = null)

    // Opens on the whole congregation: All FS Groups, grouped by Congregation.
    var selectedGroupId by rememberSaveable { mutableStateOf<String?>(groupScopeId ?: ALL_GROUPS) }
    if (groupScopeId != null && selectedGroupId != groupScopeId) selectedGroupId = groupScopeId
    // Pre-select the user's own group once the list is known (and never leave a stale choice).
    LaunchedEffect(groups, myGroupId) {
        if (selectedGroupId == ALL_GROUPS) return@LaunchedEffect
        if (selectedGroupId == null || groups.none { it.id == selectedGroupId }) {
            selectedGroupId = groups.firstOrNull { it.id == myGroupId }?.id
        }
    }
    val showingAll = selectedGroupId == ALL_GROUPS
    val reportGroups = if (showingAll) groups else groups.filter { it.id == selectedGroupId }
    // "Group by" (only with All FS Groups): one sheet per FS Group, or one combined sheet for the whole congregation.
    var groupByCongregation by rememberSaveable { mutableStateOf(true) }
    // Opens full screen: system bars and the filter controls are hidden so the report fills the display; the toolbar icon brings them back.
    var fullScreen by rememberSaveable { mutableStateOf(true) }
    val fullScreenActive = fullScreen && congregationId != null // with no congregation chosen yet the picker must stay visible
    // "Table only": hides the toolbar, the congregation bar, the Circuit Overseer panel and the search box so the report table fills the whole display.
    var tableOnly by rememberSaveable { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = tableOnly) { tableOnly = false }
    com.emfitsolutions.gopreach.ui.components.map.HideSystemBarsEffect(fullScreenActive || tableOnly)
    val wholeCongregation = if (showingAll && groupByCongregation) congregations.firstOrNull { it.id == congregationId } else null

    // The period: From..To months (both default to this month = a single-month report).
    val months = remember { monthChoices() }
    var fromMonth by remember { mutableLongStateOf(startMonth) }
    var toMonth by remember { mutableLongStateOf(startMonth) }
    val monthFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }
    fun labelOf(month: Long) = monthFormat.format(Date(month))

    // Circuit Overseer: only the submitted months of the chosen congregation exist for them; their records are mirrored on demand.
    val visibleMonths by remember(congregationId) { viewModel.visibleMonthsFor(congregationId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    if (coMode) {
        LaunchedEffect(visibleMonths.map { it.periodMonth }) {
            if (visibleMonths.isNotEmpty() && visibleMonths.none { it.periodMonth == fromMonth }) { fromMonth = visibleMonths.first().periodMonth; toMonth = fromMonth }
        }
    }

    val sheetsFlow = remember(reportGroups.map { it.id }, fromMonth, toMonth, wholeCongregation?.id) {
        if (reportGroups.isEmpty()) emptyFlow() else viewModel.sheetsFor(reportGroups, fromMonth, toMonth, wholeCongregation)
    }
    val sheets by sheetsFlow.collectAsStateWithLifecycle(initialValue = null)

    // Two-range comparison (first range vs second range), using the shared DualPeriodFilter.
    var compareMode by rememberSaveable { mutableStateOf(false) }
    var periods by remember { mutableStateOf<Pair<MonthRange, MonthRange>?>(null) }
    val periodA = periods?.first
    val periodB = periods?.second
    var compareView by rememberSaveable { mutableStateOf("report") }
    val listAFlow = remember(reportGroups.map { it.id }, periodA, compareMode) {
        if (!compareMode || periodA == null || reportGroups.isEmpty()) emptyFlow() else viewModel.listItemsFor(reportGroups, periodA.start, periodA.end)
    }
    val listBFlow = remember(reportGroups.map { it.id }, periodB, compareMode) {
        if (!compareMode || periodB == null || reportGroups.isEmpty()) emptyFlow() else viewModel.listItemsFor(reportGroups, periodB.start, periodB.end)
    }
    val listItemsA by listAFlow.collectAsStateWithLifecycle(initialValue = null)
    val listItemsB by listBFlow.collectAsStateWithLifecycle(initialValue = null)
    val sheetsAFlow = remember(reportGroups.map { it.id }, periodA, wholeCongregation?.id, compareMode) {
        if (!compareMode || periodA == null || reportGroups.isEmpty()) emptyFlow()
        else viewModel.sheetsFor(reportGroups, periodA.start, periodA.end, wholeCongregation).map { list -> list.map { it.copy(periodTag = periodA.label()) } }
    }
    val sheetsBFlow = remember(reportGroups.map { it.id }, periodB, wholeCongregation?.id, compareMode) {
        if (!compareMode || periodB == null || reportGroups.isEmpty()) emptyFlow()
        else viewModel.sheetsFor(reportGroups, periodB.start, periodB.end, wholeCongregation).map { list -> list.map { it.copy(periodTag = periodB.label()) } }
    }
    val sheetsA by sheetsAFlow.collectAsStateWithLifecycle(initialValue = null)
    val sheetsB by sheetsBFlow.collectAsStateWithLifecycle(initialValue = null)
    var graphIndex by rememberSaveable { mutableStateOf(0) }
    val metricsAFlow = remember(reportGroups.map { it.id }, periodA, wholeCongregation?.id, compareMode) {
        if (!compareMode || periodA == null || reportGroups.isEmpty()) emptyFlow() else viewModel.monthlyMetrics(reportGroups, periodA, wholeCongregation)
    }
    val metricsBFlow = remember(reportGroups.map { it.id }, periodB, wholeCongregation?.id, compareMode) {
        if (!compareMode || periodB == null || reportGroups.isEmpty()) emptyFlow() else viewModel.monthlyMetrics(reportGroups, periodB, wholeCongregation)
    }
    val metricsAState by metricsAFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val metricsBState by metricsBFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val fsMetrics = remember(metricsAState, metricsBState) { fieldServiceGraphMetrics(metricsAState, metricsBState) }
    var query by rememberSaveable { mutableStateOf("") }
    fun matches(name: String, remarks: String = "") = query.isBlank() || name.contains(query, ignoreCase = true) || remarks.contains(query, ignoreCase = true)
    // Which publishers to list: everyone, those with a report, or those with no report (to find who still needs a record entered by hand).
    var reportFilter by rememberSaveable { mutableStateOf(0) }
    fun passes(hasReport: Boolean) = reportFilter == 0 || (reportFilter == 1) == hasReport
    val searchedSheets = if (query.isBlank() && reportFilter == 0) sheets else sheets?.map { s -> s.copy(rows = s.rows.filter { matches(it.name, it.remarks) && passes(it.hasReport) }) }
    val singleReady = searchedSheets?.takeIf { s -> s.isNotEmpty() && s.any { it.rows.isNotEmpty() } }
    val compareSheets = (sheetsA.orEmpty() + sheetsB.orEmpty()).filter { it.rows.isNotEmpty() }
    val ready = if (compareMode) compareSheets.takeIf { it.isNotEmpty() } else singleReady

    // List View: every report in the period with its lock state. The screen opens in the Table (Report) view: the publishers' names, like the sample.
    var listView by rememberSaveable { mutableStateOf(false) }

    // Table View entry: Add / Edit / Delete a publisher record of the month, for the active role scope only.
    val manual: com.emfitsolutions.gopreach.ui.screens.manualreport.ManualFieldServiceViewModel = koinViewModel()
    val manualPublishers by remember(congregationId, groupScopeId) { congregationId?.let { manual.publishersIn(it, groupScopeId) } ?: emptyFlow() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val manualReports by remember(congregationId) { congregationId?.let { manual.reportsIn(it) } ?: emptyFlow() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var sendOpen by rememberSaveable { mutableStateOf(false) }
    // A month picked in the Send view, shown as the report table on demand; records can be added to it there.
    var previewMonth by rememberSaveable { mutableStateOf<Long?>(null) }
    // The month the record actions (Add / Edit / Delete) work on: the previewed month, else the one on screen.
    val recordMonth = previewMonth ?: fromMonth
    val monthStatus by remember(congregationId, recordMonth) { viewModel.submissionFor(congregationId, recordMonth) }.collectAsStateWithLifecycle(initialValue = null)
    val monthLocked = monthStatus?.isLocked == true
    val editingAllowed = recordScope != null && !coMode && congregationId != null && fromMonth == toMonth && !compareMode && !listView
    val editingEnabled = editingAllowed && !monthLocked
    val previewEditing = previewMonth != null && recordScope != null && !coMode && congregationId != null && !monthLocked
    val recordEditingActive = if (previewMonth != null) previewEditing else editingEnabled
    val sendCongregation = congregations.firstOrNull { it.id == congregationId }
    // What is sent to the Circuit Overseer is always the whole congregation as ONE report, whatever grouping is chosen for viewing.
    val previewSheetsFlow = remember(reportGroups.map { it.id }, previewMonth, sendCongregation?.id) {
        val m = previewMonth
        if (m == null || reportGroups.isEmpty() || sendCongregation == null) emptyFlow() else viewModel.sheetsFor(reportGroups, m, m, sendCongregation)
    }
    val previewSheets by previewSheetsFlow.collectAsStateWithLifecycle(initialValue = null)
    val showSendToggle = canSendToCircuit && !restricted && congregationId != null && !compareMode
    var editTarget by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<FieldServiceReportRow?>(null) }
    val itemsFlow = remember(reportGroups.map { it.id }, fromMonth, toMonth) {
        if (reportGroups.isEmpty()) emptyFlow() else viewModel.listItemsFor(reportGroups, fromMonth, toMonth)
    }
    val allListItems by itemsFlow.collectAsStateWithLifecycle(initialValue = null)
    val listItems = if (query.isBlank() && reportFilter == 0) allListItems else allListItems?.filter { (matches(it.publisherName) || it.groupName.contains(query, ignoreCase = true)) && passes(it.report != null) }
    // All / With Report / No Record, with how many of each (the Records menu), so the publishers still to be entered are one tap away.
    val withReportCount = if (listView) allListItems.orEmpty().count { it.report != null } else sheets.orEmpty().sumOf { s -> s.rows.count { it.hasReport } }
    val noReportCount = if (listView) allListItems.orEmpty().count { it.report == null } else sheets.orEmpty().sumOf { s -> s.rows.count { !it.hasReport } }
    // Save this month's Field Service Record: for the Overseer, Servant and Assistant of an FS Group, for their OWN group and the month shown (a small bar at the bottom).
    val snapshotSlot = com.emfitsolutions.gopreach.ui.screens.groupmodules.officerSlotOf(currentPersonId, congregationId, groupsAll)
    val showSaveBar = !tableOnly && !coMode && !compareMode && !listView && fromMonth == toMonth && snapshotSlot != null && congregationId != null && !sendOpen
    var pendingLock by remember { mutableStateOf<FieldServiceReportListItem?>(null) }
    var pendingUnlock by remember { mutableStateOf<FieldServiceReportListItem?>(null) }
    var confirmLockAll by remember { mutableStateOf(false) }
    var rejectTarget by remember { mutableStateOf<FieldServiceReportListItem?>(null) }
    var reverseTarget by remember { mutableStateOf<FieldServiceReportListItem?>(null) }

    // Back from a chosen congregation returns to the congregation picker (Super-Admin / Circuit Overseer); it only leaves the report from the picker itself.
    val backToPicker = fixedCongregationId == null && congregationId != null
    val goBack: () -> Unit = {
        if (sendOpen) sendOpen = false // the Send view closes back to the report first
        else if (backToPicker) { pickedCongregationId = null; if (restricted) CircuitScopeStore.selectCongregation(null); selectedGroupId = ALL_GROUPS; query = "" } else onBack()
    }
    androidx.activity.compose.BackHandler(enabled = backToPicker || sendOpen) { goBack() }
    var shareMenu by remember { mutableStateOf(false) }

    // One month at a time (e.g. September 2026), with arrows to the previous / next month and the full list one tap away. Built once so the Full Screen views
    // keep the month on screen and selectable.
    val monthKeys = (if (coMode) visibleMonths.map { it.periodMonth } else months).distinct().sortedDescending()
    val monthAt = monthKeys.indexOf(fromMonth)
    val monthStepper: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier) {
            MonthStepper(
                label = labelOf(fromMonth),
                options = monthKeys.map { it.toString() to labelOf(it) },
                onPick = { key -> fromMonth = key.toLong(); toMonth = fromMonth },
                onOlder = if (monthAt >= 0 && monthAt < monthKeys.lastIndex) ({ fromMonth = monthKeys[monthAt + 1]; toMonth = fromMonth }) else null,
                onNewer = if (monthAt > 0) ({ fromMonth = monthKeys[monthAt - 1]; toMonth = fromMonth }) else null,
            )
        }
    }

    com.emfitsolutions.gopreach.ui.components.Scaffold(
        adminTab = if (tableOnly || restricted) null else com.emfitsolutions.gopreach.ui.components.AdminTab.FIELD_SERVICE,
        topBar = {
            if (!tableOnly) TopAppBar(
                title = { Text("Field Service Report", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = goBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { fullScreen = !fullScreen }) {
                        Icon(if (fullScreenActive) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, contentDescription = if (fullScreenActive) "Show filters" else "Full screen")
                    }
                    if (!compareMode) IconButton(onClick = { tableOnly = true }, enabled = congregationId != null && !listView) {
                        Icon(Icons.Rounded.OpenInFull, contentDescription = "View the table full screen")
                    }
                    if (compareMode) FieldServiceExportMenu(ready = ready, iconOnly = true, graphHtml = { if (compareMode && periodA != null && periodB != null) comparativeGraphHtml(periodA.label(), periodB.label(), metricsAState.map { m -> m.monthStart }, metricsBState.map { m -> m.monthStart }, fsMetrics, fsMetrics[graphIndex.coerceIn(0, fsMetrics.lastIndex)]) else null })
                },
            )
        },
        bottomBar = {
            if (showSaveBar && snapshotSlot != null) {
                com.emfitsolutions.gopreach.ui.screens.groupmodules.SaveSnapshotRow(
                    slot = snapshotSlot, monthStart = fromMonth, currentPersonId = currentPersonId, report = viewModel, onOpenSnapshots = onOpenSnapshots,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // The slim bar with the month and the way back floats over the top of the table-only view: leave room for it.
            if (tableOnly) androidx.compose.foundation.layout.Spacer(Modifier.height(64.dp))
            if (!tableOnly && restricted && congregationId != null) {
                SelectedCongregationBar(
                    name = congregations.firstOrNull { it.id == congregationId }?.name.orEmpty(),
                    onChange = { pickedCongregationId = null; CircuitScopeStore.selectCongregation(null); selectedGroupId = ALL_GROUPS },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            // The two sections of this module: the report itself and the permanent Monthly Report Snapshots.
            if (!tableOnly && !coMode && onOpenSnapshots != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(selected = true, onClick = {}, label = { Text("Field Service Reports") })
                androidx.compose.material3.FilterChip(selected = false, onClick = onOpenSnapshots, label = { Text("Saved Field Service Records") })
            }
            if (!fullScreenActive && !tableOnly && !(restricted && congregationId == null)) Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fixedCongregationId == null && !restricted) {
                    SimpleDropdown(
                        label = "Congregation",
                        selectedLabel = congregations.firstOrNull { it.id == congregationId }?.name.orEmpty(),
                        options = congregations.map { it.id to it.name },
                        onSelected = { pickedCongregationId = it; if (restricted) CircuitScopeStore.selectCongregation(it); selectedGroupId = ALL_GROUPS },
                    )
                }
                // Only the month is picked here (no FS Group box): an officer sees their own group, the others see everyone, with "All Publishers / By FS Group" below.
                if (compareMode) {
                    // Two month RANGES to compare, e.g. Feb 2025 to Oct 2025 against Mar 2026 to Sep 2026 (the same month twice = one month).
                    DualPeriodFilter(
                        initialA = periods?.first ?: MonthRange.ofMonth(1),
                        initialB = periods?.second ?: MonthRange.ofMonth(0),
                        applyLabel = "Apply Filter",
                        onApply = { a, b -> periods = a to b },
                    )
                } else {
                monthStepper(Modifier)

                }
            }
                if (fullScreenActive && !tableOnly && !compareMode && congregationId != null) monthStepper(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                if (!fullScreenActive && !tableOnly && !(restricted && congregationId == null)) Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val mainIndex = if (compareMode) 2 else if (listView) 1 else 0
                    ViewSegments(listOf("Report" to Icons.Rounded.TableChart, "List" to Icons.Rounded.ViewList, "Compare" to Icons.Rounded.CompareArrows), mainIndex) { picked ->
                        when (picked) {
                            0 -> { compareMode = false; listView = false }
                            1 -> { compareMode = false; listView = true }
                            else -> { compareMode = true; listView = false }
                        }
                    }
                    if (compareMode) ViewSegments(listOf("Report" to Icons.Rounded.TableChart, "List" to Icons.Rounded.ViewList, "Line Graph" to Icons.Rounded.ShowChart), listOf("report", "list", "graph").indexOf(compareView).coerceAtLeast(0)) { picked ->
                        compareView = listOf("report", "list", "graph")[picked]
                    }
                }
            if (!tableOnly && coMode && congregationId != null && !compareMode) {
                visibleMonths.firstOrNull { it.periodMonth == fromMonth }?.let { st ->
                    CoReviewPanel(
                        status = st,
                        congregationName = congregations.firstOrNull { it.id == congregationId }?.name.orEmpty(),
                        monthLabel = labelOf(fromMonth),
                        actorPersonId = currentPersonId,
                        canAct = true,
                        viewModel = viewModel,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            if (!tableOnly && recordScope != null && editingAllowed) {
                RecordScopeBar(
                    activeRole = activeRoleLabel ?: currentRole?.name?.replace('_', ' ').orEmpty(),
                    scope = recordScope,
                    congregationName = congregations.firstOrNull { it.id == congregationId }?.name,
                    groupName = groupsAll.firstOrNull { it.id == groupScopeId }?.name,
                    monthLabel = labelOf(fromMonth),
                    status = monthStatus,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (!editingEnabled && monthLocked) {
                    Text(
                        com.emfitsolutions.gopreach.data.model.MONTH_LOCKED_MESSAGE,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            // (Saving this month's Field Service Record is the small bar at the bottom of the screen.)
            // Sending is about the whole congregation's month(s) - the panel picks its own months - so it shows in Report View and List View alike,
            // whichever FS Group, grouping or month is on screen.
            // The module's actions in ONE row, like the website: Records (Add Record, All / With Report / No Record), Export, then Send to Circuit Overseer.
            val sendingNow = showSendToggle && sendOpen
            if (!tableOnly && congregationId != null && !compareMode && !coModeEmpty(coMode, visibleMonths.size)) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!sendingNow) {
                        RecordsMenu(
                            canAdd = editingEnabled, onAdd = { adding = true }, filter = reportFilter, onFilter = { reportFilter = it },
                            all = withReportCount + noReportCount, withReport = withReportCount, noReport = noReportCount,
                        )
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    // One Export button (Print, PDF, Excel, Word), the same as the Ministry's.
                    if (!sendingNow) FieldServiceExportMenu(ready = ready, iconOnly = false, graphHtml = { null })
                    if (showSendToggle) {
                        androidx.compose.material3.Surface(
                            onClick = { sendOpen = !sendOpen }, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = Color(0xFFF2B705),
                            modifier = Modifier.size(40.dp).semantics { contentDescription = if (sendOpen) "Hide Send to Circuit Overseer" else "Send to Circuit Overseer" },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(if (sendOpen) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.Send, contentDescription = null, tint = Color(0xFF1B1500), modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            // How to view the publishers: all of them as ONE report, or one table per FS Group. (Sending to the Circuit Overseer is always one report.)
            if (!tableOnly && showingAll && congregationId != null && !compareMode && !listView && !sendOpen) {
                Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(selected = groupByCongregation, onClick = { groupByCongregation = true }, label = { Text("All Publishers") })
                    androidx.compose.material3.FilterChip(selected = !groupByCongregation, onClick = { groupByCongregation = false }, label = { Text("By FS Group") })
                }
            }

            // Send to Circuit Overseer opens as its own view (the report and its publishers list would only repeat what is already there).
            val sending = !tableOnly && showSendToggle && sendOpen
            if (!tableOnly && congregationId != null && !compareMode && !sending && !coModeEmpty(coMode, visibleMonths.size)) {
                androidx.compose.material3.OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search records...") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") } },
                )
            }
            val current = searchedSheets
            when {
                sending -> congregations.firstOrNull { it.id == congregationId }?.let { congregation ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        SendToCircuitPanel(
                            congregation = congregation,
                            actorPersonId = currentPersonId,
                            viewModel = viewModel,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            onPreview = { previewMonth = it },
                        )
                    }
                }
                restricted && congregationId == null -> com.emfitsolutions.gopreach.ui.screens.circuit.SelectCongregationPrompt(
                    congregations = congregations,
                    onSelect = { pickedCongregationId = it; CircuitScopeStore.selectCongregation(it); selectedGroupId = ALL_GROUPS },
                    hint = "Choose a congregation under your assigned Circuit to view its Field Service Report.",
                )
                coMode && congregationId != null && visibleMonths.isEmpty() -> Message("This congregation has not submitted any Field Service Report yet. Months appear here once they are submitted.")
                selectedGroupId == null || reportGroups.isEmpty() -> Message(
                    if (restricted && congregationId == null) "Select a Congregation — choose a congregation under your assigned Circuit to view its Field Service Report."
                    else if (fixedCongregationId == null && congregationId == null) "Select a congregation, then an FS Group."
                    else if (groups.isEmpty()) "This congregation has no FS Groups yet."
                    else "Select an FS Group (or All FS Groups) to see its report.",
                )
                compareMode -> {
                    val a = periodA
                    val b = periodB
                    if (a == null || b == null) Message("Choose the first and second month ranges, then tap Apply Filter.")
                    else if (compareView == "list") CompareListView(a.label(), listItemsA, b.label(), listItemsB)
                    else if (compareView == "graph") Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PeriodComparisonSummary(a.label(), sheetsA.orEmpty(), b.label(), sheetsB.orEmpty())
                        ComparativeLineGraph(a.label(), b.label(), metricsAState.map { m -> m.monthStart }, metricsBState.map { m -> m.monthStart }, fsMetrics, graphIndex) { graphIndex = it }
                    }
                    else Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        PeriodComparisonSummary(a.label(), sheetsA.orEmpty(), b.label(), sheetsB.orEmpty())
                        PeriodComparisonTable(a.label(), sheetsA.orEmpty(), b.label(), sheetsB.orEmpty())
                        listOf(a.label() to sheetsA, b.label() to sheetsB).forEach { (label, list) ->
                            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            val withRows = list.orEmpty().filter { it.rows.isNotEmpty() }
                            if (list == null) Text("Loading…", style = MaterialTheme.typography.bodySmall)
                            else if (withRows.isEmpty()) Text("No records in this period.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else withRows.forEach { SheetTable(it) }
                        }
                    }
                }
                listView -> {
                    val items = listItems
                    when {
                        items == null -> Message("Loading…")
                        items.isEmpty() -> Column {
                            RecordFound(0, Modifier.padding(horizontal = 16.dp))
                            Message("No publishers are assigned to ${if (showingAll) "these FS Groups" else "this FS Group"} yet.")
                        }
                        else -> ReportListView(
                            entries = items,
                            canManageLocks = canManageLocks,
                            onLock = { pendingLock = it },
                            onUnlock = { pendingUnlock = it },
                            onLockAll = { confirmLockAll = true },
                            actionsEnabled = !monthLocked,
                            onApprove = { viewModel.approveAccess(it.report ?: return@ReportListView, currentPersonId, groupScopeId) },
                            onReject = { rejectTarget = it },
                            onReverse = { reverseTarget = it },
                            summary = FieldServiceSummary.ofItems(items),
                        )
                    }
                }
                current == null -> Message("Loading…")
                ready == null -> Message("No publishers are assigned to ${if (showingAll) "these FS Groups" else "this FS Group"} yet.")
                else -> {
                    val hScroll = rememberScrollState()
                    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                        sheetItems(
                            current.filter { it.rows.isNotEmpty() }, hScroll,
                            actions = if (editingEnabled) RowActions(onEdit = { editTarget = it.personId }, onDelete = { deleteTarget = it }) else null,
                        )
                        item(key = "end-summary") { SummaryCard(FieldServiceSummary.ofSheets(current.filter { it.rows.isNotEmpty() }), Modifier.padding(vertical = 16.dp)) }
                    }
                }
            }
        }
        if (tableOnly) {
            // What is left on screen besides the table: the month (to change it) and a small round button to come back.
            Row(
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                monthStepper(Modifier.weight(1f))
                androidx.compose.material3.SmallFloatingActionButton(
                    onClick = { tableOnly = false },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ) { Icon(Icons.Rounded.CloseFullscreen, contentDescription = "Exit full screen table") }
            }
        }
        }
    }

    previewMonth?.let { month ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { previewMonth = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { previewMonth = null }) { Icon(Icons.Rounded.Close, contentDescription = "Close preview") }
                        Text(labelOf(month) + " – Preview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (previewEditing) androidx.compose.material3.Button(onClick = { adding = true }, modifier = Modifier.padding(end = 8.dp)) { Text("Add Record") }
                    }
                    if (monthLocked) Text(
                        com.emfitsolutions.gopreach.data.model.MONTH_LOCKED_MESSAGE,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                    val shown = previewSheets?.filter { it.rows.isNotEmpty() }
                    when {
                        shown == null -> Message("Loading…")
                        shown.isEmpty() -> Message("No records for this month.")
                        else -> {
                            val hScroll = rememberScrollState()
                            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                                sheetItems(shown, hScroll, actions = if (previewEditing) RowActions(onEdit = { editTarget = it.personId }, onDelete = { deleteTarget = it }) else null)
                                item(key = "preview-summary") { SummaryCard(FieldServiceSummary.ofSheets(shown), Modifier.padding(vertical = 16.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (recordEditingActive && (adding || editTarget != null) && congregationId != null) {
        RecordEditDialog(
            month = recordMonth,
            monthLabel = labelOf(recordMonth),
            congregationId = congregationId,
            actorPersonId = currentPersonId,
            actorRole = currentRole,
            scopeGroupId = groupScopeId,
            publishers = manualPublishers,
            existing = manualReports,
            fixedPublisherId = editTarget,
            manual = manual,
            onDismiss = { adding = false; editTarget = null },
        )
    }
    deleteTarget?.let { row ->
        val record = manualReports.firstOrNull { it.publisherPersonId == row.personId && it.periodMonth == recordMonth }
        RecordDeleteDialog(
            name = row.name, monthLabel = labelOf(recordMonth),
            onConfirm = {
                if (record != null && congregationId != null) manual.delete(record, congregationId, currentRole, currentPersonId, groupScopeId)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
    rejectTarget?.let { item ->
        var reason by remember(item.key) { mutableStateOf("") }
        com.emfitsolutions.gopreach.ui.components.AlertDialog(
        scrollBody = true,
            onDismissRequest = { rejectTarget = null },
            title = { Text("Reject access request") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.publisherName + "'s report stays submitted and locked.")
                    androidx.compose.material3.OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text("Reason (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { item.report?.let { viewModel.rejectAccess(it, reason, currentPersonId, groupScopeId) }; rejectTarget = null }) { Text("Reject") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { rejectTarget = null }) { Text("Cancel") } },
        )
    }
    reverseTarget?.let { item ->
        var reason by remember(item.key) { mutableStateOf("") }
        com.emfitsolutions.gopreach.ui.components.AlertDialog(
        scrollBody = true,
            onDismissRequest = { reverseTarget = null },
            title = { Text("Reverse submission") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.publisherName + "'s submission is cancelled and the report opens for editing again; no access request is needed. They submit it again when corrected.")
                    androidx.compose.material3.OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text("Reason (required)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(enabled = reason.isNotBlank(), onClick = { item.report?.let { viewModel.reverseSubmission(it, reason, currentPersonId, groupScopeId) }; reverseTarget = null }) { Text("Reverse") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { reverseTarget = null }) { Text("Cancel") } },
        )
    }
    pendingLock?.let { item ->
        com.emfitsolutions.gopreach.ui.components.AlertDialog(
            onDismissRequest = { pendingLock = null },
            title = { Text("Lock this report?") },
            text = { Text(item.publisherName + "'s report" + (item.monthLabel?.let { " for $it" } ?: "") + " will be locked (posted). The publisher will no longer be able to edit it.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { item.report?.let { viewModel.lock(it, currentPersonId, groupScopeId) }; pendingLock = null }) { Text("Lock") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingLock = null }) { Text("Cancel") } },
        )
    }
    pendingUnlock?.let { item ->
        com.emfitsolutions.gopreach.ui.components.AlertDialog(
            onDismissRequest = { pendingUnlock = null },
            title = { Text("Unlock this report?") },
            text = { Text(item.publisherName + "'s report" + (item.monthLabel?.let { " for $it" } ?: "") + " will be unlocked. The publisher can edit it and submit it again.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { item.report?.let { viewModel.unlock(it, currentPersonId, groupScopeId) }; pendingUnlock = null }) { Text("Unlock") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingUnlock = null }) { Text("Cancel") } },
        )
    }
    if (confirmLockAll) {
        com.emfitsolutions.gopreach.ui.components.AlertDialog(
            onDismissRequest = { confirmLockAll = false },
            title = { Text("Lock all submitted reports?") },
            text = { Text("Every submitted report listed here will be locked (posted). Those publishers will no longer be able to edit them.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { viewModel.lockAll(listItems.orEmpty(), currentPersonId, groupScopeId); confirmLockAll = false }) { Text("Lock all") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmLockAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Message(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun Cell(
    text: String,
    width: Dp,
    modifier: Modifier = Modifier,
    fill: Color = Color.Transparent,
    bold: Boolean = false,
    left: Boolean = false,
) {
    val (cellBg, cellFg) = com.emfitsolutions.gopreach.ui.theme.cellColors(fill)
    Box(
        modifier = modifier.width(width).height(34.dp).background(cellBg).border(0.5.dp, com.emfitsolutions.gopreach.ui.theme.cellBorder()).padding(horizontal = 6.dp),
        contentAlignment = if (left) Alignment.CenterStart else Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = cellFg, maxLines = 1)
    }
}

/** The on-screen version of the sample for one group: heading, publisher lines, and the yellow Total row. */
@Composable
internal fun SheetTable(sheet: FieldServiceReportSheet) {
    val rc = sheet.reportColumns
    val hc = sheet.hourColumns
    Column {
        Text(sheet.groupName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(sheet.titleLine, style = MaterialTheme.typography.bodyMedium)
        Text(sheet.countLine, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        sheet.officerRows.forEachIndexed { index, (label, value) ->
            if (label.isNotBlank()) {
                Text(
                    "$label  $value",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = if (index == sheet.officerRows.lastIndex) Modifier.padding(bottom = 10.dp) else Modifier,
                )
            }
        }

        com.emfitsolutions.gopreach.ui.components.PinnedTable(
            rowCount = sheet.rows.size,
            header = {
            Column {
            // Block headings.
            Row {
                Cell("", W_NO); Cell("", W_STATUS); Cell("", W_NAME)
                Cell("No. of Reports", W_NUM * rc.size, fill = FILL_REPORTS, bold = true)
                Cell("Hours", W_NUM * hc.size, fill = FILL_HOURS, bold = true)
                Cell("Bible Studies", W_NUM * rc.size, fill = FILL_BIBLE, bold = true)
                Cell("", W_REMARKS)
            }
            // Column headings.
            Row {
                Cell("", W_NO)
                Cell("Status", W_STATUS, bold = true)
                Cell("Publisher's Name", W_NAME, bold = true, left = true)
                rc.forEach { Cell(it.label, W_NUM, fill = FILL_REPORTS, bold = true) }
                hc.forEach { Cell(it.label, W_NUM, fill = FILL_HOURS, bold = true) }
                rc.forEach { Cell(it.label, W_NUM, fill = FILL_BIBLE, bold = true) }
                Cell("Remarks", W_REMARKS, bold = true)
            }
            }
            },
            footer = {
            Row {
                Cell("", W_NO, fill = FILL_TOTAL); Cell("", W_STATUS, fill = FILL_TOTAL)
                Cell("Total:", W_NAME, fill = FILL_TOTAL, bold = true, left = true)
                rc.forEach { Cell(sheet.reportCount(it).toString(), W_NUM, fill = FILL_TOTAL, bold = true) }
                hc.forEach { Cell(formatHours(sheet.totalHours(it)), W_NUM, fill = FILL_TOTAL, bold = true) }
                rc.forEach { Cell(sheet.totalBibleStudies(it).toString(), W_NUM, fill = FILL_TOTAL, bold = true) }
                Cell("", W_REMARKS, fill = FILL_TOTAL)
            }
            },
        ) { index ->
                val line = sheet.rows[index]
                Row {
                    Cell(line.number.toString(), W_NO)
                    Cell(line.status.label, W_STATUS)
                    Cell(line.name, W_NAME, left = true)
                    rc.forEach { s -> Cell(if (line.reported && line.status == s) line.reportsCount.toString() else "", W_NUM) }
                    hc.forEach { s -> Cell(if (line.status == s) line.hours?.let { formatHours(it) }.orEmpty() else "", W_NUM) }
                    rc.forEach { s -> Cell(if (line.status == s) line.bibleStudies?.toString().orEmpty() else "", W_NUM) }
                    Cell(line.remarks, W_REMARKS, left = true)
                }
            }
    }
}

private fun statusText(report: com.emfitsolutions.gopreach.data.model.MonthlyReport?): String = when (report?.status) {
    null -> "No report"
    com.emfitsolutions.gopreach.data.model.ReportStatus.DRAFT -> "Unlocked · draft"
    com.emfitsolutions.gopreach.data.model.ReportStatus.SUBMITTED -> "Unlocked · submitted"
    com.emfitsolutions.gopreach.data.model.ReportStatus.RETURNED -> "Unlocked · returned"
    com.emfitsolutions.gopreach.data.model.ReportStatus.CORRECTED -> "Unlocked · corrected"
    com.emfitsolutions.gopreach.data.model.ReportStatus.POSTED -> "Locked"
    com.emfitsolutions.gopreach.data.model.ReportStatus.ACCESS_REQUESTED -> "Locked · access requested"
    com.emfitsolutions.gopreach.data.model.ReportStatus.ACCESS_GRANTED -> "Unlocked · edit access granted"
}

/** One card per report: who, their numbers and lock state at a glance, with clear Lock / Unlock / Approve / Reject / Reverse buttons for admins. */
@Composable
private fun ReportListView(
    entries: List<FieldServiceReportListItem>,
    canManageLocks: Boolean,
    onLock: (FieldServiceReportListItem) -> Unit,
    onUnlock: (FieldServiceReportListItem) -> Unit,
    onLockAll: () -> Unit,
    actionsEnabled: Boolean = true,
    onApprove: (FieldServiceReportListItem) -> Unit = {},
    onReject: (FieldServiceReportListItem) -> Unit = {},
    onReverse: (FieldServiceReportListItem) -> Unit = {},
    summary: List<List<String>> = emptyList(),
) {
    val lockable = entries.count { it.report?.status == RS.SUBMITTED || it.report?.status == RS.CORRECTED }
    val reported = entries.count { it.report != null }
    val lockedCount = entries.count { it.report?.status == RS.POSTED }
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "glance") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                GlanceTile("Reported", reported.toString(), Color(0xFF1565C0), Modifier.weight(1f))
                GlanceTile("Locked", lockedCount.toString(), Color(0xFF1B6E2B), Modifier.weight(1f))
                GlanceTile("No report", (entries.size - reported).toString(), Color(0xFF757575), Modifier.weight(1f))
            }
        }
        if (canManageLocks && lockable > 0) {
            item(key = "lock-all") {
                com.emfitsolutions.gopreach.ui.components.co.CoButton(
                    "Lock all submitted ($lockable)", onLockAll, kind = com.emfitsolutions.gopreach.ui.components.co.CoKind.Primary,
                    icon = Icons.Rounded.Lock, fillWidth = true,
                )
            }
        }
        item { RecordFound(entries.size) }
        items(entries, key = { it.key }) { item ->
            val report = item.report
            val locked = report?.status == RS.POSTED
            val canLock = report?.status == RS.SUBMITTED || report?.status == RS.CORRECTED
            val (tint, icon) = when (report?.status) {
                null -> Color(0xFF757575) to Icons.Rounded.RemoveCircleOutline
                RS.POSTED -> Color(0xFF1B6E2B) to Icons.Rounded.Lock
                RS.SUBMITTED, RS.CORRECTED -> Color(0xFF1565C0) to Icons.Rounded.CheckCircle
                RS.ACCESS_REQUESTED -> Color(0xFFEF6C00) to Icons.Rounded.LockClock
                RS.RETURNED -> Color(0xFFEF6C00) to Icons.AutoMirrored.Rounded.Undo
                else -> Color(0xFF6A1B9A) to Icons.Rounded.EditNote
            }
            com.emfitsolutions.gopreach.ui.components.co.CoCard(
                Modifier.fillMaxWidth(),
                accent = if (report?.status == RS.ACCESS_REQUESTED) com.emfitsolutions.gopreach.ui.components.co.coPalette().warning else null,
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(38.dp).background(tint.copy(alpha = 0.14f), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(item.publisherName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2)
                            Text(
                                listOfNotNull(item.groupName, item.status?.label).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            statusText(report), style = MaterialTheme.typography.labelMedium, color = tint, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.background(tint.copy(alpha = 0.14f), androidx.compose.foundation.shape.RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    if (report != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (item.status?.isPioneer == true) NumberPill("Hours", report.hoursRendered?.let { formatHours(it) } ?: "—")
                            NumberPill("Bible Studies", report.bibleStudiesCount.toString())
                        }
                        Text(report.status.publisherLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (report.status == RS.ACCESS_REQUESTED) {
                            Text("Reason: " + report.accessRequestReason.orEmpty().ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (canManageLocks && actionsEnabled) {
                            val s = report.status
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (s == RS.ACCESS_REQUESTED) {
                                    com.emfitsolutions.gopreach.ui.components.co.CoButton("Approve access", { onApprove(item) }, Modifier.weight(1f), kind = com.emfitsolutions.gopreach.ui.components.co.CoKind.Success, icon = Icons.Rounded.CheckCircle)
                                    com.emfitsolutions.gopreach.ui.components.co.CoButton("Reject", { onReject(item) }, Modifier.weight(1f), kind = com.emfitsolutions.gopreach.ui.components.co.CoKind.Secondary, icon = Icons.Rounded.Close)
                                }
                                if (s == RS.SUBMITTED || s == RS.CORRECTED || s == RS.POSTED || s == RS.ACCESS_REQUESTED || s == RS.ACCESS_GRANTED) {
                                    com.emfitsolutions.gopreach.ui.components.co.CoButton("Reverse", { onReverse(item) }, Modifier.weight(1f), kind = com.emfitsolutions.gopreach.ui.components.co.CoKind.Secondary, icon = Icons.AutoMirrored.Rounded.Undo)
                                }
                            }
                        }
                        if (canManageLocks && (locked || canLock)) {
                            if (locked) com.emfitsolutions.gopreach.ui.components.co.CoButton("Unlock to edit", { onUnlock(item) }, kind = com.emfitsolutions.gopreach.ui.components.co.CoKind.Secondary, icon = Icons.Rounded.LockOpen, fillWidth = true)
                            else com.emfitsolutions.gopreach.ui.components.co.CoButton("Lock", { onLock(item) }, kind = com.emfitsolutions.gopreach.ui.components.co.CoKind.Primary, icon = Icons.Rounded.Lock, fillWidth = true)
                        }
                    }
                }
            }
        }
        if (summary.isNotEmpty()) item(key = "end-summary") { SummaryCard(summary) }
    }
}

@Composable
private fun GlanceTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.background(color.copy(alpha = 0.10f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NumberPill(label: String, value: String) {
    Row(
        Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
    }
}

/** "‹ September 2026 ›": arrows to the previous / next month the report can show; tapping the name opens the full list of months. */
@Composable
private fun MonthStepper(label: String, options: List<Pair<String, String>>, onPick: (String) -> Unit, onOlder: (() -> Unit)?, onNewer: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    // A solid block in the app's colour, so the month is the first thing the eye finds on the report.
    val onTone = MaterialTheme.colorScheme.onPrimary
    androidx.compose.material3.Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = onTone),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 3.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = onOlder != null, onClick = { onOlder?.invoke() }) {
                Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous month", tint = onTone.copy(alpha = if (onOlder != null) 1f else 0.35f))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                androidx.compose.material3.TextButton(onClick = { open = true }, colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = onTone)) {
                    Icon(Icons.Rounded.CalendarMonth, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("  " + label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                }
                androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    options.forEach { (key, text) -> androidx.compose.material3.DropdownMenuItem(text = { Text(text, fontWeight = if (text == label) FontWeight.Bold else FontWeight.Normal) }, onClick = { open = false; onPick(key) }) }
                }
            }
            IconButton(enabled = onNewer != null, onClick = { onNewer?.invoke() }) {
                Icon(Icons.Rounded.ChevronRight, contentDescription = "Next month", tint = onTone.copy(alpha = if (onNewer != null) 1f else 0.35f))
            }
        }
    }
}

/** Side-by-side totals for the two selected periods, summed over every sheet shown for each. */
@Composable
private fun PeriodComparisonSummary(labelA: String, sheetsA: List<FieldServiceReportSheet>, labelB: String, sheetsB: List<FieldServiceReportSheet>) {
    fun reports(s: List<FieldServiceReportSheet>) = s.sumOf { sheet -> sheet.reportColumns.sumOf { sheet.reportCount(it) } }
    fun hours(s: List<FieldServiceReportSheet>) = s.sumOf { sheet -> sheet.hourColumns.sumOf { sheet.totalHours(it) } }
    fun studies(s: List<FieldServiceReportSheet>) = s.sumOf { sheet -> sheet.reportColumns.sumOf { sheet.totalBibleStudies(it) } }
    fun participated(s: List<FieldServiceReportSheet>) = s.sumOf { it.participatedCount }
    androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Comparison", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("", modifier = Modifier.weight(1.2f))
                Text(labelA, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text(labelB, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            }
            listOf(
                Triple("Reports", reports(sheetsA).toString(), reports(sheetsB).toString()),
                Triple("Participated", participated(sheetsA).toString(), participated(sheetsB).toString()),
                Triple("Hours", formatHours(hours(sheetsA)), formatHours(hours(sheetsB))),
                Triple("Bible Studies", studies(sheetsA).toString(), studies(sheetsB).toString()),
            ).forEach { (label, a, b) ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.2f))
                    Text(a, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(b, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** What the Circuit Overseer's screens leave behind for the Field Service Report to open on (read once). */
private class ReportHandoff(val month: Long?, val mode: String?) {
    companion object {
        fun consume(): ReportHandoff {
            val store = com.emfitsolutions.gopreach.ui.components.CongregationContextStore
            val h = ReportHandoff(store.get("field_service_report_month")?.toLongOrNull(), store.get("field_service_report_mode"))
            store.set("field_service_report_month", null)
            store.set("field_service_report_mode", null)
            return h
        }
    }
}

/** Compare Periods → List View: every publisher's report for each of the two ranges, one list per range. */
@Composable
private fun CompareListView(labelA: String, itemsA: List<FieldServiceReportListItem>?, labelB: String, itemsB: List<FieldServiceReportListItem>?) {
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(Triple("a", labelA, itemsA), Triple("b", labelB, itemsB)).forEach { (tag, label, items) ->
            item(key = "$tag-head") {
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (items == null) Text("Loading…", style = MaterialTheme.typography.bodySmall) else RecordFound(items.size)
            }
            items?.let { list ->
                items(list, key = { "$tag-" + it.key }) { item ->
                    val report = item.report
                    androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(item.publisherName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text(statusText(report), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                listOfNotNull(item.groupName, item.monthLabel, item.status?.label).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (report != null) {
                                val parts = buildList {
                                    if (item.status?.isPioneer == true) add("Hours " + (report.hoursRendered?.let { formatHours(it) } ?: "—"))
                                    add("Bible Studies " + report.bibleStudiesCount)
                                }
                                Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The Records button of the Field Service Report: Add Record, and which publishers to list (All / With Report / No Record, with how many of each) - one dropdown like Export. */
@Composable
private fun RecordsMenu(canAdd: Boolean, onAdd: () -> Unit, filter: Int, onFilter: (Int) -> Unit, all: Int, withReport: Int, noReport: Int) {
    var open by remember { mutableStateOf(false) }
    Box {
        androidx.compose.material3.OutlinedButton(onClick = { open = true }) {
            Icon(Icons.Rounded.Menu, contentDescription = null, modifier = Modifier.size(18.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
            Text("Records")
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canAdd) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Add Record") },
                    leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    onClick = { open = false; onAdd() },
                )
                androidx.compose.material3.HorizontalDivider()
            }
            listOf("All" to all, "With Report" to withReport, "No Record" to noReport).forEachIndexed { index, (label, n) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("$label ($n)", fontWeight = if (filter == index) FontWeight.Bold else FontWeight.Normal) },
                    leadingIcon = { if (filter == index) Icon(Icons.Rounded.Check, contentDescription = "Selected") },
                    onClick = { open = false; onFilter(index) },
                )
            }
        }
    }
}

/** The Export button of the Field Service Report: Print, PDF, Excel, Word (the same menu as the Ministry's). */
@Composable
private fun FieldServiceExportMenu(ready: List<FieldServiceReportSheet>?, iconOnly: Boolean, graphHtml: () -> String?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        if (iconOnly) com.emfitsolutions.gopreach.ui.components.ActionIconButton(com.emfitsolutions.gopreach.ui.components.ActionKind.Export, onClick = { open = true }, contentDescription = "Export", enabled = ready != null)
        else com.emfitsolutions.gopreach.ui.components.ActionButton(com.emfitsolutions.gopreach.ui.components.ActionKind.Export, onClick = { open = true }, label = "Export", enabled = ready != null)
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // The paper of Print and PDF, remembered (the same choice as Settings > Printing and the preview's buttons): A4 or Letter, as on the website.
            var paper by remember { mutableStateOf(com.emfitsolutions.gopreach.data.print.FittedPrint.paperOf(context)) }
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paper", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf("A4" to "A4", "LETTER" to "Letter").forEach { (key, label) ->
                    androidx.compose.material3.FilterChip(selected = paper == key, onClick = { paper = key; com.emfitsolutions.gopreach.data.print.FittedPrint.setPaper(context, key) }, label = { Text(label) })
                }
            }
            androidx.compose.material3.HorizontalDivider()
            listOf("print" to "Print", "pdf" to "PDF", "excel" to "Excel", "word" to "Word").forEach { (key, label) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        ready?.let { sheets ->
                            when (key) {
                                "print" -> FieldServiceReportExporter.printPdf(context, sheets, graphHtml())
                                "pdf" -> { android.widget.Toast.makeText(context, "In the print window choose \"Save as PDF\".", android.widget.Toast.LENGTH_LONG).show(); FieldServiceReportExporter.printPdf(context, sheets, graphHtml()) }
                                "excel" -> FieldServiceReportExporter.shareExcel(context, sheets)
                                "word" -> FieldServiceReportExporter.shareWord(context, sheets)
                            }
                        }
                    },
                )
            }
        }
    }
}

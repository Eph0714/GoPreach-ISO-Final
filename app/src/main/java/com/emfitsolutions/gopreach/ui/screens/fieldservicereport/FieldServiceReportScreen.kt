package com.emfitsolutions.gopreach.ui.screens.fieldservicereport

import com.emfitsolutions.gopreach.platform.SimpleDateFormat
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
    val searchedSheets = if (query.isBlank()) sheets else sheets?.map { s -> s.copy(rows = s.rows.filter { matches(it.name, it.remarks) }) }
    val singleReady = searchedSheets?.takeIf { s -> s.isNotEmpty() && s.any { it.rows.isNotEmpty() } }
    val compareSheets = (sheetsA.orEmpty() + sheetsB.orEmpty()).filter { it.rows.isNotEmpty() }
    val ready = if (compareMode) compareSheets.takeIf { it.isNotEmpty() } else singleReady

    // List View: every report in the period with its lock state.
    var listView by rememberSaveable { mutableStateOf(false) }

    // Table View entry: Add / Edit / Delete a publisher record of the month, for the active role scope only.
    val manual: com.emfitsolutions.gopreach.ui.screens.manualreport.ManualFieldServiceViewModel = koinViewModel()
    val manualPublishers by remember(congregationId, groupScopeId) { congregationId?.let { manual.publishersIn(it, groupScopeId) } ?: emptyFlow() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val manualReports by remember(congregationId) { congregationId?.let { manual.reportsIn(it) } ?: emptyFlow() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val monthStatus by remember(congregationId, fromMonth) { viewModel.submissionFor(congregationId, fromMonth) }.collectAsStateWithLifecycle(initialValue = null)
    val monthLocked = monthStatus?.isLocked == true
    val editingAllowed = recordScope != null && !coMode && congregationId != null && fromMonth == toMonth && !compareMode && !listView
    val editingEnabled = editingAllowed && !monthLocked
    var editTarget by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<FieldServiceReportRow?>(null) }
    val itemsFlow = remember(reportGroups.map { it.id }, fromMonth, toMonth) {
        if (reportGroups.isEmpty()) emptyFlow() else viewModel.listItemsFor(reportGroups, fromMonth, toMonth)
    }
    val allListItems by itemsFlow.collectAsStateWithLifecycle(initialValue = null)
    val listItems = if (query.isBlank()) allListItems else allListItems?.filter { matches(it.publisherName) || it.groupName.contains(query, ignoreCase = true) }
    var pendingLock by remember { mutableStateOf<FieldServiceReportListItem?>(null) }
    var pendingUnlock by remember { mutableStateOf<FieldServiceReportListItem?>(null) }
    var confirmLockAll by remember { mutableStateOf(false) }
    var rejectTarget by remember { mutableStateOf<FieldServiceReportListItem?>(null) }
    var reverseTarget by remember { mutableStateOf<FieldServiceReportListItem?>(null) }

    // Back from a chosen congregation returns to the congregation picker (Super-Admin / Circuit Overseer); it only leaves the report from the picker itself.
    val backToPicker = fixedCongregationId == null && congregationId != null
    val goBack: () -> Unit = {
        if (backToPicker) { pickedCongregationId = null; if (restricted) CircuitScopeStore.selectCongregation(null); selectedGroupId = ALL_GROUPS; query = "" } else onBack()
    }
    androidx.activity.compose.BackHandler(enabled = backToPicker) { goBack() }

    Scaffold(
        topBar = {
            if (!tableOnly) TopAppBar(
                title = { Text("Field Service Report", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = goBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(enabled = congregationId != null && !compareMode && !listView, onClick = { tableOnly = true }) {
                        Icon(Icons.Rounded.OpenInFull, contentDescription = "View the table full screen")
                    }
                    IconButton(onClick = { fullScreen = !fullScreen }) {
                        Icon(if (fullScreenActive) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, contentDescription = if (fullScreenActive) "Show filters" else "Full screen")
                    }
                    IconButton(enabled = ready != null, onClick = { ready?.let { FieldServiceReportExporter.shareExcel(context, it) } }) {
                        Icon(Icons.Rounded.TableChart, contentDescription = "Export to Excel")
                    }
                    IconButton(enabled = ready != null, onClick = { ready?.let { FieldServiceReportExporter.printPdf(context, it, if (compareMode && periodA != null && periodB != null) comparativeGraphHtml(periodA.label(), periodB.label(), metricsAState.map { m -> m.monthStart }, metricsBState.map { m -> m.monthStart }, fsMetrics, fsMetrics[graphIndex.coerceIn(0, fsMetrics.lastIndex)]) else null) } }) {
                        Icon(Icons.Rounded.PictureAsPdf, contentDescription = "Print or save as PDF")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!tableOnly && restricted && congregationId != null) {
                SelectedCongregationBar(
                    name = congregations.firstOrNull { it.id == congregationId }?.name.orEmpty(),
                    onChange = { pickedCongregationId = null; CircuitScopeStore.selectCongregation(null); selectedGroupId = ALL_GROUPS },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
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
                SimpleDropdown(
                    label = "FS Group",
                    selectedLabel = if (showingAll) "All FS Groups" else groups.firstOrNull { it.id == selectedGroupId }?.name.orEmpty(),
                    options = (if (groupScopeId == null) listOf(ALL_GROUPS to "All FS Groups") else emptyList()) + groups.map { it.id to it.name },
                    onSelected = { selectedGroupId = it },
                )
                if (showingAll) {
                    SimpleDropdown(
                        label = "Group by",
                        selectedLabel = if (groupByCongregation) "Congregation" else "FS Group",
                        options = listOf("group" to "FS Group", "congregation" to "Congregation"),
                        onSelected = { groupByCongregation = it == "congregation" },
                    )
                }
                if (compareMode) {
                    // Two month RANGES to compare, e.g. Feb 2025 to Oct 2025 against Mar 2026 to Sep 2026 (the same month twice = one month).
                    DualPeriodFilter(
                        initialA = periods?.first ?: MonthRange.ofMonth(1),
                        initialB = periods?.second ?: MonthRange.ofMonth(0),
                        applyLabel = "Apply Filter",
                        onApply = { a, b -> periods = a to b },
                    )
                } else {
                // One month at a time (e.g. September 2026).
                SimpleDropdown(
                    label = "Month",
                    selectedLabel = labelOf(fromMonth),
                    options = (if (coMode) visibleMonths.map { it.periodMonth } else months).map { it.toString() to labelOf(it) },
                    onSelected = { key -> fromMonth = key.toLong(); toMonth = fromMonth },
                )
                }
            }
                if (!fullScreenActive && !tableOnly && !(restricted && congregationId == null)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                    if (compareMode) {
                        FilterChip(selected = compareView == "report", onClick = { compareView = "report" }, label = { Text("Report View") })
                        FilterChip(selected = compareView == "list", onClick = { compareView = "list" }, label = { Text("List View") })
                        FilterChip(selected = compareView == "graph", onClick = { compareView = "graph" }, label = { Text("Line Graph") })
                    } else {
                        FilterChip(selected = !listView, onClick = { listView = false }, label = { Text("Report View") })
                        FilterChip(selected = listView, onClick = { listView = true }, label = { Text("List View") })
                    }
                    FilterChip(selected = compareMode, onClick = { compareMode = !compareMode; if (compareMode) listView = false }, label = { Text("Compare Periods") })
                }
            if (!tableOnly && coMode && congregationId != null && !compareMode && !listView) {
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
                if (editingEnabled) {
                    Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.Button(onClick = { adding = true }) { Text("Add Record") }
                    }
                } else if (monthLocked) {
                    Text(
                        com.emfitsolutions.gopreach.data.model.MONTH_LOCKED_MESSAGE,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (!tableOnly && canSendToCircuit && !restricted && congregationId != null && showingAll && groupByCongregation && fromMonth == toMonth && !compareMode && !listView) {
                val congregation = congregations.firstOrNull { it.id == congregationId }
                if (congregation != null) {
                    SendToCircuitPanel(
                        congregation = congregation,
                        actorPersonId = currentPersonId,
                        viewModel = viewModel,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            if (!tableOnly && congregationId != null && !compareMode && !coModeEmpty(coMode, visibleMonths.size)) {
                androidx.compose.material3.OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search records...") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") } },
                )
            }
            val current = searchedSheets
            when {
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
            // The one control left on screen: a small round button to come back.
            androidx.compose.material3.SmallFloatingActionButton(
                onClick = { tableOnly = false },
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(12.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) { Icon(Icons.Rounded.CloseFullscreen, contentDescription = "Exit full screen table") }
        }
        }
    }

    if (editingEnabled && (adding || editTarget != null) && congregationId != null) {
        RecordEditDialog(
            month = fromMonth,
            monthLabel = labelOf(fromMonth),
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
        val record = manualReports.firstOrNull { it.publisherPersonId == row.personId && it.periodMonth == fromMonth }
        RecordDeleteDialog(
            name = row.name, monthLabel = labelOf(fromMonth),
            onConfirm = {
                if (record != null && congregationId != null) manual.delete(record, congregationId, currentRole, currentPersonId, groupScopeId)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
    rejectTarget?.let { item ->
        var reason by remember(item.key) { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
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
        androidx.compose.material3.AlertDialog(
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
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingLock = null },
            title = { Text("Lock this report?") },
            text = { Text(item.publisherName + "'s report" + (item.monthLabel?.let { " for $it" } ?: "") + " will be locked (posted). The publisher will no longer be able to edit it.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { item.report?.let { viewModel.lock(it, currentPersonId, groupScopeId) }; pendingLock = null }) { Text("Lock") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingLock = null }) { Text("Cancel") } },
        )
    }
    pendingUnlock?.let { item ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingUnlock = null },
            title = { Text("Unlock this report?") },
            text = { Text(item.publisherName + "'s report" + (item.monthLabel?.let { " for $it" } ?: "") + " will be unlocked. The publisher can edit it and submit it again.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { item.report?.let { viewModel.unlock(it, currentPersonId, groupScopeId) }; pendingUnlock = null }) { Text("Unlock") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingUnlock = null }) { Text("Cancel") } },
        )
    }
    if (confirmLockAll) {
        androidx.compose.material3.AlertDialog(
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
    Box(
        modifier = modifier.width(width).height(34.dp).background(fill).border(0.5.dp, Color(0xFF444444)).padding(horizontal = 6.dp),
        contentAlignment = if (left) Alignment.CenterStart else Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = Color.Black, maxLines = 1)
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

        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
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
            sheet.rows.forEach { line ->
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
            Row {
                Cell("", W_NO, fill = FILL_TOTAL); Cell("", W_STATUS, fill = FILL_TOTAL)
                Cell("Total:", W_NAME, fill = FILL_TOTAL, bold = true, left = true)
                rc.forEach { Cell(sheet.reportCount(it).toString(), W_NUM, fill = FILL_TOTAL, bold = true) }
                hc.forEach { Cell(formatHours(sheet.totalHours(it)), W_NUM, fill = FILL_TOTAL, bold = true) }
                rc.forEach { Cell(sheet.totalBibleStudies(it).toString(), W_NUM, fill = FILL_TOTAL, bold = true) }
                Cell("", W_REMARKS, fill = FILL_TOTAL)
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

/** One line per report: who, which month, their numbers and lock state, with Lock / Unlock for admins. */
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
    val lockable = entries.count {
        it.report?.status == com.emfitsolutions.gopreach.data.model.ReportStatus.SUBMITTED ||
            it.report?.status == com.emfitsolutions.gopreach.data.model.ReportStatus.CORRECTED
    }
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (canManageLocks && lockable > 0) {
            item(key = "lock-all") {
                androidx.compose.material3.OutlinedButton(onClick = onLockAll, modifier = Modifier.fillMaxWidth()) {
                    Text("Lock all submitted ($lockable)")
                }
            }
        }
        item { RecordFound(entries.size) }
        items(entries, key = { it.key }) { item ->
            val report = item.report
            val locked = report?.status == com.emfitsolutions.gopreach.data.model.ReportStatus.POSTED
            val canLock = report?.status == com.emfitsolutions.gopreach.data.model.ReportStatus.SUBMITTED ||
                report?.status == com.emfitsolutions.gopreach.data.model.ReportStatus.CORRECTED
            val tint = when {
                report == null -> Color(0xFF757575)
                locked -> Color(0xFF1B6E2B)
                else -> Color(0xFF1565C0)
            }
            androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.publisherName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Box(modifier = Modifier.background(tint.copy(alpha = 0.14f), androidx.compose.foundation.shape.RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                            Text(statusText(report), style = MaterialTheme.typography.labelMedium, color = tint, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Text(
                        listOfNotNull(item.groupName, item.monthLabel, item.status?.label).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (report != null) {
                        val parts = buildList {
                            if (item.status?.isPioneer == true) add("Hours " + (report.hoursRendered?.let { formatHours(it) } ?: "—"))
                            add("Bible Studies " + report.bibleStudiesCount)
                        }
                        Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium)
                        Text(report.status.publisherLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (report.status == com.emfitsolutions.gopreach.data.model.ReportStatus.ACCESS_REQUESTED) {
                            Text("Reason: " + report.accessRequestReason.orEmpty().ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (canManageLocks && actionsEnabled) {
                            val s = report.status
                            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                                if (s == com.emfitsolutions.gopreach.data.model.ReportStatus.ACCESS_REQUESTED) {
                                    androidx.compose.material3.Button(onClick = { onApprove(item) }) { Text("Approve Access") }
                                    androidx.compose.material3.OutlinedButton(onClick = { onReject(item) }) { Text("Reject") }
                                }
                                if (s == com.emfitsolutions.gopreach.data.model.ReportStatus.SUBMITTED || s == com.emfitsolutions.gopreach.data.model.ReportStatus.CORRECTED ||
                                    s == com.emfitsolutions.gopreach.data.model.ReportStatus.POSTED || s == com.emfitsolutions.gopreach.data.model.ReportStatus.ACCESS_REQUESTED ||
                                    s == com.emfitsolutions.gopreach.data.model.ReportStatus.ACCESS_GRANTED
                                ) {
                                    androidx.compose.material3.OutlinedButton(onClick = { onReverse(item) }) { Text("Reverse") }
                                }
                            }
                        }
                        if (canManageLocks && (locked || canLock)) {
                            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                                if (locked) {
                                    androidx.compose.material3.OutlinedButton(onClick = { onUnlock(item) }) { Text("Unlock to edit") }
                                } else {
                                    androidx.compose.material3.Button(onClick = { onLock(item) }) { Text("Lock") }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (summary.isNotEmpty()) item(key = "end-summary") { SummaryCard(summary) }
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

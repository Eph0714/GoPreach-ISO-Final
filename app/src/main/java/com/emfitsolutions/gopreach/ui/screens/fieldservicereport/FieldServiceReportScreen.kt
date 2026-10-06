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
import com.emfitsolutions.gopreach.ui.components.ComparativeGraphReport
import com.emfitsolutions.gopreach.ui.components.comparativeGraphHtml
import com.emfitsolutions.gopreach.ui.components.MonthRange
import kotlinx.coroutines.flow.map
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.SimpleDropdown
import kotlinx.coroutines.flow.emptyFlow
import java.util.Calendar

// Approximations of the sample's theme fills, shared with the print layout.
private val FILL_REPORTS = Color(0xFFFBE3D6)
private val FILL_HOURS = Color(0xFFC1D3EA)
private val FILL_BIBLE = Color(0xFFCBEBCD)
private val FILL_TOTAL = Color(0xFFFFFF00)

private val W_NO = 36.dp
private val W_STATUS = 64.dp
private val W_NAME = 250.dp
private val W_NUM = 56.dp
private val W_REMARKS = 200.dp

/** Dropdown key for the "All FS Groups" choice. */
private const val ALL_GROUPS = "__all__"

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
    onBack: () -> Unit,
    /** Whether this role may Lock / Unlock publisher reports in the List View. */
    canManageLocks: Boolean = false,
    viewModel: FieldServiceReportViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val congregations by viewModel.congregations.collectAsStateWithLifecycle(initialValue = emptyList())
    var pickedCongregationId by rememberSaveable { mutableStateOf<String?>(null) }
    val congregationId = fixedCongregationId ?: pickedCongregationId
    val groups by remember(congregationId) { viewModel.groupsFor(congregationId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val myGroupId by remember(currentPersonId) { viewModel.myGroupId(currentPersonId) }.collectAsStateWithLifecycle(initialValue = null)

    var selectedGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    // Pre-select the user's own group once the list is known (and never leave a stale choice).
    LaunchedEffect(groups, myGroupId) {
        if (selectedGroupId == ALL_GROUPS && groups.isNotEmpty()) return@LaunchedEffect
        if (selectedGroupId == null || groups.none { it.id == selectedGroupId }) {
            selectedGroupId = groups.firstOrNull { it.id == myGroupId }?.id
        }
    }
    val showingAll = selectedGroupId == ALL_GROUPS
    val reportGroups = if (showingAll) groups else groups.filter { it.id == selectedGroupId }
    // "Group by" (only with All FS Groups): one sheet per FS Group, or one combined sheet for the whole congregation.
    var groupByCongregation by rememberSaveable { mutableStateOf(false) }
    val wholeCongregation = if (showingAll && groupByCongregation) congregations.firstOrNull { it.id == congregationId } else null

    // The period: From..To months (both default to this month = a single-month report).
    val months = remember { monthChoices() }
    var fromMonth by remember { mutableLongStateOf(fieldServiceMonthStart(0)) }
    var toMonth by remember { mutableLongStateOf(fieldServiceMonthStart(0)) }
    val monthFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }
    fun labelOf(month: Long) = monthFormat.format(Date(month))

    val sheetsFlow = remember(reportGroups.map { it.id }, fromMonth, toMonth, wholeCongregation?.id) {
        if (reportGroups.isEmpty()) emptyFlow() else viewModel.sheetsFor(reportGroups, fromMonth, toMonth, wholeCongregation)
    }
    val sheets by sheetsFlow.collectAsStateWithLifecycle(initialValue = null)

    // Two-range comparison (first range vs second range), using the shared DualPeriodFilter.
    var compareMode by rememberSaveable { mutableStateOf(false) }
    var periods by remember { mutableStateOf<Pair<MonthRange, MonthRange>?>(null) }
    val periodA = periods?.first
    val periodB = periods?.second
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
    val singleReady = sheets?.takeIf { s -> s.isNotEmpty() && s.any { it.rows.isNotEmpty() } }
    val compareSheets = (sheetsA.orEmpty() + sheetsB.orEmpty()).filter { it.rows.isNotEmpty() }
    val ready = if (compareMode) compareSheets.takeIf { it.isNotEmpty() } else singleReady

    // List View: every report in the period with its lock state.
    var listView by rememberSaveable { mutableStateOf(false) }
    val itemsFlow = remember(reportGroups.map { it.id }, fromMonth, toMonth) {
        if (reportGroups.isEmpty()) emptyFlow() else viewModel.listItemsFor(reportGroups, fromMonth, toMonth)
    }
    val listItems by itemsFlow.collectAsStateWithLifecycle(initialValue = null)
    var pendingLock by remember { mutableStateOf<FieldServiceReportListItem?>(null) }
    var pendingUnlock by remember { mutableStateOf<FieldServiceReportListItem?>(null) }
    var confirmLockAll by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Field Service Report") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                actions = {
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fixedCongregationId == null) {
                    SimpleDropdown(
                        label = "Congregation",
                        selectedLabel = congregations.firstOrNull { it.id == congregationId }?.name.orEmpty(),
                        options = congregations.map { it.id to it.name },
                        onSelected = { pickedCongregationId = it; selectedGroupId = null },
                    )
                }
                SimpleDropdown(
                    label = "FS Group",
                    selectedLabel = if (showingAll) "All FS Groups" else groups.firstOrNull { it.id == selectedGroupId }?.name.orEmpty(),
                    options = listOf(ALL_GROUPS to "All FS Groups") + groups.map { it.id to it.name },
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
                    DualPeriodFilter(
                        initialA = periods?.first ?: MonthRange.ofMonth(1),
                        initialB = periods?.second ?: MonthRange.ofMonth(0),
                        applyLabel = "Apply Filter",
                        onApply = { a, b -> periods = a to b },
                    )
                } else {
                // Month range: From … To (the same month twice = a single-month report).
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f)) {
                        SimpleDropdown(
                            label = "From",
                            selectedLabel = labelOf(fromMonth),
                            options = months.map { it.toString() to labelOf(it) },
                            onSelected = { key ->
                                fromMonth = key.toLong()
                                if (toMonth < fromMonth) toMonth = fromMonth
                            },
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        SimpleDropdown(
                            label = "To",
                            selectedLabel = labelOf(toMonth),
                            options = months.map { it.toString() to labelOf(it) },
                            onSelected = { key ->
                                toMonth = key.toLong()
                                if (fromMonth > toMonth) fromMonth = toMonth
                            },
                        )
                    }
                }
                }
            }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                    FilterChip(selected = !listView, onClick = { listView = false }, label = { Text("Report View") })
                    FilterChip(selected = listView && !compareMode, enabled = !compareMode, onClick = { listView = true }, label = { Text("List View") })
                    FilterChip(selected = compareMode, onClick = { compareMode = !compareMode; if (compareMode) listView = false }, label = { Text("Compare Periods") })
                }
            val current = sheets
            when {
                selectedGroupId == null || reportGroups.isEmpty() -> Message(
                    if (fixedCongregationId == null && congregationId == null) "Select a congregation, then an FS Group."
                    else if (groups.isEmpty()) "This congregation has no FS Groups yet."
                    else "Select an FS Group (or All FS Groups) to see its report.",
                )
                compareMode -> {
                    val a = periodA
                    val b = periodB
                    if (a == null || b == null) Message("Choose the first and second month ranges, then tap Apply Filter.")
                    else Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        PeriodComparisonSummary(a.label(), sheetsA.orEmpty(), b.label(), sheetsB.orEmpty())
                        ComparativeGraphReport(a.label(), b.label(), metricsAState.map { m -> m.monthStart }, metricsBState.map { m -> m.monthStart }, fsMetrics, graphIndex) { graphIndex = it }
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
                        )
                    }
                }
                current == null -> Message("Loading…")
                ready == null -> Message("No publishers are assigned to ${if (showingAll) "these FS Groups" else "this FS Group"} yet.")
                else -> Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    current.filter { it.rows.isNotEmpty() }.forEach { SheetTable(it) }
                }
            }
        }
    }

    pendingLock?.let { item ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingLock = null },
            title = { Text("Lock this report?") },
            text = { Text(item.publisherName + "'s report" + (item.monthLabel?.let { " for $it" } ?: "") + " will be locked (posted). The publisher will no longer be able to edit it.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { item.report?.let { viewModel.lock(it, currentPersonId) }; pendingLock = null }) { Text("Lock") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingLock = null }) { Text("Cancel") } },
        )
    }
    pendingUnlock?.let { item ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingUnlock = null },
            title = { Text("Unlock this report?") },
            text = { Text(item.publisherName + "'s report" + (item.monthLabel?.let { " for $it" } ?: "") + " will be unlocked. The publisher can edit it and submit it again.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { item.report?.let { viewModel.unlock(it, currentPersonId) }; pendingUnlock = null }) { Text("Unlock") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingUnlock = null }) { Text("Cancel") } },
        )
    }
    if (confirmLockAll) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmLockAll = false },
            title = { Text("Lock all submitted reports?") },
            text = { Text("Every submitted report listed here will be locked (posted). Those publishers will no longer be able to edit them.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { viewModel.lockAll(listItems.orEmpty(), currentPersonId); confirmLockAll = false }) { Text("Lock all") } },
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
private fun Cell(
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
private fun SheetTable(sheet: FieldServiceReportSheet) {
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
}

/** One line per report: who, which month, their numbers and lock state, with Lock / Unlock for admins. */
@Composable
private fun ReportListView(
    entries: List<FieldServiceReportListItem>,
    canManageLocks: Boolean,
    onLock: (FieldServiceReportListItem) -> Unit,
    onUnlock: (FieldServiceReportListItem) -> Unit,
    onLockAll: () -> Unit,
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

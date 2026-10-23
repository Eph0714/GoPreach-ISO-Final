package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.FieldServiceReportExporter
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.FieldServiceReportRow
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.FieldServiceReportSheet
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.SheetTable
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.StatusColumn
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.emfitsolutions.gopreach.ui.components.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.CoReceivedReport
import com.emfitsolutions.gopreach.data.model.CoReportStatus
import com.emfitsolutions.gopreach.platform.Date
import com.emfitsolutions.gopreach.platform.Locale
import com.emfitsolutions.gopreach.platform.SimpleDateFormat
import com.emfitsolutions.gopreach.ui.components.UniversalColumn
import com.emfitsolutions.gopreach.ui.components.UniversalReport
import com.emfitsolutions.gopreach.ui.components.UniversalSort
import com.emfitsolutions.gopreach.ui.components.co.CoCard
import com.emfitsolutions.gopreach.ui.components.co.CoFullScreenButton
import com.emfitsolutions.gopreach.ui.components.co.CoFullScreenEffect
import com.emfitsolutions.gopreach.ui.components.co.CoKind
import com.emfitsolutions.gopreach.ui.components.co.CoStatusBadge
import com.emfitsolutions.gopreach.ui.components.co.coPalette
import org.koin.compose.viewmodel.koinViewModel

/** Carries "open this received report" from a dashboard notification to the screen (set just before navigating). */
object ReceivedReportsLaunch {
    var pendingReportId: String? = null
    fun take(): String? = pendingReportId.also { pendingReportId = null }
}

private fun monthName(millis: Long): String = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(millis))
private fun stamp(millis: Long): String = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(millis))

/**
 * Circuit Overseer → Field Service Report → Received Reports. Congregation first, then that congregation's received months; every month
 * opens the frozen copy the congregation sent (never its working records). A report not yet opened is highlighted and counted;
 * opening it marks it read, saved on the server so it stays read after sign-out, restart or on another phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceivedReportsScreen(
    currentPersonId: String,
    onBack: () -> Unit,
    /** A month sent before frozen copies existed has none: it opens the congregation's report as before. */
    onOpenLiveReport: (congregationId: String, month: Long) -> Unit,
    viewModel: ReceivedReportsViewModel = koinViewModel(),
    people: CircuitPeopleViewModel = koinViewModel(),
    inbox: CircuitReportsInboxViewModel = koinViewModel(),
) {
    val congregations by remember(currentPersonId) { people.congregations(currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val launchedWith = remember { ReceivedReportsLaunch.take() }
    val allUnread by remember(currentPersonId) { viewModel.unread(currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    // A tapped notification chooses its congregation, then its report.
    remember(launchedWith) {
        launchedWith?.let { id -> allUnread.firstOrNull { it.id == id }?.let { CircuitScopeStore.selectCongregation(it.congregationId) } }
        0
    }
    val selected = validCongregation(congregations, CircuitScopeStore.congregation)
    var openId by rememberSaveable { mutableStateOf(launchedWith) }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    // The opened snapshot is shown as its report table, its details, or compared with the earlier send of the same month.
    var viewMode by rememberSaveable(openId) { mutableStateOf("report") }
    CoFullScreenEffect(fullScreen)
    BackHandler(enabled = fullScreen) { fullScreen = false }
    BackHandler(enabled = !fullScreen && openId != null) { openId = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (openId != null) "Submitted CO Snapshot" else "Received Reports") },
                navigationIcon = { IconButton(onClick = { if (openId != null) openId = null else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                actions = { com.emfitsolutions.gopreach.ui.components.CircuitHomeButton(); if (selected != null) CoFullScreenButton(fullScreen) { fullScreen = !fullScreen } },
            )
        },
    ) { padding ->
        if (selected == null) {
            SelectCongregationPrompt(
                congregations = congregations, onSelect = { CircuitScopeStore.selectCongregation(it) }, modifier = Modifier.padding(padding),
                hint = "Choose a congregation to see the Field Service Reports it sent you.",
            )
            return@Scaffold
        }
        val months by remember(currentPersonId, selected.id) { viewModel.months(currentPersonId, selected.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
        val versionsOfOpen by remember(openId, selected.id) { viewModel.versions(selected.id, openMonthOf(openId)) }.collectAsStateWithLifecycle(initialValue = emptyList())
        val open = openId?.let { id -> versionsOfOpen.firstOrNull { it.id == id } }
        val read by remember(currentPersonId) { viewModel.readIds(currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptySet())

        if (open != null) {
            // Opening a report is what marks it read.
            LaunchedEffect(open.id) { runCatching { viewModel.markRead(currentPersonId, open) } }
            val openStatus by remember(open.id) { inbox.status(open.congregationId, open.periodMonth) }.collectAsStateWithLifecycle(initialValue = null)
            val openHistory by remember(open.id) { inbox.history(open.congregationId, open.periodMonth) }.collectAsStateWithLifecycle(initialValue = emptyList())
            val earlier = previousSendOf(open, versionsOfOpen)
            val diff = earlier?.let { com.emfitsolutions.gopreach.domain.SnapshotComparison.compare(it, open) }
            Column(Modifier.fillMaxSize().padding(padding)) {
                openStatus?.let { st ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            CoStatusBadge(st.status.workflowLabel, badgeKind(st.status))
                        }
                        if (open.version == st.version) SnapshotActions(st, open.congregationName, currentPersonId, inbox)
                        else Text("This is an earlier send, kept as history. Actions apply to the latest send.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = viewMode == "report", onClick = { viewMode = "report" }, label = { Text("Report") })
                    FilterChip(selected = viewMode == "details", onClick = { viewMode = "details" }, label = { Text("Snapshot Details") })
                    if (diff != null) FilterChip(selected = viewMode == "compare", onClick = { viewMode = "compare" }, label = { Text("Changes since the previous send") })
                }
                when {
                    viewMode == "details" -> SnapshotDetailsPanel(open, monthName(open.periodMonth), modifier = Modifier.weight(1f), history = openHistory)
                    viewMode == "compare" && diff != null -> SnapshotComparisonPanel(diff, monthName(open.periodMonth), modifier = Modifier.weight(1f))
                    else -> ReceivedReportTable(open, modifier = Modifier.weight(1f))
                }
            }
            return@Scaffold
        }

        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SelectedCongregationBar(
                    selected.name, onChange = { CircuitScopeStore.selectCongregation(null) }, title = "Received Reports",
                )
            }
            val unreadHere = months.sumOf { it.unread }
            item {
                Text(
                    if (months.isEmpty()) "No report has been sent yet." else "Reports Received: ${months.size}" + if (unreadHere > 0) "   ·   New: $unreadHere" else "",
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                )
            }
            items(months, key = { it.periodMonth }) { m ->
                val newOnes = m.unread > 0
                CoCard(
                    modifier = Modifier.fillMaxWidth(),
                    accent = if (newOnes) coPalette().danger else null,
                    onClick = { m.report?.let { openId = it.id } ?: onOpenLiveReport(m.congregationId, m.periodMonth) },
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(monthName(m.periodMonth), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (newOnes) CoStatusBadge(if (m.unread > 1) "${m.unread} NEW" else "NEW", CoKind.Danger)
                            m.status?.let {
                                CoStatusBadge(
                                    it.status.label,
                                    when (it.status) { CoReportStatus.RECEIVED -> CoKind.Success; CoReportStatus.RETURNED -> CoKind.Warning; else -> CoKind.Primary },
                                )
                            }
                        }
                        val sent = m.report?.submittedAt ?: m.status?.submittedAt
                        if (sent != null && sent > 0) Text("Sent ${stamp(sent)}" + (m.report?.submittedByName?.takeIf { it.isNotBlank() }?.let { " by $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val copy = m.report
                        if (copy == null) Text("Sent before copies were kept — opens the congregation's report.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Text("Publishers ${copy.publisherCount} · Participated ${copy.participatedCount} · Hours ${formatHoursText(copy.totalHours)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun openMonthOf(reportId: String?): Long = reportId?.substringBeforeLast('_')?.substringAfterLast('_')?.toLongOrNull() ?: 0L

private fun formatHoursText(hours: Double): String = if (hours % 1.0 == 0.0) hours.toLong().toString() else "%.1f".format(hours)

/** The frozen snapshot as the congregation's Field Service Report sheet (UP / Pub / AP / RP blocks, yellow Total row), exactly as it was sent. */
internal fun CoReceivedReport.toSheet(showVersion: Boolean = false): FieldServiceReportSheet {
    val lines = rows.map { r ->
        FieldServiceReportRow(
            number = r.number, status = StatusColumn.entries.firstOrNull { it.label == r.status } ?: StatusColumn.PUB, name = r.name,
            reportsCount = r.reportsCount, hours = r.hours, bibleStudies = r.bibleStudies, remarks = r.remarks, personId = r.personId,
        )
    }
    val present = lines.map { it.status }.toSet()
    val columns = StatusColumn.entries.filter { it.alwaysShown || it in present }
    return FieldServiceReportSheet(
        groupName = congregationName, monthLabel = monthName(periodMonth), isRange = false, overseer = "", servant = "", assistant = "",
        reportColumns = columns, hourColumns = columns.filter { it.isPioneer }, rows = lines,
        officerRows = listOf("Congregation:" to congregationName, "Submitted:" to ((if (showVersion) "Send $version · " else "") + stamp(submittedAt))),
    )
}

/** The frozen snapshot: the congregation's sheet by default (with Print / PDF and Excel), or the searchable list with its end-of-report summary. */
@Composable
internal fun ReceivedReportTable(report: CoReceivedReport, modifier: Modifier = Modifier, showVersion: Boolean = false) {
    var asList by rememberSaveable(report.id) { mutableStateOf(false) }
    val sheet = remember(report, showVersion) { report.toSheet(showVersion) }
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(modifier) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !asList, onClick = { asList = false }, label = { Text("Sheet") })
            FilterChip(selected = asList, onClick = { asList = true }, label = { Text("List") })
        }
        if (!asList) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedButton(onClick = { FieldServiceReportExporter.printPdf(context, listOf(sheet)) }) { Text("Print / PDF") }
                androidx.compose.material3.OutlinedButton(onClick = { FieldServiceReportExporter.shareExcel(context, listOf(sheet)) }) { Text("Excel") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) { SheetTable(sheet) }
        } else ReceivedReportList(report, Modifier.weight(1f), showVersion)
    }
}

@Composable
private fun ReceivedReportList(report: CoReceivedReport, modifier: Modifier = Modifier, showVersion: Boolean = false) {
    UniversalReport(
        title = "Field Service Report",
        details = listOf("Congregation" to report.congregationName, "Month" to monthName(report.periodMonth), "Submitted" to ((if (showVersion) "Send ${report.version} · " else "") + stamp(report.submittedAt))),
        items = report.rows,
        key = { it.number.toString() + it.name },
        columns = listOf(
            UniversalColumn("No.", 50.dp) { it.number.toString() },
            UniversalColumn("Status", 70.dp) { it.status },
            UniversalColumn("Publisher's Name", 220.dp) { it.name },
            UniversalColumn("Reports", 80.dp) { it.reportsCount.toString() },
            UniversalColumn("Hours", 80.dp) { it.hours?.let(::formatHoursText).orEmpty() },
            UniversalColumn("Bible Studies", 110.dp) { it.bibleStudies?.toString().orEmpty() },
            UniversalColumn("Remarks", 200.dp) { it.remarks },
        ),
        searchText = { listOf(it.name, it.status, it.remarks) },
        sorts = listOf(
            UniversalSort("no", "As reported", compareBy { it.number }),
            UniversalSort("az", "Name A–Z", compareBy { it.name.lowercase() }),
        ),
        summary = { shown ->
            listOf(
                "Total Publishers" to shown.size.toString(),
                "Participated" to shown.count { it.bibleStudies != null }.toString(),
                "Reports" to shown.sumOf { it.reportsCount }.toString(),
                "Total Hours" to formatHoursText(shown.sumOf { it.hours ?: 0.0 }),
                "Bible Studies" to shown.sumOf { it.bibleStudies ?: 0 }.toString(),
            )
        },
        generatedBy = "Circuit Overseer",
        card = { r ->
            CoCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row { Text("${r.number}. ${r.name}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text(r.status, color = MaterialTheme.colorScheme.primary) }
                    Text("Reports ${r.reportsCount} · Hours ${r.hours?.let(::formatHoursText) ?: "—"} · Bible Studies ${r.bibleStudies ?: "—"}", style = MaterialTheme.typography.bodySmall)
                    if (r.remarks.isNotBlank()) Text(r.remarks, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        emptyMessage = "This report has no rows.",
        modifier = modifier,
    )
}

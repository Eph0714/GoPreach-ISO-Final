package com.emfitsolutions.gopreach.ui.screens.circuit

import com.emfitsolutions.gopreach.ui.components.ActionKind
import com.emfitsolutions.gopreach.ui.components.ActionButton
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.TableChart
import com.emfitsolutions.gopreach.ui.components.AlertDialog
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.export.TableReportData
import com.emfitsolutions.gopreach.data.export.TableReportExporter
import com.emfitsolutions.gopreach.data.model.CoAttendanceRow
import com.emfitsolutions.gopreach.data.model.CoAttendanceSnapshot
import com.emfitsolutions.gopreach.data.model.CoMonthStatus
import com.emfitsolutions.gopreach.data.model.CoReportStatus
import com.emfitsolutions.gopreach.data.model.MeetingType
import com.emfitsolutions.gopreach.data.repository.messageOrNull
import com.emfitsolutions.gopreach.domain.AttendanceCalculator
import com.emfitsolutions.gopreach.ui.components.co.CoButton
import com.emfitsolutions.gopreach.ui.components.co.CoCard
import com.emfitsolutions.gopreach.ui.components.co.CoKind
import com.emfitsolutions.gopreach.ui.components.co.CoStatusBadge
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.Cell
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Carries "open this attendance snapshot" from the Comparative Report to the Circuit Attendance Report screen (set just before navigating). */
object ReceivedAttendanceLaunch {
    var pendingReportId: String? = null
    fun take(): String? = pendingReportId.also { pendingReportId = null }
}

/** The congregation id inside a snapshot id ("{congregationId}_{month}_{version}"). */
internal fun congregationOfSnapshotId(snapshotId: String): String = snapshotId.substringBeforeLast('_').substringBeforeLast('_')

private fun monthName(millis: Long): String = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(millis))
private fun stamp(millis: Long): String = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(millis))
private fun figure(v: Double?): String = v?.let { AttendanceCalculator.formatTwo(it).removeSuffix("0").removeSuffix(".0").removeSuffix(".") } ?: "—"

private fun badge(status: CoReportStatus): CoKind = when (status) {
    CoReportStatus.RECEIVED -> CoKind.Success
    CoReportStatus.RETURNED, CoReportStatus.CANCELLED -> CoKind.Warning
    CoReportStatus.SUBMITTED -> CoKind.Primary
    CoReportStatus.NOT_SUBMITTED -> CoKind.Neutral
}

/**
 * Circuit Overseer -> Received Reports -> Circuit Attendance Report. Congregation first, then the attendance months it sent; each month opens
 * the frozen snapshot the congregation sent (never its working records) with the same actions as the Field Service Report: Mark as Received
 * (which locks that month's attendance), Return to Congregation (unlocks it) or Cancel Submission.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceivedAttendanceScreen(
    currentPersonId: String,
    onBack: () -> Unit,
    onOpenCircuitSummary: () -> Unit,
    viewModel: ReceivedAttendanceViewModel = koinViewModel(),
    people: CircuitPeopleViewModel = koinViewModel(),
) {
    val congregations by remember(currentPersonId) { people.congregations(currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val launchedWith = remember { ReceivedAttendanceLaunch.take() }
    // Arriving from a Comparative Report month card: that snapshot's congregation is chosen and the snapshot opens.
    remember(launchedWith) { launchedWith?.let { CircuitScopeStore.selectCongregation(congregationOfSnapshotId(it)) }; 0 }
    val selected = validCongregation(congregations, CircuitScopeStore.congregation)
    var openId by rememberSaveable { mutableStateOf(launchedWith) }
    BackHandler(enabled = openId != null) { openId = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (openId != null) "Attendance Snapshot" else "Circuit Attendance Report") },
                navigationIcon = { IconButton(onClick = { if (openId != null) openId = null else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                actions = { com.emfitsolutions.gopreach.ui.components.CircuitHomeButton(); if (openId == null) TextButton(onClick = onOpenCircuitSummary) { Text("Circuit summary") } },
            )
        },
    ) { padding ->
        if (selected == null) {
            SelectCongregationPrompt(
                congregations = congregations, onSelect = { CircuitScopeStore.selectCongregation(it) }, modifier = Modifier.padding(padding),
                hint = "Choose a congregation to see the attendance it sent you.",
            )
            return@Scaffold
        }
        val months by remember(selected.id, currentPersonId) { viewModel.months(selected.id, currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
        val versionsOfOpen by remember(openId, selected.id) {
            viewModel.versions(selected.id, openId?.substringBeforeLast('_')?.substringAfterLast('_')?.toLongOrNull() ?: 0L)
        }.collectAsStateWithLifecycle(initialValue = emptyList())
        val open = openId?.let { id -> versionsOfOpen.firstOrNull { it.id == id } }

        if (open != null) {
            // Opening a snapshot is what marks it read.
            androidx.compose.runtime.LaunchedEffect(open.id) { runCatching { viewModel.markRead(currentPersonId, open) } }
            val status by remember(open.id) { viewModel.status(open.congregationId, open.periodMonth) }.collectAsStateWithLifecycle(initialValue = null)
            Column(Modifier.fillMaxSize().padding(padding)) {
                status?.let { st ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CoStatusBadge(st.status.workflowLabel, badge(st.status))
                        }
                        if (open.version == st.version) AttendanceSnapshotActions(st, open.congregationName, currentPersonId, viewModel)
                        else Text("This is an earlier send, kept as history. Actions apply to the latest send.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                AttendanceSnapshotSheet(open, Modifier.weight(1f))
            }
            return@Scaffold
        }

        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { SelectedCongregationBar(selected.name, onChange = { CircuitScopeStore.selectCongregation(null) }, title = "Circuit Attendance Report") }
            item {
                Text(
                    if (months.isEmpty()) "No attendance has been sent yet." else "Months Sent: ${months.size}",
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                )
            }
            items(months, key = { it.periodMonth }) { m ->
                val copy = m.snapshot
                CoCard(Modifier.fillMaxWidth(), onClick = { copy?.let { openId = it.id } }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(monthName(m.periodMonth), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (m.unread > 0) CoStatusBadge(if (m.unread > 1) "${m.unread} NEW" else "NEW", CoKind.Danger)
                            CoStatusBadge(m.status.status.label, badge(m.status.status))
                        }
                        val sent = copy?.submittedAt ?: m.status.submittedAt
                        if (sent > 0) Text("Sent ${stamp(sent)}" + (copy?.submittedByName?.takeIf { it.isNotBlank() }?.let { " by $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (copy != null) Text("Midweek ${figure(copy.average(MeetingType.MIDWEEK))} · Weekend ${figure(copy.average(MeetingType.WEEKEND))}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private enum class AttendanceAction { RECEIVE, RETURN, CANCEL }

@Composable
private fun AttendanceSnapshotActions(
    status: CoMonthStatus,
    congregationName: String,
    currentPersonId: String,
    viewModel: ReceivedAttendanceViewModel,
) {
    val scope = rememberCoroutineScope()
    val showToast = rememberActionToast()
    var pending by remember(status.id, status.version) { mutableStateOf<AttendanceAction?>(null) }
    var reason by remember(status.id, status.version) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status.status == CoReportStatus.SUBMITTED) {
            CoButton("Mark as Received", { reason = ""; pending = AttendanceAction.RECEIVE }, kind = CoKind.Success, icon = Icons.Rounded.CheckCircle, enabled = !busy, fillWidth = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (status.status.canReturn) {
                CoButton("Return to Congregation", { reason = ""; pending = AttendanceAction.RETURN }, modifier = Modifier.weight(1f), kind = CoKind.Warning, icon = Icons.AutoMirrored.Rounded.Undo, enabled = !busy)
            }
            if (status.status.canCancel) {
                CoButton("Cancel Submission", { reason = ""; pending = AttendanceAction.CANCEL }, modifier = Modifier.weight(1f), kind = CoKind.Secondary, icon = Icons.Rounded.Block, enabled = !busy)
            }
        }
    }

    pending?.let { action ->
        val needsReason = action != AttendanceAction.RECEIVE
        AlertDialog(
        scrollBody = true,
            onDismissRequest = { if (!busy) pending = null },
            title = {
                Text(
                    when (action) {
                        AttendanceAction.RECEIVE -> "Mark as Received?"
                        AttendanceAction.RETURN -> "Return to Congregation?"
                        AttendanceAction.CANCEL -> "Cancel Submission?"
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Congregation: $congregationName", fontWeight = FontWeight.SemiBold)
                    Text("Reporting month: " + monthName(status.periodMonth))
                    Text(
                        when (action) {
                            AttendanceAction.RECEIVE -> "Confirm you reviewed this report. This month's attendance is locked for the congregation's admins until you return it."
                            AttendanceAction.RETURN -> "The congregation is told why, and this month's attendance is unlocked so it can be corrected and sent again as a new report. This snapshot stays on record unchanged."
                            AttendanceAction.CANCEL -> "The submission is cancelled but its snapshot and history are kept. This does not unlock the month; to have it corrected, use Return to Congregation."
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
                ActionButton(ActionKind.Save, onClick = {
                    busy = true
                    scope.launch {
                        val result = when (action) {
                            AttendanceAction.RECEIVE -> viewModel.receive(status, reason, currentPersonId)
                            AttendanceAction.RETURN -> viewModel.returnToCongregation(status, reason, currentPersonId)
                            AttendanceAction.CANCEL -> viewModel.cancel(status, reason, currentPersonId)
                        }
                        val problem = result.messageOrNull()
                        showToast(
                            problem ?: when (action) {
                                AttendanceAction.RECEIVE -> "Attendance marked as received. The month is now locked."
                                AttendanceAction.RETURN -> "Attendance returned to the congregation."
                                AttendanceAction.CANCEL -> "Submission cancelled."
                            },
                        )
                        busy = false
                        if (problem == null) pending = null
                    }
                }, label = "Confirm", enabled = !busy && (!needsReason || reason.isNotBlank()))
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { pending = null }) { Text("Back") } },
        )
    }
}

private val midweekFill = Color(0xFFFCE4D6)
private val weekendFill = Color(0xFFC6EFCE)
private val averageFill = Color(0xFFFFFF00)

/**
 * What Excel / Print produce for a received attendance report: every meeting, then the monthly average rows exactly as the sheet ends (Midweek, Weekend), then the summary.
 * Nothing the screen shows down to its last average is left out.
 */
internal fun attendanceExportData(report: CoAttendanceSnapshot, showVersion: Boolean = false): TableReportData {
    fun official(r: CoAttendanceRow) = AttendanceCalculator.display(r.officialAttendance, r.roundingMode)
    val rows = report.rows.sortedBy { it.meetingDate }.map { r ->
        val p = r.parts.map { it.toString() }
        listOf(
            SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(r.meetingDate)), r.meetingType.label,
            p.getOrNull(0).takeIf { r.meetingType == MeetingType.MIDWEEK } ?: "—", p.getOrNull(1).takeIf { r.meetingType == MeetingType.MIDWEEK } ?: "—", p.getOrNull(2).takeIf { r.meetingType == MeetingType.MIDWEEK } ?: "—",
            p.getOrNull(0).takeIf { r.meetingType == MeetingType.WEEKEND } ?: "—", p.getOrNull(1).takeIf { r.meetingType == MeetingType.WEEKEND } ?: "—", official(r),
        )
    }
    val averages = listOf(MeetingType.MIDWEEK to report.midweek, MeetingType.WEEKEND to report.weekend)
        .filter { (_, list) -> list.isNotEmpty() }
        .map { (type, _) -> listOf("Monthly average", type.label, "", "", "", "", "", figure(report.average(type))) }
    return TableReportData(
        title = "Meeting Attendance Report",
        details = listOf("Congregation" to report.congregationName, "Month" to monthName(report.periodMonth), "Submitted" to ((if (showVersion) "Send ${report.version} · " else "") + stamp(report.submittedAt))),
        recordsFound = report.rows.size.toString(),
        table = listOf(listOf("Date", "Meeting", "Treasures", "Apply Yourself", "Living as Christians", "Public Meeting", "Watchtower Study", "Official Attendance")) + rows + averages,
        summary = listOf(
            listOf("Midweek meetings recorded", report.midweek.size.toString()), listOf("Midweek average", figure(report.average(MeetingType.MIDWEEK))),
            listOf("Weekend meetings recorded", report.weekend.size.toString()), listOf("Weekend average", figure(report.average(MeetingType.WEEKEND))),
        ),
        generatedBy = "Circuit Overseer",
        generatedAt = SimpleDateFormat("MMMM d, yyyy h:mm a", Locale.getDefault()).format(Date()),
    )
}

/** The frozen attendance as the congregation sent it: a Midweek block and a Weekend block, each with its parts, average and official figure, then the monthly averages. */
@Composable
internal fun AttendanceSnapshotSheet(report: CoAttendanceSnapshot, modifier: Modifier = Modifier, showVersion: Boolean = false) {
    val context = LocalContext.current
    val dateFmt = remember { SimpleDateFormat("EEE, MMM d", Locale.getDefault()) }
    fun official(r: CoAttendanceRow) = AttendanceCalculator.display(r.officialAttendance, r.roundingMode)
    fun exportData() = attendanceExportData(report, showVersion)

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(report.congregationName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Meeting Attendance for " + monthName(report.periodMonth) + (if (showVersion) " · Send ${report.version}" else "") + " · ${stamp(report.submittedAt)}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(ActionKind.Print, onClick = { TableReportExporter.print(context, exportData()) }, label = "  Print / PDF")
            OutlinedButton(onClick = { TableReportExporter.shareExcel(context, exportData()) }) { Icon(Icons.Rounded.TableChart, contentDescription = null); Text("  Excel") }
        }
        listOf(
            Triple(MeetingType.MIDWEEK, listOf("Treasures", "Apply Yourself", "Living as Christians"), midweekFill),
            Triple(MeetingType.WEEKEND, listOf("Public Meeting", "Watchtower Study"), weekendFill),
        ).forEach { (type, heads, fill) ->
            val rows = if (type == MeetingType.MIDWEEK) report.midweek else report.weekend
            Text(type.label + " Meeting", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (rows.isEmpty()) {
                Text("No " + type.label.lowercase() + " meeting was recorded this month.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Column(Modifier.horizontalScroll(rememberScrollState())) {
                Row {
                    Cell("Date", 110.dp, fill = fill, bold = true, left = true)
                    heads.forEach { Cell(it, 110.dp, fill = fill, bold = true) }
                    Cell("Average", 80.dp, fill = fill, bold = true)
                    Cell("Official", 80.dp, fill = fill, bold = true)
                }
                rows.forEach { r ->
                    Row {
                        Cell(dateFmt.format(Date(r.meetingDate)), 110.dp, left = true)
                        heads.indices.forEach { i -> Cell(r.parts.getOrNull(i)?.toString().orEmpty(), 110.dp) }
                        Cell(AttendanceCalculator.formatTwo(r.calculatedAverage), 80.dp)
                        Cell(official(r), 80.dp, bold = true)
                    }
                }
                Row {
                    Cell("Monthly average", 110.dp + 110.dp * heads.size, fill = averageFill, bold = true, left = true)
                    Cell("", 80.dp, fill = averageFill)
                    Cell(figure(report.average(type)), 80.dp, fill = averageFill, bold = true)
                }
            }
        }
    }
}

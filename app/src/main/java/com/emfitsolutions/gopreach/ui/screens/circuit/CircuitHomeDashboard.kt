package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SupervisorAccount
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.Stars
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.domain.ActionArea
import com.emfitsolutions.gopreach.domain.ActionItem
import com.emfitsolutions.gopreach.domain.AttendanceSummaries
import com.emfitsolutions.gopreach.domain.CircuitOverviewBuilder
import com.emfitsolutions.gopreach.domain.CongregationCard
import com.emfitsolutions.gopreach.domain.ReportStanding
import com.emfitsolutions.gopreach.domain.TrendMetric
import com.emfitsolutions.gopreach.ui.components.CongregationContextStore
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.SimpleDropdown
import org.koin.compose.viewmodel.koinViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun standingColor(s: ReportStanding): Color = when (s) {
    ReportStanding.RECEIVED -> Color(0xFF2E7D32)
    ReportStanding.SUBMITTED -> Color(0xFF1565C0)
    ReportStanding.RETURNED -> Color(0xFFEF6C00)
    ReportStanding.CANCELLED -> Color(0xFF6A1B9A)
    ReportStanding.NOT_SUBMITTED -> Color(0xFFC62828)
    ReportStanding.NO_DATA -> Color(0xFF757575)
}

private val OUTLINE get() = Color(0x1F000000)

private fun monthStartOf(offset: Int): Long = Calendar.getInstance().apply {
    add(Calendar.MONTH, offset); set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun monthLabelLong(m: Long) = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(m))
private fun monthLabelShort(m: Long) = SimpleDateFormat("MMM", Locale.getDefault()).format(Date(m))

private fun ago(ms: Long): String {
    if (ms <= 0L) return "never"
    val min = (System.currentTimeMillis() - ms) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min minute${if (min == 1L) "" else "s"} ago"
        min < 24 * 60 -> "${min / 60} hour${if (min / 60 == 1L) "" else "s"} ago"
        else -> SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(ms))
    }
}

private fun stampFull(ms: Long) = if (ms <= 0L) "—" else SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ms))

private fun timelineLabel(ms: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val now = Calendar.getInstance()
    val day = when {
        c.get(Calendar.YEAR) == now.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) -> "Today"
        c.get(Calendar.YEAR) == now.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) - 1 -> "Yesterday"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))
    }
    return day + ", " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
}

@Composable
private fun Section(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        content()
    }
}

@Composable
private fun DashCard(modifier: Modifier = Modifier, accent: Color? = null, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.animateContentSize(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, accent?.copy(alpha = 0.5f) ?: OUTLINE),
    ) { content() }
}

@Composable
private fun StatusBadge(s: ReportStanding) {
    val c = standingColor(s)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.background(c.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Box(Modifier.size(7.dp).background(c, CircleShape))
        Text(s.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = c)
    }
}

@Composable
private fun Skeleton(height: Dp, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(height).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(14.dp)))
}

/**
 * The Circuit Overseer's command center: what needs attention, how the circuit is doing, which congregation to open, and what to do next.
 * Figures of the chosen month come from the saved monthly snapshots (today's records only for the current month); anything missing is
 * shown as "—" and never as zero.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CircuitHomeDashboard(
    currentPersonId: String,
    onOpenReport: () -> Unit,
    onOpenPublishers: () -> Unit,
    onOpenCongregations: () -> Unit,
    onOpenTerritory: () -> Unit,
    onOpenCircuitReport: () -> Unit,
    onOpenAttendance: () -> Unit,
    onOpenComparative: () -> Unit,
    onOpenReportSubmission: () -> Unit,
    onOpenCongregation: (String) -> Unit,
    onOpenLeaders: () -> Unit,
    onOpenReceived: () -> Unit = {},
    /** Opens the submitted snapshot a "New Field Service Report Received" card points at (the card sets which one just before). */
    onOpenSnapshot: () -> Unit = {},
    onOpenAnnouncements: () -> Unit = {},
    modifier: Modifier = Modifier,
    received: ReceivedReportsViewModel = koinViewModel(),
    dashboard: CircuitDashboardViewModel = koinViewModel(),
    people: CircuitPeopleViewModel = koinViewModel(),
    overviewVm: CircuitOverviewViewModel = koinViewModel(),
    attendanceReceived: ReceivedAttendanceViewModel = koinViewModel(),
) {
    val header by remember(currentPersonId) { dashboard.stateFor(currentPersonId) }.collectAsStateWithLifecycle(initialValue = null)
    val unreadReports by remember(currentPersonId) { received.unread(currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val unreadAttendance by remember(currentPersonId) { attendanceReceived.unread(currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val summaries by remember(currentPersonId) { people.summaries(currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val sources by overviewVm.sources.collectAsStateWithLifecycle(initialValue = null)
    val online by people.isOnline.collectAsStateWithLifecycle(initialValue = true)
    val lastSync by people.lastSyncAt.collectAsStateWithLifecycle()

    val thisMonth = remember { monthStartOf(0) }
    var month by rememberSaveable { mutableStateOf(thisMonth) }
    val months = remember { (0 downTo -23).map { monthStartOf(it) } }
    var showAllActions by rememberSaveable { mutableStateOf(false) }

    val scoped = sources?.let { s ->
        val ids = summaries.map { it.congregation.id }.toSet()
        s.copy(
            statuses = s.statuses.filter { it.congregationId in ids }, events = s.events.filter { it.congregationId in ids },
            attendance = s.attendance.filter { it.congregationId in ids }, statistics = s.statistics.filter { it.congregationId in ids },
            comparative = s.comparative.filter { it.congregationId in ids },
        )
    }
    val overview = remember(scoped, summaries, month) {
        scoped?.let {
            CircuitOverviewBuilder.build(
                month, thisMonth, Calendar.getInstance().apply { timeInMillis = month; add(Calendar.MONTH, -1) }.timeInMillis,
                summaries, it.statuses, it.attendance, it.statistics, it.comparative,
            ) { m -> AttendanceSummaries.expectedMeetings(m) }
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // ---------------- header: who, which circuit, what is new ----------------
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        header?.circuitCode?.let { "Circuit $it" } ?: "Your Circuit",
                        fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        listOfNotNull(header?.assignedCount?.let { "$it assigned congregation${if (it == 1) "" else "s"}" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (unreadReports.isNotEmpty() || unreadAttendance.isNotEmpty()) NewReportsIndicator(unreadReports.size + unreadAttendance.size, onOpenReceived)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).background(if (online) Color(0xFF2E7D32) else Color(0xFFEF6C00), CircleShape))
                Text(
                    (if (online) "Online" else "Offline – showing last synchronized data") + "  ·  Last synchronized: " + ago(lastSync),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (overview == null) {
            // skeleton loading, never a blank screen
            Skeleton(84.dp); Skeleton(130.dp); Skeleton(150.dp)
            return@Column
        }

        // ---------------- Circuit Overview: the statistics and the shortcuts, in one module ----------------
        val t = overview.totals
        val periodNote = if (month == thisMonth) "Current records" else "Saved monthly snapshots"
        com.emfitsolutions.gopreach.ui.components.co.CoCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Circuit Overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(periodNote, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SimpleDropdown(
                    label = "Reporting Month", selectedLabel = monthLabelLong(month),
                    options = months.map { it.toString() to monthLabelLong(it) }, onSelected = { month = it.toLong() },
                )
                val partial = t.covered in 1 until t.congregations
                if (t.covered == 0 && month != thisMonth) {
                    Text("No saved monthly statistics exist for ${monthLabelLong(month)}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (partial) Text("Showing ${t.covered} of ${t.congregations} congregations that have data for this month.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val cols = if (maxWidth < 600.dp) 2 else 3
                    val stats: List<@Composable (Modifier) -> Unit> = listOf(
                        // Congregations has no earlier-month figure to compare with: the assignment list keeps no history.
                        { m -> OverviewStatCard(Icons.Rounded.AccountBalance, COLOR_CONGREGATIONS, "Congregations", t.congregations, m) },
                        { m -> OverviewStatCard(Icons.Rounded.People, COLOR_PUBLISHERS, "Publishers", t.publishers, m, change = t.publishersChange) },
                        { m -> OverviewStatCard(Icons.Rounded.SupervisorAccount, Color(0xFF3949AB), "Elders", t.elders, m, change = t.eldersChange) },
                        { m -> OverviewStatCard(Icons.Rounded.WorkspacePremium, Color(0xFF2E7D32), "Regular Pioneers", t.regularPioneers, m, change = t.regularPioneersChange) },
                        { m -> OverviewStatCard(Icons.Rounded.EventAvailable, Color(0xFF00897B), "Auxiliary Pioneers", t.auxiliaryPioneers, m, change = t.auxiliaryPioneersChange) },
                        { m -> OverviewStatCard(Icons.Rounded.Stars, Color(0xFFF9A825), "Special Pioneers", t.specialPioneers, m, change = t.specialPioneersChange) },
                        { m -> OverviewStatCard(Icons.Rounded.PersonOutline, Color(0xFFEF6C00), "Unbaptized Publishers", t.unbaptized, m, change = t.unbaptizedChange) },
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        stats.chunked(cols).forEachIndexed { rowIndex, row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Max)) {
                                row.forEachIndexed { i, card -> AppearIn(rowIndex * cols + i, Modifier.weight(1f).fillMaxHeight()) { card(Modifier.fillMaxHeight().fillMaxWidth()) } }
                                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }

                androidx.compose.material3.HorizontalDivider()
                Text("GO TO", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)

                // Every shortcut is a destination the old Quick Access offered or the task list names; the Field Service Report and the
                // Circuit Attendance Report are reached through Received Reports, so neither has a tile of its own.
                class Shortcut(val icon: androidx.compose.ui.graphics.vector.ImageVector, val color: Color, val title: String, val caption: String, val onClick: () -> Unit)
                val shortcuts = listOf(
                    Shortcut(Icons.Rounded.AccountBalance, COLOR_CONGREGATIONS, "Congregations", "View and select a congregation", onOpenCongregations),
                    Shortcut(Icons.Rounded.Inbox, COLOR_REPORT, "Received Reports", "Field Service and Circuit Attendance Reports", onOpenReceived),
                    Shortcut(Icons.Rounded.Campaign, Color(0xFFD84315), "Announcements", "Write to a congregation or its elders", onOpenAnnouncements),
                    Shortcut(Icons.Rounded.Map, COLOR_TERRITORY, "Territory Map", "Select a congregation's territory", onOpenTerritory),
                    Shortcut(Icons.Rounded.People, COLOR_PUBLISHERS, "Publishers", "Publishers by category", onOpenPublishers),
                    Shortcut(Icons.Rounded.SupervisorAccount, Color(0xFF3949AB), "Elders & Servants", "Elders and ministerial servants", onOpenLeaders),
                    Shortcut(Icons.Rounded.Groups, COLOR_ATTENDANCE, "Meeting Attendance", "Weekly attendance records", onOpenAttendance),
                    Shortcut(Icons.Rounded.CompareArrows, COLOR_COMPARE, "Comparative Report", "Compare periods", onOpenComparative),
                )
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val cols = when { maxWidth < 360.dp -> 1; maxWidth < 700.dp -> 2; else -> 3 }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        shortcuts.chunked(cols).forEachIndexed { rowIndex, row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Max)) {
                                row.forEachIndexed { i, s ->
                                    AppearIn(8 + rowIndex * cols + i, Modifier.weight(1f).fillMaxHeight()) {
                                        OverviewShortcut(s.icon, s.color, s.title, s.caption, s.onClick, Modifier.fillMaxHeight().fillMaxWidth())
                                    }
                                }
                                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }

        // ---------------- actionable notifications (hidden when there is nothing to act on) ----------------
        // A report the overseer has not opened yet is the banner; the same report is not listed again as an action.
        val actions = overview.actions.filterNot { a ->
            a.review && a.area == ActionArea.FIELD_SERVICE && unreadReports.any { r -> r.congregationId == a.congregationId && r.periodMonth == month }
        }
        if (unreadReports.isNotEmpty() || actions.isNotEmpty()) {
            Section("Needs Your Attention", "${unreadReports.take(1).size + actions.size} item${if (unreadReports.take(1).size + actions.size == 1) "" else "s"}") {
                // A congregation sent a report: tap to open that one (and it stops being new once opened).
                unreadReports.firstOrNull()?.let { newest ->
                    com.emfitsolutions.gopreach.ui.components.co.CoCard(
                        modifier = Modifier.fillMaxWidth(), accent = com.emfitsolutions.gopreach.ui.components.co.coPalette().danger,
                        onClick = { ReceivedReportsLaunch.pendingReportId = newest.id; CircuitScopeStore.selectCongregation(newest.congregationId); onOpenSnapshot() },
                    ) {
                        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.NotificationsActive, contentDescription = null, tint = com.emfitsolutions.gopreach.ui.components.co.coPalette().danger)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("New Field Service Report Received", fontWeight = FontWeight.Bold)
                                Text(newest.congregationName + " — " + monthLabelLong(newest.periodMonth), style = MaterialTheme.typography.bodyMedium)
                                Text(if (unreadReports.size > 1) "Tap to Review · ${unreadReports.size - 1} more waiting" else "Tap to Review", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                (if (showAllActions) actions else actions.take(4)).forEach { a -> ActionCard(a, month, onOpenReport, onOpenCongregation) }
                if (actions.size > 4) TextButton(onClick = { showAllActions = !showAllActions }) { Text(if (showAllActions) "Show fewer" else "Show all ${actions.size}") }
            }
        }

        Spacer(Modifier.height(72.dp)) // room for the bottom navigation
    }
}

@Composable
private fun ActionCard(a: ActionItem, month: Long, onOpenReport: () -> Unit, onOpenCongregation: (String) -> Unit) {
    DashCard(Modifier.fillMaxWidth(), accent = Color(0xFFEF6C00)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Warning, contentDescription = null, tint = Color(0xFFEF6C00), modifier = Modifier.size(18.dp))
                Text(a.congregationName, fontWeight = FontWeight.SemiBold)
            }
            Text(a.area.label, style = MaterialTheme.typography.labelLarge)
            Text(a.status, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Color(0xFFEF6C00))
            a.reason?.takeIf { it.isNotBlank() }?.let { Text("Reason: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Button(
                onClick = {
                    CircuitScopeStore.selectCongregation(a.congregationId)
                    if (a.review && a.area == ActionArea.FIELD_SERVICE) {
                        CongregationContextStore.set("field_service_report_month", month.toString())
                        onOpenReport()
                    } else onOpenCongregation(a.congregationId)
                },
                modifier = Modifier.padding(top = 4.dp),
            ) { Text(if (a.review) "Review Report" else "Open Congregation") }
        }
    }
}

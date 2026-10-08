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
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Person
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

@Composable
private fun StatCard(label: String, value: Int?, modifier: Modifier = Modifier, note: String? = null, noteColor: Color = Color.Unspecified, accent: Color = Color(0xFF3949AB), onClick: (() -> Unit)? = null) {
    val animated by animateIntAsState(value ?: 0, animationSpec = tween(600), label = "stat")
    val shape = RoundedCornerShape(14.dp)
    Card(
        modifier = modifier.clip(shape).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.09f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(4.dp).background(accent)) // the colored top edge that marks each card
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(if (value == null) "—" else "%,d".format(animated), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = accent)
                Text(
                    if (value == null) "Data unavailable" else note.orEmpty().ifBlank { " " },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else if (noteColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else noteColor,
                    maxLines = 1,
                )
                if (onClick != null) Text("View  ›", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = accent)
            }
        }
    }
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
    modifier: Modifier = Modifier,
    received: ReceivedReportsViewModel = koinViewModel(),
    dashboard: CircuitDashboardViewModel = koinViewModel(),
    people: CircuitPeopleViewModel = koinViewModel(),
    overviewVm: CircuitOverviewViewModel = koinViewModel(),
) {
    val header by remember(currentPersonId) { dashboard.stateFor(currentPersonId) }.collectAsStateWithLifecycle(initialValue = null)
    val unreadReports by remember(currentPersonId) { received.unread(currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
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
        // ---------------- header ----------------
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Circuit Overview", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                listOfNotNull(header?.circuitCode?.let { "Circuit $it" }, header?.assignedCount?.let { "$it congregation${if (it == 1) "" else "s"}" }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SimpleDropdown(
                label = "Reporting Month", selectedLabel = monthLabelLong(month),
                options = months.map { it.toString() to monthLabelLong(it) }, onSelected = { month = it.toLong() },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).background(if (online) Color(0xFF2E7D32) else Color(0xFFEF6C00), CircleShape))
                Text(
                    (if (online) "Online" else "Offline – showing last synchronized data") + "  ·  Last synchronized: " + ago(lastSync),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // A congregation sent a report: tap to open that one (and it stops being new once opened).
        unreadReports.firstOrNull()?.let { newest ->
            com.emfitsolutions.gopreach.ui.components.co.CoCard(
                modifier = Modifier.fillMaxWidth(), accent = com.emfitsolutions.gopreach.ui.components.co.coPalette().danger,
                onClick = { ReceivedReportsLaunch.pendingReportId = newest.id; CircuitScopeStore.selectCongregation(newest.congregationId); onOpenReceived() },
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

        if (overview == null) {
            // skeleton loading, never a blank screen
            Skeleton(84.dp); Skeleton(84.dp); Skeleton(130.dp); Skeleton(150.dp)
            return@Column
        }

        // ---------------- circuit summary ----------------
        val t = overview.totals
        val periodNote = if (month == thisMonth) "As of ${monthLabelLong(month)} (current records)" else monthLabelLong(month) + " (saved monthly snapshots)"
        Section("Circuit Summary", periodNote) {
            val partial = t.covered in 1 until t.congregations
            if (t.covered == 0 && month != thisMonth) {
                Text("No saved monthly statistics exist for ${monthLabelLong(month)}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (partial) Text("Showing ${t.covered} of ${t.congregations} congregations that have data for this month.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cols = when { maxWidth < 600.dp -> 2; maxWidth < 900.dp -> 3; else -> 6 }
                val items: List<@Composable (Modifier) -> Unit> = listOf(
                    { m -> StatCard("Congregations", t.congregations, m, accent = Color(0xFF3949AB), onClick = onOpenCongregations) },
                    { m ->
                        val ch = t.publishersChange
                        StatCard("Publishers", t.publishers, m, accent = Color(0xFF1E88E5), onClick = onOpenPublishers, note = ch?.let { (if (it >= 0) "+" else "") + it + " from previous month" }, noteColor = if ((ch ?: 0) < 0) Color(0xFFC62828) else Color(0xFF2E7D32))
                    },
                    { m -> StatCard("Elders", t.elders, m, accent = Color(0xFF6A1B9A), onClick = onOpenLeaders) },
                    { m -> StatCard("Regular Pioneers", t.regularPioneers, m, accent = Color(0xFF2E7D32), onClick = onOpenPublishers) },
                    { m -> StatCard("Auxiliary Pioneers", t.auxiliaryPioneers, m, accent = Color(0xFF00897B), onClick = onOpenPublishers) },
                    { m -> StatCard("Unbaptized Publishers", t.unbaptized, m, accent = Color(0xFFEF6C00), onClick = onOpenPublishers) },
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.chunked(cols).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { it(Modifier.weight(1f)) }
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        // ---------------- action required ----------------
        val actions = overview.actions
        if (actions.isNotEmpty()) {
            Section("Action Required", "${actions.size} item${if (actions.size == 1) "" else "s"}") {
                (if (showAllActions) actions else actions.take(4)).forEach { a -> ActionCard(a, month, onOpenReport, onOpenCongregation) }
                if (actions.size > 4) TextButton(onClick = { showAllActions = !showAllActions }) { Text(if (showAllActions) "Show fewer" else "Show all ${actions.size}") }
            }
        }

        // ---------------- quick actions ----------------
        Section("Quick Actions") {
            QuickAccessGrid(
                listOf(
                    QuickModule(Icons.Rounded.AccountBalance, "Congregations", "Your circuit's congregations and their people counts", null, COLOR_CONGREGATIONS, onOpenCongregations),
                    QuickModule(Icons.Rounded.People, "Publishers", "View congregation publishers", null, COLOR_PUBLISHERS, onOpenPublishers),
                    QuickModule(Icons.Rounded.Inbox, "Received Reports", "Reports your congregations sent you", null, COLOR_REPORT, onOpenReceived, badge = unreadReports.size),
                    QuickModule(Icons.Rounded.Assessment, "Field Service", "View monthly field service", null, COLOR_REPORT, onOpenReport),
                    QuickModule(Icons.Rounded.Groups, "Meeting Attendance", "View attendance records", null, COLOR_ATTENDANCE, onOpenAttendance),
                    QuickModule(Icons.Rounded.CompareArrows, "Comparative Report", "Compare historical months", null, COLOR_COMPARE, onOpenComparative),
                    QuickModule(Icons.Rounded.Person, "Elders & Servants", "View elders and ministerial servants", null, COLOR_CONGREGATIONS, onOpenLeaders),
                    QuickModule(Icons.Rounded.Map, "Territory", "View territory information", null, COLOR_TERRITORY, onOpenTerritory),
                    QuickModule(Icons.Rounded.BarChart, "Reports", "Open the Circuit Report", null, COLOR_CIRCUIT, onOpenCircuitReport),
                ),
            )
        }

        Spacer(Modifier.height(72.dp)) // room for the bottom navigation
    }
}


@Composable
private fun ActionCard(a: ActionItem, month: Long, onOpenReport: () -> Unit, onOpenCongregation: (String) -> Unit) {
    DashCard(Modifier.fillMaxWidth(), accent = Color(0xFFEF6C00)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("⚠  ${a.congregationName}", fontWeight = FontWeight.SemiBold)
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

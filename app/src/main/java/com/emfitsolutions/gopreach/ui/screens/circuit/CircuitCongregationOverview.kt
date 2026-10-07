package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.domain.AttendanceSummaries
import com.emfitsolutions.gopreach.domain.CircuitOverviewBuilder
import com.emfitsolutions.gopreach.domain.CongregationCard
import com.emfitsolutions.gopreach.domain.ReportStanding
import com.emfitsolutions.gopreach.ui.components.CongregationContextStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.koin.compose.viewmodel.koinViewModel

/**
 * Circuit Overseer → one congregation: statistic cards, the Field Service Report and Meeting Attendance for the month, and quick actions.
 * Figures come from the same builder as the dashboard (saved snapshots for a past month, "—" when unavailable).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CongregationOverviewDashboard(
    currentPersonId: String,
    congregationId: String,
    onBack: () -> Unit,
    onOpenPublishers: () -> Unit,
    onOpenLeaders: () -> Unit,
    onOpenFieldService: () -> Unit,
    onOpenAttendance: () -> Unit,
    onOpenComparative: () -> Unit,
    onOpenTerritory: () -> Unit,
    onOpenReports: () -> Unit,
    people: CircuitPeopleViewModel = koinViewModel(),
    overviewVm: CircuitOverviewViewModel = koinViewModel(),
) {
    val summaries by remember(currentPersonId) { people.summaries(currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val sources by overviewVm.sources.collectAsStateWithLifecycle(initialValue = null)
    val thisMonth = remember { Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis }
    val monthLabel = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(thisMonth)) }
    val mine = summaries.filter { it.congregation.id == congregationId }
    val card: CongregationCard? = sources?.let { s ->
        CircuitOverviewBuilder.build(
            thisMonth, thisMonth, thisMonth, mine, s.statuses, s.attendance, s.statistics, s.comparative,
        ) { m -> AttendanceSummaries.expectedMeetings(m) }.cards.firstOrNull()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(mine.firstOrNull()?.congregation?.name ?: "Congregation") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        if (mine.isEmpty()) {
            Text("This congregation is not part of your circuit.", modifier = Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        val s = mine.first()
        val f = card?.figures
        fun v(n: Int?) = n?.toString() ?: "—"
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(s.congregation.name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    listOf(s.congregation.cityMunicipality.orEmpty(), s.congregation.province.orEmpty()).filter { it.isNotBlank() }.joinToString(", ").ifBlank { "Congregation Overview" } + " · As of $monthLabel",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("Congregation Statistics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val stats = listOf(
                "Publishers" to f?.publishers, "Elders" to f?.elders, "Ministerial Servants" to f?.ministerialServants,
                "Regular Pioneers" to f?.regularPioneers, "Auxiliary Pioneers" to f?.auxiliaryPioneers, "Unbaptized Publishers" to f?.unbaptized,
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cols = if (maxWidth < 600.dp) 2 else 3
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    stats.chunked(cols).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { (label, n) ->
                                Card(
                                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, Color(0x1F000000)),
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(v(n), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            // Field Service Report card
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, Color(0x1F000000)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Field Service Report", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(monthLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val st = card?.standing ?: ReportStanding.NO_DATA
                    Text("● ${st.label}", color = standingColor(st), fontWeight = FontWeight.SemiBold)
                    val reports = f?.fieldServiceReports
                    val pubs = f?.publishers
                    Text("Publisher Reports: ${v(pubs)}", style = MaterialTheme.typography.bodyMedium)
                    Text("Submitted: ${v(reports)}", style = MaterialTheme.typography.bodyMedium)
                    Text("Missing: ${if (reports != null && pubs != null) (pubs - reports).coerceAtLeast(0).toString() else "—"}", style = MaterialTheme.typography.bodyMedium)
                    card?.returnReason?.takeIf { st == ReportStanding.RETURNED }?.let { Text("Reason: $it", style = MaterialTheme.typography.bodySmall, color = Color(0xFFEF6C00)) }
                    Button(onClick = onOpenFieldService, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("View Field Service Report →") }
                }
            }

            // Meeting Attendance card
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, Color(0x1F000000)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Meeting Attendance", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    val a = card?.attendance
                    fun avg(x: Double?) = x?.let { Math.round(it).toString() } ?: "—"
                    Text("Midweek", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text("Average: ${avg(a?.midweekAverage)}   ·   Recorded: ${a?.midweekRecorded ?: 0} / ${a?.midweekExpected ?: 0}", style = MaterialTheme.typography.bodyMedium)
                    Text("Weekend", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text("Average: ${avg(a?.weekendAverage)}   ·   Recorded: ${a?.weekendRecorded ?: 0} / ${a?.weekendExpected ?: 0}", style = MaterialTheme.typography.bodyMedium)
                    if ((a?.missing ?: 0) > 0) Text("⚠ ${a!!.missing} meetings missing", color = Color(0xFFEF6C00), fontWeight = FontWeight.SemiBold)
                    Button(onClick = onOpenAttendance, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("View Attendance →") }
                }
            }

            Text("Quick Actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            QuickAccessGrid(
                listOf(
                    QuickModule(Icons.Rounded.People, "Publishers", "View congregation publishers", null, COLOR_PUBLISHERS, onOpenPublishers),
                    QuickModule(Icons.Rounded.Assessment, "Field Service", "View monthly field service", null, COLOR_REPORT, onOpenFieldService),
                    QuickModule(Icons.Rounded.Groups, "Meeting Attendance", "View attendance records", null, COLOR_ATTENDANCE, onOpenAttendance),
                    QuickModule(Icons.Rounded.CompareArrows, "Comparative Report", "Compare historical months", null, COLOR_COMPARE, onOpenComparative),
                    QuickModule(Icons.Rounded.Person, "Elders", "View elders", null, COLOR_CONGREGATIONS, onOpenLeaders),
                    QuickModule(Icons.Rounded.Person, "Ministerial Servants", "View ministerial servants", null, COLOR_CONGREGATIONS, onOpenLeaders),
                    QuickModule(Icons.Rounded.Map, "Territory", "View territory information", null, COLOR_TERRITORY, onOpenTerritory),
                    QuickModule(Icons.Rounded.BarChart, "Reports", "Open the report center", null, COLOR_CIRCUIT, onOpenReports),
                ),
            )
        }
    }
}

package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.displayName
import com.emfitsolutions.gopreach.platform.Date
import com.emfitsolutions.gopreach.platform.Locale
import com.emfitsolutions.gopreach.platform.SimpleDateFormat
import com.emfitsolutions.gopreach.ui.components.CongregationContextStore
import com.emfitsolutions.gopreach.ui.components.RecordFound
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.fieldServiceMonthStart
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.SimpleDropdown
import org.koin.compose.viewmodel.koinViewModel

/**
 * Circuit Overseer → Circuit Dashboard: circuit code, overseer name, number of assigned congregations, and the Quick Access modules.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircuitDashboardBody(
    currentPersonId: String,
    modifier: Modifier = Modifier,
    onOpenReport: () -> Unit,
    onOpenPublishers: () -> Unit,
    onOpenCongregations: () -> Unit,
    onOpenTerritory: () -> Unit,
    onOpenCircuitReport: () -> Unit,
    onOpenAttendance: () -> Unit = {},
    onOpenComparative: () -> Unit = {},
    onOpenReportSubmission: () -> Unit = {},
    onOpenCongregation: (String) -> Unit = {},
    onOpenLeaders: () -> Unit = {},
    viewModel: CircuitDashboardViewModel = koinViewModel(),
    people: CircuitPeopleViewModel = koinViewModel(),
) {
    // The Circuit Overseer's command center (see CircuitHomeDashboard); the older tile layout below is kept only for reference.
    CircuitHomeDashboard(
        currentPersonId = currentPersonId, onOpenReport = onOpenReport, onOpenPublishers = onOpenPublishers, onOpenCongregations = onOpenCongregations,
        onOpenTerritory = onOpenTerritory, onOpenCircuitReport = onOpenCircuitReport, onOpenAttendance = onOpenAttendance, onOpenComparative = onOpenComparative,
        onOpenReportSubmission = onOpenReportSubmission, onOpenCongregation = onOpenCongregation, onOpenLeaders = onOpenLeaders, modifier = modifier,
    )
    if (false) {
    val state by remember(currentPersonId) { viewModel.stateFor(currentPersonId) }
        .collectAsStateWithLifecycle(initialValue = null)

    run {
        Column(
            modifier = modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            run {
                state?.let { s -> CircuitSummaryHeader(s) }
            }
            run {
                // Quick Access: five modules. The detailed ones (Field Service Report, Territory Map, Publishers) first ask
                // for a congregation; Circuit Report is the one summary-level view of the whole circuit.
                Text("QUICK ACCESS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
                QuickAccessGrid(
                    listOf(
                        QuickModule(Icons.Rounded.AccountBalance, "Congregations", (state?.assignedCount?.let { "$it in your circuit — with their people counts" } ?: "Your circuit's congregations and their people counts"), null, COLOR_CONGREGATIONS, onOpenCongregations),
                        QuickModule(Icons.Rounded.Assessment, "Field Service Report", "Select a congregation, then view its report", null, COLOR_REPORT, onOpenReport),
                        QuickModule(Icons.Rounded.Map, "Territory Map", "Select a congregation to view its territory", null, COLOR_TERRITORY, onOpenTerritory),
                        QuickModule(Icons.Rounded.People, "Publishers", "Select a congregation to view its publishers", null, COLOR_PUBLISHERS, onOpenPublishers),
                        QuickModule(Icons.Rounded.BarChart, "Circuit Report", "Consolidated statistics of every congregation in the circuit", null, COLOR_CIRCUIT, onOpenCircuitReport),
                        QuickModule(Icons.Rounded.Groups, "Meeting Attendance", "Weekly Midweek and Weekend attendance of a congregation", null, COLOR_ATTENDANCE, onOpenAttendance),
                        QuickModule(Icons.Rounded.CompareArrows, "Comparative Report", "Compare a congregation's statistics between two periods", null, COLOR_COMPARE, onOpenComparative),
                    ),
                )
            }
        }
    }
    }
}

/** One slim line: the circuit number as a small chip, the overseer's name and how many congregations they oversee. */
@Composable
private fun CircuitSummaryHeader(state: CircuitDashboardState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "CIRCUIT ${state.circuitCode ?: "—"}",
                    style = MaterialTheme.typography.labelMedium, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primaryContainer, androidx.compose.foundation.shape.RoundedCornerShape(7.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Text(state.overseerName, style = MaterialTheme.typography.titleSmall, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            }
            Text(
                if (state.congregationNames.isEmpty()) "${state.assignedCount} congregations"
                else state.congregationNames.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
            )
        }
    }
}

/** Compact card for the Circuit Overseer's Main Form — circuit, name, congregation count, and a way into the dashboard. */
@Composable
fun CircuitOverseerSummaryCard(
    currentPersonId: String,
    onOpenDashboard: () -> Unit,
    viewModel: CircuitDashboardViewModel = koinViewModel(),
) {
    val state by remember(currentPersonId) { viewModel.stateFor(currentPersonId) }
        .collectAsStateWithLifecycle(initialValue = null)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state?.let { CircuitSummaryHeader(it) }
        OutlinedButton(onClick = onOpenDashboard, modifier = Modifier.fillMaxWidth()) { Text("Open Circuit Dashboard") }
    }
}

/** The Circuit Dashboard as its own screen (back arrow + scrolling body); the same body is the Circuit Overseer's Main Form. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircuitDashboardScreen(
    currentPersonId: String,
    onBack: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenPublishers: () -> Unit,
    onOpenCongregations: () -> Unit,
    onOpenTerritory: () -> Unit,
    onOpenCircuitReport: () -> Unit,
    onOpenAttendance: () -> Unit = {},
    onOpenComparative: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Circuit Dashboard") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        CircuitDashboardBody(
            currentPersonId = currentPersonId,
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            onOpenReport = onOpenReport, onOpenPublishers = onOpenPublishers, onOpenCongregations = onOpenCongregations,
            onOpenTerritory = onOpenTerritory, onOpenCircuitReport = onOpenCircuitReport,
            onOpenAttendance = onOpenAttendance, onOpenComparative = onOpenComparative,
        )
    }
}

internal class QuickModule(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val description: String,
    val count: String?,
    /** The module's color code — tints its tile and icon. */
    val color: androidx.compose.ui.graphics.Color,
    val onClick: () -> Unit,
)

internal val COLOR_CONGREGATIONS = androidx.compose.ui.graphics.Color(0xFF1E88E5)
internal val COLOR_REPORT = androidx.compose.ui.graphics.Color(0xFF43A047)
internal val COLOR_TERRITORY = androidx.compose.ui.graphics.Color(0xFFFB8C00)
internal val COLOR_PUBLISHERS = androidx.compose.ui.graphics.Color(0xFF8E24AA)
internal val COLOR_CIRCUIT = androidx.compose.ui.graphics.Color(0xFF00897B)
internal val COLOR_ATTENDANCE = androidx.compose.ui.graphics.Color(0xFFD81B60)
internal val COLOR_COMPARE = androidx.compose.ui.graphics.Color(0xFF3949AB)

/**
 * Equal-sized tiles that arrange themselves by the width available: one column on a very narrow screen, two on a
 * phone, three on a wide one. A short last row keeps the same tile size (it is not stretched). Each module has its own
 * color, used for the tile tint and icon, so it can be recognised at a glance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickAccessGrid(modules: List<QuickModule>) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth < 320.dp -> 1
            maxWidth < 640.dp -> 2
            else -> 3
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            modules.chunked(columns).forEach { rowModules ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    rowModules.forEach { m ->
                        // Equal-sized, quiet tiles: white card, a small tinted icon badge, one short title and a two-line caption.
                        Card(
                            onClick = m.onClick,
                            colors = CardDefaults.cardColors(containerColor = m.color.copy(alpha = 0.07f)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, m.color.copy(alpha = 0.45f)),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).height(88.dp),
                        ) {
                            Row(modifier = Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                androidx.compose.foundation.layout.Box(
                                    contentAlignment = androidx.compose.ui.Alignment.Center,
                                    modifier = Modifier.size(38.dp).background(m.color, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                                ) { Icon(m.icon, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(21.dp)) }
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text(m.title, style = MaterialTheme.typography.labelLarge, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(m.description, style = MaterialTheme.typography.labelSmall, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, lineHeight = 13.sp)
                                }
                                m.count?.let { Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = m.color) }
                            }
                        }
                    }
                    // Keep the last row's tiles the same size as the others.
                    repeat(columns - rowModules.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

package com.emfitsolutions.gopreach.ui.screens.planner

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import com.emfitsolutions.gopreach.ui.components.DualPeriodFilter
import com.emfitsolutions.gopreach.ui.components.MonthRange
import com.emfitsolutions.gopreach.ui.components.GraphMetric
import com.emfitsolutions.gopreach.ui.components.ComparativeGraphReport
import com.emfitsolutions.gopreach.ui.components.hoursFormat
import com.emfitsolutions.gopreach.ui.components.countFormat
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.export.ComparativeReportPdfExporter
import com.emfitsolutions.gopreach.ui.components.charts.LineSeries
import com.emfitsolutions.gopreach.ui.components.charts.MultiSeriesLineChart
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** My Planner → Compare — "Add a multi-month Comparative Report... Compare
 * Hours, Minutes, Return Visits, and Bible Studies... Add an animated
 * color-coded Line Graph. Allow printing and exporting." A Start/End month
 * picker (reusing [PeriodListDialog], the same jump-to-period list every
 * other Planner view now uses), a compact bordered table, the animated
 * chart, then Export as PDF. */
@Composable
internal fun PlannerComparativeContent(currentPersonId: String, viewModel: PlannerComparativeViewModel = hiltViewModel()) {
    val startMonth by viewModel.startMonth.collectAsStateWithLifecycle()
    val endMonth by viewModel.endMonth.collectAsStateWithLifecycle()
    val state by remember(currentPersonId) { viewModel.stateFor(currentPersonId) }.collectAsStateWithLifecycle()
    val monthFormat = remember { SimpleDateFormat("MMM yyyy", Locale.getDefault()) }
    val context = LocalContext.current

    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    // The last 36 months, oldest first — the same lookback window
    // [PlannerComparativeViewModel.monthsBetween] caps a picked range to.
    val pickableMonths = remember {
        (35 downTo 0).map { monthsAgo ->
            Calendar.getInstance().apply {
                add(Calendar.MONTH, -monthsAgo)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(PlannerBodyPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Comparative Report", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Compare Hours, Return Visits, and Bible Studies across any range of months.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        var compareMode by rememberSaveable { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !compareMode, onClick = { compareMode = false }, label = { Text("One range", fontSize = 12.sp) })
            FilterChip(selected = compareMode, onClick = { compareMode = true }, label = { Text("Compare two ranges", fontSize = 12.sp) })
        }
        if (compareMode) {
            DualPeriodComparison(currentPersonId = currentPersonId, viewModel = viewModel)
            return@Column
        }

        ValueEditRow(label = "Start Month", value = monthFormat.format(Date(startMonth)), onEdit = { showStartPicker = true })
        ValueEditRow(label = "End Month", value = monthFormat.format(Date(endMonth)), onEdit = { showEndPicker = true })

        if (state.rows.isEmpty()) {
            PlannerEmptyHint("No data in this range yet.")
        } else {
            MultiSeriesLineChart(
                series = listOf(
                    LineSeries("Hours", PlannerAccent.Hours, state.rows.map { it.totalMinutes / 60f }),
                    LineSeries("Return Visits", PlannerAccent.ReturnVisits, state.rows.map { it.returnVisitCount.toFloat() }),
                    LineSeries("Bible Studies", PlannerAccent.BibleStudies, state.rows.map { it.bibleStudyCount.toFloat() }),
                ),
                xLabels = state.rows.map { SimpleDateFormat("MMM", Locale.getDefault()).format(Date(it.monthStart)) },
            )

            ComparativeTable(rows = state.rows, monthFormat = monthFormat)

            Button(
                onClick = { ComparativeReportPdfExporter.export(context, state.rows) },
                colors = ButtonDefaults.buttonColors(containerColor = PlannerAccent.Hours),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Export as PDF")
            }
        }
    }

    if (showStartPicker) {
        PeriodListDialog(
            title = "Select Start Month",
            items = pickableMonths.map { monthFormat.format(Date(it)) to null },
            onDismiss = { showStartPicker = false },
            onSelect = { index -> viewModel.setStartMonth(pickableMonths[index]) },
        )
    }
    if (showEndPicker) {
        PeriodListDialog(
            title = "Select End Month",
            items = pickableMonths.map { monthFormat.format(Date(it)) to null },
            onDismiss = { showEndPicker = false },
            onSelect = { index -> viewModel.setEndMonth(pickableMonths[index]) },
        )
    }
}

/** "Calendar table grid with visible borders" (the same rule the Monthly
 * Planner/Report specs keep repeating) — a bordered Month/Hours/Return
 * Visits/Bible Studies grid, compact rows, consistent column widths. */
@Composable
private fun ComparativeTable(rows: List<ComparativeMonthRow>, monthFormat: SimpleDateFormat) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(4.dp)) {
            TableRow(listOf("Month", "Hours", "Return Visits", "Bible Studies"), emphasize = true, borderColor = borderColor)
            HorizontalDivider(color = borderColor)
            rows.forEach { row ->
                val hours = row.totalMinutes / 60
                val minutes = row.totalMinutes % 60
                TableRow(
                    listOf(monthFormat.format(Date(row.monthStart)), "${hours}h ${minutes}m", row.returnVisitCount.toString(), row.bibleStudyCount.toString()),
                    emphasize = false,
                    borderColor = borderColor,
                )
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, emphasize: Boolean, borderColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 0.5.dp, color = borderColor)
            .padding(vertical = 8.dp, horizontal = 6.dp),
    ) {
        cells.forEach { cell ->
            Text(
                cell,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Compare two separate month ranges (Period A vs Period B, any lengths) using the shared [DualPeriodFilter]. Each
 * period keeps its own month-by-month rows for the line graph and table, and the totals are shown side by side on wide
 * screens or stacked on narrow ones. Nothing runs until Compare is tapped.
 */
@Composable
private fun DualPeriodComparison(currentPersonId: String, viewModel: PlannerComparativeViewModel) {
    val context = LocalContext.current
    var applied by remember { mutableStateOf<Pair<MonthRange, MonthRange>?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DualPeriodFilter(
            initialA = MonthRange(MonthRange.monthStart(11), MonthRange.monthStart(6)),
            initialB = MonthRange(MonthRange.monthStart(5), MonthRange.monthStart(0)),
            onApply = { a, b -> applied = a to b },
        )
        val (a, b) = applied ?: return@Column
        val rowsA by remember(currentPersonId, a) { viewModel.rowsFor(currentPersonId, a) }.collectAsStateWithLifecycle(initialValue = emptyList())
        val rowsB by remember(currentPersonId, b) { viewModel.rowsFor(currentPersonId, b) }.collectAsStateWithLifecycle(initialValue = emptyList())
        val labelA = a.label()
        val labelB = b.label()
        // The graph sits directly in the report, labelled with the actual ranges chosen above.
        val metrics = remember(rowsA, rowsB) {
            listOf(
                GraphMetric("Hours / Minutes", ::hoursFormat, rowsA.map { it.totalMinutes / 60.0 }, rowsB.map { it.totalMinutes / 60.0 }),
                GraphMetric("Return Visits", ::countFormat, rowsA.map { it.returnVisitCount.toDouble() }, rowsB.map { it.returnVisitCount.toDouble() }),
                GraphMetric("Bible Studies", ::countFormat, rowsA.map { it.bibleStudyCount.toDouble() }, rowsB.map { it.bibleStudyCount.toDouble() }),
            )
        }
        var metricIndex by remember { mutableStateOf(0) }
        Text("$labelA vs $labelB", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        ComparativeGraphReport(labelA, labelB, rowsA.map { it.monthStart }, rowsB.map { it.monthStart }, metrics, metricIndex) { metricIndex = it }
        val tableFormat = remember { SimpleDateFormat("MMM yyyy", Locale.getDefault()) }
        Text(labelA, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        if (rowsA.isEmpty()) PlannerEmptyHint("No data in this range.") else ComparativeTable(rows = rowsA, monthFormat = tableFormat)
        Text(labelB, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        if (rowsB.isEmpty()) PlannerEmptyHint("No data in this range.") else ComparativeTable(rows = rowsB, monthFormat = tableFormat)
        Button(
            onClick = { ComparativeReportPdfExporter.exportPeriods(context, labelA, rowsA, labelB, rowsB) },
            enabled = rowsA.isNotEmpty() || rowsB.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = PlannerAccent.Hours),
            modifier = Modifier.fillMaxWidth().height(40.dp),
        ) {
            Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Export / Print both periods", fontSize = 13.sp)
        }
    }
}


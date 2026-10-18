package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.export.TableReportData
import com.emfitsolutions.gopreach.data.export.TableReportExporter
import com.emfitsolutions.gopreach.domain.AcceptedComparative
import com.emfitsolutions.gopreach.domain.CircuitAttendanceComparative
import com.emfitsolutions.gopreach.domain.CircuitAttendanceComparative.Trend
import com.emfitsolutions.gopreach.ui.components.PinnedTable
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.SimpleDropdown
import org.koin.compose.viewmodel.koinViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun monthAt(offsetFromNow: Int): Long = Calendar.getInstance().apply {
    set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    add(Calendar.MONTH, offsetFromNow)
}.timeInMillis

private fun nextMonth(month: Long): Long = Calendar.getInstance().apply { timeInMillis = month; add(Calendar.MONTH, 1) }.timeInMillis

private data class PeriodChoice(val from: Long? = null, val to: Long? = null) {
    val complete: Boolean get() = from != null && to != null && from <= to
    fun toPeriod() = AcceptedComparative.Period(from!!, to!!)
}

private val GridWidths: List<Dp> = listOf(190.dp, 90.dp, 130.dp, 130.dp, 90.dp, 90.dp, 120.dp, 110.dp, 110.dp)

/**
 * Circuit Overseer: the attendance of the ENTIRE circuit (or a few chosen congregations) in two reporting periods side by side, one row per
 * congregation and meeting type with Difference, % Change and Trend, then the circuit's own rows. Uses received snapshots only.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CircuitAttendanceComparisonScreen(
    currentPersonId: String,
    onBack: () -> Unit,
    viewModel: AcceptedComparativeViewModel = koinViewModel(),
    people: CircuitPeopleViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val congregations by remember(currentPersonId) { people.congregations(currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val ids = remember(congregations) { congregations.map { it.id } }
    val monthsByCongregation by remember(ids) { viewModel.attendanceMonthsOf(ids) }.collectAsStateWithLifecycle(initialValue = emptyMap())

    val monthFmt = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }
    val monthLabel = { m: Long -> monthFmt.format(Date(m)) }
    val monthOptions = remember { (0 downTo -59).map { monthAt(it) } }
    val thisMonth = remember { monthAt(0) }

    val drafts = remember { mutableStateListOf(PeriodChoice(), PeriodChoice()) }
    var generated by remember { mutableStateOf<List<AcceptedComparative.Period>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var onlyChanged by rememberSaveable { mutableStateOf(false) }

    val problem = if (drafts.all { it.complete }) {
        AcceptedComparative.validate(drafts.map { it.toPeriod() }, thisMonth) { AcceptedComparative.monthsOf(it, ::nextMonth).size }
    } else null

    val result = generated?.let { periods ->
        CircuitAttendanceComparative.build(
            congregations.map { c -> CircuitAttendanceComparative.CongregationInput(c.id, c.name, monthsByCongregation[c.id].orEmpty()) },
            periods[0], periods[1], ::nextMonth, monthLabel,
        )
    }
    val grid = result?.let { CircuitAttendanceComparative.table(it) }
    val shown = grid?.drop(1)?.filter { row ->
        val circuitRow = row[0].startsWith("Entire circuit") || row[0].startsWith("Circuit total")
        circuitRow || ((query.isBlank() || row[0].contains(query.trim(), ignoreCase = true)) && (!onlyChanged || !row[6].contains("Unchanged")))
    }.orEmpty()

    fun exportData(): TableReportData {
        val r = result!!
        return TableReportData(
            title = "Circuit Attendance Comparison",
            details = listOf("Circuit" to "${congregations.size} congregation${if (congregations.size == 1) "" else "s"}", "Period 1" to r.firstLabel, "Period 2" to r.secondLabel),
            recordsFound = shown.size.toString(),
            table = listOf(grid!!.first()) + shown,
            summary = listOf(listOf("Metric", "Value"), listOf("Congregations with received reports", "${r.congregationsWithData} of ${congregations.size}"),
                listOf("Months in Period 1", r.firstMonths.toString()), listOf("Months in Period 2", r.secondMonths.toString())) + r.notes.map { listOf("Note", it) },
            generatedBy = "Circuit Overseer",
            generatedAt = SimpleDateFormat("MMMM d, yyyy h:mm a", Locale.getDefault()).format(Date()),
            landscape = true,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Circuit Attendance Comparison") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    "Compare the Midweek and Weekend attendance of every congregation in your circuit across two reporting periods. Only months whose " +
                        "attendance report you have marked as received are used; a missing month is never counted as zero.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(drafts.size) { index ->
                val draft = drafts[index]
                val label = if (draft.complete) AcceptedComparative.periodLabel(draft.toPeriod(), monthLabel) else if (index == 0) "Period 1 - baseline" else "Period 2 - comparison"
                Column(
                    Modifier.fillMaxWidth().border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), MaterialTheme.shapes.medium).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    SimpleDropdown(
                        label = "From month", selectedLabel = draft.from?.let(monthLabel) ?: "Select the first month",
                        options = monthOptions.map { it.toString() to monthLabel(it) },
                        onSelected = { picked -> generated = null; val f = picked.toLong(); drafts[index] = draft.copy(from = f, to = draft.to?.takeIf { it >= f }) },
                    )
                    SimpleDropdown(
                        label = "To month", selectedLabel = draft.to?.let(monthLabel) ?: "Select the last month",
                        options = monthOptions.filter { draft.from == null || it >= draft.from }.map { it.toString() to monthLabel(it) },
                        onSelected = { picked -> generated = null; drafts[index] = draft.copy(to = picked.toLong()) },
                    )
                    if (draft.complete) {
                        val span = AcceptedComparative.monthsOf(draft.toPeriod(), ::nextMonth)
                        val reported = congregations.count { c -> monthsByCongregation[c.id].orEmpty().any { it.month in span && it.state == AcceptedComparative.MonthState.ACCEPTED } }
                        Text(
                            "${span.size} month${if (span.size == 1) "" else "s"} - $reported of ${congregations.size} congregations have a received report",
                            style = MaterialTheme.typography.bodySmall, color = if (reported > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (problem != null) Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    else if (!drafts.all { it.complete }) Text("Choose the From and To months of both periods.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (congregations.isEmpty()) Text("No congregation is assigned to your circuit.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Button(
                        enabled = drafts.all { it.complete } && problem == null && congregations.isNotEmpty(),
                        onClick = { generated = drafts.map { it.toPeriod() } },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Generate Comparison", fontWeight = FontWeight.Bold) }
                }
            }
            if (result != null && grid != null) {
                item {
                    Column(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f), MaterialTheme.shapes.medium).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text("Circuit Attendance Comparison", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Period 1: ${result.firstLabel} (${result.firstMonths} month${if (result.firstMonths == 1) "" else "s"})", style = MaterialTheme.typography.bodyMedium)
                        Text("Period 2: ${result.secondLabel} (${result.secondMonths} month${if (result.secondMonths == 1) "" else "s"})", style = MaterialTheme.typography.bodyMedium)
                        Text("${result.congregationsWithData} of ${congregations.size} congregations have received reports in these periods.", style = MaterialTheme.typography.bodySmall)
                        result.circuitRows.filter { it.name.startsWith("Entire circuit") }.forEach { c ->
                            Text(
                                "${c.meetingType.label}: ${CircuitAttendanceComparative.number(c.figure.first)} -> ${CircuitAttendanceComparative.number(c.figure.second)} " +
                                    "(${CircuitAttendanceComparative.signed(c.figure.difference)}, ${CircuitAttendanceComparative.percent(c.figure.percentChange)}) ${c.figure.trend.symbol} ${c.figure.trend.label}",
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = trendColor(c.figure.trend),
                            )
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search congregation") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(selected = onlyChanged, onClick = { onlyChanged = !onlyChanged }, label = { Text("Hide unchanged") })
                            Text("Records Found: ${shown.size}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { TableReportExporter.print(context, exportData()) }) { Icon(Icons.Rounded.Print, contentDescription = null); Text("  Print / PDF") }
                            OutlinedButton(onClick = { TableReportExporter.shareExcel(context, exportData()) }) { Icon(Icons.Rounded.TableChart, contentDescription = null); Text("  Excel") }
                        }
                    }
                }
                item {
                    // The grid scrolls inside its own box with the heading pinned, and sideways when it is wider than the screen.
                    PinnedTable(
                        rowCount = shown.size,
                        maxHeight = 520.dp,
                        header = { GridRow(grid.first(), header = true) },
                    ) { i -> GridRow(shown[i], header = false, striped = i % 2 == 1) }
                }
                items(result.notes.size) { i -> Text(result.notes[i], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun trendColor(t: Trend): Color = when (t) {
    Trend.INCREASED -> Color(0xFF2E7D32)
    Trend.DECREASED -> Color(0xFFC62828)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun GridRow(cells: List<String>, header: Boolean, striped: Boolean = false) {
    val circuit = !header && (cells[0].startsWith("Entire circuit") || cells[0].startsWith("Circuit total"))
    val fill = when {
        header -> com.emfitsolutions.gopreach.ui.components.TableHeadFill
        circuit -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        striped -> androidx.compose.ui.graphics.Color.Transparent
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Row(Modifier.height(44.dp)) {
        cells.forEachIndexed { i, text ->
            Box(
                Modifier.width(GridWidths.getOrElse(i) { 100.dp }).fillMaxHeight().background(fill).border(0.5.dp, com.emfitsolutions.gopreach.ui.theme.cellBorder()).padding(horizontal = 6.dp),
                contentAlignment = if (i < 2) Alignment.CenterStart else Alignment.Center,
            ) {
                val color = if (!header && i in 4..6) {
                    when {
                        text.contains("Increased") || text.startsWith("+") -> Color(0xFF2E7D32)
                        text.contains("Decreased") || text.startsWith("-") && text.length > 1 -> Color(0xFFC62828)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                } else MaterialTheme.colorScheme.onSurface
                Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = if (header || circuit) FontWeight.Bold else FontWeight.Normal, color = color, maxLines = 2)
            }
        }
    }
}

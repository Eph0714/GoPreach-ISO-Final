package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.export.ComparativeReportData
import com.emfitsolutions.gopreach.data.export.ComparativeStatisticsExporter
import com.emfitsolutions.gopreach.domain.AcceptedComparative
import com.emfitsolutions.gopreach.domain.AcceptedComparative.Metric
import com.emfitsolutions.gopreach.domain.AcceptedComparative.MonthState
import com.emfitsolutions.gopreach.domain.AcceptedComparative.Period
import com.emfitsolutions.gopreach.domain.ConsolidatedSheet
import com.emfitsolutions.gopreach.domain.ConsolidatedSheets
import androidx.compose.foundation.layout.Spacer
import com.emfitsolutions.gopreach.ui.components.co.CoCard
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.SimpleDropdown
import org.koin.compose.viewmodel.koinViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun monthAt(offsetFromNow: Int): Long = Calendar.getInstance().apply {
    add(Calendar.MONTH, offsetFromNow); set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun nextMonth(month: Long): Long = Calendar.getInstance().apply { timeInMillis = month; add(Calendar.MONTH, 1) }.timeInMillis

/** One period the Circuit Overseer is still choosing (either end may be missing). */
private data class PeriodDraft(val from: Long? = null, val to: Long? = null) {
    val complete: Boolean get() = from != null && to != null
    fun toPeriod() = Period(from!!, to!!)
}

private val periodColors = listOf(Color(0xFF1E88E5), Color(0xFFF2B705), Color(0xFF2E7D32), Color(0xFF8E24AA), Color(0xFFE53935), Color(0xFF00897B))

private enum class ResultView(val label: String) { REPORT("Report"), SUMMARY("Comparison"), MONTHLY("Monthly Breakdown"), CHARTS("Charts") }

/**
 * Circuit Overseer -> Comparative Report: a report generator. The overseer chooses a congregation and any number of reporting
 * periods (any range, any length, across years); on Generate the whole report - period labels, month counts, snapshot coverage,
 * monthly rows, statistics, differences, charts, warnings, and what Print / PDF / Excel produce - is rebuilt from the ACCEPTED
 * monthly snapshots of exactly those periods. Changing the congregation or any period discards the previous report at once, so
 * nothing stale can be shown or exported. See [AcceptedComparative] for the calculation rules.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AcceptedComparativeScreen(
    currentPersonId: String,
    onBack: () -> Unit,
    onOpenSnapshot: (reportId: String) -> Unit,
    onOpenSubmittedReports: () -> Unit,
    viewModel: AcceptedComparativeViewModel = koinViewModel(),
    people: CircuitPeopleViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val congregations by remember(currentPersonId) { people.congregations(currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val selected = validCongregation(congregations, CircuitScopeStore.congregation)
    val monthFmt = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }
    val monthLabel = { m: Long -> monthFmt.format(Date(m)) }
    val monthOptions = remember { (0 downTo -59).map { monthAt(it) } } // this month back five years
    val thisMonth = remember { monthAt(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Comparative Report") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                actions = { TextButton(onClick = onOpenSubmittedReports) { Text("Submitted by congregations") } },
            )
        },
    ) { padding ->
        if (selected == null) {
            SelectCongregationPrompt(
                congregations = congregations, onSelect = { CircuitScopeStore.selectCongregation(it) }, modifier = Modifier.padding(padding),
                hint = "Choose a congregation to compare its accepted monthly reports.",
            )
            return@Scaffold
        }

        // Everything below belongs to ONE congregation: choosing another starts from scratch.
        val drafts = remember(selected.id) { mutableStateListOf(PeriodDraft(), PeriodDraft()) }
        var generated by remember(selected.id) { mutableStateOf<List<Period>?>(null) }
        var view by rememberSaveable(selected.id) { mutableStateOf(ResultView.REPORT.name) }
        var chartMetric by rememberSaveable(selected.id) { mutableStateOf(Metric.PUBLISHERS.name) }
        val months by remember(selected.id) { viewModel.months(selected.id) }.collectAsStateWithLifecycle(initialValue = emptyList())

        val allComplete = drafts.all { it.complete }
        val problem = if (allComplete) {
            AcceptedComparative.validate(drafts.map { it.toPeriod() }, thisMonth) { AcceptedComparative.monthsOf(it, ::nextMonth).size }
        } else null
        // The report is derived from the generated periods and the accepted snapshots; any change to the selection clears it first.
        val result = generated?.let { AcceptedComparative.build(selected.name, it, months, ::nextMonth, monthLabel) }

        // Always consolidated as the whole congregation: one sheet per chosen period, like the Field Service Report.
        val sheets = result?.periods?.map { ConsolidatedSheets.of(selected.name, it, monthLabel) }.orEmpty()

        fun selectionChanged(block: () -> Unit) { generated = null; block() }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SelectedCongregationBar(selected.name, onChange = { CircuitScopeStore.selectCongregation(null) }, title = "Comparative Report") }
            item {
                Text(
                    "Choose the reporting periods to compare. Only months whose snapshot you have accepted (marked Received) are used.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            itemsIndexed(drafts.toList()) { index, draft ->
                val letter = ('A' + index).toString()
                CoCard(Modifier.fillMaxWidth(), accent = periodColors[index % periodColors.size]) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(14.dp).clip(CircleShape).background(periodColors[index % periodColors.size]))
                            Text("  Period $letter", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (drafts.size > 2) {
                                IconButton(onClick = { selectionChanged { drafts.removeAt(index) } }) { Icon(Icons.Rounded.Close, contentDescription = "Remove Period $letter") }
                            }
                        }
                        SimpleDropdown(
                            label = "From month",
                            selectedLabel = draft.from?.let(monthLabel) ?: "Select the first month",
                            options = monthOptions.map { it.toString() to monthLabel(it) },
                            onSelected = { picked ->
                                selectionChanged {
                                    val from = picked.toLong()
                                    drafts[index] = draft.copy(from = from, to = draft.to?.takeIf { it >= from })
                                }
                            },
                        )
                        SimpleDropdown(
                            label = "To month",
                            selectedLabel = draft.to?.let(monthLabel) ?: "Select the last month",
                            options = monthOptions.filter { draft.from == null || it >= draft.from }.map { it.toString() to monthLabel(it) },
                            onSelected = { picked -> selectionChanged { drafts[index] = draft.copy(to = picked.toLong()) } },
                        )
                        if (draft.complete && draft.from!! <= draft.to!!) {
                            val span = AcceptedComparative.monthsOf(draft.toPeriod(), ::nextMonth)
                            val accepted = span.count { m -> months.any { it.month == m && it.state == MonthState.ACCEPTED } }
                            Text(
                                AcceptedComparative.periodLabel(draft.toPeriod(), monthLabel) + " - " + span.size + (if (span.size == 1) " month" else " months") +
                                    " - accepted snapshots: $accepted of ${span.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (accepted == span.size) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (drafts.size < AcceptedComparative.MAX_PERIODS) {
                        OutlinedButton(onClick = { selectionChanged { drafts.add(PeriodDraft()) } }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("  Add Comparison Period")
                        }
                    }
                    if (problem != null) Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    else if (!allComplete) Text("Choose the From and To months of every period.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        enabled = allComplete && problem == null,
                        onClick = { generated = drafts.map { it.toPeriod() } },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Generate Comparative Report", fontWeight = FontWeight.Bold) }
                }
            }

            if (result != null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Comparative Report - ${selected.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Generated " + SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date()),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ResultView.entries.forEach { v ->
                                FilterChip(selected = view == v.name, onClick = { view = v.name }, label = { Text(v.label) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { ComparativeStatisticsExporter.print(context, exportData(selected.name, result, sheets, monthLabel)) }) {
                                Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(18.dp)); Text("  Print / PDF")
                            }
                            OutlinedButton(onClick = { ComparativeStatisticsExporter.shareExcel(context, exportData(selected.name, result, sheets, monthLabel)) }) {
                                Icon(Icons.Rounded.TableChart, contentDescription = null, modifier = Modifier.size(18.dp)); Text("  Excel")
                            }
                        }
                    }
                }
                if (result.warnings.isNotEmpty()) {
                    item {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Data coverage", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                result.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
                            }
                        }
                    }
                }
                when (ResultView.valueOf(view)) {
                    ResultView.REPORT -> {
                        itemsIndexed(sheets) { index, sheet -> SheetView(sheet, periodColors[index % periodColors.size]) }
                        item { result.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    }
                    ResultView.SUMMARY -> {
                        itemsIndexed(result.periods) { index, p -> PeriodSummaryCard(p, periodColors[index % periodColors.size]) }
                        item { Text("Period Comparison", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
                        item { ReportGrid(result.comparison) }
                        item { result.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    }
                    ResultView.MONTHLY -> {
                        itemsIndexed(result.periods) { index, p -> MonthlyPeriod(p, periodColors[index % periodColors.size], monthLabel, onOpenSnapshot) }
                    }
                    ResultView.CHARTS -> {
                        item {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Metric.entries.forEach { m -> FilterChip(selected = chartMetric == m.name, onClick = { chartMetric = m.name }, label = { Text(m.label) }) }
                            }
                        }
                        item { TrendChart(result, Metric.valueOf(chartMetric), monthLabel) }
                    }
                }
            }
        }
    }
}

private fun exportData(congregationName: String, result: AcceptedComparative.Result, sheets: List<ConsolidatedSheet>, monthLabel: (Long) -> String) = ComparativeReportData(
    congregationName = congregationName,
    periodA = "", periodB = "",
    generatedBy = "Circuit Overseer",
    generatedAt = SimpleDateFormat("MMMM d, yyyy h:mm a", Locale.getDefault()).format(Date()),
    calculationMode = "",
    comparison = result.comparison,
    monthly = result.monthly,
    notes = result.notes + result.warnings,
    missingMonths = result.periods.flatMap { p -> p.missing.map { "Period ${p.letter}: ${monthLabel(it)}" } },
    summary = result.summary,
    title = "Comparative Report (accepted snapshots)",
    periodLines = result.periods.map { "Period ${it.letter}" to "${it.label} - ${it.expected} months, ${it.available} accepted snapshots" + if (it.missing.isNotEmpty()) ", ${it.missing.size} missing" else "" },
    comparisonHeading = "Period Comparison",
    monthlyHeading = "Monthly Breakdown (accepted snapshots only)",
    sheets = sheets,
)

@Composable
private fun PeriodSummaryCard(p: AcceptedComparative.PeriodResult, color: Color) {
    CoCard(Modifier.fillMaxWidth(), accent = color) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Period ${p.letter}: ${p.label}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "${p.expected} months - accepted snapshots ${p.available} of ${p.expected}" + if (p.missing.isNotEmpty()) " - ${p.missing.size} missing" else "",
                style = MaterialTheme.typography.bodySmall, color = if (p.complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            if (p.available == 0) {
                Text("No accepted snapshot in this period, so there are no figures to show.", style = MaterialTheme.typography.bodySmall)
            } else {
                fun avg(m: Metric) = p.figures.getValue(m).average?.let(AcceptedComparative::oneDecimal) ?: "-"
                Text("Monthly average - Publishers ${avg(Metric.PUBLISHERS)} - Regular Pioneers ${avg(Metric.REGULAR_PIONEERS)} - Auxiliary Pioneers ${avg(Metric.AUXILIARY_PIONEERS)}", style = MaterialTheme.typography.bodySmall)
                val studies = p.figures.getValue(Metric.BIBLE_STUDIES)
                val hours = p.figures.getValue(Metric.HOURS)
                Text("Bible Studies - total ${studies.total?.toLong()} (avg ${avg(Metric.BIBLE_STUDIES)}/month)", style = MaterialTheme.typography.bodySmall)
                Text("Reported hours - total ${hours.total?.let(AcceptedComparative::oneDecimal)} (avg ${avg(Metric.HOURS)}/month)", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MonthlyPeriod(p: AcceptedComparative.PeriodResult, color: Color, monthLabel: (Long) -> String, onOpenSnapshot: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(color))
            Text(
                "  Period ${p.letter}: ${p.label} - ${p.expected} months, ${p.available} accepted",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            )
        }
        p.rows.forEach { row ->
            CoCard(Modifier.fillMaxWidth(), accent = if (row.accepted) null else MaterialTheme.colorScheme.error) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(monthLabel(row.month), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (row.accepted) "Accepted - send ${row.input.report?.version}" else row.input.state.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (row.accepted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        if (row.accepted) {
                            fun v(m: Metric) = row.values[m]?.let { if (m == Metric.HOURS) AcceptedComparative.oneDecimal(it) else it.toLong().toString() } ?: "-"
                            Text(
                                "Publishers ${v(Metric.PUBLISHERS)} - RP ${v(Metric.REGULAR_PIONEERS)} - AP ${v(Metric.AUXILIARY_PIONEERS)} - Bible Studies ${v(Metric.BIBLE_STUDIES)} - Hours ${v(Metric.HOURS)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Text("No figures - this month is not counted as zero.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    row.input.report?.takeIf { row.accepted }?.let { r -> TextButton(onClick = { onOpenSnapshot(r.id) }) { Text("Open") } }
                }
            }
        }
    }
}

/** A table of already-formatted text (first row = headings), scrolling sideways when it is wide. */
@Composable
private fun ReportGrid(rows: List<List<String>>, firstColumn: Dp = 190.dp, column: Dp = 110.dp) {
    if (rows.isEmpty()) return
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        rows.forEachIndexed { r, cells ->
            Row {
                cells.forEachIndexed { c, text ->
                    Box(
                        Modifier.width(if (c == 0) firstColumn else column).height(34.dp)
                            .background(if (r == 0) Color(0xFFDDE6F2) else Color.Transparent)
                            .border(0.5.dp, Color(0xFF444444)).padding(horizontal = 6.dp),
                        contentAlignment = if (c == 0) Alignment.CenterStart else Alignment.Center,
                    ) { Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal, color = Color.Black, maxLines = 1) }
                }
            }
        }
    }
}

/**
 * One bar per selected month, in chronological order, coloured by period. A month without an accepted snapshot has no bar (marked
 * "n/a"), never a zero-height one. With nothing to draw it says so instead of inventing values.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrendChart(result: AcceptedComparative.Result, metric: Metric, monthLabel: (Long) -> String) {
    data class Bar(val label: String, val value: Double?, val period: Int)
    val bars = result.periods.flatMap { p -> p.rows.map { Bar(monthLabel(it.month), it.values[metric], p.index) } }
    if (bars.none { it.value != null }) {
        Text("There is no accepted data for \"${metric.label}\" in the selected periods, so there is nothing to chart.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val max = bars.mapNotNull { it.value }.max().let { if (it <= 0.0) 1.0 else it }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(metric.label + if (metric.additive) " (per month)" else " (monthly count)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            result.periods.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(periodColors[p.index % periodColors.size]))
                    Text("  Period ${p.letter}: ${p.label}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        val barWidth = 52.dp
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            androidx.compose.foundation.Canvas(Modifier.width(barWidth * bars.size).height(260.dp)) {
                val w = barWidth.toPx()
                val top = 22.dp.toPx()
                val bottom = size.height - 40.dp.toPx()
                val paint = android.graphics.Paint().apply { color = textColor; textSize = 11.dp.toPx(); textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true }
                bars.forEachIndexed { i, bar ->
                    val cx = i * w + w / 2
                    val v = bar.value
                    if (v != null) {
                        val h = ((v / max) * (bottom - top)).toFloat().coerceAtLeast(2f)
                        drawRect(periodColors[bar.period % periodColors.size], topLeft = androidx.compose.ui.geometry.Offset(cx - w * 0.3f, bottom - h), size = androidx.compose.ui.geometry.Size(w * 0.6f, h))
                        drawIntoCanvas { it.nativeCanvas.drawText(if (metric == Metric.HOURS) AcceptedComparative.oneDecimal(v) else v.toLong().toString(), cx, bottom - h - 4.dp.toPx(), paint) }
                    } else {
                        drawIntoCanvas { it.nativeCanvas.drawText("n/a", cx, bottom - 6.dp.toPx(), paint) }
                    }
                    drawIntoCanvas { it.nativeCanvas.drawText(bar.label, cx, bottom + 16.dp.toPx(), paint) }
                }
                drawLine(Color.Gray, androidx.compose.ui.geometry.Offset(0f, bottom), androidx.compose.ui.geometry.Offset(size.width, bottom), strokeWidth = 1.dp.toPx())
            }
        }
        Text("A month without an accepted snapshot shows n/a - it is not counted as zero.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val peachFill = Color(0xFFFCE4D6)
private val blueFill = Color(0xFFBDD7EE)
private val greenFill = Color(0xFFC6EFCE)
private val totalFill = Color(0xFFFFFF00)

/**
 * One period as the Field Service Report sheet, consolidated as the whole congregation: Status and Publisher's Name, then No. of
 * Reports / Hours / Bible Studies under each status, Remarks, and the yellow Total row - the same layout the congregations use.
 */
@Composable
private fun SheetView(sheet: ConsolidatedSheet, color: Color) {
    val table = remember(sheet) { ConsolidatedSheets.toTable(sheet) }
    val rc = sheet.reportColumns.size
    val hc = sheet.hourColumns.size
    val numberWidth = 34.dp
    val statusWidth = 56.dp
    val nameWidth = 190.dp
    val cellWidth = 50.dp
    val remarksWidth = 200.dp
    val line = Color(0xFF444444)
    fun widthOf(i: Int, last: Int): Dp = when {
        i == 0 -> numberWidth
        i == 1 -> statusWidth
        i == 2 -> nameWidth
        i == last -> remarksWidth
        else -> cellWidth
    }
    fun fillOf(i: Int): Color = when {
        i < 3 -> Color.Transparent
        i < 3 + rc -> peachFill
        i < 3 + rc + hc -> blueFill
        i < 3 + rc + hc + rc -> greenFill
        else -> Color.Transparent
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(color))
            Text("  " + sheet.congregationName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        Text(sheet.reportTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text("Congregation: " + sheet.congregationName + "   -   FS Groups: Consolidated, all FS Groups", style = MaterialTheme.typography.bodySmall)
        Text(
            "Accepted months: ${sheet.monthsAccepted} of ${sheet.monthsExpected}",
            style = MaterialTheme.typography.bodySmall,
            color = if (sheet.monthsAccepted == sheet.monthsExpected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        if (sheet.rows.isEmpty()) {
            Text("No accepted snapshot in this period, so there are no publishers to list.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            // band: No. of Reports | Hours | Bible Studies
            Row {
                Spacer(Modifier.width(numberWidth + statusWidth + nameWidth))
                Box(Modifier.width(cellWidth * rc).height(28.dp).background(peachFill).border(1.dp, Color.Black), contentAlignment = Alignment.Center) { Text("No. of Reports", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.Black) }
                if (hc > 0) Box(Modifier.width(cellWidth * hc).height(28.dp).background(blueFill).border(1.dp, Color.Black), contentAlignment = Alignment.Center) { Text("Hours", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.Black) }
                Box(Modifier.width(cellWidth * rc).height(28.dp).background(greenFill).border(1.dp, Color.Black), contentAlignment = Alignment.Center) { Text("Bible Studies", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.Black) }
            }
            table.forEachIndexed { r, cells ->
                val header = r == 0
                val total = r == table.size - 1
                Row {
                    cells.forEachIndexed { i, text ->
                        val last = cells.size - 1
                        Box(
                            Modifier.width(widthOf(i, last)).height(28.dp)
                                .background(if (total && i >= 2) totalFill else if (header) fillOf(i).let { if (it == Color.Transparent) Color.Transparent else it } else Color.Transparent)
                                .border(if (total) 1.5.dp else 0.5.dp, if (total) Color.Black else line)
                                .padding(horizontal = 4.dp),
                            contentAlignment = if (i == 2 || i == last) Alignment.CenterStart else if (i < 2 && !header) Alignment.CenterEnd.takeIf { i == 0 } ?: Alignment.CenterStart else Alignment.Center,
                        ) {
                            Text(
                                if (header && i == 0) "" else text,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (header || total) FontWeight.Bold else FontWeight.Normal,
                                color = Color.Black, maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

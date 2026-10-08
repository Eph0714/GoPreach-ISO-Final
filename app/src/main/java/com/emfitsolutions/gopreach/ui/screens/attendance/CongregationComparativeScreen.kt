package com.emfitsolutions.gopreach.ui.screens.attendance

import com.emfitsolutions.gopreach.ui.components.co.CoButton
import com.emfitsolutions.gopreach.ui.components.co.CoKind
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.export.ComparativeReportData
import com.emfitsolutions.gopreach.data.export.ComparativeStatisticsExporter
import com.emfitsolutions.gopreach.data.model.AttendanceRounding
import com.emfitsolutions.gopreach.data.model.CongregationMonthlyStatistics
import com.emfitsolutions.gopreach.domain.AttendanceCalculator
import com.emfitsolutions.gopreach.domain.ComparativeStat
import com.emfitsolutions.gopreach.domain.ComparativeStatistics
import com.emfitsolutions.gopreach.domain.PeriodFigures
import com.emfitsolutions.gopreach.ui.screens.circuit.CircuitPeopleViewModel
import com.emfitsolutions.gopreach.ui.screens.circuit.CircuitScopeStore
import com.emfitsolutions.gopreach.ui.screens.circuit.SelectCongregationPrompt
import com.emfitsolutions.gopreach.ui.screens.circuit.SelectedCongregationBar
import com.emfitsolutions.gopreach.ui.screens.circuit.validCongregation
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.SimpleDropdown
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun monthAt(offsetFromNow: Int): Long = Calendar.getInstance().apply {
    add(Calendar.MONTH, offsetFromNow); set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun monthsBetween(from: Long, to: Long): List<Long> {
    if (to < from) return emptyList()
    val out = mutableListOf<Long>()
    val c = Calendar.getInstance().apply { timeInMillis = from }
    while (c.timeInMillis <= to && out.size < 120) { out += c.timeInMillis; c.add(Calendar.MONTH, 1) }
    return out
}

@Composable
private fun Cell(text: String, width: Dp, bold: Boolean = false, fill: Color = Color.Transparent, left: Boolean = false, sub: String? = null) {
    Box(
        modifier = Modifier.width(width).height(if (sub != null) 44.dp else 34.dp).background(fill).border(0.5.dp, Color(0xFF444444)).padding(horizontal = 6.dp),
        contentAlignment = if (left) Alignment.CenterStart else Alignment.Center,
    ) {
        Column(horizontalAlignment = if (left) Alignment.Start else Alignment.CenterHorizontally) {
            Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = Color.Black, maxLines = 1)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = Color(0xFF555555), maxLines = 1)
        }
    }
}

/**
 * The Congregation Comparative Report: how a congregation's reporting, headcounts and meeting attendance changed between two periods,
 * built ONLY from its saved monthly historical snapshots (never from today's publishers). Month with no snapshot shows "—" and is
 * counted as missing, never as zero. Monthly view (sticky first column, horizontal scroll) and Period Comparison, with Print/PDF and Excel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CongregationComparativeScreen(
    fixedCongregationId: String?,
    circuitScope: String?,
    currentPersonId: String,
    onBack: () -> Unit,
    viewModel: MeetingAttendanceViewModel = koinViewModel(),
    people: CircuitPeopleViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by viewModel.congregations.collectAsStateWithLifecycle(initialValue = emptyList())
    val circuitCongregations by remember(circuitScope) { people.congregations(circuitScope ?: "") }.collectAsStateWithLifecycle(initialValue = emptyList())
    var pickedId by rememberSaveable { mutableStateOf<String?>(null) }
    val congregation = when {
        circuitScope != null -> validCongregation(circuitCongregations, CircuitScopeStore.congregation)
        fixedCongregationId != null -> all.firstOrNull { it.id == fixedCongregationId }
        else -> all.firstOrNull { it.id == pickedId }
    }
    val stats by remember(congregation?.id) { viewModel.statisticsFor(congregation?.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val rounding by remember(congregation?.id) { viewModel.rounding(congregation?.id) }.collectAsStateWithLifecycle(initialValue = AttendanceRounding.ROUNDED)

    val monthFmt = remember { SimpleDateFormat("MMM yyyy", Locale.getDefault()) }
    val options = remember { (0 downTo -47).map { monthAt(it) } } // this month back four years
    var aFrom by rememberSaveable { mutableStateOf(monthAt(-11)) }
    var aTo by rememberSaveable { mutableStateOf(monthAt(-6)) }
    var bFrom by rememberSaveable { mutableStateOf(monthAt(-5)) }
    var bTo by rememberSaveable { mutableStateOf(monthAt(0)) }
    var view by rememberSaveable { mutableStateOf("comparison") }
    val label = { f: Long, t: Long -> if (f == t) monthFmt.format(Date(f)) else monthFmt.format(Date(f)) + " – " + monthFmt.format(Date(t)) }

    val monthsA = monthsBetween(aFrom, aTo)
    val monthsB = monthsBetween(bFrom, bTo)
    val periodA = ComparativeStatistics.period(stats.filter { it.serviceMonth in monthsA }, monthsA.size)
    val periodB = ComparativeStatistics.period(stats.filter { it.serviceMonth in monthsB }, monthsB.size)
    val rangeMonths = monthsBetween(minOf(aFrom, bFrom), maxOf(aTo, bTo))

    fun fmt(stat: ComparativeStat, v: Double?, mode: AttendanceRounding = rounding): String = when {
        v == null -> "—"
        stat.isAttendance -> AttendanceCalculator.display(if (mode == AttendanceRounding.ROUNDED) AttendanceCalculator.roundHalfUp(v) else v, mode)
        else -> Math.round(v).toString()
    }
    fun signed(d: Double?, stat: ComparativeStat): String = d?.let { (if (it > 0.00001) "+" else if (it < -0.00001) "-" else "") + fmt(stat, kotlin.math.abs(it)) } ?: "—"
    fun pct(p: Double?): String = p?.let { (if (it > 0.00001) "+" else if (it < -0.00001) "-" else "") + String.format(Locale.US, "%.1f", kotlin.math.abs(it)) + "%" } ?: "—"
    fun subline(stat: ComparativeStat, p: PeriodFigures): String? = when {
        stat.isTotal -> p.monthlyAverage[stat]?.let { "avg " + String.format(Locale.US, "%.1f", it) + "/mo" }
        stat.isAttendance -> {
            val rec = if (stat == ComparativeStat.MIDWEEK_ATTENDANCE) p.midweekRecorded else p.weekendRecorded
            val miss = if (stat == ComparativeStat.MIDWEEK_ATTENDANCE) p.midweekMissing else p.weekendMissing
            "$rec recorded" + if (miss > 0) " / $miss missing" else ""
        }
        else -> p.beginning[stat]?.let { "start ${Math.round(it)} · avg " + String.format(Locale.US, "%.1f", p.monthlyAverage[stat] ?: it) }
    }

    val comparison = listOf(listOf("Statistic", "Period A", "Period B", "Difference", "% Change")) + ComparativeStat.entries.map { s ->
        val a = periodA.value[s]; val b = periodB.value[s]
        listOf(s.label + if (s.isTotal) " (total)" else "", fmt(s, a), fmt(s, b), signed(ComparativeStatistics.difference(a, b), s), pct(ComparativeStatistics.percentChange(a, b)))
    }
    val monthly = listOf(listOf("Statistic") + rangeMonths.map { monthFmt.format(Date(it)) }) + ComparativeStat.entries.map { s ->
        listOf(s.label) + rangeMonths.map { m -> stats.firstOrNull { it.serviceMonth == m }?.let { snap -> fmt(s, s.valueIn(snap), snap.attendanceRoundingMode) } ?: "—" }
    }
    val notes = listOf(
        "Period A (${label(aFrom, aTo)}): ${periodA.monthsWithData} of ${monthsA.size} months have data" + (if (periodA.monthsMissing > 0) " · ${periodA.monthsMissing} Missing" else "") +
            " · Midweek ${periodA.midweekRecorded} recorded / ${periodA.midweekMissing} missing · Weekend ${periodA.weekendRecorded} recorded / ${periodA.weekendMissing} missing",
        "Period B (${label(bFrom, bTo)}): ${periodB.monthsWithData} of ${monthsB.size} months have data" + (if (periodB.monthsMissing > 0) " · ${periodB.monthsMissing} Missing" else "") +
            " · Midweek ${periodB.midweekRecorded} recorded / ${periodB.midweekMissing} missing · Weekend ${periodB.weekendRecorded} recorded / ${periodB.weekendMissing} missing",
        "Reports are totals; headcounts are the count at the end of each period; attendance is the average of the weekly figures (never a sum). A month without a saved snapshot shows — and is not counted.",
    )

    val summary = listOf(listOf("Metric", "Value"), listOf("Period A", label(aFrom, aTo)), listOf("Period B", label(bFrom, bTo))) +
        comparison.drop(1).map { r -> listOf(r[0].removeSuffix(" (total)") + " Difference", r[3] + if (r[4] != "—") " (" + r[4] + ")" else "") } +
        listOf(listOf("Months Missing Data", (monthsA + monthsB).count { m -> stats.none { it.serviceMonth == m } }.toString()))

    var fullScreen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    com.emfitsolutions.gopreach.ui.components.co.CoFullScreenEffect(fullScreen)
    androidx.activity.compose.BackHandler(enabled = fullScreen) { fullScreen = false }
    Scaffold(
        topBar = {
            TopAppBar(
                actions = { com.emfitsolutions.gopreach.ui.components.co.CoFullScreenButton(fullScreen) { fullScreen = !fullScreen } },
                title = { Text("Congregation Comparative Report") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                circuitScope != null && congregation == null ->
                    SelectCongregationPrompt(circuitCongregations, onSelect = { CircuitScopeStore.selectCongregation(it) }, hint = "Choose a congregation under your assigned Circuit to compare its statistics.")
                circuitScope != null -> SelectedCongregationBar(congregation!!.name, onChange = { CircuitScopeStore.selectCongregation(null) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                fixedCongregationId == null -> SimpleDropdown("Congregation", congregation?.name ?: "Select a congregation", all.map { it.id to it.name }, { pickedId = it })
                else -> Text("Congregation: ${congregation?.name.orEmpty()}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (congregation == null) return@Column

            Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Period A", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { SimpleDropdown("From", monthFmt.format(Date(aFrom)), options.map { it.toString() to monthFmt.format(Date(it)) }, { aFrom = it.toLong(); if (aTo < aFrom) aTo = aFrom }) }
                    Box(Modifier.weight(1f)) { SimpleDropdown("To", monthFmt.format(Date(aTo)), options.map { it.toString() to monthFmt.format(Date(it)) }, { aTo = it.toLong(); if (aFrom > aTo) aFrom = aTo }) }
                }
                Text("Period B", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { SimpleDropdown("From", monthFmt.format(Date(bFrom)), options.map { it.toString() to monthFmt.format(Date(it)) }, { bFrom = it.toLong(); if (bTo < bFrom) bTo = bFrom }) }
                    Box(Modifier.weight(1f)) { SimpleDropdown("To", monthFmt.format(Date(bTo)), options.map { it.toString() to monthFmt.format(Date(it)) }, { bTo = it.toLong(); if (bFrom > bTo) bFrom = bTo }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = view == "comparison", onClick = { view = "comparison" }, label = { Text("Period Comparison") })
                    FilterChip(selected = view == "monthly", onClick = { view = "monthly" }, label = { Text("Monthly View") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    fun data(by: String) = ComparativeReportData(
                        congregationName = congregation.name, periodA = label(aFrom, aTo), periodB = label(bFrom, bTo), generatedBy = by,
                        generatedAt = SimpleDateFormat("MMMM d, yyyy h:mm a", Locale.getDefault()).format(Date()),
                        calculationMode = rounding.label, comparison = comparison, monthly = monthly, notes = notes, summary = summary,
                        missingMonths = (monthsA + monthsB).filter { m -> stats.none { it.serviceMonth == m } }.map { monthFmt.format(Date(it)) },
                    )
                    CoButton("Print / PDF", { scope.launch { ComparativeStatisticsExporter.print(context, data(viewModel.generatedBy(currentPersonId))) } }, kind = CoKind.Secondary, icon = Icons.Rounded.Print)
                    CoButton("Excel", { scope.launch { ComparativeStatisticsExporter.shareExcel(context, data(viewModel.generatedBy(currentPersonId))) } }, kind = CoKind.Secondary, icon = Icons.Rounded.TableChart)
                }
            }

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (stats.isEmpty()) {
                    Text(
                        "No historical statistics have been saved for this congregation yet. They are saved when its monthly Field Service Report is submitted to the Circuit Overseer.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (view == "comparison") {
                    val hs = rememberScrollState()
                    Column(modifier = Modifier.horizontalScroll(hs)) {
                        Row {
                            Cell("Statistic", 170.dp, true, Color(0xFFDDE6F2), left = true); Cell("Period A", 118.dp, true, Color(0xFFDDE6F2)); Cell("Period B", 118.dp, true, Color(0xFFDDE6F2))
                            Cell("Difference", 96.dp, true, Color(0xFFDDE6F2)); Cell("% Change", 90.dp, true, Color(0xFFDDE6F2))
                        }
                        ComparativeStat.entries.forEachIndexed { i, s ->
                            val a = periodA.value[s]; val b = periodB.value[s]
                            val d = ComparativeStatistics.difference(a, b)
                            val tint = when { d == null || kotlin.math.abs(d) < 0.00001 -> Color.Transparent; d > 0 -> Color(0xFFE3F4E3); else -> Color(0xFFFBE3E3) }
                            val rowFill = if (s.isAttendance) Color(0xFFFFF8D6) else Color.Transparent
                            Row {
                                Cell(s.label + if (s.isTotal) " (total)" else "", 170.dp, s.isAttendance, rowFill, left = true, sub = if (s.isTotal) "report volume" else if (s.isAttendance) "average of weekly figures" else "at end of period")
                                Cell(fmt(s, a), 118.dp, true, rowFill, sub = subline(s, periodA)); Cell(fmt(s, b), 118.dp, true, rowFill, sub = subline(s, periodB))
                                Cell(signed(d, s), 96.dp, true, tint); Cell(pct(ComparativeStatistics.percentChange(a, b)), 90.dp, false, tint)
                            }
                        }
                    }
                    notes.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    // Monthly view: the first column stays put while the months scroll sideways.
                    Row {
                        Column {
                            Cell("Statistic", 150.dp, true, Color(0xFFDDE6F2), left = true)
                            ComparativeStat.entries.forEach { s -> Cell(s.label, 150.dp, s.isAttendance, if (s.isAttendance) Color(0xFFFFF8D6) else Color.Transparent, left = true) }
                        }
                        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            Row { rangeMonths.forEach { m -> Cell(monthFmt.format(Date(m)), 78.dp, true, Color(0xFFDDE6F2)) } }
                            ComparativeStat.entries.forEach { s ->
                                Row {
                                    rangeMonths.forEach { m ->
                                        val snap = stats.firstOrNull { it.serviceMonth == m }
                                        Cell(snap?.let { fmt(s, s.valueIn(it), it.attendanceRoundingMode) } ?: "—", 78.dp, s.isAttendance, if (s.isAttendance) Color(0xFFFFF8D6) else Color.Transparent)
                                    }
                                }
                            }
                        }
                    }
                    Text("— means no snapshot for that month (not zero). Months with data: ${rangeMonths.count { m -> stats.any { it.serviceMonth == m } }} of ${rangeMonths.size}.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // The mandatory end-of-report Summary (both views).
                com.emfitsolutions.gopreach.ui.components.EndSummary(summary.drop(1).map { it[0] to it[1] }, Modifier.padding(top = 8.dp))
            }
        }
    }
}

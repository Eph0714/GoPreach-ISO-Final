package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.data.repository.CompareChart
import com.emfitsolutions.gopreach.data.repository.CompareRow
import com.emfitsolutions.gopreach.data.repository.ComparisonReport

private fun say(n: Double): String = com.emfitsolutions.gopreach.domain.ManualSummaryNarration.number(n)
private fun fmt(n: Double?, unit: String): String = if (n == null) "-" else say(n) + if (unit == "%") "%" else ""
private fun sign(n: Double) = if (n > 0) "+" else ""
private fun diffOf(r: CompareRow) = r.difference?.let { sign(it) + say(it) + if (r.unit == "%") " pts" else "" } ?: "-"
private fun pctOf(r: CompareRow) = r.percent?.let { sign(it) + say(it) + "%" } ?: "-"
private fun arrow(direction: String) = when (direction) { "increase" -> "▲"; "decrease" -> "▼"; "unchanged" -> "="; else -> "–" }

private val UP = Color(0xFF1B7F4B)
private val DOWN = Color(0xFFB4540A)

/**
 * The comparative report of the Summary: the server's own report (indicators matched by name between two columns, two visits or two saved files), with the
 * choice of which to compare and the full report below. Everything shown, and everything read aloud, is what the server returned: nothing is recalculated here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComparativeReport(state: CompareUi, noComparisonMessage: String, canChoose: Boolean, viewModel: PreviousManualRecordViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val group = state.group
        if (canChoose) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (group != null) {
                    if (state.groups.size > 1) Picker("Compare", group.title, state.groups.map { it.title }) { i -> viewModel.pickGroup(state.groups[i]) }
                    val labels = group.columns.map { it.label }
                    Picker("Earlier", group.columns.firstOrNull { it.key == state.previous }?.label.orEmpty(), labels) { i -> viewModel.pickPair(group.columns[i].key, state.current) }
                    Picker("Later", group.columns.firstOrNull { it.key == state.current }?.label.orEmpty(), labels) { i -> viewModel.pickPair(state.previous, group.columns[i].key) }
                }
                if (state.others.isNotEmpty()) Picker("Other file", state.other?.name ?: "Compare with another saved file...", listOf("This worksheet only") + state.others.map { it.name }) { i ->
                    viewModel.compareWith(if (i == 0) null else state.others[i - 1])
                }
            }
        } else if (group != null) {
            Text("Save this file to choose other columns or to compare it with another saved file.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.note.isNotBlank()) Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer) { Text(state.note, Modifier.fillMaxWidth().padding(8.dp), style = MaterialTheme.typography.bodySmall) }
        val report = state.report
        if (report == null) {
            if (state.note.isBlank()) Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer) { Text(noComparisonMessage, Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodyMedium) }
        } else ReportBody(report)
    }
}

@Composable
private fun Picker(label: String, current: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text("$label: ", style = MaterialTheme.typography.labelSmall)
            Text(current, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(150.dp))
            Icon(Icons.Rounded.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(i) }) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportBody(r: ComparisonReport) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(r.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        val scope = listOfNotNull(
            r.scope.congregation.takeIf { it.isNotBlank() }?.let { "Congregation: $it" }, r.scope.fileName.takeIf { it.isNotBlank() }?.let { "File: $it" },
            r.scope.sheet.takeIf { it.isNotBlank() }?.let { "Worksheet: $it" }, "${r.counts.indicators} indicator${if (r.counts.indicators == 1) "" else "s"} analysed",
        ).joinToString(" · ")
        Text(scope, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Bullets("Executive summary", r.executive)
        Text("Comparative table", fontWeight = FontWeight.SemiBold)
        Card {
            Column(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
                Row {
                    Text("Indicator", Modifier.width(150.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    listOf(r.scope.previous, r.scope.current, "Difference", "Change").forEach { Text(it, Modifier.width(92.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                HorizontalDivider()
                r.table.forEach { x ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(x.label + if (x.total) " (total)" else "", Modifier.width(150.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(fmt(x.previous, x.unit), Modifier.width(92.dp)); Text(fmt(x.current, x.unit), Modifier.width(92.dp))
                        val tint = when (x.direction) { "increase" -> UP; "decrease" -> DOWN; else -> MaterialTheme.colorScheme.onSurface }
                        Text(arrow(x.direction) + " " + diffOf(x), Modifier.width(92.dp), color = tint, fontWeight = FontWeight.SemiBold)
                        Text(if (x.direction == "unavailable") "n/a" else pctOf(x), Modifier.width(92.dp))
                    }
                }
            }
        }
        r.chart?.let { Chart(it) }
        Chips("Key improvements", r.improvements, UP)
        Chips("Areas for attention", r.attention, DOWN)
        Chips("Other changes (no improvement or concern is implied)", r.otherChanges, MaterialTheme.colorScheme.outline)
        Chips("Unchanged indicators", r.unchanged, MaterialTheme.colorScheme.outline)
        Bullets("Data limitations", r.limitations)
        Bullets("Source and method", r.method)
    }
}

@Composable
private fun Bullets(title: String, items: List<String>) {
    if (items.isEmpty()) return
    Text(title, fontWeight = FontWeight.SemiBold)
    items.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(title: String, items: List<String>, tint: Color) {
    if (items.isEmpty()) return
    Text(title, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { Surface(shape = RoundedCornerShape(50), border = androidx.compose.foundation.BorderStroke(1.dp, tint), color = Color.Transparent) { Text(it, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium) } }
    }
}

@Composable
private fun Chart(c: CompareChart) {
    val max = maxOf(1.0, c.series.maxOf { maxOf(kotlin.math.abs(it.previous), kotlin.math.abs(it.current)) })
    Text("Chart", fontWeight = FontWeight.SemiBold)
    Text("Lighter bar: ${c.previousLabel}.  Solid bar: ${c.currentLabel}.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    c.series.forEach { p ->
        Column(Modifier.fillMaxWidth()) {
            Text(p.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.fillMaxWidth(maxOf(0.01f, (kotlin.math.abs(p.previous) / max).toFloat())).height(8.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(50)))
            Box(Modifier.padding(top = 2.dp).fillMaxWidth(maxOf(0.01f, (kotlin.math.abs(p.current) / max).toFloat())).height(8.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
        }
    }
}

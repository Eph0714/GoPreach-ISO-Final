package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.data.repository.AnalysisCard
import com.emfitsolutions.gopreach.data.repository.AnalysisChart
import com.emfitsolutions.gopreach.data.repository.CompareRow
import com.emfitsolutions.gopreach.data.repository.ManualAnalysis
import com.emfitsolutions.gopreach.domain.ManualSummaryNarration

private fun say(n: Double): String = ManualSummaryNarration.number(n)
private fun fmt(n: Double?, unit: String): String = if (n == null) "-" else say(n) + if (unit == "%") "%" else ""
private fun sign(n: Double) = if (n > 0) "+" else ""
private fun arrow(direction: String) = when (direction) { "increase" -> "▲"; "decrease" -> "▼"; "unchanged" -> "="; else -> "–" }

private val UP = Color(0xFF1B7F4B)
private val DOWN = Color(0xFFB4540A)
/** The GoPreach period colours: purple, lavender, medium yellow, dark purple, violet, soft yellow. */
private val PALETTE = listOf(Color(0xFF6B4FD1), Color(0xFFC9B8F6), Color(0xFFE9B83B), Color(0xFF3D2B8F), Color(0xFF9B86E8), Color(0xFFF3D98B))

private fun tintOf(direction: String, polarity: Int, neutral: Color): Color = when (direction) {
    "increase" -> if (polarity == -1) DOWN else UP
    "decrease" -> if (polarity == 1) DOWN else UP
    else -> neutral
}

/**
 * The Previous Manual Record's Summary and Comparative Analysis, exactly as the server built it: key indicator cards, bar graphs of every period, the comparison table,
 * findings with their source cells, data-quality notes and (only when it really worked) the free AI's plain-language wording. Nothing is calculated here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalysisView(ui: AnalysisUi, compare: CompareUi, viewModel: PreviousManualRecordViewModel, onShare: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (ui.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Analysing the worksheet...", style = MaterialTheme.typography.bodySmall) }
        ui.error?.let { err ->
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("The analysis could not be made: $err", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { viewModel.startAnalysis(refresh = true) }) { Text("Try again") }
                }
            }
        }
        val a = ui.analysis ?: return@Column
        if (!a.available) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer) { Text(a.message, Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodyMedium) }
            return@Column
        }
        val group = a.groups.firstOrNull { it.id == ui.group } ?: a.groups.firstOrNull()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (group != null) {
                if (a.groups.size > 1) Picker("Compare", group.title, a.groups.map { it.title }) { i -> val g = a.groups[i]; viewModel.pickAnalysis(g.id, g.recommended.previous, g.recommended.current) }
                val labels = group.columns.map { it.label }
                Picker("Earlier", group.columns.firstOrNull { it.key == ui.previous }?.label.orEmpty(), labels) { i -> viewModel.pickAnalysis(group.id, group.columns[i].key, ui.current) }
                Picker("Later", group.columns.firstOrNull { it.key == ui.current }?.label.orEmpty(), labels) { i -> viewModel.pickAnalysis(group.id, ui.previous, group.columns[i].key) }
            }
            if (compare.others.isNotEmpty()) Picker("Other file", "Compare with another saved file...", listOf("This worksheet only") + compare.others.map { it.name }) { i ->
                viewModel.compareWith(if (i == 0) null else compare.others[i - 1])
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.refreshAnalysis() }, enabled = !ui.aiBusy && !ui.loading) { Text("Refresh Analysis") }
            OutlinedButton(onClick = { onShare(plainText(a)) }) { Text("Export Summary") }
        }
        ReportHeader(a)
        AiNote(a, ui.aiBusy) { viewModel.refreshAnalysis() }
        if (a.executive.isNotEmpty()) { Heading("Executive summary"); a.executive.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) } }
        if (a.cards.isNotEmpty()) {
            Heading("Key indicators (${a.report?.scope?.previous.orEmpty()} → ${a.report?.scope?.current.orEmpty()})")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { a.cards.forEach { IndicatorCard(it) } }
        }
        a.charts.forEach { BarGraph(it) }
        a.report?.let { r -> ComparisonTable(r.scope.previous, r.scope.current, r.table) }
        if (a.findings.isNotEmpty()) {
            Heading("Findings (each one comes from cells of the worksheet)")
            a.findings.forEachIndexed { i, f ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${i + 1}. [${if (f.kind == "pattern") "Pattern" else "Fact"}] ${f.text}", style = MaterialTheme.typography.bodyMedium)
                    f.explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (f.evidence.isNotEmpty()) Text("Source cells: " + f.evidence.take(6).joinToString(", ") { "${it.cell} (${it.period})" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (a.quality.notes.isNotEmpty()) { Heading("Data-quality notes"); a.quality.notes.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) } }
        a.report?.let { r ->
            Heading("Source and method")
            (r.method + "The figures were calculated by GoPreach from the saved cells; the AI only words them and cannot add or change a figure.").forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun Heading(text: String) = Text(text, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)

@Composable
private fun ReportHeader(a: ManualAnalysis) {
    Text("Previous Manual Record — Summary and Comparative Analysis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    a.report?.title?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    val meta = listOfNotNull(
        a.source.congregation.takeIf { it.isNotBlank() }?.let { "Congregation: $it" }, a.source.fileName.takeIf { it.isNotBlank() }?.let { "File: $it" },
        a.source.sheet.takeIf { it.isNotBlank() }?.let { "Worksheet: $it" }, "Periods: " + a.periods.joinToString(", ") { it.label }, "Analysis generated ${a.generatedAt.replace('T', ' ').take(16)} UTC",
    ).joinToString(" · ")
    Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Says plainly whether the AI wrote the wording; when it did not, why, with the figures below calculated by GoPreach regardless. */
@Composable
private fun AiNote(a: ManualAnalysis, busy: Boolean, onRetry: () -> Unit) {
    when {
        busy -> Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer) {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("The free AI is writing the plain-language summary... The verified comparison below is already complete and does not depend on it.", style = MaterialTheme.typography.bodySmall)
            }
        }
        a.ai.status == "OK" -> Surface(shape = MaterialTheme.shapes.small, color = UP.copy(alpha = 0.14f)) {
            Text("The wording of the summary and findings was written by ${a.ai.model ?: "the AI"}, a free AI model running on GoPreach's own server. Every figure in it was checked against GoPreach's calculations.", Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall)
        }
        a.ai.status == "NOT_REQUESTED" || a.ai.status == "SKIPPED" -> Unit
        else -> Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer) {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(a.ai.message, style = MaterialTheme.typography.bodySmall)
                if (a.ai.status != "DISABLED") OutlinedButton(onClick = onRetry) { Text("Try the AI summary again") }
            }
        }
    }
}

@Composable
private fun IndicatorCard(c: AnalysisCard) {
    Card(Modifier.width(158.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(c.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(fmt(c.current, c.unit), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            val change = if (c.direction == "unavailable") "Not comparable" else "${arrow(c.direction)} ${c.difference?.let { sign(it) + say(it) + if (c.unit == "%") " pts" else "" }.orEmpty()}${c.percent?.let { " (" + sign(it) + say(it) + "%)" }.orEmpty()}"
            Text(change, color = tintOf(c.direction, c.polarity, MaterialTheme.colorScheme.onSurface), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            Text("was ${fmt(c.previous, c.unit)}" + if (c.percent == null && c.direction != "unavailable" && (c.difference ?: 0.0) != 0.0) " · no % (earlier value was 0)" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A grouped bar graph: one row per indicator, one bar per reporting period with its value written on it; a missing value is a gap, never a zero. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BarGraph(ch: AnalysisChart) {
    val max = maxOf(1.0, ch.series.flatMap { it.values }.filterNotNull().maxOfOrNull { kotlin.math.abs(it) } ?: 1.0)
    val unit = if (ch.kind == "percent") "%" else ""
    Heading(ch.title)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ch.periods.forEachIndexed { i, p ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(Modifier.width(12.dp).height(12.dp).background(PALETTE[i % PALETTE.size], RoundedCornerShape(3.dp)))
                Text("  $p", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    ch.series.forEach { s ->
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(s.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            s.values.forEachIndexed { i, v ->
                if (v == null) Text("${ch.periods.getOrElse(i) { "" }}: no value", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth(maxOf(0.015f, (kotlin.math.abs(v) / max).toFloat())).height(10.dp).background(PALETTE[i % PALETTE.size], RoundedCornerShape(50)))
                    }
                    Text(" ${say(v)}$unit", Modifier.width(64.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    Text(ch.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ComparisonTable(previous: String, current: String, rows: List<CompareRow>) {
    Heading("Comparison table")
    Card {
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
            Row {
                Text("Indicator", Modifier.width(150.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                listOf("$previous (earlier)", "$current (later)", "Difference", "Change").forEach { Text(it, Modifier.width(104.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            HorizontalDivider()
            rows.forEach { x ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(x.label + if (x.total && !x.label.trim().lowercase().matches(Regex("^(grand\\s+)?(total|sum|overall).*"))) " (total)" else "", Modifier.width(150.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(fmt(x.previous, x.unit), Modifier.width(104.dp)); Text(fmt(x.current, x.unit), Modifier.width(104.dp))
                    Text(if (x.direction == "unavailable") "not available" else arrow(x.direction) + " " + (x.difference?.let { sign(it) + say(it) + if (x.unit == "%") " pts" else "" } ?: "-"), Modifier.width(104.dp), color = tintOf(x.direction, x.polarity, MaterialTheme.colorScheme.onSurface), fontWeight = FontWeight.SemiBold)
                    Text(if (x.direction == "unavailable") "not available" else x.percent?.let { sign(it) + say(it) + "%" } ?: if ((x.difference ?: 0.0) != 0.0) "not applicable" else "0%", Modifier.width(104.dp))
                }
            }
        }
    }
}

/** The report as plain text for sharing: summary, periods, the comparison, findings, data-quality notes and the source. */
fun plainText(a: ManualAnalysis): String = buildString {
    appendLine("Previous Manual Record — Summary and Comparative Analysis")
    a.report?.title?.let { appendLine(it) }
    appendLine("Congregation: ${a.source.congregation}")
    appendLine("File: ${a.source.fileName} · Worksheet: ${a.source.sheet}")
    appendLine("Reporting periods: ${a.periods.joinToString(", ") { it.label }}")
    appendLine("Analysis generated: ${a.generatedAt.replace('T', ' ').take(16)} UTC")
    appendLine(if (a.aiOk) "Wording: written by ${a.ai.model ?: "AI"} (free AI on GoPreach's server), checked against GoPreach's calculations." else "Wording: calculated by GoPreach without AI.")
    appendLine(); appendLine("EXECUTIVE SUMMARY"); a.executive.forEach { appendLine("- $it") }
    a.report?.let { r ->
        appendLine(); appendLine("COMPARISON: ${r.scope.previous} (earlier) → ${r.scope.current} (later)")
        r.table.forEach { x ->
            appendLine("${x.label}: ${fmt(x.previous, x.unit)} → ${fmt(x.current, x.unit)}" + if (x.direction == "unavailable") " (not available)" else "  difference ${x.difference?.let { sign(it) + say(it) } ?: "-"}, change ${x.percent?.let { sign(it) + say(it) + "%" } ?: "not applicable"}")
        }
    }
    if (a.findings.isNotEmpty()) { appendLine(); appendLine("FINDINGS"); a.findings.forEachIndexed { i, f -> appendLine("${i + 1}. ${f.text}${f.explanation?.let { " — $it" }.orEmpty()}") } }
    if (a.quality.notes.isNotEmpty()) { appendLine(); appendLine("DATA-QUALITY NOTES"); a.quality.notes.forEach { appendLine("- $it") } }
    appendLine(); appendLine("The original spreadsheet is never changed. Figures were calculated by GoPreach from the saved cells.")
}

package com.emfitsolutions.gopreach.ui.components

import com.emfitsolutions.gopreach.data.print.escapeHtml
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emfitsolutions.gopreach.data.print.ReportPrinter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One thing that can be graphed for two month ranges: its name, how to print a value, and the real month-by-month
 * values of each range (one entry per month, in order). The ranges may have different numbers of months.
 */
data class GraphMetric(
    val label: String,
    val format: (Double) -> String,
    val valuesA: List<Double>,
    val valuesB: List<Double>,
) {
    val totalA: Double get() = valuesA.sum()
    val totalB: Double get() = valuesB.sum()
    val difference: Double get() = totalB - totalA
}

/** Hours-like metric: values are hours, printed as "12h 30m". */
fun hoursFormat(v: Double): String {
    val minutes = Math.round(v * 60).toInt()
    return "${minutes / 60}h ${minutes % 60}m"
}

fun countFormat(v: Double): String = Math.round(v).toString()

private val ColorFirst = Color(0xFF1E88E5)
private val ColorSecond = Color(0xFFEF6C00)

private fun signed(delta: Double, format: (Double) -> String): String =
    (if (delta > 0.0001) "+" else if (delta < -0.0001) "-" else "") + format(Math.abs(delta))

/**
 * The Comparative Graph shared by every report that compares two month ranges. It is always labelled with the actual
 * ranges the user picked ([labelA] vs [labelB], e.g. "September 2025 – October 2025 vs September 2026 – October 2026")
 * — never a generic name. One line per range for the chosen [metrics] entry, month by month (the real month names of
 * each range sit under the lines), tap for exact values, then a compact summary with the difference per metric.
 * All values are the ones passed in; nothing is estimated.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComparativeGraphReport(
    labelA: String,
    labelB: String,
    monthsA: List<Long>,
    monthsB: List<Long>,
    metrics: List<GraphMetric>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val metric = metrics.getOrNull(selected) ?: return
    var showA by remember { mutableStateOf(true) }
    var showB by remember { mutableStateOf(true) }
    var tapped by remember(labelA, labelB, selected) { mutableStateOf<Int?>(null) }
    val monthFormat = remember { SimpleDateFormat("MMM yyyy", Locale.getDefault()) }
    val shortMonth = remember { SimpleDateFormat("MMM", Locale.getDefault()) }
    val wide = LocalConfiguration.current.screenWidthDp >= 720

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Comparative Graph", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(labelA, style = MaterialTheme.typography.labelMedium, color = ColorFirst, fontWeight = FontWeight.SemiBold)
            Text("vs", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(labelB, style = MaterialTheme.typography.labelMedium, color = ColorSecond, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                metrics.forEachIndexed { i, m ->
                    FilterChip(selected = selected == i, onClick = { onSelect(i) }, label = { Text(m.label, fontSize = 12.sp) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(selected = showA, onClick = { showA = !showA }, label = { Text(labelA, fontSize = 11.sp) })
                FilterChip(selected = showB, onClick = { showB = !showB }, label = { Text(labelB, fontSize = 11.sp) })
            }

            val longest = maxOf(metric.valuesA.size, metric.valuesB.size)
            if (longest == 0 || (metric.valuesA.all { it == 0.0 } && metric.valuesB.all { it == 0.0 })) {
                Text("No ${metric.label} data in the selected ranges.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val max = maxOf(metric.valuesA.maxOrNull() ?: 0.0, metric.valuesB.maxOrNull() ?: 0.0).coerceAtLeast(0.0001)
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (wide) 220.dp else 170.dp)
                        .pointerInput(longest) {
                            detectTapGestures { offset ->
                                val step = if (longest > 1) size.width.toFloat() / (longest - 1) else size.width.toFloat()
                                tapped = if (longest > 1) Math.round(offset.x / step).coerceIn(0, longest - 1) else 0
                            }
                        },
                ) {
                    val step = if (longest > 1) size.width / (longest - 1) else 0f
                    fun y(v: Double) = (size.height - (v / max * size.height)).toFloat()
                    fun draw(series: List<Double>, color: Color) {
                        val path = Path()
                        series.forEachIndexed { i, v -> if (i == 0) path.moveTo(step * i, y(v)) else path.lineTo(step * i, y(v)) }
                        drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
                        series.forEachIndexed { i, v -> drawCircle(color, 4.dp.toPx(), Offset(step * i, y(v))) }
                    }
                    if (showA) draw(metric.valuesA, ColorFirst)
                    if (showB) draw(metric.valuesB, ColorSecond)
                    tapped?.let { i -> drawLine(Color.Gray.copy(alpha = 0.6f), Offset(step * i, 0f), Offset(step * i, size.height), 1.dp.toPx()) }
                }
                // The actual months under each position: first range on top, second range below.
                Row(modifier = Modifier.fillMaxWidth()) {
                    (0 until longest).forEach { i ->
                        Text(monthsA.getOrNull(i)?.let { shortMonth.format(Date(it)) } ?: "", style = MaterialTheme.typography.labelSmall, color = ColorFirst, modifier = Modifier.weight(1f), maxLines = 1)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    (0 until longest).forEach { i ->
                        Text(monthsB.getOrNull(i)?.let { shortMonth.format(Date(it)) } ?: "", style = MaterialTheme.typography.labelSmall, color = ColorSecond, modifier = Modifier.weight(1f), maxLines = 1)
                    }
                }
                val i = tapped
                if (i != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (showA) {
                            Text(
                                monthsA.getOrNull(i)?.let { "${monthFormat.format(Date(it))}: ${metric.format(metric.valuesA.getOrElse(i) { 0.0 })}" } ?: "$labelA: no month here",
                                style = MaterialTheme.typography.bodySmall,
                                color = ColorFirst,
                            )
                        }
                        if (showB) {
                            Text(
                                monthsB.getOrNull(i)?.let { "${monthFormat.format(Date(it))}: ${metric.format(metric.valuesB.getOrElse(i) { 0.0 })}" } ?: "$labelB: no month here",
                                style = MaterialTheme.typography.bodySmall,
                                color = ColorSecond,
                            )
                        }
                    }
                } else {
                    Text("Tap the graph to see exact values.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    // Comparison Summary under the graph, with the real ranges as headings.
    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) { SummaryBlock(labelA, ColorFirst, metrics) { it.totalA } }
            Box(modifier = Modifier.weight(1f)) { SummaryBlock(labelB, ColorSecond, metrics) { it.totalB } }
        }
    } else {
        SummaryBlock(labelA, ColorFirst, metrics) { it.totalA }
        SummaryBlock(labelB, ColorSecond, metrics) { it.totalB }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Difference (second range minus first range)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            metrics.forEach { m ->
                val d = m.difference
                Text(
                    "${m.label} Difference: ${signed(d, m.format)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (m === metric) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        d > 0.0001 -> Color(0xFF2E7D32)
                        d < -0.0001 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun SummaryBlock(range: String, accent: Color, metrics: List<GraphMetric>, total: (GraphMetric) -> Double) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(range, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = accent)
            metrics.forEach { m -> Text("${m.label}: ${m.format(total(m))}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/**
 * The same Comparative Graph as print/PDF HTML: an inline SVG line chart of [metric] for the two ranges (titled with
 * the actual ranges) plus a compact summary table, sized for one landscape page.
 */
fun comparativeGraphHtml(labelA: String, labelB: String, monthsA: List<Long>, monthsB: List<Long>, metrics: List<GraphMetric>, metric: GraphMetric): String {
    fun e(s: String) = escapeHtml(s)
    val shortMonth = SimpleDateFormat("MMM", Locale.getDefault())
    val longest = maxOf(metric.valuesA.size, metric.valuesB.size).coerceAtLeast(1)
    val max = maxOf(metric.valuesA.maxOrNull() ?: 0.0, metric.valuesB.maxOrNull() ?: 0.0).coerceAtLeast(0.0001)
    val w = 900.0
    val h = 220.0
    fun xAt(i: Int) = if (longest > 1) w * i / (longest - 1) else w / 2
    fun yAt(v: Double) = h - v / max * h
    fun line(series: List<Double>, color: String): String {
        val pts = series.mapIndexed { i, v -> String.format(Locale.US, "%.1f,%.1f", xAt(i), yAt(v)) }
        val dots = series.mapIndexed { i, v -> String.format(Locale.US, "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"4\" fill=\"%s\"/>", xAt(i), yAt(v), color) }
        return "<polyline fill=\"none\" stroke=\"$color\" stroke-width=\"3\" points=\"${pts.joinToString(" ")}\"/>" + dots.joinToString("")
    }
    val delta = metric.difference
    val direction = if (delta > 0.0001) "increased" else if (delta < -0.0001) "decreased" else "did not change"
    return buildString {
        append("<div style=\"page-break-after:always\">")
        append("<h3 style=\"margin:0 0 4px 0\">Comparative Graph — ").append(e(metric.label)).append("</h3>")
        append("<div><span style=\"color:#1E88E5\"><b>").append(e(labelA)).append("</b></span> vs <span style=\"color:#EF6C00\"><b>").append(e(labelB)).append("</b></span></div>")
        append("<svg viewBox=\"-10 -10 ${(w + 20).toInt()} ${(h + 44).toInt()}\" style=\"width:100%;height:240px;margin-top:6px\">")
        append("<rect x=\"0\" y=\"0\" width=\"$w\" height=\"$h\" fill=\"none\" stroke=\"#ccc\"/>")
        append(line(metric.valuesA, "#1E88E5")).append(line(metric.valuesB, "#EF6C00"))
        for (i in 0 until longest) {
            monthsA.getOrNull(i)?.let { append(String.format(Locale.US, "<text x=\"%.1f\" y=\"%.0f\" font-size=\"11\" fill=\"#1E88E5\" text-anchor=\"middle\">%s</text>", xAt(i), h + 15, e(shortMonth.format(Date(it))))) }
            monthsB.getOrNull(i)?.let { append(String.format(Locale.US, "<text x=\"%.1f\" y=\"%.0f\" font-size=\"11\" fill=\"#EF6C00\" text-anchor=\"middle\">%s</text>", xAt(i), h + 29, e(shortMonth.format(Date(it))))) }
        }
        append("</svg>")
        append("<table style=\"margin-top:6px\"><tr><th class=\"l\"></th><th>").append(e(labelA)).append("</th><th>").append(e(labelB)).append("</th><th>Difference</th></tr>")
        metrics.forEach { m ->
            append("<tr><td class=\"l\">").append(e(m.label)).append("</td><td>").append(e(m.format(m.totalA))).append("</td><td>").append(e(m.format(m.totalB))).append("</td><td>")
                .append(e(signed(m.difference, m.format))).append("</td></tr>")
        }
        append("</table>")
        append("<p><b>").append(e(metric.label)).append(":</b> the second range ").append(direction).append(" compared with the first.</p>")
        append("</div>")
    }
}

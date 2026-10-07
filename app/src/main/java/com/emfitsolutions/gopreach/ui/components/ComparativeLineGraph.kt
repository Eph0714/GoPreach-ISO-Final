package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LineFirst = Color(0xFF1E88E5)
private val LineSecond = Color(0xFFEF6C00)

/**
 * Line-graph version of the comparative graph: one line per month range over the month positions (the first month of
 * each range at the left), a dot per month, tap a position for the exact values, and the totals and difference of the
 * chosen metric. Uses the same [GraphMetric] data as [ComparativeGraphReport]; nothing is estimated.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComparativeLineGraph(
    labelA: String,
    labelB: String,
    monthsA: List<Long>,
    monthsB: List<Long>,
    metrics: List<GraphMetric>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val metric = metrics.getOrNull(selected) ?: return
    var tapped by remember(labelA, labelB, selected) { mutableStateOf<Int?>(null) }
    val monthFormat = remember { SimpleDateFormat("MMM yyyy", Locale.getDefault()) }
    val shortMonth = remember { SimpleDateFormat("MMM", Locale.getDefault()) }
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val longest = maxOf(metric.valuesA.size, metric.valuesB.size)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Line Graph Comparison", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(labelA, style = MaterialTheme.typography.labelMedium, color = LineFirst, fontWeight = FontWeight.SemiBold)
            Text("vs", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(labelB, style = MaterialTheme.typography.labelMedium, color = LineSecond, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                metrics.forEachIndexed { i, m -> FilterChip(selected = selected == i, onClick = { onSelect(i) }, label = { Text(m.label, fontSize = 12.sp) }) }
            }
            if (longest == 0 || (metric.valuesA.all { it == 0.0 } && metric.valuesB.all { it == 0.0 })) {
                Text("No ${metric.label} data in the selected ranges.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val max = maxOf(metric.valuesA.maxOrNull() ?: 0.0, metric.valuesB.maxOrNull() ?: 0.0).coerceAtLeast(0.0001)
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .pointerInput(longest) {
                            detectTapGestures { offset -> tapped = (offset.x / (size.width.toFloat() / longest)).toInt().coerceIn(0, longest - 1) }
                        },
                ) {
                    val slot = size.width / longest
                    val pad = 12.dp.toPx()
                    val chartHeight = size.height - 2 * pad
                    listOf(0f, 0.5f, 1f).forEach { f ->
                        val y = pad + chartHeight * (1 - f)
                        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    }
                    tapped?.let { t -> drawLine(gridColor, Offset(slot * t + slot / 2, 0f), Offset(slot * t + slot / 2, size.height), 2.dp.toPx()) }
                    fun series(values: List<Double>, color: Color) {
                        val points = values.mapIndexed { i, v -> Offset(slot * i + slot / 2, pad + chartHeight * (1 - (v / max).toFloat())) }
                        points.zipWithNext().forEach { (a, b) -> drawLine(color, a, b, 3.dp.toPx(), StrokeCap.Round) }
                        points.forEach { drawCircle(color, 4.dp.toPx(), it) }
                    }
                    series(metric.valuesA, LineFirst)
                    series(metric.valuesB, LineSecond)
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    (0 until longest).forEach { i ->
                        Text(monthsA.getOrNull(i)?.let { shortMonth.format(Date(it)) } ?: "", style = MaterialTheme.typography.labelSmall, color = LineFirst, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    (0 until longest).forEach { i ->
                        Text(monthsB.getOrNull(i)?.let { shortMonth.format(Date(it)) } ?: "", style = MaterialTheme.typography.labelSmall, color = LineSecond, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1)
                    }
                }
                val i = tapped
                if (i != null) {
                    Text(
                        monthsA.getOrNull(i)?.let { "${monthFormat.format(Date(it))}: ${metric.format(metric.valuesA.getOrElse(i) { 0.0 })}" } ?: "$labelA: no month here",
                        style = MaterialTheme.typography.bodySmall, color = LineFirst,
                    )
                    Text(
                        monthsB.getOrNull(i)?.let { "${monthFormat.format(Date(it))}: ${metric.format(metric.valuesB.getOrElse(i) { 0.0 })}" } ?: "$labelB: no month here",
                        style = MaterialTheme.typography.bodySmall, color = LineSecond,
                    )
                } else {
                    Text("Tap a month position to see exact values.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val diff = metric.difference
            Text(
                "Total: ${metric.format(metric.totalA)} vs ${metric.format(metric.totalB)}  ·  Difference: " +
                    (if (diff > 0.0001) "+" else if (diff < -0.0001) "-" else "") + metric.format(Math.abs(diff)),
                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

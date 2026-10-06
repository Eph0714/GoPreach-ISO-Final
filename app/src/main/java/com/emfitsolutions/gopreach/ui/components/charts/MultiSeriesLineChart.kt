package com.emfitsolutions.gopreach.ui.components.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.max

data class LineSeries(val label: String, val color: Color, val points: List<Float>)

/**
 * Lightweight, dependency-free multi-series line chart — same "no charting
 * library in this project" call [SimpleBarChart] already made, extended to
 * lines instead of bars for the Comparative Report's "animated color-coded
 * Line Graph" (My Planner enhancement spec).
 *
 * Each series is normalized to its own max (0..1) rather than sharing one
 * y-axis, since Hours/Return Visits/Bible Studies live on wildly different
 * scales — this keeps every line's *trend* readable at a glance rather than
 * flattening the smaller-scale ones to the bottom of the chart. [xLabels]
 * (one per point) render below the chart; a legend of series names renders
 * above it.
 */
@Composable
fun MultiSeriesLineChart(series: List<LineSeries>, xLabels: List<String>, modifier: Modifier = Modifier, sharedScale: Boolean = false) {
    var progress by remember(series) { mutableFloatStateOf(0f) }
    val animatedProgress by animateFloatAsState(targetValue = progress, animationSpec = tween(700), label = "lineChartProgress")
    LaunchedEffect(series) { progress = 1f }

    Column(modifier = modifier) {
        LegendRow(series)
        Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
            if (series.isEmpty() || series.first().points.size < 2) return@Canvas
            val pointCount = series.first().points.size
            val stepX = size.width / (pointCount - 1)
            series.forEach { s ->
                // [sharedScale]: every line on one y-axis (same unit, e.g. counts per month); default is one scale per line.
                val maxValue = max(if (sharedScale) series.maxOf { it.points.maxOrNull() ?: 0f } else (s.points.maxOrNull() ?: 0f), 0.0001f)
                val visiblePointCount = (1 + animatedProgress * (pointCount - 1))
                val fullPoints = (pointCount - 1).coerceAtMost((visiblePointCount).toInt())
                val path = androidx.compose.ui.graphics.Path()
                var previous: Offset? = null
                for (i in 0..fullPoints) {
                    val x = stepX * i
                    val y = size.height - (s.points[i] / maxValue) * size.height
                    val point = Offset(x, y)
                    if (previous == null) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                    previous = point
                }
                // Interpolate the final partial segment for a smooth draw-in
                // rather than the line jumping point-to-point.
                val fractional = visiblePointCount - fullPoints
                if (fullPoints < pointCount - 1 && fractional in 0f..1f) {
                    val startY = size.height - (s.points[fullPoints] / maxValue) * size.height
                    val endY = size.height - (s.points[fullPoints + 1] / maxValue) * size.height
                    val startX = stepX * fullPoints
                    val endX = stepX * (fullPoints + 1)
                    path.lineTo(startX + (endX - startX) * fractional, startY + (endY - startY) * fractional)
                }
                drawPath(path, color = s.color, style = Stroke(width = 3.dp.toPx()))
                for (i in 0..fullPoints) {
                    val x = stepX * i
                    val y = size.height - (s.points[i] / maxValue) * size.height
                    drawCircle(color = s.color, radius = 4.dp.toPx(), center = Offset(x, y))
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            xLabels.forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun LegendRow(series: List<LineSeries>) {
    Row(modifier = Modifier.fillMaxWidth()) {
        series.forEach { s ->
            Row(modifier = Modifier.padding(end = 16.dp)) {
                androidx.compose.foundation.Canvas(modifier = Modifier.size(10.dp).padding(top = 3.dp)) {
                    drawCircle(color = s.color, radius = size.minDimension / 2)
                }
                Text(
                    " ${s.label}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = s.color,
                )
            }
        }
    }
}

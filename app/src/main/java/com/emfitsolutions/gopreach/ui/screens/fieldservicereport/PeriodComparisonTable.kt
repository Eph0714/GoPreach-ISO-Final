package com.emfitsolutions.gopreach.ui.screens.fieldservicereport

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private fun signed(d: Double) = (if (d > 0) "+" else "") + formatHours(d)

/**
 * Compare Periods as a table: one row per publisher status (plus a Total row), with No. of Reports, Hours and Bible
 * Studies for the first period, the second period and the change between them.
 */
@Composable
fun PeriodComparisonTable(labelA: String, sheetsA: List<FieldServiceReportSheet>, labelB: String, sheetsB: List<FieldServiceReportSheet>) {
    val all = sheetsA + sheetsB
    val columns = StatusColumn.entries.filter { c -> c.alwaysShown || all.any { s -> s.reportColumns.contains(c) } }
    fun reports(s: List<FieldServiceReportSheet>, c: StatusColumn) = s.sumOf { it.reportCount(c) }
    fun hours(s: List<FieldServiceReportSheet>, c: StatusColumn) = s.sumOf { it.totalHours(c) }
    fun studies(s: List<FieldServiceReportSheet>, c: StatusColumn) = s.sumOf { it.totalBibleStudies(c) }
    val border = MaterialTheme.colorScheme.outline
    val head = MaterialTheme.colorScheme.secondaryContainer
    val widths = listOf(90.dp) + List(9) { 64.dp }

    @Composable
    fun Cell(text: String, width: Dp, bold: Boolean = false, fill: Color = Color.Transparent, left: Boolean = false) {
        Box(
            modifier = Modifier.width(width).height(34.dp).background(fill).border(0.5.dp, border).padding(horizontal = 6.dp),
            contentAlignment = if (left) Alignment.CenterStart else Alignment.Center,
        ) { Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1) }
    }

    Column {
        Text("Table View", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
        Text("$labelA  vs  $labelB", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Row {
                Cell("", widths[0], fill = head)
                listOf("No. of Reports", "Hours", "Bible Studies").forEach { Box(Modifier.width(192.dp)) { Cell(it, 192.dp, true, head) } }
            }
            Row {
                Cell("Status", widths[0], true, head, left = true)
                repeat(3) { listOf(labelA.take(10), labelB.take(10), "Change").forEachIndexed { i, t -> Cell(t, widths[1 + i], true, head) } }
            }
            columns.forEach { c ->
                Row {
                    Cell(c.label, widths[0], left = true)
                    val ra = reports(sheetsA, c); val rb = reports(sheetsB, c)
                    Cell("$ra", widths[1]); Cell("$rb", widths[2]); Cell(signed((rb - ra).toDouble()), widths[3])
                    val ha = hours(sheetsA, c); val hb = hours(sheetsB, c)
                    Cell(formatHours(ha), widths[4]); Cell(formatHours(hb), widths[5]); Cell(signed(hb - ha), widths[6])
                    val sa = studies(sheetsA, c); val sb = studies(sheetsB, c)
                    Cell("$sa", widths[7]); Cell("$sb", widths[8]); Cell(signed((sb - sa).toDouble()), widths[9])
                }
            }
            Row {
                val yellow = Color(0xFFFFFF00)
                Cell("Total", widths[0], true, yellow, left = true)
                val ra = columns.sumOf { reports(sheetsA, it) }; val rb = columns.sumOf { reports(sheetsB, it) }
                val ha = columns.sumOf { hours(sheetsA, it) }; val hb = columns.sumOf { hours(sheetsB, it) }
                val sa = columns.sumOf { studies(sheetsA, it) }; val sb = columns.sumOf { studies(sheetsB, it) }
                listOf("$ra", "$rb", signed((rb - ra).toDouble()), formatHours(ha), formatHours(hb), signed(hb - ha), "$sa", "$sb", signed((sb - sa).toDouble()))
                    .forEachIndexed { i, t -> Cell(t, widths[1 + i], true, yellow) }
            }
        }
    }
}

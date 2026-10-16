package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.data.model.CoMonthStatus
import com.emfitsolutions.gopreach.data.model.CoReportStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Widths: List<Dp> = listOf(48.dp, 130.dp, 150.dp, 64.dp, 140.dp, 140.dp)

/**
 * The Table View of the "Send to Circuit Overseer" panels (Field Service Report and Meeting Attendance): one row per service month with its
 * status, version, and when / by whom it was sent. A tick selects a month for sending; a Received month cannot be selected. The heading stays
 * pinned while the months scroll.
 */
@Composable
fun SendStatusTable(
    months: List<Long>,
    statusOf: (Long) -> CoMonthStatus?,
    selected: Set<Long>,
    onToggle: (Long) -> Unit,
    monthLabel: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    val stamp = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
    PinnedTable(
        rowCount = months.size, modifier = modifier, maxHeight = 360.dp,
        header = {
            Row(Modifier.height(40.dp)) {
                listOf("", "Month", "Status", "Ver.", "Sent", "Sent by").forEachIndexed { i, t -> Cell(t, Widths[i], MaterialTheme.colorScheme.secondaryContainer, bold = true) }
            }
        },
    ) { i ->
        val m = months[i]
        val s = statusOf(m)
        val status = s?.status ?: CoReportStatus.NOT_SUBMITTED
        val received = status == CoReportStatus.RECEIVED
        val fill = if (m in selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else if (i % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface
        val color = when (status) {
            CoReportStatus.RECEIVED -> Color(0xFF2E7D32)
            CoReportStatus.RETURNED, CoReportStatus.CANCELLED -> Color(0xFFE65100)
            CoReportStatus.SUBMITTED -> MaterialTheme.colorScheme.primary
            CoReportStatus.NOT_SUBMITTED -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(Modifier.height(44.dp).clickable(enabled = !received) { onToggle(m) }) {
            Box(Modifier.width(Widths[0]).fillMaxHeight().background(fill).border(0.5.dp, MaterialTheme.colorScheme.outline), contentAlignment = Alignment.Center) {
                Checkbox(checked = m in selected, onCheckedChange = { onToggle(m) }, enabled = !received)
            }
            Cell(monthLabel(m), Widths[1], fill)
            Cell(status.workflowLabel, Widths[2], fill, color = color, bold = true)
            Cell(if (s != null && status != CoReportStatus.NOT_SUBMITTED) s.version.toString() else "-", Widths[3], fill)
            Cell(if (s != null && s.submittedAt > 0 && status != CoReportStatus.NOT_SUBMITTED) stamp.format(Date(s.submittedAt)) else "-", Widths[4], fill)
            Cell(if (status != CoReportStatus.NOT_SUBMITTED) s?.submittedByName?.ifBlank { null } ?: "-" else "-", Widths[5], fill)
        }
    }
}

@Composable
private fun Cell(text: String, width: Dp, fill: Color, bold: Boolean = false, color: Color = Color.Unspecified) {
    Box(Modifier.width(width).fillMaxHeight().background(fill).border(0.5.dp, MaterialTheme.colorScheme.outline).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = color, maxLines = 2)
    }
}

package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.data.model.CoReceivedReport
import com.emfitsolutions.gopreach.domain.SnapshotComparison
import com.emfitsolutions.gopreach.domain.SnapshotDiff
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fmtHours(h: Double): String = if (h % 1.0 == 0.0) h.toLong().toString() else "%.1f".format(Locale.US, h)
private fun fmtStamp(millis: Long): String = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(millis))

/** "Pub" -> "Publishers", "AP" -> "Auxiliary Pioneers", ... (the status labels a snapshot's lines carry). */
fun statusName(label: String): String = when (label) {
    "Pub" -> "Publishers"
    "UP" -> "Unbaptized Publishers"
    "AP" -> "Auxiliary Pioneers"
    "RP" -> "Regular Pioneers"
    "SP" -> "Special Pioneers"
    "Irr" -> "Irregular Publishers"
    "Inactive" -> "Inactive Publishers"
    "Reproof" -> "Publishers Under Reproof"
    else -> label
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

/**
 * What the Circuit Overseer sees about ONE submitted snapshot: the month, when it was submitted and by whom, which send it is, and
 * the headcount by service category - including the publishers whose category differs from the month before and how many
 * categories the Admin had to confirm. It is a frozen Submitted CO Snapshot, never the congregation's live data.
 */
@Composable
fun SnapshotDetailsPanel(report: CoReceivedReport, monthLabel: String, modifier: Modifier = Modifier, history: List<com.emfitsolutions.gopreach.data.model.CoReportEvent> = emptyList()) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Submitted CO Snapshot") {
            Text("Congregation: " + report.congregationName)
            Text("Reporting month: $monthLabel")
            Text("Snapshot: send " + report.version)
            Text("Submitted: " + fmtStamp(report.submittedAt) + if (report.submittedByName.isNotBlank()) " by " + report.submittedByName else "")
            Text(
                "A frozen copy of the congregation's report at the time it was sent. Later changes to the congregation's live records do not change it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (history.isNotEmpty()) SectionCard("Version history") {
            history.forEach { e ->
                Text(
                    fmtStamp(e.at) + " - " + e.action + " (" + e.userName + (if (e.userRole.isNotBlank()) ", " + e.userRole.replace('_', ' ').lowercase() else "") + ") - version " + e.version +
                        (e.remarks?.takeIf { it.isNotBlank() }?.let { " - Reason / remarks: $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        SectionCard("Publishers by category") {
            Text("Total in the report: " + report.publisherCount, fontWeight = FontWeight.SemiBold)
            report.countByStatus.entries.sortedBy { it.key }.forEach { (label, n) -> Text(statusName(label) + ": " + n) }
            Text("Participated: " + report.participatedCount + " · Hours: " + fmtHours(report.totalHours) + " · Bible Studies: " + report.totalBibleStudies, style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("Category changes this month") {
            if (report.categoryChanges.isEmpty()) {
                Text("No publisher changed category compared with the month before.", style = MaterialTheme.typography.bodySmall)
            } else {
                report.categoryChanges.forEach { Text(it.name + ": " + statusName(it.from) + " -> " + statusName(it.to)) }
            }
            if (report.categoriesConfirmedByAdmin > 0) {
                Text(
                    report.categoriesConfirmedByAdmin.toString() + " publisher categor" + (if (report.categoriesConfirmedByAdmin == 1) "y was" else "ies were") + " confirmed by the congregation's Admin because none was on record.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Previous send, new send and the difference, side by side, for the same congregation and the same month. */
@Composable
fun SnapshotComparisonPanel(diff: SnapshotDiff, monthLabel: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Send " + diff.current.version + " compared with send " + diff.previous.version) {
            Text("Reporting month: $monthLabel (the same month in both)", fontWeight = FontWeight.SemiBold)
            Text("Previous: send " + diff.previous.version + " - submitted " + fmtStamp(diff.previous.submittedAt), style = MaterialTheme.typography.bodySmall)
            Text("New: send " + diff.current.version + " - submitted " + fmtStamp(diff.current.submittedAt), style = MaterialTheme.typography.bodySmall)
            if (!diff.hasChanges) Text("No differences between the two sends.", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        SectionCard("Totals") {
            Row(Modifier.fillMaxWidth()) {
                Text("", Modifier.weight(2.2f)); Text("Prev.", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text("New", Modifier.weight(1f), fontWeight = FontWeight.Bold); Text("Diff.", Modifier.weight(1f), fontWeight = FontWeight.Bold)
            }
            HorizontalDivider()
            TotalsRow("Publishers (all)", diff.previousTotals.publishers.toDouble(), diff.currentTotals.publishers.toDouble())
            diff.statusLabels.forEach { label ->
                TotalsRow(statusName(label), (diff.previousTotals.byStatus[label] ?: 0).toDouble(), (diff.currentTotals.byStatus[label] ?: 0).toDouble())
            }
            TotalsRow("Participated", diff.previousTotals.participated.toDouble(), diff.currentTotals.participated.toDouble())
            TotalsRow("Total hours", diff.previousTotals.hours, diff.currentTotals.hours, hours = true)
            TotalsRow("Bible Studies", diff.previousTotals.bibleStudies.toDouble(), diff.currentTotals.bibleStudies.toDouble())
        }
        if (diff.added.isNotEmpty()) SectionCard("Added (" + diff.added.size + ")") { diff.added.forEach { Text(it.name + " - " + statusName(it.status)) } }
        if (diff.removed.isNotEmpty()) SectionCard("Removed (" + diff.removed.size + ")") { diff.removed.forEach { Text(it.name + " - " + statusName(it.status)) } }
        if (diff.reclassified.isNotEmpty()) SectionCard("Reclassified (" + diff.reclassified.size + ")") {
            diff.reclassified.forEach { Text(it.name + ": " + statusName(it.from) + " -> " + statusName(it.to)) }
        }
        if (diff.figureChanges.isNotEmpty()) SectionCard("Changed figures (" + diff.figureChanges.size + ")") {
            diff.figureChanges.forEach { c ->
                val parts = buildList {
                    if (c.hoursBefore != c.hoursAfter) add("hours " + (c.hoursBefore?.let(::fmtHours) ?: "-") + " -> " + (c.hoursAfter?.let(::fmtHours) ?: "-"))
                    if (c.studiesBefore != c.studiesAfter) add("Bible Studies " + (c.studiesBefore ?: "-") + " -> " + (c.studiesAfter ?: "-"))
                }
                Text(c.name + ": " + parts.joinToString("; "))
            }
        }
    }
}

@Composable
private fun TotalsRow(label: String, before: Double, after: Double, hours: Boolean = false) {
    fun show(v: Double) = if (hours) fmtHours(v) else v.toLong().toString()
    val delta = after - before
    val deltaText = when {
        delta == 0.0 -> "0"
        delta > 0 -> "+" + show(delta)
        else -> "-" + show(-delta)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(2.2f), style = MaterialTheme.typography.bodyMedium)
        Text(show(before), Modifier.weight(1f))
        Text(show(after), Modifier.weight(1f))
        Text(
            deltaText, Modifier.weight(1f), fontWeight = FontWeight.SemiBold,
            color = when {
                delta == 0.0 -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.primary
            },
        )
    }
}

/** The earlier send of the same month to compare [current] against; null for the first send. */
fun previousSendOf(current: CoReceivedReport, sendsOfThatMonth: List<CoReceivedReport>): CoReceivedReport? =
    SnapshotComparison.previousSend(current, sendsOfThatMonth)

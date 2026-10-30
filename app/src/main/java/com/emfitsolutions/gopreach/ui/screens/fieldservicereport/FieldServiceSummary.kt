package com.emfitsolutions.gopreach.ui.screens.fieldservicereport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.data.model.ReportStatus

/**
 * The mandatory end-of-report Summary of the Field Service Report. It is computed from exactly the rows being shown (after search and
 * filters), so the screen, the PDF/print and the Excel file always agree. First row = headings (Metric | Value).
 */
object FieldServiceSummary {
    fun ofSheets(sheets: List<FieldServiceReportSheet>): List<List<String>> {
        val records = sheets.sumOf { it.publisherCount }
        val reported = sheets.sumOf { it.participatedCount }
        val reports = sheets.sumOf { s -> s.reportColumns.sumOf { s.reportCount(it) } }
        val hours = sheets.sumOf { s -> s.hourColumns.sumOf { s.totalHours(it) } }
        val studies = sheets.sumOf { s -> s.reportColumns.sumOf { s.totalBibleStudies(it) } }
        return listOf(
            listOf("Metric", "Value"),
            listOf("Total Records", records.toString()),
            listOf("Reports Submitted", reported.toString()),
            listOf("Reports Pending", (records - reported).toString()),
            listOf("Total Reports", reports.toString()),
            listOf("Total Hours (pioneers)", formatHours(hours)),
            listOf("Total Bible Studies", studies.toString()),
        )
    }

    fun ofItems(items: List<FieldServiceReportListItem>): List<List<String>> {
        val reports = items.mapNotNull { it.report }
        val hours = reports.sumOf { it.hoursRendered ?: 0.0 }
        return listOf(
            listOf("Metric", "Value"),
            listOf("Total Records", items.size.toString()),
            listOf("Reports Submitted", reports.size.toString()),
            listOf("Reports Pending", (items.size - reports.size).toString()),
            listOf("Locked (Posted)", reports.count { it.status == ReportStatus.POSTED }.toString()),
            listOf("Total Hours (pioneers)", formatHours(hours)),
            listOf("Total Bible Studies", reports.sumOf { it.bibleStudiesCount }.toString()),
        )
    }

    /** Sheets of a two-range comparison carry a periodTag; each period gets its own summary, never a mix of the two. */
    fun grouped(sheets: List<FieldServiceReportSheet>): List<Pair<String?, List<List<String>>>> =
        sheets.groupBy { it.periodTag }.map { (tag, list) -> tag to ofSheets(list) }

    fun groupedHtml(sheets: List<FieldServiceReportSheet>): String =
        grouped(sheets).joinToString("") { (tag, rows) -> (tag?.let { "<h4 style=\"margin:10px 0 0 0\">$it</h4>" } ?: "") + html(rows) }

    /** The same summary as an HTML block for the PDF / print, placed after the last sheet. */
    fun html(rows: List<List<String>>): String = buildString {
        // the same markup as the website's Summary block; its look is the shared gp-report.css
        append("<section class=\"fs-sheet gp-sheet\"><h3>Summary</h3><dl>")
        rows.drop(1).forEach { r -> append("<div><dt>").append(ReportPrinterEscape.escape(r[0])).append("</dt><dd>").append(ReportPrinterEscape.escape(r[1])).append("</dd></div>") }
        append("</dl></section>")
    }

    private object ReportPrinterEscape {
        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}

/** On-screen Summary card shown after the last record of Table View and List View. */
@Composable
internal fun SummaryCard(rows: List<List<String>>, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Summary", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            rows.drop(1).forEach { r ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(r[0], style = MaterialTheme.typography.bodyMedium)
                    Text(r[1], style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

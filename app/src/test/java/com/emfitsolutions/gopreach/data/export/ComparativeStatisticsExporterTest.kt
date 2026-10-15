package com.emfitsolutions.gopreach.data.export

import com.emfitsolutions.gopreach.domain.ComparisonSummary
import com.emfitsolutions.gopreach.domain.ComparisonSummary.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What is printed must be the same comparison that is on screen: the same rows, the same summary, no calculation commentary. */
class ComparativeStatisticsExporterTest {
    private val summary = ComparisonSummary.build(
        "August 2026", "September 2026",
        listOf(Row("Total Publishers", 120.0, 126.0), Row("Unbaptized Publishers", 8.0, 7.0), Row("Bible Studies", 35.0, 35.0), Row("New", 0.0, 4.0), Row("Gone", null, 2.0)),
        unavailable = listOf("Transferees (incoming and outgoing)", "New Publishers"),
    )

    private fun data(graph: Boolean, congregation: String = "Solano <Tagalog> & Co") = ComparativeReportData(
        congregationName = congregation, periodA = "", periodB = "", generatedBy = "Circuit Overseer", generatedAt = "Oct 15, 2026", calculationMode = "",
        comparison = summary.table(), monthly = emptyList(), notes = emptyList(), title = "Field Service Report Comparison",
        periodLines = listOf("August 2026" to "1 of 1 month received", "September 2026" to "1 of 1 month received"),
        summaryParagraphs = summary.paragraphs, graph = if (graph) summary.rows else emptyList(), footnotes = listOfNotNull(summary.unavailableNote),
    )

    @Test
    fun theTableAndTheSummaryArePrintedFromTheSameRowsAsTheScreen() {
        val html = ComparativeStatisticsExporter.buildHtml(data(graph = false))
        summary.table().flatten().forEach { cell -> assertTrue("the table cell \"$cell\" is printed", html.contains(cell.replace("&", "&amp;"))) }
        summary.paragraphs.forEach { assertTrue("the summary line is printed: $it", html.contains(it.replace("&", "&amp;"))) }
        assertTrue(html.contains("Comparison Summary"))
        assertTrue(html.contains("Not recorded in the received reports: Transferees (incoming and outgoing), New Publishers."))
        assertTrue("the real period names are printed", html.contains("August 2026") && html.contains("September 2026"))
    }

    @Test
    fun missingDataIsPrintedAsADashNeverAsZero() {
        val html = ComparativeStatisticsExporter.buildHtml(data(graph = false))
        val goneRow = html.substringAfter("<td class=\"l\">Gone</td>").substringBefore("</tr>")
        assertTrue("the missing value is a dash: $goneRow", goneRow.contains("<td>-</td>"))
        assertFalse("and not a zero", goneRow.contains("<td>0</td>"))
    }

    @Test
    fun theGraphIsPrintedOnlyWhenTheGraphViewWasChosenAndCarriesTheSameValues() {
        val withGraph = ComparativeStatisticsExporter.buildHtml(data(graph = true))
        val without = ComparativeStatisticsExporter.buildHtml(data(graph = false))
        assertTrue(withGraph.contains("<h2>Graph</h2>"))
        assertFalse(without.contains("<h2>Graph</h2>"))
        summary.rows.forEach { assertTrue("${it.name} is in the graph", withGraph.contains(it.name)) }
        assertTrue("a missing value is labelled, not drawn as an empty bar", withGraph.contains("no data"))
    }

    @Test
    fun userTextIsEscapedAndNoCalculationCommentaryIsPrinted() {
        val html = ComparativeStatisticsExporter.buildHtml(data(graph = true))
        assertTrue(html.contains("Solano &lt;Tagalog&gt; &amp; Co"))
        assertFalse(html.contains("Solano <Tagalog>"))
        listOf("Calculation", "Percentage Change =", "Period A", "Period B", "formula").forEach { assertFalse("\"$it\" must not be printed", html.contains(it)) }
        assertEquals("no empty monthly section", false, html.contains("Monthly"))
    }
}

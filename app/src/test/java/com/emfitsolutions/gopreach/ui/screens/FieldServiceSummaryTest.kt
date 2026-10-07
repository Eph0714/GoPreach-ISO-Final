package com.emfitsolutions.gopreach.ui.screens

import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.FieldServiceReportRow
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.FieldServiceReportSheet
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.FieldServiceSummary
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.StatusColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldServiceSummaryTest {
    private fun sheet(vararg rows: FieldServiceReportRow) = FieldServiceReportSheet(
        groupName = "G", monthLabel = "Sep 2026", isRange = false, overseer = "", servant = "", assistant = "",
        reportColumns = listOf(StatusColumn.PUB, StatusColumn.RP), hourColumns = listOf(StatusColumn.RP), rows = rows.toList(),
    )

    private fun value(rows: List<List<String>>, metric: String) = rows.first { it[0] == metric }[1]

    @Test
    fun `the summary is computed from the rows shown`() {
        val all = sheet(
            FieldServiceReportRow(1, StatusColumn.PUB, "Ana", 1, null, 2, ""),
            FieldServiceReportRow(2, StatusColumn.RP, "Ben", 1, 40.5, 1, ""),
            FieldServiceReportRow(3, StatusColumn.PUB, "Cy", 0, null, null, ""),
        )
        val s = FieldServiceSummary.ofSheets(listOf(all))
        assertEquals("3", value(s, "Total Records"))
        assertEquals("2", value(s, "Reports Submitted"))
        assertEquals("1", value(s, "Reports Pending"))
        assertEquals("40.5", value(s, "Total Hours (pioneers)"))
        assertEquals("3", value(s, "Total Bible Studies"))

        // searching for "Ben" narrows the rows, and the summary follows
        val filtered = FieldServiceSummary.ofSheets(listOf(all.copy(rows = all.rows.filter { it.name == "Ben" })))
        assertEquals("1", value(filtered, "Total Records"))
        assertEquals("0", value(filtered, "Reports Pending"))
    }

    @Test
    fun `the printed summary lists every metric`() {
        val html = FieldServiceSummary.html(FieldServiceSummary.ofSheets(listOf(sheet())))
        assertTrue(html.contains("Summary") && html.contains("Total Records") && html.contains("Total Bible Studies"))
    }
}

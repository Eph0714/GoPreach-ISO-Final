package com.emfitsolutions.gopreach.ui.screens.circuit

import com.emfitsolutions.gopreach.data.model.CoAttendanceRow
import com.emfitsolutions.gopreach.data.model.CoAttendanceSnapshot
import com.emfitsolutions.gopreach.data.model.MeetingType
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.FieldServiceReportExporter
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.FieldServiceReportRow
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.FieldServiceReportSheet
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.StatusColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipInputStream

/** What Excel produces for the Received Reports: everything down to and including the Total (and the averages / summary), not only the detail rows. */
class ReceivedExportsTest {
    private fun sheetXml(bytes: ByteArray): String {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var e = zip.nextEntry
            while (e != null) { if (e.name == "xl/worksheets/sheet1.xml") return zip.readBytes().decodeToString(); e = zip.nextEntry }
        }
        error("no worksheet")
    }

    @Test
    fun theFieldServiceExcelEndsWithTheTotalRowAndTheSummary() {
        val cols = listOf(StatusColumn.UP, StatusColumn.PUB, StatusColumn.AP, StatusColumn.RP)
        val rows = listOf(
            FieldServiceReportRow(1, StatusColumn.PUB, "Ana One", 1, null, 2, ""),
            FieldServiceReportRow(2, StatusColumn.RP, "Ben Two", 1, 50.0, 3, "ok"),
            FieldServiceReportRow(3, StatusColumn.AP, "Cora Three", 1, 30.0, 1, ""),
        )
        val sheet = FieldServiceReportSheet(
            groupName = "Demo Congregation", monthLabel = "March 2025", isRange = false, overseer = "", servant = "", assistant = "",
            reportColumns = cols, hourColumns = cols.filter { it.isPioneer }, rows = rows,
        )
        val xml = sheetXml(FieldServiceReportExporter.buildXlsx(File("src/main/assets/fsr/styles.xml").readBytes(), File("src/main/assets/fsr/theme1.xml").readBytes(), sheet))
        assertTrue("every publisher row", xml.contains("Ana One") && xml.contains("Ben Two") && xml.contains("Cora Three"))
        assertTrue("the Total row", xml.contains("Total:"))
        assertTrue("the total of the hours (50 + 30) is in it", xml.contains(">80<"))
        assertTrue("the Total row comes after the last publisher", xml.indexOf("Total:") > xml.indexOf("Cora Three"))
    }

    @Test
    fun theAttendanceExcelEndsWithTheMonthlyAveragesAndTheSummary() {
        val day = 86_400_000L
        val report = CoAttendanceSnapshot(
            congregationName = "Demo Congregation", periodMonth = 1_740_787_200_000, version = 1, submittedAt = 1_741_000_000_000,
            rows = listOf(
                CoAttendanceRow(MeetingType.MIDWEEK, 1_741_000_000_000, listOf(80, 82, 81), 81.0, 81.0),
                CoAttendanceRow(MeetingType.MIDWEEK, 1_741_000_000_000 + 7 * day, listOf(90, 91, 90), 90.33, 90.0),
                CoAttendanceRow(MeetingType.WEEKEND, 1_741_000_000_000 + 3 * day, listOf(100, 101), 100.5, 101.0),
            ),
        )
        val data = attendanceExportData(report)
        assertEquals("three meetings, then one average row per meeting type", 1 + 3 + 2, data.table.size)
        val avg = data.table.takeLast(2)
        assertEquals(listOf("Monthly average", "Midweek"), avg[0].take(2)); assertEquals("85.5", avg[0].last())
        assertEquals(listOf("Monthly average", "Weekend"), avg[1].take(2)); assertEquals("101", avg[1].last())
        assertTrue(data.summary.any { it[0] == "Midweek average" && it[1] == "85.5" })
        assertEquals("only recorded meeting types get an average row", 1 + 1 + 1, attendanceExportData(report.copy(rows = report.rows.filter { it.meetingType == MeetingType.WEEKEND }.take(1))).table.size)
    }
}

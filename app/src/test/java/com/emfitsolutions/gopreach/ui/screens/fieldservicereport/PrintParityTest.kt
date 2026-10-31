package com.emfitsolutions.gopreach.ui.screens.fieldservicereport

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The printed Field Service Record is ONE design shared with the website: assets/print/gp-report.css (the look) and assets/print/gp-fit.js (the fit), byte for byte the same files as the
 * website's public/css/gp-report.css and public/js/gp-fit.js. The report's HTML carries no style of its own and uses the website's class names.
 *
 * If this test fails after you changed one of the two files: copy it to the website too, then put the new checksum into BOTH projects (this test and the website's PrintParityTest).
 * Never restyle the printed report in Kotlin: that is exactly how the app and the website drifted apart before.
 */
class PrintParityTest {
    private fun sha(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readText().replace("\r\n", "\n").toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun theSharedStylesheetAndFitScriptAreTheOnesTheWebsiteHas() {
        assertEquals("47c5c97ce1ffd491dae28a09630872a58b03713a2885653a2407c284915b7c66", sha(File("src/main/assets/print/gp-report.css")))
        assertEquals("6896e3635a934d1dc42ec32a02c71f669103fb6009d888fd2d9f38a6f0c2f99b", sha(File("src/main/assets/print/gp-fit.js")))
    }

    @Test
    fun theFitKeepsAWideTableInsideThePaper() {
        val fit = File("src/main/assets/print/gp-fit.js").readText()
        assertTrue("a shrunk table is measured again and shrunk further until it really fits", fit.contains("function settle(") && fit.contains("s = settle(t, avail, s)"))
        assertFalse("a percentage width is scaled by the zoom again by the browser: the width stays in px", fit.contains("'calc(100% / '"))
    }

    private fun sheet(hours: Boolean) = FieldServiceReportSheet(
        groupName = "FS Group 5", monthLabel = "October 2026", isRange = false, overseer = "Henry Canales", servant = "None", assistant = "Ephraim Fernandez",
        reportColumns = listOf(StatusColumn.PUB, StatusColumn.RP),
        hourColumns = if (hours) listOf(StatusColumn.RP) else emptyList(),
        rows = listOf(FieldServiceReportRow(1, StatusColumn.RP, "Canales, Aurora", 1, 62.0, 6, "A long remark that has to wrap rather than push the table off the page"), FieldServiceReportRow(2, StatusColumn.PUB, "Butac, Japhet", 1, null, 1, "")),
    )

    @Test
    fun theHtmlUsesTheWebsitesClassNamesAndStylesNothingItself() {
        for (hours in listOf(true, false)) {
            val html = FieldServiceReportExporter.buildHtml(listOf(sheet(hours)))
            for (token in listOf("fs-sheet fs-print gp-sheet", "fs-banner", "fs-meta", "class=\"gp-fit\"", "fs-h-rep", "fs-h-bib", "fs-total", "text-left whitespace-nowrap font-semibold", "<section class=\"fs-sheet gp-sheet\"><h3>Summary</h3><dl>")) {
                assertTrue("the printed report has no '$token'", html.contains(token))
            }
            assertEquals("the hours block is there only when there are hour columns", hours, html.contains("fs-h-hrs"))
            assertFalse("the report carries no style of its own", html.contains("<style") || html.contains("style=\""))
        }
    }
}

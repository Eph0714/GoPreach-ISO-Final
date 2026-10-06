package com.emfitsolutions.gopreach.ui.screens.fieldservicereport

import org.junit.Test
import java.io.File

/** Generates the reference workbook's data through the exporter, so the output can be compared with the sample. */
class FieldServiceReportExporterTest {
    private fun row(no: Int, s: StatusColumn, name: String, reported: Boolean = true, hours: Double? = null, bs: Int? = null, remarks: String = "") =
        FieldServiceReportRow(no, s, name, if (reported) 1 else 0, hours, bs, remarks)

    @Test
    fun writeSampleReplica() {
        val rows = listOf(
            row(1, StatusColumn.RP, "Canales, Aurora", hours = 62.0, bs = 6),
            row(2, StatusColumn.RP, "Canales, Henry", reported = false, hours = 47.0, bs = 2),
            row(3, StatusColumn.AP, "Fernandez, Ephraim", hours = 24.5, bs = 1, remarks = "Continues AP til December"),
            row(4, StatusColumn.RP, "Fernandez, Ferlyn Mae", hours = 50.0, bs = 1),
            row(5, StatusColumn.RP, "Bautista, Delaila", hours = 62.0, bs = 5),
            row(6, StatusColumn.PUB, "Butac, Japhet", bs = 1),
            row(7, StatusColumn.PUB, "Butac, Fallyn Grace", bs = 3),
            row(8, StatusColumn.PUB, "Butac, Chloe", bs = 0),
            row(9, StatusColumn.PUB, "David, Genalyn", bs = 4),
            row(10, StatusColumn.UP, "David, Nathalia", bs = 0),
            row(11, StatusColumn.RP, "Francisco, Elvie", hours = 59.0, bs = 4),
            row(12, StatusColumn.PUB, "Francisco, Crisnhie", bs = 0),
            row(13, StatusColumn.PUB, "Francisco, Desiree", bs = 1),
            row(14, StatusColumn.PUB, "Yacapin, Mercedita", bs = 2),
        )
        val cols = listOf(StatusColumn.UP, StatusColumn.PUB, StatusColumn.AP, StatusColumn.RP)
        val sheet = FieldServiceReportSheet(
            groupName = "FS Group 5", monthLabel = "October 2026", isRange = false,
            overseer = "Henry Canales", servant = "None", assistant = "Ephraim Fernandez",
            reportColumns = cols, hourColumns = cols.filter { it.isPioneer }, rows = rows,
        )
        val styles = File("src/main/assets/fsr/styles.xml").readBytes()
        val theme = File("src/main/assets/fsr/theme1.xml").readBytes()
        val dir = File("build/fsr-test").apply { mkdirs() }
        File(dir, "replica.xlsx").writeBytes(FieldServiceReportExporter.buildXlsx(styles, theme, sheet))
        File(dir, "replica.html").writeText(FieldServiceReportExporter.buildHtml(sheet))

        // Extra statuses become extra columns in every block.
        val extra = sheet.copy(
            reportColumns = StatusColumn.entries.toList(),
            hourColumns = StatusColumn.entries.filter { it.isPioneer },
            rows = rows + row(15, StatusColumn.SP, "Special, Sam", hours = 100.0, bs = 7) + row(16, StatusColumn.INACTIVE, "Idle, Ian", reported = false),
        )
        File(dir, "extra-statuses.xlsx").writeBytes(FieldServiceReportExporter.buildXlsx(styles, theme, extra))
    }
}

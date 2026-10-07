package com.emfitsolutions.gopreach.ui.screens.fieldservicereport

import com.emfitsolutions.gopreach.data.print.escapeHtml
import android.content.Context
import com.emfitsolutions.gopreach.data.print.OrientationMode
import com.emfitsolutions.gopreach.data.print.PrintOptions
import android.content.Intent
import androidx.core.content.FileProvider
import com.emfitsolutions.gopreach.data.print.ReportPrinter
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports a [FieldServiceReportSheet] in the exact layout of the reference
 * "FIELD SERVICE REPORT SAMPLE.xlsx": group name, month, the three group
 * roles, then a block of columns per measure — No. of Reports (UP, Pub, AP,
 * RP...), Hours (AP, RP...) and Bible Studies (UP, Pub, AP, RP...) — one line
 * per publisher with a 1 under their status, their hours under AP/RP and their
 * Bible Studies under their own status, a Remarks column, and a yellow Total
 * row. The Excel file reuses the sample's own style sheet and theme (bundled
 * under assets/fsr), so fills, borders and fonts match it exactly; any extra
 * status (SP, Irr, Inactive, Reproof) is just one more column in each block.
 */
object FieldServiceReportExporter {

    // Style ids from the sample's styles.xml (+ 27, a Hours "middle" header the sample never needed).
    private const val S_BORDER = 1
    private const val S_HEAD_BOLD = 2
    private const val S_HEAD_BOLD_LEFT = 3
    private const val S_NAME = 4
    private const val S_REMARKS_GROUP_HEAD = 6
    private const val S_REPORTS_HEAD = 7
    private const val S_BIBLE_HEAD = 8
    private const val S_CELL = 9
    private const val S_HOURS_HEAD = 10
    private const val S_REMARKS_HEAD = 11
    private const val S_NAME_LAST = 12
    private const val S_CELL_LAST = 13
    private const val S_TOTAL = 14
    private const val S_TOTAL_RIGHT = 15
    private const val S_TOTAL_LABEL = 16
    private const val S_BIBLE_FIRST = 17
    private const val S_BIBLE_MID = 18
    private const val S_BIBLE_LAST = 19
    private const val S_HOURS_FIRST = 20
    private const val S_HOURS_LAST = 21
    private const val S_REPORTS_FIRST = 22
    private const val S_REPORTS_MID = 23
    private const val S_REPORTS_LAST = 24
    private const val S_TITLE = 25
    private const val S_TITLE_FILL = 26
    private const val S_HOURS_MID = 27

    private fun col(n: Int): String {
        var x = n
        val sb = StringBuilder()
        while (x > 0) {
            val r = (x - 1) % 26
            sb.insert(0, ('A' + r))
            x = (x - 1) / 26
        }
        return sb.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun num(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

    private class Cells {
        val sb = StringBuilder()
        fun str(ref: String, text: String, s: Int = 0) {
            sb.append("<c r=\"").append(ref).append('"')
            if (s != 0) sb.append(" s=\"").append(s).append('"')
            sb.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(esc(text)).append("</t></is></c>")
        }
        fun number(ref: String, v: String, s: Int) {
            sb.append("<c r=\"").append(ref).append("\" s=\"").append(s).append("\"><v>").append(v).append("</v></c>")
        }
        fun empty(ref: String, s: Int) {
            sb.append("<c r=\"").append(ref).append("\" s=\"").append(s).append("\"/>")
        }
        fun formula(ref: String, f: String, v: String, s: Int) {
            sb.append("<c r=\"").append(ref).append("\" s=\"").append(s).append("\"><f>").append(f).append("</f><v>").append(v).append("</v></c>")
        }
    }

    /** First / middle / last style for a header block of [n] columns. */
    private fun blockStyle(i: Int, n: Int, first: Int, mid: Int, last: Int) = when {
        i == 0 -> first
        i == n - 1 -> last
        else -> mid
    }

    fun buildXlsx(context: Context, sheets: List<FieldServiceReportSheet>): ByteArray =
        buildXlsx(
            styles = context.assets.open("fsr/styles.xml").use { it.readBytes() },
            theme = context.assets.open("fsr/theme1.xml").use { it.readBytes() },
            sheets = sheets,
        )

    /** [styles] and [theme] are the sample workbook's own parts (bundled under assets/fsr). */
    private fun worksheetXml(sheet: FieldServiceReportSheet, summary: List<List<String>> = emptyList()): String {
        val rc = sheet.reportColumns
        val hc = sheet.hourColumns
        val r = rc.size
        val h = hc.size
        val reportsStart = 5
        val hoursStart = reportsStart + r
        val bibleStart = hoursStart + h
        val remarksCol = bibleStart + r
        val firstData = 9
        val lastData = firstData + sheet.rows.size - 1
        val totalRow = lastData + 1

        val rows = StringBuilder()

        // ---- rows 1-5: title and group roles --------------------------------
        run {
            val c = Cells()
            c.str("B1", sheet.groupName, S_TITLE); c.empty("C1", S_TITLE_FILL)
            rows.append("<row r=\"1\">").append(c.sb).append("</row>")
        }
        run { val c = Cells(); c.str("B2", sheet.titleLine); rows.append("<row r=\"2\">").append(c.sb).append("</row>") }
        run { val c = Cells(); c.str("B3", sheet.officerRows[0].first); c.str("D3", sheet.officerRows[0].second); rows.append("<row r=\"3\">").append(c.sb).append("</row>") }
        run { val c = Cells(); c.str("B4", sheet.officerRows[1].first); c.str("D4", sheet.officerRows[1].second); rows.append("<row r=\"4\">").append(c.sb).append("</row>") }
        run { val c = Cells(); c.str("B5", sheet.officerRows[2].first); c.str("D5", sheet.officerRows[2].second); rows.append("<row r=\"5\">").append(c.sb).append("</row>") }
        rows.append("<row r=\"6\" ht=\"15.75\" thickBot=\"1\"/>")

        // ---- row 7: block headings ------------------------------------------
        run {
            val c = Cells()
            for (i in 0 until r) {
                val ref = col(reportsStart + i) + "7"
                val s = blockStyle(i, r, S_REPORTS_FIRST, S_REPORTS_MID, S_REPORTS_LAST)
                if (i == 0) c.str(ref, "No. of Reports", s) else c.empty(ref, s)
            }
            for (i in 0 until h) {
                val ref = col(hoursStart + i) + "7"
                val s = blockStyle(i, h, S_HOURS_FIRST, S_HOURS_MID, S_HOURS_LAST)
                if (i == 0) c.str(ref, "Hours", s) else c.empty(ref, s)
            }
            for (i in 0 until r) {
                val ref = col(bibleStart + i) + "7"
                val s = blockStyle(i, r, S_BIBLE_FIRST, S_BIBLE_MID, S_BIBLE_LAST)
                if (i == 0) c.str(ref, "Bible Studies", s) else c.empty(ref, s)
            }
            c.empty(col(remarksCol) + "7", S_REMARKS_GROUP_HEAD)
            rows.append("<row r=\"7\" ht=\"15.75\" thickTop=\"1\">").append(c.sb).append("</row>")
        }

        // ---- row 8: column headings -----------------------------------------
        run {
            val c = Cells()
            c.empty("B8", S_BORDER)
            c.str("C8", "Status", S_HEAD_BOLD)
            c.str("D8", "Publisher's Name", S_HEAD_BOLD_LEFT)
            rc.forEachIndexed { i, s -> c.str(col(reportsStart + i) + "8", s.label, S_REPORTS_HEAD) }
            hc.forEachIndexed { i, s -> c.str(col(hoursStart + i) + "8", s.label, S_HOURS_HEAD) }
            rc.forEachIndexed { i, s -> c.str(col(bibleStart + i) + "8", s.label, S_BIBLE_HEAD) }
            c.str(col(remarksCol) + "8", "Remarks", S_REMARKS_HEAD)
            rows.append("<row r=\"8\">").append(c.sb).append("</row>")
        }

        // ---- publisher rows ---------------------------------------------------
        sheet.rows.forEachIndexed { index, line ->
            val n = firstData + index
            val last = index == sheet.rows.lastIndex
            val cell = if (last) S_CELL_LAST else S_CELL
            val c = Cells()
            c.number("B$n", line.number.toString(), S_BORDER)
            c.str("C$n", line.status.label, S_BORDER)
            c.str("D$n", line.name, if (last) S_NAME_LAST else S_NAME)
            rc.forEachIndexed { i, s ->
                val ref = col(reportsStart + i) + n
                if (line.reported && line.status == s) c.number(ref, line.reportsCount.toString(), cell) else c.empty(ref, cell)
            }
            hc.forEachIndexed { i, s ->
                val ref = col(hoursStart + i) + n
                val v = line.hours
                if (line.status == s && v != null) c.number(ref, num(v), cell) else c.empty(ref, cell)
            }
            rc.forEachIndexed { i, s ->
                val ref = col(bibleStart + i) + n
                val v = line.bibleStudies
                if (line.status == s && v != null) c.number(ref, v.toString(), cell) else c.empty(ref, cell)
            }
            if (line.remarks.isNotBlank()) c.str(col(remarksCol) + n, line.remarks, cell) else c.empty(col(remarksCol) + n, cell)
            rows.append("<row r=\"").append(n).append("\"").append(if (last) " ht=\"15.75\" thickBot=\"1\"" else "").append(">").append(c.sb).append("</row>")
        }

        // ---- totals (SUM formulas, like the sample) ------------------------------
        run {
            val c = Cells()
            c.str("D$totalRow", "Total:", S_TOTAL_LABEL)
            rc.forEachIndexed { i, s ->
                val letter = col(reportsStart + i)
                c.formula("$letter$totalRow", "SUM($letter$firstData:$letter$lastData)", sheet.reportCount(s).toString(), S_TOTAL)
            }
            hc.forEachIndexed { i, s ->
                val letter = col(hoursStart + i)
                c.formula("$letter$totalRow", "SUM($letter$firstData:$letter$lastData)", num(sheet.totalHours(s)), S_TOTAL)
            }
            rc.forEachIndexed { i, s ->
                val letter = col(bibleStart + i)
                c.formula("$letter$totalRow", "SUM($letter$firstData:$letter$lastData)", sheet.totalBibleStudies(s).toString(), S_TOTAL)
            }
            c.empty(col(remarksCol) + totalRow, S_TOTAL_RIGHT)
            rows.append("<row r=\"").append(totalRow).append("\" ht=\"16.5\" thickTop=\"1\" thickBot=\"1\">").append(c.sb).append("</row>")
        }

        var lastRow = totalRow
        if (summary.isNotEmpty()) {
            var n = totalRow + 2
            run { val c = Cells(); c.str("D$n", "Summary", S_TOTAL_LABEL); rows.append("<row r=\"").append(n).append("\">").append(c.sb).append("</row>") }
            summary.drop(1).forEach { r ->
                n++
                val c = Cells(); c.str("D$n", r[0]); c.str("E$n", r[1])
                rows.append("<row r=\"").append(n).append("\">").append(c.sb).append("</row>")
            }
            lastRow = n
        }

        val merges = buildList {
            add("B1:C1")
            if (r > 1) add(col(reportsStart) + "7:" + col(reportsStart + r - 1) + "7")
            if (h > 1) add(col(hoursStart) + "7:" + col(hoursStart + h - 1) + "7")
            if (r > 1) add(col(bibleStart) + "7:" + col(bibleStart + r - 1) + "7")
        }

        val sheetXml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
            append("<dimension ref=\"B1:").append(col(remarksCol)).append(lastRow).append("\"/>")
            append("<sheetViews><sheetView tabSelected=\"1\" workbookViewId=\"0\"/></sheetViews>")
            append("<sheetFormatPr defaultRowHeight=\"15\"/>")
            append("<cols><col min=\"4\" max=\"4\" width=\"30.140625\" customWidth=\"1\"/>")
            append("<col min=\"").append(remarksCol).append("\" max=\"").append(remarksCol).append("\" width=\"26.85546875\" customWidth=\"1\"/></cols>")
            append("<sheetData>").append(rows).append("</sheetData>")
            append("<mergeCells count=\"").append(merges.size).append("\">")
            merges.forEach { append("<mergeCell ref=\"").append(it).append("\"/>") }
            append("</mergeCells>")
            append("<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>")
            append("<pageSetup paperSize=\"9\" orientation=\"portrait\"/>")
            append("</worksheet>")
        }

        return sheetXml
    }

    /** One workbook with a worksheet per group (a single group = a single sheet, like the sample). */
    fun buildXlsx(styles: ByteArray, theme: ByteArray, sheets: List<FieldServiceReportSheet>): ByteArray {
        // Excel sheet names: max 31 chars, none of \ / ? * : [ ], unique.
        val used = mutableSetOf<String>()
        val names = sheets.mapIndexed { i, s ->
            var base = s.groupName.replace(Regex("[\\\\/?*:\\[\\]]"), " ").trim().take(31).ifBlank { "Sheet" + (i + 1) }
            var candidate = base
            var n = 2
            while (!used.add(candidate.lowercase())) candidate = base.take(28) + " " + n++
            candidate
        }
        val sheetOverrides = sheets.indices.joinToString("") {
            "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
        }
        val contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            sheetOverrides +
            "<Override PartName=\"/xl/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            "</Types>"
        val rootRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>"
        val sheetEntries = names.mapIndexed { i, name ->
            "<sheet name=\"" + esc(name) + "\" sheetId=\"" + (i + 1) + "\" r:id=\"rId" + (i + 1) + "\"/>"
        }.joinToString("")
        val workbook = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<sheets>" + sheetEntries + "</sheets><calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>"
        val sheetRels = sheets.indices.joinToString("") {
            "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>"
        }
        val workbookRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            sheetRels +
            "<Relationship Id=\"rId${sheets.size + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"theme/theme1.xml\"/>" +
            "<Relationship Id=\"rId${sheets.size + 2}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
            "</Relationships>"

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypes.toByteArray())
            put("_rels/.rels", rootRels.toByteArray())
            put("xl/workbook.xml", workbook.toByteArray())
            put("xl/_rels/workbook.xml.rels", workbookRels.toByteArray())
            sheets.forEachIndexed { i, s -> put("xl/worksheets/sheet${i + 1}.xml", worksheetXml(s, if (i == sheets.indexOfLast { it.periodTag == s.periodTag }) FieldServiceSummary.ofSheets(sheets.filter { it.periodTag == s.periodTag }) else emptyList()).toByteArray()) }
            put("xl/styles.xml", styles)
            put("xl/theme/theme1.xml", theme)
        }
        return out.toByteArray()
    }

    fun buildXlsx(styles: ByteArray, theme: ByteArray, sheet: FieldServiceReportSheet): ByteArray = buildXlsx(styles, theme, listOf(sheet))

    private fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifBlank { "report" }

    private fun fileLabel(sheets: List<FieldServiceReportSheet>): String =
        (if (sheets.size == 1) safeName(sheets[0].groupName) else "All_FS_Groups") + "_" + safeName(sheets.firstOrNull()?.monthLabel.orEmpty())

    /** Writes the .xlsx (one sheet per group) and hands it to the system share sheet (same FileProvider/cache
     * folder the map exports use), so it can be saved to Drive/Files, emailed, or opened in Excel. */
    fun shareExcel(context: Context, sheets: List<FieldServiceReportSheet>) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "FieldServiceReport_" + fileLabel(sheets) + ".xlsx")
        file.writeBytes(buildXlsx(context, sheets))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Field Service Report"))
    }

    /** Print / Save as PDF through the system print dialog, in the same layout (a page per group). */
    fun printPdf(context: Context, sheets: List<FieldServiceReportSheet>, graphHtml: String? = null, footerHtml: String? = null) =
        ReportPrinter.printHtml(
            context,
            "Field Service Report - " + (if (sheets.size == 1) sheets[0].groupName else "All FS Groups") + " - " + sheets.firstOrNull()?.monthLabel.orEmpty(),
            buildHtml(sheets, graphHtml, footerHtml),
            PrintOptions(OrientationMode.LANDSCAPE),
        )

    // Colors approximating the sample's theme fills (orange / blue / green tints) and the yellow total row.
    private const val FILL_REPORTS = "#FBE3D6"
    private const val FILL_HOURS = "#C1D3EA"
    private const val FILL_BIBLE = "#CBEBCD"
    private const val FILL_TOTAL = "#FFFF00"

    fun buildHtml(sheet: FieldServiceReportSheet): String = buildHtml(listOf(sheet))

    fun buildHtml(sheets: List<FieldServiceReportSheet>, graphHtml: String? = null, footerHtml: String? = null): String = buildString {
        append("<html><head><meta charset=\"utf-8\"><style>")
        append("@page{size:landscape;margin:12mm} body{font-family:sans-serif;font-size:12px;} ")
        append("table{border-collapse:collapse;} td,th{border:1px solid #000;padding:3px 6px;text-align:center;} ")
        append("td.l,th.l{text-align:left;} th{font-weight:bold;} .noborder td{border:none;text-align:left;padding:1px 4px;} ")
        append("</style></head><body>")
        // Comparative Graph Report (two-period mode) gets its own page ahead of the sheets.
        graphHtml?.let { append(it) }
        sheets.forEachIndexed { i, sheet ->
            append("<div style=\"").append(if (i < sheets.lastIndex) "page-break-after:always" else "").append("\">")
            appendSheet(sheet)
            append("</div>")
        }
        if (sheets.isNotEmpty()) append(FieldServiceSummary.groupedHtml(sheets))
        footerHtml?.let { append(it) }
        append("</body></html>")
    }

    private fun StringBuilder.appendSheet(sheet: FieldServiceReportSheet) {
        val rc = sheet.reportColumns
        val hc = sheet.hourColumns
        fun e(s: String) = escapeHtml(s)
        append("<table class=\"noborder\"><tr><td><b>").append(e(sheet.groupName)).append("</b></td></tr>")
        append("<tr><td>").append(e(sheet.titleLine)).append("</td></tr>")
        append("<tr><td><b>").append(e(sheet.countLine)).append("</b></td></tr>")
        sheet.officerRows.forEach { (label, value) -> if (label.isNotBlank()) append("<tr><td>").append(e(label)).append(" ").append(e(value)).append("</td></tr>") }
        append("</table><br>")
        append("<table><tr><th rowspan=\"2\"></th><th rowspan=\"2\">Status</th><th rowspan=\"2\" class=\"l\">Publisher's Name</th>")
        append("<th colspan=\"${rc.size}\" style=\"background:$FILL_REPORTS\">No. of Reports</th>")
        append("<th colspan=\"${hc.size}\" style=\"background:$FILL_HOURS\">Hours</th>")
        append("<th colspan=\"${rc.size}\" style=\"background:$FILL_BIBLE\">Bible Studies</th><th rowspan=\"2\">Remarks</th></tr><tr>")
        rc.forEach { append("<th style=\"background:$FILL_REPORTS\">").append(e(it.label)).append("</th>") }
        hc.forEach { append("<th style=\"background:$FILL_HOURS\">").append(e(it.label)).append("</th>") }
        rc.forEach { append("<th style=\"background:$FILL_BIBLE\">").append(e(it.label)).append("</th>") }
        append("</tr>")
        sheet.rows.forEach { line ->
            append("<tr><td>").append(line.number).append("</td><td>").append(e(line.status.label)).append("</td><td class=\"l\">").append(e(line.name)).append("</td>")
            rc.forEach { s -> append("<td>").append(if (line.reported && line.status == s) line.reportsCount.toString() else "").append("</td>") }
            hc.forEach { s -> append("<td>").append(if (line.status == s) line.hours?.let { formatHours(it) }.orEmpty() else "").append("</td>") }
            rc.forEach { s -> append("<td>").append(if (line.status == s) line.bibleStudies?.toString().orEmpty() else "").append("</td>") }
            append("<td class=\"l\">").append(e(line.remarks)).append("</td></tr>")
        }
        append("<tr style=\"background:$FILL_TOTAL;font-weight:bold\"><td></td><td></td><td class=\"l\">Total:</td>")
        rc.forEach { append("<td>").append(sheet.reportCount(it)).append("</td>") }
        hc.forEach { append("<td>").append(formatHours(sheet.totalHours(it))).append("</td>") }
        rc.forEach { append("<td>").append(sheet.totalBibleStudies(it)).append("</td>") }
        append("<td></td></tr></table>")
    }
}

package com.emfitsolutions.gopreach.data.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.emfitsolutions.gopreach.data.model.displayName
import com.emfitsolutions.gopreach.data.print.OrientationMode
import com.emfitsolutions.gopreach.data.print.PrintOptions
import com.emfitsolutions.gopreach.data.print.ReportPrinter
import com.emfitsolutions.gopreach.ui.screens.circuit.PUBLISHER_EXPORT_COLUMNS
import com.emfitsolutions.gopreach.ui.screens.circuit.PublisherReportSection
import com.emfitsolutions.gopreach.ui.screens.circuit.PublisherTotals
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Print / PDF and Excel output of the Circuit Overseer's Publisher records. Both take the same sections the screen is
 * showing (already filtered), so a printout always agrees with the list. Each congregation gets its own heading —
 * Congregation, Coordinator, Congregation Code — its publishers, and its totals (total publishers and one count per
 * category); when more than one congregation is included, a Circuit Summary follows.
 */
object CircuitPublisherExporter {

    private fun today() = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date())

    private fun grand(sections: List<PublisherReportSection>): PublisherTotals {
        val categories = sections.firstOrNull()?.totals?.byCategory?.map { it.first }.orEmpty()
        return PublisherTotals(
            total = sections.sumOf { it.totals.total },
            byCategory = categories.map { c -> c to sections.sumOf { s -> s.totals.byCategory.firstOrNull { it.first == c }?.second ?: 0 } },
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Print / Save as PDF (system print dialog)

    fun print(context: Context, circuitLabel: String, filterText: String, sections: List<PublisherReportSection>) {
        fun e(s: String) = ReportPrinter.escapeHtml(s)
        val columns = PUBLISHER_EXPORT_COLUMNS
        val html = buildString {
            append("<html><head><meta charset=\"utf-8\"><style>")
            append("@page{size:landscape;margin:10mm} body{font-family:sans-serif;font-size:9px;} h2{margin:0 0 4px 0;font-size:15px;}")
            append(".head div{font-size:11px;margin:1px 0} .meta{color:#444;margin:4px 0 8px 0}")
            append("table{border-collapse:collapse;width:100%} th,td{border:1px solid #000;padding:2px 4px;text-align:left;vertical-align:top}")
            append("thead{display:table-header-group} tr{page-break-inside:avoid} th{background:#e6e6e6}")
            append(".tot{margin-top:8px;font-size:11px} .tot td,.tot th{font-size:11px} .sec{page-break-after:always} .sec:last-child{page-break-after:auto}")
            append("</style></head><body>")
            sections.forEach { s ->
                append("<div class=\"sec\"><h2>Publisher Records</h2><div class=\"head\">")
                append("<div><b>Congregation:</b> ").append(e(s.heading.name)).append("</div>")
                append("<div><b>Coordinator:</b> ").append(e(s.heading.coordinator)).append("</div>")
                append("<div><b>Congregation Code:</b> ").append(e(s.heading.code.ifBlank { "—" })).append("</div></div>")
                append("<div class=\"meta\">").append(e(circuitLabel)).append(" · ").append(e(today()))
                if (filterText.isNotBlank()) append(" · ").append(e(filterText))
                append("</div><table><thead><tr>")
                columns.forEach { append("<th>").append(e(it.title)).append("</th>") }
                append("</tr></thead><tbody>")
                s.rows.forEachIndexed { i, r ->
                    append("<tr>")
                    columns.forEach { append("<td>").append(e(it.value(r, i))).append("</td>") }
                    append("</tr>")
                }
                append("</tbody></table>")
                appendTotals(s.totals, "Totals")
                append("</div>")
            }
            if (sections.size > 1) {
                append("<div class=\"sec\"><h2>Circuit Summary</h2><div class=\"meta\">").append(e(circuitLabel)).append(" · ").append(e(today())).append("</div>")
                append("<table class=\"tot\"><thead><tr><th>Congregation</th><th>Coordinator</th><th>Code</th><th>Total Publishers</th>")
                sections.first().totals.byCategory.forEach { append("<th>").append(e(it.first.displayName)).append("</th>") }
                append("</tr></thead><tbody>")
                sections.forEach { s ->
                    append("<tr><td>").append(e(s.heading.name)).append("</td><td>").append(e(s.heading.coordinator)).append("</td><td>").append(e(s.heading.code))
                        .append("</td><td>").append(s.totals.total).append("</td>")
                    s.totals.byCategory.forEach { append("<td>").append(it.second).append("</td>") }
                    append("</tr>")
                }
                val g = grand(sections)
                append("<tr><th colspan=\"3\">TOTAL</th><th>").append(g.total).append("</th>")
                g.byCategory.forEach { append("<th>").append(it.second).append("</th>") }
                append("</tr></tbody></table></div>")
            }
            // The mandatory end-of-report Summary: the totals of everything printed above, always last.
            if (sections.isNotEmpty()) append("<div class=\"sec\">").also { appendTotals(grand(sections), "Summary") }.append("</div>")
            append("</body></html>")
        }
        ReportPrinter.printHtml(
            context,
            "Publisher Records - " + (sections.singleOrNull()?.heading?.name ?: circuitLabel),
            html,
            PrintOptions(OrientationMode.LANDSCAPE),
        )
    }

    private fun StringBuilder.appendTotals(t: PublisherTotals, title: String) {
        append("<table class=\"tot\" style=\"width:auto\"><thead><tr><th colspan=\"2\">").append(title).append("</th></tr></thead><tbody>")
        append("<tr><td><b>Total Publishers</b></td><td><b>").append(t.total).append("</b></td></tr>")
        t.byCategory.forEach { (c, n) -> append("<tr><td>").append(ReportPrinter.escapeHtml(c.displayName)).append("</td><td>").append(n).append("</td></tr>") }
        append("</tbody></table>")
    }

    // ---------------------------------------------------------------------------------------------
    // Excel (.xlsx, hand-built like the other exporters in this app: inline strings, no extra library)

    fun shareExcel(context: Context, circuitLabel: String, filterText: String, sections: List<PublisherReportSection>) {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun col(i: Int): String { var n = i; var s = ""; do { s = ('A' + n % 26) + s; n = n / 26 - 1 } while (n >= 0); return s }
        val columns = PUBLISHER_EXPORT_COLUMNS
        val rowsXml = StringBuilder()
        var r = 0
        fun cell(c: Int, text: String, style: Int = 0) = "<c r=\"${col(c)}${r}\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(text)}</t></is></c>"
        fun numCell(c: Int, v: Int, style: Int = 0) = "<c r=\"${col(c)}${r}\" s=\"$style\"><v>$v</v></c>"
        fun row(vararg cells: String) { rowsXml.append("<row r=\"$r\">").append(cells.joinToString("")).append("</row>") }
        fun next() { r++ }

        next(); row(cell(0, "Publisher Records", 1))
        next(); row(cell(0, circuitLabel + " · " + today() + if (filterText.isNotBlank()) " · $filterText" else ""))
        sections.forEach { s ->
            next()
            next(); row(cell(0, "Congregation:", 1), cell(1, s.heading.name))
            next(); row(cell(0, "Coordinator:", 1), cell(1, s.heading.coordinator))
            next(); row(cell(0, "Congregation Code:", 1), cell(1, s.heading.code.ifBlank { "—" }))
            next()
            next(); row(*columns.mapIndexed { i, c -> cell(i, c.title, 2) }.toTypedArray())
            s.rows.forEachIndexed { i, rec ->
                next()
                row(*columns.mapIndexed { ci, c -> cell(ci, c.value(rec, i)) }.toTypedArray())
            }
            next()
            next(); row(cell(0, "Total Publishers", 1), numCell(1, s.totals.total, 1))
            s.totals.byCategory.forEach { (c, n) -> next(); row(cell(0, c.displayName), numCell(1, n)) }
        }
        if (sections.size > 1) {
            val g = grand(sections)
            next(); next(); row(cell(0, "Circuit Summary", 1))
            next(); row(cell(0, "Congregation", 2), cell(1, "Coordinator", 2), cell(2, "Code", 2), cell(3, "Total Publishers", 2),
                *g.byCategory.mapIndexed { i, (c, _) -> cell(4 + i, c.displayName, 2) }.toTypedArray())
            sections.forEach { s ->
                next(); row(cell(0, s.heading.name), cell(1, s.heading.coordinator), cell(2, s.heading.code), numCell(3, s.totals.total),
                    *s.totals.byCategory.mapIndexed { i, (_, n) -> numCell(4 + i, n) }.toTypedArray())
            }
            next(); row(cell(0, "TOTAL", 1), cell(1, "", 1), cell(2, "", 1), numCell(3, g.total, 1), *g.byCategory.mapIndexed { i, (_, n) -> numCell(4 + i, n, 1) }.toTypedArray())
        }
        if (sections.isNotEmpty()) {
            val g = grand(sections)
            next(); next(); row(cell(0, "Summary", 1))
            next(); row(cell(0, "Total Publishers", 1), numCell(1, g.total, 1))
            g.byCategory.forEach { (c, n) -> next(); row(cell(0, c.displayName), numCell(1, n)) }
        }

        val colWidths = columns.mapIndexed { i, c -> "<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"${(c.width / 7.0).coerceAtLeast(8.0)}\" customWidth=\"1\"/>" }.joinToString("")
        val sheet = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<cols>$colWidths</cols><sheetData>$rowsXml</sheetData></worksheet>"
        val styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
            "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFD9E1F2\"/></patternFill></fill></fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"3\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/></cellXfs></styleSheet>"
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                fun put(name: String, content: String) { zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
                put("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                    "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                    "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>")
                put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
                put("xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Publishers\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
                put("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                    "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>")
                put("xl/styles.xml", styles)
                put("xl/worksheets/sheet1.xml", sheet)
            }
        }.toByteArray()

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val label = (sections.singleOrNull()?.heading?.name ?: circuitLabel).replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifBlank { "Circuit" }
        val file = File(dir, "PublisherRecords_$label.xlsx").apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Publisher Records"))
    }
}

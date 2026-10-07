package com.emfitsolutions.gopreach.data.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.emfitsolutions.gopreach.data.print.OrientationMode
import com.emfitsolutions.gopreach.data.print.PrintOptions
import com.emfitsolutions.gopreach.data.print.ReportPrinter
import com.emfitsolutions.gopreach.ui.screens.circuit.CIRCUIT_REPORT_COLUMNS
import com.emfitsolutions.gopreach.ui.screens.circuit.CircuitReportHeader
import com.emfitsolutions.gopreach.ui.screens.circuit.CircuitReportRow
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Print / PDF and Excel output of the Circuit Report. Both take exactly the rows and total the screen shows, so what is
 * printed or exported is never a different dataset.
 */
object CircuitReportExporter {
    private fun stamp() = SimpleDateFormat("MMMM d, yyyy h:mm a", Locale.getDefault()).format(Date())
    private val numeric = setOf("Total Publisher", "Auxiliary Pioneer", "Regular Publishers", "Unbaptized Publisher", "Elders", "Ministerial Servants")

    /** The end-of-report Summary (Metric | Value): the congregation count and every numeric total, the same figures the screen shows. */
    fun summaryOf(header: CircuitReportHeader, total: CircuitReportRow): List<Pair<String, String>> =
        listOf("Total Congregations" to header.totalCongregations.toString()) + CIRCUIT_REPORT_COLUMNS.filter { it.first in numeric }.map { (title, value) -> title to value(total) }

    fun print(context: Context, header: CircuitReportHeader, rows: List<CircuitReportRow>, total: CircuitReportRow, syncNote: String?) {
        fun e(s: String) = ReportPrinter.escapeHtml(s)
        val html = buildString {
            append("<html><head><meta charset=\"utf-8\"><style>")
            append("@page{size:landscape;margin:10mm} body{font-family:sans-serif;font-size:11px;} h2{margin:0 0 6px 0;font-size:18px;letter-spacing:1px}")
            append(".head div{font-size:12px;margin:1px 0} .meta{color:#444;margin:6px 0 8px 0}")
            append("table{border-collapse:collapse;width:100%} th,td{border:1px solid #000;padding:3px 6px;text-align:left}")
            append("th{background:#e6e6e6} td.n,th.n{text-align:right} tr{page-break-inside:avoid} tr.total td{font-weight:bold;background:#f2f2f2}")
            append("</style></head><body><h2>CIRCUIT REPORT</h2><div class=\"head\">")
            append("<div><b>Circuit:</b> ").append(e(header.circuit)).append("</div>")
            append("<div><b>Circuit Overseer:</b> ").append(e(header.overseer)).append("</div>")
            append("<div><b>Total Congregations:</b> ").append(header.totalCongregations).append("</div></div>")
            append("<div class=\"meta\">Generated: ").append(e(stamp()))
            if (syncNote != null) append(" · ").append(e(syncNote))
            append("</div><table><thead><tr>")
            CIRCUIT_REPORT_COLUMNS.forEach { (title, _) -> append("<th").append(if (title in numeric) " class=\"n\"" else "").append(">").append(e(title)).append("</th>") }
            append("</tr></thead><tbody>")
            (rows + total).forEach { r ->
                append(if (r === total) "<tr class=\"total\">" else "<tr>")
                CIRCUIT_REPORT_COLUMNS.forEach { (title, value) -> append("<td").append(if (title in numeric) " class=\"n\"" else "").append(">").append(e(value(r))).append("</td>") }
                append("</tr>")
            }
            append("</tbody></table><h3 style=\"margin:14px 0 4px 0\">Summary</h3><table style=\"width:auto\">")
            summaryOf(header, total).forEach { (k, v) -> append("<tr><td>").append(e(k)).append("</td><td style=\"text-align:right;font-weight:bold\">").append(e(v)).append("</td></tr>") }
            append("</table><div class=\"meta\">Generated: ").append(e(stamp())).append("</div></body></html>")
        }
        ReportPrinter.printHtml(context, "Circuit Report - ${header.circuit}", html, PrintOptions(OrientationMode.LANDSCAPE))
    }

    fun shareExcel(context: Context, header: CircuitReportHeader, rows: List<CircuitReportRow>, total: CircuitReportRow, syncNote: String?) {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun col(i: Int) = ('A' + i).toString()
        val xml = StringBuilder()
        var r = 0
        fun text(c: Int, s: String, style: Int = 0) = "<c r=\"${col(c)}$r\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(s)}</t></is></c>"
        fun number(c: Int, v: String, style: Int = 0) = v.toIntOrNull()?.let { "<c r=\"${col(c)}$r\" s=\"$style\"><v>$it</v></c>" } ?: text(c, v, style)
        fun row(vararg cells: String) { xml.append("<row r=\"$r\">").append(cells.joinToString("")).append("</row>") }
        r++; row(text(0, "CIRCUIT REPORT", 1))
        r++; row(text(0, "Circuit:", 1), text(1, header.circuit))
        r++; row(text(0, "Circuit Overseer:", 1), text(1, header.overseer))
        r++; row(text(0, "Total Congregations:", 1), text(1, header.totalCongregations.toString()))
        r++; row(text(0, "Generated:", 1), text(1, stamp() + (syncNote?.let { " · $it" } ?: "")))
        r++
        r++; row(*CIRCUIT_REPORT_COLUMNS.mapIndexed { i, (title, _) -> text(i, title, 2) }.toTypedArray())
        (rows + total).forEach { rec ->
            r++
            val style = if (rec === total) 1 else 0
            row(*CIRCUIT_REPORT_COLUMNS.mapIndexed { i, (title, value) -> if (title in numeric) number(i, value(rec), style) else text(i, value(rec), style) }.toTypedArray())
        }
        r++
        r++; row(text(0, "Summary", 1))
        summaryOf(header, total).forEach { (k, v) -> r++; row(text(0, k, 1), number(1, v)) }
        val widths = listOf(8, 28, 18, 26, 14, 16, 18, 20, 9, 20)
        val cols = widths.mapIndexed { i, w -> "<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>" }.joinToString("")
        val sheet = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><cols>$cols</cols><sheetData>$xml</sheetData></worksheet>"
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
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Circuit Report\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
                put("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                    "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>")
                put("xl/styles.xml", styles)
                put("xl/worksheets/sheet1.xml", sheet)
            }
        }.toByteArray()
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val label = header.circuit.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifBlank { "Circuit" }
        val file = File(dir, "CircuitReport_$label.xlsx").apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Circuit Report"))
    }
}

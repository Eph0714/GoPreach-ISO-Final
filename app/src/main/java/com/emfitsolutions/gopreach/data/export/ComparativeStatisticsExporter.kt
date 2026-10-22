package com.emfitsolutions.gopreach.data.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.emfitsolutions.gopreach.domain.ConsolidatedSheets
import com.emfitsolutions.gopreach.data.print.OrientationMode
import com.emfitsolutions.gopreach.data.print.PrintOptions
import com.emfitsolutions.gopreach.data.print.ReportPrinter
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Everything a printed / Excel Congregation Comparative Report contains, already formatted as text (so both outputs match the screen exactly). */
data class ComparativeReportData(
    val congregationName: String,
    val periodA: String,
    val periodB: String,
    val generatedBy: String,
    val generatedAt: String,
    val calculationMode: String,
    /** First row = headings (Statistic | Period A | Period B | Difference | % Change). */
    val comparison: List<List<String>>,
    /** First row = headings (Statistic | Jan 2025 | ...). */
    val monthly: List<List<String>>,
    /** Meeting attendance coverage lines (recorded / missing per period) and the months that have no snapshot. */
    val notes: List<String>,
    /** Formal-report details (report number, status, submitted by / date, Circuit Overseer, received date, CO remarks), shown under the header. */
    val details: List<Pair<String, String>> = emptyList(),
    /** Months of the periods that have no historical snapshot. */
    val missingMonths: List<String> = emptyList(),
    /** The end-of-report summary (first row = headings), always printed last. */
    val summary: List<List<String>> = emptyList(),
    /** The report's title; the Circuit Overseer's dynamic report names itself here. */
    val title: String = "Congregation Comparative Report",
    /** When not empty, these lines replace the fixed "Period A / Period B" lines (any number of periods, each with its months and coverage). */
    val periodLines: List<Pair<String, String>> = emptyList(),
    val comparisonHeading: String = "Period Comparison",
    val monthlyHeading: String = "Monthly Historical Statistics",
    /** Field Service Report style sheets (one per period, consolidated as the whole congregation), printed / exported before the comparison. */
    val sheets: List<com.emfitsolutions.gopreach.domain.ConsolidatedSheet> = emptyList(),
    /** The automatic Comparison Summary: a few short paragraphs, printed after the comparison results. */
    val summaryParagraphs: List<String> = emptyList(),
    /** When not empty, the comparison is also printed as bars (one pair per row, each pair on its own scale starting at zero). */
    val graph: List<com.emfitsolutions.gopreach.domain.ComparisonSummary.Row> = emptyList(),
    /** Short lines under the comparison table (figures that are not recorded). */
    val footnotes: List<String> = emptyList(),
    /** Extra titled tables (first row = headings) printed before the comparison, e.g. the consolidated attendance of each period. */
    val extraTables: List<Pair<String, List<List<String>>>> = emptyList(),
)

/** Print / PDF (through the zoomable print preview) and Excel of the Congregation Comparative Report, in a compact landscape layout. */
object ComparativeStatisticsExporter {

    fun print(context: Context, data: ComparativeReportData) {
        ReportPrinter.printHtml(context, "${data.title} - ${data.congregationName}", buildHtml(data), PrintOptions(OrientationMode.LANDSCAPE))
    }

    /** The printed page as HTML - one function so what is printed can be checked against what is on screen. */
    internal fun buildHtml(data: ComparativeReportData): String {
        fun e(s: String) = ReportPrinter.escapeHtml(s)
        fun table(rows: List<List<String>>, firstColWide: Boolean = true) = buildString {
            append("<table><thead><tr>")
            rows.first().forEachIndexed { i, h -> append("<th").append(if (i == 0) " class=\"l\"" else "").append(">").append(e(h)).append("</th>") }
            append("</tr></thead><tbody>")
            rows.drop(1).forEach { r ->
                append("<tr>")
                r.forEachIndexed { i, c -> append("<td").append(if (i == 0) " class=\"l\"" else "").append(">").append(e(c)).append("</td>") }
                append("</tr>")
            }
            append("</tbody></table>")
        }
        return buildString {
            append("<html><head><meta charset=\"utf-8\"><style>")
            append("@page{size:landscape;margin:8mm} body{font-family:sans-serif;font-size:10px;} h1{margin:0;font-size:15px} h2{margin:10px 0 4px 0;font-size:12px}")
            append(".sub{font-size:13px;font-weight:bold;margin:0 0 4px 0} .meta div{margin:1px 0}")
            append("table{border-collapse:collapse;width:100%;margin-bottom:4px} th,td{border:1px solid #000;padding:2px 5px;text-align:right} th{background:#e6e6e6}")
            append("td.l,th.l{text-align:left;white-space:nowrap} tr{page-break-inside:avoid} .note{margin:1px 0;color:#333}")
            append(" th.peach{background:#fce4d6} th.blue{background:#bdd7ee} th.green{background:#c6efce} th.plain{background:#fff;border:none} tr.total td{background:#ff0;font-weight:bold;border:2px solid #000}")
            append(" .gr{margin:3px 0;page-break-inside:avoid} .gn{font-weight:bold;margin-bottom:1px} .gc{font-weight:normal;color:#444;margin-left:6px} .bar{position:relative;height:11px;margin:1px 0;background:#f2f2f2;width:60%} .bar span{display:block;height:11px;-webkit-print-color-adjust:exact;print-color-adjust:exact} .bar span.first{background:#444} .bar span.second{background:#f2b705} .bar b{position:absolute;left:4px;top:-1px;font-size:9px;color:#000;mix-blend-mode:normal} .nodata{font-size:9px;color:#777;margin:1px 0} .legend{margin:2px 0 4px 0} .k{display:inline-block;width:10px;height:10px;-webkit-print-color-adjust:exact;print-color-adjust:exact} .k.first{background:#444} .k.second{background:#f2b705} p.sum{margin:3px 0;font-size:11px}")
            append("</style></head><body><h1>GoPreach App</h1><p class=\"sub\">").append(e(data.title)).append("</p><div class=\"meta\">")
            append("<div><b>Congregation:</b> ").append(e(data.congregationName)).append("</div>")
            if (data.periodLines.isEmpty()) append("<div><b>Period A:</b> ").append(e(data.periodA)).append(" &nbsp; <b>Period B:</b> ").append(e(data.periodB)).append("</div>")
            else data.periodLines.forEach { (k, v) -> append("<div><b>").append(e(k)).append(":</b> ").append(e(v)).append("</div>") }
            append("<div><b>Generated By:</b> ").append(e(data.generatedBy)).append(" &nbsp; <b>Generated:</b> ").append(e(data.generatedAt))
            if (data.calculationMode.isNotBlank()) append(" &nbsp; <b>Attendance Calculation:</b> ").append(e(data.calculationMode))
            append("</div>")
            data.details.forEach { (k, v) -> append("<div><b>").append(e(k)).append(":</b> ").append(e(v)).append("</div>") }
            append("</div>")
            data.sheets.forEach { sheet ->
                val t = ConsolidatedSheets.toTable(sheet)
                val rc = sheet.reportColumns.size
                val hc = sheet.hourColumns.size
                append("<h2>").append(e(sheet.reportTitle)).append("</h2>")
                append("<div class=\"note\"><b>Congregation:</b> ").append(e(sheet.congregationName)).append(" &nbsp; <b>FS Groups:</b> Consolidated - all FS Groups &nbsp; <b>Months with a report:</b> ${sheet.monthsAccepted} of ${sheet.monthsExpected}</div>")
                append("<table class=\"sheet\"><thead><tr><th colspan=\"3\" class=\"plain\"></th><th colspan=\"$rc\" class=\"peach\">No. of Reports</th>")
                if (hc > 0) append("<th colspan=\"$hc\" class=\"blue\">Hours</th>")
                append("<th colspan=\"$rc\" class=\"green\">Bible Studies</th><th class=\"plain\"></th></tr><tr>")
                t.first().forEachIndexed { i, h ->
                    val cls = when { i < 3 -> "plain"; i < 3 + rc -> "peach"; i < 3 + rc + hc -> "blue"; i < 3 + rc + hc + rc -> "green"; else -> "plain" }
                    append("<th class=\"").append(cls).append("\">").append(e(h)).append("</th>")
                }
                append("</tr></thead><tbody>")
                t.drop(1).forEachIndexed { ri, r ->
                    append(if (ri == t.size - 2) "<tr class=\"total\">" else "<tr>")
                    r.forEachIndexed { i, c -> append("<td").append(if (i == 2 || i == r.size - 1) " class=\"l\"" else "").append(">").append(e(c)).append("</td>") }
                    append("</tr>")
                }
                append("</tbody></table>")
            }
            data.extraTables.forEach { (title, rows) -> append("<h2>").append(e(title)).append("</h2>").append(table(rows)) }
            append("<h2>").append(e(data.comparisonHeading)).append("</h2>").append(table(data.comparison))
            data.footnotes.forEach { append("<div class=\"note\">").append(e(it)).append("</div>") }
            data.notes.forEach { append("<div class=\"note\">").append(e(it)).append("</div>") }
            if (data.missingMonths.isNotEmpty()) append("<div class=\"note\"><b>Missing data:</b> ").append(e(data.missingMonths.joinToString(", "))).append("</div>")
            if (data.graph.isNotEmpty()) {
                append("<h2>Graph</h2><div class=\"legend\"><span class=\"k first\"></span> ").append(e(data.comparison.first().getOrElse(1) { "" })).append(" &nbsp; <span class=\"k second\"></span> ").append(e(data.comparison.first().getOrElse(2) { "" })).append("</div>")
                data.graph.forEach { r ->
                    val top = maxOf(r.first ?: 0.0, r.second ?: 0.0)
                    fun bar(v: Double?, cls: String) = if (v == null) "<div class=\"nodata\">no data</div>" else "<div class=\"bar\"><span class=\"$cls\" style=\"width:${if (top <= 0.0) 0 else (v / top * 100).toInt().coerceAtLeast(if (v > 0) 1 else 0)}%\"></span><b>${e(com.emfitsolutions.gopreach.domain.ComparisonSummary.number(v))}</b></div>"
                    append("<div class=\"gr\"><div class=\"gn\">").append(e(r.name)).append(" <span class=\"gc\">").append(e(r.changeText)).append("</span></div>").append(bar(r.first, "first")).append(bar(r.second, "second")).append("</div>")
                }
            }
            if (data.monthly.isNotEmpty()) append("<h2>").append(e(data.monthlyHeading)).append("</h2>").append(table(data.monthly))
            if (data.summaryParagraphs.isNotEmpty()) {
                append("<h2>Comparison Summary</h2>")
                data.summaryParagraphs.forEach { append("<p class=\"sum\">").append(e(it)).append("</p>") }
            }
            if (data.summary.isNotEmpty()) append("<h2>Summary</h2>").append(table(data.summary))
            append("<div class=\"note\">Generated By: ").append(e(data.generatedBy)).append(" &nbsp; Generated: ").append(e(data.generatedAt)).append("</div>")
            append("</body></html>")
        }
    }

    fun shareExcel(context: Context, data: ComparativeReportData) {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun col(i: Int): String { var n = i; var s = ""; do { s = ('A' + n % 26) + s; n = n / 26 - 1 } while (n >= 0); return s }
        val xml = StringBuilder()
        var r = 0
        fun cell(c: Int, v: String, style: Int) = "<c r=\"${col(c)}$r\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>"
        fun row(cells: List<String>, style: Int = 0, firstStyle: Int = style) { r++; xml.append("<row r=\"$r\">"); cells.forEachIndexed { i, v -> xml.append(cell(i, v, if (i == 0) firstStyle else style)) }; xml.append("</row>") }
        fun blank() { r++ }
        row(listOf("GoPreach App"), 1)
        row(listOf(data.title), 1)
        row(listOf("Congregation:", data.congregationName), 0, 1)
        if (data.periodLines.isEmpty()) {
            row(listOf("Period A:", data.periodA), 0, 1)
            row(listOf("Period B:", data.periodB), 0, 1)
        } else data.periodLines.forEach { (k, v) -> row(listOf("$k:", v), 0, 1) }
        row(listOf("Generated By:", data.generatedBy), 0, 1)
        row(listOf("Generated:", data.generatedAt), 0, 1)
        if (data.calculationMode.isNotBlank()) row(listOf("Attendance Calculation:", data.calculationMode), 0, 1)
        data.details.forEach { (k, v) -> row(listOf("$k:", v), 0, 1) }
        blank()
        fun styledRow(cells: List<String>, styles: List<Int>) { r++; xml.append("<row r=\"$r\">"); cells.forEachIndexed { i, v -> xml.append(cell(i, v, styles[i])) }; xml.append("</row>") }
        data.sheets.forEach { sheet ->
            val t = ConsolidatedSheets.toTable(sheet)
            val rc = sheet.reportColumns.size
            val hc = sheet.hourColumns.size
            row(listOf(sheet.reportTitle), 1)
            row(listOf("Congregation:", sheet.congregationName), 0, 1)
            row(listOf("FS Groups:", "Consolidated - all FS Groups"), 0, 1)
            row(listOf("Months with a report:", "${sheet.monthsAccepted} of ${sheet.monthsExpected}"), 0, 1)
            fun groupStyle(i: Int) = when { i < 3 -> 8; i < 3 + rc -> 4; i < 3 + rc + hc -> 5; i < 3 + rc + hc + rc -> 6; else -> 8 }
            val band = List(3) { "" } + listOf("No. of Reports") + List(rc - 1) { "" } + (if (hc > 0) listOf("Hours") + List(hc - 1) { "" } else emptyList()) + listOf("Bible Studies") + List(rc - 1) { "" } + listOf("")
            styledRow(band, band.indices.map { i -> if (i < 3 || i == band.size - 1) 0 else groupStyle(i) })
            styledRow(t.first(), t.first().indices.map { groupStyle(it) })
            t.drop(1).forEachIndexed { ri, cells -> styledRow(cells, cells.indices.map { if (ri == t.size - 2) 7 else 3 }) }
            blank()
        }
        data.extraTables.forEach { (title, rows) ->
            row(listOf(title), 1)
            row(rows.first(), 2)
            rows.drop(1).forEach { row(it, 0, 1) }
            blank()
        }
        row(listOf(data.comparisonHeading), 1)
        row(data.comparison.first(), 2)
        data.comparison.drop(1).forEach { row(it, 0, 1) }
        data.footnotes.forEach { row(listOf(it)) }
        data.notes.forEach { row(listOf(it)) }
        if (data.missingMonths.isNotEmpty()) row(listOf("Missing data:", data.missingMonths.joinToString(", ")), 0, 1)
        if (data.monthly.isNotEmpty()) {
            blank()
            row(listOf(data.monthlyHeading), 1)
            row(data.monthly.first(), 2)
            data.monthly.drop(1).forEach { row(it, 0, 1) }
        }
        if (data.summaryParagraphs.isNotEmpty()) {
            blank()
            row(listOf("Comparison Summary"), 1)
            data.summaryParagraphs.forEach { row(listOf(it)) }
        }
        if (data.summary.isNotEmpty()) {
            blank()
            row(listOf("Summary"), 1)
            row(data.summary.first(), 2)
            data.summary.drop(1).forEach { row(it, 0, 1) }
        }
        val widest = maxOf(data.monthly.firstOrNull()?.size ?: 0, data.extraTables.maxOfOrNull { it.second.first().size } ?: 0, data.comparison.first().size, data.sheets.maxOfOrNull { ConsolidatedSheets.toTable(it).first().size } ?: 0, 5)
        val layout = com.emfitsolutions.gopreach.data.export.ExcelLayout.parts(xml.toString())
        val sheet = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">${layout.sheetPr}${layout.cols}<sheetData>$xml</sheetData>${layout.pageSetup}</worksheet>"
        val styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
            "<fills count=\"7\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFD9E1F2\"/></patternFill></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFCE4D6\"/></patternFill></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFBDD7EE\"/></patternFill></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFC6EFCE\"/></patternFill></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFFFF00\"/></patternFill></fill></fills>" +
            "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>" +
            "<border><left style=\"thin\"/><right style=\"thin\"/><top style=\"thin\"/><bottom style=\"thin\"/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"9\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"4\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"5\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"6\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyBorder=\"1\"/></cellXfs></styleSheet>"
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
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Comparative Report\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
                put("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                    "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>")
                put("xl/styles.xml", styles)
                put("xl/worksheets/sheet1.xml", sheet)
            }
        }.toByteArray()
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val label = data.congregationName.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifBlank { "Congregation" }
        val file = File(dir, "ComparativeReport_$label.xlsx").apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Congregation Comparative Report"))
    }
}

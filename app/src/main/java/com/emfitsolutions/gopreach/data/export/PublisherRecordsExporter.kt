package com.emfitsolutions.gopreach.data.export

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.emfitsolutions.gopreach.data.print.OrientationMode
import com.emfitsolutions.gopreach.data.print.PrintOptions
import com.emfitsolutions.gopreach.data.print.ReportPrinter
import com.emfitsolutions.gopreach.ui.screens.publishers.PublisherColumns
import com.emfitsolutions.gopreach.ui.screens.publishers.PublisherRow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Print Format for the printed and PDF Publisher records. */
enum class PublisherPrintFormat(val label: String) { TABLE("Table"), DETAILED("Detailed List") }

/**
 * Print, PDF and Excel output for the Publisher Module. All three take the same already-filtered [PublisherRow]s the
 * screen is showing, so they always agree; a blank value is printed as a blank cell, never a placeholder, and ages are
 * worked out from the dates at the moment of printing/exporting.
 */
object PublisherRecordsExporter {

    /** The one column list the screen's table also uses, so printed output is exactly what the table shows. */
    private val cols get() = PublisherColumns.all

    /** A run of rows under an optional group heading ("FS GROUP 1 · 14"); numbering restarts in every section, like the table. */
    private class Section(val heading: String?, val rows: List<PublisherRow>)

    private fun sections(rows: List<PublisherRow>, grouped: Boolean): List<Section> =
        if (!grouped) listOf(Section(null, rows))
        else rows.groupBy { PublisherColumns.groupHeading(it) }.toSortedMap().map { (name, members) -> Section("$name · ${members.size}", members) }
    private fun reportDate(): String = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date())

    // ---------------------------------------------------------------------------------------------
    // Print (system print dialog, which also saves to PDF)

    fun print(context: Context, rows: List<PublisherRow>, congregationName: String, filterText: String, format: PublisherPrintFormat, grouped: Boolean = false) {
        fun e(s: String) = ReportPrinter.escapeHtml(s)
        val secs = sections(rows, grouped)
        val html = buildString {
            append("<html><head><meta charset=\"utf-8\"><style>")
            append("@page{margin:10mm} body{font-family:sans-serif;font-size:9px;} h2{margin:0 0 2px 0;} .meta{color:#444;margin-bottom:8px;}")
            append("table{border-collapse:collapse;width:100%} th,td{border:1px solid #000;padding:2px 4px;text-align:left;vertical-align:top}")
            append("thead{display:table-header-group} tr{page-break-inside:avoid} th{font-weight:bold} td.no,th.no{text-align:center;width:22px} td.grp{background:#d9d2ec;font-weight:bold;font-size:10px}")
            append(".rec{border:1px solid #000;padding:6px 8px;margin-bottom:8px;page-break-inside:avoid} .rec div{margin:1px 0} .lbl{display:inline-block;width:150px;font-weight:bold} .grph{font-weight:bold;font-size:12px;margin:10px 0 4px 0}")
            append("</style></head><body>")
            append("<h2>Publisher Records</h2><div class=\"meta\">").append(e(congregationName)).append(" · ").append(e(reportDate()))
            if (filterText.isNotBlank()) append(" · ").append(e(filterText))
            append(" · Records: ").append(rows.size).append("</div>")
            if (format == PublisherPrintFormat.TABLE) {
                append("<table><thead><tr><th class=\"no\">No.</th>")
                cols.forEach { append("<th>").append(e(it.title)).append("</th>") }
                append("</tr></thead><tbody>")
                secs.forEach { sec ->
                    sec.heading?.let { append("<tr><td class=\"grp\" colspan=\"").append(cols.size + 1).append("\">").append(e(it)).append("</td></tr>") }
                    sec.rows.forEachIndexed { i, r ->
                        append("<tr><td class=\"no\">").append(i + 1).append("</td>")
                        cols.forEach { append("<td>").append(e(it.value(r))).append("</td>") }
                        append("</tr>")
                    }
                }
                append("</tbody></table>")
            } else {
                secs.forEach { sec ->
                    sec.heading?.let { append("<div class=\"grph\">").append(e(it)).append("</div>") }
                    sec.rows.forEach { r ->
                        append("<div class=\"rec\"><b>PUBLISHER RECORD</b>")
                        cols.forEach { c -> append("<div><span class=\"lbl\">").append(e(c.title)).append(":</span>").append(e(c.value(r))).append("</div>") }
                        append("</div>")
                    }
                }
            }
            append(com.emfitsolutions.gopreach.ui.screens.publishers.PublisherSummary.html(com.emfitsolutions.gopreach.ui.screens.publishers.PublisherSummary.rows(rows)))
            append("<div style=\"margin-top:6px;color:#444\">Generated: ").append(e(reportDate())).append("</div>")
            append("</body></html>")
        }
        ReportPrinter.printHtml(
            context,
            "Publisher Records - $congregationName",
            html,
            PrintOptions(if (format == PublisherPrintFormat.TABLE) OrientationMode.LANDSCAPE else OrientationMode.PORTRAIT),
        )
    }
    // ---------------------------------------------------------------------------------------------
    // PDF file (own layout: repeated headers, page numbers, wrapped text, no row split across pages)

    fun sharePdf(context: Context, rows: List<PublisherRow>, congregationName: String, filterText: String, format: PublisherPrintFormat, grouped: Boolean = false) {
        val secs = sections(rows, grouped)
        val landscape = format == PublisherPrintFormat.TABLE
        // The table has every publisher field as a column, so it gets an A3-wide page; the Detailed List is a portrait A4 page.
        val pageW = if (landscape) 1191 else 595
        val pageH = 842
        val margin = 32f
        val contentW = pageW - margin * 2
        val size = if (landscape) 8f else 9f
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = size }
        val boldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = size; typeface = Typeface.DEFAULT_BOLD }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 16f; typeface = Typeface.DEFAULT_BOLD }
        val grayPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 9f }
        val linePaint = Paint().apply { color = Color.BLACK; strokeWidth = 0.6f; style = Paint.Style.STROKE }
        val groupFill = Paint().apply { color = Color.rgb(217, 210, 236); style = Paint.Style.FILL }

        fun layout(text: String, paint: TextPaint, width: Float) =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(10)).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()

        // Each page is a list of draw actions; page numbers are added at the end once the total is known.
        class PageDraw(val actions: MutableList<(android.graphics.Canvas) -> Unit> = mutableListOf())
        val pages = mutableListOf<PageDraw>()
        var page = PageDraw().also { pages += it }
        var y = margin

        fun newPage() { page = PageDraw().also { pages += it }; y = margin }

        run {
            val top = y
            page.actions += { c -> c.drawText("Publisher Records", margin, top + 14f, titlePaint) }
            y += 22f
            val sub = listOf(congregationName, reportDate(), filterText.takeIf { it.isNotBlank() }, "Records: ${rows.size}").filterNotNull().joinToString("  ·  ")
            val subLayout = layout(sub, grayPaint, contentW)
            val subY = y
            page.actions += { c -> c.save(); c.translate(margin, subY); subLayout.draw(c); c.restore() }
            y += subLayout.height + 8f
        }

        if (format == PublisherPrintFormat.TABLE) {
            val titles = listOf("No.") + cols.map { it.title }
            val weights = listOf(26f) + cols.map { it.width.toFloat() }
            val scale = contentW / weights.sum()
            val widths = weights.map { it * scale }
            // Headings wrap inside their column, so a long one ("Contact Person No.") never runs into the next.
            val headLayouts = titles.mapIndexed { i, t -> layout(t, boldPaint, widths[i] - 6f) }
            val headH = (headLayouts.maxOf { it.height } + 6f).coerceAtLeast(18f)
            fun drawHeaderRow() {
                val top = y
                page.actions += { c ->
                    var x = margin
                    headLayouts.forEachIndexed { i, lay ->
                        c.drawRect(x, top, x + widths[i], top + headH, linePaint)
                        c.save(); c.translate(x + 3f, top + 3f); lay.draw(c); c.restore()
                        x += widths[i]
                    }
                }
                y += headH
            }
            drawHeaderRow()
            secs.forEach { sec ->
                sec.heading?.let { heading ->
                    if (y + 18f > pageH - margin - 14f) { newPage(); drawHeaderRow() }
                    val top = y
                    page.actions += { c ->
                        c.drawRect(margin, top, margin + contentW, top + 18f, groupFill)
                        c.drawRect(margin, top, margin + contentW, top + 18f, linePaint)
                        c.drawText(heading, margin + 4f, top + 12f, boldPaint)
                    }
                    y += 18f
                }
                sec.rows.forEachIndexed { index, r ->
                    val cells = listOf((index + 1).toString()) + cols.map { it.value(r) }
                    val layouts = cells.mapIndexed { i, t -> layout(t, textPaint, widths[i] - 6f) }
                    val rowH = (layouts.maxOf { it.height } + 6f).coerceAtLeast(16f)
                    if (y + rowH > pageH - margin - 14f) { newPage(); drawHeaderRow() }
                    val top = y
                    page.actions += { c ->
                        var x = margin
                        layouts.forEachIndexed { i, lay ->
                            c.drawRect(x, top, x + widths[i], top + rowH, linePaint)
                            c.save(); c.translate(x + 3f, top + 3f); lay.draw(c); c.restore()
                            x += widths[i]
                        }
                    }
                    y += rowH
                }
            }
        } else {
            secs.forEach { sec ->
                sec.heading?.let { heading ->
                    if (y + 20f > pageH - margin - 14f) newPage()
                    val top = y
                    page.actions += { c -> c.drawText(heading, margin, top + 12f, boldPaint) }
                    y += 20f
                }
                sec.rows.forEach { r ->
                    val rowsOut = cols.map { it.title to it.value(r) }
                    val labelW = 110f
                    val valueLayouts = rowsOut.map { (_, v) -> layout(v, textPaint, contentW - labelW - 16f) }
                    val blockH = 18f + rowsOut.indices.sumOf { (valueLayouts[it].height.coerceAtLeast(12) + 2).toInt() } + 8f
                    if (y + blockH > pageH - margin - 14f) newPage()
                    val top = y
                    page.actions += { c ->
                        c.drawRect(margin, top, margin + contentW, top + blockH - 4f, linePaint)
                        c.drawText("PUBLISHER RECORD", margin + 8f, top + 12f, boldPaint)
                        var yy = top + 18f
                        rowsOut.forEachIndexed { i, (label, _) ->
                            c.drawText("$label:", margin + 8f, yy + 9f, boldPaint)
                            c.save(); c.translate(margin + 8f + labelW, yy); valueLayouts[i].draw(c); c.restore()
                            yy += valueLayouts[i].height.coerceAtLeast(12) + 2f
                        }
                    }
                    y += blockH
                }
            }
        }

        run {
            val sum = com.emfitsolutions.gopreach.ui.screens.publishers.PublisherSummary.rows(rows)
            val blockH = 30f + (sum.size - 1) * 13f
            if (y + blockH > pageH - margin - 14f) newPage()
            y += 12f
            val top = y
            page.actions += { c ->
                c.drawText("Summary", margin, top + 12f, boldPaint)
                sum.drop(1).forEachIndexed { i, r ->
                    c.drawText(r[0] + ":", margin, top + 28f + i * 13f, textPaint)
                    c.drawText(r[1], margin + 150f, top + 28f + i * 13f, boldPaint)
                }
            }
            y += blockH
        }

        val document = PdfDocument()
        pages.forEachIndexed { index, p ->
            val pdfPage = document.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, index + 1).create())
            p.actions.forEach { it(pdfPage.canvas) }
            pdfPage.canvas.drawText("Page ${index + 1} of ${pages.size}", pageW - margin - 60f, pageH - margin + 10f, grayPaint)
            document.finishPage(pdfPage)
        }
        val file = File(File(context.cacheDir, "exports").apply { mkdirs() }, "PublisherRecords_${safe(congregationName)}_${System.currentTimeMillis()}.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        com.emfitsolutions.gopreach.data.print.PdfPreviewDialog.showOrShare(context, file, "Publisher Records — $congregationName") { share(context, file, "application/pdf", "Share Publisher Records") }
    }

    // ---------------------------------------------------------------------------------------------
    // Excel (.xlsx) — a minimal hand-written workbook: header row, widths, frozen header, real dates

    fun shareExcel(context: Context, rows: List<PublisherRow>, congregationName: String, grouped: Boolean = false) {
        val secs = sections(rows, grouped)
        // Same columns as the table (No. first), written as text exactly as the table shows them.
        val headers = listOf("No.") + cols.map { it.title }
        val widths = listOf(6) + cols.map { (it.width / 6).coerceAtLeast(8) }

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun colName(i: Int): String = buildString { var n = i; do { insert(0, 'A' + n % 26); n = n / 26 - 1 } while (n >= 0) }
        fun str(ref: String, text: String, style: Int = 0) = if (text.isEmpty()) "" else "<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(text)}</t></is></c>"
        fun num(ref: String, v: Number, style: Int = 0) = "<c r=\"$ref\" s=\"$style\"><v>$v</v></c>"

        val rowsXml = buildString {
            append("<row r=\"1\">")
            headers.forEachIndexed { i, h -> append(str("${colName(i)}1", h, 1)) }
            append("</row>")
            var r = 1
            secs.forEach { sec ->
                sec.heading?.let { heading -> r++; append("<row r=\"$r\">"); append(str("A$r", heading, 1)); append("</row>") }
                sec.rows.forEachIndexed { index, row ->
                    r++
                    append("<row r=\"$r\">")
                    append(num("A$r", index + 1))
                    cols.forEachIndexed { i, c -> append(str("${colName(i + 1)}$r", c.value(row), 3)) }
                    append("</row>")
                }
            }
            val sum = com.emfitsolutions.gopreach.ui.screens.publishers.PublisherSummary.rows(rows)
            var sr = r + 2
            append("<row r=\"$sr\">"); append(str("B$sr", "Summary", 1)); append("</row>")
            sum.drop(1).forEach { row ->
                sr++
                append("<row r=\"$sr\">"); append(str("B$sr", row[0])); append(str("C$sr", row[1])); append("</row>")
            }
        }
        val layout = ExcelLayout.parts(rowsXml, widths.size)
        val sheet = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            layout.sheetPr +
            "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>" +
            layout.cols + "<sheetData>" + rowsXml + "</sheetData>" + layout.pageSetup + "</worksheet>"
        val styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"mmmm d, yyyy\"/></numFmts>" +
            "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
            "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFD9E1F2\"/></patternFill></fill></fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"4\">" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>" +
            "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"><alignment horizontal=\"left\"/></xf>" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment wrapText=\"1\" vertical=\"top\"/></xf>" +
            "</cellXfs></styleSheet>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) { zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            put("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>")
            put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            put("xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Publishers\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>")
            put("xl/styles.xml", styles)
            put("xl/worksheets/sheet1.xml", sheet)
        }
        val file = File(File(context.cacheDir, "exports").apply { mkdirs() }, "PublisherRecords_${safe(congregationName)}.xlsx")
        file.writeBytes(out.toByteArray())
        share(context, file, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Share Publisher Records")
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifBlank { "Congregation" }

    private fun share(context: Context, file: File, mime: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }
}

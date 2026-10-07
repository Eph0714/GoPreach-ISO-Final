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
import com.emfitsolutions.gopreach.data.model.displayName
import com.emfitsolutions.gopreach.data.print.OrientationMode
import com.emfitsolutions.gopreach.data.print.PrintOptions
import com.emfitsolutions.gopreach.data.print.ReportPrinter
import com.emfitsolutions.gopreach.domain.PersonDates
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

    /** One row's printable values; blank strings stay blank. */
    private data class Line(
        val number: Int,
        val name: String,
        val first: String,
        val middle: String,
        val last: String,
        val gender: String,
        val birthdate: Long?,
        val age: Int?,
        val baptismalDate: Long?,
        val baptismalAge: Int?,
        val group: String,
        val status: String,
        val contact: String,
        val address: String,
        val remarks: String,
    )

    private fun lines(rows: List<PublisherRow>): List<Line> = rows.mapIndexed { index, r ->
        val p = r.person
        Line(
            number = index + 1,
            name = p.fullName,
            first = p.firstName,
            middle = p.middleInitial.orEmpty(),
            last = p.lastName,
            gender = p.gender?.name?.lowercase()?.replaceFirstChar { it.uppercase() }.orEmpty(),
            birthdate = p.birthdate,
            age = PersonDates.age(p.birthdate),
            baptismalDate = p.baptismalDate,
            baptismalAge = PersonDates.baptismalAge(p.birthdate, p.baptismalDate),
            group = r.groupName.takeUnless { it.equals("None", true) || it == "—" }.orEmpty(),
            status = r.category.displayName,
            contact = p.contact,
            address = p.address,
            remarks = p.remarks?.trim().orEmpty(),
        )
    }

    private fun reportDate(): String = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date())
    private fun yearsOrBlank(v: Int?): String = PersonDates.yearsText(v)

    // ---------------------------------------------------------------------------------------------
    // Print (system print dialog, which also saves to PDF)

    fun print(context: Context, rows: List<PublisherRow>, congregationName: String, filterText: String, format: PublisherPrintFormat) {
        val data = lines(rows)
        fun e(s: String) = ReportPrinter.escapeHtml(s)
        val html = buildString {
            append("<html><head><meta charset=\"utf-8\"><style>")
            append("@page{margin:12mm} body{font-family:sans-serif;font-size:11px;} h2{margin:0 0 2px 0;} .meta{color:#444;margin-bottom:8px;}")
            append("table{border-collapse:collapse;width:100%} th,td{border:1px solid #000;padding:3px 5px;text-align:left;vertical-align:top}")
            append("thead{display:table-header-group} tr{page-break-inside:avoid} th{background:#e6e6e6}")
            append(".rec{border:1px solid #000;padding:6px 8px;margin-bottom:8px;page-break-inside:avoid} .rec div{margin:1px 0} .lbl{display:inline-block;width:150px;font-weight:bold}")
            append("</style></head><body>")
            append("<h2>Publisher Records</h2><div class=\"meta\">").append(e(congregationName)).append(" · ").append(e(reportDate()))
            if (filterText.isNotBlank()) append(" · ").append(e(filterText))
            append(" · Records: ").append(data.size).append("</div>")
            if (format == PublisherPrintFormat.TABLE) {
                append("<table><thead><tr><th>#</th><th>Publisher Name</th><th>Gender</th><th>Birthdate</th><th>Age</th><th>Baptismal Date</th><th>Baptismal Age</th><th>Group</th><th>Status</th></tr></thead><tbody>")
                data.forEach { l ->
                    append("<tr><td>").append(l.number).append("</td><td>").append(e(l.name)).append("</td><td>").append(e(l.gender)).append("</td><td>")
                        .append(e(PersonDates.format(l.birthdate))).append("</td><td>").append(e(yearsOrBlank(l.age))).append("</td><td>")
                        .append(e(PersonDates.format(l.baptismalDate))).append("</td><td>").append(e(yearsOrBlank(l.baptismalAge))).append("</td><td>")
                        .append(e(l.group)).append("</td><td>").append(e(l.status)).append("</td></tr>")
                }
                append("</tbody></table>")
            } else {
                data.forEach { l ->
                    append("<div class=\"rec\"><b>PUBLISHER RECORD</b>")
                    fun row(label: String, value: String) { append("<div><span class=\"lbl\">").append(e(label)).append(":</span>").append(e(value)).append("</div>") }
                    row("Name", l.name); row("Gender", l.gender)
                    row("Birthdate", PersonDates.format(l.birthdate)); row("Age", yearsOrBlank(l.age))
                    row("Baptismal Date", PersonDates.format(l.baptismalDate)); row("Baptismal Age", yearsOrBlank(l.baptismalAge))
                    row("Field Service Group", l.group); row("Status", l.status); row("Remarks", l.remarks)
                    append("</div>")
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

    fun sharePdf(context: Context, rows: List<PublisherRow>, congregationName: String, filterText: String, format: PublisherPrintFormat) {
        val data = lines(rows)
        val landscape = format == PublisherPrintFormat.TABLE
        val pageW = if (landscape) 842 else 595
        val pageH = if (landscape) 595 else 842
        val margin = 32f
        val contentW = pageW - margin * 2
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 9f }
        val boldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 9f; typeface = Typeface.DEFAULT_BOLD }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 16f; typeface = Typeface.DEFAULT_BOLD }
        val grayPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 9f }
        val linePaint = Paint().apply { color = Color.BLACK; strokeWidth = 0.6f; style = Paint.Style.STROKE }
        val headFill = Paint().apply { color = Color.rgb(230, 230, 230); style = Paint.Style.FILL }

        fun layout(text: String, paint: TextPaint, width: Float) =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(10)).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()

        // Each page is a list of draw actions; page numbers are added at the end once the total is known.
        class PageDraw(val actions: MutableList<(android.graphics.Canvas) -> Unit> = mutableListOf())
        val pages = mutableListOf<PageDraw>()
        var page = PageDraw().also { pages += it }
        var y = margin

        fun header(first: Boolean) {
            if (first) {
                val top = y
                page.actions += { c -> c.drawText("Publisher Records", margin, top + 14f, titlePaint) }
                y += 22f
                val sub = listOf(congregationName, reportDate(), filterText.takeIf { it.isNotBlank() }, "Records: ${data.size}").filterNotNull().joinToString("  ·  ")
                val subLayout = layout(sub, grayPaint, contentW)
                val subY = y
                page.actions += { c -> c.save(); c.translate(margin, subY); subLayout.draw(c); c.restore() }
                y += subLayout.height + 8f
            }
        }

        fun newPage(afterBreak: () -> Unit = {}) {
            page = PageDraw().also { pages += it }
            y = margin
            afterBreak()
        }

        header(first = true)
        if (format == PublisherPrintFormat.TABLE) {
            val titles = listOf("#", "Publisher Name", "Gender", "Birthdate", "Age", "Baptismal Date", "Baptismal Age", "Group", "Status")
            val weights = floatArrayOf(28f, 190f, 55f, 95f, 40f, 95f, 60f, 110f, 105f)
            val scale = contentW / weights.sum()
            val widths = weights.map { it * scale }
            fun drawHeaderRow() {
                val top = y
                val h = 18f
                page.actions += { c ->
                    c.drawRect(margin, top, margin + contentW, top + h, headFill)
                    var x = margin
                    titles.forEachIndexed { i, t ->
                        c.drawRect(x, top, x + widths[i], top + h, linePaint)
                        c.drawText(t, x + 3f, top + 12f, boldPaint)
                        x += widths[i]
                    }
                }
                y += h
            }
            drawHeaderRow()
            data.forEach { l ->
                val cells = listOf(
                    l.number.toString(), l.name, l.gender, PersonDates.format(l.birthdate), yearsOrBlank(l.age),
                    PersonDates.format(l.baptismalDate), yearsOrBlank(l.baptismalAge), l.group, l.status,
                )
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
        } else {
            data.forEach { l ->
                val rowsOut = listOf(
                    "Name" to l.name, "Gender" to l.gender, "Birthdate" to PersonDates.format(l.birthdate), "Age" to yearsOrBlank(l.age),
                    "Baptismal Date" to PersonDates.format(l.baptismalDate), "Baptismal Age" to yearsOrBlank(l.baptismalAge),
                    "Field Service Group" to l.group, "Status" to l.status, "Remarks" to l.remarks,
                )
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

    fun shareExcel(context: Context, rows: List<PublisherRow>, congregationName: String) {
        val data = lines(rows)
        val headers = listOf(
            "#", "Publisher Name", "First Name", "Middle Name", "Last Name", "Gender", "Birthdate", "Age", "Baptismal Date", "Baptismal Age",
            "Field Service Group", "Status", "Contact", "Address", "Remarks",
        )
        val widths = listOf(5, 30, 18, 12, 18, 10, 18, 8, 18, 14, 22, 22, 16, 36, 40)

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun colName(i: Int) = ('A' + i).toString()
        fun str(ref: String, text: String, style: Int = 0) = if (text.isEmpty()) "" else "<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(text)}</t></is></c>"
        fun num(ref: String, v: Number, style: Int = 0) = "<c r=\"$ref\" s=\"$style\"><v>$v</v></c>"
        fun date(ref: String, utcMillis: Long) = num(ref, utcMillis / 86_400_000.0 + 25569.0, 2)

        val sheet = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
            append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
            append("<cols>")
            widths.forEachIndexed { i, w -> append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>") }
            append("</cols><sheetData><row r=\"1\">")
            headers.forEachIndexed { i, h -> append(str("${colName(i)}1", h, 1)) }
            append("</row>")
            data.forEachIndexed { index, l ->
                val r = index + 2
                append("<row r=\"$r\">")
                append(num("A$r", l.number))
                append(str("B$r", l.name)); append(str("C$r", l.first)); append(str("D$r", l.middle)); append(str("E$r", l.last)); append(str("F$r", l.gender))
                l.birthdate?.let { append(date("G$r", it)) }
                l.age?.let { append(num("H$r", it)) }
                l.baptismalDate?.let { append(date("I$r", it)) }
                l.baptismalAge?.let { append(num("J$r", it)) }
                append(str("K$r", l.group)); append(str("L$r", l.status)); append(str("M$r", l.contact)); append(str("N$r", l.address, 3)); append(str("O$r", l.remarks, 3))
                append("</row>")
            }
            val sum = com.emfitsolutions.gopreach.ui.screens.publishers.PublisherSummary.rows(rows)
            var sr = data.size + 3
            append("<row r=\"$sr\">"); append(str("B$sr", "Summary", 1)); append("</row>")
            sum.drop(1).forEach { r ->
                sr++
                append("<row r=\"$sr\">"); append(str("B$sr", r[0])); append(str("C$sr", r[1])); append("</row>")
            }
            append("</sheetData></worksheet>")
        }
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

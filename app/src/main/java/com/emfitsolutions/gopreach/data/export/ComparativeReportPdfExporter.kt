package com.emfitsolutions.gopreach.data.export

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.emfitsolutions.gopreach.ui.screens.planner.ComparativeMonthRow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Allow printing and exporting" (My Planner Comparative Report spec) — a
 * one-page PDF with the same month-by-month table and color-coded line
 * graph the on-screen report shows, using Android's own [PdfDocument] (no
 * new dependency), then handed to the share sheet the same
 * FileProvider/cacheDir way [BibleTextExporter.share] already does, so
 * "Print" is just whatever the Publisher's share targets offer (a print
 * service, Drive, email, ...) rather than a separate print pipeline.
 */
object ComparativeReportPdfExporter {
    private const val PAGE_WIDTH = 842 // A4 landscape @ 72dpi
    private const val PAGE_HEIGHT = 595
    private val monthFormat = SimpleDateFormat("MMM yyyy", Locale.getDefault())

    private val hoursColor = Color.parseColor("#1E88E5")
    private val returnVisitsColor = Color.parseColor("#2E7D32")
    private val bibleStudiesColor = Color.parseColor("#8E24AA")

    fun export(context: Context, rows: List<ComparativeMonthRow>) {
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create())
        val canvas = page.canvas

        val titlePaint = Paint().apply { color = Color.BLACK; textSize = 20f; isFakeBoldText = true }
        canvas.drawText("Comparative Report", 32f, 40f, titlePaint)

        val subtitlePaint = Paint().apply { color = Color.DKGRAY; textSize = 12f }
        if (rows.isNotEmpty()) {
            canvas.drawText(
                "${monthFormat.format(Date(rows.first().monthStart))} – ${monthFormat.format(Date(rows.last().monthStart))}",
                32f, 60f, subtitlePaint,
            )
        }

        drawLegend(canvas, 32f, 80f)
        drawChart(canvas, rows, top = 100f, height = 220f, left = 32f, right = PAGE_WIDTH - 32f)
        drawTable(canvas, rows, top = 340f, left = 32f)

        document.finishPage(page)

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "gopreach-comparative-report-${System.currentTimeMillis()}.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share or Print Comparative Report"))
    }

    /** Two-range comparison: one page per range (titled with the actual dates and its totals), same layout as [export]. */
    fun exportPeriods(context: Context, labelA: String, rowsA: List<ComparativeMonthRow>, labelB: String, rowsB: List<ComparativeMonthRow>) {
        val document = PdfDocument()
        listOf(labelA to rowsA, labelB to rowsB).forEachIndexed { index, (subtitle, rows) ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create())
            val canvas = page.canvas
            canvas.drawText("Comparative Report", 32f, 40f, Paint().apply { color = Color.BLACK; textSize = 20f; isFakeBoldText = true })
            val subtitlePaint = Paint().apply { color = Color.DKGRAY; textSize = 12f }
            canvas.drawText(subtitle, 32f, 60f, subtitlePaint)
            val minutes = rows.sumOf { it.totalMinutes }
            canvas.drawText(
                "Total: ${minutes / 60}h ${minutes % 60}m · Return Visits ${rows.sumOf { it.returnVisitCount }} · Bible Studies ${rows.sumOf { it.bibleStudyCount }}",
                32f, 76f, subtitlePaint,
            )
            drawLegend(canvas, 32f, 96f)
            drawChart(canvas, rows, top = 112f, height = 210f, left = 32f, right = PAGE_WIDTH - 32f)
            drawTable(canvas, rows, top = 345f, left = 32f)
            document.finishPage(page)
        }
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "gopreach-comparative-periods-${System.currentTimeMillis()}.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share or Print Comparative Report"))
    }

    private fun drawLegend(canvas: android.graphics.Canvas, left: Float, top: Float) {
        val labelPaint = Paint().apply { color = Color.BLACK; textSize = 11f }
        var x = left
        listOf("Hours" to hoursColor, "Return Visits" to returnVisitsColor, "Bible Studies" to bibleStudiesColor).forEach { (label, color) ->
            val swatchPaint = Paint().apply { this.color = color }
            canvas.drawRect(x, top - 9f, x + 10f, top + 1f, swatchPaint)
            canvas.drawText(label, x + 14f, top, labelPaint)
            x += 14f + labelPaint.measureText(label) + 20f
        }
    }

    /** Each series is normalized to its own max (0..1) so Hours, Return
     * Visits and Bible Studies — wildly different scales — are all readable
     * on one chart, matching the on-screen animated line graph exactly. */
    private fun drawChart(canvas: android.graphics.Canvas, rows: List<ComparativeMonthRow>, top: Float, height: Float, left: Float, right: Float) {
        if (rows.size < 2) return
        val bottom = top + height
        val axisPaint = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }
        canvas.drawLine(left, bottom, right, bottom, axisPaint)

        val hoursSeries = rows.map { it.totalMinutes / 60f }
        val rvSeries = rows.map { it.returnVisitCount.toFloat() }
        val bsSeries = rows.map { it.bibleStudyCount.toFloat() }

        drawSeries(canvas, hoursSeries, left, right, top, bottom, hoursColor)
        drawSeries(canvas, rvSeries, left, right, top, bottom, returnVisitsColor)
        drawSeries(canvas, bsSeries, left, right, top, bottom, bibleStudiesColor)

        val labelPaint = Paint().apply { color = Color.DKGRAY; textSize = 9f; textAlign = Paint.Align.CENTER }
        val step = (right - left) / (rows.size - 1).coerceAtLeast(1)
        rows.forEachIndexed { index, row ->
            canvas.drawText(monthFormat.format(Date(row.monthStart)), left + step * index, bottom + 14f, labelPaint)
        }
    }

    private fun drawSeries(canvas: android.graphics.Canvas, series: List<Float>, left: Float, right: Float, top: Float, bottom: Float, color: Int) {
        val max = series.maxOrNull()?.takeIf { it > 0f } ?: 1f
        val paint = Paint().apply { this.color = color; strokeWidth = 2.5f; style = Paint.Style.STROKE; isAntiAlias = true }
        val dotPaint = Paint().apply { this.color = color; isAntiAlias = true }
        val step = (right - left) / (series.size - 1).coerceAtLeast(1)
        var previousX = left
        var previousY = bottom - (series[0] / max) * (bottom - top)
        canvas.drawCircle(previousX, previousY, 3f, dotPaint)
        for (i in 1 until series.size) {
            val x = left + step * i
            val y = bottom - (series[i] / max) * (bottom - top)
            canvas.drawLine(previousX, previousY, x, y, paint)
            canvas.drawCircle(x, y, 3f, dotPaint)
            previousX = x
            previousY = y
        }
    }

    private fun drawTable(canvas: android.graphics.Canvas, rows: List<ComparativeMonthRow>, top: Float, left: Float) {
        val headerPaint = Paint().apply { color = Color.BLACK; textSize = 11f; isFakeBoldText = true }
        val cellPaint = Paint().apply { color = Color.DKGRAY; textSize = 10f }
        val columnWidth = 130f
        val rowHeight = 18f

        val headers = listOf("Month", "Hours", "Return Visits", "Bible Studies")
        headers.forEachIndexed { index, header -> canvas.drawText(header, left + columnWidth * index, top, headerPaint) }

        rows.forEachIndexed { rowIndex, row ->
            val y = top + rowHeight * (rowIndex + 1)
            val hours = row.totalMinutes / 60
            val minutes = row.totalMinutes % 60
            val cells = listOf(monthFormat.format(Date(row.monthStart)), "${hours}h ${minutes}m", row.returnVisitCount.toString(), row.bibleStudyCount.toString())
            cells.forEachIndexed { colIndex, cell -> canvas.drawText(cell, left + columnWidth * colIndex, y, cellPaint) }
        }
    }
}

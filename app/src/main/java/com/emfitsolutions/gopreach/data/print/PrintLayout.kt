package com.emfitsolutions.gopreach.data.print

import android.content.Context
import android.print.PrintAttributes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Paper sizes GoPreach users print on. The system print dialog (which doubles as the print preview) can still change it per print. */
/** Android's media size for a paper size and orientation. */
fun PaperSize.mediaSize(landscape: Boolean): PrintAttributes.MediaSize {
    val media = when (this) {
        PaperSize.A4 -> PrintAttributes.MediaSize.ISO_A4
        PaperSize.LETTER -> PrintAttributes.MediaSize.NA_LETTER
        PaperSize.LEGAL -> PrintAttributes.MediaSize.NA_LEGAL
    }
    return if (landscape) media.asLandscape() else media.asPortrait()
}

/** Letter where Letter is the norm (the Philippines, US, Canada, Mexico), A4 elsewhere. */
fun defaultPaperSizeFor(locale: Locale): PaperSize = when (locale.country) {
    "PH", "US", "CA", "MX" -> PaperSize.LETTER
    else -> PaperSize.A4
}

/** The user's saved print settings (Settings → Printing). Per device, like the theme. */
class PrintPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("gopreach_settings", Context.MODE_PRIVATE)

    var paperSize: PaperSize
        get() = runCatching { PaperSize.valueOf(prefs.getString(KEY_PAPER, null)!!) }.getOrDefault(defaultPaperSizeFor(Locale.getDefault()))
        set(value) { prefs.edit().putString(KEY_PAPER, value.name).apply() }

    var orientation: OrientationMode
        get() = runCatching { OrientationMode.valueOf(prefs.getString(KEY_ORIENTATION, null)!!) }.getOrDefault(OrientationMode.AUTO)
        set(value) { prefs.edit().putString(KEY_ORIENTATION, value.name).apply() }

    private companion object {
        const val KEY_PAPER = "print_paper_size"
        const val KEY_ORIENTATION = "print_orientation"
    }
}

/**
 * The one print layout every GoPreach report goes through ([ReportPrinter]). It owns the paper size, orientation,
 * margins, type scale, spacing, page-break rules and the standard report header, so no individual report decides
 * those for itself. The layout is adjusted to the content (columns, text length) rather than shrinking everything:
 * readable first, then organised, then compact, then fits the paper.
 */
object PrintLayout {

    /** Margins in thousandths of an inch (about 10 mm): tight enough to use the page, wide enough to print safely. */
    private const val MARGIN_MILS = 394

    /** Rough number of characters that fit across a page at body size, by orientation. */
    private const val PORTRAIT_CAPACITY = 95.0
    private const val LANDSCAPE_CAPACITY = 140.0

    private val numeric = Regex("^[-+()$₱%\\s]*[0-9][0-9.,:/%\\s-]*$")

    fun attributes(context: Context, landscape: Boolean): PrintAttributes =
        PrintAttributes.Builder()
            .setMediaSize(PrintPreferences(context).paperSize.mediaSize(landscape))
            .setMinMargins(PrintAttributes.Margins(MARGIN_MILS, MARGIN_MILS, MARGIN_MILS, MARGIN_MILS))
            .build()

    /** The orientation to print in: the report's own choice, else the user's, else whichever fits the content. */
    fun isLandscape(context: Context, options: PrintOptions, estimatedWidthChars: Double? = null): Boolean {
        val mode = options.orientation ?: PrintPreferences(context).orientation.takeIf { it != OrientationMode.AUTO } ?: OrientationMode.AUTO
        return when (mode) {
            OrientationMode.PORTRAIT -> false
            OrientationMode.LANDSCAPE -> true
            OrientationMode.AUTO -> (estimatedWidthChars ?: 0.0) > PORTRAIT_CAPACITY
        }
    }

    /**
     * Page rules for any report's HTML — applied to every report, including ones with their own bespoke layout.
     * They only prevent bad breaks; they never restyle a report: table headers repeat on every page, a row is never
     * split, headings stay with what follows them, and blocks marked keep-together stay whole.
     */
    const val PAGE_RULES_CSS = "" +
        "thead{display:table-header-group} tfoot{display:table-footer-group} " +
        "tr,img,.keep{page-break-inside:avoid;break-inside:avoid} " +
        "h1,h2,h3,h4{page-break-after:avoid;break-after:avoid} " +
        "*{-webkit-print-color-adjust:exact;print-color-adjust:exact} "

    /** Adds [PAGE_RULES_CSS] to a finished HTML document without touching its own styles. */
    fun withPageRules(html: String): String {
        val tag = "<style>$PAGE_RULES_CSS</style>"
        val at = html.indexOf("</head>", ignoreCase = true)
        return if (at >= 0) html.substring(0, at) + tag + html.substring(at) else tag + html
    }

    private fun e(s: String) = ReportPrinter.escapeHtml(s)

    /** A compact, professional document for a [ReportTable]. Returns the HTML and the orientation it was laid out for. */
    fun tableDocument(context: Context, table: ReportTable, options: PrintOptions): Pair<String, Boolean> {
        val width = estimatedWidthChars(table)
        val landscape = isLandscape(context, options, width)
        val fontPt = fontSizePt(width, if (landscape) LANDSCAPE_CAPACITY else PORTRAIT_CAPACITY)
        val generated = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date())

        val html = buildString {
            append("<html><head><meta charset=\"utf-8\"><style>")
            // Page numbers in the footer where the print engine supports page margin boxes; otherwise ignored.
            append("@page{@bottom-center{content:\"Page \" counter(page) \" of \" counter(pages);font-size:8pt;color:#555}} ")
            append("body{font-family:Arial,Helvetica,sans-serif;font-size:${fontPt}pt;line-height:1.25;color:#111;margin:0} ")
            append(".head{margin:0 0 6px 0;text-align:center} ")
            append(".head h1{font-size:${fontPt + 3.5}pt;margin:0;font-weight:bold;letter-spacing:.3px} ")
            append(".head .sub{font-size:${fontPt - 1}pt;color:#444;margin-top:1px} ")
            append(".head .count{font-size:${fontPt}pt;font-weight:bold;margin-top:2px} ")
            append("table{width:100%;border-collapse:collapse;table-layout:auto} ")
            append("th,td{border:.5pt solid #555;padding:2pt 4pt;vertical-align:top;word-wrap:break-word;overflow-wrap:anywhere} ")
            append("th{background:#e6e6e6;font-weight:bold;text-align:left} ")
            append("td.n,th.n{text-align:right;white-space:nowrap} ")
            append("tbody tr:nth-child(even) td{background:#f6f6f6} ")
            append(".totals{margin-top:6px;font-weight:bold} .totals span{display:inline-block;margin-right:14px} ")
            append(".signatures{margin-top:14px;display:flex;gap:24px} .signatures div{flex:1;border-top:.6pt solid #111;padding-top:2px;text-align:center;font-size:${fontPt - 1}pt} ")
            append("</style></head><body>")

            append("<div class=\"head\"><h1>").append(e(table.title)).append("</h1>")
            val sub = listOfNotNull(table.subtitle?.takeIf { it.isNotBlank() }, "Generated: $generated").joinToString("  •  ")
            append("<div class=\"sub\">").append(e(sub)).append("</div>")
            // The record count, right under the title — a single compact line.
            table.countText?.let { append("<div class=\"count\">").append(e(it)).append("</div>") }
            append("</div>")

            val numericColumns = table.columns.indices.map { c ->
                val values = table.rows.mapNotNull { it.getOrNull(c) }.filter { it.isNotBlank() }
                values.isNotEmpty() && values.all { numeric.matches(it) }
            }
            append("<table><thead><tr>")
            table.columns.forEachIndexed { i, col -> append(if (numericColumns[i]) "<th class=\"n\">" else "<th>").append(e(col)).append("</th>") }
            append("</tr></thead><tbody>")
            table.rows.forEach { row ->
                append("<tr>")
                table.columns.indices.forEach { c ->
                    append(if (numericColumns[c]) "<td class=\"n\">" else "<td>").append(e(row.getOrNull(c).orEmpty())).append("</td>")
                }
                append("</tr>")
            }
            append("</tbody></table>")
            if (table.totals.isNotEmpty()) {
                append("<div class=\"totals keep\">")
                table.totals.forEach { (label, value) -> append("<span>").append(e(label)).append(": ").append(e(value)).append("</span>") }
                append("</div>")
            }
            if (table.signatureLabels.isNotEmpty()) {
                // The whole signature block stays together; if it doesn't fit, all of it moves to the next page.
                append("<div class=\"signatures keep\">")
                table.signatureLabels.forEach { append("<div>").append(e(it)).append("</div>") }
                append("</div>")
            }
            append("</body></html>")
        }
        return html to landscape
    }

    /** Approximate width of the table in characters: each column as wide as its longest typical cell (capped). */
    private fun estimatedWidthChars(table: ReportTable): Double =
        table.columns.indices.sumOf { c ->
            val longest = (table.rows.take(200).mapNotNull { it.getOrNull(c)?.length } + table.columns[c].length).maxOrNull() ?: 4
            longest.coerceIn(4, 28) + 2
        }.toDouble()

    /** Body size in points: as large as comfortably fits, never below 8 pt so it stays readable. */
    private fun fontSizePt(widthChars: Double, capacity: Double): Double = when (widthChars / capacity) {
        in 0.0..0.65 -> 10.5
        in 0.65..0.85 -> 10.0
        in 0.85..1.0 -> 9.5
        in 1.0..1.25 -> 9.0
        in 1.25..1.5 -> 8.5
        else -> 8.0
    }
}

package com.emfitsolutions.gopreach.data.print

import android.content.Context
import android.print.PrintManager
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

private const val TAG = "ReportPrinter"

/**
 * A generic printable report table — every report screen builds one of
 * these (its own title/heading, column headers, row cells, and any summary
 * totals) and hands it to [ReportPrinter.print]; nothing here is coupled to
 * any one screen's own data class, so it's reusable across every "make all
 * reports have a print preview" report in the app.
 */
data class ReportTable(
    val title: String,
    val columns: List<String>,
    val rows: List<List<String>>,
    /** Rendered as its own small summary block below the table — e.g.
     * "Total Bible Study" to "12". */
    val totals: List<Pair<String, String>> = emptyList(),
    /** Reporting period / congregation, shown under the title ("October 2026 • Solano Tagalog Congregation"). */
    val subtitle: String? = null,
    /** Names under signature lines ("Prepared by", "Noted by"); the block is kept together on one page. */
    val signatureLabels: List<String> = emptyList(),
    /**
     * How many records this report covers — taken by each report from the same filtered data it lists (never
     * deleted records, never anything outside the selected congregation / filters). Shown under the title when
     * printed or saved as PDF and in [shareText]. Null for a report that is a set of figures rather than a list.
     */
    val count: Int? = null,
    /** What is being counted, e.g. "Total Publishers" or "Total Bible Studies". */
    val countLabel: String = "Total Records",
) {
    /** "Total Publishers: 35", or null when the report has no record count. */
    val countText: String? get() = count?.let { "$countLabel: $it" }

    /** A concise plain-text version for Send As Text: title, period, count. */
    fun shareText(): String = listOfNotNull(title, subtitle?.takeIf { it.isNotBlank() }, countText).joinToString("\n")
}

/**
 * "Make all reports have a print preview" — Android's own [PrintManager] +
 * a throwaway [WebView], no third-party PDF library needed. Every Android
 * print dialog shows its own print preview (the real pages, paper size,
 * orientation and margins, re-laid-out when any of them change) before
 * anything is sent anywhere, and offers "Save as PDF" out of the box.
 *
 * Every report — table-based or bespoke HTML — prints through here, and so through [PrintLayout]: one place
 * decides paper size, orientation, margins, type scale, spacing and page-break rules.
 */
object ReportPrinter {

    /** Holds every in-flight [WebView] until its print hand-off completes —
     * a WebView that's never attached to any view hierarchy is otherwise
     * unreachable from GC roots the moment [printHtml] returns, so the print
     * dialog could silently never appear. */
    private val inFlightWebViews = mutableSetOf<WebView>()

    /** Prints a [ReportTable] in the standard compact layout. */
    fun print(context: Context, table: ReportTable, options: PrintOptions = PrintOptions()) {
        val (html, landscape) = PrintLayout.tableDocument(context, table, options)
        handOff(context, table.title, html, landscape)
    }

    /**
     * Prints a report that builds its own HTML. The central page rules (repeating table headers, no split rows,
     * headings kept with their content) are added, and the paper size / margins / orientation come from
     * [PrintLayout]. Pass `PrintOptions(LANDSCAPE)` for a wide sheet.
     */
    fun printHtml(context: Context, title: String, html: String, options: PrintOptions = PrintOptions()) {
        handOff(context, title, PrintLayout.withPageRules(html), PrintLayout.isLandscape(context, options))
    }

    private fun handOff(context: Context, title: String, html: String, landscape: Boolean) {
        val webView = WebView(context)
        inFlightWebViews += webView
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                try {
                    val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                    if (printManager == null) {
                        Log.e(TAG, "PRINT_SERVICE unavailable on this device")
                        Toast.makeText(context, "Printing isn't available on this device.", Toast.LENGTH_LONG).show()
                        return
                    }
                    val adapter = view.createPrintDocumentAdapter(title)
                    printManager.print(title, adapter, PrintLayout.attributes(context, landscape))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open print dialog", e)
                    Toast.makeText(context, "Couldn't open the print dialog: ${e.localizedMessage ?: "unknown error"}", Toast.LENGTH_LONG).show()
                } finally {
                    inFlightWebViews -= webView
                }
            }

            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                Log.e(TAG, "WebView failed to load report HTML: $description")
                Toast.makeText(context, "Couldn't prepare the report for printing.", Toast.LENGTH_LONG).show()
                inFlightWebViews -= webView
            }
        }
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    /** Not private — reused by any caller of [printHtml] building its own
     * bespoke HTML so every print path escapes user-entered text the same way. */
    fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

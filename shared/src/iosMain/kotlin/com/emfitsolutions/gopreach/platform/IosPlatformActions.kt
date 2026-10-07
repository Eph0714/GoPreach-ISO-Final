package com.emfitsolutions.gopreach.platform

import com.emfitsolutions.gopreach.data.print.OrientationMode
import com.emfitsolutions.gopreach.data.print.PrintOptions
import com.emfitsolutions.gopreach.data.print.ReportTable
import com.emfitsolutions.gopreach.data.print.escapeHtml
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.setValue
import platform.Foundation.writeToURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIMarkupTextPrintFormatter
import platform.UIKit.UIPrintInfo
import platform.UIKit.UIPrintInteractionController
import platform.UIKit.popoverPresentationController

/**
 * Print, CSV export and "open file" on iOS. Printing uses the system print sheet (AirPrint, with its own preview and
 * "Save as PDF"); opening a file uses the share sheet, which offers Files, Numbers, Excel, Mail and any other app that
 * can take it.
 */
class IosPlatformActions : PlatformActions {

    override fun print(table: ReportTable, options: PrintOptions) = printHtml(table.title, tableHtml(table), options)

    override fun printHtml(title: String, html: String, options: PrintOptions) {
        val info = UIPrintInfo.printInfo()
        info.jobName = title
        // UIPrintInfoOrientation: portrait = 0, landscape = 1 (the typed constants are not exposed to Kotlin).
        info.setValue(if (options.orientation == OrientationMode.LANDSCAPE) 1L else 0L, forKey = "orientation")
        val controller = UIPrintInteractionController.sharedPrintController
        controller.printInfo = info
        controller.printFormatter = UIMarkupTextPrintFormatter(markupText = html)
        controller.presentAnimated(true, completionHandler = null)
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun writeCsv(uri: String, table: ReportTable): Boolean {
        val url = NSURL(string = uri)
        return (csv(table) as NSString).writeToURL(url, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    override fun openFile(uri: String, mimeType: String) {
        val url = NSURL(string = uri)
        val presenter = topViewController() ?: return
        val share = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
        // On iPad the share sheet is a popover and needs an anchor.
        share.popoverPresentationController?.sourceView = presenter.view
        presenter.presentViewController(share, animated = true, completion = null)
    }

    private fun cell(value: String) = "\"" + value.replace("\"", "\"\"") + "\""

    private fun csv(table: ReportTable): String = buildString {
        append(cell(table.title)).append('\n')
        table.subtitle?.let { append(cell(it)).append('\n') }
        append('\n')
        append(table.columns.joinToString(",") { cell(it) }).append('\n')
        table.rows.forEach { row -> append(row.joinToString(",") { cell(it) }).append('\n') }
        if (table.totals.isNotEmpty()) {
            append('\n')
            table.totals.forEach { (label, value) -> append(cell(label)).append(',').append(cell(value)).append('\n') }
        }
    }

    private fun tableHtml(table: ReportTable): String = buildString {
        append("<html><head><meta charset=\"utf-8\"><style>")
        append("body{font-family:-apple-system,Helvetica,Arial,sans-serif;font-size:11pt;}")
        append("table{border-collapse:collapse;width:100%;}th,td{border:1px solid #999;padding:4px 6px;text-align:left;}")
        append("th{background:#eee;}.sig{display:inline-block;width:40%;margin-top:48px;border-top:1px solid #000;text-align:center;}")
        append("</style></head><body>")
        append("<h2>").append(escapeHtml(table.title)).append("</h2>")
        table.subtitle?.takeIf { it.isNotBlank() }?.let { append("<p>").append(escapeHtml(it)).append("</p>") }
        table.countText?.let { append("<p><b>").append(escapeHtml(it)).append("</b></p>") }
        append("<table><tr>")
        table.columns.forEach { append("<th>").append(escapeHtml(it)).append("</th>") }
        append("</tr>")
        table.rows.forEach { row ->
            append("<tr>")
            row.forEach { append("<td>").append(escapeHtml(it)).append("</td>") }
            append("</tr>")
        }
        append("</table>")
        table.totals.forEach { (label, value) -> append("<p><b>").append(escapeHtml(label)).append(":</b> ").append(escapeHtml(value)).append("</p>") }
        table.signatureLabels.forEach { append("<div class=\"sig\">").append(escapeHtml(it)).append("</div> ") }
        append("</body></html>")
    }
}

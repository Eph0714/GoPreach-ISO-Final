package com.emfitsolutions.gopreach.data.print

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.emfitsolutions.gopreach.data.export.CsvExporter
import com.emfitsolutions.gopreach.platform.PlatformActions

/** Android implementation of [PlatformActions]: system print dialog, content-resolver CSV writes, "open with" chooser. */
class AndroidPlatformActions(private val context: Context) : PlatformActions {
    override fun print(table: ReportTable, options: PrintOptions) = ReportPrinter.print(context, table, options)

    override fun printHtml(title: String, html: String, options: PrintOptions) = ReportPrinter.printHtml(context, title, html, options)

    override fun writeCsv(uri: String, table: ReportTable): Boolean =
        CsvExporter.write(context, Uri.parse(uri), table.title, subtitle = null, columns = table.columns, rows = table.rows, totals = table.totals, recordCount = table.countLabel to (table.count ?: table.rows.size))

    override fun openFile(uri: String, mimeType: String) = CsvExporter.openWithChooser(context, Uri.parse(uri), mimeType)
}

@Composable
fun rememberAndroidPlatformActions(): PlatformActions {
    val context = LocalContext.current
    return remember(context) { AndroidPlatformActions(context) }
}

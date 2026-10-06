package com.emfitsolutions.gopreach.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.emfitsolutions.gopreach.data.print.PrintOptions
import com.emfitsolutions.gopreach.data.print.ReportTable

/**
 * Things a screen asks the device to do that look different on every platform: print a report, save a CSV into a file the user
 * picked, open a file in another app. [uri] values are platform URI strings (content://, file://, https://).
 */
interface PlatformActions {
    fun print(table: ReportTable, options: PrintOptions = PrintOptions())
    fun printHtml(title: String, html: String, options: PrintOptions = PrintOptions())

    /** Writes [table] as CSV into [uri] (chosen with the system file picker). Returns false if the file could not be opened. */
    fun writeCsv(uri: String, table: ReportTable): Boolean

    /** Opens [uri] with a chooser of apps that can show a file of [mimeType]. */
    fun openFile(uri: String, mimeType: String)
}

/** Provided once at the app root by each platform host (MainActivity on Android, the Compose view controller on iOS). */
val LocalPlatformActions = staticCompositionLocalOf<PlatformActions> { error("PlatformActions not provided") }

@Composable
fun rememberPlatformActions(): PlatformActions = LocalPlatformActions.current

package com.emfitsolutions.gopreach.data.export

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * "Export as ... excel" — plain CSV, which opens directly in Excel/Sheets/
 * any spreadsheet app and needs no new dependency (a real .xlsx would need
 * a whole spreadsheet-writing library this app doesn't otherwise use). Same
 * shape [com.emfitsolutions.gopreach.ui.screens.reports.writeReportsCsv]
 * already used for its own report, generalized so every report screen can
 * share it instead of hand-rolling its own CSV string-builder.
 *
 * Bug fix ("I cannot see any PDF or Excel inside the Reports Summary"): the
 * Storage Access Framework picker this feeds from (`CreateDocument`) saves
 * the file into whatever folder the user chose — Downloads, Drive, a
 * dedicated folder — and simply closes; nothing about that flow shows the
 * result inside the app itself. With zero success/failure feedback (the old
 * [write] returned `Unit` and silently no-op'd if [Context.getContentResolver]
 * .openOutputStream] ever returned null), a failed write and a successful
 * one looked identical from the user's side: the picker closes either way,
 * and nothing else happens. [write] now reports whether it actually wrote
 * anything, and [openWithChooser] immediately opens the just-saved file so
 * the user *sees* it appear on screen instead of having to go hunt for it
 * in a file manager afterward.
 */
object CsvExporter {

    /** @return true if the file was actually written, false if the system
     * couldn't open a stream for [uri] (rare, but not impossible — e.g. a
     * document provider that rejected the write). Throws on a genuine I/O
     * failure (permission revoked mid-write, disk full, etc.) — the caller
     * is expected to catch that and tell the user, not let it disappear. */
    fun write(
        context: Context,
        uri: Uri,
        title: String,
        subtitle: String?,
        columns: List<String>,
        rows: List<List<String>>,
        totals: List<Pair<String, String>> = emptyList(),
    ): Boolean {
        val csv = buildString {
            append(escapeCsvCell(title)).append('\n')
            if (subtitle != null) append(escapeCsvCell(subtitle)).append('\n')
            append('\n')
            append(columns.joinToString(",") { escapeCsvCell(it) }).append('\n')
            rows.forEach { row -> append(row.joinToString(",") { escapeCsvCell(it) }).append('\n') }
            if (totals.isNotEmpty()) {
                append('\n')
                totals.forEach { (label, value) -> append(escapeCsvCell(label)).append(',').append(escapeCsvCell(value)).append('\n') }
            }
        }
        val stream = context.contentResolver.openOutputStream(uri) ?: return false
        stream.use { it.write(csv.toByteArray()) }
        return true
    }

    /** Opens [uri] immediately after a successful export/print-to-PDF, via
     * whatever app the device already has for [mimeType] (a spreadsheet app
     * for CSV, a PDF viewer for PDF) — a plain `Toast` fallback if nothing
     * on the device can open it, rather than a dead tap. */
    fun openWithChooser(context: Context, uri: Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(Intent.createChooser(intent, "Open exported file").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Saved, but no app on this device can open it.", Toast.LENGTH_LONG).show()
        }
    }

    private fun escapeCsvCell(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}

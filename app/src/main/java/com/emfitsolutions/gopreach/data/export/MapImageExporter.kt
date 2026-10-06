package com.emfitsolutions.gopreach.data.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.view.View
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * "Add a print in every map" — exports whatever a map [View] (osmdroid's own
 * `MapView`, same real Android `View` every [com.emfitsolutions.gopreach.ui
 * .components.map.OsmBoundaryMap] call site already holds) is currently
 * showing as a PNG image or a single-page PDF, then hands it to the system
 * share sheet — same cacheDir/exports/ + FileProvider pattern
 * [BoundaryKmlExporter] already uses, so both share one file_paths.xml entry
 * and one mental model for "export a map/boundary" across this app. Captures
 * only the native `View` itself (the real map tiles, boundary lines,
 * markers) — Compose-drawn chrome layered on top (style picker, distance
 * banner) is a separate layer `View.draw` never touches, which is actually
 * the wanted result: a clean map image, not a screenshot with UI controls
 * baked in.
 */
object MapImageExporter {

    private fun captureBitmap(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        return bitmap
    }

    private fun safeFileName(title: String): String = title.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifBlank { "map" }

    /** PNG, shared via [Intent.ACTION_SEND] — [title] names both the export
     * file and the share sheet's own suggested name. */
    fun exportAsImage(context: Context, mapView: View, title: String) =
        exportBitmapAsImage(context, captureBitmap(mapView), title)

    /** Same as [exportAsImage] for a bitmap the caller already has — a GL map
     * (MapLibre) can't be drawn into a Canvas, so it hands over its own
     * `snapshot()` instead. */
    fun exportBitmapAsImage(context: Context, bitmap: Bitmap, title: String) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "${safeFileName(title)}-map.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        share(context, file, "image/png")
    }

    /** Single-page PDF, the captured map image filling the whole page at its
     * own native pixel size (no extra scaling/margins — this is a map, not a
     * document, so there's no "paper size" to fit). */
    fun exportAsPdf(context: Context, mapView: View, title: String) =
        exportBitmapAsPdf(context, captureBitmap(mapView), title)

    fun exportBitmapAsPdf(context: Context, bitmap: Bitmap, title: String) {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
        val page = document.startPage(pageInfo)
        page.canvas.drawBitmap(bitmap, 0f, 0f, null)
        document.finishPage(page)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "${safeFileName(title)}-map.pdf")
        FileOutputStream(file).use { out -> document.writeTo(out) }
        document.close()
        share(context, file, "application/pdf")
    }

    private fun share(context: Context, file: File, mimeType: String) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share map"))
    }
}

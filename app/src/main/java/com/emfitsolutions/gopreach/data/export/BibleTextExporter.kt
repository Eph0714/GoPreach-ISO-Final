package com.emfitsolutions.gopreach.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.emfitsolutions.gopreach.data.model.BibleTextCategory
import com.emfitsolutions.gopreach.data.model.BibleTextRecord
import com.emfitsolutions.gopreach.data.model.SavedVideo
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Marks a JSON file as one this app's own "My Bible Text Record" share/
 * import flow produced — [BibleTextExporter.parseExportJson] refuses
 * anything else (a stray JSON file the user picked by mistake, a future
 * incompatible export shape) rather than silently misreading it as records. */
private const val EXPORT_FILE_TYPE = "gopreach-bible-text-export"

/** The single entry inside the .zip [BibleTextExporter.share] produces —
 * "sharing must be a zip, and import must extract that zip". */
private const val EXPORT_ZIP_ENTRY_NAME = "bible-text-export.json"

/** A distinctive MIME type, not the generic "application/zip" — set on the
 * share [Intent] and matched by [com.emfitsolutions.gopreach.MainActivity]'s
 * own ACTION_VIEW intent-filter, so that when the receiving Publisher taps
 * the downloaded/attached file it opens straight into GoPreach's own import
 * flow ("automatically import to his device") instead of a generic zip/
 * archive app, or a chooser cluttered with every app that can open a zip. */
const val BIBLE_TEXT_EXPORT_MIME_TYPE = "application/vnd.gopreach.bibletext+zip"

/** One portable, publisher-independent Bible Text Record. [eventName]/
 * [themeTopic]/[speaker] — not a category/event id — since an id only means
 * something inside the *exporting* Publisher's own `bibleTextCategories`;
 * the receiving Publisher resolves-or-creates their own Event by this
 * Event+Theme/Topic+Speaker combination on import (see
 * [com.emfitsolutions.gopreach.ui.screens.bibletext.BibleTextRecordViewModel
 * .importRecords]), rather than any id ever crossing Publishers.
 *
 * [themeTopic] keeps its pre-upgrade wire name (`categoryName`, via
 * [SerializedName]) so a file exported by an older app build still imports
 * correctly — that field held exactly this value before this module's
 * Event/Topic restructuring, just under the old flat "Category" name.
 * [eventName]/[speaker] are new and simply come back blank/null from an old
 * file, same "migrate rather than lose data" default this module's own
 * [com.emfitsolutions.gopreach.data.model.BibleTextCategory] uses for a
 * pre-upgrade Event. */
data class ExportedBibleTextRecord(
    @SerializedName("categoryName") val themeTopic: String,
    val eventName: String = "",
    val speaker: String? = null,
    val bibleVersionId: String,
    val languageId: String,
    val bibleBookId: String,
    val chapter: Int,
    val verses: String,
    val remarks: String,
    val videos: List<SavedVideo> = emptyList(),
)

data class BibleTextExportFile(
    val type: String = EXPORT_FILE_TYPE,
    val exportedAt: Long = 0L,
    val records: List<ExportedBibleTextRecord> = emptyList(),
)

/**
 * "Share the My Bible Text Record to other[s]... export or share through
 * Messenger and other platform[s]... the receiving Publisher can import the
 * data" — a plain JSON file (Gson, the same JSON library every other
 * export/backup feature in this app already uses — see BackupRepository),
 * shared through Android's own share sheet so it reaches whatever app the
 * Publisher picks (Messenger, Gmail, Drive, Bluetooth, ...), then re-opened
 * on the receiving device via a plain file picker for import.
 */
object BibleTextExporter {
    private val gson = Gson()

    fun buildExportJson(records: List<BibleTextRecord>, eventsById: Map<String, BibleTextCategory>): String {
        val exported = records.map { record ->
            val event = eventsById[record.categoryId]
            ExportedBibleTextRecord(
                eventName = event?.event.orEmpty(),
                themeTopic = event?.name ?: "Uncategorized",
                speaker = event?.speaker,
                bibleVersionId = record.bibleVersionId,
                languageId = record.languageId,
                bibleBookId = record.bibleBookId,
                chapter = record.chapter,
                verses = record.verses,
                remarks = record.remarks,
                videos = record.videos,
            )
        }
        return gson.toJson(BibleTextExportFile(exportedAt = System.currentTimeMillis(), records = exported))
    }

    /** Null for anything that isn't a genuine export of this shape — a
     * malformed file, or valid JSON that just isn't one of these (missing/
     * wrong [EXPORT_FILE_TYPE]) — so the caller can show "this isn't a
     * Bible Text Record file" instead of crashing or silently importing
     * garbage. */
    fun parseExportJson(json: String): BibleTextExportFile? = runCatching {
        val file = gson.fromJson(json, BibleTextExportFile::class.java)
        file?.takeIf { it.type == EXPORT_FILE_TYPE }
    }.getOrNull()

    /** Reads a .zip produced by [share] — the exported JSON lives inside it as
     * [EXPORT_ZIP_ENTRY_NAME] — and parses whichever entry actually holds it.
     * Falls back to treating [input] as a plain (pre-zip) export file, so a
     * file already shared before this change still imports (spec's own
     * "never lose data" pattern this module already follows elsewhere). */
    fun parseExportFile(input: InputStream): BibleTextExportFile? {
        val bytes = input.readBytes()
        val fromZip = runCatching {
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val parsed = parseExportJson(zip.readBytes().toString(Charsets.UTF_8))
                        if (parsed != null) return@use parsed
                    }
                    entry = zip.nextEntry
                }
                null
            }
        }.getOrNull()
        return fromZip ?: parseExportJson(bytes.toString(Charsets.UTF_8))
    }

    /** Zips [json] into a fresh file under cacheDir/exports/ and launches the
     * system share sheet for it — same FileProvider mechanism
     * [com.emfitsolutions.gopreach.ui.components.SupportingImageCapture]'s
     * camera capture already uses, generalized to a zipped JSON payload
     * instead of a photo, so a Messenger/Gmail/Drive/Bluetooth/... target can
     * all receive it the normal Android way. */
    fun share(context: Context, json: String) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "gopreach-bible-text-${System.currentTimeMillis()}.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(EXPORT_ZIP_ENTRY_NAME))
            zip.write(json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = BIBLE_TEXT_EXPORT_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Bible Text Records"))
    }
}

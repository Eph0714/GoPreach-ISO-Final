package com.emfitsolutions.gopreach.data.offlinemap

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Offline street map: one PMTiles file (OpenStreetMap data, cut from the free Protomaps build) kept in the app's private
 * storage and drawn by MapLibre when there is no internet (or when the user forces it). MapTiler's own terms forbid bulk
 * downloading its tiles, so the offline map is OpenStreetMap-only: street map, no satellite imagery.
 *
 * The pack is put there either by downloading it from `offlineMapUrl` (local.properties -> BuildConfig.OFFLINE_MAP_URL) or
 * by picking a `.pmtiles` file already on the phone. [styleUrl] is what the map screens load instead of the MapTiler style.
 */
object OfflineMaps {
    private lateinit var app: Context
    private const val PREFS = "offline_maps"
    private const val KEY_FORCE = "force_offline"
    private const val GLYPH_MARKER = "glyphs.v1"
    private val GLYPH_FONTS = listOf("NotoSansRegular", "NotoSansMedium", "NotoSansItalic")
    private val GLYPH_RANGES = listOf("0-255", "256-511")

    fun init(context: Context) { app = context.applicationContext }

    private val dir: File get() = File(app.filesDir, "offline_maps").also { it.mkdirs() }
    val packFile: File get() = File(dir, "philippines.pmtiles")
    private val partFile: File get() = File(dir, "philippines.pmtiles.part")

    fun isInstalled(): Boolean = packFile.length() > MIN_PACK_BYTES
    fun packSizeBytes(): Long = packFile.length()
    fun partialBytes(): Long = partFile.length()

    var forceOffline: Boolean
        get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FORCE, false)
        set(v) { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_FORCE, v).apply() }

    fun isOnline(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** The offline map is used when the pack is installed and the phone is offline (or the user chose "always offline"). */
    fun shouldUse(): Boolean = ::app.isInitialized && isInstalled() && (forceOffline || !isOnline())

    /** A `file://` MapLibre style for the installed pack ([dark] picks the night colours). Safe to call only when [isInstalled]. */
    fun styleUrl(dark: Boolean): String {
        val out = File(dir, "style_${if (dark) "dark" else "light"}.json")
        // The style only changes when the pack file does, so it is not rewritten on every recomposition.
        if (out.exists() && out.lastModified() >= packFile.lastModified()) return "file://" + out.absolutePath
        val glyphs = ensureGlyphs()
        val name = if (dark) "offline_dark" else "offline_light"
        val json = app.assets.open("offlinemap/$name.json").bufferedReader().use { it.readText() }
            .replace("__GLYPHS__", "file://" + glyphs.absolutePath)
            .replace("__PMTILES__", "file://" + packFile.absolutePath)
        out.writeText(json)
        return "file://" + out.absolutePath
    }

    /** Copies the bundled label fonts out of the APK once (MapLibre reads glyphs from plain files). */
    private fun ensureGlyphs(): File {
        val target = File(dir, "glyphs")
        val marker = File(target, GLYPH_MARKER)
        if (marker.exists()) return target
        for (font in GLYPH_FONTS) {
            File(target, font).mkdirs()
            for (range in GLYPH_RANGES) {
                app.assets.open("offlinemap/glyphs/$font/$range.pbf").use { input ->
                    File(target, "$font/$range.pbf").outputStream().use { input.copyTo(it) }
                }
            }
        }
        marker.writeText("ok")
        return target
    }

    fun delete() {
        packFile.delete(); partFile.delete()
    }

    /**
     * Downloads [url] into the pack file, resuming a partial download. [onProgress] gets (bytes so far, total or -1).
     * Throws on failure; the partial file is kept so the next call continues from where it stopped.
     */
    suspend fun download(url: String, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val existing = partFile.length()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 30_000
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val resumed = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
            if (conn.responseCode != HttpURLConnection.HTTP_OK && !resumed) error("Server answered ${conn.responseCode}")
            val total = if (resumed) existing + conn.contentLengthLong else conn.contentLengthLong
            if (!resumed) partFile.delete()
            conn.inputStream.use { input -> copyTo(input, append = resumed, total = total, onProgress = onProgress) }
        } finally {
            conn.disconnect()
        }
        finish()
    }

    /** Installs a `.pmtiles` file picked on the phone (content:// or file:// URI string). */
    suspend fun importFrom(uri: String, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        partFile.delete()
        val input = app.contentResolver.openInputStream(Uri.parse(uri)) ?: error("Could not open the chosen file")
        input.use { copyTo(it, append = false, total = -1, onProgress = onProgress) }
        finish()
    }

    private suspend fun copyTo(input: InputStream, append: Boolean, total: Long, onProgress: (Long, Long) -> Unit) {
        var done = if (append) partFile.length() else 0L
        java.io.FileOutputStream(partFile, append).use { out ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                onProgress(done, total)
            }
        }
    }

    /** Checks the file really is a PMTiles archive, then swaps it in for the previous pack. */
    private fun finish() {
        val ok = partFile.length() > MIN_PACK_BYTES && partFile.inputStream().use { s ->
            val head = ByteArray(7); s.read(head) == 7 && String(head, Charsets.US_ASCII) == "PMTiles"
        }
        if (!ok) { partFile.delete(); error("That file is not a PMTiles map") }
        packFile.delete()
        check(partFile.renameTo(packFile)) { "Could not save the map file" }
    }

    private const val MIN_PACK_BYTES = 1_000_000L
}

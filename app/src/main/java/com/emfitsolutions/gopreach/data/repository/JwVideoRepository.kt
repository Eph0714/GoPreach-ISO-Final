package com.emfitsolutions.gopreach.data.repository

import android.content.Context
import android.util.Log
import com.emfitsolutions.gopreach.data.model.SavedVideo
import com.emfitsolutions.gopreach.di.ApplicationScope
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** What a pasted JW Library / jw.org video link points at. [lank] is jw.org's
 * language-independent key for the video; [jwLocale] the language the link
 * named, if it named one. */
data class VideoLink(val lank: String, val jwLocale: String?)

/** One downloadable/streamable rendition of a video. */
data class VideoFile(val label: String, val url: String, val sizeBytes: Long, val height: Int)

/** A video found on jw.org: what to save with the record, plus the renditions
 * available right now. [languageFallback] is true when the video wasn't
 * available in the requested language and the English one was used instead. */
data class ResolvedVideo(val video: SavedVideo, val files: List<VideoFile>, val languageFallback: Boolean)

/** Where a video should be played from. */
data class PlaybackSource(val uri: String, val isLocalFile: Boolean)

/**
 * Videos from jw.org, for the Bible Text records: turning a link pasted from
 * JW Library into a [SavedVideo], streaming it, and keeping a downloaded copy
 * on the device for offline playback.
 *
 * Everything comes from jw.org's own public media service (the one its Online
 * Library and JW Library use), so nothing is bundled and GoPreach never reads
 * JW Library's private storage. A downloaded copy is just the MP4 jw.org
 * publishes for download, saved in the app's own storage (the publisher can
 * delete it again at any time) — it isn't shared with anyone or synced.
 */
@Singleton
class JwVideoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val videoDir get() = File(context.filesDir, "videos")

    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Video key -> percent (0-100) for every download currently running. */
    val downloadProgress: StateFlow<Map<String, Int>> = _downloadProgress.asStateFlow()

    private val _downloaded = MutableStateFlow(scanDownloaded())
    /** Keys of the videos that have a downloaded copy on this device. */
    val downloaded: StateFlow<Set<String>> = _downloaded.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-off messages for the screen to show (a download finished or failed). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** The unique key a video's downloaded file and progress are tracked under. */
    fun keyOf(video: SavedVideo): String = "${video.jwLocale}_${video.lank}"

    /** Reads a pasted link — a share link from JW Library, a jw.org address, or
     * just the bare key — into a [VideoLink]; null if it isn't a video link. */
    fun parseLink(text: String): VideoLink? {
        val raw = text.trim()
        val match = LANK_REGEX.find(raw) ?: return null
        // Strip a language segment ("pub-jwbvod26_E_34_VIDEO" -> "pub-jwbvod26_34_VIDEO"):
        // publication symbols are lowercase/digits, so a short all-capitals segment
        // in the middle can only be a language code.
        val parts = match.groupValues[1].split("_")
        var languageFromKey: String? = null
        val kept = parts.filterIndexed { index, part ->
            val isLanguage = index in 1 until parts.lastIndex && LANGUAGE_SEGMENT.matches(part)
            if (isLanguage) languageFromKey = part
            !isLanguage
        }
        val locale = LOCALE_PARAM.find(raw)?.groupValues?.get(1)?.uppercase() ?: languageFromKey
        return VideoLink(lank = kept.joinToString("_"), jwLocale = locale)
    }

    /** Looks the video up on jw.org in [preferredLocale], then English if it
     * isn't published in that language. Null if it can't be found or jw.org
     * can't be reached. */
    suspend fun resolve(lank: String, preferredLocale: String): ResolvedVideo? = withContext(Dispatchers.IO) {
        for (locale in listOf(preferredLocale, "E").distinct()) {
            val item = fetchMediaItem(locale, lank) ?: continue
            val files = (item.getAsJsonArray("files")?.mapNotNull { element ->
                val file = element.asJsonObject
                if (file.get("mimetype")?.asString != "video/mp4") return@mapNotNull null
                val url = file.get("progressiveDownloadURL")?.asString ?: return@mapNotNull null
                VideoFile(
                    label = file.get("label")?.asString.orEmpty(),
                    url = url,
                    sizeBytes = file.get("filesize")?.asLong ?: 0L,
                    height = file.get("frameHeight")?.asInt ?: 0,
                )
            } ?: emptyList()).sortedBy { it.height }
            if (files.isEmpty()) continue
            val video = SavedVideo(
                title = item.get("title")?.asString.orEmpty(),
                lank = lank,
                jwLocale = locale,
                durationSeconds = item.get("duration")?.asDouble?.toInt() ?: 0,
                thumbnailUrl = thumbnailOf(item),
                addedAt = System.currentTimeMillis(),
            )
            return@withContext ResolvedVideo(video, files, languageFallback = locale != preferredLocale)
        }
        null
    }

    private fun fetchMediaItem(locale: String, lank: String): JsonObject? = runCatching {
        val connection = (URL("https://b.jw-cdn.org/apis/mediator/v1/media-items/$locale/$lank?clientType=www").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (connection.responseCode != 200) return@runCatching null
            val json = JsonParser.parseString(connection.inputStream.bufferedReader().use { it.readText() }).asJsonObject
            json.getAsJsonArray("media")?.firstOrNull()?.asJsonObject
        } finally {
            connection.disconnect()
        }
    }.onFailure { Log.w(TAG, "Could not look up $lank ($locale)", it) }.getOrNull()

    private fun thumbnailOf(item: JsonObject): String {
        val images = item.getAsJsonObject("images") ?: return ""
        for (kind in listOf("wss", "lss", "sqr", "pnr")) {
            val sizes = images.getAsJsonObject(kind) ?: continue
            for (size in listOf("md", "lg", "sm", "xl")) {
                sizes.get(size)?.asString?.let { return it }
            }
        }
        return ""
    }

    /** The rendition to stream or download by default: 360p (a good size/quality
     * balance on a phone), else the closest one below it, else the smallest. */
    fun pickFile(files: List<VideoFile>): VideoFile? =
        files.lastOrNull { it.height in 1..360 } ?: files.firstOrNull()

    fun localFile(video: SavedVideo): File = File(videoDir, "${keyOf(video)}.mp4")

    fun isDownloaded(video: SavedVideo): Boolean = keyOf(video) in _downloaded.value

    /** The downloaded copy if there is one (so it plays with no internet),
     * otherwise a fresh stream address from jw.org; null if neither. */
    suspend fun playbackSource(video: SavedVideo): PlaybackSource? {
        val local = localFile(video)
        if (local.isFile && local.length() > 0) return PlaybackSource(local.toURI().toString(), isLocalFile = true)
        val resolved = resolve(video.lank, video.jwLocale) ?: return null
        return pickFile(resolved.files)?.let { PlaybackSource(it.url, isLocalFile = false) }
    }

    /** Downloads [video] into the app's storage in the background (it keeps
     * going if the screen is left); progress shows in [downloadProgress] and the
     * outcome arrives on [messages]. */
    fun startDownload(video: SavedVideo) {
        val key = keyOf(video)
        if (key in _downloadProgress.value || isDownloaded(video)) return
        _downloadProgress.update { it + (key to 0) }
        appScope.launch {
            val outcome = runCatching { download(video, key) }
            _downloadProgress.update { it - key }
            outcome.onSuccess { ok ->
                _messages.tryEmit(if (ok) "\"${video.title}\" downloaded for offline use." else "Couldn't download \"${video.title}\". Check your internet connection and try again.")
            }.onFailure {
                Log.w(TAG, "Download failed for $key", it)
                _messages.tryEmit("Couldn't download \"${video.title}\". Check your internet connection and free space.")
            }
        }
    }

    private suspend fun download(video: SavedVideo, key: String): Boolean = withContext(Dispatchers.IO) {
        val resolved = resolve(video.lank, video.jwLocale) ?: return@withContext false
        val file = pickFile(resolved.files) ?: return@withContext false
        videoDir.mkdirs()
        val target = localFile(video)
        val partial = File(videoDir, "${target.name}.part")
        val connection = (URL(file.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (connection.responseCode != 200) return@withContext false
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: file.sizeBytes
            var copied = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) {
                            val percent = (copied * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                _downloadProgress.update { it + (key to percent) }
                            }
                        }
                    }
                }
            }
            if (partial.length() == 0L || (total > 0 && partial.length() < total)) {
                partial.delete()
                return@withContext false
            }
            target.delete()
            if (!partial.renameTo(target)) {
                partial.delete()
                return@withContext false
            }
            _downloaded.update { it + key }
            true
        } finally {
            connection.disconnect()
            if (partial.exists()) partial.delete()
        }
    }

    /** Deletes the downloaded copy (the record keeps its link). */
    fun removeDownload(video: SavedVideo) {
        localFile(video).delete()
        _downloaded.update { it - keyOf(video) }
    }

    private fun scanDownloaded(): Set<String> =
        videoDir.listFiles { file -> file.isFile && file.name.endsWith(".mp4") && file.length() > 0 }
            ?.map { it.name.removeSuffix(".mp4") }
            ?.toSet()
            .orEmpty()

    private companion object {
        const val TAG = "JwVideo"
        const val USER_AGENT = "GoPreach/1.0 (Android; congregation ministry app)"

        /** jw.org's key for a video: "pub-<symbol>_<track>_VIDEO" or
         * "docid-<number>_<track>_VIDEO" (with an optional language segment). */
        val LANK_REGEX = Regex("((?:pub|docid)-[A-Za-z0-9]+(?:_[A-Za-z0-9]+)*_VIDEO)")
        val LANGUAGE_SEGMENT = Regex("[A-Z]{1,4}")
        val LOCALE_PARAM = Regex("[?&]wtlocale=([A-Za-z]{1,4})")
    }
}

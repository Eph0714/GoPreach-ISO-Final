package com.emfitsolutions.gopreach.data.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed class DownloadEvent {
    data class Progress(val percent: Int) : DownloadEvent()
    data class Done(val file: File) : DownloadEvent()
}

/**
 * Downloads the update APK with progress, into this app's own external files
 * directory (no storage permission needed — that directory is always
 * app-private-but-FileProvider-shareable on modern Android) rather than the
 * public Downloads folder, so the user never has to go find it themselves.
 */
@Singleton
class ApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val updatesDir: File
        get() = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }

    fun download(url: String, fileName: String): Flow<DownloadEvent> = flow {
        val destFile = File(updatesDir, fileName)
        if (destFile.exists()) destFile.delete()

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true

        if (connection.responseCode !in 200..299) {
            throw Exception("Download failed (HTTP ${connection.responseCode})")
        }

        val totalBytes = connection.contentLengthLong
        var downloadedBytes = 0L
        var lastEmittedPercent = -1

        connection.inputStream.use { input ->
            destFile.outputStream().use { output ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloadedBytes += read
                    if (totalBytes > 0) {
                        val percent = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                        if (percent != lastEmittedPercent) {
                            lastEmittedPercent = percent
                            emit(DownloadEvent.Progress(percent))
                        }
                    }
                }
            }
        }
        emit(DownloadEvent.Done(destFile))
    }.flowOn(Dispatchers.IO)

    /** Removes any previously downloaded update APKs — called after a
     * successful install, or when starting a fresh download attempt. */
    fun clearDownloads() {
        updatesDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        /** Lowercase hex SHA-256 of [file], for comparing against the manifest's digest. */
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

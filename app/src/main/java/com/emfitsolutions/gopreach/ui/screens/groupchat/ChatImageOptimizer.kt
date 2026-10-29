package com.emfitsolutions.gopreach.ui.screens.groupchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import com.emfitsolutions.gopreach.domain.chat.ChatImageFormat
import com.emfitsolutions.gopreach.domain.chat.ChatImagePlan
import com.emfitsolutions.gopreach.domain.chat.ChatImagePlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max

/** A picture ready to be sent: [uri] is what is uploaded (the original untouched, or an optimized copy in the app's cache), [thumbUri] its small preview. */
data class PreparedChatImage(
    val uri: Uri,
    val fileName: String,
    val mime: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val thumbUri: Uri?,
    val originalBytes: Long,
    /** One plain line for the person: what was done to the picture. */
    val note: String,
)

/** A problem the person can read (the picture cannot be used); thrown by [ChatImageOptimizer.prepare]. */
class ChatImageException(message: String) : Exception(message)

/**
 * Gets a picture ready for a Group Chat, quality first (the rules are in [ChatImagePlanner]):
 * a small picture is sent exactly as it is; a larger one keeps its resolution and is re-encoded at high quality (PNG keeps its transparency, a HEIC becomes JPEG);
 * only when the result still does not fit the server's limit is it scaled down in small steps with its proportions kept.
 * The picture's orientation is applied to its pixels (a portrait photo stays upright) and the camera's metadata is not sent.
 * The original on the phone is only read, never changed. Decoding never loads more than about 36 million pixels at once, and an out-of-memory error
 * falls back to a smaller decode instead of crashing.
 */
class ChatImageOptimizer(private val context: Context) {
    private val dir: File get() = File(context.cacheDir, "chat_upload").apply { mkdirs() }

    suspend fun prepare(source: Uri, displayName: String): PreparedChatImage = withContext(Dispatchers.Default) {
        val resolver = context.contentResolver
        val mime = (resolver.getType(source) ?: mimeFromName(displayName)).lowercase()
        val originalBytes = sizeOf(source)
        if (originalBytes <= 0L) throw ChatImageException("This photo could not be read. Choose another one.")
        if (!mime.startsWith("image/")) throw ChatImageException("This is not a photo.")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var width = bounds.outWidth
        var height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            if (ChatImagePlanner.isHeic(mime)) throw ChatImageException("This phone cannot read HEIC / HEIF photos. Choose a JPEG or PNG instead.")
            if (originalBytes <= ChatImagePlanner.LIMIT_BYTES && mime != "image/gif") return@withContext asIs(source, displayName, mime, originalBytes, 0, 0, null, "Sent as it is")
            throw ChatImageException("This photo could not be read. Choose another one.")
        }
        val rotation = orientationDegrees(source)
        val flipped = rotation == 90 || rotation == 270
        val shownWidth = if (flipped) height else width
        val shownHeight = if (flipped) width else height

        val alpha = (mime == "image/png" || mime == "image/webp") && usesTransparency(source, width, height)
        val plan = ChatImagePlanner.plan(mime, originalBytes, alpha)
        val thumb = makeThumbnail(source, width, height, rotation, keepAlpha = alpha)

        if (plan is ChatImagePlan.Original) {
            return@withContext asIs(source, displayName, mime, originalBytes, shownWidth, shownHeight, thumb, if (mime == "image/gif") "Sent as it is" else "Sent as it is (already small)")
        }
        plan as ChatImagePlan.Reencode

        // Full resolution first; smaller only if the file still does not fit.
        var bitmap = decode(source, width, height, rotation) ?: return@withContext fallbackOriginal(source, displayName, mime, originalBytes, shownWidth, shownHeight, thumb)
        val steps = ChatImagePlanner.sizeSteps(bitmap.width, bitmap.height)
        var bytes: ByteArray? = null
        var finalW = bitmap.width
        var finalH = bitmap.height
        for ((w, h) in steps) {
            val candidate = if (w == bitmap.width && h == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
            val encoded = encode(candidate, plan.format, plan.quality)
            if (candidate !== bitmap) candidate.recycle()
            if (encoded.size <= ChatImagePlanner.LIMIT_BYTES) { bytes = encoded; finalW = w; finalH = h; break }
            bytes = encoded
            finalW = w; finalH = h
        }
        val firstWidth = bitmap.width
        bitmap.recycle()
        val result = bytes ?: throw ChatImageException("This photo could not be processed. Choose another one.")
        val converted = ChatImagePlanner.isHeic(mime)
        if (ChatImagePlanner.keepOriginal(originalBytes, result.size.toLong(), converted) && finalW == firstWidth) {
            return@withContext asIs(source, displayName, mime, originalBytes, shownWidth, shownHeight, thumb, "Original kept (it is already well compressed)")
        }
        if (result.size > ChatImagePlanner.LIMIT_BYTES) {
            throw ChatImageException("This photo is still over the 8 MB limit after the best-quality optimization (${formatFileSize(result.size.toLong())}). Choose a smaller one.")
        }
        val file = File(dir, "${UUID.randomUUID()}.${plan.format.extension}").apply { writeBytes(result) }
        val note = if (finalW != firstWidth) "Optimized and scaled to $finalW x $finalH to fit the limit" else "Optimized at full resolution"
        PreparedChatImage(Uri.fromFile(file), withExtension(displayName, plan.format.extension), plan.format.mime, result.size.toLong(), finalW, finalH, thumb, originalBytes, note)
    }

    // -------------------------------------------------------------------------------------------------------------------------------------

    private fun asIs(uri: Uri, name: String, mime: String, size: Long, w: Int, h: Int, thumb: Uri?, note: String) =
        PreparedChatImage(uri, name, mime, size, w, h, thumb, size, note)

    private fun fallbackOriginal(uri: Uri, name: String, mime: String, size: Long, w: Int, h: Int, thumb: Uri?): PreparedChatImage {
        if (size > ChatImagePlanner.LIMIT_BYTES || ChatImagePlanner.isHeic(mime)) throw ChatImageException("This phone does not have enough free memory to process this photo. Close other apps and try again, or choose a smaller photo.")
        return asIs(uri, name, mime, size, w, h, thumb, "Sent as it is (not enough memory to optimize)")
    }

    /** Decodes the whole picture (rotated upright), using a coarser decode only when it would be more than ~36 million pixels or when memory runs out. */
    private fun decode(source: Uri, width: Int, height: Int, rotation: Int): Bitmap? {
        var sample = 1
        while ((width.toLong() / sample) * (height.toLong() / sample) > MAX_DECODE_PIXELS) sample *= 2
        repeat(4) {
            try {
                val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val raw = context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
                return rotate(raw, rotation)
            } catch (e: OutOfMemoryError) {
                sample *= 2
            }
        }
        return null
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }

    private fun encode(bitmap: Bitmap, format: ChatImageFormat, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(max(64 * 1024, bitmap.byteCount / 8))
        val target = when (format) {
            ChatImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
            ChatImageFormat.PNG -> Bitmap.CompressFormat.PNG
            ChatImageFormat.WEBP -> if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        // A JPEG has no transparency: a picture that had some would be drawn on black, so such a picture is never planned as JPEG (see ChatImagePlanner).
        bitmap.compress(target, quality, out)
        return out.toByteArray()
    }

    private fun makeThumbnail(source: Uri, width: Int, height: Int, rotation: Int, keepAlpha: Boolean): Uri? = try {
        var sample = 1
        while (max(width, height) / (sample * 2) >= ChatImagePlanner.THUMBNAIL_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (raw == null) null else {
            val upright = rotate(raw, rotation)
            val (tw, th) = ChatImagePlanner.thumbnailSize(upright.width, upright.height)
            val small = if (tw == upright.width && th == upright.height) upright else Bitmap.createScaledBitmap(upright, tw, th, true)
            val file = File(dir, "${UUID.randomUUID()}_thumb.${if (keepAlpha) "png" else "jpg"}")
            file.outputStream().use { small.compress(if (keepAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, ChatImagePlanner.THUMBNAIL_QUALITY, it) }
            if (small !== upright) small.recycle()
            upright.recycle()
            Uri.fromFile(file)
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun usesTransparency(source: Uri, width: Int, height: Int): Boolean = try {
        var sample = 1
        while (max(width, height) / (sample * 2) >= 256) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val small = context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (small == null || !small.hasAlpha()) false else {
            val pixels = IntArray(small.width * small.height)
            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            pixels.any { (it ushr 24) < 250 }
        }
    } catch (e: Exception) {
        false
    }

    private fun orientationDegrees(source: Uri): Int = try {
        context.contentResolver.openInputStream(source)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    } catch (e: Exception) {
        0
    }

    private fun sizeOf(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return runCatching { context.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: 0L }.getOrDefault(0L)
    }

    private fun mimeFromName(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    private fun withExtension(name: String, ext: String): String = name.substringBeforeLast('.', name).ifBlank { "photo" } + "." + ext

    private companion object {
        const val MAX_DECODE_PIXELS = 36_000_000L
    }
}

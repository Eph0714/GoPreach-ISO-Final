package com.emfitsolutions.gopreach.domain.chat

import kotlin.math.roundToInt

/** What to do with a picture before it is sent to a Group Chat. The rule is quality first: never shrink a picture just to make the file small. */
sealed interface ChatImagePlan {
    /** Send the picture exactly as it is (no second compression). */
    data object Original : ChatImagePlan

    /** Re-encode it as [format] at [quality] (0-100), at full resolution first; scale down in small steps only if the result still exceeds the limit. */
    data class Reencode(val format: ChatImageFormat, val quality: Int = ChatImagePlanner.QUALITY) : ChatImagePlan
}

enum class ChatImageFormat(val mime: String, val extension: String) { JPEG("image/jpeg", "jpg"), PNG("image/png", "png"), WEBP("image/webp", "webp") }

object ChatImagePlanner {
    /** The server stores a file up to 8 MB; a small margin is kept for the request. */
    const val LIMIT_BYTES = 8L * 1024 * 1024 - 64 * 1024

    /** A picture up to this size is sent untouched. */
    const val SMALL_BYTES = 1536L * 1024

    const val QUALITY = 92
    const val THUMBNAIL_SIDE = 640
    const val THUMBNAIL_QUALITY = 82

    /** Scale factor of each step when a picture has to fit the limit (about 10% smaller per step), and the shortest side allowed. */
    const val SCALE_STEP = 0.9
    const val MIN_LONG_SIDE = 1200

    fun isHeic(mime: String) = mime.equals("image/heic", true) || mime.equals("image/heif", true)

    /**
     * [mime] is the picture's type, [sizeBytes] its file size and [hasAlpha] whether it uses transparency (only relevant for PNG / WebP).
     * - GIF (an animation) and anything up to [SMALL_BYTES] is sent as it is, except HEIC / HEIF, which most viewers cannot show, so it becomes JPEG.
     * - A larger PNG / WebP that uses transparency keeps its format (so the transparency survives); every other large picture becomes a high-quality JPEG.
     */
    fun plan(mime: String, sizeBytes: Long, hasAlpha: Boolean): ChatImagePlan {
        val type = mime.lowercase()
        if (type == "image/gif") return ChatImagePlan.Original
        if (isHeic(type)) return ChatImagePlan.Reencode(ChatImageFormat.JPEG)
        if (sizeBytes <= SMALL_BYTES) return ChatImagePlan.Original
        if (hasAlpha && type == "image/png") return ChatImagePlan.Reencode(ChatImageFormat.PNG)
        if (hasAlpha && type == "image/webp") return ChatImagePlan.Reencode(ChatImageFormat.WEBP)
        return ChatImagePlan.Reencode(ChatImageFormat.JPEG)
    }

    /** Whether a re-encoded result should replace the original: never when it is not smaller (and the original already fits), and never when it is over the limit. */
    fun keepOriginal(originalBytes: Long, reencodedBytes: Long, converted: Boolean): Boolean =
        !converted && originalBytes <= LIMIT_BYTES && reencodedBytes >= originalBytes

    /** The sizes to try, from the full size down in [SCALE_STEP] steps, stopping before the long side goes under [MIN_LONG_SIDE]. */
    fun sizeSteps(width: Int, height: Int): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var scale = 1.0
        repeat(14) {
            val w = maxOf(1, (width * scale).roundToInt())
            val h = maxOf(1, (height * scale).roundToInt())
            out += w to h
            if (w <= MIN_LONG_SIDE || h <= MIN_LONG_SIDE) return out
            scale *= SCALE_STEP
        }
        return out
    }

    /** The preview's size: the long side is [THUMBNAIL_SIDE] (never enlarged), the proportions are kept. */
    fun thumbnailSize(width: Int, height: Int): Pair<Int, Int> {
        val k = minOf(1.0, THUMBNAIL_SIDE.toDouble() / maxOf(width, height))
        return maxOf(1, (width * k).roundToInt()) to maxOf(1, (height * k).roundToInt())
    }
}

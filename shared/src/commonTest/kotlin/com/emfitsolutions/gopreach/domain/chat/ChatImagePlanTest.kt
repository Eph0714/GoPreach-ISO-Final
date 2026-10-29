package com.emfitsolutions.gopreach.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatImagePlanTest {
    private val mb = 1024L * 1024

    @Test fun aSmallPictureIsSentAsItIsAndNeverCompressedTwice() {
        assertEquals(ChatImagePlan.Original, ChatImagePlanner.plan("image/jpeg", 300 * 1024, false))
        assertEquals(ChatImagePlan.Original, ChatImagePlanner.plan("image/png", 1 * mb, true))
        assertEquals(ChatImagePlan.Original, ChatImagePlanner.plan("image/webp", 1 * mb, false))
    }

    @Test fun aLargePictureKeepsItsResolutionAndIsReEncodedAtHighQuality() {
        val plan = ChatImagePlanner.plan("image/jpeg", 6 * mb, false) as ChatImagePlan.Reencode
        assertEquals(ChatImageFormat.JPEG, plan.format)
        assertEquals(92, plan.quality)
    }

    @Test fun transparencyKeepsPngAndWebpButAPhotoWithoutItBecomesJpeg() {
        assertEquals(ChatImagePlan.Reencode(ChatImageFormat.PNG), ChatImagePlanner.plan("image/png", 5 * mb, true))
        assertEquals(ChatImagePlan.Reencode(ChatImageFormat.WEBP), ChatImagePlanner.plan("image/webp", 5 * mb, true))
        assertEquals(ChatImagePlan.Reencode(ChatImageFormat.JPEG), ChatImagePlanner.plan("image/png", 5 * mb, false))
        assertEquals(ChatImagePlan.Reencode(ChatImageFormat.JPEG), ChatImagePlanner.plan("image/webp", 5 * mb, false))
    }

    @Test fun heicIsAlwaysConvertedAndAGifIsNeverTouched() {
        assertEquals(ChatImagePlan.Reencode(ChatImageFormat.JPEG), ChatImagePlanner.plan("image/heic", 200 * 1024, false))
        assertEquals(ChatImagePlan.Reencode(ChatImageFormat.JPEG), ChatImagePlanner.plan("image/HEIF", 9 * mb, false))
        assertEquals(ChatImagePlan.Original, ChatImagePlanner.plan("image/gif", 9 * mb, false))
    }

    @Test fun aResultThatIsNotSmallerNeverReplacesTheOriginal() {
        assertTrue(ChatImagePlanner.keepOriginal(4 * mb, 4 * mb + 1, converted = false))
        assertTrue(ChatImagePlanner.keepOriginal(4 * mb, 4 * mb, converted = false))
        assertFalse(ChatImagePlanner.keepOriginal(4 * mb, 2 * mb, converted = false))
        assertFalse(ChatImagePlanner.keepOriginal(4 * mb, 5 * mb, converted = true), "a HEIC must be converted whatever the size")
        assertFalse(ChatImagePlanner.keepOriginal(12 * mb, 12 * mb, converted = false), "an original over the limit cannot be kept")
    }

    @Test fun scalingStartsAtFullSizeKeepsTheShapeAndStopsBeforeTheSmallestSide() {
        val steps = ChatImagePlanner.sizeSteps(4000, 3000)
        assertEquals(4000 to 3000, steps.first(), "the first try is the full resolution")
        steps.zipWithNext { a, b -> assertTrue(b.first < a.first && b.second < a.second) }
        steps.forEach { (w, h) -> assertEquals(4.0 / 3.0, w.toDouble() / h, 0.01, "the aspect ratio is kept at $w x $h") }
        assertTrue(steps.last().let { it.first <= ChatImagePlanner.MIN_LONG_SIDE || it.second <= ChatImagePlanner.MIN_LONG_SIDE })
        assertEquals(1, ChatImagePlanner.sizeSteps(800, 600).size.coerceAtMost(1))
    }

    @Test fun thePreviewIsNeverEnlargedAndKeepsTheProportions() {
        assertEquals(640 to 480, ChatImagePlanner.thumbnailSize(4000, 3000))
        assertEquals(480 to 640, ChatImagePlanner.thumbnailSize(3000, 4000))
        assertEquals(300 to 200, ChatImagePlanner.thumbnailSize(300, 200))
    }
}

package com.emfitsolutions.gopreach.domain.map

import com.emfitsolutions.gopreach.data.model.DrawingStatus
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class DrawingLabelTest {
    private val utc = TimeZone.UTC
    // 2026-10-29 00:00 UTC
    private val oct29 = 1_793_232_000_000L

    @Test fun statusOnlyWhenNothingElseIsSet() {
        assertEquals("To Do", DrawingLabel.text(DrawingStatus.TO_DO, 0L, "", "", utc))
    }

    @Test fun theDateFollowsTheStatus() {
        assertEquals("Finished · Oct 29, 2026", DrawingLabel.text(DrawingStatus.FINISHED, oct29, "", "", utc))
        assertEquals("To Continue · Oct 29, 2026", DrawingLabel.text(DrawingStatus.TO_CONTINUE, oct29, "", "", utc))
    }

    @Test fun nameFirstThenStatusThenFirstLineOfRemarks() {
        val text = DrawingLabel.text(DrawingStatus.TO_CONTINUE, oct29, "  East of the highway ", "Continue from the main road.\nSecond line", utc)
        assertEquals("East of the highway\nTo Continue · Oct 29, 2026\nContinue from the main road.", text)
    }

    @Test fun longRemarksAreCut() {
        val text = DrawingLabel.text(DrawingStatus.FINISHED, 0L, "", "x".repeat(100), utc)
        assertEquals("Finished\n" + "x".repeat(39) + "…", text)
    }

    @Test fun everyStatusHasItsOwnDateLabel() {
        assertEquals(listOf("Date finished", "Date to continue", "Date to do"), DrawingStatus.entries.map { it.dateLabel })
    }
}

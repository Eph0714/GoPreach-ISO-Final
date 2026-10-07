package com.emfitsolutions.gopreach.ui.screens

import com.emfitsolutions.gopreach.ui.screens.settings.InactivityTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InactivityTrackerTest {
    @Test
    fun idleTimeIsMeasuredFromTheLastStoredActivity() {
        assertEquals(90_000L, InactivityTracker.idleSince(storedWall = 10_000L, nowWall = 100_000L))
    }

    @Test
    fun noRecordMeansTheSessionIsTreatedAsExpired() {
        assertTrue(InactivityTracker.idleSince(0L, 100_000L) > 365L * 24 * 3_600_000L)
    }

    @Test
    fun aClockSetBackMeansExpiredToo() {
        assertTrue(InactivityTracker.idleSince(storedWall = 200_000L, nowWall = 100_000L) > 365L * 24 * 3_600_000L)
    }
}

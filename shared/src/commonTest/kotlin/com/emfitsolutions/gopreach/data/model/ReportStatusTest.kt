package com.emfitsolutions.gopreach.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportStatusTest {
    @Test
    fun `a submitted report is locked for its publisher until access is granted or it is reversed`() {
        for (s in listOf(ReportStatus.SUBMITTED, ReportStatus.CORRECTED, ReportStatus.POSTED, ReportStatus.ACCESS_REQUESTED)) {
            assertTrue(s.lockedForPublisher, "$s is locked")
            assertTrue(s.countsAsSubmitted, "$s counts as handed in")
        }
        for (s in listOf(ReportStatus.DRAFT, ReportStatus.RETURNED, ReportStatus.ACCESS_GRANTED)) {
            assertFalse(s.lockedForPublisher, "$s lets the publisher edit")
            assertFalse(s.countsAsSubmitted, "$s is not final, so it is not counted as submitted")
        }
    }

    @Test
    fun `statuses are named the way the publisher workflow names them`() {
        assertEquals("Open", ReportStatus.DRAFT.publisherLabel)
        assertEquals("Submitted", ReportStatus.SUBMITTED.publisherLabel)
        assertEquals("Access Requested", ReportStatus.ACCESS_REQUESTED.publisherLabel)
        assertEquals("Access Granted", ReportStatus.ACCESS_GRANTED.publisherLabel)
        assertEquals("Reversed", ReportStatus.RETURNED.publisherLabel)
    }

    @Test
    fun `only submitted-and-final reports feed the consolidated report`() {
        assertTrue(MonthlyReport(status = ReportStatus.ACCESS_REQUESTED).isSubmittedOrPosted)
        assertFalse(MonthlyReport(status = ReportStatus.ACCESS_GRANTED).isSubmittedOrPosted)
        assertFalse(MonthlyReport(status = ReportStatus.RETURNED).isSubmittedOrPosted)
    }
}

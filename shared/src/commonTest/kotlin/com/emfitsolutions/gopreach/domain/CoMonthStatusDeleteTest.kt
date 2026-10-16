package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.CoMonthStatus
import com.emfitsolutions.gopreach.data.model.CoReportStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class CoMonthStatusDeleteTest {
    private fun s(status: CoReportStatus, from: String? = null) = CoMonthStatus(status = status, cancelledFromStatus = from)

    @Test
    fun aSubmissionCanBeDeletedOnlyWhileTheOverseerHasNotReceivedIt() {
        assertEquals(true, s(CoReportStatus.SUBMITTED).canDeleteSubmission)
        assertEquals(true, s(CoReportStatus.RETURNED).canDeleteSubmission)
        assertEquals(true, s(CoReportStatus.CANCELLED, "SUBMITTED").canDeleteSubmission)
        assertEquals(false, s(CoReportStatus.CANCELLED, "RECEIVED").canDeleteSubmission, "cancelled from Received stays locked")
        assertEquals(false, s(CoReportStatus.RECEIVED).canDeleteSubmission)
        assertEquals(false, s(CoReportStatus.NOT_SUBMITTED).canDeleteSubmission, "nothing to delete in Draft")
    }
}

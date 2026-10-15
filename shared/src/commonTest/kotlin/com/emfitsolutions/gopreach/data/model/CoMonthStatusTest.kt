package com.emfitsolutions.gopreach.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoMonthStatusTest {
    private fun status(month: Long, s: CoReportStatus, cancelledFrom: String? = null) =
        CoMonthStatus(id = coReportId("solano", month), congregationId = "solano", periodMonth = month, status = s, cancelledFromStatus = cancelledFrom)

    @Test
    fun `only received locks the month - a snapshot awaiting receipt leaves the records editable`() {
        val list = listOf(
            status(1000, CoReportStatus.SUBMITTED), status(2000, CoReportStatus.RECEIVED),
            status(3000, CoReportStatus.RETURNED), status(4000, CoReportStatus.NOT_SUBMITTED),
        )
        assertFalse(isMonthLocked(list, "solano", 1000), "sent but not yet received: still editable")
        assertTrue(isMonthLocked(list, "solano", 2000))
        assertFalse(isMonthLocked(list, "solano", 3000))
        assertFalse(isMonthLocked(list, "solano", 4000))
    }

    @Test
    fun `cancelling a received submission never silently unlocks the month`() {
        val list = listOf(
            status(1000, CoReportStatus.CANCELLED, cancelledFrom = "RECEIVED"),
            status(2000, CoReportStatus.CANCELLED, cancelledFrom = "SUBMITTED"),
        )
        assertTrue(isMonthLocked(list, "solano", 1000), "cancelled from received stays locked until the overseer returns it")
        assertFalse(isMonthLocked(list, "solano", 2000), "a cancelled awaiting-receipt submission was never locked")
    }

    @Test
    fun `the lock is for that service month and congregation only`() {
        val list = listOf(status(1000, CoReportStatus.RECEIVED))
        assertFalse(isMonthLocked(list, "solano", 2000), "another month stays open")
        assertFalse(isMonthLocked(list, "bambang", 1000), "another congregation stays open")
        assertFalse(isMonthLocked(list, null, 1000))
    }

    @Test
    fun `the overseer sees a month only once it was submitted`() {
        assertFalse(CoReportStatus.NOT_SUBMITTED.visibleToCircuitOverseer)
        assertTrue(listOf(CoReportStatus.SUBMITTED, CoReportStatus.RECEIVED, CoReportStatus.RETURNED, CoReportStatus.CANCELLED).all { it.visibleToCircuitOverseer })
    }

    @Test
    fun `the congregation can send, update or undo only where the workflow allows`() {
        assertTrue(CoReportStatus.NOT_SUBMITTED.canSend)
        assertTrue(CoReportStatus.RETURNED.canSend)
        assertTrue(CoReportStatus.CANCELLED.canSend)
        assertFalse(CoReportStatus.SUBMITTED.canSend)
        assertFalse(CoReportStatus.RECEIVED.canSend)
        assertTrue(CoReportStatus.SUBMITTED.canSendUpdate, "an updated snapshot while awaiting receipt")
        assertFalse(CoReportStatus.RECEIVED.canSendUpdate, "a received snapshot is replaced only after the overseer returns it")
        assertTrue(CoReportStatus.SUBMITTED.canUndo)
        assertFalse(CoReportStatus.RECEIVED.canUndo, "once received only the overseer can return it")
    }

    @Test
    fun `the overseer can return or cancel only a sent or received month`() {
        assertTrue(CoReportStatus.SUBMITTED.canReturn && CoReportStatus.RECEIVED.canReturn)
        assertTrue(CoReportStatus.SUBMITTED.canCancel && CoReportStatus.RECEIVED.canCancel)
        assertFalse(CoReportStatus.RETURNED.canReturn || CoReportStatus.CANCELLED.canReturn || CoReportStatus.NOT_SUBMITTED.canReturn)
        assertFalse(CoReportStatus.RETURNED.canCancel || CoReportStatus.CANCELLED.canCancel || CoReportStatus.NOT_SUBMITTED.canCancel)
    }

    @Test
    fun `a future service month can never be submitted`() {
        assertTrue(isFutureServiceMonth(periodMonth = 2000, nowMillis = 1000))
        assertFalse(isFutureServiceMonth(periodMonth = 1000, nowMillis = 1000))
        assertFalse(isFutureServiceMonth(periodMonth = 500, nowMillis = 1000))
        assertEquals("solano_1000", coReportId("solano", 1000))
    }
}

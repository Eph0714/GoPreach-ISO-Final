package com.emfitsolutions.gopreach.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoMonthStatusTest {
    private fun status(month: Long, s: CoReportStatus) =
        CoMonthStatus(id = coReportId("solano", month), congregationId = "solano", periodMonth = month, status = s)

    @Test
    fun `submitted and received lock the month, returned and not submitted do not`() {
        val list = listOf(
            status(1000, CoReportStatus.SUBMITTED), status(2000, CoReportStatus.RECEIVED),
            status(3000, CoReportStatus.RETURNED), status(4000, CoReportStatus.NOT_SUBMITTED),
        )
        assertTrue(isMonthLocked(list, "solano", 1000))
        assertTrue(isMonthLocked(list, "solano", 2000))
        assertFalse(isMonthLocked(list, "solano", 3000))
        assertFalse(isMonthLocked(list, "solano", 4000))
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
        assertTrue(listOf(CoReportStatus.SUBMITTED, CoReportStatus.RECEIVED, CoReportStatus.RETURNED).all { it.visibleToCircuitOverseer })
    }

    @Test
    fun `the congregation can send or undo only where the workflow allows`() {
        assertTrue(CoReportStatus.NOT_SUBMITTED.canSend)
        assertTrue(CoReportStatus.RETURNED.canSend)
        assertFalse(CoReportStatus.SUBMITTED.canSend)
        assertFalse(CoReportStatus.RECEIVED.canSend)
        assertTrue(CoReportStatus.SUBMITTED.canUndo)
        assertFalse(CoReportStatus.RECEIVED.canUndo, "once received only the overseer can return it")
    }

    @Test
    fun `a future service month can never be submitted`() {
        assertTrue(isFutureServiceMonth(periodMonth = 2000, nowMillis = 1000))
        assertFalse(isFutureServiceMonth(periodMonth = 1000, nowMillis = 1000))
        assertFalse(isFutureServiceMonth(periodMonth = 500, nowMillis = 1000))
        assertEquals("solano_1000", coReportId("solano", 1000))
    }
}

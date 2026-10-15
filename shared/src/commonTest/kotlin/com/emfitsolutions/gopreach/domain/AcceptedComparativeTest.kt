package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.CoReceivedReport
import com.emfitsolutions.gopreach.data.model.CoReceivedRow
import com.emfitsolutions.gopreach.domain.AcceptedComparative.Metric
import com.emfitsolutions.gopreach.domain.AcceptedComparative.MonthInput
import com.emfitsolutions.gopreach.domain.AcceptedComparative.MonthState
import com.emfitsolutions.gopreach.domain.AcceptedComparative.Period
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Months are plain numbers 1, 2, 3 ... here; the calculation never assumes a calendar. */
class AcceptedComparativeTest {
    private val next = { m: Long -> m + 1 }
    private val label = { m: Long -> "M$m" }

    private fun row(n: Int, id: String, status: String, hours: Double? = null, studies: Int? = 0) =
        CoReceivedRow(number = n, status = status, name = "P$n", reportsCount = 1, hours = hours, bibleStudies = studies, personId = id)

    private fun accepted(month: Long, vararg rows: CoReceivedRow) =
        MonthInput(month, MonthState.ACCEPTED, CoReceivedReport(id = "c_${month}_1", congregationId = "c", periodMonth = month, version = 1, rows = rows.toList()))

    private fun build(periods: List<Period>, vararg known: MonthInput) = AcceptedComparative.build("Test Cong", periods, known.toList(), next, label)

    @Test
    fun monthsAndCoverageFollowTheChosenRanges() {
        val r = build(listOf(Period(1, 3), Period(4, 12)), accepted(1, row(1, "a", "Pub")), accepted(5, row(1, "a", "Pub")))
        assertEquals(3, r.periods[0].expected)
        assertEquals(9, r.periods[1].expected)
        assertEquals(1, r.periods[0].available)
        assertEquals(1, r.periods[1].available)
        assertEquals(listOf(2L, 3L), r.periods[0].missing)
        assertTrue(r.warnings.any { it.contains("different lengths") })
    }

    @Test
    fun headcountsAreAveragedNeverSummed() {
        val r = build(
            listOf(Period(1, 2), Period(3, 4)),
            accepted(1, row(1, "a", "Pub"), row(2, "b", "Pub")), accepted(2, row(1, "a", "Pub"), row(2, "b", "Pub"), row(3, "c", "Pub"), row(4, "d", "Pub")),
            accepted(3, row(1, "a", "Pub")), accepted(4, row(1, "a", "Pub")),
        )
        val a = r.periods[0].figures.getValue(Metric.PUBLISHERS)
        assertEquals(3.0, a.average)
        assertNull(a.total, "a headcount has no total")
        assertEquals(1.0, r.periods[1].figures.getValue(Metric.PUBLISHERS).average)
    }

    @Test
    fun hoursAndBibleStudiesAreSummedAndAveraged() {
        val r = build(
            listOf(Period(1, 2), Period(3, 3)),
            accepted(1, row(1, "a", "RP", hours = 50.0, studies = 2)), accepted(2, row(1, "a", "RP", hours = 70.5, studies = 3)),
            accepted(3, row(1, "a", "RP", hours = 60.0, studies = 1)),
        )
        val hours = r.periods[0].figures.getValue(Metric.HOURS)
        assertEquals(120.5, hours.total)
        assertEquals(60.25, hours.average)
        assertEquals(5.0, r.periods[0].figures.getValue(Metric.BIBLE_STUDIES).total)
    }

    @Test
    fun aMissingMonthIsNeverZeroAndOtherMonthsAreNotSubstituted() {
        val r = build(listOf(Period(1, 3), Period(4, 4)), accepted(1, row(1, "a", "Pub")), accepted(3, row(1, "a", "Pub"), row(2, "b", "Pub")), accepted(4, row(1, "a", "Pub")))
        val a = r.periods[0]
        assertEquals(1.5, a.figures.getValue(Metric.PUBLISHERS).average, "(1 + 2) / 2 accepted months - the missing month is not a zero")
        val missingRow = a.rows.single { it.month == 2L }
        assertTrue(missingRow.values.isEmpty())
        assertEquals(MonthState.NOT_SENT, missingRow.input.state)
    }

    @Test
    fun anUnacceptedMonthIsNotCountedEvenIfASnapshotWasSent() {
        val sent = MonthInput(1, MonthState.AWAITING, CoReceivedReport(id = "c_1_1", congregationId = "c", periodMonth = 1, rows = listOf(row(1, "a", "Pub"))))
        val r = build(listOf(Period(1, 1), Period(2, 2)), sent, accepted(2, row(1, "a", "Pub")))
        assertEquals(0, r.periods[0].available)
        assertNull(r.periods[0].figures.getValue(Metric.PUBLISHERS).average)
        assertTrue(r.warnings.any { it.contains("none of its 1 months") })
    }

    @Test
    fun twoPeriodsShowDifferenceAndPercentChange() {
        assertEquals(50.0, AcceptedComparative.percentChange(10.0, 15.0))
        assertEquals(-20.0, AcceptedComparative.percentChange(10.0, 8.0))
        assertNull(AcceptedComparative.percentChange(0.0, 5.0), "a zero baseline is N/A")
        assertNull(AcceptedComparative.percentChange(null, 5.0))
        assertEquals(5.0, AcceptedComparative.difference(10.0, 15.0))
        assertNull(AcceptedComparative.difference(10.0, null))
        val r = build(listOf(Period(1, 1), Period(2, 2)), accepted(1, row(1, "a", "Pub"), row(2, "b", "Pub")), accepted(2, row(1, "a", "Pub"), row(2, "b", "Pub"), row(3, "c", "Pub")))
        val publishers = r.comparison.first { it[0] == "Publishers - monthly average" }
        assertEquals(listOf("Publishers - monthly average", "2.0", "3.0", "+1.0", "+50.0%"), publishers)
        assertEquals(listOf("Metric", "M1 (1/1 months)", "M2 (1/1 months)", "Difference", "% Change"), r.comparison.first())
    }

    @Test
    fun theWordingFollowsWhetherTheReportsWereReceivedOrSentAndNeverSaysAccepted() {
        val known = arrayOf(accepted(1, row(1, "a", "Pub")))
        val received = AcceptedComparative.build("Cong", listOf(Period(1, 2), Period(3, 3)), known.toList(), next, label)
        val sent = AcceptedComparative.build("Cong", listOf(Period(1, 2), Period(3, 3)), known.toList(), next, label, noun = "sent")
        assertTrue(received.monthly.any { it[1] == "Received" }, "monthly: ${received.monthly}")
        assertTrue(sent.monthly.any { it[1] == "Sent" }, "monthly: ${sent.monthly}")
        assertTrue(received.warnings.filter { it.contains(" report") }.let { it.isNotEmpty() && it.all { w -> w.contains("received report") } })
        assertTrue(sent.warnings.filter { it.contains(" report") }.let { it.isNotEmpty() && it.all { w -> w.contains("sent report") } })
        val all = (received.warnings + received.monthly.flatten() + received.comparison.flatten() + received.summary.flatten()).joinToString(" ")
        assertTrue(!all.contains("ccepted") && !all.contains("snapshot"), "nothing the Circuit Overseer reads says Accepted: $all")
    }

    @Test
    fun threePeriodsAreSideBySideWithoutDifferenceColumns() {
        val r = build(listOf(Period(1, 1), Period(2, 2), Period(3, 3)), accepted(1, row(1, "a", "Pub")), accepted(2, row(1, "a", "Pub")), accepted(3, row(1, "a", "Pub")))
        assertEquals(4, r.comparison.first().size)
        assertFalse(r.comparison.first().contains("Difference"))
    }

    @Test
    fun aPersonIsCountedOnceAcrossMonths() {
        val r = build(listOf(Period(1, 3), Period(4, 4)), accepted(1, row(1, "a", "Pub"), row(2, "b", "Pub")), accepted(2, row(1, "a", "Pub")), accepted(3, row(1, "a", "Pub"), row(3, "c", "Pub")), accepted(4, row(1, "a", "Pub")))
        assertEquals(3, r.periods[0].distinctReporters, "a, b and c - not 5 monthly appearances")
        assertFalse(r.periods[0].distinctApproximate)
    }

    @Test
    fun monthlyBreakdownHasOneRowPerSelectedMonthGroupedByPeriod() {
        val r = build(listOf(Period(1, 2), Period(5, 8)), accepted(1, row(1, "a", "Pub")))
        // 1 header + (1 heading + 2 months) + (1 heading + 4 months)
        assertEquals(1 + 3 + 5, r.monthly.size)
        assertTrue(r.monthly[1][0].startsWith("M1 - M2"))
        assertEquals(listOf("M1", "M2"), r.monthly.slice(2..3).map { it[0] }.let { listOf(it[0], it[1]) })
        assertEquals("-", r.monthly[3][2], "a month with no accepted snapshot shows a dash, never 0")
    }

    @Test
    fun validationRejectsBadSelections() {
        val none = { _: Period -> 1 }
        assertNotNull(AcceptedComparative.validate(listOf(Period(1, 2)), 100, none), "needs two periods")
        assertNotNull(AcceptedComparative.validate(listOf(Period(3, 1), Period(5, 6)), 100, none), "from after to")
        assertNotNull(AcceptedComparative.validate(listOf(Period(1, 5), Period(4, 8)), 100, none), "overlap")
        assertNotNull(AcceptedComparative.validate(listOf(Period(1, 2), Period(3, 200)), 100, none), "future")
        assertNotNull(AcceptedComparative.validate(listOf(Period(1, 2), Period(3, 4)), 100) { 99 }, "too long")
        assertNull(AcceptedComparative.validate(listOf(Period(1, 2), Period(3, 4), Period(5, 6)), 100, none))
    }

    @Test
    fun identicalSelectionsGiveIdenticalReportsAndDifferentOnesDoNot() {
        val known = arrayOf(accepted(1, row(1, "a", "Pub")), accepted(2, row(1, "a", "Pub"), row(2, "b", "Pub")))
        val first = build(listOf(Period(1, 1), Period(2, 2)), *known)
        assertEquals(first, build(listOf(Period(1, 1), Period(2, 2)), *known))
        assertTrue(first != build(listOf(Period(1, 2), Period(3, 3)), *known))
    }
}

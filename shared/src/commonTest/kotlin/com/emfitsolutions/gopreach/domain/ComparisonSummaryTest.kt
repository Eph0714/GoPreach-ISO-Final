package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.AttendanceRounding
import com.emfitsolutions.gopreach.data.model.CoAttendanceRow
import com.emfitsolutions.gopreach.data.model.CoAttendanceSnapshot
import com.emfitsolutions.gopreach.data.model.CoReceivedReport
import com.emfitsolutions.gopreach.data.model.CoReceivedRow
import com.emfitsolutions.gopreach.data.model.MeetingType
import com.emfitsolutions.gopreach.domain.AcceptedComparative.MonthState
import com.emfitsolutions.gopreach.domain.AcceptedComparative.Period
import com.emfitsolutions.gopreach.domain.ComparisonSummary.Direction
import com.emfitsolutions.gopreach.domain.ComparisonSummary.Row
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComparisonSummaryTest {
    private val next = { m: Long -> m + 1 }
    private val label = { m: Long -> "M$m" }

    // ---------------------------------------------------------------- rows

    @Test
    fun aRowReportsIncreaseDecreaseUnchangedAndUnavailableSeparately() {
        val up = Row("Up", 120.0, 126.0)
        assertEquals(Direction.INCREASED, up.direction)
        assertEquals(listOf("Up", "120", "126", "+6 ▲", "+5.0%"), listOf(up.name, up.firstText, up.secondText, up.changeText, up.percentText))

        val down = Row("Down", 8.0, 7.0)
        assertEquals(Direction.DECREASED, down.direction)
        assertEquals("-1 ▼", down.changeText)
        assertEquals("-12.5%", down.percentText)

        val same = Row("Same", 5.0, 5.0)
        assertEquals(Direction.UNCHANGED, same.direction)
        assertEquals("0 =", same.changeText)
        assertEquals("0%", same.percentText)

        val missing = Row("Missing", null, 3.0)
        assertEquals(Direction.UNAVAILABLE, missing.direction)
        assertEquals("-", missing.firstText, "an unavailable value is a dash, never a false zero")
        assertEquals("-", missing.changeText)
        assertEquals("-", missing.percentText)
        assertNull(missing.difference)
    }

    @Test
    fun aZeroBaselineHasAChangeButNoPercentageAndNeverDividesByZero() {
        val r = Row("New", 0.0, 4.0)
        assertEquals(Direction.INCREASED, r.direction)
        assertEquals("+4 ▲", r.changeText)
        assertNull(r.percent)
        assertEquals("N/A", r.percentText)
        assertEquals("0%", Row("Still zero", 0.0, 0.0).percentText)
    }

    @Test
    fun averagesAreShownWithOneDecimalOnlyWhenTheyAreNotWholeNumbers() {
        assertEquals("86", ComparisonSummary.number(86.0))
        assertEquals("56.5", ComparisonSummary.number(56.5))
        assertEquals("85", ComparisonSummary.number(85.0))
    }

    // ---------------------------------------------------------------- summary text

    @Test
    fun theSummaryIsShortPlainAndNamesTheRealPeriods() {
        val s = ComparisonSummary.build(
            "August 2026", "September 2026",
            listOf(Row("Total Publishers", 120.0, 126.0), Row("Regular Pioneers", 18.0, 20.0), Row("Unbaptized Publishers", 8.0, 7.0), Row("Bible Studies", 35.0, 35.0)),
        )
        val text = s.paragraphs.joinToString(" ")
        assertTrue(text.contains("August 2026") && text.contains("September 2026"))
        assertTrue(text.contains("Total Publishers increased by 6 (from 120 to 126, +5.0%)"))
        assertTrue(text.contains("Unbaptized Publishers decreased by 1"))
        assertTrue(text.contains("Bible Studies stayed the same"))
        assertTrue(text.contains("The largest change was in Unbaptized Publishers (-12.5%)"), "the largest change is ranked by percentage, up or down")
        assertFalse(text.contains("Period A") || text.contains("Period B"))
        assertFalse(text.contains("Calculation") || text.contains("Percentage Change ="), "no formulas or technical commentary in the report")
        assertFalse(text.contains("improve") || text.contains("good") || text.contains("bad"), "an increase is never called an improvement")
    }

    @Test
    fun aMissingFigureIsNamedAndNeverCountedAsZero() {
        val s = ComparisonSummary.build("M1", "M2", listOf(Row("Up", 1.0, 2.0), Row("Gone", null, 5.0)))
        assertTrue(s.paragraphs.any { it.contains("Gone could not be compared") })
        assertFalse(s.paragraphs.any { it.contains("Gone increased") })
    }

    @Test
    fun everythingUnchangedAndNothingComparableHaveTheirOwnWording() {
        assertTrue(ComparisonSummary.build("M1", "M2", listOf(Row("A", 3.0, 3.0))).paragraphs.single().contains("stayed the same"))
        assertTrue(ComparisonSummary.build("M1", "M2", listOf(Row("A", null, null))).paragraphs.single().contains("No comparison is available"))
    }

    @Test
    fun theTableAndTheUnavailableNoteComeFromTheSameRows() {
        val s = ComparisonSummary.build("M1", "M2", listOf(Row("A", 1.0, 3.0)), unavailable = listOf("Transferees (incoming and outgoing)", "New Publishers"))
        assertEquals(listOf("Metric", "M1", "M2", "Change", "% Change"), s.table().first())
        assertEquals(listOf("A", "1", "3", "+2 ▲", "+200.0%"), s.table()[1])
        assertEquals("Not recorded in the received reports: Transferees (incoming and outgoing), New Publishers.", s.unavailableNote)
        assertNull(ComparisonSummary.build("M1", "M2", listOf(Row("A", 1.0, 3.0))).unavailableNote)
    }

    // ---------------------------------------------------------------- field service

    private fun fsRow(n: Int, status: String, studies: Int) = CoReceivedRow(number = n, status = status, name = "P$n$status", reportsCount = 1, bibleStudies = studies, personId = "p$n$status")

    private fun fsMonth(month: Long, vararg rows: CoReceivedRow) =
        AcceptedComparative.MonthInput(month, MonthState.ACCEPTED, CoReceivedReport(id = "c_${month}_1", congregationId = "c", periodMonth = month, rows = rows.toList()))

    @Test
    fun fieldServiceComparisonShowsTheKeyStatisticsAndNamesWhatIsNotRecorded() {
        val r = AcceptedComparative.build(
            "Cong", listOf(Period(1, 1), Period(2, 2)),
            listOf(fsMonth(1, fsRow(1, "RP", 2), fsRow(2, "Pub", 1), fsRow(3, "UP", 0)), fsMonth(2, fsRow(1, "RP", 3), fsRow(4, "RP", 1), fsRow(2, "Pub", 1), fsRow(5, "AP", 2))),
            next, label,
        )
        val s = ComparisonSummary.forFieldService(r)
        assertEquals(listOf("Total Publishers", "Regular Pioneers", "Auxiliary Pioneers", "Unbaptized Publishers", "Bible Studies"), s.rows.map { it.name })
        assertEquals(listOf("Total Publishers", "3", "4", "+1 ▲", "+33.3%"), s.table()[1])
        assertEquals(listOf("Regular Pioneers", "1", "2", "+1 ▲", "+100.0%"), s.table()[2])
        assertEquals(listOf("Auxiliary Pioneers", "0", "1", "+1 ▲", "N/A"), s.table()[3], "a zero baseline has no percentage")
        assertEquals(listOf("Unbaptized Publishers", "1", "0", "-1 ▼", "-100.0%"), s.table()[4])
        assertEquals(listOf("Bible Studies", "3", "7", "+4 ▲", "+133.3%"), s.table()[5])
        assertEquals(listOf("Transferees (incoming and outgoing)", "New Publishers"), s.unavailable, "no records for these: not shown as zero")
        assertTrue(s.paragraphs.joinToString(" ").contains("M1") && s.paragraphs.joinToString(" ").contains("M2"))
    }

    @Test
    fun aPeriodWithNoReceivedMonthIsUnavailableNotZero() {
        val r = AcceptedComparative.build("Cong", listOf(Period(1, 1), Period(2, 2)), listOf(fsMonth(1, fsRow(1, "RP", 2))), next, label)
        val s = ComparisonSummary.forFieldService(r)
        assertTrue(s.rows.all { it.direction == Direction.UNAVAILABLE })
        assertEquals("-", s.rows.first().secondText)
        assertTrue(s.paragraphs.joinToString(" ").contains("No comparison is available"))
        assertTrue(s.paragraphs.any { it.contains("M2: 0 of 1 month received") })
    }

    @Test
    fun longerPeriodsAreLabelledAsAveragesAndTotals() {
        val r = AcceptedComparative.build(
            "Cong", listOf(Period(1, 2), Period(3, 3)),
            listOf(fsMonth(1, fsRow(1, "RP", 1)), fsMonth(2, fsRow(1, "RP", 1), fsRow(2, "Pub", 1)), fsMonth(3, fsRow(1, "RP", 2))),
            next, label,
        )
        val s = ComparisonSummary.forFieldService(r)
        assertEquals("Total Publishers (monthly avg.)", s.rows.first().name)
        assertEquals("1.5", s.rows.first().firstText)
        assertEquals("Bible Studies (total)", s.rows.last().name)
        assertTrue(s.paragraphs.any { it.contains("different lengths of time") })
    }

    // ---------------------------------------------------------------- attendance

    private fun meeting(type: MeetingType, official: Double) =
        CoAttendanceRow(type, 1L, listOf(official.toInt()), official, official, AttendanceRounding.ROUNDED, "")

    private fun att(month: Long, vararg rows: CoAttendanceRow) =
        AttendanceComparative.MonthInput(month, MonthState.ACCEPTED, CoAttendanceSnapshot(id = "c_${month}_1", congregationId = "c", periodMonth = month, rows = rows.toList()))

    @Test
    fun attendanceComparisonUsesTheChosenPeriodsAndReceivedMonthsOnly() {
        val r = AttendanceComparative.build(
            "Cong", Period(1, 1), Period(2, 2),
            listOf(
                att(1, meeting(MeetingType.MIDWEEK, 80.0), meeting(MeetingType.MIDWEEK, 90.0), meeting(MeetingType.WEEKEND, 100.0)),
                att(2, meeting(MeetingType.MIDWEEK, 100.0), meeting(MeetingType.WEEKEND, 110.0), meeting(MeetingType.WEEKEND, 130.0)),
            ),
            next, label,
        )
        assertEquals(listOf("Metric", "M1", "M2", "Change", "% Change"), r.comparison.first())
        assertEquals(listOf("Midweek Average Attendance", "85", "100", "+15 ▲", "+17.6%"), r.comparison[1])
        assertEquals(listOf("Weekend Average Attendance", "100", "120", "+20 ▲", "+20.0%"), r.comparison[2])
        assertEquals(listOf("Midweek Meetings Recorded", "2", "1", "-1 ▼", "-50.0%"), r.comparison.first { it[0] == "Midweek Meetings Recorded" })
        assertTrue(r.summary.paragraphs.first().contains("M1") && r.summary.paragraphs.first().contains("M2"))
    }

    @Test
    fun theAttendanceSheetListsEveryMeetingOfThePeriodAndClosesWithItsAverage() {
        val r = AttendanceComparative.build(
            "Cong", Period(1, 2), Period(3, 3),
            listOf(
                att(1, CoAttendanceRow(MeetingType.MIDWEEK, 20L, listOf(80, 82, 81), 81.0, 81.0, AttendanceRounding.ROUNDED, ""), meeting(MeetingType.WEEKEND, 100.0)),
                att(2, CoAttendanceRow(MeetingType.MIDWEEK, 10L, listOf(90, 90, 90), 90.0, 90.0, AttendanceRounding.ROUNDED, "")),
            ),
            next, label,
        )
        val tables = AttendanceComparative.sheetTables(r.first) { "D$it" }
        assertEquals(listOf("Midweek Meetings - M1 - M2", "Weekend Meetings - M1 - M2"), tables.map { it.first })
        val midweek = tables[0].second
        assertEquals(listOf("Date", "Treasures", "Apply Yourself", "Living as Christians", "Average", "Official"), midweek.first())
        assertEquals(listOf("D10", "90", "90", "90", "90.00", "90"), midweek[1], "meetings are in date order, with their original counts")
        assertEquals(listOf("D20", "80", "82", "81", "81.00", "81"), midweek[2])
        assertEquals(listOf("Average for the period", "", "", "", "", "85.5"), midweek.last())
        assertTrue(AttendanceComparative.sheetTables(r.second) { "D$it" }.isEmpty(), "a period with no received month has no sheet")
    }

    @Test
    fun aMissingMonthIsNotZeroAndAMeetingTypeNotRecordedHasNoFigure() {
        val r = AttendanceComparative.build(
            "Cong", Period(1, 2), Period(3, 3),
            listOf(att(1, meeting(MeetingType.MIDWEEK, 80.0)), AttendanceComparative.MonthInput(2, MonthState.AWAITING)),
            next, label,
        )
        assertEquals(1, r.first.available)
        assertEquals(listOf(2L), r.first.missing)
        assertNull(r.first.figures[AttendanceComparative.Metric.WEEKEND_AVG], "no weekend meeting recorded: no figure, not 0")
        assertEquals("-", r.comparison.first { it[0] == "Weekend Average Attendance" }[1])
        assertEquals("-", r.comparison.first { it[0] == "Midweek Average Attendance" }[4], "the second period has no data: no percentage")
        assertTrue(r.summary.paragraphs.any { it.contains("M1 - M2: 1 of 2 months received") })
    }
}

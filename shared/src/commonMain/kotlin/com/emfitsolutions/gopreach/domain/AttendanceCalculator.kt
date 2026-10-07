package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.AttendanceRounding
import com.emfitsolutions.gopreach.data.model.CongregationMonthlyStatistics
import com.emfitsolutions.gopreach.data.model.MeetingAttendance
import com.emfitsolutions.gopreach.data.model.MeetingType
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.math.floor
import kotlin.math.roundToLong

/** Attendance arithmetic, written once: average first from the ORIGINAL counts, rounding only afterwards. */
object AttendanceCalculator {

    /** The mathematical mean of [parts] (never rounded before this point). */
    fun average(parts: List<Int>): Double = if (parts.isEmpty()) 0.0 else parts.sum().toDouble() / parts.size

    /** Nearest whole number, halves up (81.49 → 81, 81.50 → 82, 98.50 → 99). */
    fun roundHalfUp(value: Double): Double = floor(value + 0.5)

    /** The official figure for [average] under [mode]: a whole number, or the exact mean to two decimals. */
    fun official(average: Double, mode: AttendanceRounding): Double = when (mode) {
        AttendanceRounding.ROUNDED -> roundHalfUp(average)
        AttendanceRounding.EXACT -> (average * 100.0).roundToLong() / 100.0
    }

    /** "98.50" for an exact figure, "99" for a whole one — what the screens and exports print. */
    fun display(value: Double, mode: AttendanceRounding): String =
        if (mode == AttendanceRounding.ROUNDED) value.roundToLong().toString() else formatTwo(value)

    /** Two decimals, no locale surprises. */
    fun formatTwo(value: Double): String {
        val cents = (value * 100.0).roundToLong()
        val sign = if (cents < 0) "-" else ""
        val abs = kotlin.math.abs(cents)
        return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }

    /** Parses a typed attendance count: digits only, zero or more. Null = invalid (empty, negative, decimal, letters). */
    fun parseCount(text: String): Int? = text.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it.length <= 6 }?.toIntOrNull()

    /** Builds the stored record for a meeting from its original counts (calculated average + official value + the mode used). */
    fun build(
        base: MeetingAttendance,
        parts: List<Int>,
        mode: AttendanceRounding,
    ): MeetingAttendance {
        val avg = average(parts)
        val official = official(avg, mode)
        return when (base.meetingType) {
            MeetingType.MIDWEEK -> base.copy(
                treasuresAttendance = parts[0], applyYourselfAttendance = parts[1], livingAsChristiansAttendance = parts[2],
                publicMeetingAttendance = null, watchtowerStudyAttendance = null,
                calculatedAverage = avg, officialAttendance = official, roundingMode = mode,
            )
            MeetingType.WEEKEND -> base.copy(
                publicMeetingAttendance = parts[0], watchtowerStudyAttendance = parts[1],
                treasuresAttendance = null, applyYourselfAttendance = null, livingAsChristiansAttendance = null,
                calculatedAverage = avg, officialAttendance = official, roundingMode = mode,
            )
        }
    }

    /** Re-applies [mode] to a saved record from its original counts. */
    fun recalculated(record: MeetingAttendance, mode: AttendanceRounding): MeetingAttendance = build(record, record.parts, mode)
}

/** One meeting type's figures for a month (or any period). Missing meetings are never zeros: they are simply not counted. */
data class AttendanceSummary(
    val recorded: Int,
    val expected: Int,
    /** Mean of the official weekly values, null when nothing was recorded. */
    val average: Double?,
    val highest: Double?,
    val lowest: Double?,
) {
    val missing: Int get() = (expected - recorded).coerceAtLeast(0)
}

object AttendanceSummaries {
    /** Summary of [records] (already limited to one meeting type and period, deleted ones excluded). */
    fun of(records: List<MeetingAttendance>, expected: Int): AttendanceSummary {
        val values = records.filter { !it.deleted }.map { it.officialAttendance }
        return AttendanceSummary(
            recorded = values.size,
            expected = expected,
            average = if (values.isEmpty()) null else values.sum() / values.size,
            highest = values.maxOrNull(),
            lowest = values.minOrNull(),
        )
    }

    /**
     * How many meetings of one type a month is expected to have: its (Monday–Sunday) weeks, each assigned to the month that holds the
     * week's Thursday — so 4 or 5. A week is therefore never counted for two months.
     */
    fun expectedMeetings(monthStart: Long, tz: TimeZone = TimeZone.currentSystemDefault()): Int {
        val first = Instant.fromEpochMilliseconds(monthStart).toLocalDateTime(tz).date.let { LocalDate(it.year, it.month, 1) }
        val next = first.plus(1, DateTimeUnit.MONTH)
        var count = 0
        var d = first
        while (d < next) {
            if (d.dayOfWeek == DayOfWeek.THURSDAY) count++
            d = d.plus(1, DateTimeUnit.DAY)
        }
        return count
    }
}

/** One of the statistics the comparative report shows, and how a whole period of months is boiled down to one number. */
enum class ComparativeStat(val label: String, val isAttendance: Boolean = false, val isTotal: Boolean = false) {
    FIELD_SERVICE_REPORTS("Field Service Reports", isTotal = true),
    ELDERS("Elders"),
    MINISTERIAL_SERVANTS("Ministerial Servants"),
    PUBLISHERS("Publishers"),
    AUXILIARY_PIONEERS("Auxiliary Pioneers"),
    REGULAR_PIONEERS("Regular Pioneers"),
    UNBAPTIZED_PUBLISHERS("Unbaptized Publishers"),
    MIDWEEK_ATTENDANCE("Midweek Attendance", isAttendance = true),
    WEEKEND_ATTENDANCE("Weekend Attendance", isAttendance = true);

    /** This statistic's value in one month's snapshot (null = no figure, e.g. no meeting recorded). */
    fun valueIn(s: CongregationMonthlyStatistics): Double? = when (this) {
        FIELD_SERVICE_REPORTS -> s.fieldServiceReportCount.toDouble()
        ELDERS -> s.elderCount.toDouble()
        MINISTERIAL_SERVANTS -> s.ministerialServantCount.toDouble()
        PUBLISHERS -> s.publisherCount.toDouble()
        AUXILIARY_PIONEERS -> s.auxiliaryPioneerCount.toDouble()
        REGULAR_PIONEERS -> s.regularPioneerCount.toDouble()
        UNBAPTIZED_PUBLISHERS -> s.unbaptizedPublisherCount.toDouble()
        MIDWEEK_ATTENDANCE -> s.averageMidweekAttendance
        WEEKEND_ATTENDANCE -> s.averageWeekendAttendance
    }
}

/** A period (a run of months) boiled down per statistic, with the months that had no snapshot reported as missing. */
data class PeriodFigures(
    val monthsWithData: Int,
    val monthsMissing: Int,
    /** The headline number per statistic: report TOTAL, headcount at the END of the period, attendance AVERAGE of the weekly figures. */
    val value: Map<ComparativeStat, Double?>,
    /** Headcounts: first month with data, and the mean over the months. */
    val beginning: Map<ComparativeStat, Double?>,
    val monthlyAverage: Map<ComparativeStat, Double?>,
    val midweekRecorded: Int,
    val weekendRecorded: Int,
    val midweekMissing: Int,
    val weekendMissing: Int,
)

object ComparativeStatistics {
    /**
     * Boils [snapshots] (one per month, already limited to the period) down for a period of [monthCount] months:
     *  - Field Service Reports: the TOTAL over the months (it is report volume);
     *  - Elders / Ministerial Servants / Publishers / pioneer categories: the ENDING count (adding monthly counts would count the
     *    same people repeatedly), with the beginning count and the monthly mean alongside;
     *  - Attendance: the mean of the WEEKLY official figures — each month's average weighted by its recorded meetings, which is
     *    exactly the mean of all the weeks (never a sum, and months with no meeting contribute nothing instead of a zero).
     */
    fun period(snapshots: List<CongregationMonthlyStatistics>, monthCount: Int): PeriodFigures {
        val ordered = snapshots.sortedBy { it.serviceMonth }
        val value = mutableMapOf<ComparativeStat, Double?>()
        val beginning = mutableMapOf<ComparativeStat, Double?>()
        val monthlyAvg = mutableMapOf<ComparativeStat, Double?>()
        for (stat in ComparativeStat.entries) {
            val values = ordered.mapNotNull { stat.valueIn(it) }
            monthlyAvg[stat] = if (values.isEmpty()) null else values.sum() / values.size
            beginning[stat] = values.firstOrNull()
            value[stat] = when {
                stat.isAttendance -> {
                    val weighted = ordered.mapNotNull { s ->
                        val avg = stat.valueIn(s) ?: return@mapNotNull null
                        val n = if (stat == ComparativeStat.MIDWEEK_ATTENDANCE) s.midweekMeetingsRecorded else s.weekendMeetingsRecorded
                        if (n > 0) avg * n to n else null
                    }
                    val meetings = weighted.sumOf { it.second }
                    if (meetings == 0) null else weighted.sumOf { it.first } / meetings
                }
                stat.isTotal -> if (values.isEmpty()) null else values.sum()
                else -> values.lastOrNull()
            }
        }
        return PeriodFigures(
            monthsWithData = ordered.size,
            monthsMissing = (monthCount - ordered.size).coerceAtLeast(0),
            value = value, beginning = beginning, monthlyAverage = monthlyAvg,
            midweekRecorded = ordered.sumOf { it.midweekMeetingsRecorded },
            weekendRecorded = ordered.sumOf { it.weekendMeetingsRecorded },
            midweekMissing = ordered.sumOf { it.midweekMeetingsMissing },
            weekendMissing = ordered.sumOf { it.weekendMeetingsMissing },
        )
    }

    /** Difference (B − A) and the percentage change relative to A; null when either side has no figure, percentage null when A is 0. */
    fun difference(a: Double?, b: Double?): Double? = if (a == null || b == null) null else b - a
    fun percentChange(a: Double?, b: Double?): Double? = if (a == null || b == null || a == 0.0) null else (b - a) / a * 100.0
}

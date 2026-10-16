package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.MeetingType
import kotlin.math.abs

/**
 * The Circuit Overseer's attendance comparison for the ENTIRE circuit: two chosen reporting periods, one row per congregation and
 * meeting type, plus the circuit as a whole. Built only from accepted (received) snapshots, with the same rules as [AttendanceComparative]:
 * a month with no accepted snapshot is missing and never counted as zero; a period's average is the mean of the official figures of every
 * recorded meeting; Difference = second - first; % change = (second - first) / first x 100, or N/A when the first value is zero / unavailable.
 */
object CircuitAttendanceComparative {
    data class CongregationInput(val id: String, val name: String, val months: List<AttendanceComparative.MonthInput>)

    enum class Trend(val label: String, val symbol: String) { INCREASED("Increased", "▲"), DECREASED("Decreased", "▼"), UNCHANGED("Unchanged", "●"), NA("N/A", "-") }

    /** One compared figure: the average attendance of one meeting type in both periods. */
    data class Figure(val first: Double?, val second: Double?) {
        val difference: Double? get() = if (first != null && second != null) second - first else null
        val percentChange: Double? get() = if (first != null && second != null && first != 0.0) (second - first) / first * 100.0 else null
        val trend: Trend get() = when {
            difference == null -> Trend.NA
            abs(difference!!) < 0.00001 -> Trend.UNCHANGED
            difference!! > 0 -> Trend.INCREASED
            else -> Trend.DECREASED
        }
    }

    data class Row(
        val name: String,
        val meetingType: MeetingType,
        val figure: Figure,
        /** Months with a received report, out of the months of each period. */
        val firstAvailable: Int, val firstExpected: Int, val secondAvailable: Int, val secondExpected: Int,
        val isCircuit: Boolean = false,
    )

    data class Result(
        val firstLabel: String,
        val secondLabel: String,
        val firstMonths: Int,
        val secondMonths: Int,
        /** Congregations (Midweek then Weekend rows each) followed by the circuit rows. */
        val rows: List<Row>,
        val circuitRows: List<Row>,
        val congregationsWithData: Int,
        val notes: List<String>,
    )

    private fun pooled(periods: List<AttendanceComparative.PeriodResult>, type: MeetingType): Double? {
        val values = periods.flatMap { p -> p.rows.filter { it.accepted }.mapNotNull { it.input.snapshot }.flatMap { s -> s.rows.filter { it.meetingType == type }.map { it.officialAttendance } } }
        return if (values.isEmpty()) null else values.sum() / values.size
    }

    fun build(
        congregations: List<CongregationInput>,
        first: AcceptedComparative.Period,
        second: AcceptedComparative.Period,
        next: (Long) -> Long,
        monthLabel: (Long) -> String,
    ): Result {
        val perCongregation = congregations.sortedBy { it.name.lowercase() }.map { c ->
            c to AttendanceComparative.build(c.name, first, second, c.months, next, monthLabel)
        }
        val firstLabel = perCongregation.firstOrNull()?.second?.first?.label ?: AcceptedComparative.periodLabel(first, monthLabel)
        val secondLabel = perCongregation.firstOrNull()?.second?.second?.label ?: AcceptedComparative.periodLabel(second, monthLabel)
        val expectedFirst = AcceptedComparative.monthsOf(first, next).size
        val expectedSecond = AcceptedComparative.monthsOf(second, next).size

        val rows = perCongregation.flatMap { (c, r) ->
            listOf(MeetingType.MIDWEEK to AttendanceComparative.Metric.MIDWEEK_AVG, MeetingType.WEEKEND to AttendanceComparative.Metric.WEEKEND_AVG).map { (type, metric) ->
                Row(c.name, type, Figure(r.first.figures[metric], r.second.figures[metric]), r.first.available, r.first.expected, r.second.available, r.second.expected)
            }
        }
        val circuit = MeetingType.entries.map { type ->
            val firstPeriods = perCongregation.map { it.second.first }
            val secondPeriods = perCongregation.map { it.second.second }
            Row(
                "Entire circuit (average per meeting)", type, Figure(pooled(firstPeriods, type), pooled(secondPeriods, type)),
                firstPeriods.sumOf { it.available }, firstPeriods.sumOf { it.expected }, secondPeriods.sumOf { it.available }, secondPeriods.sumOf { it.expected },
                isCircuit = true,
            )
        }
        // The circuit's total weekly attendance: the sum of each congregation's average, only over congregations that have a figure in BOTH periods.
        val totals = MeetingType.entries.map { type ->
            val both = rows.filter { it.meetingType == type && it.figure.first != null && it.figure.second != null }
            Row(
                "Circuit total (sum of congregation averages)", type,
                Figure(both.takeIf { it.isNotEmpty() }?.sumOf { it.figure.first!! }, both.takeIf { it.isNotEmpty() }?.sumOf { it.figure.second!! }),
                both.size, perCongregation.size, both.size, perCongregation.size, isCircuit = true,
            )
        }
        val withData = perCongregation.count { (_, r) -> r.hasAnyData }
        val notes = buildList {
            add("Only months whose attendance report the Circuit Overseer has marked as received are used. A missing month is never counted as zero.")
            add("A congregation's average is the mean of the official attendance of every meeting recorded in its received months.")
            add("Circuit total adds the congregation averages, using only congregations that have a figure in both periods (${totals.firstOrNull()?.firstAvailable ?: 0} of ${perCongregation.size}).")
            perCongregation.filter { (_, r) -> !r.hasAnyData }.takeIf { it.isNotEmpty() }?.let { add("No received report in either period: " + it.joinToString { (c, _) -> c.name } + ".") }
        }
        return Result(firstLabel, secondLabel, expectedFirst, expectedSecond, rows, circuit + totals, withData, notes)
    }

    fun number(v: Double?): String = v?.let { AcceptedComparative.oneDecimal(it) } ?: "-"

    fun signed(d: Double?): String = when {
        d == null -> "N/A"
        abs(d) < 0.00001 -> "0"
        else -> (if (d > 0) "+" else "-") + AcceptedComparative.oneDecimal(abs(d))
    }

    fun percent(p: Double?): String = when {
        p == null -> "N/A"
        abs(p) < 0.05 -> "0.0%"
        else -> (if (p > 0) "+" else "-") + AcceptedComparative.oneDecimal(abs(p)) + "%"
    }

    /** The grid as text, first row = headings. Used by the screen, Print / PDF and Excel so all three match. */
    fun table(r: Result): List<List<String>> {
        val head = listOf("Congregation", "Meeting", r.firstLabel, r.secondLabel, "Difference", "% Change", "Trend", "Months reported (first)", "Months reported (second)")
        fun line(x: Row) = listOf(
            x.name, x.meetingType.label, number(x.figure.first), number(x.figure.second), signed(x.figure.difference), percent(x.figure.percentChange),
            x.figure.trend.symbol + " " + x.figure.trend.label, "${x.firstAvailable} of ${x.firstExpected}", "${x.secondAvailable} of ${x.secondExpected}",
        )
        return listOf(head) + r.rows.map(::line) + r.circuitRows.map(::line)
    }
}

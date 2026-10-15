package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.CoAttendanceSnapshot
import com.emfitsolutions.gopreach.data.model.MeetingType
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The Circuit Overseer's Circuit Attendance comparison: two chosen reporting periods of ONE congregation, compared from the ACCEPTED
 * attendance snapshots only (a month the overseer marked Received) - never the congregation's live records.
 *
 * Rules (also printed on the report): a month without an accepted snapshot is missing, never zero; a period's average attendance is the mean
 * of the official figures of every meeting recorded in its accepted months (so a month with more meetings weighs more, as the meetings do);
 * a meeting type that was not recorded in a period has no figure. Difference = second - first; % change = ((second - first) / first) x 100,
 * or N/A when the first value is zero or either is unavailable.
 */
object AttendanceComparative {
    /** What is known about one service month: [snapshot] is the accepted one (only when [state] is ACCEPTED). */
    data class MonthInput(val month: Long, val state: AcceptedComparative.MonthState, val snapshot: CoAttendanceSnapshot? = null)

    enum class Metric(val label: String, val decimal: Boolean) {
        MIDWEEK_AVG("Midweek Average Attendance", true),
        WEEKEND_AVG("Weekend Average Attendance", true),
        MIDWEEK_HIGH("Midweek Highest", false),
        MIDWEEK_LOW("Midweek Lowest", false),
        WEEKEND_HIGH("Weekend Highest", false),
        WEEKEND_LOW("Weekend Lowest", false),
        MIDWEEK_COUNT("Midweek Meetings Recorded", false),
        WEEKEND_COUNT("Weekend Meetings Recorded", false),
    }

    data class MonthRow(val input: MonthInput, val midweekAverage: Double?, val weekendAverage: Double?, val midweekCount: Int, val weekendCount: Int) {
        val month: Long get() = input.month
        val accepted: Boolean get() = input.state == AcceptedComparative.MonthState.ACCEPTED
    }

    data class PeriodResult(
        val period: AcceptedComparative.Period,
        /** The period as chosen, e.g. "January 2025 - June 2025". */
        val label: String,
        val months: List<Long>,
        val rows: List<MonthRow>,
        val figures: Map<Metric, Double?>,
    ) {
        val expected: Int get() = months.size
        val available: Int get() = rows.count { it.accepted }
        val missing: List<Long> get() = rows.filter { !it.accepted }.map { it.month }
        val complete: Boolean get() = expected > 0 && available == expected
    }

    data class Result(
        val congregationName: String,
        val first: PeriodResult,
        val second: PeriodResult,
        val warnings: List<String>,
        /** First row = headings (Metric | first period | second period | Change | % Change). */
        val comparison: List<List<String>>,
        val monthly: List<List<String>>,
        val summary: ComparisonSummary.Result,
        val notes: List<String>,
    ) {
        val periods: List<PeriodResult> get() = listOf(first, second)
        val hasAnyData: Boolean get() = first.available > 0 || second.available > 0
    }

    private fun valuesOf(snapshots: List<CoAttendanceSnapshot>, type: MeetingType): List<Double> =
        snapshots.flatMap { s -> s.rows.filter { it.meetingType == type }.map { it.officialAttendance } }

    private fun mean(v: List<Double>): Double? = if (v.isEmpty()) null else v.sum() / v.size

    private fun figuresOf(snapshots: List<CoAttendanceSnapshot>): Map<Metric, Double?> {
        val mid = valuesOf(snapshots, MeetingType.MIDWEEK)
        val wk = valuesOf(snapshots, MeetingType.WEEKEND)
        if (snapshots.isEmpty()) return Metric.entries.associateWith { null }
        return mapOf(
            Metric.MIDWEEK_AVG to mean(mid), Metric.WEEKEND_AVG to mean(wk),
            Metric.MIDWEEK_COUNT to mid.size.toDouble(), Metric.WEEKEND_COUNT to wk.size.toDouble(),
            Metric.MIDWEEK_HIGH to mid.maxOrNull(), Metric.MIDWEEK_LOW to mid.minOrNull(),
            Metric.WEEKEND_HIGH to wk.maxOrNull(), Metric.WEEKEND_LOW to wk.minOrNull(),
        )
    }

    private fun periodOf(period: AcceptedComparative.Period, byMonth: Map<Long, MonthInput>, next: (Long) -> Long, monthLabel: (Long) -> String): PeriodResult {
        val months = AcceptedComparative.monthsOf(period, next)
        val rows = months.map { m ->
            val input = byMonth[m] ?: MonthInput(m, AcceptedComparative.MonthState.NOT_SENT)
            val s = input.snapshot.takeIf { input.state == AcceptedComparative.MonthState.ACCEPTED }
            MonthRow(
                input, s?.let { mean(valuesOf(listOf(it), MeetingType.MIDWEEK)) }, s?.let { mean(valuesOf(listOf(it), MeetingType.WEEKEND)) },
                s?.rows?.count { it.meetingType == MeetingType.MIDWEEK } ?: 0, s?.rows?.count { it.meetingType == MeetingType.WEEKEND } ?: 0,
            )
        }
        val accepted = rows.filter { it.accepted }.mapNotNull { it.input.snapshot }
        return PeriodResult(period, AcceptedComparative.periodLabel(period, monthLabel), months, rows, figuresOf(accepted))
    }

    private fun show(v: Double?, m: Metric): String = when {
        v == null -> "-"
        m.decimal -> AcceptedComparative.oneDecimal(v)
        else -> v.roundToLong().toString()
    }

    private fun signed(d: Double?, m: Metric): String = when {
        d == null -> "N/A"
        abs(d) < 0.00001 -> "0"
        else -> (if (d > 0) "+" else "-") + show(abs(d), m)
    }

    private fun pct(p: Double?): String = when {
        p == null -> "N/A"
        abs(p) < 0.05 -> "0.0%"
        else -> (if (p > 0) "+" else "-") + AcceptedComparative.oneDecimal(abs(p)) + "%"
    }

    /**
     * The consolidated attendance of one period, like the Field Service sheet of a period: every meeting recorded in its received / sent months,
     * Midweek and Weekend each in its own table (original counts, average, official figure), closed by the period's average. One table is
     * left out when that meeting type has no record. The same tables are shown on screen and printed.
     */
    fun sheetTables(p: PeriodResult, dateLabel: (Long) -> String): List<Pair<String, List<List<String>>>> {
        val snapshots = p.rows.filter { it.accepted }.mapNotNull { it.input.snapshot }
        fun block(type: MeetingType, parts: List<String>, average: Metric): Pair<String, List<List<String>>>? {
            val meetings = snapshots.flatMap { s -> s.rows.filter { it.meetingType == type } }.sortedBy { it.meetingDate }
            if (meetings.isEmpty()) return null
            val header = listOf("Date") + parts + listOf("Average", "Official")
            val body = meetings.map { r ->
                listOf(dateLabel(r.meetingDate)) + parts.indices.map { i -> r.parts.getOrNull(i)?.toString().orEmpty() } +
                    listOf(AttendanceCalculator.formatTwo(r.calculatedAverage), AttendanceCalculator.display(r.officialAttendance, r.roundingMode))
            }
            val total = listOf("Average for the period") + List(parts.size) { "" } + listOf("", show(p.figures[average], average))
            return "${type.label} Meetings - ${p.label}" to (listOf(header) + body + listOf(total))
        }
        return listOfNotNull(
            block(MeetingType.MIDWEEK, listOf("Treasures", "Apply Yourself", "Living as Christians"), Metric.MIDWEEK_AVG),
            block(MeetingType.WEEKEND, listOf("Public Meeting", "Watchtower Study"), Metric.WEEKEND_AVG),
        )
    }

    fun build(
        congregationName: String,
        first: AcceptedComparative.Period,
        second: AcceptedComparative.Period,
        known: List<MonthInput>,
        next: (Long) -> Long,
        monthLabel: (Long) -> String,
        noun: String = "received",
    ): Result {
        val byMonth = known.associateBy { it.month }
        val a = periodOf(first, byMonth, next, monthLabel)
        val b = periodOf(second, byMonth, next, monthLabel)

        val rows = Metric.entries.map { ComparisonSummary.Row(it.label, a.figures[it], b.figures[it]) }
        val coverage = buildList {
            listOf(a, b).filter { !it.complete }.forEach { r ->
                add("${r.label}: ${r.available} of ${r.expected} month${if (r.expected == 1) "" else "s"} $noun" + if (r.available == 0) "." else " (missing: ${r.missing.size}).")
            }
        }
        val summary = ComparisonSummary.build(a.label, b.label, rows, coverage = coverage, noun = noun)
        val comparison = summary.table()

        val cols = listOf("Period / Month", "Status", "Midweek average", "Weekend average", "Midweek meetings", "Weekend meetings")
        val monthly = buildList {
            add(cols)
            listOf(a, b).forEach { r ->
                add(listOf("${r.label} (${r.expected} months, ${r.available} $noun)") + List(cols.size - 1) { "" })
                r.rows.forEach { row ->
                    val status = if (row.accepted) noun.replaceFirstChar { it.uppercase() } else row.input.state.label
                    add(
                        listOf(
                            monthLabel(row.month), status,
                            if (row.accepted) show(row.midweekAverage, Metric.MIDWEEK_AVG) else "-", if (row.accepted) show(row.weekendAverage, Metric.WEEKEND_AVG) else "-",
                            if (row.accepted) row.midweekCount.toString() else "-", if (row.accepted) row.weekendCount.toString() else "-",
                        ),
                    )
                }
            }
        }
        return Result(congregationName, a, b, emptyList(), comparison, monthly, summary, emptyList())    }
}

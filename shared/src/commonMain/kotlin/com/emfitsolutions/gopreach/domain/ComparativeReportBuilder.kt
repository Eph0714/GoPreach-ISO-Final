package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.AttendanceRounding
import com.emfitsolutions.gopreach.data.model.ComparativeSnapshot
import com.emfitsolutions.gopreach.data.model.CongregationMonthlyStatistics
import kotlin.math.abs
import kotlin.math.roundToLong

/** Why a pair of periods cannot be used for a Comparative Report (null from [validate] = fine). */
object ComparativePeriods {
    /** Checks both ranges: ordered, not in the future, and not overlapping each other. [monthStartNow] is the first instant of the current month. */
    fun validate(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long, monthStartNow: Long): String? = when {
        aStart > aEnd -> "Period A: the From month cannot be after the To month."
        bStart > bEnd -> "Period B: the From month cannot be after the To month."
        aEnd > monthStartNow || bEnd > monthStartNow -> "Future months cannot be selected."
        aStart <= bEnd && bStart <= aEnd -> "Period A and Period B must not overlap."
        else -> null
    }
}

/** Builds the saved content of a Comparative Report from the congregation's historical monthly snapshots (never from today's publisher data). */
object ComparativeReportBuilder {

    /** Months from [from] to [to] (inclusive, first-of-month millis), via [next]. */
    fun monthsBetween(from: Long, to: Long, next: (Long) -> Long): List<Long> {
        val out = mutableListOf<Long>()
        var m = from
        while (m <= to && out.size < 120) { out += m; m = next(m) }
        return out
    }

    fun formatValue(stat: ComparativeStat, v: Double?, mode: AttendanceRounding): String = when {
        v == null -> "—"
        stat.isAttendance -> AttendanceCalculator.display(if (mode == AttendanceRounding.ROUNDED) AttendanceCalculator.roundHalfUp(v) else v, mode)
        else -> v.roundToLong().toString()
    }

    private fun oneDecimal(v: Double): String { val t = (abs(v) * 10).roundToLong(); return (if (v < 0) "-" else "") + "${t / 10}.${t % 10}" }

    private fun signed(d: Double?, stat: ComparativeStat, mode: AttendanceRounding): String =
        d?.let { (if (it > 0.00001) "+" else if (it < -0.00001) "-" else "") + formatValue(stat, abs(it), mode) } ?: "—"

    private fun percent(p: Double?): String = p?.let { (if (it > 0.00001) "+" else if (it < -0.00001) "-" else "") + oneDecimal(abs(it)) + "%" } ?: "—"

    private fun coverage(label: String, p: PeriodFigures, months: Int) =
        "$label: ${p.monthsWithData} of $months months have data" + (if (p.monthsMissing > 0) " · ${p.monthsMissing} Missing" else "") +
            " · Midweek ${p.midweekRecorded} recorded / ${p.midweekMissing} missing · Weekend ${p.weekendRecorded} recorded / ${p.weekendMissing} missing"

    /**
     * [stats] are all of the congregation's saved snapshots; the two periods pick theirs out. [monthLabel] formats a month; [next] steps one month.
     * Returns the pre-formatted report and the ids of the snapshots it used.
     */
    fun build(
        congregationName: String,
        stats: List<CongregationMonthlyStatistics>,
        aStart: Long, aEnd: Long, bStart: Long, bEnd: Long,
        mode: AttendanceRounding,
        generatedBy: String, generatedAt: String,
        monthLabel: (Long) -> String,
        next: (Long) -> Long,
    ): Pair<ComparativeSnapshot, List<String>> {
        val monthsA = monthsBetween(aStart, aEnd, next)
        val monthsB = monthsBetween(bStart, bEnd, next)
        val inA = stats.filter { it.serviceMonth in monthsA }
        val inB = stats.filter { it.serviceMonth in monthsB }
        val a = ComparativeStatistics.period(inA, monthsA.size)
        val b = ComparativeStatistics.period(inB, monthsB.size)
        val labelA = periodLabel(aStart, aEnd, monthLabel)
        val labelB = periodLabel(bStart, bEnd, monthLabel)

        val comparison = listOf(listOf("Statistic", "Period A", "Period B", "Difference", "% Change")) + ComparativeStat.entries.map { s ->
            val va = a.value[s]; val vb = b.value[s]
            listOf(
                s.label + if (s.isTotal) " (total)" else "", formatValue(s, va, mode), formatValue(s, vb, mode),
                signed(ComparativeStatistics.difference(va, vb), s, mode), percent(ComparativeStatistics.percentChange(va, vb)),
            )
        }
        val range = monthsBetween(minOf(aStart, bStart), maxOf(aEnd, bEnd), next)
        val monthly = listOf(listOf("Statistic") + range.map(monthLabel)) + ComparativeStat.entries.map { s ->
            listOf(s.label) + range.map { m -> stats.firstOrNull { it.serviceMonth == m }?.let { snap -> formatValue(s, s.valueIn(snap), snap.attendanceRoundingMode) } ?: "—" }
        }
        val missing = (monthsA + monthsB).filter { m -> stats.none { it.serviceMonth == m } }.map(monthLabel)
        val notes = listOf(
            coverage("Period A ($labelA)", a, monthsA.size),
            coverage("Period B ($labelB)", b, monthsB.size),
            "Reports are totals; headcounts are the count at the end of each period; attendance is the average of the weekly figures (never a sum). A month without a saved snapshot shows — and is not counted.",
        )
        val summary = listOf(listOf("Metric", "Value"), listOf("Period A", labelA), listOf("Period B", labelB)) +
            comparison.drop(1).map { r -> listOf(r[0].removeSuffix(" (total)") + " Difference", r[3] + if (r[4] != "—") " (" + r[4] + ")" else "") } +
            listOf(listOf("Months Missing Data", missing.size.toString()))
        val snapshot = ComparativeSnapshot(
            congregationName = congregationName, periodA = labelA, periodB = labelB, generatedBy = generatedBy, generatedAt = generatedAt,
            calculationMode = mode.label, comparison = comparison, monthly = monthly, notes = notes, missingMonths = missing, summary = summary,
        )
        return snapshot to (inA + inB).sortedBy { it.serviceMonth }.map { it.id }
    }

    fun periodLabel(from: Long, to: Long, monthLabel: (Long) -> String) =
        if (from == to) monthLabel(from) else monthLabel(from) + " – " + monthLabel(to)
}

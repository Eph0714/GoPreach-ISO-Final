package com.emfitsolutions.gopreach.domain

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What the Circuit Overseer sees after a comparison: the compared figures as clean rows (previous value, current value, change, percentage,
 * direction) and a short plain-language summary written from those rows alone. Nothing is invented: a figure that has no data in one of the
 * periods is "unavailable" (never a false zero), a percentage is only calculated when the first period's value is not zero, and an increase or
 * decrease is reported as what it is - never as good or bad.
 */
object ComparisonSummary {
    enum class Direction(val symbol: String) { INCREASED("▲"), DECREASED("▼"), UNCHANGED("="), UNAVAILABLE("–") }

    /** One compared figure. [first] / [second] are null when that period has no data for it. */
    data class Row(val name: String, val first: Double?, val second: Double?) {
        val difference: Double? get() = if (first == null || second == null) null else second - first

        /** ((second - first) / first) x 100; null when the first value is zero or either value is unavailable. */
        val percent: Double? get() = if (first == null || second == null || abs(first) < EPSILON) null else (second - first) / first * 100.0

        val direction: Direction
            get() {
                val d = difference ?: return Direction.UNAVAILABLE
                return when { d > EPSILON -> Direction.INCREASED; d < -EPSILON -> Direction.DECREASED; else -> Direction.UNCHANGED }
            }

        val firstText: String get() = first?.let(::number) ?: "-"
        val secondText: String get() = second?.let(::number) ?: "-"
        val differenceText: String get() = difference?.let { if (abs(it) < EPSILON) "0" else signed(it) } ?: "-"
        val percentText: String get() = when {
            difference == null -> "-"
            abs(difference!!) < EPSILON -> "0%"
            percent == null -> "N/A"
            else -> signedPercent(percent!!)
        }
        /** "+6 ▲" - what the Change column and the exports show. */
        val changeText: String get() = if (direction == Direction.UNAVAILABLE) "-" else "$differenceText ${direction.symbol}"
    }

    data class Result(
        val firstLabel: String,
        val secondLabel: String,
        val rows: List<Row>,
        /** Figures that are not recorded in the reports at all (listed once, never shown as zero). */
        val unavailable: List<String>,
        /** The summary: a few short paragraphs, most important first. */
        val paragraphs: List<String>,
        /** What the compared reports are called to the reader: "received" for the Circuit Overseer, "sent" for a congregation's own admins. */
        val noun: String = "received",
    ) {
        /** Metric | first period | second period | Change | % Change - the same table on screen, in the list view, in Print / PDF and in Excel. */
        fun table(): List<List<String>> =
            listOf(listOf("Metric", firstLabel, secondLabel, "Change", "% Change")) + rows.map { listOf(it.name, it.firstText, it.secondText, it.changeText, it.percentText) }

        /** The line printed under the table about figures that are not recorded, or null when there are none. */
        val unavailableNote: String? get() = unavailable.takeIf { it.isNotEmpty() }?.let { "Not recorded in the $noun reports: ${it.joinToString(", ")}." }
    }

    private const val EPSILON = 0.00001

    /** 86 -> "86", 56.5 -> "56.5": whole values without a decimal, others with one. */
    fun number(v: Double): String = if (abs(v - v.roundToLong()) < 0.05) v.roundToLong().toString() else AcceptedComparative.oneDecimal(v)

    private fun signed(v: Double): String = (if (v > 0) "+" else "-") + number(abs(v))

    private fun signedPercent(p: Double): String = (if (p > 0) "+" else "-") + AcceptedComparative.oneDecimal(abs(p)) + "%"

    private fun clause(r: Row): String {
        val d = r.difference!!
        val verb = if (d > 0) "increased" else "decreased"
        val from = "from ${r.firstText} to ${r.secondText}" + (r.percent?.let { ", ${signedPercent(it)}" } ?: "")
        return "${r.name} $verb by ${number(abs(d))} ($from)"
    }

    private fun joinNames(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    /** How much a change matters when ranking: the percentage when there is one, otherwise the size of the change (a move away from zero ranks high). */
    private fun weight(r: Row): Double = r.percent?.let { abs(it) } ?: abs(r.difference ?: 0.0).let { if (it > 0) 1000.0 + it else 0.0 }

    fun build(
        firstLabel: String,
        secondLabel: String,
        rows: List<Row>,
        unavailable: List<String> = emptyList(),
        /** Short factual lines about the data itself (an incomplete period); added to the end of the summary as they are. */
        coverage: List<String> = emptyList(),
        noun: String = "received",
    ): Result {
        val compared = rows.filter { it.direction != Direction.UNAVAILABLE }
        val changed = compared.filter { it.direction != Direction.UNCHANGED }.sortedByDescending(::weight)
        val same = compared.filter { it.direction == Direction.UNCHANGED }
        val missing = rows.filter { it.direction == Direction.UNAVAILABLE }

        val paragraphs = buildList {
            when {
                compared.isEmpty() -> add("No comparison is available between $firstLabel and $secondLabel, because at least one of the periods has no $noun report data.")
                changed.isEmpty() -> add("Between $firstLabel and $secondLabel, every compared figure stayed the same.")
                else -> {
                    add("Between $firstLabel and $secondLabel: " + changed.take(MAX_CLAUSES).joinToString(". ") { clause(it) } + ".")
                    if (changed.size > MAX_CLAUSES) add("${changed.size - MAX_CLAUSES} other figure${if (changed.size - MAX_CLAUSES == 1) "" else "s"} also changed; see the table.")
                }
            }
            if (changed.isNotEmpty() && same.isNotEmpty()) add("${joinNames(same.map { it.name })} stayed the same.")
            if (changed.size >= 3) {
                val top = changed.first()
                add("The largest change was in ${top.name}" + (top.percent?.let { " (${signedPercent(it)})" } ?: " (${top.differenceText})") + ".")
            }
            if (compared.isNotEmpty() && missing.isNotEmpty()) add("${joinNames(missing.map { it.name })} could not be compared, because one of the periods has no data for ${if (missing.size == 1) "it" else "them"}.")
            addAll(coverage)
        }
        return Result(firstLabel, secondLabel, rows, unavailable, paragraphs, noun)
    }

    private const val MAX_CLAUSES = 5

    /** The Field Service Report comparison: the key publisher statistics, first period against second, from the accepted snapshots. */
    fun forFieldService(result: AcceptedComparative.Result, noun: String = "received"): Result {
        require(result.periods.size == 2) { "A comparison covers exactly two periods." }
        val a = result.periods[0]
        val b = result.periods[1]
        val multi = a.expected > 1 || b.expected > 1
        fun avg(m: AcceptedComparative.Metric, p: AcceptedComparative.PeriodResult) = p.figures.getValue(m).average
        fun headcount(name: String, m: AcceptedComparative.Metric) = Row(if (multi) "$name (monthly avg.)" else name, avg(m, a), avg(m, b))
        val rows = listOf(
            headcount("Total Publishers", AcceptedComparative.Metric.REPORTING),
            headcount("Regular Pioneers", AcceptedComparative.Metric.REGULAR_PIONEERS),
            headcount("Auxiliary Pioneers", AcceptedComparative.Metric.AUXILIARY_PIONEERS),
            headcount("Unbaptized Publishers", AcceptedComparative.Metric.UNBAPTIZED),
            Row(if (multi) "Bible Studies (total)" else "Bible Studies", a.figures.getValue(AcceptedComparative.Metric.BIBLE_STUDIES).total, b.figures.getValue(AcceptedComparative.Metric.BIBLE_STUDIES).total),
        )
        val coverage = buildList {
            listOf(a, b).filter { !it.complete }.forEach { p ->
                add("${p.label}: ${p.available} of ${p.expected} month${if (p.expected == 1) "" else "s"} $noun" + if (p.available == 0) "." else " (missing: ${p.missing.size}).")
            }
            if (a.expected != b.expected) add("The periods cover ${a.expected} and ${b.expected} months, so the Bible Studies totals are for different lengths of time.")
        }
        // The received reports carry no transfer or new-publisher records, so these two are listed as not recorded instead of shown as zero.
        return build(a.label, b.label, rows, unavailable = listOf("Transferees (incoming and outgoing)", "New Publishers"), coverage = coverage, noun = noun)
    }
}

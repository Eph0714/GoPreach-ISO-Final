package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.CoReceivedReport
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The Circuit Overseer's dynamic Comparative Report: any number of manually chosen reporting periods of ONE congregation, compared
 * using only the ACCEPTED monthly snapshots (a month the Circuit Overseer marked Received) - never live congregation records.
 *
 * Calculation rules (also printed on every report):
 *  - A month without an accepted snapshot is MISSING: it shows "-" and is never counted as zero, never replaced by another month.
 *  - Headcounts (publishers, pioneers, ...) are monthly counts. A period's figure is the MONTHLY AVERAGE over its accepted months;
 *    monthly headcounts are never summed.
 *  - Hours and Bible Studies are additive: a period shows both its TOTAL and its monthly average (over accepted months).
 *  - Two periods: Difference = B - A; % change = (B - A) / A x 100, or N/A when A is zero or either value is unavailable.
 *    Difference and % change are computed from exactly the figures shown, so a period with missing months is compared on what it
 *    has, and the report says so. Totals of periods of different length or coverage are not directly comparable: compare averages.
 *  - Three or more periods are each calculated independently and shown side by side.
 */
object AcceptedComparative {
    const val MAX_PERIODS = 6
    const val MAX_MONTHS_PER_PERIOD = 36

    /** One chosen period: first-of-month millis of its first and last month. */
    data class Period(val from: Long, val to: Long)

    enum class MonthState(val label: String) {
        ACCEPTED("Accepted"),
        /** Sent by the congregation but not yet marked Received by the overseer. */
        AWAITING("Awaiting acceptance"),
        RETURNED("Returned for correction"),
        CANCELLED("Cancelled by the Circuit Overseer"),
        NOT_SENT("No snapshot sent"),
        /** Marked Received, but the frozen copy is not on file (a month received before copies were kept). */
        NO_COPY("Accepted - copy not on file"),
    }

    /** What is known about one service month of the congregation. [report] is the accepted snapshot (only when [state] is ACCEPTED). */
    data class MonthInput(val month: Long, val state: MonthState, val report: CoReceivedReport? = null)

    enum class Metric(val label: String, val additive: Boolean) {
        REPORTING("Publishers reporting (all)", false),
        PUBLISHERS("Publishers", false),
        UNBAPTIZED("Unbaptized Publishers", false),
        REGULAR_PIONEERS("Regular Pioneers", false),
        AUXILIARY_PIONEERS("Auxiliary Pioneers", false),
        SPECIAL_PIONEERS("Special Pioneers", false),
        OTHER("Other statuses", false),
        BIBLE_STUDIES("Bible Studies", true),
        HOURS("Reported hours", true),
    }

    private val PUBLISHER_LABELS = setOf("Pub")
    private val UNBAPTIZED_LABELS = setOf("UP")
    private val RP_LABELS = setOf("RP")
    private val AP_LABELS = setOf("AP")
    private val SP_LABELS = setOf("SP")

    /** The value of [metric] in one accepted snapshot. */
    fun valueIn(metric: Metric, report: CoReceivedReport): Double = when (metric) {
        Metric.REPORTING -> report.rows.size.toDouble()
        Metric.PUBLISHERS -> report.rows.count { it.status in PUBLISHER_LABELS }.toDouble()
        Metric.UNBAPTIZED -> report.rows.count { it.status in UNBAPTIZED_LABELS }.toDouble()
        Metric.REGULAR_PIONEERS -> report.rows.count { it.status in RP_LABELS }.toDouble()
        Metric.AUXILIARY_PIONEERS -> report.rows.count { it.status in AP_LABELS }.toDouble()
        Metric.SPECIAL_PIONEERS -> report.rows.count { it.status in SP_LABELS }.toDouble()
        Metric.OTHER -> report.rows.count { it.status !in PUBLISHER_LABELS + UNBAPTIZED_LABELS + RP_LABELS + AP_LABELS + SP_LABELS }.toDouble()
        Metric.BIBLE_STUDIES -> report.rows.sumOf { it.bibleStudies ?: 0 }.toDouble()
        Metric.HOURS -> report.rows.sumOf { it.hours ?: 0.0 }
    }

    data class MonthRow(val input: MonthInput, val values: Map<Metric, Double>) {
        val month: Long get() = input.month
        val accepted: Boolean get() = input.state == MonthState.ACCEPTED
    }

    data class MetricFigure(val total: Double?, val average: Double?)

    data class PeriodResult(
        val index: Int,
        val period: Period,
        val label: String,
        val months: List<Long>,
        val rows: List<MonthRow>,
        val figures: Map<Metric, MetricFigure>,
        /** Distinct people who appear in at least one accepted snapshot (each counted once), and whether some had to be matched by name. */
        val distinctReporters: Int?,
        val distinctApproximate: Boolean,
    ) {
        val expected: Int get() = months.size
        val available: Int get() = rows.count { it.accepted }
        val missing: List<Long> get() = rows.filter { !it.accepted }.map { it.month }
        val complete: Boolean get() = expected > 0 && available == expected
        val letter: String get() = ('A' + index).toString()
    }

    data class Result(
        val congregationName: String,
        val periods: List<PeriodResult>,
        val warnings: List<String>,
        val comparison: List<List<String>>,
        val monthly: List<List<String>>,
        val summary: List<List<String>>,
        val notes: List<String>,
    ) {
        val hasAnyData: Boolean get() = periods.any { it.available > 0 }
    }

    /** Why the chosen periods cannot be compared (null = fine). Periods must be ordered, not in the future, and must not overlap. */
    fun validate(periods: List<Period>, monthStartNow: Long, monthsOf: (Period) -> Int): String? {
        if (periods.size < 2) return "Choose at least two reporting periods."
        if (periods.size > MAX_PERIODS) return "At most $MAX_PERIODS periods can be compared."
        periods.forEachIndexed { i, p ->
            val name = "Period " + ('A' + i)
            if (p.from > p.to) return "$name: the From month cannot be after the To month."
            if (p.to > monthStartNow) return "$name: future months cannot be selected."
            if (monthsOf(p) > MAX_MONTHS_PER_PERIOD) return "$name: a period can include at most $MAX_MONTHS_PER_PERIOD months."
        }
        for (i in periods.indices) for (j in i + 1 until periods.size) {
            if (periods[i].from <= periods[j].to && periods[j].from <= periods[i].to) {
                return "Period ${'A' + i} and Period ${'A' + j} must not overlap."
            }
        }
        return null
    }

    /** Every month from [from] to [to], oldest first, stepping with [next]. */
    fun monthsOf(period: Period, next: (Long) -> Long): List<Long> {
        val out = mutableListOf<Long>()
        var m = period.from
        while (m <= period.to && out.size <= MAX_MONTHS_PER_PERIOD) { out += m; m = next(m) }
        return out
    }

    private fun personKey(row: com.emfitsolutions.gopreach.data.model.CoReceivedRow) = if (row.personId.isNotBlank()) "id:" + row.personId else "name:" + row.name.trim().lowercase()

    /**
     * Builds the whole report for [periods] from what is known about each month in [known] (anything not listed counts as "no snapshot
     * sent"). Every figure, table and warning comes from these inputs alone, so a new selection always produces a new, complete report.
     */
    fun build(
        congregationName: String,
        periods: List<Period>,
        known: List<MonthInput>,
        next: (Long) -> Long,
        monthLabel: (Long) -> String,
    ): Result {
        val byMonth = known.associateBy { it.month }
        val results = periods.mapIndexed { index, period ->
            val months = monthsOf(period, next)
            val rows = months.map { m ->
                val input = byMonth[m] ?: MonthInput(m, MonthState.NOT_SENT)
                val values = if (input.state == MonthState.ACCEPTED && input.report != null) Metric.entries.associateWith { valueIn(it, input.report) } else emptyMap()
                MonthRow(input, values)
            }
            val accepted = rows.filter { it.accepted }
            val figures = Metric.entries.associateWith { metric ->
                if (accepted.isEmpty()) MetricFigure(null, null)
                else {
                    val sum = accepted.sumOf { it.values.getValue(metric) }
                    MetricFigure(if (metric.additive) sum else null, sum / accepted.size)
                }
            }
            val people = accepted.flatMap { it.input.report?.rows.orEmpty() }
            val approximate = people.any { it.personId.isBlank() }
            PeriodResult(
                index, period, periodLabel(period, monthLabel), months, rows, figures,
                distinctReporters = if (accepted.isEmpty()) null else people.map(::personKey).toSet().size,
                distinctApproximate = approximate,
            )
        }

        val warnings = buildList {
            results.forEach { r ->
                when {
                    r.available == 0 -> add("Period ${r.letter} (${r.label}): none of its ${r.expected} months has an accepted snapshot, so it has no figures.")
                    !r.complete -> add("Period ${r.letter} (${r.label}): only ${r.available} of ${r.expected} months have an accepted snapshot - missing: ${r.missing.joinToString(", ", transform = monthLabel)}. Its figures use only the accepted months.")
                }
            }
            val lengths = results.map { it.expected }.distinct()
            if (lengths.size > 1) add("The periods have different lengths (${results.joinToString(" vs ") { it.expected.toString() }} months): compare monthly averages, not totals.")
            val coverage = results.filter { it.available > 0 }.map { it.available }.distinct()
            if (coverage.size > 1 && lengths.size == 1) add("The periods have different numbers of accepted months (${results.joinToString(" vs ") { it.available.toString() }}): compare monthly averages, not totals.")
            if (results.any { it.distinctApproximate }) add("Distinct-person counts for some snapshots are matched by name because those snapshots were sent before publisher ids were kept.")
        }

        val comparison = comparisonTable(results)
        val monthly = monthlyTable(results, monthLabel)
        val notes = listOf(
            "Calculation: headcounts are monthly counts - each period shows the monthly average over its accepted months, never a sum of headcounts. Bible Studies and hours are additive - each period shows its total and its monthly average. A month without an accepted snapshot is missing: it shows - and is not counted as zero.",
        ) + (if (results.size == 2) listOf("Difference = Period B - Period A. % Change = (B - A) / A x 100; N/A when Period A is zero or a value is unavailable.") else listOf("Each period is calculated independently; the periods are shown side by side."))
        val summary = summaryTable(results, comparison)
        return Result(congregationName, results, warnings, comparison, monthly, summary, notes)
    }

    fun periodLabel(p: Period, monthLabel: (Long) -> String) = if (p.from == p.to) monthLabel(p.from) else monthLabel(p.from) + " - " + monthLabel(p.to)

    // ---------------------------------------------------------------- formatting

    fun oneDecimal(v: Double): String {
        val t = (abs(v) * 10).roundToLong()
        return (if (v < 0 && t != 0L) "-" else "") + (t / 10) + "." + (t % 10)
    }

    private fun show(v: Double?, metric: Metric, total: Boolean): String = when {
        v == null -> "-"
        metric == Metric.HOURS -> oneDecimal(v)
        total -> v.roundToLong().toString()
        else -> oneDecimal(v)
    }

    /** B - A, or null when either is unavailable. */
    fun difference(a: Double?, b: Double?): Double? = if (a == null || b == null) null else b - a

    /** ((B - A) / A) x 100, or null (shown as N/A) when A is zero or either is unavailable. */
    fun percentChange(a: Double?, b: Double?): Double? = if (a == null || b == null || a == 0.0) null else (b - a) / a * 100.0

    private fun signed(d: Double?, metric: Metric, total: Boolean): String = when {
        d == null -> "N/A"
        abs(d) < 0.00001 -> "0"
        else -> (if (d > 0) "+" else "-") + show(abs(d), metric, total)
    }

    private fun pct(p: Double?): String = when {
        p == null -> "N/A"
        abs(p) < 0.05 -> "0.0%"
        else -> (if (p > 0) "+" else "-") + oneDecimal(abs(p)) + "%"
    }

    private fun comparisonTable(results: List<PeriodResult>): List<List<String>> {
        val two = results.size == 2
        val header = listOf("Metric") + results.map { "Period ${it.letter} (${it.available}/${it.expected} months)" } + (if (two) listOf("Difference", "% Change") else emptyList())
        fun row(label: String, values: List<Double?>, metric: Metric, total: Boolean): List<String> {
            val cells = values.map { show(it, metric, total) }
            val extra = if (two) listOf(signed(difference(values[0], values[1]), metric, total), pct(percentChange(values[0], values[1]))) else emptyList()
            return listOf(label) + cells + extra
        }
        val rows = mutableListOf<List<String>>()
        Metric.entries.forEach { m ->
            if (m.additive) rows += row(m.label + " - total", results.map { it.figures.getValue(m).total }, m, total = true)
            rows += row(m.label + " - monthly average", results.map { it.figures.getValue(m).average }, m, total = false)
        }
        val distinct = results.map { it.distinctReporters?.toDouble() }
        rows += row("Distinct people who reported" + if (results.any { it.distinctApproximate }) " (approx.)" else "", distinct, Metric.REPORTING, total = true)
        rows += listOf("Accepted months") + results.map { "${it.available} of ${it.expected}" } + (if (two) listOf("", "") else emptyList())
        return listOf(header) + rows
    }

    private fun monthlyTable(results: List<PeriodResult>, monthLabel: (Long) -> String): List<List<String>> {
        val cols = listOf("Period / Month", "Snapshot", "Publishers", "Regular Pioneers", "Auxiliary Pioneers", "Bible Studies", "Hours")
        val out = mutableListOf(cols)
        results.forEach { r ->
            out += listOf("Period ${r.letter}: ${r.label} (${r.expected} months, ${r.available} accepted)") + List(cols.size - 1) { "" }
            r.rows.forEach { row ->
                val status = if (row.accepted) "Accepted - send ${row.input.report?.version ?: ""}" else row.input.state.label
                fun cell(m: Metric) = if (row.accepted) show(row.values[m], m, total = true) else "-"
                out += listOf(monthLabel(row.month), status, cell(Metric.PUBLISHERS), cell(Metric.REGULAR_PIONEERS), cell(Metric.AUXILIARY_PIONEERS), cell(Metric.BIBLE_STUDIES), cell(Metric.HOURS))
            }
        }
        return out
    }

    private fun summaryTable(results: List<PeriodResult>, comparison: List<List<String>>): List<List<String>> {
        val out = mutableListOf(listOf("Metric", "Value"))
        results.forEach { r ->
            out += listOf("Period ${r.letter}", "${r.label} - ${r.expected} months, ${r.available} accepted" + if (r.missing.isNotEmpty()) ", ${r.missing.size} missing" else "")
        }
        if (results.size == 2) {
            comparison.drop(1).filter { it.size >= 5 && (it[0].endsWith(" - total") || it[0].endsWith(" - monthly average")) }.forEach { r ->
                out += listOf(r[0] + " difference", r[3] + if (r[4] != "N/A") " (" + r[4] + ")" else "")
            }
        }
        out += listOf("Months without an accepted snapshot", results.sumOf { it.missing.size }.toString())
        return out
    }
}

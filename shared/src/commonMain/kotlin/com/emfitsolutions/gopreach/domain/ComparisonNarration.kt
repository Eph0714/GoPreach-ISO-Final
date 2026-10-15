package com.emfitsolutions.gopreach.domain

import kotlin.math.abs

/**
 * What the Circuit Overseer hears when the generated comparison is read aloud: a list of short spoken sentences written from the same rows
 * the screen shows. Only meaningful information is spoken - no formulas, no field names, no symbols (+, %, arrows), no heading chatter - and an
 * increase or decrease is stated as it is, never as good or bad. The same sentences are shown as text, so listening is always optional.
 */
object ComparisonNarration {
    private const val MAX_PER_GROUP = 5

    /** "Total Publishers (monthly avg.)" is spoken as "Total Publishers, monthly average"; "Bible Studies (total)" as "Bible Studies". */
    private fun spokenName(name: String): String = name.replace(" (monthly avg.)", ", monthly average").replace(" (total)", "")

    private fun percentWords(p: Double): String = ComparisonSummary.number(abs(p)).let { "$it percent" }

    private fun weight(r: ComparisonSummary.Row): Double = r.percent?.let { abs(it) } ?: abs(r.difference ?: 0.0).let { if (it > 0) 1000.0 + it else 0.0 }

    private fun move(r: ComparisonSummary.Row, verb: String): String {
        val size = ComparisonSummary.number(abs(r.difference!!))
        val tail = r.percent?.let { ", ${if (r.direction == ComparisonSummary.Direction.INCREASED) "up" else "down"} ${percentWords(it)}" } ?: ""
        return "${spokenName(r.name)} $verb by $size, from ${r.firstText} to ${r.secondText}$tail."
    }

    private fun joinNames(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    fun script(reportType: String, congregationName: String, summary: ComparisonSummary.Result): List<String> {
        val rows = summary.rows
        val compared = rows.filter { it.direction != ComparisonSummary.Direction.UNAVAILABLE }
        val up = compared.filter { it.direction == ComparisonSummary.Direction.INCREASED }.sortedByDescending(::weight)
        val down = compared.filter { it.direction == ComparisonSummary.Direction.DECREASED }.sortedByDescending(::weight)
        val same = compared.filter { it.direction == ComparisonSummary.Direction.UNCHANGED }
        val missing = rows.filter { it.direction == ComparisonSummary.Direction.UNAVAILABLE }

        return buildList {
            add("$reportType comparison for $congregationName.")
            add("Comparing ${summary.firstLabel}, the first period, with ${summary.secondLabel}, the second period.")
            if (compared.isEmpty()) {
                add("No comparison is available, because at least one of the periods has no ${summary.noun} report data.")
            } else {
                add("Of ${compared.size} figure${if (compared.size == 1) "" else "s"} compared, ${up.size} increased, ${down.size} decreased, and ${same.size} stayed the same.")
                if (up.isNotEmpty()) {
                    add("Increases.")
                    up.take(MAX_PER_GROUP).forEach { add(move(it, "increased")) }
                }
                if (down.isNotEmpty()) {
                    add("Decreases.")
                    down.take(MAX_PER_GROUP).forEach { add(move(it, "decreased")) }
                }
                if (same.isNotEmpty()) add("${joinNames(same.map { spokenName(it.name) })} stayed the same.")
                if (missing.isNotEmpty()) add("${joinNames(missing.map { spokenName(it.name) })} could not be compared, because one of the periods has no data for ${if (missing.size == 1) "it" else "them"}.")
            }
            // Transferees are only spoken when the reports actually record them; today they are listed as not recorded, so nothing is said about them.
            add("These figures summarize the changes between the two reporting periods.")
        }
    }
}

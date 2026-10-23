package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.repository.ManualSummary
import com.emfitsolutions.gopreach.data.repository.ManualSummaryColumn

/**
 * The words read aloud for a worksheet's Summary: what the sheet holds, what is worth checking and, when two or more columns hold numbers, a comparison
 * between them. Only the numbers saved in the file are used (nothing is recalculated). The website builds the same sentences in `co/manual.blade.php`.
 */
object ManualSummaryNarration {
    /** A column's name as it is spoken: its heading, or "column C" when it has none. */
    private fun name(c: ManualSummaryColumn) = c.label.trim().takeIf { it.isNotEmpty() && it != c.column }?.let { "$it, column ${c.column}" } ?: "column ${c.column}"

    fun number(v: Double): String = if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e12) v.toLong().toString() else (kotlin.math.round(v * 100) / 100).toString()

    private fun percent(part: Double, whole: Double): String = number(kotlin.math.round(part / whole * 1000) / 10.0)

    fun script(s: ManualSummary): List<String> = buildList {
        add("Summary" + if (s.title.isNotBlank()) " of ${s.title.trim()}." else " of this worksheet.")
        add("It has ${s.rows} rows and ${s.cols} columns, with ${s.filled} filled cells, of which ${s.numeric} are numbers.")
        if (s.formulaCount > 0) add("It uses ${s.formulaCount} formula${if (s.formulaCount == 1) "" else "s"}.") else add("It has no formulas.")
        s.notes.take(5).forEach { add("Worth checking: $it.") }
        addAll(comparison(s.columns))
        add("End of the summary.")
    }

    /** The comparison between the columns that hold numbers; says plainly when there is nothing to compare. */
    fun comparison(columns: List<ManualSummaryColumn>): List<String> {
        if (columns.isEmpty()) return listOf("This sheet has no column of numbers to total or compare.")
        if (columns.size == 1) {
            val c = columns[0]
            return listOf("Only ${name(c)} holds numbers: ${c.count} of them, totalling ${number(c.sum)}, an average of ${number(c.average)}. There is no second column to compare it with.")
        }
        val out = ArrayList<String>()
        out.add("${columns.size} columns hold numbers, so they can be compared.")
        columns.take(6).forEach { out.add("${name(it).replaceFirstChar { ch -> ch.uppercase() }}: ${it.count} numbers, total ${number(it.sum)}, average ${number(it.average)}.") }
        val byTotal = columns.sortedByDescending { it.sum }
        val hi = byTotal.first(); val lo = byTotal.last()
        if (hi.sum == lo.sum) {
            out.add("All these columns have the same total, ${number(hi.sum)}.")
        } else {
            out.add("${name(hi).replaceFirstChar { it.uppercase() }} has the highest total, ${number(hi.sum)}, and ${name(lo)} the lowest, ${number(lo.sum)}.")
            if (lo.sum > 0) out.add("So ${name(hi)} is ${percent(hi.sum - lo.sum, lo.sum)} percent higher than ${name(lo)}, a difference of ${number(hi.sum - lo.sum)}.")
        }
        val all = columns.sumOf { it.sum }
        if (all > 0 && columns.all { it.sum >= 0 } && columns.size > 2) out.add("${name(hi).replaceFirstChar { it.uppercase() }} makes up ${percent(hi.sum, all)} percent of all the column totals together.")
        val avgHi = columns.maxBy { it.average }; val avgLo = columns.minBy { it.average }
        if (avgHi !== avgLo && avgHi.average != avgLo.average) out.add("By average per number, ${name(avgHi)} is highest at ${number(avgHi.average)} and ${name(avgLo)} lowest at ${number(avgLo.average)}.")
        val cHi = columns.maxBy { it.count }; val cLo = columns.minBy { it.count }
        if (cHi.count != cLo.count) out.add("${name(cHi).replaceFirstChar { it.uppercase() }} has the most numbers, ${cHi.count}, and ${name(cLo)} the fewest, ${cLo.count}, so compare their totals with care.")
        return out
    }
}

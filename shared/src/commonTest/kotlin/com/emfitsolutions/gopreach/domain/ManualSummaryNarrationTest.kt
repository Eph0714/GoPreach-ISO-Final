package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.repository.ManualSummary
import com.emfitsolutions.gopreach.data.repository.ManualSummaryColumn
import kotlin.test.Test
import kotlin.test.assertTrue

class ManualSummaryNarrationTest {
    private fun col(letter: String, label: String, count: Int, sum: Double) = ManualSummaryColumn(letter, label, count, sum, sum / count)

    @Test fun twoColumnsAreComparedWithTheirDifference() {
        val text = ManualSummaryNarration.script(ManualSummary(title = "Visit", rows = 10, cols = 3, filled = 20, numeric = 12, columns = listOf(col("B", "Publishers", 6, 120.0), col("C", "Studies", 6, 80.0)))).joinToString(" ")
        assertTrue("highest total, 120" in text && "50 percent higher" in text && "difference of 40" in text, text)
    }

    @Test fun oneColumnHasNothingToCompare() {
        val text = ManualSummaryNarration.comparison(listOf(col("B", "Hours", 4, 40.0))).joinToString(" ")
        assertTrue("no second column to compare" in text, text)
    }

    @Test fun noNumbersSaysSo() {
        assertTrue("no column of numbers" in ManualSummaryNarration.comparison(emptyList()).single())
    }
}

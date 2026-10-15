package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.domain.ComparisonSummary.Row
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComparisonNarrationTest {
    private fun script(vararg rows: Row) =
        ComparisonNarration.script("Field Service Report", "Solano Tagalog Congregation", ComparisonSummary.build("January to June 2026", "July to December 2026", rows.toList(), unavailable = listOf("Transferees (incoming and outgoing)", "New Publishers")))

    @Test
    fun itFollowsTheRequestedOrderAndUsesTheRealData() {
        val s = script(Row("Total Publishers", 120.0, 126.0), Row("Regular Pioneers", 18.0, 20.0), Row("Unbaptized Publishers", 8.0, 7.0), Row("Bible Studies", 35.0, 35.0))
        assertEquals("Field Service Report comparison for Solano Tagalog Congregation.", s[0])
        assertEquals("Comparing January to June 2026, the first period, with July to December 2026, the second period.", s[1])
        val idx = { text: String -> s.indexOfFirst { it.contains(text) } }
        assertTrue(idx("Of 4 figures compared, 2 increased, 1 decreased, and 1 stayed the same.") in 2..3)
        assertTrue(idx("Increases.") < idx("Total Publishers increased by 6") && idx("Total Publishers increased by 6") < idx("Decreases."))
        assertTrue(idx("Decreases.") < idx("Unbaptized Publishers decreased by 1") && idx("Unbaptized Publishers decreased by 1") < idx("Bible Studies stayed the same."))
        assertTrue(s.last().startsWith("These figures summarize"))
        assertTrue(s.any { it == "Total Publishers increased by 6, from 120 to 126, up 5 percent." })
    }

    @Test
    fun itSpeaksNoFormulasSymbolsFieldNamesOrJudgments() {
        val all = script(Row("Total Publishers (monthly avg.)", 86.0, 90.5), Row("Bible Studies (total)", 3.0, 0.0), Row("Missing", null, 2.0)).joinToString(" ")
        for (bad in listOf("%", "+", "▲", "▼", "=", "(", ")", "Percentage Change", "Calculation", "(monthly avg.)", "improve", "good", "bad", "Period A")) {
            assertFalse(all.contains(bad), "the speech must not contain \"$bad\"")
        }
        assertTrue(all.contains("Total Publishers, monthly average increased by 4.5"))
        assertTrue(all.contains("Bible Studies decreased by 3, from 3 to 0, down 100 percent."))
        assertTrue(all.contains("Missing could not be compared"))
    }

    @Test
    fun nothingIsSaidAboutTransfereesThatTheReportsDoNotRecord() {
        val all = script(Row("A", 1.0, 2.0)).joinToString(" ")
        assertFalse(all.contains("Transferee") || all.contains("New Publishers"))
    }

    @Test
    fun everyFigureTheNarrationSpeaksIsTheFigureThatIsDisplayed() {
        val rows = listOf(Row("Total Publishers", 120.0, 126.0), Row("Regular Pioneers", 18.0, 20.5), Row("Bible Studies", 35.0, 30.0), Row("Auxiliary Pioneers", 2.0, 2.0))
        val spoken = ComparisonNarration.script("Field Service Report", "Cong", ComparisonSummary.build("M1", "M2", rows)).joinToString(" ")
        rows.filter { it.direction != ComparisonSummary.Direction.UNCHANGED }.forEach { r ->
            assertTrue(spoken.contains("${r.name} ") && spoken.contains("from ${r.firstText} to ${r.secondText}"), "${r.name} is spoken with the figures the table shows")
        }
        // a different selection never reuses the old figures
        val other = ComparisonNarration.script("Field Service Report", "Cong", ComparisonSummary.build("M3", "M4", listOf(Row("Total Publishers", 1.0, 2.0)))).joinToString(" ")
        assertFalse(other.contains("120") || other.contains("M1"), "no old figures are narrated")
    }

    @Test
    fun theWordingFollowsWhetherTheReportsWereReceivedOrSent() {
        val sent = ComparisonNarration.script("Circuit Attendance Report", "Cong", ComparisonSummary.build("M1", "M2", listOf(Row("A", null, null)), noun = "sent"))
        assertTrue(sent.any { it.contains("has no sent report data") })
        val received = ComparisonNarration.script("Circuit Attendance Report", "Cong", ComparisonSummary.build("M1", "M2", listOf(Row("A", null, null))))
        assertTrue(received.any { it.contains("has no received report data") })
    }

    @Test
    fun aZeroBaselineIsSpokenWithoutAPercentageAndNoComparisonIsSaidPlainly() {
        assertTrue(script(Row("New", 0.0, 4.0)).contains("New increased by 4, from 0 to 4."))
        val none = script(Row("A", null, null))
        assertTrue(none.any { it.startsWith("No comparison is available") })
        assertFalse(none.any { it.startsWith("Of ") })
    }
}

package com.emfitsolutions.gopreach.ui.screens.circuit

import com.emfitsolutions.gopreach.data.repository.AnalysisAi
import com.emfitsolutions.gopreach.data.repository.AnalysisFinding
import com.emfitsolutions.gopreach.data.repository.AnalysisQuality
import com.emfitsolutions.gopreach.data.repository.AnalysisSource
import com.emfitsolutions.gopreach.data.repository.CompareColumn
import com.emfitsolutions.gopreach.data.repository.CompareRow
import com.emfitsolutions.gopreach.data.repository.CompareScope
import com.emfitsolutions.gopreach.data.repository.ComparisonReport
import com.emfitsolutions.gopreach.data.repository.ManualAnalysis
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualAnalysisTextTest {
    private val base = ManualAnalysis(
        available = true, generatedAt = "2026-10-23T08:14:00.000Z", source = AnalysisSource("visit.xlsx", "Visit", "Alpha Congregation"),
        periods = listOf(CompareColumn("1", "March 2025"), CompareColumn("2", "June 2025")),
        report = ComparisonReport(
            title = "t", scope = CompareScope(previous = "March 2025", current = "June 2025"),
            table = listOf(
                CompareRow("Publishers", previous = 80.0, current = 84.0, difference = 4.0, percent = 5.0, direction = "increase"),
                CompareRow("New Publishers", previous = 0.0, current = 4.0, difference = 4.0, percent = null, direction = "increase"),
                CompareRow("Auxiliary Pioneers", previous = 6.0, current = null, direction = "unavailable"),
            ),
        ),
        findings = listOf(AnalysisFinding("f1", text = "Publishers increased from 80 to 84.", explanation = "Plain words.")), quality = AnalysisQuality(listOf("1 cell holds text.")),
        executive = listOf("Publishers rose from 80 to 84."),
    )

    @Test fun theSharedReportCarriesTheCalculatedFiguresAndSaysHowItWasWorded() {
        val t = plainText(base.copy(ai = AnalysisAi("OK", model = "qwen2.5:7b-instruct")))
        assertTrue(t.contains("Congregation: Alpha Congregation") && t.contains("Reporting periods: March 2025, June 2025"))
        assertTrue(t.contains("Publishers: 80 → 84  difference +4, change +5%"))
        assertTrue("a zero baseline has no percentage", t.contains("New Publishers: 0 → 4  difference +4, change not applicable"))
        assertTrue("a missing value is not a zero", t.contains("Auxiliary Pioneers: 6 → - (not available)"))
        assertTrue(t.contains("1. Publishers increased from 80 to 84. — Plain words.") && t.contains("1 cell holds text."))
        assertTrue(t.contains("written by qwen2.5:7b-instruct"))
    }

    @Test fun whenTheAiDidNotWorkTheReportNeverClaimsItDid() {
        val t = plainText(base.copy(ai = AnalysisAi("TIMEOUT", message = "slow")))
        assertTrue(t.contains("calculated by GoPreach without AI"))
        assertFalse(t.contains("written by"))
    }
}

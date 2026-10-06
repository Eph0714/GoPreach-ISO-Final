package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.PublisherCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Calendar

class PublisherReportCalculatorTest {
    private val sept2026 = Calendar.getInstance().apply { clear(); set(2026, Calendar.SEPTEMBER, 1) }.timeInMillis

    @Test
    fun convertsToHalfHours() {
        val cases = mapOf(
            15 * 60 to 15.0, 15 * 60 + 15 to 15.0, 15 * 60 + 28 to 15.5, 15 * 60 + 30 to 15.5,
            15 * 60 + 45 to 16.0, 15 * 60 + 58 to 16.0, 16 * 60 + 10 to 16.0, 16 * 60 + 31 to 16.5,
        )
        cases.forEach { (minutes, hours) -> assertEquals("$minutes min", hours, PublisherReportCalculator.convertToHours(minutes), 0.0) }
    }

    @Test
    fun pioneerText() {
        val text = PublisherReportCalculator.build("Ephraim", "Fernandez", PublisherCategory.AUXILIARY_PIONEER, sept2026, 15 * 60 + 28, 1).toText()
        assertEquals("Name: EPHRAIM FERNANDEZ (Auxiliary Pioneer)\nMonth: September 2026\nHours: 15.5\nBible Study: 1", text.replace("\r", ""))
        assertFalse(text.contains("Minutes"))
        val sixteen = PublisherReportCalculator.build("Ephraim", "Fernandez", PublisherCategory.AUXILIARY_PIONEER, sept2026, 15 * 60 + 58, 1).toText()
        assertEquals(true, sixteen.contains("Hours: 16\n"))
    }

    @Test
    fun nonPioneerText() {
        val yes = PublisherReportCalculator.build("Juan", "Dela Cruz", PublisherCategory.REGULAR_PUBLISHER, sept2026, 90, 1).toText()
        assertEquals("Name: JUAN DELA CRUZ (Publisher)\nMonth: September 2026\nAttended in Preaching: YES\nBible Study: 1", yes)
        val no = PublisherReportCalculator.build("Juan", "Dela Cruz", PublisherCategory.UNBAPTIZED_PUBLISHER, sept2026, 0, 0).toText()
        assertEquals("Name: JUAN DELA CRUZ (Unbaptized Publisher)\nMonth: September 2026\nAttended in Preaching: NO\nBible Study: 0", no)
        assertFalse(no.contains("Hours"))
    }
}

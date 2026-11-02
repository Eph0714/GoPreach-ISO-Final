package com.emfitsolutions.gopreach.ui.screens.monthlyreport

import com.emfitsolutions.gopreach.data.model.PublisherCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MonthlyReportTimeFieldsTest {

    private fun state(category: PublisherCategory, hours: String = "0", minutes: String = "0") =
        MonthlyReportUiState(category = category, hoursText = hours, minutesText = minutes)

    @Test fun nonPioneersAreNeverAskedForTimeAndStoreNone() {
        listOf(PublisherCategory.REGULAR_PUBLISHER, PublisherCategory.UNBAPTIZED_PUBLISHER).forEach { c ->
            val s = state(c, hours = "", minutes = "99") // garbage in the hidden fields must not block or be stored
            assertFalse(s.isPioneer)
            assertNull(s.hoursError)
            assertNull(s.minutesError)
            assertFalse(s.hasValidationError)
            assertEquals(0.0, s.reportedHoursDecimal)
        }
    }

    @Test fun pioneersKeepTheirTimeRules() {
        listOf(PublisherCategory.REGULAR_PIONEER, PublisherCategory.AUXILIARY_PIONEER, PublisherCategory.SPECIAL_PIONEER).forEach { c ->
            assertTrue(state(c).isPioneer)
            assertTrue(state(c, hours = "").hoursError != null)
            assertTrue(state(c, minutes = "75").minutesError != null)
            assertNull(state(c, hours = "40", minutes = "30").hoursError)
            assertTrue(state(c, hours = "40", minutes = "30").reportedHoursDecimal > 0.0)
        }
    }
}

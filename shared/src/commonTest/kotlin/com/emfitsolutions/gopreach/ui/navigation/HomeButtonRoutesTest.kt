package com.emfitsolutions.gopreach.ui.navigation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeButtonRoutesTest {
    @Test fun everyOpenedFormHasTheBackToHomeButton() {
        assertTrue(Destinations.hasHomeButton(Destinations.MANAGE_ELDERS))
        assertTrue(Destinations.hasHomeButton(Destinations.ENROLL_PUBLISHER))
        assertTrue(Destinations.hasHomeButton(Destinations.CONSOLIDATED_REPORT))
        // the main forms too, and a screen opened from one (a child form)
        Destinations.MOTHER_FORMS.forEach { assertTrue(Destinations.hasHomeButton(it), "$it is a form and has the button") }
    }

    @Test fun theHomepagesAndTheScreensBeforeSignInHaveNone() {
        listOf(Destinations.LOGIN, Destinations.ABOUT, Destinations.FORGOT_PASSWORD, Destinations.FORCED_PASSWORD_CHANGE, Destinations.ADMIN_HOME, Destinations.PUBLISHER_HOME)
            .forEach { assertFalse(Destinations.hasHomeButton(it), "$it has no Back to Home") }
        assertFalse(Destinations.hasHomeButton(null))
    }
}

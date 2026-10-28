package com.emfitsolutions.gopreach.ui.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Back to Homepage" appears on every main (mother) form and on nothing else. This reads the navigation graph itself, so a screen added later cannot be
 * forgotten: it must be listed in [Destinations.MOTHER_FORMS] or named here as a child / sign-in / homepage screen.
 */
class MotherFormsAuditTest {
    /** Screens opened FROM a mother form (add / edit / detail / wizard / settings sub-pages): no Home button of their own. */
    private val children = setOf(
        "ADD_CIRCUIT_OVERSEER", "ADD_USER", "CIRCUIT_CONGREGATION", "EDIT_CIRCUIT_OVERSEER", "EDIT_MONTHLY_REPORT", "EDIT_USER", "GROUP_CHAT_DETAIL", "GROUP_CHAT_SETTING",
        "SESSION_TIMEOUT_SETTING", "TERRITORY_ASSIGNMENT_WIZARD", "THEME_COLOR_SETTINGS", "VIEW_CIRCUIT_OVERSEER",
    )

    /** Sign-in flow and the two homepages themselves. */
    private val notMainForms = setOf("LOGIN", "ABOUT", "FORGOT_PASSWORD", "FORCED_PASSWORD_CHANGE", "ADMIN_HOME", "PUBLISHER_HOME")

    private fun graphRoutes(): Set<String> {
        val source = listOf("src/main/java/com/emfitsolutions/gopreach/ui/navigation/GoPreachNavGraph.kt", "app/src/main/java/com/emfitsolutions/gopreach/ui/navigation/GoPreachNavGraph.kt")
            .map(::File).first { it.exists() }.readText()
        return Regex("""composable\(\s*(?:route\s*=\s*)?Destinations\.([A-Z_]+)""").findAll(source).map { it.groupValues[1] }.toSet()
    }

    private fun constants(): Map<String, String> =
        Destinations::class.java.declaredFields.filter { it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .associate { it.name to (it.apply { isAccessible = true }.get(null) as String) }

    @Test fun everyScreenOfTheGraphIsAMainFormOrAKnownNonMainForm() {
        val routes = graphRoutes()
        assertTrue("the graph was not read", routes.size > 60)
        val values = constants()
        val unclassified = routes.filter { name ->
            val value = values[name] ?: return@filter true
            !Destinations.isMotherForm(value) && name !in children && name !in notMainForms
        }
        assertEquals("classify these screens in Destinations.MOTHER_FORMS or in this test", emptyList<String>(), unclassified.sorted())
    }

    @Test fun aScreenIsNeverBothAMainFormAndAChild() {
        val values = constants()
        for (name in children + notMainForms) assertFalse("$name must not be a main form", Destinations.isMotherForm(values[name]))
        assertTrue(children.intersect(notMainForms).isEmpty())
    }

    @Test fun everyMainFormIsAScreenOfTheGraph() {
        val inGraph = graphRoutes().mapNotNull { constants()[it] }.toSet()
        assertEquals("these main forms have no screen in the graph", emptyList<String>(), Destinations.MOTHER_FORMS.filter { it !in inGraph }.sorted())
    }

    @Test fun homepagesAndSignInScreensGetNoHomeButton() {
        for (route in listOf(Destinations.LOGIN, Destinations.ADMIN_HOME, Destinations.PUBLISHER_HOME, Destinations.FORCED_PASSWORD_CHANGE)) {
            assertFalse(route, Destinations.isMotherForm(route))
        }
        assertFalse(Destinations.isMotherForm(null))
        assertTrue(Destinations.isMotherForm(Destinations.SETTINGS))
    }
}

package com.emfitsolutions.gopreach.ui.components

import com.emfitsolutions.gopreach.data.model.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Publisher form: the single Coordinate box and the five contact details a person must give in their own profile (an Admin may leave them empty). */
class PublisherFormStateTest {
    private val person = Person(id = "p1", firstName = "PAT", lastName = "PUB", gpsLat = 16.5, gpsLng = 121.1)
    private val complete = PublisherFormState(
        firstName = "PAT", lastName = "PUB", address = "1 MAIN ST", contact = "0917", province = "Pangasinan", cityMunicipality = "Urdaneta", barangay = "Anonas",
    )

    @Test fun anExistingPersonsSeparateLatitudeAndLongitudeShowInOneBox() {
        assertEquals("16.5, 121.1", PublisherFormState.from(person, null, null).coordinateText)
        assertEquals("", PublisherFormState.from(person.copy(gpsLat = null, gpsLng = null), null, null).coordinateText)
    }

    @Test fun theBoxIsReadBackIntoLatitudeAndLongitudeInThatOrder() {
        val saved = complete.copy(coordinateText = "16.0612, 120.4075").applyTo(person)
        assertEquals(16.0612, saved.gpsLat!!, 0.0)
        assertEquals(120.4075, saved.gpsLng!!, 0.0)
        assertNull(complete.copy(coordinateText = "16.0612, 120.4075").coordinatesProblem)
    }

    @Test fun badCoordinatesBlockSavingAndAreNeverRepaired() {
        listOf("16.0612", "abc", "120.4075, 16.0612", "-95, 10").forEach {
            assertNotNull(it, complete.copy(coordinateText = it).coordinatesProblem)
            assertNotNull(it, complete.copy(coordinateText = it).formProblem)
            assertNull(it, complete.copy(coordinateText = it).latitude)
        }
        assertNull(complete.copy(coordinateText = "").coordinatesProblem)
    }

    @Test fun anEmptyBoxNeverWipesTheCoordinateThatIsStored() {
        val saved = complete.copy(coordinateText = "").applyTo(person)
        assertEquals(16.5, saved.gpsLat!!, 0.0)
        assertEquals(121.1, saved.gpsLng!!, 0.0)
    }

    @Test fun aPersonsOwnProfileNeedsAllFiveContactDetails() {
        assertFalse(complete.missingOwnDetails)
        listOf(complete.copy(address = ""), complete.copy(contact = " "), complete.copy(province = null), complete.copy(cityMunicipality = ""), complete.copy(barangay = null)).forEach {
            assertTrue(it.missingOwnDetails)
        }
        assertTrue(PublisherFormState.OWN_DETAILS_MESSAGE.startsWith("Please complete your personal details."))
    }
}

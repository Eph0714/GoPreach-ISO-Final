package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.AccountStatus
import com.emfitsolutions.gopreach.data.model.Gender
import com.emfitsolutions.gopreach.data.model.Person
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the app sends to the server's one publisher edit: the same keys as the website, only the parts that were asked to change, and nothing it must not send. */
class PublisherEditBodyTest {
    private val person = Person(
        id = "p1", firstName = "MARIA", lastName = "SANTOS", address = "PUROK 1", contact = "0917", province = "NUEVA VIZCAYA", cityMunicipality = "SOLANO", barangay = "ROXAS",
        gender = Gender.FEMALE, birthdate = 631152000000L, gpsLat = 16.5, gpsLng = 121.1, email = null, remarks = "hello",
    )

    private fun body(edit: PublisherEdit): JsonObject = publisherEditBody(edit)

    @Test fun theDetailsUseTheWebsitesKeys() {
        val d = body(PublisherEdit(person)).getValue("person").jsonObject
        assertEquals(
            setOf("firstName", "lastName", "middleInitial", "extensionName", "address", "contact", "province", "cityMunicipality", "barangay", "contactPerson", "contactPersonNumber",
                "remarks", "email", "gender", "birthdate", "baptismalDate", "gpsLat", "gpsLng"),
            d.keys,
        )
        assertEquals("FEMALE", d.getValue("gender").jsonPrimitive.content)
        assertEquals(631152000000L, d.getValue("birthdate").jsonPrimitive.content.toLong())
        assertEquals(16.5, d.getValue("gpsLat").jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(JsonNull, d.getValue("email"))
        assertEquals(JsonNull, d.getValue("baptismalDate"))
    }

    @Test fun onlyWhatWasAskedToChangeIsSent() {
        val plain = body(PublisherEdit(person))
        assertEquals(setOf("person"), plain.keys)
        val all = body(
            PublisherEdit(person, category = "REGULAR_PIONEER", sendGroup = true, groupId = "g2", congregationId = "c2", username = "maria.santos", accountStatus = "SUSPENDED", password = "TempPass9"),
        )
        assertEquals(setOf("person", "category", "groupId", "congregationId", "username", "accountStatus", "password"), all.keys)
        assertEquals("g2", all.getValue("groupId").jsonPrimitive.content)
        assertEquals("maria.santos", all.getValue("username").jsonPrimitive.content)
    }

    @Test fun aBlankPasswordOrUsernameIsNeverSentAndNoGroupMeansUnassignedOnlyWhenAsked() {
        val b = body(PublisherEdit(person, username = "  ", password = ""))
        assertFalse("password" in b.keys); assertFalse("username" in b.keys)
        assertFalse("groupId" in b.keys)
        assertEquals(JsonNull, body(PublisherEdit(person, sendGroup = true, groupId = null)).getValue("groupId"))
    }

    @Test fun theUsernameIsSentExactlyAsTyped() {
        assertEquals("jose.m.cruz", body(PublisherEdit(person, username = "jose.m.cruz")).getValue("username").jsonPrimitive.content)
        assertTrue(AccountStatus.entries.map { it.name }.containsAll(listOf("ACTIVE", "INACTIVE", "SUSPENDED")))
        assertEquals(JsonPrimitive("INACTIVE"), body(PublisherEdit(person, accountStatus = "INACTIVE")).getValue("accountStatus"))
    }

    @Test fun provinceMunicipalityBarangayAndContactMayBeBlankAndAreSentAsBlankNeverInvented() {
        val blank = person.copy(contact = "", province = null, cityMunicipality = null, barangay = null)
        val d = body(PublisherEdit(blank)).getValue("person").jsonObject
        assertEquals("", d.getValue("contact").jsonPrimitive.content)
        for (k in listOf("province", "cityMunicipality", "barangay")) assertEquals(k, JsonNull, d.getValue(k))
        assertEquals("the required details are still sent", "MARIA", d.getValue("firstName").jsonPrimitive.content)
        // and a given value goes out exactly as given
        val given = body(PublisherEdit(person.copy(contact = "0918", province = "PANGASINAN"))).getValue("person").jsonObject
        assertEquals(listOf("0918", "PANGASINAN"), listOf(given.getValue("contact").jsonPrimitive.content, given.getValue("province").jsonPrimitive.content))
    }

    @Test fun aSaveThatLeavesTheUsernameAloneSendsNoUsernameAtAll() {
        assertFalse(body(PublisherEdit(person)).containsKey("username"))
        assertFalse(body(PublisherEdit(person, username = "  ")).containsKey("username"))
    }
}

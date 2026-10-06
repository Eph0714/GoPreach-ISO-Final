package com.emfitsolutions.gopreach.data.json

import com.emfitsolutions.gopreach.data.model.Person
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocJsonTest {
    @Test fun roundTripsAPerson() {
        val p = Person(id = "juan", isSuperAdmin = true)
        val json = DocJson.encode(p)
        assertTrue(json.contains("\"isSuperAdmin\":true"), json)
        assertEquals(p, DocJson.decode<Person>(json))
    }

    @Test fun readsDocumentsWithMissingAndUnknownFields() {
        val p = DocJson.decode<Person>("""{"id":"ana","somethingNew":5}""")
        assertEquals("ana", p.id)
    }
}

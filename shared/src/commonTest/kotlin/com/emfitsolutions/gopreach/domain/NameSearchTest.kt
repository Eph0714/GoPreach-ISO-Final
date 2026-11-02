package com.emfitsolutions.gopreach.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NameSearchTest {
    private val efren = "MARTIN, EFREN"

    @Test fun fullFirstLastAndPartialNamesFindThePublisher() {
        listOf("Efren Martin", "martin efren", "Martin, Efren", "EFREN", "martin", "mart", "efr mar", "  Efren   Martin  ").forEach { assertTrue(NameSearch.matches(it, efren), it) }
    }

    @Test fun otherNamesAndNonsenseDoNot() {
        listOf("John Martin", "Efren Santos", "zzzqq", "martinez").forEach { assertFalse(NameSearch.matches(it, efren), it) }
    }

    @Test fun nothingTypedFindsEverythingAndSeveralFieldsAreSearchedTogether() {
        assertTrue(NameSearch.matches("", efren)); assertTrue(NameSearch.matches("   ", null))
        assertTrue(NameSearch.matches("efren group 3", efren, "Group 3", null))
        assertFalse(NameSearch.matches("efren group 4", efren, "Group 3"))
    }
}

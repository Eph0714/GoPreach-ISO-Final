package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.Group
import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {
    @Test
    fun numbersCompareByValueNotByCharacter() {
        val names = listOf("FS GROUP 10", "FS GROUP 2", "FS GROUP 1", "FS GROUP 11", "FS GROUP 3")
        assertEquals(
            listOf("FS GROUP 1", "FS GROUP 2", "FS GROUP 3", "FS GROUP 10", "FS GROUP 11"),
            names.sortedWith(NaturalOrder.comparator),
        )
    }

    @Test
    fun caseInsensitiveAlphabetical() {
        assertEquals(listOf("alpha", "Bravo", "CHARLIE"), listOf("CHARLIE", "alpha", "Bravo").sortedWith(NaturalOrder.comparator))
    }

    @Test
    fun groupsAreOrderedByName() {
        val groups = listOf(Group(name = "Group B"), Group(name = "Group 10"), Group(name = "Group 9"), Group(name = "Group A"))
        assertEquals(listOf("Group 9", "Group 10", "Group A", "Group B"), groups.sortedWith(GroupNameOrder).map { it.name })
    }
}

package com.emfitsolutions.gopreach.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NaturalOrderTest {
    @Test fun numbersCompareByValue() {
        assertEquals(listOf("FS GROUP 2", "FS GROUP 10"), listOf("FS GROUP 10", "FS GROUP 2").sortedWith(NaturalOrder.comparator))
    }

    @Test fun ignoresCaseAndLeadingZerosAndHugeNumbers() {
        assertTrue(NaturalOrder.compare("group 007", "GROUP 8") < 0)
        assertTrue(NaturalOrder.compare("g 99999999999999999999999", "g 100000000000000000000000") < 0)
        assertTrue(NaturalOrder.compare("a", "B") < 0)
    }
}

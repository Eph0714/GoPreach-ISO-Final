package com.emfitsolutions.gopreach.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoordinateInputTest {
    @Test fun aValidPairIsReadInTheOrderLatitudeThenLongitude() {
        assertEquals(CoordinateInput.Pair(16.0612, 120.4075), CoordinateInput.parse("16.0612, 120.4075"))
        assertEquals(CoordinateInput.Pair(16.0612, 120.4075), CoordinateInput.parse("  16.0612,120.4075  "))
        assertEquals(CoordinateInput.Pair(-12.5, -170.0), CoordinateInput.parse("-12.5 ,  -170"))
        assertNull(CoordinateInput.problem("16.0612, 120.4075"))
    }

    @Test fun blankIsFineBecauseTheCoordinateIsOptional() {
        assertNull(CoordinateInput.parse("   "))
        assertNull(CoordinateInput.problem(""))
    }

    @Test fun oneNumberOrJunkIsRefusedWithTheFormatMessage() {
        listOf("16.0612", "16.0612; 120.4075", "abc, def", "16.0612, 120.4075, 5", ", 120", "16.0612 120.4075").forEach {
            assertNull(CoordinateInput.parse(it), it)
            assertEquals(CoordinateInput.FORMAT_MESSAGE, CoordinateInput.problem(it), it)
        }
    }

    @Test fun outOfRangeValuesAreRefusedNotRepairedOrSwapped() {
        listOf("120.4075, 16.0612", "-91, 10", "10, 181").forEach {
            assertNull(CoordinateInput.parse(it), it)
            assertEquals(CoordinateInput.RANGE_MESSAGE, CoordinateInput.problem(it), it)
        }
    }

    @Test fun aStoredPairIsShownWithoutExponentOrTrailingZeros() {
        assertEquals("16.0612, 120.4075", CoordinateInput.format(16.0612, 120.4075))
        assertEquals("16.5, 121", CoordinateInput.format(16.5, 121.0))
        assertEquals("-12.345678, -0.00001", CoordinateInput.format(-12.345678, -0.00001))
        assertEquals("0, 0", CoordinateInput.format(0.0, -0.0))
    }
}

package com.emfitsolutions.gopreach.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordFoundTest {
    @Test
    fun labelUsesTheExactStandardFormat() {
        assertEquals("Record Found: 35", recordFoundText(35))
        assertEquals("Record Found: 1", recordFoundText(1))
        // Zero is shown too, never hidden.
        assertEquals("Record Found: 0", recordFoundText(0))
    }
}

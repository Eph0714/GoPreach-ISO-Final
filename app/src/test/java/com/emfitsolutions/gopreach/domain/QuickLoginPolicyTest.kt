package com.emfitsolutions.gopreach.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickLoginPolicyTest {
    @Test
    fun weakPinsAreRejected() {
        listOf("123456", "000000", "111111", "654321", "121212", "12345", "1234567", "12ab56", "159753", "482482").forEach {
            assertNotNull("$it should be rejected", QuickLoginPolicy.pinProblem(it))
        }
    }

    @Test
    fun reasonablePinsAreAccepted() {
        listOf("493017", "820514", "715309").forEach { assertNull("$it should be accepted", QuickLoginPolicy.pinProblem(it)) }
    }

    @Test
    fun patternRules() {
        assertNotNull(QuickLoginPolicy.patternProblem(listOf(0, 1, 2, 5, 8)))           // too short
        assertNotNull(QuickLoginPolicy.patternProblem(listOf(0, 1, 2, 3, 4, 5, 6)))      // dots in reading order
        assertNotNull(QuickLoginPolicy.patternProblem(listOf(8, 7, 6, 5, 4, 3)))         // reverse reading order
        assertNotNull(QuickLoginPolicy.patternProblem(listOf(0, 1, 1, 4, 5, 8)))         // repeated dot
        assertNull(QuickLoginPolicy.patternProblem(listOf(0, 4, 2, 5, 7, 3)))
        assertEquals("0-4-2", QuickLoginPolicy.patternSecret(listOf(0, 4, 2)))
    }

    @Test
    fun lockoutGrowsAndThenStops() {
        assertEquals(0L, QuickLoginPolicy.lockDurationMs(4))
        assertEquals(30_000L, QuickLoginPolicy.lockDurationMs(5))
        assertEquals(0L, QuickLoginPolicy.lockDurationMs(6))
        assertEquals(60_000L, QuickLoginPolicy.lockDurationMs(10))
        assertTrue(QuickLoginPolicy.lockDurationMs(15) <= QuickLoginPolicy.MAX_LOCK_MS)
    }

    @Test
    fun hashIsSaltedAndVerifies() {
        val a = SecretHasher.hash("493017")
        val b = SecretHasher.hash("493017")
        assertNotEquals("same secret must not give the same hash", a, b)
        assertFalse(a.contains("493017"))
        assertTrue(SecretHasher.matches("493017", a))
        assertFalse(SecretHasher.matches("493018", a))
        assertFalse(SecretHasher.matches("493017", "garbage"))
    }
}

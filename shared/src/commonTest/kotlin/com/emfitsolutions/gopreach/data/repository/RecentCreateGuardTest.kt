package com.emfitsolutions.gopreach.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RecentCreateGuardTest {
    private var now = 1_000_000L
    private var counter = 0
    private val guard = RecentCreateGuard(windowMillis = 30_000, clock = { now })
    private fun newId() = "id${++counter}"

    @Test
    fun `the same new record saved three times in a row is one record`() {
        val ids = List(3) { guard.idFor("coord|BIBLE_STUDY|solano|Juan", ::newId) }
        assertEquals(listOf("id1", "id1", "id1"), ids)
        assertEquals(1, counter, "only one id was ever generated")
    }

    @Test
    fun `different content is a different record`() {
        val a = guard.idFor("coord|BIBLE_STUDY|solano|Juan", ::newId)
        val b = guard.idFor("coord|BIBLE_STUDY|solano|Maria", ::newId)
        assertNotEquals(a, b)
    }

    @Test
    fun `the same content later on is a new record`() {
        val first = guard.idFor("sig", ::newId)
        now += 31_000
        assertNotEquals(first, guard.idFor("sig", ::newId))
    }

    @Test
    fun `just inside the window it is still the same record`() {
        val first = guard.idFor("sig", ::newId)
        now += 29_000
        assertEquals(first, guard.idFor("sig", ::newId))
    }
}

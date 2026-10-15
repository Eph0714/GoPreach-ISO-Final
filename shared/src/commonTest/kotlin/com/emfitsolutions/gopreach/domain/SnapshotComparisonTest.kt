package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.CoReceivedReport
import com.emfitsolutions.gopreach.data.model.CoReceivedRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotComparisonTest {
    private fun row(n: Int, id: String, name: String, status: String, hours: Double? = null, studies: Int? = 0) =
        CoReceivedRow(number = n, status = status, name = name, reportsCount = 1, hours = hours, bibleStudies = studies, personId = id)

    private fun send(version: Int, vararg rows: CoReceivedRow, month: Long = 100L, cong: String = "c1") =
        CoReceivedReport(id = "${cong}_${month}_$version", congregationId = cong, periodMonth = month, version = version, rows = rows.toList())

    @Test
    fun aCorrectedSendShowsWhatChanged() {
        val first = send(1, row(1, "a", "Ana", "Pub", studies = 1), row(2, "b", "Ben", "AP", hours = 30.0, studies = 2), row(3, "c", "Cy", "Pub", studies = 0))
        val second = send(2, row(1, "a", "Ana", "AP", hours = 30.0, studies = 1), row(2, "b", "Ben", "AP", hours = 32.5, studies = 2), row(4, "d", "Di", "Pub", studies = 3))
        val diff = assertNotNull(SnapshotComparison.compare(first, second))
        assertEquals(listOf("Di"), diff.added.map { it.name })
        assertEquals(listOf("Cy"), diff.removed.map { it.name })
        assertEquals(listOf("Ana"), diff.reclassified.map { it.name })
        assertEquals("Pub", diff.reclassified.single().from)
        assertEquals("AP", diff.reclassified.single().to)
        assertEquals(setOf("Ana", "Ben"), diff.figureChanges.map { it.name }.toSet())
        assertEquals(3, diff.previousTotals.publishers)
        assertEquals(3, diff.currentTotals.publishers)
        assertEquals(62.5, diff.currentTotals.hours)
        assertTrue(diff.hasChanges)
    }

    @Test
    fun anIdenticalResendHasNoChanges() {
        val a = send(1, row(1, "a", "Ana", "Pub"))
        val b = send(2, row(1, "a", "Ana", "Pub"))
        assertTrue(!assertNotNull(SnapshotComparison.compare(a, b)).hasChanges)
    }

    @Test
    fun differentMonthsAreNeverComparedAsRevisions() {
        val july = send(1, row(1, "a", "Ana", "Pub"), month = 100L)
        val august = send(1, row(1, "a", "Ana", "Pub"), month = 200L)
        assertNull(SnapshotComparison.compare(july, august))
        assertNull(SnapshotComparison.previousSend(august, listOf(july, august)))
    }

    @Test
    fun differentCongregationsAreNeverCompared() {
        assertNull(SnapshotComparison.compare(send(1, cong = "c1"), send(2, cong = "c2")))
    }

    @Test
    fun theEarlierSendIsTheOneBefore() {
        val s1 = send(1); val s2 = send(2); val s3 = send(3)
        assertEquals(s2, SnapshotComparison.previousSend(s3, listOf(s1, s2, s3)))
        assertNull(SnapshotComparison.previousSend(s1, listOf(s1, s2, s3)))
    }

    @Test
    fun sendsWithoutPublisherIdsAreMatchedByName() {
        val a = send(1, row(1, "", "Ana", "Pub"), row(2, "", "Ben", "Pub"))
        val b = send(2, row(1, "", "Ana", "AP"), row(2, "", "Ben", "Pub"))
        val diff = assertNotNull(SnapshotComparison.compare(a, b))
        assertEquals(listOf("Ana"), diff.reclassified.map { it.name })
        assertTrue(diff.added.isEmpty() && diff.removed.isEmpty())
    }
}

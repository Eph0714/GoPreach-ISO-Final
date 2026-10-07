package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.ui.screens.circuit.CircuitPersonRow
import com.emfitsolutions.gopreach.ui.screens.circuit.CongregationHeading
import com.emfitsolutions.gopreach.ui.screens.circuit.publisherSections
import com.emfitsolutions.gopreach.ui.screens.circuit.publisherTotals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CircuitPublisherReportTest {
    private fun row(id: String, cong: String, name: String, category: PublisherCategory) = CircuitPersonRow(
        personId = id, name = name, congregationId = cong, congregationName = cong.uppercase(), groupId = null, groupName = "",
        category = category, roleLabel = null, status = "ACTIVE", contact = "",
    )

    private val rows = listOf(
        row("1", "b", "A", PublisherCategory.REGULAR_PIONEER),
        row("2", "b", "B", PublisherCategory.REGULAR_PUBLISHER),
        row("3", "a", "C", PublisherCategory.REGULAR_PIONEER),
        row("4", "a", "D", PublisherCategory.SPECIAL_PIONEER),
    )

    @Test
    fun `totals count the publishers and each category, never Removed`() {
        val t = publisherTotals(rows)
        assertEquals(4, t.total)
        assertEquals(2, t.byCategory.first { it.first == PublisherCategory.REGULAR_PIONEER }.second)
        assertEquals(1, t.byCategory.first { it.first == PublisherCategory.SPECIAL_PIONEER }.second)
        assertEquals(0, t.byCategory.first { it.first == PublisherCategory.AUXILIARY_PIONEER }.second)
        assertTrue(t.byCategory.none { it.first == PublisherCategory.REMOVED_PUBLISHER })
        assertEquals(4, t.byCategory.sumOf { it.second })
    }

    @Test
    fun `sections are one per congregation, ordered by name, each with its heading and totals`() {
        val headings = listOf(
            CongregationHeading("a", "ALPHA", "A-01", "Henry Canales"),
            CongregationHeading("b", "BRAVO", "B-02", "—"),
        )
        val sections = publisherSections(rows, headings)
        assertEquals(listOf("ALPHA", "BRAVO"), sections.map { it.heading.name })
        assertEquals("Henry Canales", sections[0].heading.coordinator)
        assertEquals("A-01", sections[0].heading.code)
        assertEquals(2, sections[0].totals.total)
        assertEquals(2, sections[1].rows.size)
    }

    @Test
    fun `a congregation with no heading record still gets a section`() {
        val sections = publisherSections(rows, emptyList())
        assertEquals(2, sections.size)
        assertEquals("—", sections[0].heading.coordinator)
    }

    @Test
    fun `the summary line starts with the grand total`() {
        assertTrue(publisherTotals(rows).summaryLine().startsWith("Total Publishers: 4"))
    }
}

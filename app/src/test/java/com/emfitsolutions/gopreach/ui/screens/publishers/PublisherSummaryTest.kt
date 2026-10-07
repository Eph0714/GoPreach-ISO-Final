package com.emfitsolutions.gopreach.ui.screens.publishers

import com.emfitsolutions.gopreach.data.model.AccountStatus
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import org.junit.Assert.assertEquals
import org.junit.Test

class PublisherSummaryTest {
    private fun row(name: String, status: AccountStatus, category: PublisherCategory) = PublisherRow(
        person = Person(id = name, firstName = name, lastName = "X", accountStatus = status),
        assignment = RoleAssignment(personId = name),
        category = category,
        groupName = "Group 1",
    )

    private val rows = listOf(
        row("A", AccountStatus.ACTIVE, PublisherCategory.REGULAR_PIONEER),
        row("B", AccountStatus.ACTIVE, PublisherCategory.AUXILIARY_PIONEER),
        row("C", AccountStatus.INACTIVE, PublisherCategory.UNBAPTIZED_PUBLISHER),
        row("D", AccountStatus.SUSPENDED, PublisherCategory.REGULAR_PUBLISHER),
    )

    private fun value(r: List<List<String>>, m: String) = r.first { it[0] == m }[1]

    @Test
    fun countsEveryGroupFromTheRowsShown() {
        val s = PublisherSummary.rows(rows)
        assertEquals("4", value(s, "Total Publishers"))
        assertEquals("2", value(s, "Active"))
        assertEquals("1", value(s, "Inactive"))
        assertEquals("1", value(s, "Suspended"))
        assertEquals("1", value(s, "Regular Pioneers"))
        assertEquals("1", value(s, "Auxiliary Pioneers"))
        assertEquals("1", value(s, "Unbaptized Publishers"))
    }

    @Test
    fun followsTheFilter() {
        val filtered = PublisherListFilter(PublisherFilterMode.STATUS, status = AccountStatus.ACTIVE).apply(rows)
        val s = PublisherSummary.rows(filtered)
        assertEquals("2", value(s, "Total Publishers"))
        assertEquals("0", value(s, "Inactive"))
    }
}

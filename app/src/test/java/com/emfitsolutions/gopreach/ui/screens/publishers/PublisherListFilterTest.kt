package com.emfitsolutions.gopreach.ui.screens.publishers

import com.emfitsolutions.gopreach.data.model.AccountStatus
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import org.junit.Assert.assertEquals
import org.junit.Test

class PublisherListFilterTest {
    private fun row(first: String, last: String, group: String?, status: AccountStatus, middle: String? = null) = PublisherRow(
        person = Person(id = "$first$last", firstName = first, lastName = last, middleInitial = middle, accountStatus = status),
        assignment = RoleAssignment(personId = "$first$last", groupId = group),
        category = PublisherCategory.REGULAR_PUBLISHER,
        groupName = group?.let { "Group $it" } ?: "Unassigned",
    )

    private val rows = listOf(
        row("EPHRAIM", "FERNANDEZ", "1", AccountStatus.ACTIVE, middle = "M"),
        row("JUAN", "DELA CRUZ", "2", AccountStatus.ACTIVE),
        row("MARIA", "SANTOS", "1", AccountStatus.INACTIVE),
        row("PEDRO", "FERNANDEZ", null, AccountStatus.SUSPENDED),
    )

    @Test
    fun allSearchesNameGroupAndStatus() {
        assertEquals(2, PublisherListFilter(PublisherFilterMode.ALL, "fernandez").apply(rows).size)
        assertEquals(2, PublisherListFilter(PublisherFilterMode.ALL, "group 1").apply(rows).size)
        assertEquals(1, PublisherListFilter(PublisherFilterMode.ALL, "suspended").apply(rows).size)
        assertEquals(4, PublisherListFilter(PublisherFilterMode.ALL, "").apply(rows).size)
    }

    @Test
    fun byNameIgnoresGroupAndStatusText() {
        assertEquals(0, PublisherListFilter(PublisherFilterMode.NAME, "group 1").apply(rows).size)
        assertEquals(1, PublisherListFilter(PublisherFilterMode.NAME, "ephr").apply(rows).size)
        assertEquals(1, PublisherListFilter(PublisherFilterMode.NAME, "m").apply(rows).filter { it.person.firstName == "EPHRAIM" }.size)
        assertEquals(1, PublisherListFilter(PublisherFilterMode.NAME, "fernandez, ephraim").apply(rows).size)
    }

    @Test
    fun byGroupAndByStatus() {
        assertEquals(2, PublisherListFilter(PublisherFilterMode.GROUP, groupId = "1").apply(rows).size)
        assertEquals(1, PublisherListFilter(PublisherFilterMode.GROUP, groupId = NO_GROUP_FILTER).apply(rows).size)
        assertEquals(2, PublisherListFilter(PublisherFilterMode.STATUS, status = AccountStatus.ACTIVE).apply(rows).size)
        assertEquals(4, PublisherListFilter(PublisherFilterMode.STATUS, status = null).apply(rows).size)
    }

    @Test
    fun countLabelsDescribeWhatIsShown() {
        assertEquals("Total Publishers: 4", PublisherListFilter().countLabel(4))
        assertEquals("Matching Publishers: 2", PublisherListFilter(PublisherFilterMode.NAME, "fernandez").countLabel(2))
        assertEquals("Active Publishers: 2", PublisherListFilter(PublisherFilterMode.STATUS, status = AccountStatus.ACTIVE).countLabel(2))
        assertEquals("Group 1 Publishers: 2", PublisherListFilter(PublisherFilterMode.GROUP, groupId = "1").countLabel(2, "Group 1"))
    }
}

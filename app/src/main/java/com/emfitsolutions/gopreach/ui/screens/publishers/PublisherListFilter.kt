package com.emfitsolutions.gopreach.ui.screens.publishers

import com.emfitsolutions.gopreach.domain.NameSearch
import com.emfitsolutions.gopreach.data.model.AccountStatus
import com.emfitsolutions.gopreach.data.repository.AccountStatusInfo
import com.emfitsolutions.gopreach.data.repository.PasswordStatus

enum class PublisherFilterMode(val label: String) {
    ALL("All"), NAME("By Name"), GROUP("By Group"), STATUS("By Status"), PASSWORD("By Password")
}

/** [PublisherListFilter.groupId] value for publishers who are not in any FS Group. */
const val NO_GROUP_FILTER = "__none__"

/**
 * The Publisher list's search + filter, in one place: the list on screen and the count shown above it both come
 * from [apply], so the number can never disagree with the rows. It works on rows that are already limited to the
 * selected / authorized congregation, so it can't reach beyond it.
 *
 *  - **All**: [query] matches the publisher's name, their FS Group's name or their status.
 *  - **By Name**: [query] matches first name, middle initial, last name, extension or full name.
 *  - **By Group**: only publishers in [groupId] ([NO_GROUP_FILTER] = no group).
 *  - **By Status**: only publishers whose status is [status] (Active / Inactive / Suspended).
 *  - **By Password**: only publishers whose temporary-password status (from the server) is [password].
 */
data class PublisherListFilter(
    val mode: PublisherFilterMode = PublisherFilterMode.ALL,
    val query: String = "",
    val groupId: String? = null,
    val status: AccountStatus? = null,
    val password: PasswordStatus? = null,
) {
    fun apply(rows: List<PublisherRow>, passwords: Map<String, AccountStatusInfo> = emptyMap()): List<PublisherRow> {
        val q = query.trim().lowercase()
        return rows.filter { row ->
            // The search box is always there: By Name searches the name alone, every other mode the name, FS Group, status, username and password step.
            val queryOk = q.isEmpty() || when (mode) {
                PublisherFilterMode.NAME -> nameParts(row).any { it.contains(q) } || NameSearch.matches(q, *nameParts(row).toTypedArray())
                else -> nameParts(row).any { it.contains(q) } || NameSearch.matches(q, *nameParts(row).toTypedArray()) ||
                    row.groupName.lowercase().contains(q) || statusText(row).contains(q) || row.person.username.lowercase().contains(q) ||
                    (passwords[row.person.id]?.status?.label?.lowercase()?.contains(q) ?: false)
            }
            // The group, status and password choices narrow the list too, whichever mode is showing.
            val groupOk = when (groupId) {
                null -> true
                NO_GROUP_FILTER -> row.assignment.groupId == null
                else -> row.assignment.groupId == groupId
            }
            queryOk && groupOk &&
                (status == null || row.person.accountStatus == status) &&
                (password == null || passwords[row.person.id]?.status == password)
        }
    }

    /** The headline above the list, matching what is being shown: "Total Publishers: 50", "Active Publishers: 42",
     * "Group 1 Publishers: 12", "Matching Publishers: 2". */
    fun countLabel(count: Int, groupName: String? = null): String = when {
        mode == PublisherFilterMode.GROUP && groupId != null -> "${groupName ?: "Group"} Publishers: $count"
        mode == PublisherFilterMode.STATUS && status != null -> "${statusLabel(status)} Publishers: $count"
        mode == PublisherFilterMode.PASSWORD && password != null -> "${password.label}: $count"
        query.isNotBlank() && (mode == PublisherFilterMode.ALL || mode == PublisherFilterMode.NAME) -> "Matching Publishers: $count"
        else -> "Total Publishers: $count"
    }

    private fun nameParts(row: PublisherRow): List<String> {
        val p = row.person
        val first = p.firstName
        val last = p.lastName
        return listOf(
            first, p.middleInitial.orEmpty(), last, p.extensionName.orEmpty(),
            "$first $last", "$last $first", "$last, $first", p.fullName,
        ).map { it.lowercase() }
    }

    private fun statusText(row: PublisherRow): String = statusLabel(row.person.accountStatus).lowercase()

    companion object {
        fun statusLabel(status: AccountStatus): String = status.name.lowercase().replaceFirstChar { it.uppercase() }
    }
}

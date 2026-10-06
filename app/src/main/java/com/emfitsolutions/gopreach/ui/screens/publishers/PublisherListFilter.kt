package com.emfitsolutions.gopreach.ui.screens.publishers

import com.emfitsolutions.gopreach.data.model.AccountStatus

enum class PublisherFilterMode(val label: String) {
    ALL("All"), NAME("By Name"), GROUP("By Group"), STATUS("By Status")
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
 */
data class PublisherListFilter(
    val mode: PublisherFilterMode = PublisherFilterMode.ALL,
    val query: String = "",
    val groupId: String? = null,
    val status: AccountStatus? = null,
) {
    fun apply(rows: List<PublisherRow>): List<PublisherRow> {
        val q = query.trim().lowercase()
        return rows.filter { row ->
            when (mode) {
                PublisherFilterMode.ALL ->
                    q.isEmpty() || nameParts(row).any { it.contains(q) } ||
                        row.groupName.lowercase().contains(q) || statusText(row).contains(q)
                PublisherFilterMode.NAME -> q.isEmpty() || nameParts(row).any { it.contains(q) }
                PublisherFilterMode.GROUP -> when (groupId) {
                    null -> true
                    NO_GROUP_FILTER -> row.assignment.groupId == null
                    else -> row.assignment.groupId == groupId
                }
                PublisherFilterMode.STATUS -> status == null || row.person.accountStatus == status
            }
        }
    }

    /** The headline above the list, matching what is being shown: "Total Publishers: 50", "Active Publishers: 42",
     * "Group 1 Publishers: 12", "Matching Publishers: 2". */
    fun countLabel(count: Int, groupName: String? = null): String = when {
        mode == PublisherFilterMode.GROUP && groupId != null -> "${groupName ?: "Group"} Publishers: $count"
        mode == PublisherFilterMode.STATUS && status != null -> "${statusLabel(status)} Publishers: $count"
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

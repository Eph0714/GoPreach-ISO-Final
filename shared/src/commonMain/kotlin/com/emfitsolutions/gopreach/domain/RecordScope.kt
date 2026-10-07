package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.AdminRole

/**
 * Which publishers' Field Service Records the ACTIVE role of the current session may manage (add, edit, delete). It is derived
 * from the one role the user selected for the session — never from the union of every role the account holds:
 *
 *  - Super Admin → every congregation and publisher;
 *  - Admin / Coordinator Elder / Service Overseer / Secretary → every publisher of their own congregation;
 *  - Group Coordinator / Servant / Assistant → only the publishers of their own FS Group;
 *  - every other role → nothing.
 */
data class RecordScope(
    /** Null only for the Super Admin. */
    val congregationId: String?,
    /** Non-null for a group role: the only FS Group whose publishers may be managed. */
    val groupId: String?,
    val allCongregations: Boolean = false,
) {
    val isGroupScoped: Boolean get() = groupId != null

    /** Whether a publisher of [publisherCongregationId] / FS Group [publisherGroupId] is inside this scope. */
    fun allowsPublisher(publisherCongregationId: String?, publisherGroupId: String?): Boolean = when {
        allCongregations -> true
        publisherCongregationId == null || publisherCongregationId != congregationId -> false
        groupId != null -> publisherGroupId == groupId
        else -> true
    }

    /** A short statement of the scope for the screen ("Group 5 Publishers Only", "All Publishers — Solano"). */
    fun describe(congregationName: String?, groupName: String?): String = when {
        allCongregations -> "All publishers — every congregation"
        groupId != null -> (groupName ?: "FS Group") + " publishers only"
        else -> "All publishers — " + (congregationName ?: "own congregation")
    }
}

/** The congregation-wide roles that manage every publisher's records of their own congregation. */
val CongregationWideRecordRoles = setOf(
    AdminRole.ADMIN_PER_CONGREGATION,
    AdminRole.COORDINATOR_ELDER,
    AdminRole.SERVICE_OVERSEER,
    AdminRole.SECRETARY,
)

/** The scope of the session's active role, or null when that role may not manage field service records at all. */
fun recordScopeOf(state: SessionState): RecordScope? {
    val assignment = state.activeRoleAssignment ?: return null
    val role = state.activeAdminRole
    return when {
        state.person?.isSuperAdmin == true || role == AdminRole.SUPER_ADMIN -> RecordScope(null, null, allCongregations = true)
        role in CongregationWideRecordRoles -> assignment.congregationId?.let { RecordScope(it, null) }
        // A group role: scoped to the group of the assignment, whatever else the account is.
        state.activeGroupId != null -> assignment.congregationId?.let { RecordScope(it, state.activeGroupId) }
        else -> null
    }
}

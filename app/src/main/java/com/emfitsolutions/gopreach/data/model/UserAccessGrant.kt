package com.emfitsolutions.gopreach.data.model

/**
 * The complete WHAT (permissions) + WHERE (scope) for one restricted user —
 * spec's "GoPreach App: Super-Admin Account and User Access Management" §6-§8.
 * One grant per [Person] who holds an [AdminRole.CIRCUIT_OVERSEER] RoleAssignment
 * (or any other future restricted role); a person with a built-in role
 * (Super-Admin, Admin Per Congregation, Coordinator Elder, Regular Elder) never
 * needs one — their access is the existing fixed set those roles have always had.
 *
 * Firestore collection: `userAccessGrants/{personId}` — **keyed by personId, not
 * an auto-id**. That's deliberate: it's what lets both the app and the Firestore
 * security rules (see firestore.rules) look this up with a single, cheap
 * `get()`/`exists()` by a known path instead of a query, which rules can't do
 * against arbitrary fields. Never create one with a different document id.
 */
data class UserAccessGrant(
    val personId: String = "",

    /** Serialized [Permission] names — Firestore has no native enum-list type. */
    val permissions: List<String> = emptyList(),

    /** Serialized [ScopeType] name. */
    val scopeType: String = ScopeType.SELECTED_CONGREGATIONS.name,
    val scopeCongregationIds: List<String> = emptyList(),
    val scopeGroupIds: List<String> = emptyList(),

    // Audit trail — who configured this access, and when it last changed.
    val createdByPersonId: String = "",
    val createdAt: Long = 0L,
    val lastEditedByPersonId: String? = null,
    val lastEditedAt: Long? = null,
) {
    val resolvedPermissions: Set<Permission>
        get() = permissions.mapNotNull { runCatching { Permission.valueOf(it) }.getOrNull() }.toSet()

    val resolvedScopeType: ScopeType
        get() = runCatching { ScopeType.valueOf(scopeType) }.getOrDefault(ScopeType.SELECTED_CONGREGATIONS)

    fun allows(permission: Permission, congregationId: String? = null, groupId: String? = null): Boolean {
        if (permission !in resolvedPermissions) return false
        return when (resolvedScopeType) {
            ScopeType.ALL_CONGREGATIONS -> true
            ScopeType.SELECTED_CONGREGATIONS -> congregationId != null && congregationId in scopeCongregationIds
            ScopeType.SELECTED_GROUPS -> groupId != null && groupId in scopeGroupIds
        }
    }
}

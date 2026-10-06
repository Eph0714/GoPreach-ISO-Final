package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.AccountStatus
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Permission
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.model.UserAccessGrant

/**
 * Every permission check in the app reduces to one question (spec §3): "does this
 * Person have an ACTIVE RoleAssignment matching role X in scope Y" — never a lookup
 * on the Person record, since a person's admin role and publisher category are
 * separate RoleAssignment rows that can both be true at once.
 */
object PermissionChecker {

    /** True if any of [assignments] is an active Admin RoleAssignment of [role],
     * scoped to [congregationId] (Super-Admin is congregation-agnostic and matches
     * any congregation) and, when [role] is REGULAR_ELDER, to [groupId]. */
    fun hasAdminRole(
        assignments: List<RoleAssignment>,
        role: AdminRole,
        congregationId: String? = null,
        groupId: String? = null,
    ): Boolean = assignments.any { a ->
        a.status == RoleAssignmentStatus.ACTIVE &&
            (a.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role == role &&
            (role == AdminRole.SUPER_ADMIN || a.congregationId == congregationId) &&
            (role != AdminRole.REGULAR_ELDER || a.groupId == groupId)
    }

    /** True if any of [assignments] is an active Publisher RoleAssignment. Category
     * is intentionally not part of most checks — most publisher-facing capabilities
     * (submit a report, log a visit) apply to any active publisher category. */
    fun isActivePublisher(assignments: List<RoleAssignment>): Boolean = assignments.any { a ->
        a.status == RoleAssignmentStatus.ACTIVE && a.resolvedRoleTypeOrNull() is RoleType.Publisher
    }

    /** Roles with full CRUD on Territory Assignment, Publishers, Ministerial
     * Servants and Elders within their own congregation. Mirrors
     * `canManageRoleAssignmentsFor`/`canManageTerritoryAssignmentsFor` in
     * firestore.rules (Super-Admin is congregation-agnostic). */
    val FULL_CRUD_ROLES: Set<AdminRole> = setOf(
        AdminRole.SUPER_ADMIN,
        AdminRole.ADMIN_PER_CONGREGATION,
        AdminRole.COORDINATOR_ELDER,
        AdminRole.SERVICE_OVERSEER,
        AdminRole.SECRETARY,
    )

    const val NO_ACCESS_MESSAGE = "No access. Contact Admin"

    /** The first active [FULL_CRUD_ROLES] assignment among [assignments], if any. */
    fun fullCrudAssignment(assignments: List<RoleAssignment>): RoleAssignment? = assignments.firstOrNull { a ->
        a.status == RoleAssignmentStatus.ACTIVE &&
            (a.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role in FULL_CRUD_ROLES
    }

    /** The most senior Admin-track role among [assignments], if any — for deciding
     * which Control Panel / management screens to surface after login.
     *
     * Uses [RoleAssignment.resolvedRoleTypeOrNull] (never throws): this runs on
     * *every* login before the Main Form renders, so one corrupt/unparseable
     * RoleAssignment here used to crash the app immediately after a correct
     * login instead of just being ignored like it holds no admin role. */
    fun highestAdminRole(assignments: List<RoleAssignment>): AdminRole? {
        val activeAdminRoles = assignments
            .filter { it.status == RoleAssignmentStatus.ACTIVE }
            .mapNotNull { (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role }
            .toSet()
        return listOf(
            AdminRole.SUPER_ADMIN,
            AdminRole.ADMIN_PER_CONGREGATION,
            AdminRole.COORDINATOR_ELDER,
            AdminRole.SERVICE_OVERSEER,
            AdminRole.SECRETARY,
            AdminRole.REGULAR_ELDER,
            AdminRole.MINISTERIAL_SERVANT,
            AdminRole.CIRCUIT_OVERSEER,
        ).firstOrNull { it in activeAdminRoles }
    }

    /** Everything an [AdminRole.ADMIN_PER_CONGREGATION] can do within their own
     * congregation, today, with no [UserAccessGrant] involved — used only as the
     * comparison set an Admin's own [UserAccessGrant] (if any, for
     * "Admin can manage users only if explicitly authorized" — spec §14) is
     * layered on top of, never to gate the four built-in roles themselves. */
    val FULL_CONGREGATION_PERMISSIONS: Set<Permission> = Permission.entries.toSet() - Permission.MANAGE_USERS

    /**
     * WHAT+WHERE check for a restricted ([AdminRole.CIRCUIT_OVERSEER] or any
     * future grant-based) user — spec §6/§13: "Authenticated User -> Role ->
     * Permission -> Scope -> Requested Data". The four built-in roles are
     * untouched by this function; it only ever narrows access for a person whose
     * *only* relevant RoleAssignment is grant-based, or grants an Admin an
     * extra capability (MANAGE_USERS) their built-in role doesn't imply on its
     * own. Mirrored server-side in firestore.rules for the same collections —
     * see that file's comments for exactly which ones and why.
     */
    fun hasPermission(
        assignments: List<RoleAssignment>,
        grant: UserAccessGrant?,
        permission: Permission,
        congregationId: String? = null,
        groupId: String? = null,
    ): Boolean {
        if (hasAdminRole(assignments, AdminRole.SUPER_ADMIN)) return true
        if (hasAdminRole(assignments, AdminRole.ADMIN_PER_CONGREGATION, congregationId = congregationId) &&
            permission in FULL_CONGREGATION_PERMISSIONS
        ) {
            return true
        }
        // MANAGE_USERS is the one capability even a built-in Admin doesn't carry
        // implicitly (spec §2/§14: "Admin can manage users only if explicitly
        // authorized") — so it's always resolved from the grant, for anyone.
        return grant?.allows(permission, congregationId, groupId) == true
    }

    /** Spec §9 — an INACTIVE/SUSPENDED account may hold ever so many still-ACTIVE
     * RoleAssignments; none of them matter once the account itself is disabled.
     * Checked at sign-in ([com.emfitsolutions.gopreach.data.repository.AuthRepository.signIn]);
     * exposed here too so any other call site can ask the same question the
     * same way. */
    fun isAccountUsable(person: Person): Boolean = person.accountStatus == AccountStatus.ACTIVE

    /** Overload that also enforces "CREATING PUBLISHER" spec's Removed
     * Publisher rule: "Remove publisher cannot Open the Publisher's Account
     * anymore unless the status will be change on any other status other
     * than Removed Publisher." Someone whose *only* role is a Publisher
     * category, all of them REMOVED_PUBLISHER, is blocked; anyone who also
     * holds an active Admin role (Coordinator Elder, etc.) is unaffected —
     * this rule is about locking the Publisher account itself, not an Admin
     * account that happens to carry a stale removed category. */
    fun isAccountUsable(person: Person, roleAssignments: List<RoleAssignment>): Boolean {
        if (!isAccountUsable(person)) return false
        if (highestAdminRole(roleAssignments) != null) return true
        val activeCategories = roleAssignments
            .filter { it.status == RoleAssignmentStatus.ACTIVE }
            .mapNotNull { (it.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.category }
        return activeCategories.isEmpty() || activeCategories.any { it != PublisherCategory.REMOVED_PUBLISHER }
    }

    /** Account Management spec's permission matrix (§5): who may change another
     * person's username/account status, and whose accounts they may act on.
     * Not wired through [hasPermission]/[UserAccessGrant] — like every other
     * Coordinator Elder/Service Overseer capability in this app, it's a direct
     * [AdminRole] check (see [com.emfitsolutions.gopreach.ui.screens.home
     * .AdminHomeScreen]'s own `canViewUserLogs`-style booleans), not a second
     * permission mechanism alongside the grant-based one CIRCUIT_OVERSEER uses.
     *
     * [targetAdminRole] is null when the target's only active role is
     * Publisher. [SECRETARY] is treated exactly like [AdminRole.SERVICE_OVERSEER]
     * everywhere else in this codebase (see that enum constant's own doc
     * comment), so it is here too. Mirrored server-side by
     * `canManageCredentialsFor` in firestore.rules — this function only ever
     * decides what the UI *offers*; the rule is what actually enforces it. */
    fun canManageCredentialsFor(
        actingRole: AdminRole,
        actingCongregationId: String?,
        targetIsPublisher: Boolean,
        targetAdminRole: AdminRole?,
        targetCongregationId: String?,
    ): Boolean {
        if (actingRole == AdminRole.SUPER_ADMIN) return true
        if (actingCongregationId == null || actingCongregationId != targetCongregationId) return false
        return when (actingRole) {
            AdminRole.ADMIN_PER_CONGREGATION ->
                targetIsPublisher || targetAdminRole == AdminRole.REGULAR_ELDER || targetAdminRole == AdminRole.MINISTERIAL_SERVANT
            AdminRole.COORDINATOR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY ->
                targetIsPublisher
            else -> false
        }
    }
}

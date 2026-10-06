package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType

/**
 * Which Field Service Groups a signed-in user may manage, resolved from *every* active role they hold
 * (not just the one picked at login) so the widest applicable scope always wins:
 *
 *  - Super Admin → [AllCongregations]
 *  - Admin / Coordinator Elder / Secretary / Service Overseer → [Congregation] (all groups of that congregation)
 *  - only Group Overseer / Servant / Assistant → [OwnGroup] (just the group(s) they are assigned to,
 *    decided by the Group record itself — see [OwnGroup.allows])
 *  - anything else → [None]
 */
sealed class GroupAccessScope {
    data object AllCongregations : GroupAccessScope()
    data class Congregation(val congregationId: String) : GroupAccessScope()
    data class OwnGroup(val congregationId: String?, val personId: String) : GroupAccessScope()
    data object None : GroupAccessScope()

    /** Add / deactivate / delete groups — never available to a group-level user. */
    val canAddOrRemoveGroups: Boolean get() = this is AllCongregations || this is Congregation
    val canOpen: Boolean get() = this !is None

    companion object {
        private val CONGREGATION_WIDE = setOf(
            AdminRole.ADMIN_PER_CONGREGATION, AdminRole.COORDINATOR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY,
        )

        fun resolve(person: Person?, assignments: List<RoleAssignment>): GroupAccessScope {
            if (person == null) return None
            val active = assignments.filter { it.status == RoleAssignmentStatus.ACTIVE }
            fun role(a: RoleAssignment) = (a.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role
            if (person.isSuperAdmin || active.any { role(it) == AdminRole.SUPER_ADMIN }) return AllCongregations
            active.firstOrNull { role(it) in CONGREGATION_WIDE && it.congregationId != null }
                ?.let { return Congregation(it.congregationId!!) }
            // Group-level: an Elder / Ministerial Servant on a group, or a Publisher placed in the Assistant slot.
            // Publishers who are merely members (groupId set, no slot) are filtered out later by [allows].
            val grouped = active.firstOrNull { it.groupId != null }
            return if (grouped != null) OwnGroup(grouped.congregationId, person.id) else None
        }
    }
}

/** True when [group] is inside this scope; for [GroupAccessScope.OwnGroup] only if the person holds one of its three slots. */
fun GroupAccessScope.allows(group: com.emfitsolutions.gopreach.data.model.Group): Boolean = when (this) {
    GroupAccessScope.AllCongregations -> true
    is GroupAccessScope.Congregation -> group.congregationId == congregationId
    is GroupAccessScope.OwnGroup ->
        group.congregationId == congregationId &&
            personId in listOfNotNull(group.overseerPersonId, group.servantPersonId, group.assistantPersonId)
    GroupAccessScope.None -> false
}

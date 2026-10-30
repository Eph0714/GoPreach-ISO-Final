package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType

/**
 * The people whose sign-in (username, temporary password) an Overseer, Servant or Assistant of an FS Group may manage: the PLAIN publishers of THEIR OWN group - an active publisher role in
 * [groupId] and nothing above it: no admin-track role, not the Super Admin, not an officer of any FS Group (their own group's included) and never [selfId]. The server applies the same rule
 * to every change; this only decides what the screen offers.
 */
fun plainGroupMemberIds(
    groupId: String,
    selfId: String,
    people: List<Person>,
    assignments: List<RoleAssignment>,
    groups: List<Group>,
): Set<String> {
    val officers = groups.flatMap { listOfNotNull(it.overseerPersonId, it.servantPersonId, it.assistantPersonId) }.toSet()
    val active = assignments.filter { it.status == RoleAssignmentStatus.ACTIVE }
    val above = active.filter { it.resolvedRoleTypeOrNull() is RoleType.Admin }.map { it.personId }.toSet()
    val members = active
        .filter { it.groupId == groupId && (it.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.category.let { c -> c != null && c != PublisherCategory.REMOVED_PUBLISHER } }
        .map { it.personId }
        .toSet()
    val byId = people.associateBy { it.id }
    return members.filter { id ->
        val p = byId[id]
        p != null && id != selfId && id !in officers && id !in above && !p.isSuperAdmin && p.activeAdminRole == null
    }.toSet()
}

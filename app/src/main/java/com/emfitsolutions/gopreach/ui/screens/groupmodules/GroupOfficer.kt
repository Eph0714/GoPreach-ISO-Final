package com.emfitsolutions.gopreach.ui.screens.groupmodules

import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType

/** The FS Group of which [personId] is the Overseer, Servant or Assistant, read from the group's own slots (never from a profile field), and which of the three they are. */
data class OfficerSlot(val group: Group, val label: String)

fun officerSlotOf(personId: String, congregationId: String?, groups: List<Group>): OfficerSlot? {
    if (personId.isBlank()) return null
    return groups.firstOrNull { it.status == RecordStatus.ACTIVE && (congregationId == null || it.congregationId == congregationId) && personId in listOfNotNull(it.overseerPersonId, it.servantPersonId, it.assistantPersonId) }
        ?.let { g ->
            OfficerSlot(
                g,
                when (personId) {
                    g.overseerPersonId -> "Group Overseer"
                    g.servantPersonId -> "Group Servant"
                    else -> "Group Assistant"
                },
            )
        }
}

/** The publishers of [group] now: an ACTIVE publisher role naming the group (never a removed publisher) and the group's three officers. */
fun groupMemberIds(group: Group, assignments: List<RoleAssignment>): Set<String> = buildSet {
    listOfNotNull(group.overseerPersonId, group.servantPersonId, group.assistantPersonId).forEach(::add)
    assignments.filter { it.groupId == group.id && it.status == RoleAssignmentStatus.ACTIVE }.forEach { a ->
        val type = a.resolvedRoleTypeOrNull() as? RoleType.Publisher ?: return@forEach
        if (type.category != PublisherCategory.REMOVED_PUBLISHER) add(a.personId)
    }
}

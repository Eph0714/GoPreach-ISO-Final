package com.emfitsolutions.gopreach.ui.screens.groups

import com.emfitsolutions.gopreach.platform.nowMillis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.RegularElderRole
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.GroupRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.domain.GroupAccessScope
import com.emfitsolutions.gopreach.domain.allows
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** One candidate for the Group Assistant dropdown — [isElder] distinguishes
 * a Regular Elder candidate (from [ManageGroupsViewModel.availableEldersFor])
 * from a Publisher one (browsed from the Publishers Record), since the two
 * pools are merged into one list for that dropdown. */
data class PersonCandidate(val person: Person, val isElder: Boolean)

data class GroupRow(
    val group: Group,
    val overseerName: String?,
    val servantName: String?,
    val assistantName: String?,
)

/** One Publisher candidate for the "[ADD MEMBERS] Browse from Publishers
 * Record" section of the Group dialog — [currentGroupName] is non-null when
 * they're already a member of some *other* group, so the UI can make clear
 * that checking them here transfers them off it (spec: "A publisher can only
 * be assign in a single group but it can be transferred to other group from
 * time to time"). */
data class MemberCandidate(
    val person: Person,
    val assignment: RoleAssignment,
    val currentGroupName: String?,
)

/** Spec §3 — "CRUD Groups"; each Group needs exactly one Elder in each of three
 * roles (Overseer/Servant/Assistant) rather than the single Elder this used to
 * allow — see [Group.missingRoles]. */
class ManageGroupsViewModel(
    private val groupRepository: GroupRepository,
    private val personRepository: PersonRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val auditLogRepository: AuditLogRepository,
    private val recycleBinRepository: com.emfitsolutions.gopreach.data.repository.RecycleBinRepository,
    congregationRepository: CongregationRepository,
) : ViewModel() {

    /** Only needed by a Super-Admin, who isn't scoped to one congregation
     * already — lets them pick which congregation a new group belongs to. */
    val congregations: Flow<List<Congregation>> = congregationRepository.observeAll()

    /** "Field Service Group Name (Required, avoid duplicate name in a
     * congregation)" — every other active Field Service Group's name within
     * [congregationId], uppercased for a case-insensitive comparison,
     * excluding [excludeGroupId] itself (so re-saving an existing group
     * under its own unchanged name never trips this). The dialog collects
     * this once per congregation/exclude-id combination and checks
     * membership locally on submit, the same "cheap client-side recheck"
     * pattern [com.emfitsolutions.gopreach.data.repository
     * .CongregationRepository.isCodeAvailable] uses for a Congregation's
     * own unique code. */
    fun namesInUse(congregationId: String, excludeGroupId: String?): Flow<Set<String>> =
        groupRepository.observeAll().map { groups ->
            groups
                .filter { it.congregationId == congregationId && it.status == RecordStatus.ACTIVE && it.id != excludeGroupId }
                .map { it.name.trim().uppercase() }
                .toSet()
        }

    /** Regular Elders available for [role], scoped to one congregation and — when
     * editing an existing group — excluding whoever already fills a *different*
     * role in that same group (an Elder can't hold two of the three roles at
     * once in one Group). Matches by
     * [com.emfitsolutions.gopreach.data.model.RoleAssignment.regularElderRole]
     * when set; an Elder enrolled before that field existed has none, and is
     * offered as a candidate for *any* role — placing them in a role's dropdown
     * is what assigns them that role (see [save]), which is how an existing
     * single-Elder Group gets migrated onto the three-role structure without
     * losing who was already assigned. */
    fun availableEldersFor(congregationId: String, role: RegularElderRole, excludePersonIds: Set<String> = emptySet()): Flow<List<Person>> =
        combine(personRepository.observeAll(), roleAssignmentRepository.observeAll()) { people, assignments ->
            assignments
                .filter { assignment ->
                    assignment.status == RoleAssignmentStatus.ACTIVE &&
                        assignment.congregationId == congregationId &&
                        (assignment.regularElderRole == role || assignment.regularElderRole == null) &&
                        // resolvedRoleTypeOrNull, never the throwing resolvedRoleType
                        // (see DashboardStats.computeStatMembers' doc comment).
                        (assignment.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role == AdminRole.REGULAR_ELDER &&
                        assignment.personId !in excludePersonIds
                }
                .mapNotNull { assignment -> people.firstOrNull { it.id == assignment.personId } }
        }

    /** "Get the Group Servant dropdown from the Ministerial Servant list" — the active Ministerial
     * Servants of [congregationId] (minus whoever already holds another role in this group).
     * Picking one only records them as this group's servant; their own role stays Ministerial
     * Servant — nothing here ever turns them into a Regular Elder. */
    fun availableMinisterialServantsFor(congregationId: String, excludePersonIds: Set<String> = emptySet()): Flow<List<Person>> =
        combine(personRepository.observeAll(), roleAssignmentRepository.observeAll()) { people, assignments ->
            assignments
                .filter { assignment ->
                    assignment.status == RoleAssignmentStatus.ACTIVE &&
                        assignment.congregationId == congregationId &&
                        (assignment.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role == AdminRole.MINISTERIAL_SERVANT &&
                        assignment.personId !in excludePersonIds
                }
                .mapNotNull { assignment -> people.firstOrNull { it.id == assignment.personId } }
                .distinctBy { it.id }
                .sortedBy { it.fullName }
        }

    /** "'Group Assistant' can be browse from Publishers Record" — unlike
     * Overseer/Servant (Elder-only), the Assistant slot can also be filled by
     * any active Publisher in [congregationId], not just a Regular Elder.
     * [PersonCandidate.isElder] is what [ElderRoleDropdown] uses to show
     * "(Elder)"/"(Publisher)" next to each name, since the merged list would
     * otherwise be ambiguous about which pool a given name came from. */
    fun availableAssistantCandidatesFor(congregationId: String, excludePersonIds: Set<String> = emptySet()): Flow<List<PersonCandidate>> =
        combine(
            availableEldersFor(congregationId, RegularElderRole.GROUP_ASSISTANT, excludePersonIds),
            personRepository.observeAll(),
            roleAssignmentRepository.observeAll(),
        ) { elders, people, assignments ->
            val publishers = assignments
                .filter { assignment ->
                    assignment.status == RoleAssignmentStatus.ACTIVE &&
                        assignment.congregationId == congregationId &&
                        assignment.resolvedRoleTypeOrNull() is RoleType.Publisher &&
                        assignment.personId !in excludePersonIds
                }
                .mapNotNull { assignment -> people.firstOrNull { it.id == assignment.personId } }
            (elders.map { PersonCandidate(it, isElder = true) } + publishers.map { PersonCandidate(it, isElder = false) })
                .distinctBy { it.person.id }
                .sortedBy { it.person.fullName }
        }

    /** Every active Publisher (any category) in [congregationId] — the pool
     * ADD MEMBERS browses. [MemberCandidate.currentGroupName] tells the UI
     * whether checking one here would transfer them off a different group. */
    fun membersFor(congregationId: String): Flow<List<MemberCandidate>> =
        combine(personRepository.observeAll(), roleAssignmentRepository.observeAll(), groupRepository.observeAll()) { people, assignments, groups ->
            assignments
                .filter { assignment ->
                    assignment.status == RoleAssignmentStatus.ACTIVE &&
                        assignment.congregationId == congregationId &&
                        assignment.resolvedRoleTypeOrNull() is RoleType.Publisher
                }
                .mapNotNull { assignment ->
                    val person = people.firstOrNull { it.id == assignment.personId } ?: return@mapNotNull null
                    val currentGroupName = groups.firstOrNull { it.id == assignment.groupId }?.name
                    MemberCandidate(person, assignment, currentGroupName)
                }
                .sortedBy { it.person.fullName }
        }

    /** Saves the Group and its three Elder role slots (via [save]'s existing
     * logic), then applies the ADD MEMBERS section's checkbox changes:
     * [selectedMemberPersonIds] is who should end up assigned to this group
     * once saving is done — everyone else in [candidates] currently assigned
     * here gets cleared instead, and a checked Publisher already on a
     * *different* group is simply moved (spec: "can only be assign in a
     * single group but it can be transferred to other group from time to
     * time"). Runs as one coroutine so the group's real id (assigned on
     * first save, for a brand-new group) exists before members are written
     * against it.
     *
     * Re-reads each candidate's RoleAssignment fresh *after* [saveGroupAndSyncElders]
     * runs, rather than trusting [candidates]' own pre-save snapshot — now that
     * a Publisher can fill the Group Assistant slot too ("'Group Assistant'
     * can be browse from Publishers Record"), that sync may have just changed
     * the very same Publisher's `groupId` this loop is about to compare
     * against and possibly overwrite; a stale snapshot here would silently
     * undo that assistant-slot assignment the instant this loop ran. */
    fun saveWithMembers(
        group: Group,
        candidates: List<MemberCandidate>,
        selectedMemberPersonIds: Set<String>,
        actorPersonId: String,
    ) {
        viewModelScope.launch {
            val saved = saveGroupAndSyncElders(group)
            val freshAssignments = roleAssignmentRepository.observeAll().first()
            candidates.forEach { candidate ->
                val current = freshAssignments.firstOrNull { it.id == candidate.assignment.id } ?: candidate.assignment
                val shouldBeMember = candidate.person.id in selectedMemberPersonIds
                val isAlreadyMember = current.groupId == saved.id
                if (shouldBeMember == isAlreadyMember) return@forEach
                val newGroupId = if (shouldBeMember) saved.id else null
                roleAssignmentRepository.save(
                    current.copy(
                        groupId = newGroupId,
                        lastEditedByPersonId = actorPersonId,
                        lastEditedAt = nowMillis(),
                    )
                )
                auditLogRepository.log(
                    actorPersonId = actorPersonId,
                    action = "CHANGE_PUBLISHER_GROUP",
                    targetType = "Person",
                    targetId = candidate.person.id,
                    congregationId = candidate.assignment.congregationId,
                    details = "group: ${candidate.currentGroupName ?: "Unassigned"} -> ${if (shouldBeMember) saved.name else "Unassigned"}",
                )
            }
        }
    }

    fun rowsFor(congregationId: String?, scope: GroupAccessScope? = null): Flow<List<GroupRow>> =
        combine(groupRepository.observeAll(), personRepository.observeAll()) { groups, people ->
            groups
                .filter { congregationId == null || it.congregationId == congregationId }
                .filter { scope == null || scope.allows(it) }
                .map { group ->
                    GroupRow(
                        group = group,
                        overseerName = people.firstOrNull { it.id == group.overseerPersonId }?.fullName,
                        servantName = people.firstOrNull { it.id == group.servantPersonId }?.fullName,
                        assistantName = people.firstOrNull { it.id == group.assistantPersonId }?.fullName,
                    )
                }
                .sortedWith(com.emfitsolutions.gopreach.domain.NaturalOrder.by { it.group.name })
        }

    /** Saves the Group, then mirrors each role slot onto that Elder's own
     * RoleAssignment.groupId — that mirror (not the Group document itself) is
     * what [com.emfitsolutions.gopreach.domain.PermissionChecker]-driven report/
     * schedule/calendar scoping actually reads for a signed-in Regular Elder.
     * Anyone *removed* from a slot (replaced, or cleared) has their groupId
     * cleared too, so they don't keep seeing a group they're no longer on. */
    fun save(group: Group) {
        viewModelScope.launch { saveGroupAndSyncElders(group) }
    }

    /** The suspend core [save] wraps — factored out so [saveWithMembers] can
     * run it and the ADD MEMBERS sync in the same coroutine, one after the
     * other, instead of two independently-launched writes racing each other
     * for a brand-new Group's freshly-assigned id. */
    private suspend fun saveGroupAndSyncElders(group: Group): Group {
        val previous = if (group.id.isNotBlank()) groupRepository.observeAll().first().firstOrNull { it.id == group.id } else null
        val saved = groupRepository.save(group)

        suspend fun sync(role: RegularElderRole, oldPersonId: String?, newPersonId: String?) {
            if (oldPersonId == newPersonId) {
                if (newPersonId != null) setPersonGroup(newPersonId, saved.id, role)
                return
            }
            if (oldPersonId != null) setPersonGroup(oldPersonId, null, null)
            if (newPersonId != null) setPersonGroup(newPersonId, saved.id, role)
        }

        sync(RegularElderRole.GROUP_OVERSEER, previous?.overseerPersonId, saved.overseerPersonId)
        sync(RegularElderRole.GROUP_SERVANT, previous?.servantPersonId, saved.servantPersonId)
        sync(RegularElderRole.GROUP_ASSISTANT, previous?.assistantPersonId, saved.assistantPersonId)
        return saved
    }

    /** [role] is only written when non-null (assigning), and only onto an
     * actual Regular Elder's assignment — clearing an Elder from a slot
     * (role = null alongside groupId = null) leaves their regularElderRole as
     * history rather than wiping it, since they may be re-added to the same
     * role in a different Group later. Also handles a Publisher filling the
     * Group Assistant slot ("'Group Assistant' can be browse from Publishers
     * Record") — their own RoleAssignment.groupId is kept in sync exactly
     * like a regular ADD MEMBERS pick ([saveWithMembers]) would, just driven
     * from this role slot instead; [regularElderRole] is never written onto a
     * Publisher's assignment, since that field only has meaning for an Elder. */
    private suspend fun setPersonGroup(personId: String, groupId: String?, role: RegularElderRole?) {
        val assignments = roleAssignmentRepository.observeForPerson(personId).first()
        fun adminRole(a: com.emfitsolutions.gopreach.data.model.RoleAssignment) = (a.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role
        // A Regular Elder's own assignment first, then a Ministerial Servant's (which already carries
        // its own group role, see the Ministerial Servant screen), then a Publisher's. No assignment's
        // role type is ever changed — a Ministerial Servant stays a Ministerial Servant.
        val assignment = assignments.firstOrNull { adminRole(it) == AdminRole.REGULAR_ELDER }
            ?: assignments.firstOrNull { adminRole(it) == AdminRole.MINISTERIAL_SERVANT }
            ?: assignments.firstOrNull { it.resolvedRoleTypeOrNull() is RoleType.Publisher }
            ?: return
        val carriesGroupRole = adminRole(assignment) == AdminRole.REGULAR_ELDER || adminRole(assignment) == AdminRole.MINISTERIAL_SERVANT
        roleAssignmentRepository.save(
            assignment.copy(
                groupId = groupId,
                regularElderRole = if (carriesGroupRole) (role ?: assignment.regularElderRole) else assignment.regularElderRole,
            ),
        )
    }

    /** "Move to Inactive" / reactivate — the group and its elder assignments are
     * untouched; this only hides it from the normal active list. */
    fun setStatus(group: Group, status: RecordStatus, actorPersonId: String) {
        viewModelScope.launch {
            val previous = group.status
            groupRepository.save(group.copy(status = status))
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "CHANGE_GROUP_STATUS",
                targetType = "Group",
                targetId = group.id,
                congregationId = group.congregationId,
                details = "status: $previous -> $status",
            )
        }
    }

    /** Non-blocking heads-up for the confirmation dialog — Super-Admin's
     * force-delete is never refused (spec: "Allow the super admin to force
     * delete record under all modules"); [permanentlyDelete] already clears
     * every member's groupId back to Unassigned rather than leaving it
     * dangling, so this is purely informational. */
    suspend fun permanentDeleteImpactSummary(groupId: String): String? {
        val memberCount = roleAssignmentRepository.observeAll().first()
            .count { it.groupId == groupId && (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role != AdminRole.REGULAR_ELDER }
        return if (memberCount > 0) {
            "This will unassign $memberCount publisher(s) currently in this group (they become Unassigned, not deleted)."
        } else null
    }

    fun permanentlyDelete(groupId: String, actorPersonId: String) {
        viewModelScope.launch {
            // Clear groupId for whoever was on this group so they don't keep
            // seeing a report/calendar scope that no longer exists — Elders in
            // one of the three named roles, and (spec §4 gap fix) any Publisher
            // whose RoleAssignment.groupId still points at this group.
            val group = groupRepository.observeAll().first().firstOrNull { it.id == groupId }
            if (group != null) {
                // The group, plus every member whose group link is about to be cleared (put back on restore only
                // if that person still has no group).
                val members = roleAssignmentRepository.observeAll().first().filter { it.groupId == groupId }
                recycleBinRepository.moveToTrash(
                    recordType = "Field Service Group",
                    module = "Field Service Groups",
                    label = group.name,
                    congregationId = group.congregationId,
                    groupId = group.id,
                    groupName = group.name,
                    originalCreatedAt = group.createdAt,
                    deletedByPersonId = actorPersonId,
                    items = buildList {
                        add(recycleBinRepository.item("groups", group.id, group))
                        members.forEach { add(recycleBinRepository.relationshipItem("roleAssignments", it.id, it, "groupId")) }
                    },
                )
                listOfNotNull(group.overseerPersonId, group.servantPersonId, group.assistantPersonId, group.regularElderPersonId)
                    .distinct()
                    .forEach { setPersonGroup(it, null, null) }
            }
            roleAssignmentRepository.observeAll().first()
                .filter { it.groupId == groupId }
                .forEach { roleAssignmentRepository.save(it.copy(groupId = null)) }
            groupRepository.delete(groupId)
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "PERMANENT_DELETE_GROUP",
                targetType = "Group",
                targetId = groupId,
                congregationId = group?.congregationId,
            )
        }
    }
}

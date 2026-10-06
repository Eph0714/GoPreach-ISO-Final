package com.emfitsolutions.gopreach.ui.screens.elders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RegularElderRole
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.GroupRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Every primary admin-track role now managed from the single "Elders"
 * module ("Consolidate Elder, Coordinator Elder, Service Overseer and
 * Secretary Enrollment"). Ministerial Servant stays its own separate module
 * (spec's own §43 menu structure keeps it apart from Elders) even though it
 * shares the exact same `roleAssignments`/Group-role machinery underneath. */
val ELDER_PRIMARY_ROLES: Set<AdminRole> = setOf(
    AdminRole.REGULAR_ELDER, AdminRole.COORDINATOR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY,
)

/**
 * One person shown in the unified Elders module — aggregates *every* active
 * [ELDER_PRIMARY_ROLES] [RoleAssignment] this person holds, plus their Group
 * role and Publisher category, into a single row (spec §8: "stored as one
 * person/entity with multiple role assignments... do NOT create duplicate
 * person records simply because the person has multiple roles"). A person
 * checked for, say, both Coordinator Elder and Secretary at once (spec §8/§12
 * explicitly allows this — "the system must not invent new restrictions")
 * still shows up as exactly one [EldersRow], with both in [adminRoles].
 */
data class EldersRow(
    val person: Person,
    val congregationId: String,
    val congregationName: String,
    /** Every primary role this person currently holds ACTIVE — almost always
     * one, but the data model (several simultaneous RoleAssignment docs per
     * person, same as this app's existing Publisher-category + admin-role
     * combinations) allows more than one, and spec §8/§12 explicitly wants
     * that to be possible rather than artificially forced exclusive. */
    val adminRoles: Set<AdminRole>,
    /** This person's Group Overseer/Servant/Assistant slot, if any — lives on
     * whichever of their assignments actually is [AdminRole.REGULAR_ELDER]
     * (their own primary one if they have it, otherwise a separate
     * "additional role" assignment exactly like Coordinator/Service
     * Overseer/Secretary enrollment already creates for this — see
     * [EldersEnrollmentViewModel]/this class's own [updateRolesAndPerson]). */
    val regularElderRole: RegularElderRole?,
    val publisherCategory: PublisherCategory?,
    val isActive: Boolean,
    /** Every ACTIVE-or-not [ELDER_PRIMARY_ROLES] assignment for this person —
     * what [updateRolesAndPerson]/[setActive]/[permanentlyDelete] actually
     * operate on. */
    val primaryAssignments: List<RoleAssignment>,
)

/**
 * "Consolidate Elder, Coordinator Elder, Service Overseer and Secretary
 * Enrollment" — the single centralized module replacing the old separate
 * Coordinator Elder and Service Overseer manage/enroll screens (Regular
 * Elder's own module is renamed, not replaced — same underlying
 * `roleAssignments` collection every one of those screens already shared, so
 * no data migration is actually needed: every existing Coordinator
 * Elder/Service Overseer/Regular Elder record already appears here as-is).
 */
class ManageEldersViewModel(
    private val personRepository: PersonRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val groupRepository: GroupRepository,
    private val congregationRepository: CongregationRepository,
    private val auditLogRepository: AuditLogRepository,
    private val recycleBinRepository: com.emfitsolutions.gopreach.data.repository.RecycleBinRepository,
) : ViewModel() {

    /** "For Super Admin: Congregation: [All Congregations]" — the filter
     * dropdown's own option list; [rowsFor] already filters by
     * `assignment.congregationId` directly, this is just the name list to
     * pick from (same convention every other Manage screen here uses). */
    val congregations: StateFlow<List<Congregation>> = congregationRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun rowsFor(visibleCongregationId: String?): Flow<List<EldersRow>> = combine(
        personRepository.observeAll(),
        roleAssignmentRepository.observeAll(),
        groupRepository.observeAll(),
        congregationRepository.observeAll(),
    ) { people, allAssignments, groups, congregations ->
        allAssignments
            .filter { (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role in ELDER_PRIMARY_ROLES }
            .groupBy { it.personId }
            .mapNotNull { (personId, primaryAssignments) ->
                val person = people.firstOrNull { it.id == personId } ?: return@mapNotNull null
                // A person's several primary assignments should all share one
                // congregation in practice (nothing in this module ever lets
                // them diverge); the first ACTIVE one (or just the first, if
                // none currently are) is what every downstream computation
                // below treats as "the" congregation for this row.
                val congregationId = primaryAssignments.firstOrNull { it.status == RoleAssignmentStatus.ACTIVE }?.congregationId
                    ?: primaryAssignments.firstOrNull()?.congregationId
                    ?: return@mapNotNull null
                if (visibleCongregationId != null && congregationId != visibleCongregationId) return@mapNotNull null

                val personAssignments = allAssignments.filter { it.personId == personId }
                val activePrimary = primaryAssignments.filter { it.status == RoleAssignmentStatus.ACTIVE }
                val regularElderRole = personAssignments.firstOrNull {
                    it.status == RoleAssignmentStatus.ACTIVE &&
                        (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role == AdminRole.REGULAR_ELDER
                }?.regularElderRole
                val publisherCategory = personAssignments
                    .firstOrNull { it.status == RoleAssignmentStatus.ACTIVE && it.resolvedRoleTypeOrNull() is RoleType.Publisher }
                    ?.let { (it.resolvedRoleTypeOrNull() as RoleType.Publisher).category }

                EldersRow(
                    person = person,
                    congregationId = congregationId,
                    congregationName = congregations.firstOrNull { it.id == congregationId }?.name ?: "Unassigned",
                    adminRoles = activePrimary.mapNotNull { (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role }.toSet()
                        .ifEmpty { primaryAssignments.mapNotNull { (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role }.toSet() },
                    regularElderRole = regularElderRole,
                    publisherCategory = publisherCategory,
                    isActive = activePrimary.isNotEmpty(),
                    primaryAssignments = primaryAssignments,
                )
            }
            .sortedBy { it.person.fullName }
    }

    fun updatePerson(person: Person) {
        viewModelScope.launch { personRepository.save(person) }
    }

    /** "Deleting an Elders record must follow the existing GoPreach delete
     * rules" — moving to Inactive deactivates every one of this person's
     * primary role assignments at once (their whole Elder standing), the
     * same "Delete = deactivate" semantics every other Manage screen here
     * already uses for its one role; re-activating restores all of them. It
     * deliberately does not touch the separate Group-role/Publisher-category
     * assignments — an inactive Elder keeps whatever those already were,
     * consistent with "Do not automatically delete historical
     * reports/logs/... unless existing GoPreach rules explicitly require
     * it." */
    fun setActive(row: EldersRow, active: Boolean, actorPersonId: String) {
        viewModelScope.launch {
            val newStatus = if (active) RoleAssignmentStatus.ACTIVE else RoleAssignmentStatus.INACTIVE
            row.primaryAssignments.forEach { assignment ->
                if (assignment.status != newStatus) roleAssignmentRepository.save(assignment.copy(status = newStatus))
            }
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "CHANGE_ELDER_STATUS",
                targetType = "Person",
                targetId = row.person.id,
                congregationId = row.congregationId,
                details = "status: $newStatus, roles: ${row.adminRoles.joinToString()}",
            )
        }
    }

    /** Saves the Person fields, an optional congregation reassignment, and
     * reconciles every checkbox in [selectedAdminRoles]/[regularElderRole]/
     * [publisherCategory] against whatever this person currently holds —
     * adding a newly-checked role, removing an unchecked one, and updating
     * the congregation on every assignment that survives. "Role changes must
     * propagate" (spec §20) — the very next read of `roleAssignments`
     * reflects exactly this new set, which is the same source every
     * permission check/login role list in the app already reads from; there
     * is no separate, staler role record anywhere to fall out of sync.
     *
     * [newCongregationId] is only ever different from [EldersRow.congregationId]
     * when the caller is authorized to change it (Super-Admin only — the
     * calling screen fixes it for everyone else, same convention as every
     * other Manage screen's Edit dialog).
     */
    fun updateRolesAndPerson(
        row: EldersRow,
        updatedPerson: Person,
        newCongregationId: String,
        selectedAdminRoles: Set<AdminRole>,
        regularElderRole: RegularElderRole?,
        publisherCategory: PublisherCategory?,
        actorPersonId: String,
    ) {
        viewModelScope.launch {
            personRepository.save(updatedPerson)
            val now = System.currentTimeMillis()

            // Reconcile the primary admin-role checkboxes: an existing
            // assignment for a role that's still checked gets its
            // congregation refreshed; a newly-checked role gets a brand-new
            // assignment; an unchecked-but-previously-held role is removed
            // outright (spec §19: "changing a person's roles must update the
            // existing role assignment used by the rest of GoPreach" — there
            // is no soft "disabled" state for a primary role beyond this;
            // [setActive] above is the separate whole-record deactivation).
            for (role in ELDER_PRIMARY_ROLES) {
                val existing = row.primaryAssignments.firstOrNull { (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role == role }
                when {
                    role in selectedAdminRoles && existing == null -> roleAssignmentRepository.save(
                        RoleAssignment(
                            personId = row.person.id,
                            roleType = RoleType.serialize(RoleType.Admin(role)),
                            congregationId = newCongregationId,
                            // A Regular-Elder-primary assignment carries the
                            // Group role directly, same as enrollment does —
                            // never a *second*, redundant REGULAR_ELDER
                            // assignment for the same person.
                            regularElderRole = if (role == AdminRole.REGULAR_ELDER) regularElderRole else null,
                            status = RoleAssignmentStatus.ACTIVE,
                            dateAssigned = now,
                            assignedByPersonId = actorPersonId,
                        ),
                    )
                    role in selectedAdminRoles && existing != null -> {
                        val newRegularElderRole = if (role == AdminRole.REGULAR_ELDER) regularElderRole else existing.regularElderRole
                        if (existing.congregationId != newCongregationId || existing.status != RoleAssignmentStatus.ACTIVE ||
                            existing.regularElderRole != newRegularElderRole
                        ) {
                            roleAssignmentRepository.save(
                                existing.copy(
                                    congregationId = newCongregationId,
                                    status = RoleAssignmentStatus.ACTIVE,
                                    regularElderRole = newRegularElderRole,
                                    lastEditedByPersonId = actorPersonId,
                                    lastEditedAt = now,
                                ),
                            )
                        }
                    }
                    role !in selectedAdminRoles && existing != null -> roleAssignmentRepository.delete(existing.id)
                }
            }

            // Group Overseer/Assistant checked without Regular Elder itself
            // checked — the same "additional, simultaneous REGULAR_ELDER
            // RoleAssignment" pattern Coordinator/Service Overseer/Secretary
            // enrollment already uses (see EldersEnrollmentViewModel), rather
            // than folding it into a role this person isn't actually meant
            // to hold as their primary one.
            if (AdminRole.REGULAR_ELDER !in selectedAdminRoles) {
                val existingAssignments = roleAssignmentRepository.observeForPerson(row.person.id).first()
                val existingExtraRegularElder = existingAssignments.firstOrNull {
                    (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role == AdminRole.REGULAR_ELDER
                }
                when {
                    regularElderRole != null && existingExtraRegularElder == null -> roleAssignmentRepository.save(
                        RoleAssignment(
                            personId = row.person.id,
                            roleType = RoleType.serialize(RoleType.Admin(AdminRole.REGULAR_ELDER)),
                            congregationId = newCongregationId,
                            regularElderRole = regularElderRole,
                            status = RoleAssignmentStatus.ACTIVE,
                            dateAssigned = now,
                            assignedByPersonId = actorPersonId,
                        ),
                    )
                    regularElderRole != null && existingExtraRegularElder != null -> roleAssignmentRepository.save(
                        existingExtraRegularElder.copy(
                            congregationId = newCongregationId,
                            regularElderRole = regularElderRole,
                            status = RoleAssignmentStatus.ACTIVE,
                            lastEditedByPersonId = actorPersonId,
                            lastEditedAt = now,
                        ),
                    )
                    regularElderRole == null && existingExtraRegularElder != null -> roleAssignmentRepository.delete(existingExtraRegularElder.id)
                }
            }

            val existingAssignments = roleAssignmentRepository.observeForPerson(row.person.id).first()
            val existingPublisher = existingAssignments.firstOrNull {
                it.status == RoleAssignmentStatus.ACTIVE && it.resolvedRoleTypeOrNull() is RoleType.Publisher
            }
            when {
                publisherCategory != null && existingPublisher == null -> roleAssignmentRepository.save(
                    RoleAssignment(
                        personId = row.person.id,
                        roleType = RoleType.serialize(RoleType.Publisher(publisherCategory)),
                        congregationId = newCongregationId,
                        status = RoleAssignmentStatus.ACTIVE,
                        dateAssigned = now,
                        assignedByPersonId = actorPersonId,
                    ),
                )
                publisherCategory != null && existingPublisher != null -> roleAssignmentRepository.save(
                    existingPublisher.copy(
                        roleType = RoleType.serialize(RoleType.Publisher(publisherCategory)),
                        congregationId = newCongregationId,
                        lastEditedByPersonId = actorPersonId,
                        lastEditedAt = now,
                    ),
                )
                publisherCategory == null && existingPublisher != null -> roleAssignmentRepository.delete(existingPublisher.id)
            }

            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "EDIT_ELDER",
                targetType = "Person",
                targetId = row.person.id,
                congregationId = newCongregationId,
                details = "roles: ${selectedAdminRoles.joinToString()}, groupRole: $regularElderRole, publisherCategory: $publisherCategory",
            )
        }
    }

    /** Super-Admin/Admin-only permanent delete (see BUILD_PLAN.md scoping).
     * Clears this Elder out of any Group role slot they still occupy first
     * (spec §4: Elder -> Group Assignment), then deletes every primary
     * role/Group-role/Publisher-category assignment, and the Person doc too
     * only if no other RoleAssignment remains for them (spec §29: "do not
     * create orphaned records"). */
    fun permanentlyDelete(row: EldersRow, actorPersonId: String) {
        viewModelScope.launch {
            val personId = row.person.id
            val groupsHeldBefore = groupRepository.observeAll().first()
                .filter { it.overseerPersonId == personId || it.servantPersonId == personId || it.assistantPersonId == personId || it.regularElderPersonId == personId }
            val allAssignmentsBefore = roleAssignmentRepository.observeForPerson(personId).first()
            val assignmentsToTrash = allAssignmentsBefore.filter {
                (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role in ELDER_PRIMARY_ROLES ||
                    (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role == AdminRole.REGULAR_ELDER
            }
            val othersLeft = roleAssignmentRepository.observeAll().first().count { it.personId == personId && it.id !in assignmentsToTrash.map { a -> a.id } }
            // Kept whole in Deleted Records first: the Elder's assignments, the Person (if nothing else remains) and
            // the Group slots they held (put back on restore only if still empty).
            recycleBinRepository.moveToTrash(
                recordType = "Elder",
                module = "Elders",
                label = row.person.fullName,
                congregationId = row.congregationId,
                originalCreatedAt = row.person.createdAt,
                deletedByPersonId = actorPersonId,
                items = buildList {
                    assignmentsToTrash.forEach { add(recycleBinRepository.item("roleAssignments", it.id, it)) }
                    if (othersLeft == 0) add(recycleBinRepository.item("people", personId, row.person))
                    groupsHeldBefore.forEach { g ->
                        if (g.overseerPersonId == personId) add(recycleBinRepository.relationshipItem("groups", g.id, g, "overseerPersonId"))
                        if (g.servantPersonId == personId) add(recycleBinRepository.relationshipItem("groups", g.id, g, "servantPersonId"))
                        if (g.assistantPersonId == personId) add(recycleBinRepository.relationshipItem("groups", g.id, g, "assistantPersonId"))
                        if (g.regularElderPersonId == personId) add(recycleBinRepository.relationshipItem("groups", g.id, g, "regularElderPersonId"))
                    }
                },
            )
            groupsHeldBefore.forEach { group ->
                    groupRepository.save(
                        group.copy(
                            overseerPersonId = if (group.overseerPersonId == personId) null else group.overseerPersonId,
                            servantPersonId = if (group.servantPersonId == personId) null else group.servantPersonId,
                            assistantPersonId = if (group.assistantPersonId == personId) null else group.assistantPersonId,
                            regularElderPersonId = if (group.regularElderPersonId == personId) null else group.regularElderPersonId,
                        ),
                    )
                }
            val allAssignments = roleAssignmentRepository.observeForPerson(personId).first()
            val toDelete = allAssignments.filter {
                (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role in ELDER_PRIMARY_ROLES ||
                    (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role == AdminRole.REGULAR_ELDER
            }
            toDelete.forEach { roleAssignmentRepository.delete(it.id) }
            val remaining = roleAssignmentRepository.observeAll().first().count { it.personId == personId }
            if (remaining == 0) personRepository.delete(personId)
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "PERMANENT_DELETE_ELDER",
                targetType = "Person",
                targetId = personId,
                congregationId = row.congregationId,
            )
        }
    }
}

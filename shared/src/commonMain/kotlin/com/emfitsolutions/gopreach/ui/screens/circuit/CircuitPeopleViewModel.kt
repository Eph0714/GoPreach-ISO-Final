package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.model.ScopeType
import com.emfitsolutions.gopreach.data.model.UserAccessGrant
import com.emfitsolutions.gopreach.data.model.displayLabel
import com.emfitsolutions.gopreach.data.model.displayName
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.GroupRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.UserAccessGrantRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** The four pioneer/publisher categories the CO Quick Access tiles count. */
val CIRCUIT_QUICK_CATEGORIES = listOf(
    PublisherCategory.REGULAR_PIONEER,
    PublisherCategory.AUXILIARY_PIONEER,
    PublisherCategory.UNBAPTIZED_PUBLISHER,
    PublisherCategory.SPECIAL_PIONEER,
)

data class QuickAccessCounts(val counts: Map<PublisherCategory, Int>) {
    operator fun get(category: PublisherCategory): Int = counts[category] ?: 0
}

/** One person on a Circuit Overseer list (publisher, elder or ministerial servant). */
data class CircuitPersonRow(
    val personId: String,
    val name: String,
    val congregationId: String,
    val congregationName: String,
    val groupId: String?,
    val groupName: String,
    /** Publisher category; null for an Elder / Ministerial Servant row. */
    val category: PublisherCategory?,
    /** "Coordinator Elder", "Ministerial Servant", ... — for Elder / Ministerial Servant rows. */
    val roleLabel: String?,
    val status: String,
    val contact: String,
    /** The full Person record (publisher rows) — every profile field the Circuit Overseer may see. */
    val person: Person? = null,
) {
    val categoryLabel: String get() = category?.displayName ?: roleLabel.orEmpty()
}

/** One congregation's people totals, for the Circuit Overseer's Congregation List. */
data class CongregationPeopleSummary(
    val congregation: Congregation,
    val publishers: Int,
    val counts: QuickAccessCounts,
    val elders: Int,
    val servants: Int,
    /** Plain publishers (category "Publisher") — the Circuit Report's "Regular Publishers" column. */
    val regularPublishers: Int = 0,
    /** Active Coordinator Elder(s), "—" if none. */
    val coordinator: String = "—",
)

private val ELDER_ROLES = setOf(AdminRole.COORDINATOR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY, AdminRole.REGULAR_ELDER)

/**
 * Read model behind the Circuit Overseer's Quick Access, Congregation List, publisher list and Elders/Ministerial
 * Servants lists. Everything is computed from the existing Person / RoleAssignment / Group / Congregation records —
 * no second copy of any figure. The set of congregations always comes from the signed-in overseer's own grant (what
 * the security rules let this account read); [congregationId] only narrows within it and is ignored if it falls outside.
 */
class CircuitPeopleViewModel(
    private val grantRepository: UserAccessGrantRepository,
    congregationRepository: CongregationRepository,
    roleAssignmentRepository: RoleAssignmentRepository,
    private val personRepository: PersonRepository,
    groupRepository: GroupRepository,
    private val serverSyncClock: com.emfitsolutions.gopreach.data.sync.ServerSyncClock,
    connectivityObserver: com.emfitsolutions.gopreach.data.sync.ConnectivityObserver,
) : ViewModel() {

    /** When this device last received data from the server (0 = never). */
    val lastSyncAt: kotlinx.coroutines.flow.StateFlow<Long> = serverSyncClock.lastSyncAt
    val isOnline: Flow<Boolean> = connectivityObserver.observe()

    /** Circuit code and overseer name for the Circuit Report header. */
    fun circuitInfo(personId: String): Flow<Pair<String, String>> =
        combine(grantRepository.observeCircuitScope(personId), personRepository.observeAll()) { grant, people ->
            if (personId == CIRCUIT_SCOPE_ALL) "All Circuits" to "—"
            else (grant?.circuitCode ?: "—") to (people.firstOrNull { it.id == personId }?.fullName ?: "—")
        }

    private data class Snapshot(
        val congregations: List<Congregation>,
        val assignments: List<RoleAssignment>,
        val people: Map<String, Person>,
        val groups: Map<String, Group>,
    )

    private val snapshot: Flow<Snapshot> = combine(
        congregationRepository.observeAll(),
        roleAssignmentRepository.observeAll(),
        personRepository.observeAll(),
        groupRepository.observeAll(),
    ) { congregations, assignments, people, groups ->
        Snapshot(congregations, assignments, people.associateBy { it.id }, groups.associateBy { it.id })
    }

    private fun allowed(snapshot: Snapshot, grant: UserAccessGrant?): List<Congregation> {
        val active = snapshot.congregations.filter { it.status == RecordStatus.ACTIVE }
        return when {
            grant == null -> emptyList()
            grant.resolvedScopeType == ScopeType.ALL_CONGREGATIONS -> active
            else -> active.filter { it.id in grant.scopeCongregationIds }
        }.sortedBy { it.name }
    }

    private fun scoped(personId: String, congregationId: String?): Flow<Pair<Snapshot, List<Congregation>>> =
        combine(snapshot, grantRepository.observeCircuitScope(personId)) { s, grant ->
            val inScope = allowed(s, grant)
            s to (if (congregationId == null) inScope else inScope.filter { it.id == congregationId })
        }

    /** Congregations this overseer may see (for the filter dropdown). */
    fun congregations(personId: String): Flow<List<Congregation>> = scoped(personId, null).map { it.second }

    private fun publisherRows(s: Snapshot, congregations: List<Congregation>): List<CircuitPersonRow> {
        val names = congregations.associate { it.id to it.name }
        return s.assignments
            .filter { it.status == RoleAssignmentStatus.ACTIVE && it.congregationId in names }
            .mapNotNull { a ->
                val category = (a.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.category ?: return@mapNotNull null
                if (category == PublisherCategory.REMOVED_PUBLISHER) return@mapNotNull null
                val person = s.people[a.personId] ?: return@mapNotNull null
                CircuitPersonRow(
                    personId = person.id, name = personName(person),
                    congregationId = a.congregationId!!, congregationName = names.getValue(a.congregationId),
                    groupId = a.groupId, groupName = a.groupId?.let { s.groups[it]?.name }.orEmpty(),
                    category = category, roleLabel = null,
                    status = person.accountStatus.name, contact = person.contact,
                    person = person,
                )
            }
            .distinctBy { it.personId to it.congregationId }
    }

    private fun personName(p: Person) = p.lastName.trim() + ", " + p.firstName.trim()

    /** Heading data (name, Coordinator Elder(s), Congregation Code) for every congregation in scope. */
    fun headings(personId: String): Flow<List<CongregationHeading>> = scoped(personId, null).map { (s, cs) ->
        cs.map { c ->
            val coordinators = s.assignments
                .filter { it.congregationId == c.id && it.status == RoleAssignmentStatus.ACTIVE }
                .filter { (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role == AdminRole.COORDINATOR_ELDER }
                .mapNotNull { s.people[it.personId]?.fullName }
                .distinct()
            CongregationHeading(c.id, c.name, c.code, coordinators.joinToString(", ").ifBlank { "—" })
        }
    }

    /** "Circuit NT01 — Juan Dela Cruz", or "All Circuits". */
    fun circuitLabel(personId: String): Flow<String> =
        combine(grantRepository.observeCircuitScope(personId), personRepository.observeAll()) { grant, people ->
            if (personId == CIRCUIT_SCOPE_ALL) "All Circuits"
            else "Circuit ${grant?.circuitCode ?: "—"} — ${people.firstOrNull { it.id == personId }?.fullName.orEmpty()}"
        }

    /** Totals for the four Quick Access categories; [congregationId] null = every congregation in the circuit. */
    fun counts(personId: String, congregationId: String?): Flow<QuickAccessCounts> =
        scoped(personId, congregationId).map { (s, cs) ->
            val rows = publisherRows(s, cs)
            QuickAccessCounts(CIRCUIT_QUICK_CATEGORIES.associateWith { c -> rows.count { it.category == c } })
        }

    /** Publishers in scope, optionally one [category]; sorted by name. */
    fun publishers(personId: String, congregationId: String?, category: PublisherCategory? = null): Flow<List<CircuitPersonRow>> =
        scoped(personId, congregationId).map { (s, cs) ->
            publisherRows(s, cs).filter { category == null || it.category == category }
                .sortedWith(compareBy({ it.name.lowercase() }, { it.congregationName }))
        }

    /** Elders and Ministerial Servants in scope. */
    fun leaders(personId: String, congregationId: String?): Flow<Pair<List<CircuitPersonRow>, List<CircuitPersonRow>>> =
        scoped(personId, congregationId).map { (s, cs) ->
            val names = cs.associate { it.id to it.name }
            val rows = s.assignments
                .filter { it.status == RoleAssignmentStatus.ACTIVE && it.congregationId in names }
                .mapNotNull { a ->
                    val role = (a.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role ?: return@mapNotNull null
                    if (role !in ELDER_ROLES && role != AdminRole.MINISTERIAL_SERVANT) return@mapNotNull null
                    val person = s.people[a.personId] ?: return@mapNotNull null
                    role to CircuitPersonRow(
                        personId = person.id, name = personName(person),
                        congregationId = a.congregationId!!, congregationName = names.getValue(a.congregationId),
                        groupId = a.groupId, groupName = a.groupId?.let { s.groups[it]?.name }.orEmpty(),
                        category = null, roleLabel = role.displayLabel(),
                        status = person.accountStatus.name, contact = person.contact,
                    )
                }
                .sortedBy { it.second.name.lowercase() }
            rows.filter { it.first in ELDER_ROLES }.map { it.second } to rows.filter { it.first == AdminRole.MINISTERIAL_SERVANT }.map { it.second }
        }

    /** One summary line per congregation in scope. */
    fun summaries(personId: String): Flow<List<CongregationPeopleSummary>> =
        scoped(personId, null).map { (s, cs) ->
            val pubs = publisherRows(s, cs)
            cs.map { c ->
                val mine = pubs.filter { it.congregationId == c.id }
                val leaderRoles = s.assignments
                    .filter { it.status == RoleAssignmentStatus.ACTIVE && it.congregationId == c.id }
                    .mapNotNull { (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role?.let { r -> r to it.personId } }
                CongregationPeopleSummary(
                    congregation = c,
                    publishers = mine.size,
                    counts = QuickAccessCounts(CIRCUIT_QUICK_CATEGORIES.associateWith { cat -> mine.count { it.category == cat } }),
                    elders = leaderRoles.filter { it.first in ELDER_ROLES }.map { it.second }.toSet().size,
                    servants = leaderRoles.filter { it.first == AdminRole.MINISTERIAL_SERVANT }.map { it.second }.toSet().size,
                    regularPublishers = mine.count { it.category == PublisherCategory.REGULAR_PUBLISHER },
                    coordinator = leaderRoles.filter { it.first == AdminRole.COORDINATOR_ELDER }.map { it.second }.toSet()
                        .mapNotNull { s.people[it]?.fullName }.joinToString(", ").ifBlank { "—" },
                )
            }
        }
}

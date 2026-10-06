package com.emfitsolutions.gopreach.ui.screens.contactrecord

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One entry in the consolidated directory — a Person-backed role (Publisher/
 * Coordinator Elder/Service Overseer/Ministerial Servant) or an Interested
 * Person at whichever pipeline stage they're currently at. [sourceLabels] can
 * hold more than one entry for the same real person (e.g. a Coordinator Elder
 * who is also a Regular Pioneer) — same "merge, don't duplicate the row"
 * reasoning [com.emfitsolutions.gopreach.ui.screens.dashboard
 * .computeStatMembers] already uses for the dashboard's own drill-downs. An
 * Interested Person always gets its own row: they have no Person doc to merge
 * against, and moving through Searching → Return Visit → Bible Study is a
 * single record changing stage, not three. */
data class ContactRow(
    val name: String,
    val sourceLabels: Set<String>,
    /** Phone number for a Person-backed row; blank for an Interested Person
     * (this app's [com.emfitsolutions.gopreach.data.model.InterestedPerson]
     * has no phone field — see its own doc comment — so [address] is the
     * only contact detail available for that source, and Call/Message stay
     * unavailable for these rows). */
    val contact: String,
    val address: String,
    /** What [address] means for this row: a Person-backed row has a plain
     * address, while an Interested Person row stores its Place of Origin there. */
    val addressLabel: String = "Address",
    val congregationId: String,
    val congregationName: String,
    val profileImageUrl: String? = null,
)

/** "Add the exact role filters" — the dropdown's own fixed option list;
 * kept separate from whatever labels a [ContactRow] can actually carry
 * (broader — Ministerial Servant and the pipeline stages still show up
 * under "All," just without their own dedicated filter chip, since spec's
 * own filter list doesn't mention them). */
val CONTACT_ROLE_FILTERS = listOf(REGULAR_PUBLISHER, REGULAR_PIONEER, SPECIAL_PIONEER, AUXILIARY_PIONEER, SERVICE_OVERSEER, SECRETARY, CONGREGATION_ELDER, REGULAR_ELDER)

const val REGULAR_PUBLISHER = "Publisher"
const val REGULAR_PIONEER = "Regular Pioneer"
/** "Keep their category values distinct so the system can correctly
 * display and report REGULAR PIONEER versus SPECIAL PIONEER" — its own
 * filter chip here, not folded into [REGULAR_PIONEER]'s, even though the
 * two behave identically everywhere eligibility/permissions are checked
 * (see [PublisherCategory.SPECIAL_PIONEER]'s own doc comment). */
const val SPECIAL_PIONEER = "Special Pioneer"
const val AUXILIARY_PIONEER = "Auxiliary Pioneer"
const val UNBAPTIZED_PUBLISHER = "Unbaptized Publisher"
const val IRREGULAR_PUBLISHER = "Irregular Publisher"
const val INACTIVE_PUBLISHER = "Inactive Publisher"
const val REPROOF_PUBLISHER = "Reproof Publisher"
const val SEARCHING = "Searching"
const val RETURN_VISIT = "Return Visit"
const val BIBLE_STUDY = "Bible Study"
/** "Congregation Elder" per this feature's own naming — same
 * [AdminRole.COORDINATOR_ELDER] this app calls "Coordinator Elder"
 * everywhere else; only this module's own display label changed; the
 * underlying role/permissions are untouched. */
const val CONGREGATION_ELDER = "Congregation Elder"
const val SERVICE_OVERSEER = "Service Overseer"
/** "Secretary must have the same access level and permissions as Service
 * Overseer" — same reasoning as [CONGREGATION_ELDER]: only the display label
 * differs, the underlying [AdminRole.SECRETARY] carries Service-Overseer-
 * equivalent access everywhere else in the app. */
const val SECRETARY = "Secretary"
const val REGULAR_ELDER = "Regular Elder"
const val MINISTERIAL_SERVANT = "Ministerial Servant"

/** "Contact Record" module — one consolidated directory of every Publisher,
 * Interested Person (Searching/Return Visit/Bible Study), Coordinator Elder,
 * Service Overseer, and Ministerial Servant's contact details, in one place
 * instead of hunting through five separate Manage screens. "Super Admin can
 * see all congregation Contact Records. Admin, Congregation Elder, Regular
 * Elder, and Service Overseer can only see Contact Records from their own
 * congregation" — visible to Super-Admin, Admin, Coordinator Elder
 * ("Congregation Elder" in this module), Service Overseer, and Regular
 * Elder (wired from GoPreachNavGraph/AdminHomeScreen's canViewContactRecord),
 * congregation-scoped for everyone but Super-Admin via the same
 * `visibleCongregationId == null` convention every other Manage screen here
 * already uses — enforced here, in the query itself, not just by hiding rows
 * in the UI: a caller passing a non-null `visibleCongregationId` can never
 * get back a row from a different congregation no matter what the screen
 * does with the result.
 */
class ContactRecordViewModel(
    private val personRepository: PersonRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val interestedPersonRepository: InterestedPersonRepository,
    private val congregationRepository: CongregationRepository,
) : ViewModel() {

    /** For the "By Congregation" filter — Super-Admin only in practice
     * (everyone else's rows are already fixed to their own single
     * congregation upstream, same `visibleCongregationId` scoping every
     * other Manage screen here uses). */
    val congregations: StateFlow<List<Congregation>> =
        congregationRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun rowsFor(visibleCongregationId: String?): Flow<List<ContactRow>> = combine(
        personRepository.observeAll(),
        roleAssignmentRepository.observeAll(),
        interestedPersonRepository.observeAll(),
        congregationRepository.observeAll(),
    ) { people, assignments, interestedPeople, congregations ->
        val peopleById = people.associateBy { it.id }
        val congregationNameById = congregations.associateBy({ it.id }, { it.name })
        fun congregationNameFor(congregationId: String?) = congregationNameById[congregationId] ?: "Unassigned"

        data class PersonKey(val personId: String, val congregationId: String)
        val labelsByPerson = mutableMapOf<PersonKey, MutableSet<String>>()

        assignments
            .filter { it.status == RoleAssignmentStatus.ACTIVE && it.personId in peopleById }
            .forEach { assignment ->
                val congregationId = assignment.congregationId ?: return@forEach
                // "The role/category shown in the Contact Record must come
                // from the actual user's existing GoPreach role/status data"
                // — the real [PublisherCategory] (Regular Publisher, Regular
                // Pioneer, ...) and Admin role, not a generic "Publisher"
                // bucket a person's real category used to collapse into.
                val label = when (val role = assignment.resolvedRoleTypeOrNull()) {
                    is RoleType.Admin -> when (role.role) {
                        AdminRole.COORDINATOR_ELDER -> CONGREGATION_ELDER
                        AdminRole.SERVICE_OVERSEER -> SERVICE_OVERSEER
                        AdminRole.SECRETARY -> SECRETARY
                        AdminRole.REGULAR_ELDER -> REGULAR_ELDER
                        AdminRole.MINISTERIAL_SERVANT -> MINISTERIAL_SERVANT
                        else -> null
                    }
                    is RoleType.Publisher -> when (role.category) {
                        PublisherCategory.REGULAR_PUBLISHER -> REGULAR_PUBLISHER
                        PublisherCategory.REGULAR_PIONEER -> REGULAR_PIONEER
                        PublisherCategory.SPECIAL_PIONEER -> SPECIAL_PIONEER
                        PublisherCategory.AUXILIARY_PIONEER -> AUXILIARY_PIONEER
                        PublisherCategory.UNBAPTIZED_PUBLISHER -> UNBAPTIZED_PUBLISHER
                        PublisherCategory.IRREGULAR_PUBLISHER -> IRREGULAR_PUBLISHER
                        PublisherCategory.INACTIVE_PUBLISHER -> INACTIVE_PUBLISHER
                        PublisherCategory.REPROOF_PUBLISHER -> REPROOF_PUBLISHER
                        PublisherCategory.REMOVED_PUBLISHER -> null
                    }
                    null -> null
                } ?: return@forEach
                val key = PersonKey(assignment.personId, congregationId)
                labelsByPerson.getOrPut(key) { mutableSetOf() }.add(label)
            }

        val personRows = labelsByPerson.mapNotNull { (key, labels) ->
            if (visibleCongregationId != null && key.congregationId != visibleCongregationId) return@mapNotNull null
            val person = peopleById[key.personId] ?: return@mapNotNull null
            ContactRow(
                name = person.fullName,
                sourceLabels = labels,
                contact = person.contact,
                address = person.address,
                congregationId = key.congregationId,
                congregationName = congregationNameFor(key.congregationId),
                profileImageUrl = person.profileImageUrl,
            )
        }

        val interestedPersonRows = interestedPeople
            .filter { it.status == RecordStatus.ACTIVE }
            .filter { visibleCongregationId == null || it.congregationId == visibleCongregationId }
            .map { interestedPerson ->
                val label = when (interestedPerson.pipelineStage) {
                    PipelineStage.SEARCHING -> SEARCHING
                    PipelineStage.RETURN_VISIT -> RETURN_VISIT
                    PipelineStage.BIBLE_STUDY -> BIBLE_STUDY
                }
                ContactRow(
                    name = interestedPerson.name,
                    sourceLabels = setOf(label),
                    contact = "",
                    address = interestedPerson.address,
                    addressLabel = "Place of Origin",
                    congregationId = interestedPerson.congregationId,
                    congregationName = congregationNameFor(interestedPerson.congregationId),
                )
            }

        (personRows + interestedPersonRows).sortedBy { it.name }
    }
}

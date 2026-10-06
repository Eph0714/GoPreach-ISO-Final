package com.emfitsolutions.gopreach.ui.screens.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.GroupRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** One publisher line under a Field Service Group's three named roles —
 * "-Ephraim Fernandez (Auxiliary Pioneer)" in the spec's own example. */
data class FieldServiceGroupMember(val fullName: String, val category: PublisherCategory)

/** One Field Service Group's full report block. [congregationName] is only
 * ever shown by the screen when more than one congregation is in scope
 * (Super-Admin) — repeating a Publisher/Admin's own single congregation's
 * name above every group would just be noise. */
data class FieldServiceGroupReportRow(
    val congregationId: String,
    val congregationName: String,
    val group: Group,
    val overseerName: String?,
    val servantName: String?,
    val assistantName: String?,
    val members: List<FieldServiceGroupMember>,
)

/**
 * "Add a module for 'Field Service Group' Report. For Super admin, he can
 * see all congregation, other user can see only the record of their
 * congregation" — [congregationIds] is `null` only for Super-Admin (see
 * [com.emfitsolutions.gopreach.ui.screens.home.AdminHomeScreen]'s own
 * `visibleCongregationIds` convention, reused as-is here); every other role
 * this report is offered to (Admin/Coordinator Elder/Regular Elder/Service
 * Overseer) is scoped to exactly their own congregation's set.
 */
@HiltViewModel
class FieldServiceGroupReportViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
    private val congregationRepository: CongregationRepository,
    private val personRepository: PersonRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
) : ViewModel() {

    /** "Add a filter for Congregation" (Super-Admin only) — the dropdown's
     * own option list. */
    val congregations: StateFlow<List<Congregation>> = congregationRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun rowsFor(congregationIds: Set<String>?): Flow<List<FieldServiceGroupReportRow>> =
        combine(
            congregationRepository.observeAll(),
            groupRepository.observeAll(),
            personRepository.observeAll(),
            roleAssignmentRepository.observeAll(),
        ) { congregations, groups, people, assignments ->
            val visibleCongregations = congregations
                .filter { it.status == RecordStatus.ACTIVE && (congregationIds == null || it.id in congregationIds) }
                .sortedBy { it.name }

            visibleCongregations.flatMap { congregation ->
                groups
                    .filter { it.congregationId == congregation.id && it.status == RecordStatus.ACTIVE }
                    .sortedWith(com.emfitsolutions.gopreach.domain.GroupNameOrder)
                    .map { group ->
                        val members = assignments
                            .filter { assignment ->
                                assignment.status == RoleAssignmentStatus.ACTIVE &&
                                    assignment.groupId == group.id &&
                                    assignment.resolvedRoleTypeOrNull() is RoleType.Publisher
                            }
                            .mapNotNull { assignment ->
                                val person = people.firstOrNull { it.id == assignment.personId } ?: return@mapNotNull null
                                val category = (assignment.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.category ?: return@mapNotNull null
                                FieldServiceGroupMember(person.fullName, category)
                            }
                            .sortedBy { it.fullName }
                        FieldServiceGroupReportRow(
                            congregationId = congregation.id,
                            congregationName = congregation.name,
                            group = group,
                            overseerName = people.nameFor(group.overseerPersonId),
                            servantName = people.nameFor(group.servantPersonId),
                            assistantName = people.nameFor(group.assistantPersonId),
                            members = members,
                        )
                    }
            }
        }

    private fun List<Person>.nameFor(personId: String?): String? = personId?.let { id -> firstOrNull { it.id == id }?.fullName }
}

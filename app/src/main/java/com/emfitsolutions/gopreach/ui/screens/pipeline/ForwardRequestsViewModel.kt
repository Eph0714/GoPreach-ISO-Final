package com.emfitsolutions.gopreach.ui.screens.pipeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.ForwardRequest
import com.emfitsolutions.gopreach.data.model.ForwardRequestStatus
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.ForwardRequestRepository
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Service Overseer's (also Coordinator Elder/Admin/Super-Admin, per
 * the same "who can enroll a Service Overseer" access set) incoming "Forward
 * to Other Congregation" review queue.
 */
@HiltViewModel
class ForwardRequestsViewModel @Inject constructor(
    private val forwardRequestRepository: ForwardRequestRepository,
    private val interestedPersonRepository: InterestedPersonRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val personRepository: PersonRepository,
    private val auditLogRepository: AuditLogRepository,
    congregationRepository: CongregationRepository,
) : ViewModel() {

    /** "Add a filter for Congregation" (Super-Admin only) — the dropdown's
     * own option list. */
    val congregations: StateFlow<List<Congregation>> = congregationRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Pending requests addressed to any of [congregationIds] — `null` means
     * every congregation (Super-Admin). */
    fun pendingRequestsFor(congregationIds: Set<String>?): Flow<List<ForwardRequest>> =
        forwardRequestRepository.observeAll().map { list ->
            list.filter { it.status == ForwardRequestStatus.PENDING && (congregationIds == null || it.toCongregationId in congregationIds) }
                .sortedByDescending { it.requestedAt }
        }

    /** "Include the basic details of the forwarded record, not just the
     * name" — a live lookup of the record itself, keyed off
     * [ForwardRequest.interestedPersonId]. The request only ever snapshots
     * the name (see [ForwardRequest]'s own doc comment on why: so the
     * receiving screen still renders correctly even if the record changes
     * later), so anything beyond the name — stage, address, gender — has to
     * come from here instead, live from the record's current state. `null`
     * while the record isn't loaded yet, or if it was deleted since the
     * request was made. */
    fun personFor(interestedPersonId: String): Flow<InterestedPerson?> =
        interestedPersonRepository.observeAll().map { list -> list.firstOrNull { it.id == interestedPersonId } }

    /** Active, non-removed publishers of [congregationId] — the "ASSIGN TO"
     * dropdown's candidate list (spec: "Do not include (Removed) status"). */
    fun assignablePublishers(congregationId: String): Flow<List<Person>> = combine(
        roleAssignmentRepository.observeAll(),
        personRepository.observeAll(),
    ) { assignments, people ->
        assignments
            .filter { it.status == RoleAssignmentStatus.ACTIVE && it.congregationId == congregationId }
            .mapNotNull { (it.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.let { p -> it to p } }
            .filter { (_, publisher) -> publisher.category != PublisherCategory.REMOVED_PUBLISHER }
            .mapNotNull { (assignment, _) -> people.firstOrNull { it.id == assignment.personId } }
            .distinctBy { it.id }
            .sortedBy { it.fullName }
    }

    fun accept(request: ForwardRequest, assignedTo: Person, actorPersonId: String) {
        viewModelScope.launch {
            val person = interestedPersonRepository.observeAll().first().firstOrNull { it.id == request.interestedPersonId } ?: return@launch
            interestedPersonRepository.save(
                person.copy(congregationId = request.toCongregationId, publisherPersonId = assignedTo.id)
            )
            val now = System.currentTimeMillis()
            forwardRequestRepository.save(
                request.copy(
                    status = ForwardRequestStatus.ACCEPTED,
                    respondedAt = now,
                    respondedByPersonId = actorPersonId,
                    assignedToPublisherPersonId = assignedTo.id,
                    assignedToPublisherNameSnapshot = assignedTo.fullName,
                )
            )
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "ACCEPT_FORWARD_REQUEST",
                targetType = "ForwardRequest",
                targetId = request.id,
                details = "${request.personNameSnapshot} -> ${request.toCongregationNameSnapshot} / ${assignedTo.fullName}",
            )
        }
    }

    fun decline(request: ForwardRequest, actorPersonId: String) {
        viewModelScope.launch {
            forwardRequestRepository.save(
                request.copy(status = ForwardRequestStatus.DECLINED, respondedAt = System.currentTimeMillis(), respondedByPersonId = actorPersonId)
            )
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "DECLINE_FORWARD_REQUEST",
                targetType = "ForwardRequest",
                targetId = request.id,
                details = request.personNameSnapshot,
            )
        }
    }

    /** "Forward Request Module" (Super-Admin only) — every request regardless
     * of status, unlike [pendingRequestsFor] which is the receiving Service
     * Overseer's actionable queue (PENDING only). `congregationIds == null`
     * means every congregation ("All Congregations"); this is the module's
     * own filter, independent of [pendingRequestsFor]'s. */
    fun allRequestsFor(congregationIds: Set<String>?): Flow<List<ForwardRequest>> =
        forwardRequestRepository.observeAll().map { list ->
            list.filter { congregationIds == null || it.toCongregationId in congregationIds }
                .sortedByDescending { it.requestedAt }
        }

    /** "Forward Request Module" (Super-Admin only) — correcting a request's
     * own snapshot fields directly (e.g. a stale/wrong name snapshot, or
     * force-correcting its status) — an administrative fix, not a normal
     * Accept/Decline/Cancel action, so it's logged as its own audit action. */
    fun updateRequest(request: ForwardRequest, actorPersonId: String) {
        viewModelScope.launch {
            forwardRequestRepository.save(request)
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "EDIT_FORWARD_REQUEST",
                targetType = "ForwardRequest",
                targetId = request.id,
                details = "${request.personNameSnapshot} -> ${request.toCongregationNameSnapshot} (${request.status})",
            )
        }
    }

    /** "Forward Request Module" (Super-Admin only) — a hard delete, for
     * cleaning up stale/test/mistaken request records. Never touches the
     * underlying [InterestedPerson] record itself. */
    fun deleteRequest(request: ForwardRequest, actorPersonId: String) {
        viewModelScope.launch {
            forwardRequestRepository.delete(request.id)
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "DELETE_FORWARD_REQUEST",
                targetType = "ForwardRequest",
                targetId = request.id,
                details = "${request.personNameSnapshot} -> ${request.toCongregationNameSnapshot}",
            )
        }
    }
}

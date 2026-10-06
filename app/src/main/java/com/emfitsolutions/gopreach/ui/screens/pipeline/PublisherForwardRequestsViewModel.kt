package com.emfitsolutions.gopreach.ui.screens.pipeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.ForwardRequestStatus
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.PublisherForwardRequest
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.PublisherForwardRequestRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "FORWARD TO OTHER PUBLISHER" spec flow — backs the *receiving* publisher's
 * incoming queue (accept/decline, no assignment step — the sender already
 * picked exactly who should get it) and the notification/status flows both
 * the sending and receiving publisher's Home screen watch (see
 * [com.emfitsolutions.gopreach.ui.screens.home.PublisherHomeScreen]).
 */
@HiltViewModel
class PublisherForwardRequestsViewModel @Inject constructor(
    private val publisherForwardRequestRepository: PublisherForwardRequestRepository,
    private val interestedPersonRepository: InterestedPersonRepository,
    private val auditLogRepository: AuditLogRepository,
) : ViewModel() {

    /** Pending requests addressed to [publisherPersonId] — this publisher's
     * own "Forwarded to Me" review queue. */
    fun incomingRequestsFor(publisherPersonId: String): Flow<List<PublisherForwardRequest>> =
        publisherForwardRequestRepository.observeAll().map { list ->
            list.filter { it.toPublisherPersonId == publisherPersonId && it.status == ForwardRequestStatus.PENDING }
                .sortedByDescending { it.requestedAt }
        }

    /** "Include the basic details of the forwarded record, not just the
     * name" — same reasoning as [ForwardRequestsViewModel.personFor]: a live
     * lookup, since the request itself only ever snapshots the name. */
    fun personFor(interestedPersonId: String): Flow<InterestedPerson?> =
        interestedPersonRepository.observeAll().map { list -> list.firstOrNull { it.id == interestedPersonId } }

    /** Every request [publisherPersonId] has ever sent — used by the sender's
     * own Home screen notifier to catch an Accept/Decline outcome. */
    fun outgoingRequestsFor(publisherPersonId: String): Flow<List<PublisherForwardRequest>> =
        publisherForwardRequestRepository.observeAll().map { list ->
            list.filter { it.fromPublisherPersonId == publisherPersonId }
        }

    /** Read-only, congregation-scoped view for Admin/Elders/Service
     * Overseer/Super-Admin (`congregationIds == null` means every
     * congregation) — mirrors [ForwardRequestsViewModel.pendingRequestsFor]'s
     * scoping, but every status (not pending-only), since there's no action
     * for them to take here — just visibility. */
    fun requestsFor(congregationIds: Set<String>?): Flow<List<PublisherForwardRequest>> =
        publisherForwardRequestRepository.observeAll().map { list ->
            list.filter { congregationIds == null || it.congregationId in congregationIds }
                .sortedByDescending { it.requestedAt }
        }

    fun accept(request: PublisherForwardRequest, actorPersonId: String) {
        viewModelScope.launch {
            val person = interestedPersonRepository.observeAll().first().firstOrNull { it.id == request.interestedPersonId } ?: return@launch
            // Deliberately doesn't clear pendingPublisherForwardRequestId —
            // same precedent as ForwardRequestsViewModel.accept() for the
            // cross-congregation flow: the pointer is how both the old and
            // new publisher's screens keep showing this request's final
            // "Accepted" status against the record.
            interestedPersonRepository.save(person.copy(publisherPersonId = request.toPublisherPersonId))
            publisherForwardRequestRepository.save(
                request.copy(status = ForwardRequestStatus.ACCEPTED, respondedAt = System.currentTimeMillis())
            )
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "ACCEPT_PUBLISHER_FORWARD_REQUEST",
                targetType = "InterestedPerson",
                targetId = request.interestedPersonId,
                details = "${request.personNameSnapshot} -> ${request.toPublisherNameSnapshot}",
            )
        }
    }

    fun decline(request: PublisherForwardRequest, actorPersonId: String) {
        viewModelScope.launch {
            publisherForwardRequestRepository.save(
                request.copy(status = ForwardRequestStatus.DECLINED, respondedAt = System.currentTimeMillis())
            )
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "DECLINE_PUBLISHER_FORWARD_REQUEST",
                targetType = "InterestedPerson",
                targetId = request.interestedPersonId,
                details = request.personNameSnapshot,
            )
        }
    }

    /** "Forward Request Module" (Super-Admin only) — same reasoning as
     * [ForwardRequestsViewModel.updateRequest]: an administrative
     * correction, not a normal Accept/Decline action. */
    fun updateRequest(request: PublisherForwardRequest, actorPersonId: String) {
        viewModelScope.launch {
            publisherForwardRequestRepository.save(request)
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "EDIT_PUBLISHER_FORWARD_REQUEST",
                targetType = "PublisherForwardRequest",
                targetId = request.id,
                details = "${request.personNameSnapshot} -> ${request.toPublisherNameSnapshot} (${request.status})",
            )
        }
    }

    /** "Forward Request Module" (Super-Admin only) — a hard delete for
     * cleaning up stale/test/mistaken request records. Never touches the
     * underlying InterestedPerson record itself. */
    fun deleteRequest(request: PublisherForwardRequest, actorPersonId: String) {
        viewModelScope.launch {
            publisherForwardRequestRepository.delete(request.id)
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "DELETE_PUBLISHER_FORWARD_REQUEST",
                targetType = "PublisherForwardRequest",
                targetId = request.id,
                details = "${request.personNameSnapshot} -> ${request.toPublisherNameSnapshot}",
            )
        }
    }
}

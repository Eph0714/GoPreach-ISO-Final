package com.emfitsolutions.gopreach.ui.screens.householderassignment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.location.GeocodedAddress
import com.emfitsolutions.gopreach.data.location.LocationTracker
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.HouseholderAssignment
import com.emfitsolutions.gopreach.data.model.HouseholderAssignmentStatus
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.HouseholderAssignmentRepository
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
 * "House Holder Assignment" module — backs both directions of the flow:
 * the Service Overseer/Admin/Super-Admin's own search-and-assign screen
 * ([HouseholderAssignmentScreen]) and the receiving Publisher's incoming
 * review queue ([IncomingHouseholderAssignmentsScreen]). One ViewModel for
 * both, same as [com.emfitsolutions.gopreach.ui.screens.pipeline
 * .ForwardRequestsViewModel] already is for its own two-sided flow — the
 * two screens just call different methods on it.
 */
@HiltViewModel
class HouseholderAssignmentViewModel @Inject constructor(
    private val assignmentRepository: HouseholderAssignmentRepository,
    private val interestedPersonRepository: InterestedPersonRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val personRepository: PersonRepository,
    private val auditLogRepository: AuditLogRepository,
    private val locationTracker: LocationTracker,
    congregationRepository: CongregationRepository,
) : ViewModel() {

    /** Congregation dropdown — Super-Admin only, same as every other
     * congregation-scoped module's own picker. */
    val congregations: StateFlow<List<Congregation>> = congregationRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** "Select Record Type -> Search Record" — every [InterestedPerson] at
     * [stage] within [congregationId] (`null` = every congregation, Super-
     * Admin only) that's actually *eligible* for a new assignment.
     *
     * "Important Record Ownership Rule" — this is the one place that rule is
     * enforced: a record with a [InterestedPerson.publisherPersonId] already
     * set is Publisher-owned and never appears here at all, regardless of
     * [query]; a record with an outstanding [InterestedPerson
     * .pendingHouseholderAssignmentId] is already mid-flow to some other
     * Publisher and is excluded the same way, so a Service Overseer can
     * never send two assignments for the same record at once. */
    fun eligibleRecordsFor(stage: PipelineStage, congregationId: String?, query: String): Flow<List<InterestedPerson>> =
        interestedPersonRepository.observeAll().map { list ->
            list
                .filter { it.pipelineStage == stage }
                .filter { congregationId == null || it.congregationId == congregationId }
                .filter { it.publisherPersonId.isBlank() && it.pendingHouseholderAssignmentId == null }
                .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) || it.address.contains(query, ignoreCase = true) }
                .sortedBy { it.name }
        }

    /** "Manual Coordinates" — reverse-geocodes [lat]/[lng] (the on-device
     * Geocoder, backed by an internet lookup — same mechanism/reliability
     * this app's own GPS-capture flows already use) into Municipality/
     * Barangay text. `null` fields mean the geocoder couldn't resolve that
     * level (or failed outright) — the caller (see [AddEligibleRecordDialog])
     * treats this as a best-effort suggestion, same as every other
     * reverse-geocode call in this app, never an authoritative fill the
     * Service Overseer can't see/override. Province is deliberately *not*
     * asked of the geocoder at all — it always comes from the assigned
     * Congregation's own [Congregation.province] instead (spec: "Province
     * will be automatically get from Congregation province"). */
    suspend fun reverseGeocode(lat: Double, lng: Double): GeocodedAddress? = locationTracker.reverseGeocodeAddress(lat, lng)

    /** "→ Search Record" found no match — a brand-new eligible record,
     * unowned by any Publisher until a sent assignment is Accepted (spec:
     * "assign existing or *newly searched*... records"). Mirrors the same
     * fields the Searching module's own enrollment form already collects.
     * [gpsLat]/[gpsLng] are the Service Overseer's own manually-entered
     * coordinates (spec: "Manual Coordinates... for the Service Overseer to
     * enter/send a request") — the one GPS-capture path this screen offers,
     * distinct from a Publisher's own in-field capture, which still applies
     * normally once whichever Publisher accepts this assignment owns the
     * record afterward. */
    suspend fun createEligibleRecord(
        name: String,
        address: String,
        barangay: String?,
        cityMunicipality: String?,
        province: String?,
        notes: String?,
        recordType: PipelineStage,
        congregationId: String,
        createdByPersonId: String,
        gpsLat: Double? = null,
        gpsLng: Double? = null,
    ): InterestedPerson {
        val now = System.currentTimeMillis()
        return interestedPersonRepository.save(
            InterestedPerson(
                name = name,
                address = address,
                barangay = barangay,
                cityMunicipality = cityMunicipality,
                province = province,
                notes = notes,
                pipelineStage = recordType,
                congregationId = congregationId,
                createdAt = now,
                createdByPersonId = createdByPersonId,
                stageEnteredAt = now,
                gpsLat = gpsLat,
                gpsLng = gpsLng,
                gpsCapturedAt = if (gpsLat != null) now else null,
                gpsCapturedBy = if (gpsLat != null) createdByPersonId else null,
                gpsUpdatedAt = if (gpsLat != null) now else null,
            )
        )
    }

    /** "→ Select Publisher" — active, non-removed publishers of
     * [congregationId], same candidate list [ForwardRequestsViewModel
     * .assignablePublishers] already builds for its own "assign to" step. */
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

    /** "→ Send Assignment" — creates the [HouseholderAssignment] itself
     * (`PENDING`) and points the record at it via
     * [InterestedPerson.pendingHouseholderAssignmentId], the same two-write
     * pattern [ForwardRequestsViewModel.accept] already uses (mutate the
     * owned record, then save the request/assignment document), just at
     * *send* time instead of *accept* time — this flow's Accept/Reject step
     * is the receiving Publisher's alone (see [accept]/[reject] below), not
     * this sender-side call. */
    fun sendAssignment(
        person: InterestedPerson,
        congregationNameSnapshot: String,
        toPublisher: Person,
        assignedByPersonId: String,
        assignedByNameSnapshot: String,
    ) {
        viewModelScope.launch {
            val assignment = assignmentRepository.save(
                HouseholderAssignment(
                    interestedPersonId = person.id,
                    personNameSnapshot = person.name,
                    recordType = person.pipelineStage,
                    congregationId = person.congregationId,
                    congregationNameSnapshot = congregationNameSnapshot,
                    barangaySnapshot = person.barangay,
                    addressSnapshot = person.address,
                    notesSnapshot = person.notes,
                    assignedByPersonId = assignedByPersonId,
                    assignedByNameSnapshot = assignedByNameSnapshot,
                    toPublisherPersonId = toPublisher.id,
                    toPublisherNameSnapshot = toPublisher.fullName,
                    status = HouseholderAssignmentStatus.PENDING,
                    assignedAt = System.currentTimeMillis(),
                )
            )
            interestedPersonRepository.save(person.copy(pendingHouseholderAssignmentId = assignment.id))
            auditLogRepository.log(
                actorPersonId = assignedByPersonId,
                action = "SEND_HOUSEHOLDER_ASSIGNMENT",
                targetType = "InterestedPerson",
                targetId = person.id,
                details = "${person.name} -> ${toPublisher.fullName}",
            )
        }
    }

    /** The sending Service Overseer/Admin/Super-Admin's own "sent
     * assignments" tracker — every status, not pending-only, scoped by
     * [congregationIds] (`null` = every congregation, Super-Admin only). */
    fun sentAssignmentsFor(congregationIds: Set<String>?): Flow<List<HouseholderAssignment>> =
        assignmentRepository.observeAll().map { list ->
            list.filter { congregationIds == null || it.congregationId in congregationIds }
                .sortedByDescending { it.assignedAt }
        }

    /** "Cancelled — Assignment was cancelled before acceptance, if permitted
     * by the system rules" — the sending side's own cancel action, only ever
     * offered by the screen while [HouseholderAssignment.status] is still
     * `PENDING`. Frees the record back into [eligibleRecordsFor]'s pool by
     * clearing its pointer, same as [reject] does on the receiving side. */
    fun cancel(assignment: HouseholderAssignment, actorPersonId: String) {
        viewModelScope.launch {
            assignmentRepository.save(
                assignment.copy(status = HouseholderAssignmentStatus.CANCELLED, cancelledAt = System.currentTimeMillis(), cancelledByPersonId = actorPersonId)
            )
            val person = interestedPersonRepository.observeAll().first().firstOrNull { it.id == assignment.interestedPersonId }
            if (person != null && person.pendingHouseholderAssignmentId == assignment.id) {
                interestedPersonRepository.save(person.copy(pendingHouseholderAssignmentId = null))
            }
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "CANCEL_HOUSEHOLDER_ASSIGNMENT",
                targetType = "HouseholderAssignment",
                targetId = assignment.id,
                details = assignment.personNameSnapshot,
            )
        }
    }

    /** The receiving Publisher's own "Incoming Assignments" queue —
     * `PENDING` only, same "actionable queue" convention
     * [PublisherForwardRequestsViewModel.incomingRequestsFor] already uses. */
    fun incomingAssignmentsFor(publisherPersonId: String): Flow<List<HouseholderAssignment>> =
        assignmentRepository.observeAll().map { list ->
            list.filter { it.toPublisherPersonId == publisherPersonId && it.status == HouseholderAssignmentStatus.PENDING }
                .sortedByDescending { it.assignedAt }
        }

    /** "Display: ...Any other existing fields already used by the
     * corresponding entity" — a live lookup, since [HouseholderAssignment]
     * only ever snapshots the name/address/barangay/notes it had *at
     * assignment time* (see that model's own doc comment); anything else
     * (stage, gender, GPS, etc.) comes from here, same as every existing
     * forward-request review screen's own `personFor`. */
    fun personFor(interestedPersonId: String): Flow<InterestedPerson?> =
        interestedPersonRepository.observeAll().map { list -> list.firstOrNull { it.id == interestedPersonId } }

    /** "Accept Assignment" — (1) mark ACCEPTED + who/when, (2) assign the
     * record to this Publisher, (3) it's now live in their own Searching/
     * Return Visit/Bible Study list (a plain [InterestedPerson.publisherPersonId]
     * match — no separate "my records" table to update). Steps 4-6 (notify
     * the assigner, record acceptance date/time and who accepted) all fall
     * out of this same write: [HouseholderAssignment.respondedAt]/
     * [HouseholderAssignment.respondedByPersonId] are the audit trail, and
     * the status flip away from PENDING is what
     * [com.emfitsolutions.gopreach.notifications.NotificationSoundCoordinator]
     * watches to notify the assigner (see that class's own
     * `outgoingHouseholderAssignmentStatusFor`). */
    fun accept(assignment: HouseholderAssignment, actorPersonId: String, actorNameSnapshot: String) {
        viewModelScope.launch {
            val person = interestedPersonRepository.observeAll().first().firstOrNull { it.id == assignment.interestedPersonId } ?: return@launch
            interestedPersonRepository.save(person.copy(publisherPersonId = actorPersonId, pendingHouseholderAssignmentId = null))
            assignmentRepository.save(
                assignment.copy(
                    status = HouseholderAssignmentStatus.ACCEPTED,
                    respondedAt = System.currentTimeMillis(),
                    respondedByPersonId = actorPersonId,
                    respondedByNameSnapshot = actorNameSnapshot,
                )
            )
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "ACCEPT_HOUSEHOLDER_ASSIGNMENT",
                targetType = "InterestedPerson",
                targetId = assignment.interestedPersonId,
                details = "${assignment.personNameSnapshot} -> $actorNameSnapshot",
            )
        }
    }

    /** "Reject Assignment" — (1)/(3) mark REJECTED + who/when, (2) never
     * assign the record (its [InterestedPerson.publisherPersonId] is left
     * exactly as it was — blank, for an eligible record), (4) same
     * status-flip-driven assigner notification as [accept], (7)/(8) who
     * rejected and the optional [reason] are stored on the assignment
     * itself. Clearing the record's pending pointer returns it to
     * [eligibleRecordsFor]'s pool, so the assigner can pick a different
     * Publisher without it looking stuck. */
    fun reject(assignment: HouseholderAssignment, actorPersonId: String, actorNameSnapshot: String, reason: String?) {
        viewModelScope.launch {
            val person = interestedPersonRepository.observeAll().first().firstOrNull { it.id == assignment.interestedPersonId }
            if (person != null && person.pendingHouseholderAssignmentId == assignment.id) {
                interestedPersonRepository.save(person.copy(pendingHouseholderAssignmentId = null))
            }
            assignmentRepository.save(
                assignment.copy(
                    status = HouseholderAssignmentStatus.REJECTED,
                    respondedAt = System.currentTimeMillis(),
                    respondedByPersonId = actorPersonId,
                    respondedByNameSnapshot = actorNameSnapshot,
                    rejectionReason = reason,
                )
            )
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "REJECT_HOUSEHOLDER_ASSIGNMENT",
                targetType = "InterestedPerson",
                targetId = assignment.interestedPersonId,
                details = assignment.personNameSnapshot + (reason?.let { " ($it)" } ?: ""),
            )
        }
    }
}

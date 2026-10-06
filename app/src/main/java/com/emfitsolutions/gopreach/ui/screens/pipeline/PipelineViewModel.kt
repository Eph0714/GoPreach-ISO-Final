package com.emfitsolutions.gopreach.ui.screens.pipeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.location.LatLng
import com.emfitsolutions.gopreach.data.location.LocationTracker
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.ForwardRequest
import com.emfitsolutions.gopreach.data.model.ForwardRequestStatus
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.isVisibleToPublisher
import com.emfitsolutions.gopreach.data.model.PublisherForwardRequest
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.model.Visit
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.ForwardRequestRepository
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.PhilippineAddressSelection
import com.emfitsolutions.gopreach.data.repository.PhilippineLocationRepository
import com.emfitsolutions.gopreach.data.repository.PublisherForwardRequestRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.VisitRepository
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs [PipelineScreen] for all three of its stages (see [PipelineStage]) —
 * one ViewModel, not three, since a Searching/Return Visit/Bible Study record
 * is the exact same [InterestedPerson] entity throughout its life; only the
 * screen's own filtering and which action buttons it shows differ by stage.
 */
@HiltViewModel
class PipelineViewModel @Inject constructor(
    private val interestedPersonRepository: InterestedPersonRepository,
    private val visitRepository: VisitRepository,
    private val auditLogRepository: AuditLogRepository,
    private val locationTracker: LocationTracker,
    private val personRepository: PersonRepository,
    private val forwardRequestRepository: ForwardRequestRepository,
    private val publisherForwardRequestRepository: PublisherForwardRequestRepository,
    private val congregationRepository: CongregationRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val philippineLocationRepository: PhilippineLocationRepository,
    private val publisherVisibilitySettingsRepository: com.emfitsolutions.gopreach.data.repository.PublisherVisibilitySettingsRepository,
    private val recycleBinRepository: com.emfitsolutions.gopreach.data.repository.RecycleBinRepository,
) : ViewModel() {

    /** Bug fix: [save] used to let any exception from the repository/Room/
     * Gson layer propagate out of its `viewModelScope.launch` uncaught —
     * exactly the pattern documented in [com.emfitsolutions.gopreach.data
     * .sync.mirrorFirestoreCollection]'s crash fix, which kills the whole
     * process rather than just failing the one save. A record with a large
     * supporting photo attached is the case most likely to trip a layer like
     * that, which is what made this reproduce as "the app closes when I
     * click Create or Save" for records with a photo. Now caught and
     * reported here instead. */
    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

    fun personName(personId: String): Flow<String?> =
        personRepository.observeAll().map { people -> people.firstOrNull { it.id == personId }?.fullName }

    /** personId -> full name for every known Person, so lists can show and search the assigned Publisher without a flow per row. */
    val personNames: Flow<Map<String, String>> =
        personRepository.observeAll().map { people -> people.associate { it.id to it.fullName } }

    /** The Publisher's own Remarks, trimmed; null when empty or whitespace-only (nothing is invented). */
    fun personRemarks(personId: String): Flow<String?> =
        personRepository.observeAll().map { people -> people.firstOrNull { it.id == personId }?.remarks?.trim()?.takeIf { it.isNotEmpty() } }

    fun observePerson(interestedPersonId: String): Flow<InterestedPerson?> =
        interestedPersonRepository.observeAll().map { list -> list.firstOrNull { it.id == interestedPersonId } }

    /** What a Publisher sees at [stage]: always their own records; plus, for Searching/Return Visit, every unassigned
     * record of the congregation; plus (only when the Admin switched it on in Publisher Assignment) records assigned to
     * other Publishers of the same congregation. Bible Studies of others are view-only — see
     * [PipelinePersonDetailScreen]. */
    fun visibleFor(publisherPersonId: String, congregationId: String, stage: PipelineStage): Flow<List<InterestedPerson>> =
        combine(interestedPersonRepository.observeAll(), publisherVisibilitySettingsRepository.observeFor(congregationId)) { list, settings ->
            list.filter { it.pipelineStage == stage && it.isVisibleToPublisher(publisherPersonId, congregationId, settings) }
        }

    fun visibilitySettingsFor(congregationId: String): Flow<com.emfitsolutions.gopreach.data.model.PublisherVisibilitySettings> =
        publisherVisibilitySettingsRepository.observeFor(congregationId)

    fun saveVisibilitySettings(settings: com.emfitsolutions.gopreach.data.model.PublisherVisibilitySettings, actorPersonId: String) {
        viewModelScope.launch {
            publisherVisibilitySettingsRepository.save(settings.copy(updatedByPersonId = actorPersonId))
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "CHANGE_PUBLISHER_VISIBILITY",
                targetType = "PublisherVisibilitySettings",
                targetId = settings.id,
                congregationId = settings.id,
                details = "searching=${settings.showOthersSearching}, returnVisit=${settings.showOthersReturnVisit}, bibleStudy=${settings.showOthersBibleStudy}",
            )
        }
    }

    /** Every record of [congregationId] at [stage], assigned or not — the Publisher Assignment module's data source. */
    fun recordsInCongregation(stage: PipelineStage, congregationId: String): Flow<List<InterestedPerson>> =
        interestedPersonRepository.observeAll().map { list -> list.filter { it.pipelineStage == stage && it.congregationId == congregationId } }

    /** Every record of [congregationId], all stages — for the tab counts. */
    fun allRecordsInCongregation(congregationId: String): Flow<List<InterestedPerson>> =
        interestedPersonRepository.observeAll().map { list -> list.filter { it.congregationId == congregationId } }

    /** Assigns, reassigns or (blank [newPublisherPersonId]) unassigns a record. Only the assignment changes — the
     * record, its visits and its history are untouched. */
    fun assignPublisher(person: InterestedPerson, newPublisherPersonId: String, actorPersonId: String) {
        viewModelScope.launch {
            runCatching {
                interestedPersonRepository.save(person.copy(publisherPersonId = newPublisherPersonId))
                auditLogRepository.log(
                    actorPersonId = actorPersonId,
                    action = if (newPublisherPersonId.isBlank()) "UNASSIGN_INTERESTED_PERSON" else "ASSIGN_INTERESTED_PERSON",
                    targetType = "InterestedPerson",
                    targetId = person.id,
                    congregationId = person.congregationId,
                    details = "${person.name}: ${person.publisherPersonId.ifBlank { "unassigned" }} -> ${newPublisherPersonId.ifBlank { "unassigned" }}",
                )
            }.onFailure { e ->
                Log.e("PipelineViewModel", "Failed to assign publisher", e)
                _errorEvents.emit("Could not change the assignment: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun hasLocationPermission(): Boolean = locationTracker.hasLocationPermission()
    suspend fun captureCurrentLocation(): LatLng? = locationTracker.getCurrentLocation()

    /** "Move the capture coordinates in the upper part of the enrollment...
     * it can be automatic if the publisher will capture the coordinates" —
     * called from the create/edit form itself the moment coordinates are
     * captured/entered there, so the City/Municipality/Barangay dropdowns
     * further down the same form are already filled in by the time the
     * publisher reaches them. Same best-effort reverse-geocode-then-match
     * approach as [saveGpsLocation]; returns null (not an all-null
     * selection) if the geocoder had nothing at all, so the caller can
     * leave whatever the publisher already picked untouched. */
    suspend fun resolveAddressLevels(lat: Double, lng: Double): PhilippineAddressSelection? = runCatching {
        val geocoded = locationTracker.reverseGeocodeAddress(lat, lng)
        Log.d("AddressDetect", "geocoder($lat, $lng) -> $geocoded")
        if (geocoded == null) return@runCatching null
        philippineLocationRepository.resolveFromGeocode(geocoded).toSelection().also { Log.d("AddressDetect", "PSGC match -> $it") }
    }.onFailure { Log.e("AddressDetect", "address detection failed", it) }.getOrNull()

    /** Every record this publisher owns at [stage], active-only unless
     * [includeInactive] (same "Show Inactive" convention as every other list
     * screen in this app). */
    fun peopleFor(publisherPersonId: String, stage: PipelineStage): Flow<List<InterestedPerson>> =
        interestedPersonRepository.observeAll()
            .map { list -> list.filter { it.publisherPersonId == publisherPersonId && it.pipelineStage == stage } }

    /** Spec §15 — "Elders should be able to see Interested Person information
     * according to their existing Congregation/Group access scope": every
     * [InterestedPerson] at [stage] belonging to [congregationId] (null means
     * every congregation, Super-Admin-style — not used by this screen today
     * but kept consistent with every other `visibleCongregationId` convention
     * in this app), narrowed further to [groupId]'s own members when a
     * Regular Elder's scope is a single Group rather than the whole
     * congregation. Group membership is resolved the same way [ReportsViewModel
     * .buildRows] already does it — via each Publisher's own [RoleType.Publisher]
     * `RoleAssignment.groupId] — since [InterestedPerson] itself carries no
     * groupId of its own, only [InterestedPerson.congregationId]/
     * [InterestedPerson.publisherPersonId]. Backs [ElderInterestedRecordsScreen]. */
    fun peopleForScope(stage: PipelineStage, congregationId: String?, groupId: String?): Flow<List<InterestedPerson>> =
        combine(interestedPersonRepository.observeAll(), roleAssignmentRepository.observeAll()) { people, assignments ->
            val scopedPublisherIds = if (groupId == null) null else assignments
                .asSequence()
                .filter { it.resolvedRoleTypeOrNull() is RoleType.Publisher && it.groupId == groupId }
                .map { it.personId }
                .toSet()
            people
                .filter { it.pipelineStage == stage }
                .filter { congregationId == null || it.congregationId == congregationId }
                .filter { scopedPublisherIds == null || it.publisherPersonId in scopedPublisherIds }
        }

    /** Every congregation, for the Super-Admin "All Congregations" screen's
     * congregation labels/picker — same convention [ContactRecordViewModel
     * .congregations] already uses. */
    val congregations: StateFlow<List<Congregation>> =
        congregationRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Every active, non-removed Publisher in [congregationId] — the Super-
     * Admin "Add Record" flow's own publisher picker (who a brand-new record
     * gets assigned to). Same candidate shape [otherPublishers] already
     * builds; reused as-is with a blank exclude id since nobody needs
     * excluding here. */
    fun publishersFor(congregationId: String): Flow<List<Person>> = otherPublishers(congregationId, excludePersonId = "")

    /** Publishers a Find Location enrollment can be assigned to — everyone
     * [publishersFor] returns, narrowed to [groupId]'s own members when the
     * signed-in role is scoped to a single Group (a Regular Elder), using the
     * same "Publisher RoleAssignment.groupId" membership [peopleForScope]
     * already relies on. */
    fun assignablePublishersFor(congregationId: String, groupId: String?): Flow<List<Person>> {
        if (groupId == null) return publishersFor(congregationId)
        return combine(publishersFor(congregationId), roleAssignmentRepository.observeAll()) { publishers, assignments ->
            val memberIds = assignments
                .filter { it.status == RoleAssignmentStatus.ACTIVE && it.groupId == groupId && it.resolvedRoleTypeOrNull() is RoleType.Publisher }
                .map { it.personId }
                .toSet()
            publishers.filter { it.id in memberIds }
        }
    }

    /** Saves a record enrolled from Find Location and leaves an audit trail
     * of who created it and who (if anyone) it was assigned to — [save]
     * itself deliberately stays audit-free, as it always has been. */
    fun saveEnrolledRecord(person: InterestedPerson, actorPersonId: String) {
        viewModelScope.launch {
            runCatching {
                val saved = interestedPersonRepository.save(person)
                // The record is already saved; a failed audit entry must not
                // be reported to the user as a failed save.
                runCatching {
                    auditLogRepository.log(
                        actorPersonId = actorPersonId,
                        action = "ENROLL_FROM_FIND_LOCATION",
                        targetType = "InterestedPerson",
                        targetId = saved.id,
                        details = "${saved.pipelineStage}: ${saved.name} (assigned: ${saved.publisherPersonId.ifBlank { "none" }})",
                    )
                }
            }.onFailure { e ->
                Log.e("PipelineViewModel", "Failed to save Find Location enrollment", e)
                _errorEvents.emit("Could not save the record: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun save(person: InterestedPerson) {
        viewModelScope.launch {
            runCatching { interestedPersonRepository.save(person) }
                .onFailure { e ->
                    Log.e("PipelineViewModel", "Failed to save Interested Person record", e)
                    _errorEvents.emit("Could not save the record: ${e.message ?: "unknown error"}")
                }
        }
    }

    fun setStatus(person: InterestedPerson, status: RecordStatus, actorPersonId: String) {
        viewModelScope.launch {
            val previous = person.status
            interestedPersonRepository.save(person.copy(status = status))
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "CHANGE_INTERESTED_PERSON_STATUS",
                targetType = "InterestedPerson",
                targetId = person.id,
                details = "status: $previous -> $status (${person.name})",
            )
        }
    }

    fun permanentlyDelete(person: InterestedPerson, actorPersonId: String) {
        viewModelScope.launch {
            val visits = visitRepository.observeForInterestedPerson(person.id).first()
            // Kept whole in Deleted Records (the person and every visit, same ids) before anything is removed.
            recycleBinRepository.moveToTrash(
                recordType = when (person.pipelineStage) {
                    PipelineStage.BIBLE_STUDY -> "Bible Study"
                    PipelineStage.RETURN_VISIT -> "Return Visit"
                    else -> "Interested Person"
                },
                module = "Return Visit / Bible Study",
                label = person.name,
                congregationId = person.congregationId,
                originalCreatedAt = person.createdAt,
                originalModifiedAt = person.updatedAt,
                deletedByPersonId = actorPersonId,
                items = buildList {
                    add(recycleBinRepository.item("interestedPeople", person.id, person))
                    visits.forEach { add(recycleBinRepository.item("interestedPeople/${person.id}/visits", it.id, it)) }
                },
            )
            visits.forEach { visit -> visitRepository.delete(person.id, visit.id) }
            interestedPersonRepository.delete(person.id)
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "PERMANENT_DELETE_INTERESTED_PERSON",
                targetType = "InterestedPerson",
                targetId = person.id,
                details = person.name,
            )
        }
    }

    /** "MOVE TO RETURN VISIT MODULE" / "MOVE TO BIBLE STUDY MODULE" — and,
     * per "Add Reverse Status Movement," the same one-step move backward
     * (Bible Study → Return Visit → Searching Interested Person) reuses this
     * exact function; [newStage] was never restricted to "forward" in code,
     * only by which buttons [PipelineScreen] chose to show (see that
     * screen's own `previousStage()`/`nextStage()`). Updates the *same*
     * [InterestedPerson] record in place — no new record is ever created,
     * and every other field (personal info, GPS, notes, assigned Publisher,
     * Visit History, prior audit-log entries) is untouched — only
     * [InterestedPerson.pipelineStage] changes. Bumps
     * [InterestedPerson.stageEnteredAt] so date-range reports (Consolidated
     * Report, Publisher Dashboard) count this transition on the day it
     * actually happened; a move backward re-enters the earlier module the
     * same way a forward move enters the next one, and [peopleFor]'s own
     * `pipelineStage == stage` filter is what makes the record vanish from
     * its old module's list and appear in the new one automatically — no
     * separate "module sync" step exists to reuse or duplicate. */
    fun advanceStage(person: InterestedPerson, newStage: PipelineStage, actorPersonId: String) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            interestedPersonRepository.save(person.copy(pipelineStage = newStage, stageEnteredAt = now))
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "ADVANCE_PIPELINE_STAGE",
                targetType = "InterestedPerson",
                targetId = person.id,
                details = "${person.pipelineStage} -> $newStage (${person.name})",
            )
        }
    }

    /** "It can be automatic if the publisher will capture the coordinates,
     * the system will automatically fill-up the City, Municipalities, Town
     * and barangay" — reverse-geocodes the just-captured fix (best-effort;
     * see [com.emfitsolutions.gopreach.data.location.GeocodedAddress]'s own
     * doc comment on why barangay in particular isn't always resolved) and
     * matches it against the bundled PSGC data (see
     * [PhilippineLocationRepository.resolveFromGeocode]) before saving.
     * Only overwrites a level the geocoder+match actually resolved —
     * [existingPerson]'s own manually-picked province/city/barangay is left
     * untouched for any level that came back empty, rather than being
     * cleared out by a failed lookup. */
    fun saveGpsLocation(person: InterestedPerson, lat: Double, lng: Double, accuracyMeters: Float?, capturedByPersonId: String) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val geocoded = runCatching { locationTracker.reverseGeocodeAddress(lat, lng) }.getOrNull()
            val resolved = geocoded?.let { philippineLocationRepository.resolveFromGeocode(it) }
            interestedPersonRepository.save(
                person.copy(
                    gpsLat = lat, gpsLng = lng, gpsAccuracy = accuracyMeters,
                    gpsCapturedAt = now, gpsCapturedBy = capturedByPersonId, gpsUpdatedAt = now,
                    province = resolved?.provinceName ?: person.province,
                    cityMunicipality = resolved?.muncityName ?: person.cityMunicipality,
                    barangay = resolved?.barangayName ?: person.barangay,
                )
            )
            auditLogRepository.log(actorPersonId = capturedByPersonId, action = "CAPTURE_INTERESTED_PERSON_GPS", targetType = "InterestedPerson", targetId = person.id, details = person.name)
        }
    }

    fun clearGpsLocation(person: InterestedPerson, actorPersonId: String) {
        viewModelScope.launch {
            interestedPersonRepository.save(person.copy(gpsLat = null, gpsLng = null, gpsAccuracy = null, gpsCapturedAt = null, gpsCapturedBy = null, gpsUpdatedAt = null))
            auditLogRepository.log(actorPersonId = actorPersonId, action = "CLEAR_INTERESTED_PERSON_GPS", targetType = "InterestedPerson", targetId = person.id, details = person.name)
        }
    }

    fun visitsFor(interestedPersonId: String): Flow<List<Visit>> = visitRepository.observeForInterestedPerson(interestedPersonId)
    fun startVisitSync(interestedPersonId: String): Flow<Unit> = visitRepository.startRemoteSync(interestedPersonId)

    /** "Each Publisher may edit or delete only the Visit History entries that
     * they personally created" (Return Visit/Searching) — [existingVisit] is
     * the entry being replaced (null for a brand-new "Add Visit"), checked
     * against its own [Visit.createdByPersonId], never against [visit] (the
     * caller-supplied replacement) since that field is deliberately
     * preserved unchanged by [AddVisitDialog] and shouldn't be trusted as the
     * authorization input either way. "Territory Map Return Visit
     * Permissions" spec §3/§7 — a Bible Study has no per-entry ownership:
     * only [enrolledPublisherId] (the parent record's own
     * [InterestedPerson.publisherPersonId]) may edit any of its visit
     * history, regardless of [Visit.createdByPersonId]; this is what actually
     * keeps the shared Return Visit permission from ever applying to a Bible
     * Study, even if a caller somehow reached this with an unauthorized
     * combination the UI itself would never construct.
     * [canManageAllVisitHistory] is the Super-Admin/explicitly-authorized-
     * role override (spec §8); the calling screen already hides the Edit
     * affordance for anyone this returns false for, but the check is
     * repeated here too — a hidden button is never the real enforcement (the
     * real enforcement is firestore.rules; this is just the same rule applied
     * once more before firing the write, not a replacement for it). Silently
     * no-ops on failure, matching every other call site (none of them surface
     * a return value today).
     */
    fun saveVisit(
        visit: Visit,
        currentPersonId: String,
        canManageAllVisitHistory: Boolean,
        stage: PipelineStage,
        enrolledPublisherId: String,
        existingVisit: Visit? = null,
    ) {
        if (!canManageAllVisitHistory) {
            val allowed = if (stage == PipelineStage.BIBLE_STUDY) {
                currentPersonId == enrolledPublisherId
            } else {
                existingVisit == null || existingVisit.createdByPersonId == currentPersonId
            }
            if (!allowed) return
        }
        viewModelScope.launch {
            // "House Holder Visit History" spec §6/§8 — best-effort GPS
            // capture for a brand-new visit, same silent-on-failure pattern
            // [captureCurrentLocation] already follows for the parent
            // record's own location (no permission prompt forced here,
            // no error surfaced — a visit with no fix just shows "Not
            // available", it's never blocked from saving over this).
            // Editing an existing visit never re-captures or otherwise
            // touches [Visit.visitLat]/[visitLng] — [visit] already carries
            // [existingVisit]'s own untouched values forward (see
            // AddVisitDialog's own submit()), preserving that visit's
            // original coordinates exactly as spec §13 requires.
            val toSave = if (existingVisit == null && !visit.hasVisitLocation && hasLocationPermission()) {
                val captured = runCatching { captureCurrentLocation() }.getOrNull()
                if (captured != null) visit.copy(visitLat = captured.lat, visitLng = captured.lng) else visit
            } else {
                visit
            }
            visitRepository.save(toSave)
        }
    }

    fun deleteVisit(
        interestedPersonId: String,
        visit: Visit,
        currentPersonId: String,
        canManageAllVisitHistory: Boolean,
        stage: PipelineStage,
        enrolledPublisherId: String,
    ) {
        val allowed = canManageAllVisitHistory ||
            if (stage == PipelineStage.BIBLE_STUDY) currentPersonId == enrolledPublisherId else visit.createdByPersonId == currentPersonId
        if (!allowed) return
        viewModelScope.launch {
            val person = interestedPersonRepository.observeAll().first().firstOrNull { it.id == interestedPersonId }
            recycleBinRepository.moveToTrash(
                recordType = "Visit",
                module = "Return Visit / Bible Study",
                label = "Visit on ${java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(visit.visitDate))}" +
                    (person?.name?.let { " — $it" } ?: ""),
                congregationId = person?.congregationId,
                originalCreatedAt = visit.createdAt,
                deletedByPersonId = currentPersonId,
                items = listOf(recycleBinRepository.item("interestedPeople/$interestedPersonId/visits", visit.id, visit)),
            )
            visitRepository.delete(interestedPersonId, visit.id)
        }
    }

    /** "FORWARD TO OTHER CONGREGATION" spec flow — every other active
     * congregation, for the search-by-name-or-language picker. */
    fun congregationName(congregationId: String): Flow<String?> =
        congregationRepository.observeAll().map { list -> list.firstOrNull { it.id == congregationId }?.name }

    fun otherCongregations(ownCongregationId: String): Flow<List<Congregation>> =
        congregationRepository.observeAll()
            .map { list -> list.filter { it.status == RecordStatus.ACTIVE && it.id != ownCongregationId }.sortedBy { it.name } }

    /** Live status of [person]'s most recent forward attempt, if any — the
     * sending publisher's own screen reads this to show "Forward status:
     * Pending/Accepted/Declined" without a separate lookup table. */
    fun forwardRequestFor(person: InterestedPerson): Flow<ForwardRequest?> = forwardRequestRepository.observeAll()
        .map { list -> person.pendingForwardRequestId?.let { id -> list.firstOrNull { it.id == id } } }

    /** Every cross-congregation forward [publisherPersonId] has ever sent —
     * "there will be a notification for the Service Overseer and the
     * Publisher for the status of the request" (Return Visit forward spec):
     * used by the sending publisher's own Home screen notifier to catch an
     * Accept/Decline outcome, the same way [PublisherForwardRequestsViewModel
     * .outgoingRequestsFor] does for the same-congregation flow. */
    fun outgoingForwardRequestsFor(publisherPersonId: String): Flow<List<ForwardRequest>> =
        forwardRequestRepository.observeAll().map { list -> list.filter { it.fromPublisherPersonId == publisherPersonId } }

    fun forward(
        person: InterestedPerson,
        toCongregation: Congregation,
        fromCongregationName: String,
        fromPublisherName: String,
        actorPersonId: String,
    ) {
        viewModelScope.launch {
            val request = forwardRequestRepository.save(
                ForwardRequest(
                    interestedPersonId = person.id,
                    personNameSnapshot = person.name,
                    fromCongregationId = person.congregationId,
                    fromCongregationNameSnapshot = fromCongregationName,
                    fromPublisherPersonId = person.publisherPersonId,
                    fromPublisherNameSnapshot = fromPublisherName,
                    toCongregationId = toCongregation.id,
                    toCongregationNameSnapshot = toCongregation.name,
                    status = ForwardRequestStatus.PENDING,
                    requestedAt = System.currentTimeMillis(),
                )
            )
            interestedPersonRepository.save(person.copy(pendingForwardRequestId = request.id))
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "FORWARD_INTERESTED_PERSON",
                targetType = "InterestedPerson",
                targetId = person.id,
                details = "${person.name} -> ${toCongregation.name}",
            )
        }
    }

    /** "Add a cancel request to all transfer[s]... applicable if the process
     * is not yet accepted by the receiving user" — only the sending
     * publisher ever calls this, and only while [request] is still
     * [ForwardRequestStatus.PENDING] (enforced by the UI only ever showing
     * the Cancel action then; re-checked here too since this is also
     * reachable while the screen's own snapshot of [request] is stale by a
     * moment). Doesn't clear [InterestedPerson.pendingForwardRequestId] —
     * same "the pointer is how the screen keeps showing this request's
     * final status" precedent [accept]/[decline] already follow. */
    fun cancelForward(request: ForwardRequest, actorPersonId: String) {
        if (request.status != ForwardRequestStatus.PENDING) return
        viewModelScope.launch {
            forwardRequestRepository.save(
                request.copy(status = ForwardRequestStatus.CANCELLED, respondedAt = System.currentTimeMillis(), respondedByPersonId = actorPersonId)
            )
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "CANCEL_FORWARD_REQUEST",
                targetType = "InterestedPerson",
                targetId = request.interestedPersonId,
                details = "${request.personNameSnapshot} -> ${request.toCongregationNameSnapshot}",
            )
        }
    }

    /** "FORWARD TO OTHER PUBLISHER" spec flow — every other active,
     * non-removed publisher in [congregationId] (same congregation only —
     * this flow never crosses congregations), excluding the sender
     * themselves. */
    fun otherPublishers(congregationId: String, excludePersonId: String): Flow<List<Person>> = combine(
        roleAssignmentRepository.observeAll(),
        personRepository.observeAll(),
    ) { assignments, people ->
        assignments
            .filter { it.status == RoleAssignmentStatus.ACTIVE && it.congregationId == congregationId && it.personId != excludePersonId }
            .mapNotNull { (it.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.let { p -> it to p } }
            .filter { (_, publisher) -> publisher.category != PublisherCategory.REMOVED_PUBLISHER }
            .mapNotNull { (assignment, _) -> people.firstOrNull { it.id == assignment.personId } }
            .distinctBy { it.id }
            .sortedBy { it.fullName }
    }

    /** Live status of [person]'s most recent same-congregation forward
     * attempt, if any — mirrors [forwardRequestFor] for the "FORWARD TO
     * OTHER PUBLISHER" flow. */
    fun publisherForwardRequestFor(person: InterestedPerson): Flow<PublisherForwardRequest?> = publisherForwardRequestRepository.observeAll()
        .map { list -> person.pendingPublisherForwardRequestId?.let { id -> list.firstOrNull { it.id == id } } }

    fun forwardToPublisher(
        person: InterestedPerson,
        toPublisher: Person,
        fromPublisherName: String,
        actorPersonId: String,
    ) {
        viewModelScope.launch {
            val request = publisherForwardRequestRepository.save(
                PublisherForwardRequest(
                    interestedPersonId = person.id,
                    personNameSnapshot = person.name,
                    congregationId = person.congregationId,
                    fromPublisherPersonId = person.publisherPersonId,
                    fromPublisherNameSnapshot = fromPublisherName,
                    toPublisherPersonId = toPublisher.id,
                    toPublisherNameSnapshot = toPublisher.fullName,
                    status = ForwardRequestStatus.PENDING,
                    requestedAt = System.currentTimeMillis(),
                )
            )
            interestedPersonRepository.save(person.copy(pendingPublisherForwardRequestId = request.id))
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "FORWARD_INTERESTED_PERSON_TO_PUBLISHER",
                targetType = "InterestedPerson",
                targetId = person.id,
                details = "${person.name} -> ${toPublisher.fullName}",
            )
        }
    }

    /** Same "cancel while still Pending" flow as [cancelForward], for the
     * same-congregation "FORWARD TO OTHER PUBLISHER" request instead. */
    fun cancelPublisherForward(request: PublisherForwardRequest, actorPersonId: String) {
        if (request.status != ForwardRequestStatus.PENDING) return
        viewModelScope.launch {
            publisherForwardRequestRepository.save(
                request.copy(status = ForwardRequestStatus.CANCELLED, respondedAt = System.currentTimeMillis())
            )
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "CANCEL_PUBLISHER_FORWARD_REQUEST",
                targetType = "InterestedPerson",
                targetId = request.interestedPersonId,
                details = "${request.personNameSnapshot} -> ${request.toPublisherNameSnapshot}",
            )
        }
    }
}

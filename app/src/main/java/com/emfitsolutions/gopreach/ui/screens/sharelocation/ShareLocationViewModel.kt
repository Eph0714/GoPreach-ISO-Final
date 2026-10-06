package com.emfitsolutions.gopreach.ui.screens.sharelocation

import android.content.Context
import com.emfitsolutions.gopreach.data.model.displayName
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.location.LatLng
import com.emfitsolutions.gopreach.data.location.LocationSharingService
import com.emfitsolutions.gopreach.data.location.LocationTracker
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.LocationSharingSettings
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.model.SharedLocation
import com.emfitsolutions.gopreach.data.model.isCurrentlyFresh
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.GroupRepository
import com.emfitsolutions.gopreach.data.repository.LocationSharingSettingsRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.SharedLocationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SharedLocationRow(
    val person: Person,
    val location: SharedLocation,
    val category: PublisherCategory?,
    val groupName: String?,
    val congregationName: String,
)

/** "Share Location – Show Current Coordinates" spec §1 — [capturedAt] is
 * when this specific fix was obtained, so the UI can say "captured just
 * now" rather than only showing raw numbers with no sense of freshness. */
data class MyLocationState(
    val fix: LatLng,
    val capturedAt: Long,
)

/**
 * Spec §6.1 — Share Location. Visibility is role-scoped by the caller (see
 * [rowsFor]'s congregation filter); a publisher additionally shares their
 * own live position here while preaching.
 *
 * "SHARE LOCATION SETTINGS" spec — the configured per-congregation
 * [LocationSharingSettings.sharingDurationMinutes] (auto-stop) and
 * [LocationSharingSettings.accuracyRadiusMeters] (a fix worse than this is
 * never published) are enforced inside [LocationSharingService] itself, not
 * here — see that class's doc comment for why the actual sharing loop lives
 * in a foreground Service rather than this ViewModel: it needs to keep
 * running after the Publisher leaves this screen.
 */
class ShareLocationViewModel(
    private val context: Context,
    private val sharedLocationRepository: SharedLocationRepository,
    private val personRepository: PersonRepository,
    private val locationTracker: LocationTracker,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val groupRepository: GroupRepository,
    private val congregationRepository: CongregationRepository,
    private val locationSharingSettingsRepository: LocationSharingSettingsRepository,
    private val auditLogRepository: AuditLogRepository,
) : ViewModel() {

    val congregations: StateFlow<List<Congregation>> =
        congregationRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Bug fix: this used to be a plain `MutableStateFlow(false)` local to
     * this ViewModel, which reset to false every time the screen (and this
     * ViewModel) got recreated — so leaving Share Location and reopening it
     * always showed the toggle unchecked, even while sharing was still
     * genuinely active. Now derived from the actual persisted/synced
     * [SharedLocation] doc — the same source of truth every *other*
     * publisher's row on this screen already reads from — so it reflects
     * reality regardless of when or how the screen is reopened.
     *
     * "Do not wait for GPS or server synchronization before telling the
     * user that Share Location has been activated" — [_optimisticSharing]
     * is set the instant the toggle is tapped (see [toggleSharing]) and
     * takes priority over the real doc for as long as it's set, so the
     * Switch/status card respond to the tap itself, not to however long the
     * first GPS fix and Firestore round-trip take. Cleared the moment the
     * real doc actually confirms the same state, so it can never
     * permanently mask reality (sharing auto-expiring server-side later,
     * say, must still eventually show as OFF). */
    private val _optimisticSharing = MutableStateFlow<Boolean?>(null)

    fun isSharingFor(publisherPersonId: String): Flow<Boolean> =
        combine(
            sharedLocationRepository.observeFor(publisherPersonId).map { it?.isCurrentlyFresh() == true },
            _optimisticSharing,
        ) { real, optimistic -> optimistic ?: real }

    /** "Location Acquired" — a valid GPS fix obtained since the current
     * sharing session started, distinct from whether it's reached the
     * server yet (see [syncState]) or from sharing being on at all (see
     * [isSharingFor]). Passed straight through from the Service that
     * actually does the work — see [LocationSharingService.locationAcquired]'s
     * own doc comment for why this is safe as a process-wide signal. */
    val locationAcquired: StateFlow<Boolean> = LocationSharingService.locationAcquired

    /** "Location Synchronized" — whether the most recent fix has actually
     * reached Firestore; see [LocationSharingService.syncState]. */
    val syncState: StateFlow<LocationSharingService.LocationSyncState> = LocationSharingService.syncState

    /** Last coordinates [LocationSharingService] published for this
     * publisher — updated live from [SharedLocation], so the status card's
     * Latitude/Longitude/Last Updated stay current without this screen ever
     * polling GPS itself. "Simplify Share Location" spec — no manual refresh
     * control exists any more; the continuous, push-based location
     * subscription [LocationSharingService] now runs (see that class's own
     * doc comment) is the only way this ever changes. */
    private val _myLocation = MutableStateFlow<MyLocationState?>(null)
    val myLocation: StateFlow<MyLocationState?> = _myLocation.asStateFlow()

    fun hasLocationPermission(): Boolean = locationTracker.hasLocationPermission()

    /** One-off read of this device's current coordinates for "Show my current
     * Coordinates" — purely for the publisher to look at: nothing is shared or
     * stored. Falls back to the last known fix when a fresh one isn't available;
     * null when neither is. */
    suspend fun currentCoordinates(): LatLng? =
        runCatching { locationTracker.getCurrentLocation() ?: locationTracker.getLastKnownLocation() }.getOrNull()
    fun isLocationServicesEnabled(): Boolean = locationTracker.isLocationServicesEnabled()

    /** Keeps "My Current Location" in sync with whatever [LocationSharingService]
     * just published for this publisher, without a second GPS poll. */
    fun observeOwnSharedLocation(publisherPersonId: String) {
        viewModelScope.launch {
            sharedLocationRepository.observeFor(publisherPersonId).collectLatest { location ->
                if (location != null && location.isSharing) {
                    _myLocation.value = MyLocationState(LatLng(location.lat, location.lng, location.accuracyMeters), location.updatedAt)
                }
            }
        }
    }

    /** "Shared Location Reports" spec — every field the report table shows,
     * enriched from the raw [SharedLocation] doc: the sharer's Publisher
     * category ("Status"), Group name, and Congregation name. Search
     * (name/status/group, plus congregation for a Super-Admin) is applied
     * here too rather than duplicated per caller. */
    fun rowsFor(visibleCongregationId: String?, searchQuery: String): Flow<List<SharedLocationRow>> =
        combine(
            sharedLocationRepository.observeAll(),
            personRepository.observeAll(),
            roleAssignmentRepository.observeAll(),
            groupRepository.observeAll(),
            congregationRepository.observeAll(),
        ) { locations, people, assignments, groups, congregations ->
            locations
                // "Location Sharing = ON... Location data is not expired" —
                // isCurrentlyFresh() checks both the isSharing flag and its
                // own recency, so a publisher whose sharing Service died
                // without writing a clean "stopped" doc drops off this list
                // the same way they already drop off the Territory Map's
                // Publisher layer, rather than lingering as a stale "sharer."
                // Includes the viewer's own entry (unlike the old "Sharing
                // now" list, which excluded it) — "Team Locations" is meant
                // to show who's sharing, period, with the viewer's own row
                // carrying the stop-sharing action instead of a separate
                // status card.
                .filter { it.isCurrentlyFresh() }
                .filter { visibleCongregationId == null || it.congregationId == visibleCongregationId }
                .mapNotNull { location ->
                    val person = people.firstOrNull { it.id == location.publisherPersonId } ?: return@mapNotNull null
                    val category = assignments.firstOrNull {
                        it.personId == person.id && it.congregationId == location.congregationId && it.resolvedRoleTypeOrNull() is RoleType.Publisher
                    }?.let { (it.resolvedRoleTypeOrNull() as RoleType.Publisher).category }
                    val groupName = groups.firstOrNull { it.id == location.groupId }?.name
                    val congregationName = congregations.firstOrNull { it.id == location.congregationId }?.name ?: "—"
                    SharedLocationRow(person, location, category, groupName, congregationName)
                }
                .filter { row ->
                    searchQuery.isBlank() ||
                        row.person.fullName.contains(searchQuery, ignoreCase = true) ||
                        row.category?.displayName?.contains(searchQuery, ignoreCase = true) == true ||
                        row.groupName?.contains(searchQuery, ignoreCase = true) == true ||
                        row.congregationName.contains(searchQuery, ignoreCase = true)
                }
                .sortedBy { it.person.fullName }
        }

    fun settingsFor(congregationId: String): Flow<LocationSharingSettings> = locationSharingSettingsRepository.observeFor(congregationId)

    fun saveSettings(settings: LocationSharingSettings, actorPersonId: String) {
        viewModelScope.launch {
            locationSharingSettingsRepository.save(settings.copy(updatedByPersonId = actorPersonId, updatedAt = System.currentTimeMillis()))
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "UPDATE_LOCATION_SHARING_SETTINGS",
                targetType = "LocationSharingSettings",
                targetId = settings.congregationId,
                congregationId = settings.congregationId,
                details = "duration: ${settings.sharingDurationMinutes}min, accuracy: ${settings.accuracyRadiusMeters}m",
            )
        }
    }

    /** Starts/stops [LocationSharingService] — see that class's doc comment
     * for why the actual sharing loop lives there now instead of here.
     * [_optimisticSharing] gives the toggle its instant feedback (see that
     * property's own doc comment) — set the moment this is called, in
     * *both* directions (Stop responds just as immediately as Start),
     * cleared once the real doc confirms the same state, or if this
     * ViewModel is torn down (screen closed) before that ever happens. */
    fun toggleSharing(enabled: Boolean, publisherPersonId: String, congregationId: String?, groupId: String?) {
        _optimisticSharing.value = enabled
        if (enabled) {
            LocationSharingService.start(context, publisherPersonId, congregationId, groupId)
        } else {
            LocationSharingService.stop(context, publisherPersonId)
        }
        viewModelScope.launch {
            sharedLocationRepository.observeFor(publisherPersonId).map { it?.isCurrentlyFresh() == true }.first { it == enabled }
            _optimisticSharing.value = null
        }
    }
}

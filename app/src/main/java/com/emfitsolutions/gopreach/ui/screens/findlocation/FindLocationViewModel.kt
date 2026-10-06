package com.emfitsolutions.gopreach.ui.screens.findlocation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.location.LatLng
import com.emfitsolutions.gopreach.data.location.LocationTracker
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.SavedLocation
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.SavedLocationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Find Location" — a Publisher manually enters a destination's GPS
 * coordinates and gets a same-screen shortcut into Google Maps turn-by-turn
 * directions for it, walking or by any of a few vehicle types (spec request:
 * "he can see the fastest route to go there using different kinds of vehicle
 * or by walking"). Reuses [LocationTracker.reverseGeocode] (already used by
 * Share Location) purely to show the destination as a human-readable address
 * next to the raw coordinates — the actual routing/"fastest route"
 * computation is left to Google Maps itself once launched (see
 * [FindLocationScreen]'s `openDirections`), rather than reimplementing a
 * routing engine with no bundled Directions API key.
 *
 * Also backs "save this coordinate with a remark for next time" (see
 * [SavedLocation]) — own-publisher-only, offline-first like everything else.
 */
@HiltViewModel
class FindLocationViewModel @Inject constructor(
    private val locationTracker: LocationTracker,
    private val savedLocationRepository: SavedLocationRepository,
    private val interestedPersonRepository: InterestedPersonRepository,
    private val auditLogRepository: AuditLogRepository,
) : ViewModel() {
    suspend fun addressFor(lat: Double, lng: Double): String? = locationTracker.reverseGeocode(lat, lng)

    /** "The textbox will search a coordinates or address, not just the
     * latitude and longitude" — the address half; [FindLocationScreen]
     * already parses the coordinates half itself before falling back to
     * this. */
    suspend fun geocode(query: String): LatLng? = locationTracker.geocodeAddress(query)

    fun savedLocationsFor(publisherPersonId: String): Flow<List<SavedLocation>> =
        savedLocationRepository.observeForPublisher(publisherPersonId)

    fun saveLocation(publisherPersonId: String, lat: Double, lng: Double, remarks: String) {
        viewModelScope.launch {
            savedLocationRepository.save(
                SavedLocation(
                    publisherPersonId = publisherPersonId,
                    lat = lat,
                    lng = lng,
                    remarks = remarks.trim(),
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    fun deleteSavedLocation(id: String) {
        viewModelScope.launch { savedLocationRepository.delete(id) }
    }

    /** "The publisher can... assign a 'Searching Record', 'Return Visit'
     * record or 'Bible Study' Record" — this Publisher's own active records
     * at [stage], to pick from in [AssignToRecordDialog]. Same
     * ownership/active scoping [com.emfitsolutions.gopreach.ui.screens
     * .pipeline.PipelineViewModel.peopleFor] already uses. */
    fun recordsFor(publisherPersonId: String, stage: PipelineStage): Flow<List<InterestedPerson>> =
        interestedPersonRepository.observeAll().map { list ->
            list.filter { it.publisherPersonId == publisherPersonId && it.pipelineStage == stage && it.status == RecordStatus.ACTIVE }
                .sortedBy { it.name }
        }

    /** Assigns the found coordinate onto an existing Searching/Return Visit/
     * Bible Study record — same fields
     * [com.emfitsolutions.gopreach.ui.screens.pipeline.PipelineViewModel
     * .saveGpsLocation] writes (that record's own "Location" section reads
     * them the same way regardless of which screen captured them).
     * [accuracyMeters] is always null here — this is a hand-typed
     * coordinate, not a live GPS fix, so there's no real accuracy figure to
     * record. */
    fun assignToRecord(person: InterestedPerson, lat: Double, lng: Double, actorPersonId: String) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            interestedPersonRepository.save(
                person.copy(gpsLat = lat, gpsLng = lng, gpsAccuracy = null, gpsCapturedAt = now, gpsCapturedBy = actorPersonId, gpsUpdatedAt = now),
            )
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "ASSIGN_FOUND_LOCATION_TO_INTERESTED_PERSON",
                targetType = "InterestedPerson",
                targetId = person.id,
                details = person.name,
            )
        }
    }
}

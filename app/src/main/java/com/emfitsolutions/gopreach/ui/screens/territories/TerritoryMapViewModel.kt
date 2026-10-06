package com.emfitsolutions.gopreach.ui.screens.territories

import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.location.LatLng
import com.emfitsolutions.gopreach.data.location.LocationTracker
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.MapPin
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.GroupRepository
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.MapPinRepository
import com.emfitsolutions.gopreach.data.repository.MapPinResult
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.domain.PermissionChecker
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.TerritoryAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.TerritoryBoundaryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/** The three kinds of record the Territory Map shows — one per
 * [PipelineStage]. [colorHex]/[emoji] give each its own marker so they can
 * never be mistaken for one another on a phone screen. */
enum class RecordType(val label: String, val shortLabel: String, val stage: PipelineStage, val colorHex: String, val emoji: String) {
    SEARCHING("Searching", "Searching", PipelineStage.SEARCHING, "#F59E0B", "🔍"),
    RETURN_VISIT("Return Visit", "RV", PipelineStage.RETURN_VISIT, "#1E88E5", "🔁"),
    BIBLE_STUDY("Bible Study", "BS", PipelineStage.BIBLE_STUDY, "#43A047", "📖"),
    ;

    companion object {
        fun of(stage: PipelineStage): RecordType = entries.first { it.stage == stage }
    }
}

/** One territory = one barangay claimed by an FS Group (see
 * [com.emfitsolutions.gopreach.data.model.TerritoryAssignmentBarangay]). */
data class TerritoryArea(
    val id: String,
    val groupId: String,
    val groupName: String,
    val provinceName: String,
    val municipality: String,
    val barangay: String,
    /** Stable PSGC ids (never just names — two municipalities can share a Barangay name). */
    val provinceId: Int = 0,
    val muncityId: Int = 0,
    val barangayId: Int = 0,
)

/** A Searching / Return Visit / Bible Study record with a saved GPS location,
 * plus the group/territory it belongs to. */
data class LocationRecord(
    val person: InterestedPerson,
    val type: RecordType,
    val lat: Double,
    val lng: Double,
    val areaId: String?,
    val territoryName: String?,
    val groupId: String?,
    val groupName: String?,
) {
    val id: String get() = person.id
    val name: String get() = person.name
    val barangay: String? get() = person.barangay?.takeIf { it.isNotBlank() }
    val municipality: String? get() = person.cityMunicipality?.takeIf { it.isNotBlank() }
    val province: String? get() = person.province?.takeIf { it.isNotBlank() }
}

/** Lower-cases and strips "City of"/"Municipality of"/punctuation so a record's
 * stored municipality/barangay name still matches a territory's own name. */
internal fun normalizePlaceName(name: String?): String =
    (name ?: "").lowercase()
        .replace(Regex("\\b(city of|municipality of|city)\\b"), " ")
        .replace(Regex("[^a-z0-9ñ ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun placeKey(municipality: String?, barangay: String?): String? {
    val m = normalizePlaceName(municipality)
    val b = normalizePlaceName(barangay)
    return if (m.isEmpty() || b.isEmpty()) null else "$m|$b"
}

/**
 * Territory Map — a field-service location finder. Follows
 * Congregation -> FS Group -> Territory Assignment -> Territory (barangay) ->
 * Searching/RV/BS records, loading only the selected FS Group's territories
 * and their records rather than every territory up front. Everything comes
 * from the existing collections (no new database).
 */
class TerritoryMapViewModel(
    private val interestedPersonRepository: InterestedPersonRepository,
    private val congregationRepository: CongregationRepository,
    private val groupRepository: GroupRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val territoryAssignmentRepository: TerritoryAssignmentRepository,
    private val territoryBoundaryRepository: TerritoryBoundaryRepository,
    private val locationTracker: LocationTracker,
    private val mapPinRepository: MapPinRepository,
    private val personRepository: PersonRepository,
) : ViewModel() {

    /** Super-Admin's Congregation choice. */
    fun congregations(): Flow<List<Congregation>> =
        congregationRepository.observeAll().map { list -> list.filter { it.status == RecordStatus.ACTIVE }.sortedBy { it.name } }

    /** FS Groups of [congregationId] (null = none yet chosen, i.e. empty). */
    fun groupsFor(congregationId: String?): Flow<List<Group>> =
        groupRepository.observeAll().map { list ->
            if (congregationId == null) emptyList()
            else list.filter { it.status == RecordStatus.ACTIVE && it.congregationId == congregationId }.sortedWith(com.emfitsolutions.gopreach.domain.GroupNameOrder)
        }

    /** The FS Group [personId] currently belongs to (Publisher or Regular
     * Elder assignment), or null if none. */
    fun myGroupId(personId: String): Flow<String?> =
        roleAssignmentRepository.observeForPerson(personId).map { list ->
            val active = list.filter { it.status == RoleAssignmentStatus.ACTIVE && it.groupId != null }
            (active.firstOrNull { it.resolvedRoleTypeOrNull() is RoleType.Publisher } ?: active.firstOrNull())?.groupId
        }

    /** The territories (claimed barangays) assigned to [groupId] - or, when [groupId] is null
     * ("Show All FS Groups"), every group's territories within [congregationId]. */
    fun areasFor(groupId: String?, congregationId: String? = null): Flow<List<TerritoryArea>> =
        combine(
            territoryAssignmentRepository.observeBarangayClaims(),
            territoryAssignmentRepository.observeAssignments(),
        ) { claims, assignments ->
            claims.filter { if (groupId != null) it.groupId == groupId else (congregationId == null || it.congregationId == congregationId) }
                .map { claim ->
                    val assignment = assignments.firstOrNull { it.id == claim.assignmentId }
                    TerritoryAssignmentAreaFactory.create(claim, assignment?.provinceName.orEmpty())
                }
                .sortedWith(compareBy({ it.municipality }, { it.barangay }))
        }

    /** The Searching/RV/BS records that belong to [groupId] — and only those.
     * Every record belongs to exactly one FS Group: the group its owning
     * Publisher is currently assigned to or, if that Publisher has no group,
     * the group whose territory contains the record's barangay. A record is
     * therefore never in two groups' views, and "All" in the type filter only
     * ever means all three types of *this* group. [areas] (the group's own
     * territories) is used only to tag each record with its territory.
     * Scoped to [congregationId] (null = Super-Admin, every congregation). */
    fun recordsFor(
        congregationId: String?,
        groupId: String?,
        areas: List<TerritoryArea>,
        /** Optional GPS locator: the territory a point lies inside. Used when the name on the record does not match one. */
        locate: (Double, Double) -> TerritoryArea? = { _, _ -> null },
    ): Flow<List<LocationRecord>> =
        combine(
            interestedPersonRepository.observeAll(),
            roleAssignmentRepository.observeAll(),
            groupRepository.observeAll(),
            territoryAssignmentRepository.observeBarangayClaims(),
        ) { people, assignments, groups, claims ->
            val areaByKey = areas.mapNotNull { a -> placeKey(a.municipality, a.barangay)?.let { it to a } }.toMap()
            // Owner -> current FS Group (a Publisher assignment wins over any other role's).
            val groupByPerson = assignments
                .filter { it.status == RoleAssignmentStatus.ACTIVE && it.groupId != null }
                .groupBy { it.personId }
                .mapValues { (_, list) -> (list.firstOrNull { it.resolvedRoleTypeOrNull() is RoleType.Publisher } ?: list.first()).groupId!! }
            // "congregation|municipality|barangay" -> owning group, for owners without a group.
            val claimGroupByKey = claims.mapNotNull { c ->
                placeKey(c.muncityName, c.barangayName)?.let { "${c.congregationId}|$it" to c.groupId }
            }.toMap()

            people.asSequence()
                .filter { it.status == RecordStatus.ACTIVE && it.hasGpsLocation }
                .filter { congregationId == null || it.congregationId == congregationId }
                .mapNotNull { person ->
                    val key = placeKey(person.cityMunicipality, person.barangay)
                    val located = locate(person.gpsLat!!, person.gpsLng!!)
                    val belongsTo = groupByPerson[person.publisherPersonId]
                        ?: key?.let { claimGroupByKey["${person.congregationId}|$it"] }
                        ?: located?.groupId
                    // A specific group: only its records. All groups (groupId == null): every record that has a group.
                    if (belongsTo == null || (groupId != null && belongsTo != groupId)) return@mapNotNull null
                    val ownArea = located ?: key?.let { areaByKey[it] }
                    LocationRecord(
                        person = person,
                        type = RecordType.of(person.pipelineStage),
                        lat = person.gpsLat!!,
                        lng = person.gpsLng!!,
                        areaId = ownArea?.id,
                        territoryName = ownArea?.barangay,
                        groupId = belongsTo,
                        groupName = groups.firstOrNull { it.id == belongsTo }?.name,
                    )
                }
                .toList()
        }

    private val boundaryCache = ConcurrentHashMap<String, String>()

    /** GeoJSON geometry for [area]'s barangay, or null if no boundary is
     * available (not an error — the territory just has no outline). */
    suspend fun boundaryFor(area: TerritoryArea): String? {
        boundaryCache[area.id]?.let { return it }
        val geometry = runCatching {
            territoryBoundaryRepository.barangayGeometry(area.provinceName, area.municipality, area.barangay)
        }.getOrNull()
        if (geometry != null) boundaryCache[area.id] = geometry
        return geometry
    }

    /** Pins dropped in [congregationId] (null = none, e.g. Super-Admin hasn't picked one yet). */
    fun pinsFor(congregationId: String?): Flow<List<MapPin>> =
        mapPinRepository.observeAll().map { list ->
            if (congregationId == null) emptyList() else list.filter { it.congregationId == congregationId }
        }

    /** Saves a pin online (the server must have it before this reports success). */
    suspend fun createPin(congregationId: String, text: String, lat: Double, lng: Double, personId: String): MapPinResult {
        val name = personRepository.observeAll().first().firstOrNull { it.id == personId }?.fullName.orEmpty()
        return mapPinRepository.create(
            MapPin(congregationId = congregationId, text = text.trim(), lat = lat, lng = lng, createdByPersonId = personId, createdByName = name),
        )
    }

    /** Whether [personId] may remove [pin]: its creator, or a role with territory access. */
    suspend fun canDeletePin(pin: MapPin, personId: String): Boolean =
        pin.createdByPersonId == personId ||
            PermissionChecker.fullCrudAssignment(roleAssignmentRepository.observeForPerson(personId).first()) != null

    suspend fun deletePin(pin: MapPin, personId: String): MapPinResult =
        if (!canDeletePin(pin, personId)) MapPinResult.Error(PermissionChecker.NO_ACCESS_MESSAGE) else mapPinRepository.delete(pin.id)

    fun hasLocationPermission(): Boolean = locationTracker.hasLocationPermission()
    fun isLocationServicesEnabled(): Boolean = locationTracker.isLocationServicesEnabled()
    suspend fun currentLocation(): LatLng? = locationTracker.getCurrentLocation()

    /** Near-real-time: a fix every ~3 s while the screen is visible (collection
     * stops when it isn't), so the "You Are Here" dot follows the user as they
     * move. The caller ignores sub-2 m jitter. */
    fun locationUpdates(): Flow<LatLng> = locationTracker.requestLocationUpdatesFlow(intervalMillis = 3_000L, minUpdateIntervalMillis = 1_000L)
}

private object TerritoryAssignmentAreaFactory {
    fun create(claim: com.emfitsolutions.gopreach.data.model.TerritoryAssignmentBarangay, provinceName: String) = TerritoryArea(
        id = claim.id,
        groupId = claim.groupId,
        groupName = claim.groupName,
        provinceName = provinceName,
        municipality = claim.muncityName,
        barangay = claim.barangayName,
        provinceId = claim.provinceId,
        muncityId = claim.muncityId,
        barangayId = claim.barangayId,
    )
}

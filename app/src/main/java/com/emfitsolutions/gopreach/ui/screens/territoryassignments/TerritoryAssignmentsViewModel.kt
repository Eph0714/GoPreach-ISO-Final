package com.emfitsolutions.gopreach.ui.screens.territoryassignments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.location.LatLng
import com.emfitsolutions.gopreach.data.location.LocationTracker
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.TerritoryAssignment
import com.emfitsolutions.gopreach.data.model.TerritoryAssignmentBarangay
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.GroupRepository
import com.emfitsolutions.gopreach.data.repository.MapDetails
import com.emfitsolutions.gopreach.data.repository.OverpassLandmarkRepository
import com.emfitsolutions.gopreach.data.repository.TerritoryAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.TerritoryAssignmentResult
import com.emfitsolutions.gopreach.data.repository.TerritoryBoundaryRepository
import com.emfitsolutions.gopreach.ui.components.map.BoundaryGeometry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** One municipality within a [GroupTerritoryRow] — [barangays] already sorted
 * by name. */
data class MunicipalityAssignment(
    val assignment: TerritoryAssignment,
    val barangays: List<TerritoryAssignmentBarangay>,
)

/** One dashboard card — a Field Service Group's whole territory **within one
 * province** ([municipalities] already sorted by name; every entry shares
 * the same [provinceId]/[provinceName], carried once at this level rather
 * than per-municipality). [group] carried whole (not just its name/color) so
 * the card can keep reading more of it later without a second lookup. A
 * Group with assignments in two different provinces shows as two separate
 * cards — see [TerritoryAssignmentRepository.saveGroupTerritoryForProvince]'s
 * own doc comment for why a save session (and therefore a card) is scoped to
 * one province. */
data class GroupTerritoryRow(
    val congregationId: String,
    val groupId: String,
    val group: Group?,
    val provinceId: Int,
    val provinceName: String,
    val municipalities: List<MunicipalityAssignment>,
) {
    val totalBarangays: Int get() = municipalities.sumOf { it.barangays.size }
}

enum class TerritorySortOption(val label: String) {
    GROUP_NAME("Field Service Group"),
    MUNICIPALITY_COUNT("Municipality count"),
    BARANGAY_COUNT("Barangay count"),
    PROVINCE("Province"),
}

class TerritoryAssignmentsViewModel(
    private val territoryAssignmentRepository: TerritoryAssignmentRepository,
    private val groupRepository: GroupRepository,
    private val territoryBoundaryRepository: TerritoryBoundaryRepository,
    private val overpassLandmarkRepository: OverpassLandmarkRepository,
    private val locationTracker: LocationTracker,
    congregationRepository: CongregationRepository,
) : ViewModel() {

    /** Only needed by a Super-Admin, who isn't scoped to one congregation
     * already — same pattern as [com.emfitsolutions.gopreach.ui.screens
     * .groups.ManageGroupsViewModel.congregations]. */
    val congregations: Flow<List<Congregation>> = congregationRepository.observeAll()

    /** [searchQuery] matches Group name, any assigned municipality's name, or
     * any assigned barangay's name — "Search by FS Group, municipality, or
     * barangay". [provinceFilter] keeps only cards in that province;
     * [sortOption] controls ordering — see [TerritorySortOption]. One card
     * per (congregation, Group, province) — a Group with territory in two
     * provinces shows as two cards, each independently searchable/sortable. */
    fun rowsFor(
        congregationId: String?,
        searchQuery: String,
        provinceFilter: Int? = null,
        sortOption: TerritorySortOption = TerritorySortOption.GROUP_NAME,
    ): Flow<List<GroupTerritoryRow>> =
        combine(
            territoryAssignmentRepository.observeAssignments(),
            territoryAssignmentRepository.observeBarangayClaims(),
            groupRepository.observeAll(),
        ) { assignments, claims, groups ->
            assignments
                .filter { congregationId == null || it.congregationId == congregationId }
                .groupBy { Triple(it.congregationId, it.groupId, it.provinceId) }
                .map { (key, groupAssignments) ->
                    val (rowCongregationId, groupId, provinceId) = key
                    GroupTerritoryRow(
                        congregationId = rowCongregationId,
                        groupId = groupId,
                        group = groups.firstOrNull { it.id == groupId },
                        provinceId = provinceId,
                        provinceName = groupAssignments.first().provinceName,
                        municipalities = groupAssignments
                            .map { assignment ->
                                MunicipalityAssignment(
                                    assignment = assignment,
                                    barangays = claims.filter { it.assignmentId == assignment.id }.sortedBy { it.barangayName },
                                )
                            }
                            .sortedBy { it.assignment.muncityName },
                    )
                }
                .filter { provinceFilter == null || it.provinceId == provinceFilter }
                .filter { row ->
                    searchQuery.isBlank() ||
                        row.group?.name?.contains(searchQuery, ignoreCase = true) == true ||
                        row.municipalities.any { it.assignment.muncityName.contains(searchQuery, ignoreCase = true) } ||
                        row.municipalities.any { m -> m.barangays.any { it.barangayName.contains(searchQuery, ignoreCase = true) } }
                }
                .let { rows ->
                    when (sortOption) {
                        TerritorySortOption.GROUP_NAME -> rows.sortedWith(com.emfitsolutions.gopreach.domain.NaturalOrder.by { it.group?.name ?: "" })
                        TerritorySortOption.MUNICIPALITY_COUNT -> rows.sortedByDescending { it.municipalities.size }
                        TerritorySortOption.BARANGAY_COUNT -> rows.sortedByDescending { it.totalBarangays }
                        TerritorySortOption.PROVINCE -> rows.sortedBy { it.provinceName }
                    }
                }
        }

    /** Real polygon boundary for one claimed barangay, for the "tap a
     * barangay -> show its boundary" map preview — see
     * [com.emfitsolutions.gopreach.data.repository.TerritoryBoundaryRepository]'s
     * own doc comment for the bundled-Nueva-Vizcaya-first, live-fetch-
     * elsewhere fallback this now runs through; still legitimately null
     * (offline, or genuinely not found anywhere), a graceful "not available
     * yet," never an error. */
    suspend fun boundaryGeometry(province: String, municipality: String, barangay: String): String? =
        territoryBoundaryRepository.barangayGeometry(province, municipality, barangay)

    /** Real named landmarks and real streets within a boundary's own
     * bounding box — see [OverpassLandmarkRepository]'s own doc comment for
     * why the native map needs these at all. Both empty on any failure or
     * genuine absence, same graceful-miss convention as every other
     * boundary lookup in this app. */
    suspend fun mapDetailsFor(geometryJson: String): MapDetails {
        val points = BoundaryGeometry.outerRings(geometryJson).flatten()
        if (points.isEmpty()) return MapDetails(emptyList(), emptyList())
        val minLat = points.minOf { it.first }
        val maxLat = points.maxOf { it.first }
        val minLng = points.minOf { it.second }
        val maxLng = points.maxOf { it.second }
        val latPad = (maxLat - minLat).coerceAtLeast(0.001) * 0.2
        val lngPad = (maxLng - minLng).coerceAtLeast(0.001) * 0.2
        return overpassLandmarkRepository.detailsIn(minLat - latPad, minLng - lngPad, maxLat + latPad, maxLng + lngPad)
    }

    /** "Add my location, then compare the distance to the selected barangay"
     * — thin pass-through to the shared [LocationTracker] (same fused-location
     * approach Share Location already uses) so [BarangayBoundaryDialog] can
     * check/request permission and fetch a fix without reaching into
     * infrastructure directly from a Composable. */
    fun hasLocationPermission(): Boolean = locationTracker.hasLocationPermission()

    fun isLocationServicesEnabled(): Boolean = locationTracker.isLocationServicesEnabled()

    suspend fun currentLocation(): LatLng? = locationTracker.getCurrentLocation()

    /** "Make my location live and blinking" — a continuous subscription
     * (same mechanism Share Location's own live tracking uses) rather than
     * repeated one-shot [currentLocation] polling, so the marker moves as
     * the device does instead of staying frozen at whatever fix was current
     * when the user tapped the button. */
    // Near-real-time (a fix every ~3 s) so "You Are Here" follows the user live.
    fun locationUpdates(): Flow<LatLng> = locationTracker.requestLocationUpdatesFlow(intervalMillis = 3_000L, minUpdateIntervalMillis = 1_000L)

    private val _removeResult = MutableStateFlow<TerritoryAssignmentResult?>(null)
    val removeResult: StateFlow<TerritoryAssignmentResult?> = _removeResult

    fun consumeRemoveResult() {
        _removeResult.value = null
    }

    /** Removes just one municipality from a Group's territory — the
     * expanded-card per-municipality action. */
    fun remove(assignmentId: String, congregationId: String, actorPersonId: String) {
        viewModelScope.launch {
            _removeResult.value = territoryAssignmentRepository.removeAssignment(assignmentId, congregationId, actorPersonId)
        }
    }

    /** Removes a whole [GroupTerritoryRow] — every municipality this Group
     * holds in this province — the card-level "remove entire assignment"
     * action. */
    fun removeGroup(congregationId: String, groupId: String, provinceId: Int, actorPersonId: String) {
        viewModelScope.launch {
            _removeResult.value = territoryAssignmentRepository.removeGroupTerritory(congregationId, groupId, provinceId, actorPersonId)
        }
    }
}

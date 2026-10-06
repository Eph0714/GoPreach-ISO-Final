package com.emfitsolutions.gopreach.ui.components.map

import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.model.DrawingSyncState
import com.emfitsolutions.gopreach.data.model.TerritoryDrawing
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.DrawingPermissionService
import com.emfitsolutions.gopreach.data.repository.GroupRepository
import com.emfitsolutions.gopreach.data.repository.TerritoryDrawingRepository
import com.emfitsolutions.gopreach.domain.GroupAccessScope
import com.emfitsolutions.gopreach.domain.map.DrawingAccess
import com.emfitsolutions.gopreach.domain.map.DrawingGeometry
import com.emfitsolutions.gopreach.domain.map.DrawingValidation
import com.emfitsolutions.gopreach.domain.map.DrawingValidator
import com.emfitsolutions.gopreach.domain.map.TerritoryBoundary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Result of trying to save a Drawing Mode session. */
sealed interface DrawingSaveOutcome {
    /** Everything was saved locally (it syncs to the server when internet is available). */
    data class Saved(val count: Int) : DrawingSaveOutcome

    /** A required field is missing (polygon status): nothing was saved. */
    data class Incomplete(val message: String) : DrawingSaveOutcome

    /** Nothing was saved: [validation] says why (never [DrawingValidation.Valid]). */
    data class Rejected(val validation: DrawingValidation) : DrawingSaveOutcome
}

/**
 * The one ViewModel behind every map's drawing tools ([MapDrawingOverlay]): who may
 * draw, the live drawings and their sync state, and save/edit/delete through the
 * offline-first [TerritoryDrawingRepository]. Map modules never talk to the
 * repository themselves, which is what keeps every module behaving identically.
 */
@HiltViewModel
class MapDrawingViewModel @Inject constructor(
    private val repository: TerritoryDrawingRepository,
    private val permissionService: DrawingPermissionService,
    private val groupRepository: GroupRepository,
    private val congregationRepository: CongregationRepository,
) : ViewModel() {

    fun access(personId: String): Flow<DrawingAccess> = permissionService.observeAccess(personId)
    fun drawings(): Flow<List<TerritoryDrawing>> = repository.observeAll()
    fun syncStates(): Flow<Map<String, DrawingSyncState>> = repository.observeSyncStates()
    fun groups(): Flow<List<Group>> = groupRepository.observeAll()
    fun congregations(): Flow<List<Congregation>> = congregationRepository.observeAll()

    /**
     * Validates every polygon of [draft] and, only if all pass, saves them. Validation happens
     * *before* anything is written, so a drawing that leaves the user's territory is never
     * stored — locally or on the server.
     */
    suspend fun save(access: DrawingAccess, draft: List<DraftPolygon>, territories: List<TerritoryBoundary>, congregationId: String): DrawingSaveOutcome {
        // The required status, enforced here too so no caller can save a polygon without one.
        if (draft.any { it.status == null }) return DrawingSaveOutcome.Incomplete("Select a territory status first.")
        val validated = draft.map { item ->
            val v = DrawingValidator.validatePolygon(access, item.ring, territories, congregationId)
            if (v !is DrawingValidation.Valid) return DrawingSaveOutcome.Rejected(v)
            item to v
        }
        validated.forEach { (item, v) ->
            val territory = v.territory
            val role = access.roleLabelFor(territory?.groupId)
            val existing = item.existing
            val built = build(item, territory, v.congregationId, access, role)
            if (existing == null) repository.create(built, role)
            else repository.update(existing, built, access.personId, access.personName, role)
        }
        return DrawingSaveOutcome.Saved(validated.size)
    }

    /** Changes a polygon's status; its color follows automatically (Finished green, To Continue amber, To Do red). */
    suspend fun changeStatus(access: DrawingAccess, drawing: TerritoryDrawing, status: com.emfitsolutions.gopreach.data.model.DrawingStatus) {
        val edited = drawing.copy(status = status, fillColor = status.colorHex, borderColor = status.borderHex)
        repository.update(drawing, edited, access.personId, access.personName, access.roleLabelFor(drawing.groupId))
    }

    suspend fun delete(access: DrawingAccess, drawing: TerritoryDrawing) {
        repository.delete(drawing, access.personId, access.roleLabelFor(drawing.groupId))
    }

    suspend fun retrySync(drawingId: String) = repository.retrySync(drawingId)

    /**
     * Congregation-wide roles publish each loaded territory's bounding box so the server can enforce
     * the group-level boundary rule (see firestore.rules `territoryBounds`). A no-op for everyone else —
     * group-level users must never be able to write their own limits.
     */
    suspend fun publishBounds(access: DrawingAccess, territories: List<TerritoryBoundary>) {
        if (access.scope !is GroupAccessScope.Congregation && access.scope != GroupAccessScope.AllCongregations) return
        territories.forEach { t ->
            if (t.rings.isNotEmpty()) repository.publishBounds(t.territoryId, t.congregationId, t.groupId, t.rings)
        }
    }

    private fun build(item: DraftPolygon, territory: TerritoryBoundary?, congregationId: String, access: DrawingAccess, role: String): TerritoryDrawing {
        val b = DrawingGeometry.bounds(item.ring)
        return TerritoryDrawing(
            id = item.existing?.id.orEmpty(),
            geometryJson = DrawingGeometry.polygonJson(item.ring),
            fillColor = item.fillColor, // the status color
            fillOpacity = item.opacity.toDouble(),
            borderColor = item.borderColor,
            status = item.status ?: com.emfitsolutions.gopreach.data.model.DrawingStatus.FINISHED,
            remarks = item.remarks.trim(),
            name = item.name.trim(),
            userId = access.personId, userName = access.personName, userRole = role,
            congregationId = congregationId, groupId = territory?.groupId.orEmpty(),
            territoryId = territory?.territoryId.orEmpty(), territoryName = territory?.name.orEmpty(),
            provinceId = territory?.provinceId ?: 0, muncityId = territory?.muncityId ?: 0, barangayId = territory?.barangayId ?: 0,
            minLat = b.minLat, maxLat = b.maxLat, minLng = b.minLng, maxLng = b.maxLng,
        )
    }
}

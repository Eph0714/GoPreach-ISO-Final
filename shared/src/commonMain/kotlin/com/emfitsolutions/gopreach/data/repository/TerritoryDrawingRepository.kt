package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.local.dao.CacheDao
import com.emfitsolutions.gopreach.data.model.DrawingAction
import com.emfitsolutions.gopreach.data.model.DrawingSyncState
import com.emfitsolutions.gopreach.data.model.SyncState
import com.emfitsolutions.gopreach.data.model.TerritoryBounds
import com.emfitsolutions.gopreach.data.model.TerritoryDrawing
import com.emfitsolutions.gopreach.data.model.TerritoryDrawingAudit
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import com.emfitsolutions.gopreach.platform.nowMillis
import com.emfitsolutions.gopreach.data.sync.NetworkStatus
import com.emfitsolutions.gopreach.domain.map.DrawingGeometry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

private const val DRAWINGS = "territoryDrawings"
private const val AUDITS = "territoryDrawingAudits"
private const val BOUNDS = "territoryBounds"

/**
 * Territory map drawings (completed-area polygons and pins) — the drawing half
 * of the map system, shared by every map module that allows drawing.
 *
 * Offline-first, unlike the older [MapPinRepository]: every write goes through
 * [OfflineFirestoreRepository] (saved to the local cache immediately, queued,
 * flushed by the SyncWorker when internet is available), so a drawing made in
 * the field with no signal shows on the map straight away as "Pending Sync" and
 * uploads later. A rejected upload (e.g. a Firestore rules denial) leaves the
 * drawing in place as "Sync Failed" — [retrySync] re-queues it as it is, so the
 * user never has to redraw it.
 *
 * Every change also appends a [TerritoryDrawingAudit] row.
 */
class TerritoryDrawingRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
    private val cacheDao: CacheDao,
    private val network: NetworkStatus,
) {
    fun observeAll(): Flow<List<TerritoryDrawing>> = offline.observeCollection(DRAWINGS)

    /**
     * This device's sync state per drawing id: a row the server has is
     * [DrawingSyncState.SYNCED]; one still queued is [DrawingSyncState.PENDING_SYNC]
     * ([DrawingSyncState.SYNCING] while the upload worker is running); one the
     * server rejected or that failed to upload is [DrawingSyncState.SYNC_FAILED].
     */
    fun observeSyncStates(): Flow<Map<String, DrawingSyncState>> =
        combine(cacheDao.observeSyncStates(DRAWINGS), network.isSyncing) { rows, syncing ->
            rows.associate { row ->
                row.documentId to when (row.syncState) {
                    SyncState.SYNCED.name -> DrawingSyncState.SYNCED
                    SyncState.FAILED.name -> DrawingSyncState.SYNC_FAILED
                    else -> if (syncing) DrawingSyncState.SYNCING else DrawingSyncState.PENDING_SYNC
                }
            }
        }.distinctUntilChanged()

    /** A fresh id — generated locally by the Firestore SDK, so it works offline. */
    fun newId(): String = remote.newId(DRAWINGS)

    /** Saves a brand-new drawing ([TerritoryDrawing.id] may be blank; one is assigned). */
    suspend fun create(drawing: TerritoryDrawing, role: String): TerritoryDrawing {
        val now = nowMillis()
        val saved = drawing.copy(
            id = drawing.id.ifBlank { newId() },
            createdAt = now,
            updatedAt = now,
            updatedByUserId = drawing.userId,
            updatedByName = drawing.userName,
        )
        offline.save(DRAWINGS, saved.id, saved)
        audit(DrawingAction.CREATED, saved, role, original = null, updated = saved)
        return saved
    }

    /** Saves an edit of [old]. The audit action is derived from what actually changed (one row per change). */
    suspend fun update(old: TerritoryDrawing, edited: TerritoryDrawing, editorId: String, editorName: String, role: String): TerritoryDrawing {
        val saved = edited.copy(id = old.id, createdAt = old.createdAt, userId = old.userId, userName = old.userName, userRole = old.userRole,
            updatedAt = nowMillis(), updatedByUserId = editorId, updatedByName = editorName)
        offline.save(DRAWINGS, saved.id, saved)
        val moved = old.geometryJson != saved.geometryJson
        val recolored = old.fillColor != saved.fillColor || old.fillOpacity != saved.fillOpacity || old.borderColor != saved.borderColor
        val restatus = old.status != saved.status
        val actions = buildList {
            if (moved) add(DrawingAction.MOVED)
            if (recolored) add(DrawingAction.COLOR_CHANGED)
            if (restatus) add(DrawingAction.STATUS_CHANGED)
            if (old.remarks != saved.remarks) add(DrawingAction.UPDATED)
            if (isEmpty()) add(DrawingAction.UPDATED)
        }
        actions.forEach { audit(it, saved, role, original = old, updated = saved, actorId = editorId) }
        return saved
    }

    suspend fun delete(drawing: TerritoryDrawing, editorId: String, role: String) {
        offline.delete(DRAWINGS, drawing.id)
        audit(DrawingAction.DELETED, drawing, role, original = drawing, updated = null, actorId = editorId)
    }

    /**
     * Re-queues a drawing whose upload failed, exactly as it is stored locally — nothing is
     * redrawn. Also clears the old "permanent failure" row, so it gets a clean attempt.
     */
    suspend fun retrySync(drawingId: String) {
        val local = offline.get<TerritoryDrawing>(DRAWINGS, drawingId) ?: return
        offline.save(DRAWINGS, drawingId, local)
    }

    private suspend fun audit(
        action: DrawingAction,
        drawing: TerritoryDrawing,
        role: String,
        original: TerritoryDrawing?,
        updated: TerritoryDrawing?,
        actorId: String = drawing.userId,
    ) {
        val row = TerritoryDrawingAudit(
            id = remote.newId(AUDITS),
            drawingId = drawing.id,
            action = action,
            userId = actorId,
            userRole = role,
            congregationId = drawing.congregationId,
            groupId = drawing.groupId,
            territoryId = drawing.territoryId,
            originalGeometryJson = original?.geometryJson,
            updatedGeometryJson = updated?.geometryJson,
            fillColor = (updated ?: original)?.let { it.fillColor },
            fillOpacity = (updated ?: original)?.fillOpacity,
            status = (updated ?: original)?.status,
            at = nowMillis(),
            syncInfo = if (network.isOnline()) "SYNCED" else "PENDING",
        )
        offline.save(AUDITS, row.id, row)
    }

    // ---- territory bounds (server-side enforcement data) ------------------------

    fun observeBounds(): Flow<List<TerritoryBounds>> = offline.observeCollection(BOUNDS)

    /**
     * Publishes [territoryId]'s bounding box so the server can enforce the group-level boundary rule.
     * Only congregation-wide roles are allowed to write these (see firestore.rules); callers must check.
     */
    suspend fun publishBounds(territoryId: String, congregationId: String, groupId: String, rings: List<List<com.emfitsolutions.gopreach.domain.map.GeoPoint>>) {
        val all = rings.flatten()
        if (all.isEmpty()) return
        val b = DrawingGeometry.bounds(all)
        val existing = offline.get<TerritoryBounds>(BOUNDS, territoryId)
        if (existing != null && existing.groupId == groupId && existing.congregationId == congregationId &&
            existing.minLat == b.minLat && existing.maxLat == b.maxLat && existing.minLng == b.minLng && existing.maxLng == b.maxLng
        ) return
        offline.save(
            BOUNDS, territoryId,
            TerritoryBounds(territoryId, congregationId, groupId, b.minLat, b.maxLat, b.minLng, b.maxLng, nowMillis()),
        )
    }

    // ---- live mirrors ------------------------------------------------------------

    fun startRemoteSync(): Flow<Unit> =
        remote.mirror(DRAWINGS, TerritoryDrawing::class) { it.id }

    fun startBoundsRemoteSync(): Flow<Unit> =
        remote.mirror(BOUNDS, TerritoryBounds::class) { it.id }
}


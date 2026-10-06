package com.emfitsolutions.gopreach.data.model

import com.emfitsolutions.gopreach.platform.DocumentId


/**
 * Territory-work status of a drawn polygon — and, through [colorHex], its color. The color is
 * fixed per status so it means the same thing on every map: green = Finished, amber = To Continue,
 * red = To Do. Never user-chosen.
 */
@kotlinx.serialization.Serializable
enum class DrawingStatus(val label: String, val colorHex: String, val borderHex: String, val meaning: String) {
    FINISHED("Finished", "#43A047", "#1B5E20", "Already worked"),
    TO_CONTINUE("To Continue", "#FBC02D", "#B26A00", "Started, more work needed"),
    TO_DO("To Do", "#E53935", "#8E1B17", "Not yet worked"),
}

/**
 * One freehand polygon a user drew over part of a territory, with its own work [status] and
 * [remarks]. Geometry is stored as a GeoJSON `Polygon` string ([geometryJson], `[lng, lat]` order,
 * the same convention the app's bundled boundaries already use) — real geographic coordinates,
 * never screen coordinates, so a drawing stays put through zoom/pan/resize/reopen and on any
 * other device. Status and remarks belong to the drawing itself.
 *
 * [minLat]..[maxLng] is the geometry's bounding box. It exists so Firestore
 * security rules — which cannot loop over coordinates — can still check that a
 * group-level user's drawing falls inside the bounding box of their territory
 * ([TerritoryBounds]); the exact polygon check happens in
 * `DrawingValidator` before saving.
 *
 * Sync state (Pending Sync / Syncing / Synced / Sync Failed) is deliberately
 * NOT a field here: it is a property of this device's copy, derived from the
 * offline cache + outbox (see `TerritoryDrawingRepository.observeSyncStates`).
 *
 * Firestore collection: `territoryDrawings/{id}`
 */
@kotlinx.serialization.Serializable
data class TerritoryDrawing(
    @DocumentId val id: String = "",
    val geometryJson: String = "",
    /** Fill, "#RRGGBB" — always the [status] color (never user-chosen). */
    val fillColor: String = DrawingStatus.FINISHED.colorHex,
    /** Fill opacity, 0..1. New drawings start at 0.10. */
    val fillOpacity: Double = 0.10,
    /** Outline, "#RRGGBB" — the stronger shade of the status color. */
    val borderColor: String = DrawingStatus.FINISHED.borderHex,
    val status: DrawingStatus = DrawingStatus.FINISHED,
    /** The drawing's own name (optional), e.g. "East of the highway". */
    val name: String = "",
    /** Free multi-line remarks, editable after saving. */
    val remarks: String = "",
    val userId: String = "",
    val userName: String = "",
    /** Display label of the role the drawing was made under, e.g. "Group Overseer". */
    val userRole: String = "",
    val congregationId: String = "",
    val groupId: String = "",
    /** The territory (`territoryAssignmentBarangays` id) the drawing sits in. */
    val territoryId: String = "",
    val territoryName: String = "",
    /** Stable PSGC ids of the Barangay (Province → Municipality → Barangay); 0 for a drawing in an unassigned area. */
    val provinceId: Int = 0,
    val muncityId: Int = 0,
    val barangayId: Int = 0,
    val minLat: Double = 0.0,
    val maxLat: Double = 0.0,
    val minLng: Double = 0.0,
    val maxLng: Double = 0.0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val updatedByUserId: String = "",
    val updatedByName: String = "",
)

/** Every kind of change the audit trail records. */
@kotlinx.serialization.Serializable
enum class DrawingAction { CREATED, UPDATED, COLOR_CHANGED, STATUS_CHANGED, MOVED, DELETED, RESTORED }

/**
 * One audit-trail row for a drawing action (spec: who, which role, where, what
 * changed, when, and whether it had reached the server at that moment).
 *
 * Firestore collection: `territoryDrawingAudits/{id}` — create-only.
 */
@kotlinx.serialization.Serializable
data class TerritoryDrawingAudit(
    @DocumentId val id: String = "",
    val drawingId: String = "",
    val action: DrawingAction = DrawingAction.CREATED,
    val userId: String = "",
    val userRole: String = "",
    val congregationId: String = "",
    val groupId: String = "",
    val territoryId: String = "",
    val originalGeometryJson: String? = null,
    val updatedGeometryJson: String? = null,
    val fillColor: String? = null,
    val fillOpacity: Double? = null,
    val status: DrawingStatus? = null,
    val at: Long = 0L,
    /** "SYNCED" when the device was online as the action was queued, else "PENDING". */
    val syncInfo: String = "PENDING",
)

/**
 * The bounding box of one territory, published to the server so Firestore
 * rules can enforce "a group-level user's drawing must sit inside their
 * territory" without Cloud Functions. Written only by congregation-wide roles
 * (the ones allowed to manage territory assignments), never by the group-level
 * users it constrains.
 *
 * Firestore collection: `territoryBounds/{territoryId}`
 */
@kotlinx.serialization.Serializable
data class TerritoryBounds(
    @DocumentId val id: String = "",
    val congregationId: String = "",
    val groupId: String = "",
    val minLat: Double = 0.0,
    val maxLat: Double = 0.0,
    val minLng: Double = 0.0,
    val maxLng: Double = 0.0,
    val updatedAt: Long = 0L,
)

/** Per-device sync progress of one drawing. */
@kotlinx.serialization.Serializable
enum class DrawingSyncState(val label: String) {
    SYNCED("Synced"),
    PENDING_SYNC("Pending Sync"),
    SYNCING("Syncing"),
    SYNC_FAILED("Sync Failed"),
}

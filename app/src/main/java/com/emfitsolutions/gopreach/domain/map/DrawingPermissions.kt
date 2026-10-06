package com.emfitsolutions.gopreach.domain.map

import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.model.TerritoryDrawing
import com.emfitsolutions.gopreach.data.model.displayLabel
import com.emfitsolutions.gopreach.domain.GroupAccessScope
import com.emfitsolutions.gopreach.domain.allows

/** One territory (a claimed barangay) with its real boundary rings. */
data class TerritoryBoundary(
    val territoryId: String,
    val name: String,
    val groupId: String,
    val congregationId: String,
    val rings: List<List<GeoPoint>>,
    val provinceId: Int = 0,
    val muncityId: Int = 0,
    val barangayId: Int = 0,
)

/**
 * Who the signed-in user is as far as map drawing goes: their widest
 * [GroupAccessScope] (so the permission matrix is the app's existing FS Group
 * hierarchy, not a second one) plus the label stamped on what they draw.
 *
 *  - Group Overseer / Servant / Assistant → [GroupAccessScope.OwnGroup]: only inside the
 *    territory of the FS Group whose slot they hold.
 *  - Coordinator Elder / Secretary / Service Overseer / Admin → [GroupAccessScope.Congregation]: anywhere
 *    on their own congregation's map, FS Group boundaries or not.
 *  - Super Admin → [GroupAccessScope.AllCongregations]: anywhere, on the congregation selected.
 */
class DrawingAccess(
    val personId: String,
    val personName: String,
    val scope: GroupAccessScope,
    private val baseRoleLabel: String,
    groups: List<Group>,
) {
    private val groupsById = groups.associateBy { it.id }

    /** True when the strict "must be geometrically inside the assigned territory" rule applies. */
    val isGroupLevel: Boolean get() = scope is GroupAccessScope.OwnGroup

    /** Whether Drawing Mode may be offered to this user at all. */
    val canUseDrawingTools: Boolean
        get() = when (scope) {
            GroupAccessScope.None -> false
            is GroupAccessScope.OwnGroup -> groupsById.values.any { scope.allows(it) }
            else -> true
        }

    fun canDrawIn(groupId: String): Boolean {
        val group = groupsById[groupId]
        return when (scope) {
            GroupAccessScope.AllCongregations -> true
            GroupAccessScope.None -> false
            else -> group != null && scope.allows(group)
        }
    }

    /** May this user draw on [congregationId]'s map at all (congregation-wide roles: their own only). */
    fun canDrawInCongregation(congregationId: String): Boolean = when (scope) {
        GroupAccessScope.AllCongregations -> true
        is GroupAccessScope.Congregation -> scope.congregationId == congregationId
        is GroupAccessScope.OwnGroup -> groupsById.values.any { it.congregationId == congregationId && scope.allows(it) }
        GroupAccessScope.None -> false
    }

    /** Whether [territory] is one this user may draw in (group-level: only their own FS Group's). */
    fun canDrawInTerritory(territory: TerritoryBoundary): Boolean = when (scope) {
        GroupAccessScope.AllCongregations -> true
        is GroupAccessScope.Congregation -> scope.congregationId == territory.congregationId
        is GroupAccessScope.OwnGroup -> canDrawIn(territory.groupId)
        GroupAccessScope.None -> false
    }

    /**
     * The only area a group-level user may draw in — the union of their FS Group's territory rings — or null for
     * everyone else, who may draw anywhere on their congregation's map (including unassigned areas).
     */
    fun restrictedRings(territories: List<TerritoryBoundary>): List<List<GeoPoint>>? =
        if (isGroupLevel) territories.filter { canDrawInTerritory(it) }.flatMap { it.rings } else null

    /** Whether the long-press spot [point] on [congregationId]'s map offers "Draw". */
    fun canDrawAt(point: GeoPoint, territories: List<TerritoryBoundary>, congregationId: String): Boolean {
        if (!canUseDrawingTools || !canDrawInCongregation(congregationId)) return false
        val rings = restrictedRings(territories) ?: return true
        return DrawingGeometry.isAllowed(rings, point, DrawingValidator.EDGE_TOLERANCE_METERS)
    }

    /** Edit / re-status / delete: group-level users within their FS Group; congregation-wide roles within their congregation. */
    fun canManage(drawing: TerritoryDrawing): Boolean = when (scope) {
        GroupAccessScope.AllCongregations -> true
        is GroupAccessScope.Congregation -> scope.congregationId == drawing.congregationId
        is GroupAccessScope.OwnGroup -> canDrawIn(drawing.groupId)
        GroupAccessScope.None -> false
    }

    /** Role label to stamp on a drawing made in [groupId] ("Group Overseer" for the slot holder of that group, else the base role). */
    fun roleLabelFor(groupId: String?): String {
        val group = groupId?.let { groupsById[it] }
        return when {
            scope is GroupAccessScope.OwnGroup && group != null -> when (personId) {
                group.overseerPersonId -> "Group Overseer"
                group.servantPersonId -> "Group Servant"
                group.assistantPersonId -> "Group Assistant"
                else -> baseRoleLabel
            }
            else -> baseRoleLabel
        }
    }

    companion object {
        fun resolve(person: Person?, assignments: List<RoleAssignment>, groups: List<Group>): DrawingAccess {
            val scope = GroupAccessScope.resolve(person, assignments)
            val adminRoles = assignments
                .filter { it.status == RoleAssignmentStatus.ACTIVE }
                .mapNotNull { (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role }
            // Widest role first, so a Coordinator Elder who is also a Group Overseer is stamped as the former.
            val widest = listOf(
                AdminRole.SUPER_ADMIN, AdminRole.COORDINATOR_ELDER, AdminRole.SERVICE_OVERSEER,
                AdminRole.SECRETARY, AdminRole.ADMIN_PER_CONGREGATION,
            ).firstOrNull { it in adminRoles }
            val label = when {
                person?.isSuperAdmin == true -> AdminRole.SUPER_ADMIN.displayLabel()
                widest != null -> widest.displayLabel()
                else -> "Group Member"
            }
            return DrawingAccess(person?.id.orEmpty(), person?.fullName.orEmpty(), scope, label, groups)
        }

        /** No permissions at all — what the map uses before the user's roles have loaded. */
        val NONE = DrawingAccess("", "", GroupAccessScope.None, "", emptyList())
    }
}

/** Outcome of checking a drawing against the user's permissions and the territory boundaries. */
sealed interface DrawingValidation {
    /**
     * Allowed. It is saved on [congregationId]'s map; [territory] is the FS Group territory it sits in, or null
     * for a congregation-wide role drawing in an area not (yet) assigned to any FS Group.
     */
    data class Valid(val territory: TerritoryBoundary?, val congregationId: String) : DrawingValidation

    /** A group-level user's drawing leaves their assigned territory; [outsideRuns] are the offending outline parts. */
    data class OutsideAssigned(val outsideRuns: List<List<GeoPoint>>) : DrawingValidation

    /** The drawing is on another congregation's map. */
    data object OtherCongregation : DrawingValidation

    /** The polygon crosses over its own outline. */
    data object SelfIntersecting : DrawingValidation

    /** The user has no drawing permission at all. */
    data object NotAllowed : DrawingValidation
}

/**
 * The client half of the drawing rule: User Role → Congregation → FS Group → Authorized Drawing Area.
 * The same chain is repeated on the server in firestore.rules (role + congregation + FS Group slot +
 * territory membership + the territory's bounding box); this exact-polygon check is what runs before a
 * drawing is ever saved or queued.
 *
 *  - Group Overseer / Servant / Assistant: the whole outline must lie inside their FS Group's territory.
 *  - Coordinator Elder / Secretary / Service Overseer / Admin: anywhere on their own congregation's map,
 *    FS Group boundaries or not (unassigned areas included); never another congregation's.
 *  - Super Admin: anywhere, on the congregation currently selected ([congregationId]).
 */
object DrawingValidator {
    /** Slack allowed along a boundary — a fingertip tracing the edge lands a few meters over it. */
    const val EDGE_TOLERANCE_METERS = 3.0

    fun validatePolygon(
        access: DrawingAccess,
        ring: List<GeoPoint>,
        territories: List<TerritoryBoundary>,
        congregationId: String,
    ): DrawingValidation {
        if (!access.canUseDrawingTools) return DrawingValidation.NotAllowed
        if (!DrawingGeometry.isSimple(ring)) return DrawingValidation.SelfIntersecting
        if (!access.canDrawInCongregation(congregationId)) return DrawingValidation.OtherCongregation
        val anchor = DrawingGeometry.interiorPoint(ring)
        val restricted = access.restrictedRings(territories)
        if (restricted != null) {
            val runs = DrawingGeometry.outsideRuns(ring, restricted, EDGE_TOLERANCE_METERS)
            if (runs.isNotEmpty()) return DrawingValidation.OutsideAssigned(runs)
        } else if (access.scope != GroupAccessScope.AllCongregations && territories.any { it.congregationId != congregationId && it.rings.any { r -> DrawingGeometry.contains(r, anchor) } }) {
            return DrawingValidation.OtherCongregation
        }
        val permitted = territories.filter { access.canDrawInTerritory(it) && it.congregationId == congregationId }
        val home = permitted.firstOrNull { t -> t.rings.any { DrawingGeometry.contains(it, anchor) } }
            ?: permitted.firstOrNull { t -> ring.any { v -> t.rings.any { DrawingGeometry.contains(it, v) } } }
        if (home == null && access.isGroupLevel) return DrawingValidation.OutsideAssigned(emptyList())
        return DrawingValidation.Valid(home, congregationId)
    }
}

package com.emfitsolutions.gopreach.data.sync

import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.MonthlyReport
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.ScopeType
import com.emfitsolutions.gopreach.data.model.UserAccessGrant
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.tasks.await

/** What a grant-based (Circuit Overseer) account is allowed to download. */
data class RestrictedScope(val allCongregations: Boolean, val congregationIds: List<String>, val circuit: Boolean = false)

/**
 * Firestore only answers a list query if its security rules can be proven true for **every** document the
 * query could return — a rule is a gate, not a filter. For a Circuit Overseer (a grant-based account) that
 * means the app's normal "listen to the whole collection" mirrors are refused (`PERMISSION_DENIED`); the same
 * collections must instead be asked for *only* the documents the grant covers (`congregationId in [...]`, or
 * "my own document"), which the rules can prove. Verified against the rules emulator in
 * `firestore-tests/circuit.rules.test.mjs`.
 *
 * Firestore also caps an `in` filter at 30 values, so a long congregation list is mirrored in chunks; each
 * chunk gets its own registration key so they don't replace one another (see [mirrorFirestoreCollection]).
 */
class RestrictedSessionSync(
    private val firestore: FirebaseFirestore,
    private val offline: OfflineFirestoreRepository,
    private val appScope: CoroutineScope,
) {
    /** The grant's scope from the server, or null if [personId] has no grant (an ordinary account). */
    suspend fun resolve(personId: String): RestrictedScope? {
        val snap = firestore.collection("userAccessGrants").document(personId).get().await()
        if (!snap.exists()) return null
        val grant = snap.toObject(UserAccessGrant::class.java) ?: return null
        return RestrictedScope(
            allCongregations = grant.resolvedScopeType == ScopeType.ALL_CONGREGATIONS,
            congregationIds = grant.scopeCongregationIds,
            circuit = !grant.circuitCode.isNullOrBlank(),
        )
    }

    /** The caller's own grant document — the one grant they may read. */
    fun ownGrant(personId: String): Flow<Unit> = mirrorFirestoreCollection(
        firestore, offline, appScope, "userAccessGrants", UserAccessGrant::class.java,
        query = firestore.collection("userAccessGrants").whereEqualTo(FieldPath.documentId(), personId),
        registrationKey = "userAccessGrants#own",
    ) { it.personId }

    /** The caller's own role assignment(s) — how the session learns it is a Circuit Overseer. */
    fun ownRoleAssignments(personId: String): Flow<Unit> = mirrorFirestoreCollection(
        firestore, offline, appScope, "roleAssignments", RoleAssignment::class.java,
        query = firestore.collection("roleAssignments").whereEqualTo("personId", personId),
        registrationKey = "roleAssignments#own",
    ) { it.id }

    /** Congregations, groups, role assignments and monthly reports of [congregationIds] only. */
    fun forCongregations(congregationIds: List<String>, circuit: Boolean = false): Flow<Unit> {
        if (congregationIds.isEmpty()) return emptyFlow()
        val chunks = congregationIds.distinct().chunked(MAX_IN_VALUES)
        val flows = chunks.flatMapIndexed { i, chunk ->
            listOf(
                mirrorFirestoreCollection(
                    firestore, offline, appScope, "congregations", Congregation::class.java,
                    query = firestore.collection("congregations").whereIn(FieldPath.documentId(), chunk),
                    registrationKey = "congregations#$i",
                ) { it.id },
                mirrorFirestoreCollection(
                    firestore, offline, appScope, "groups", Group::class.java,
                    query = firestore.collection("groups").whereIn("congregationId", chunk),
                    registrationKey = "groups#$i",
                ) { it.id },
                mirrorFirestoreCollection(
                    firestore, offline, appScope, "roleAssignments", RoleAssignment::class.java,
                    query = firestore.collection("roleAssignments").whereIn("congregationId", chunk),
                    registrationKey = "roleAssignments#$i",
                ) { it.id },
                // A Circuit Overseer's records of a month are loaded on demand, once the month is known to be submitted
                // (see [monthRecords]); every other grant account keeps the whole-congregation mirror.
                if (circuit) byCongregation("coFieldServiceReportEvents", com.emfitsolutions.gopreach.data.model.CoReportEvent::class.java, i, chunk) { it.id }
                else byCongregation("monthlyReports", MonthlyReport::class.java, i, chunk) { it.id },
                // Meeting attendance (read-only for a Circuit Overseer): the weekly records and their audit trail.
                byCongregation("meetingAttendance", com.emfitsolutions.gopreach.data.model.MeetingAttendance::class.java, i, chunk) { it.id },
                byCongregation("meetingAttendanceEvents", com.emfitsolutions.gopreach.data.model.MeetingAttendanceEvent::class.java, i, chunk) { it.id },
                // Comparative Reports: a Circuit Overseer sees only the ones sent to them (never a draft), so each listener pins the status too.
                *comparativeReports(i, chunk).toTypedArray(),
                // Territory Map data (view-only for a Circuit Overseer).
                byCongregation("territoryAssignments", com.emfitsolutions.gopreach.data.model.TerritoryAssignment::class.java, i, chunk) { it.id },
                byCongregation("territoryAssignmentBarangays", com.emfitsolutions.gopreach.data.model.TerritoryAssignmentBarangay::class.java, i, chunk) { it.id },
                byCongregation("territoryBounds", com.emfitsolutions.gopreach.data.model.TerritoryBounds::class.java, i, chunk) { it.id },
                byCongregation("territoryDrawings", com.emfitsolutions.gopreach.data.model.TerritoryDrawing::class.java, i, chunk) { it.id },
                byCongregation("mapPins", com.emfitsolutions.gopreach.data.model.MapPin::class.java, i, chunk) { it.id },
            )
        }
        return merge(*flows.toTypedArray())
    }

    /**
     * The actual Field Service Report records of ONE submitted service month of one congregation, for a Circuit Overseer.
     * The security rules open a month's records to the overseer only once its status is Submitted / Received / Returned and
     * can only prove that for a query pinned to one congregation and one month, so each month is its own listener.
     */
    fun monthRecords(congregationId: String, periodMonth: Long): Flow<Unit> = if (BackendConfig.enabled) emptyFlow() else mirrorFirestoreCollection(
        firestore, offline, appScope, "monthlyReports", MonthlyReport::class.java,
        query = firestore.collection("monthlyReports").whereEqualTo("congregationId", congregationId).whereEqualTo("periodMonth", periodMonth),
        registrationKey = "monthlyReports#$congregationId#$periodMonth",
    ) { it.id }

    /** One collection filtered to a chunk of congregations (`congregationId in chunk`) — the only list query Firestore's rules can prove. */
    private fun comparativeReports(i: Int, chunk: List<String>): List<Flow<Unit>> =
        // 10 congregations x 3 statuses = 30 disjunctions, the most a single Firestore query may have.
        chunk.chunked(10).flatMapIndexed { j, ids ->
            listOf(
                mirrorFirestoreCollection(
                    firestore, offline, appScope, "congregationComparativeReports", com.emfitsolutions.gopreach.data.model.ComparativeReport::class.java,
                    query = firestore.collection("congregationComparativeReports").whereIn("congregationId", ids).whereIn("status", listOf("SUBMITTED", "RETURNED", "RECEIVED")),
                    registrationKey = "congregationComparativeReports#$i#$j",
                ) { it.id },
                byCongregation("comparativeReportHistory", com.emfitsolutions.gopreach.data.model.ComparativeReportHistory::class.java, i * 100 + j, ids) { it.id },
                byCongregation("comparativeReportRemarks", com.emfitsolutions.gopreach.data.model.ComparativeReportRemark::class.java, i * 100 + j, ids) { it.id },
            )
        }

    private fun <T : Any> byCongregation(path: String, clazz: Class<T>, i: Int, chunk: List<String>, idOf: (T) -> String): Flow<Unit> =
        mirrorFirestoreCollection(
            firestore, offline, appScope, path, clazz,
            query = firestore.collection(path).whereIn("congregationId", chunk),
            registrationKey = "$path#$i",
            idOf = idOf,
        )

    private companion object {
        /** Firestore's limit for an `in` filter. */
        const val MAX_IN_VALUES = 30
    }
}

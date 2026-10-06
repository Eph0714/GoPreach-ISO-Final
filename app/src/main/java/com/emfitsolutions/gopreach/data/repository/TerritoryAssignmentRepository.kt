package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.TerritoryAssignment
import com.emfitsolutions.gopreach.data.model.TerritoryAssignmentBarangay
import com.emfitsolutions.gopreach.data.sync.ConnectivityObserver
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.mirrorFirestoreCollection
import com.emfitsolutions.gopreach.di.ApplicationScope
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val ASSIGNMENTS_COLLECTION = "territoryAssignments"
private const val BARANGAYS_COLLECTION = "territoryAssignmentBarangays"

/** Firestore caps a single transaction at ~500 document writes — this stays
 * well under that even after accounting for the header doc, so a save never
 * hits a cryptic Firestore-side limit error; the caller gets this app's own
 * clear message instead (see [TerritoryAssignmentResult.Error] callers). */
const val MAX_BARANGAYS_PER_SAVE = 400

/** One municipality's desired barangay set within a [saveGroupTerritoryForProvince]
 * session — see that function's own doc comment. */
data class MunicipalitySelection(
    val provinceId: Int,
    val provinceName: String,
    val muncityId: Int,
    val muncityName: String,
    val barangays: List<PsgcOption>,
)

sealed class TerritoryAssignmentResult {
    data class Success(val assignmentId: String) : TerritoryAssignmentResult()

    /** A barangay in this save attempt is already claimed by a different
     * assignment — the transaction's own conflict check (not just the
     * wizard's client-side "already taken" hint) caught it. */
    data class Conflict(val barangayName: String, val takenByGroupName: String) : TerritoryAssignmentResult()

    data class Offline(
        val message: String = "An internet connection is required to save a territory assignment. Connect and try again.",
    ) : TerritoryAssignmentResult()

    data class Error(val message: String) : TerritoryAssignmentResult()
}

/** Thrown only inside a [FirebaseFirestore.runTransaction] lambda below, so
 * the transaction aborts (none of its writes apply) and the surrounding
 * try/catch can turn it into a typed [TerritoryAssignmentResult.Conflict] —
 * this exception never escapes [TerritoryAssignmentRepository] itself. */
private class TerritoryConflictException(val barangayName: String, val takenByGroupName: String) : Exception()

/** One municipality's plan inside a [TerritoryAssignmentRepository.saveGroupTerritoryForProvince]
 * transaction — which assignment doc to write to (existing or freshly
 * minted), and the add/remove/unchanged barangay diff for it. */
private data class MuncityPlan(
    val selection: MunicipalitySelection,
    val assignmentRef: DocumentReference,
    val isNew: Boolean,
    val existingAssignment: TerritoryAssignment?,
    val toAdd: List<PsgcOption>,
    val toRemoveIds: Set<Int>,
    val unchanged: List<PsgcOption>,
)

/**
 * Territory Assignment module — assigns every barangay of one municipality to
 * exactly one FS Group per congregation. Unlike every other repository in
 * this app, the write methods here go **straight to Firestore inside a real
 * transaction**, not through [OfflineFirestoreRepository]'s normal queue-and-
 * flush-later outbox: the thing that must be atomic isn't "did my write
 * land" (which the outbox already guarantees, eventually) but "did nobody
 * else's write land on the same barangay between my read and my write" —
 * exactly the correctness guarantee a transaction provides and a queued
 * write cannot. This mirrors the precedent [AuthRepository
 * .createAccountWithTempCredentials]/[AuthRepository.forcedPasswordChange]
 * already set for "this operation must be confirmed by the server right
 * now, not whenever the sync queue next flushes."
 *
 * Each barangay claim's document id is deterministic —
 * `"${congregationId}_${barangayId}"` (see [TerritoryAssignmentBarangay]'s
 * own doc comment) — so "is this barangay already assigned in this
 * congregation" is answered by a single, cheap `transaction.get()` on a known
 * path, and "claim it" is a `transaction.set()` on that same path that the
 * transaction guarantees cannot race a concurrent claim of the same barangay.
 */
@Singleton
class TerritoryAssignmentRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    private val connectivityObserver: ConnectivityObserver,
    private val auditLogRepository: AuditLogRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    fun observeAssignments(): Flow<List<TerritoryAssignment>> = offline.observeCollection(ASSIGNMENTS_COLLECTION)
    fun observeBarangayClaims(): Flow<List<TerritoryAssignmentBarangay>> = offline.observeCollection(BARANGAYS_COLLECTION)

    fun startRemoteSync(): Flow<Unit> = merge(
        mirrorFirestoreCollection(firestore, offline, appScope, ASSIGNMENTS_COLLECTION, TerritoryAssignment::class.java) { it.id },
        mirrorFirestoreCollection(firestore, offline, appScope, BARANGAYS_COLLECTION, TerritoryAssignmentBarangay::class.java) { it.id },
    )

    private fun claimId(congregationId: String, barangayId: Int) = "${congregationId}_$barangayId"

    /**
     * "A single Field Service Group may cover multiple municipalities" — the
     * group-level save. Takes the full desired barangay set for every
     * municipality this Group should hold **within one province**
     * ([municipalities] is a declarative end-state, not a delta) and commits
     * every create/update/delete — assignment headers *and* barangay claims,
     * across every affected municipality — in one transaction. Scoped to a
     * single province per call so a save can never touch (or accidentally
     * delete) this Group's municipalities in a *different* province; those
     * are never fetched. Same per-barangay conflict guarantee as the old
     * single-municipality path this supersedes: every newly-claimed barangay
     * is re-checked via `txn.get()` on its deterministic id before any write,
     * so a race with another admin is still caught server-side.
     */
    suspend fun saveGroupTerritoryForProvince(
        congregationId: String,
        groupId: String,
        groupName: String,
        provinceId: Int,
        provinceName: String,
        municipalities: List<MunicipalitySelection>,
        actorPersonId: String,
    ): TerritoryAssignmentResult {
        if (!connectivityObserver.isOnline()) return TerritoryAssignmentResult.Offline()
        val selections = municipalities.filter { it.barangays.isNotEmpty() }
        if (selections.isEmpty()) return TerritoryAssignmentResult.Error("Select at least one barangay.")
        val totalBarangays = selections.sumOf { it.barangays.size }
        if (totalBarangays > MAX_BARANGAYS_PER_SAVE) {
            return TerritoryAssignmentResult.Error(
                "You can assign at most $MAX_BARANGAYS_PER_SAVE barangays in one save. Split this into two assignments.",
            )
        }

        val existingAssignments = observeAssignments().first()
            .filter { it.congregationId == congregationId && it.groupId == groupId && it.provinceId == provinceId }
        val existingAssignmentIds = existingAssignments.map { it.id }.toSet()
        val existingClaims = observeBarangayClaims().first().filter { it.assignmentId in existingAssignmentIds }
        val existingByMuncity = existingAssignments.associateBy { it.muncityId }
        val selectedMuncityIds = selections.map { it.muncityId }.toSet()
        val now = System.currentTimeMillis()

        // Every assignment ref (existing or a freshly minted id) is known
        // before the transaction opens — a Firestore transaction requirement.
        val plans = selections.map { selection ->
            val existing = existingByMuncity[selection.muncityId]
            val existingIds = existingClaims.filter { it.muncityId == selection.muncityId }.map { it.barangayId }.toSet()
            val newIds = selection.barangays.map { it.id }.toSet()
            MuncityPlan(
                selection = selection,
                assignmentRef = existing?.let { firestore.collection(ASSIGNMENTS_COLLECTION).document(it.id) }
                    ?: firestore.collection(ASSIGNMENTS_COLLECTION).document(),
                isNew = existing == null,
                existingAssignment = existing,
                toAdd = selection.barangays.filter { it.id !in existingIds },
                toRemoveIds = existingIds - newIds,
                unchanged = selection.barangays.filter { it.id in existingIds },
            )
        }
        // Municipalities this Group held in this province before, but that
        // aren't in this session's selection at all — dropped entirely.
        val droppedAssignments = existingAssignments.filter { it.muncityId !in selectedMuncityIds }
        val droppedAssignmentIds = droppedAssignments.map { it.id }.toSet()
        val droppedClaims = existingClaims.filter { it.assignmentId in droppedAssignmentIds }

        return try {
            firestore.runTransaction { txn ->
                // All reads before any write — every barangay newly claimed
                // across every municipality in this save, checked against the
                // one global per-congregation uniqueness constraint.
                for (plan in plans) {
                    for (b in plan.toAdd) {
                        val ref = firestore.collection(BARANGAYS_COLLECTION).document(claimId(congregationId, b.id))
                        val snap = txn.get(ref)
                        if (snap.exists()) {
                            throw TerritoryConflictException(b.name, snap.getString("groupName") ?: "another group")
                        }
                    }
                }
                for (plan in plans) {
                    val selection = plan.selection
                    if (plan.isNew) {
                        txn.set(
                            plan.assignmentRef,
                            TerritoryAssignment(
                                id = plan.assignmentRef.id, congregationId = congregationId, groupId = groupId,
                                provinceId = provinceId, provinceName = provinceName,
                                muncityId = selection.muncityId, muncityName = selection.muncityName,
                                createdAt = now, createdByPersonId = actorPersonId,
                                updatedAt = now, updatedByPersonId = actorPersonId,
                            ),
                        )
                    } else {
                        txn.update(
                            plan.assignmentRef,
                            mapOf(
                                "groupId" to groupId, "provinceName" to provinceName, "muncityName" to selection.muncityName,
                                "updatedAt" to now, "updatedByPersonId" to actorPersonId,
                            ),
                        )
                    }
                    for (barangayId in plan.toRemoveIds) {
                        txn.delete(firestore.collection(BARANGAYS_COLLECTION).document(claimId(congregationId, barangayId)))
                    }
                    for (b in plan.toAdd) {
                        txn.set(
                            firestore.collection(BARANGAYS_COLLECTION).document(claimId(congregationId, b.id)),
                            TerritoryAssignmentBarangay(
                                id = claimId(congregationId, b.id), congregationId = congregationId, assignmentId = plan.assignmentRef.id,
                                groupId = groupId, groupName = groupName, provinceId = provinceId,
                                muncityId = selection.muncityId, muncityName = selection.muncityName,
                                barangayId = b.id, barangayName = b.name, createdAt = now, createdByPersonId = actorPersonId,
                            ),
                        )
                    }
                    // Group/municipality display fields on an unchanged claim
                    // only need rewriting if the Group itself changed — cheap
                    // to always do, keeps denormalized groupName correct even
                    // if only the Group was edited on this save.
                    for (b in plan.unchanged) {
                        txn.update(
                            firestore.collection(BARANGAYS_COLLECTION).document(claimId(congregationId, b.id)),
                            mapOf("groupId" to groupId, "groupName" to groupName, "muncityName" to selection.muncityName),
                        )
                    }
                }
                for (claim in droppedClaims) {
                    txn.delete(firestore.collection(BARANGAYS_COLLECTION).document(claim.id))
                }
                for (assignment in droppedAssignments) {
                    txn.delete(firestore.collection(ASSIGNMENTS_COLLECTION).document(assignment.id))
                }
            }.await()

            // Same "write straight into the local cache now that the server
            // has confirmed it" reasoning as every other write in this
            // repository — this screen needs the result immediately.
            plans.forEach { plan ->
                val selection = plan.selection
                offline.cacheFromServer(
                    ASSIGNMENTS_COLLECTION, plan.assignmentRef.id,
                    TerritoryAssignment(
                        id = plan.assignmentRef.id, congregationId = congregationId, groupId = groupId,
                        provinceId = provinceId, provinceName = provinceName,
                        muncityId = selection.muncityId, muncityName = selection.muncityName,
                        createdAt = plan.existingAssignment?.createdAt ?: now,
                        createdByPersonId = plan.existingAssignment?.createdByPersonId ?: actorPersonId,
                        updatedAt = now, updatedByPersonId = actorPersonId,
                    ),
                )
                plan.toRemoveIds.forEach { offline.deleteFromServer(BARANGAYS_COLLECTION, claimId(congregationId, it)) }
                selection.barangays.forEach { b ->
                    offline.cacheFromServer(
                        BARANGAYS_COLLECTION, claimId(congregationId, b.id),
                        TerritoryAssignmentBarangay(
                            id = claimId(congregationId, b.id), congregationId = congregationId, assignmentId = plan.assignmentRef.id,
                            groupId = groupId, groupName = groupName, provinceId = provinceId,
                            muncityId = selection.muncityId, muncityName = selection.muncityName,
                            barangayId = b.id, barangayName = b.name, createdAt = now, createdByPersonId = actorPersonId,
                        ),
                    )
                }
            }
            droppedClaims.forEach { offline.deleteFromServer(BARANGAYS_COLLECTION, it.id) }
            droppedAssignments.forEach { offline.deleteFromServer(ASSIGNMENTS_COLLECTION, it.id) }

            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = if (existingAssignments.isEmpty()) "ADD_TERRITORY_ASSIGNMENT" else "EDIT_TERRITORY_ASSIGNMENT",
                targetType = "TerritoryAssignment",
                targetId = groupId,
                congregationId = congregationId,
                details = "group=$groupName province=$provinceName municipalities=${plans.size} barangays=$totalBarangays",
            )
            TerritoryAssignmentResult.Success(groupId)
        } catch (e: TerritoryConflictException) {
            TerritoryAssignmentResult.Conflict(e.barangayName, e.takenByGroupName)
        } catch (e: Exception) {
            TerritoryAssignmentResult.Error(e.localizedMessage ?: "Couldn't save this territory assignment.")
        }
    }

    /** Removes every municipality this Group holds in [provinceId] — the
     * card-level "remove entire assignment" action, generalized from
     * [removeAssignment] (single assignment id) to a (group, province)
     * scope. Fresh Firestore queries (not the cached/offline flow), same
     * freshness reasoning [removeAssignment] already uses for deletes: a
     * stale local list could miss a just-added claim and leave it orphaned. */
    suspend fun removeGroupTerritory(
        congregationId: String,
        groupId: String,
        provinceId: Int,
        actorPersonId: String,
    ): TerritoryAssignmentResult {
        if (!connectivityObserver.isOnline()) return TerritoryAssignmentResult.Offline()
        return try {
            // Single-field equality filters only (groupId) — always covered
            // by Firestore's automatic indexing, no composite index needed —
            // then narrowed to this congregation/province client-side.
            val assignmentDocs = firestore.collection(ASSIGNMENTS_COLLECTION)
                .whereEqualTo("groupId", groupId)
                .get().await()
                .documents.filter { it.getString("congregationId") == congregationId && it.getLong("provinceId")?.toInt() == provinceId }
            val assignmentIds = assignmentDocs.map { it.id }.toSet()
            val claimDocs = firestore.collection(BARANGAYS_COLLECTION)
                .whereEqualTo("groupId", groupId)
                .get().await()
                .documents.filter { it.getString("assignmentId") in assignmentIds }
            firestore.runTransaction { txn ->
                // All reads before any write — a Firestore transaction
                // rejects an interleaved get()/delete()/get()/delete()
                // sequence outright ("all reads must be executed before all
                // writes"), confirmed on-device. Re-checking each claim
                // inside the transaction (rather than trusting the query
                // above) still guards the narrow race of someone editing
                // this Group's territory between that query and this
                // transaction's commit — just as two passes, not one.
                val claimSnaps = claimDocs.map { txn.get(it.reference) }
                for (snap in claimSnaps) {
                    if (snap.getString("assignmentId") in assignmentIds) txn.delete(snap.reference)
                }
                for (doc in assignmentDocs) {
                    txn.delete(doc.reference)
                }
            }.await()
            assignmentIds.forEach { offline.deleteFromServer(ASSIGNMENTS_COLLECTION, it) }
            claimDocs.forEach { offline.deleteFromServer(BARANGAYS_COLLECTION, it.id) }
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "REMOVE_TERRITORY_ASSIGNMENT",
                targetType = "TerritoryAssignment",
                targetId = groupId,
                congregationId = congregationId,
                details = "municipalities=${assignmentIds.size} barangays=${claimDocs.size}",
            )
            TerritoryAssignmentResult.Success(groupId)
        } catch (e: Exception) {
            TerritoryAssignmentResult.Error(e.localizedMessage ?: "Couldn't remove this territory assignment.")
        }
    }

    /** Hard delete — see [TerritoryAssignment]'s own doc comment for why this
     * module has no Inactive state: an assignment that still "existed" would
     * keep every one of its barangays unavailable to every other Group. */
    suspend fun removeAssignment(assignmentId: String, congregationId: String, actorPersonId: String): TerritoryAssignmentResult {
        if (!connectivityObserver.isOnline()) return TerritoryAssignmentResult.Offline()
        return try {
            // Firestore transactions can't run an arbitrary query, only
            // get() on already-known refs — so the claim docs belonging to
            // this assignment are found first, outside the transaction.
            val claimDocs = firestore.collection(BARANGAYS_COLLECTION)
                .whereEqualTo("assignmentId", assignmentId)
                .get().await()
            val assignmentRef = firestore.collection(ASSIGNMENTS_COLLECTION).document(assignmentId)
            firestore.runTransaction { txn ->
                // All reads before any write — same fix/reasoning as
                // removeGroupTerritory's own identical two-pass rewrite.
                val claimSnaps = claimDocs.documents.map { txn.get(it.reference) }
                for (snap in claimSnaps) {
                    if (snap.getString("assignmentId") == assignmentId) txn.delete(snap.reference)
                }
                txn.delete(assignmentRef)
            }.await()
            offline.deleteFromServer(ASSIGNMENTS_COLLECTION, assignmentId)
            claimDocs.documents.forEach { offline.deleteFromServer(BARANGAYS_COLLECTION, it.id) }
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "REMOVE_TERRITORY_ASSIGNMENT",
                targetType = "TerritoryAssignment",
                targetId = assignmentId,
                congregationId = congregationId,
                details = "barangays=${claimDocs.size()}",
            )
            TerritoryAssignmentResult.Success(assignmentId)
        } catch (e: Exception) {
            TerritoryAssignmentResult.Error(e.localizedMessage ?: "Couldn't remove this territory assignment.")
        }
    }
}

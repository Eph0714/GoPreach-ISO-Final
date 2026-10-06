package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.DashboardModuleLayout
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.mirrorFirestoreCollection
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val COLLECTION = "dashboardModuleLayouts"

/**
 * "Publishers App – Customizable Module Navigation Redesign" spec §6 — one
 * document per Publisher, same offline-first mirror + Firestore sync every
 * other per-account setting in this app already uses (see
 * [LocationSharingSettingsRepository] for the identical shape this mirrors).
 * A Publisher who has never customized their layout simply has no document
 * yet — [observeFor] resolves that to an all-defaults [DashboardModuleLayout]
 * rather than treating it as an error.
 */
class DashboardModuleLayoutRepository(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    private val appScope: CoroutineScope,
) {
    fun observeAll(): Flow<List<DashboardModuleLayout>> = offline.observeCollection(COLLECTION)

    fun observeFor(personId: String): Flow<DashboardModuleLayout> =
        observeAll().map { list -> list.firstOrNull { it.personId == personId } ?: DashboardModuleLayout(personId = personId) }

    suspend fun save(layout: DashboardModuleLayout) = offline.save(COLLECTION, layout.personId, layout)

    /** Saves only the Quick Access choice (ids in display order), leaving every other field of the person's layout document untouched. */
    suspend fun saveQuickAccess(personId: String, order: List<String>, configured: Boolean) {
        val current = observeFor(personId).first()
        save(current.copy(quickAccessOrder = order, quickAccessConfigured = configured, updatedAt = System.currentTimeMillis()))
    }

    fun startRemoteSync(): Flow<Unit> =
        mirrorFirestoreCollection(firestore, offline, appScope, COLLECTION, DashboardModuleLayout::class.java) { it.personId }
}

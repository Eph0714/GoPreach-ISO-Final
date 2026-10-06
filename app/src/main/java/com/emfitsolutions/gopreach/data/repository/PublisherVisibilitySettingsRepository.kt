package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.PublisherVisibilitySettings
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.mirrorFirestoreCollection
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val COLLECTION = "publisherVisibilitySettings"

/** One document per congregation; a congregation without one uses [PublisherVisibilitySettings.defaultsFor]. */
class PublisherVisibilitySettingsRepository(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    private val appScope: CoroutineScope,
) {
    fun observeAll(): Flow<List<PublisherVisibilitySettings>> = offline.observeCollection(COLLECTION)

    fun observeFor(congregationId: String): Flow<PublisherVisibilitySettings> =
        observeAll().map { list -> list.firstOrNull { it.id == congregationId } ?: PublisherVisibilitySettings.defaultsFor(congregationId) }

    suspend fun save(settings: PublisherVisibilitySettings) =
        offline.save(COLLECTION, settings.id, settings.copy(updatedAt = System.currentTimeMillis()))

    fun startRemoteSync(): Flow<Unit> =
        mirrorFirestoreCollection(firestore, offline, appScope, COLLECTION, PublisherVisibilitySettings::class.java) { it.id }
}

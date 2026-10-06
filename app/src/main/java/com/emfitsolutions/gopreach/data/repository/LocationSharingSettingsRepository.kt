package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.LocationSharingSettings
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.mirrorFirestoreCollection
import com.emfitsolutions.gopreach.di.ApplicationScope
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val COLLECTION = "locationSharingSettings"

/** "SHARE LOCATION SETTINGS" — one document per congregation; a congregation
 * with no document yet just uses [LocationSharingSettings.defaultsFor]. */
@Singleton
class LocationSharingSettingsRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    fun observeAll(): Flow<List<LocationSharingSettings>> = offline.observeCollection(COLLECTION)

    fun observeFor(congregationId: String): Flow<LocationSharingSettings> =
        observeAll().map { list -> list.firstOrNull { it.congregationId == congregationId } ?: LocationSharingSettings.defaultsFor(congregationId) }

    suspend fun currentFor(congregationId: String): LocationSharingSettings = observeFor(congregationId).first()

    suspend fun save(settings: LocationSharingSettings) = offline.save(COLLECTION, settings.congregationId, settings)

    fun startRemoteSync(): Flow<Unit> =
        mirrorFirestoreCollection(firestore, offline, appScope, COLLECTION, LocationSharingSettings::class.java) { it.congregationId }
}

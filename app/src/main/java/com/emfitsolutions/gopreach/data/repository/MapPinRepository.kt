package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.MapPin
import com.emfitsolutions.gopreach.data.sync.ConnectivityObserver
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.mirrorFirestoreCollection
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

private const val COLLECTION = "mapPins"
private const val SAVE_TIMEOUT_MS = 15_000L

/** Result of a pin write that must reach the server. */
sealed class MapPinResult {
    data object Success : MapPinResult()
    data object Offline : MapPinResult()
    data class Error(val message: String) : MapPinResult()
}

/**
 * Territory Map pins. Unlike most repositories here, writes go **straight to
 * Firestore and wait for the server** (the pin is "saved online" only once the
 * server has it) instead of the offline queue — so an offline user is told
 * plainly instead of seeing a pin that may never upload. The live collection
 * listener ([startRemoteSync]) is what feeds every device's map, and the
 * saved pin is also cached right away so it appears without waiting for it.
 */
class MapPinRepository(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    private val connectivityObserver: ConnectivityObserver,
    private val appScope: CoroutineScope,
) {
    fun observeAll(): Flow<List<MapPin>> = offline.observeCollection(COLLECTION)

    suspend fun create(pin: MapPin): MapPinResult {
        if (!connectivityObserver.isOnline()) return MapPinResult.Offline
        val id = firestore.collection(COLLECTION).document().id
        val saved = pin.copy(id = id, createdAt = System.currentTimeMillis())
        return try {
            withTimeout(SAVE_TIMEOUT_MS) { firestore.collection(COLLECTION).document(id).set(saved).await() }
            offline.cacheFromServer(COLLECTION, id, saved)
            MapPinResult.Success
        } catch (e: Exception) {
            MapPinResult.Error(e.message ?: "Couldn't save the pin.")
        }
    }

    suspend fun delete(pinId: String): MapPinResult {
        if (!connectivityObserver.isOnline()) return MapPinResult.Offline
        return try {
            withTimeout(SAVE_TIMEOUT_MS) { firestore.collection(COLLECTION).document(pinId).delete().await() }
            offline.deleteFromServer(COLLECTION, pinId)
            MapPinResult.Success
        } catch (e: Exception) {
            MapPinResult.Error(e.message ?: "Couldn't remove the pin.")
        }
    }

    fun startRemoteSync(): Flow<Unit> =
        mirrorFirestoreCollection(firestore, offline, appScope, COLLECTION, MapPin::class.java) { it.id }
}

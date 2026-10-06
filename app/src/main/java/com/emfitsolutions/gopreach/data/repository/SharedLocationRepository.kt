package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.sync.saveNow
import com.emfitsolutions.gopreach.data.model.SharedLocation
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.mirrorFirestoreCollection
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val COLLECTION = "sharedLocations"

/** Spec §6.1 — Share Location. One doc per publisher (keyed by their personId),
 * overwritten on each update; this is live presence, not a location history log. */
class SharedLocationRepository(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    private val appScope: CoroutineScope,
) {
    fun observeAll(): Flow<List<SharedLocation>> = offline.observeCollection(COLLECTION)

    /** The signed-in publisher's own doc — the actual source of truth for
     * whether their "Share my location while preaching" toggle should read
     * as on or off. Bug fix: the toggle used to be backed by a plain
     * ViewModel-local flag that reset to false every time the screen (and
     * its ViewModel) was recreated — reopening Share Location after leaving
     * it always showed unchecked even while [LocationSharingService] was
     * still actively sharing in the background. Deriving it from this
     * persisted/synced doc instead means the toggle reflects reality no
     * matter when or how the screen is reopened. */
    fun observeFor(publisherPersonId: String): Flow<SharedLocation?> =
        observeAll().map { list -> list.firstOrNull { it.publisherPersonId == publisherPersonId } }

    /** [OfflineFirestoreRepository.saveNow], not [OfflineFirestoreRepository
     * .save] — "Share Location" is live presence, not a durable record; a fix
     * that only reaches the server whenever the next WorkManager-scheduled
     * sync run happens to fire (the app's normal "queue, flush later" write
     * path) can lag the other publishers actually watching this person's dot
     * move by several seconds to tens of seconds. [LocationSharingService]
     * already only calls this while it just got a live GPS callback on an
     * active session — exactly the "caller already knows the device has a
     * live connection worth trying" case [saveNow] asks for — so every fix
     * pushes straight to Firestore the instant it clears the accuracy gate,
     * landing on every other publisher's snapshot listener in near real time.
     * Still cache-first underneath, so a fix made moments before connectivity
     * drops simply falls back to the normal queued path instead of being
     * lost. */
    suspend fun update(location: SharedLocation) = offline.saveNow(firestore, COLLECTION, location.publisherPersonId, location)

    suspend fun stopSharing(publisherPersonId: String, lastKnown: SharedLocation) =
        offline.saveNow(firestore, COLLECTION, publisherPersonId, lastKnown.copy(isSharing = false))

    fun startRemoteSync(): Flow<Unit> =
        mirrorFirestoreCollection(firestore, offline, appScope, COLLECTION, SharedLocation::class.java) { it.publisherPersonId }
}

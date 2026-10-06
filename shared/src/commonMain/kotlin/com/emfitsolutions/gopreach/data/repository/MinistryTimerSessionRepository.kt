package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.MinistryTimerSession
import com.emfitsolutions.gopreach.data.model.TimerSessionStatus
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val COLLECTION = "ministryTimerSessions"

/** Spec §20 — Ministry Timer sessions; see [MinistryTimerSession]'s own doc
 * comment for how this survives navigation without a foreground service. */
class MinistryTimerSessionRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    fun observeForPublisher(publisherPersonId: String): Flow<List<MinistryTimerSession>> =
        observeAll().map { list -> list.filter { it.publisherPersonId == publisherPersonId } }

    /** At most one of these should ever exist per Publisher (enforced by the
     * ViewModel checking before START, not by this query) — `null` means no
     * active (RUNNING or PAUSED) timer session right now. Named "Running" for
     * historical reasons but covers PAUSED too, since a paused session is
     * still the one active session blocking a new START. */
    fun observeRunning(publisherPersonId: String): Flow<MinistryTimerSession?> =
        observeForPublisher(publisherPersonId).map { list ->
            list.firstOrNull { it.status == TimerSessionStatus.RUNNING || it.status == TimerSessionStatus.PAUSED }
        }

    fun observeAll(): Flow<List<MinistryTimerSession>> = offline.observeCollection(COLLECTION)

    suspend fun save(session: MinistryTimerSession): MinistryTimerSession {
        val id = session.id.ifBlank { remote.newId(COLLECTION) }
        val withId = session.copy(id = id)
        offline.save(COLLECTION, id, withId)
        return withId
    }

    suspend fun delete(sessionId: String) = offline.delete(COLLECTION, sessionId)

    fun startRemoteSync(publisherPersonId: String): Flow<Unit> =
        remote.mirror(COLLECTION, MinistryTimerSession::class, equalTo = "publisherPersonId" to publisherPersonId) { it.id }

    suspend fun pullOnce(publisherPersonId: String) = remote.pullOnce(COLLECTION, MinistryTimerSession::class, equalTo = "publisherPersonId" to publisherPersonId) { it.id }
}

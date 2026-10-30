package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.FsGroupReportSnapshot
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val COLLECTION = "fsGroupReportSnapshots"

/** What saving a snapshot came to. */
sealed interface SnapshotSaveResult {
    data class Saved(val snapshot: FsGroupReportSnapshot) : SnapshotSaveResult
    /** A snapshot of that group and month is already saved (it is never replaced). */
    data class AlreadyExists(val snapshot: FsGroupReportSnapshot) : SnapshotSaveResult
    /** The server refused it, or could not be reached: nothing was saved, here or there. */
    data class Failed(val reason: String) : SnapshotSaveResult
}

/**
 * Monthly Report Snapshots. A snapshot is permanent, so it is written to the SERVER first and only then kept on the device: it is never half-saved, a second one for the same group and
 * month is refused (its id is the group and the month), and nothing here edits or deletes one. The server also refuses another group's snapshot and a changed or removed one.
 */
class FsGroupReportSnapshotRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    /** What this person's device holds: an officer's own group's snapshots, or, for the congregation roles, all of their congregation's. The server decides who receives which. */
    fun observeAll(): Flow<List<FsGroupReportSnapshot>> =
        offline.observeCollection<FsGroupReportSnapshot>(COLLECTION).map { all -> all.sortedWith(compareByDescending<FsGroupReportSnapshot> { it.year }.thenByDescending { it.month }.thenBy { it.groupName.lowercase() }) }

    suspend fun save(snapshot: FsGroupReportSnapshot): SnapshotSaveResult {
        val id = FsGroupReportSnapshot.idFor(snapshot.groupId, snapshot.year, snapshot.month)
        observeAll().first().firstOrNull { it.id == id }?.let { return SnapshotSaveResult.AlreadyExists(it) }
        val doc = snapshot.copy(id = id)
        return try {
            remote.pushNow(COLLECTION, id, doc)
            offline.cacheFromServer(COLLECTION, id, doc)
            SnapshotSaveResult.Saved(doc)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SnapshotSaveResult.Failed(e.message?.takeIf { it.isNotBlank() } ?: "The snapshot could not be saved. Check the connection and try again.")
        }
    }

    fun startRemoteSync(): Flow<Unit> = remote.mirror(COLLECTION, FsGroupReportSnapshot::class) { it.id }
}

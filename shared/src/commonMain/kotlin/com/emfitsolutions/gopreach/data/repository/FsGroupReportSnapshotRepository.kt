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
 * Saved Field Service Records (the stored name is still "snapshot"). One record per FS Group and month (its id is the group and the month), written to the SERVER first and only then kept
 * on the device, so it is never half-saved. The group's officers save it, update it (the same record, the earlier copy kept inside it as an internal history) and delete it; the server
 * refuses everyone else and every other group.
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

    /**
     * Replaces [existing] with [replacement] (built with FsGroupReportSnapshot.replacedBy, so it carries the next version and the old copy in its history). Written to the SERVER first,
     * like a first save: the server refuses it if it is not exactly the next version with its history, if there is no reason, or if the person is not an officer of the group.
     */
    suspend fun replace(existing: FsGroupReportSnapshot, replacement: FsGroupReportSnapshot): SnapshotSaveResult {
        val doc = replacement.copy(id = existing.id)
        return try {
            remote.pushNow(COLLECTION, existing.id, doc)
            offline.cacheFromServer(COLLECTION, existing.id, doc)
            SnapshotSaveResult.Saved(doc)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SnapshotSaveResult.Failed(e.message?.takeIf { it.isNotBlank() } ?: "The snapshot could not be replaced. Check the connection and try again.")
        }
    }

    /**
     * Deletes the saved Field Service Record [existing] (that group and month only; the submitted reports are not touched). Deleted on the SERVER first: it refuses anyone who is not an
     * officer of the record's own group. Returns null when it is gone, otherwise the reason it is not.
     */
    suspend fun delete(existing: FsGroupReportSnapshot): String? =
        try {
            remote.deleteNow(COLLECTION, existing.id)
            offline.deleteFromServer(COLLECTION, existing.id)
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message?.takeIf { it.isNotBlank() } ?: "The saved Field Service Record could not be deleted. Check the connection and try again."
        }

    fun startRemoteSync(): Flow<Unit> = remote.mirror(COLLECTION, FsGroupReportSnapshot::class) { it.id }
}

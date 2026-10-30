package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.AssignmentHistoryEntry
import com.emfitsolutions.gopreach.data.model.AssignmentStatus
import com.emfitsolutions.gopreach.data.model.PublisherAssignment
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val COLLECTION = "publisherAssignments"

/**
 * Publishers Assignment. Saved on the device at once and synced like every record; the server checks each save again (own FS Group only, the group / publisher / owner never change,
 * the history only grows), so a client that skips this class gains nothing.
 */
class PublisherAssignmentRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    /** What this person's device holds. The server only ever sends a person their own assignments and those of the FS Group they are an officer of. */
    fun observeAll(): Flow<List<PublisherAssignment>> = offline.observeCollection(COLLECTION)

    fun observeForGroup(groupId: String): Flow<List<PublisherAssignment>> =
        observeAll().map { all -> all.filter { it.groupId == groupId }.sortedByDescending { it.updatedAt } }

    fun observeForPublisher(personId: String): Flow<List<PublisherAssignment>> =
        observeAll().map { all -> all.filter { it.publisherPersonId == personId }.sortedByDescending { it.updatedAt } }

    /** Creates an assignment; the id comes from the server's id generator so two devices never collide. */
    suspend fun create(draft: PublisherAssignment, byPersonId: String, byName: String, nowMillis: Long): PublisherAssignment {
        val id = remote.newId(COLLECTION)
        val doc = draft.copy(
            id = id, assignedByPersonId = byPersonId, assignedByName = byName, createdAt = nowMillis, updatedAt = nowMillis,
            history = listOf(AssignmentHistoryEntry(nowMillis, byPersonId, byName, draft.status, "Assignment created")),
        )
        offline.save(COLLECTION, id, doc)
        return doc
    }

    /** Edits an assignment. The group, publisher and owner are taken from [old], so they cannot change; the history gets one more entry. */
    suspend fun update(old: PublisherAssignment, edited: PublisherAssignment, byPersonId: String, byName: String, nowMillis: Long): PublisherAssignment {
        val note = if (old.status != edited.status) "Status changed from ${AssignmentStatus.of(old.status).label} to ${AssignmentStatus.of(edited.status).label}" else "Assignment edited"
        val doc = old.copy(
            type = edited.type, title = edited.title, description = edited.description, remarks = edited.remarks, date = edited.date, status = edited.status, updatedAt = nowMillis,
            history = old.history + AssignmentHistoryEntry(nowMillis, byPersonId, byName, edited.status, note),
        )
        offline.save(COLLECTION, old.id, doc)
        return doc
    }

    suspend fun setStatus(old: PublisherAssignment, status: AssignmentStatus, byPersonId: String, byName: String, nowMillis: Long): PublisherAssignment =
        if (old.status == status.name) old else update(old, old.copy(status = status.name), byPersonId, byName, nowMillis)

    suspend fun delete(id: String) = offline.delete(COLLECTION, id)

    fun startRemoteSync(): Flow<Unit> = remote.mirror(COLLECTION, PublisherAssignment::class) { it.id }
}

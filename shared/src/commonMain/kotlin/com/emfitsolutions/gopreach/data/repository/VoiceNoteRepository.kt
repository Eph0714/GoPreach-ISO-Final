package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.VoiceNote
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val COLLECTION = "voiceNotes"

/** Voice notes: saved on the device at once (offline first) and synced to the server, which shows each note only to the person who made it. */
class VoiceNoteRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    /** This person's notes, newest first. The device filter is a convenience; the server is what keeps other people's notes out. */
    fun observeFor(personId: String): Flow<List<VoiceNote>> =
        offline.observeCollection<VoiceNote>(COLLECTION).map { all -> all.filter { it.publisherPersonId == personId }.sortedByDescending { it.recordedAt } }

    suspend fun get(id: String): VoiceNote? = offline.get<VoiceNote>(COLLECTION, id)

    suspend fun save(note: VoiceNote) = offline.save(COLLECTION, note.id, note)

    suspend fun delete(id: String) = offline.delete(COLLECTION, id)

    suspend fun all(personId: String): List<VoiceNote> = observeFor(personId).first()

    fun startRemoteSync(): Flow<Unit> = remote.mirror(COLLECTION, VoiceNote::class) { it.id }
}

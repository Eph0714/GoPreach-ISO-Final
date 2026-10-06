package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.sync.saveNow
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.remote.RemoteFiles
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import kotlinx.coroutines.flow.Flow

private const val COLLECTION = "people"

/**
 * Source of truth for [Person] identity records — separate from whatever roles a
 * person holds (see [RoleAssignmentRepository] and spec §3). Backs enrollment
 * (spec §4) and every screen that displays a name/contact/address.
 */
class PersonRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
    private val files: RemoteFiles,
) {
    fun observeAll(): Flow<List<Person>> = offline.observeCollection(COLLECTION)

    suspend fun get(personId: String): Person? = offline.get(COLLECTION, personId)

    suspend fun save(person: Person): Person {
        val id = person.id.ifBlank { remote.newId(COLLECTION) }
        val withId = person.copy(id = id)
        offline.save(COLLECTION, id, withId)
        return withId
    }

    /** Same as [save], but also pushes straight to Firestore immediately —
     * see [OfflineFirestoreRepository.saveNow] for why this exists
     * ([AuthRepository.createAccountWithTempCredentials] is its only caller). */
    suspend fun saveNow(person: Person): Person {
        val id = person.id.ifBlank { remote.newId(COLLECTION) }
        val withId = person.copy(id = id)
        offline.saveNow(remote, COLLECTION, id, withId)
        return withId
    }

    suspend fun delete(personId: String) {
        offline.delete(COLLECTION, personId)
    }

    /** Cache-only mirror of a [Person] already read straight from Firestore —
     * used by [AuthRepository][com.emfitsolutions.gopreach.data.repository
     * .AuthRepository] to seed the local cache right after sign-in. Must never
     * go through [save]: that enqueues a pending upload of this just-read
     * snapshot, which can race [UserSession.syncActiveRoleContext]'s own
     * `saveNow` of the newly-resolved `activeCongregationId`/`activeAdminRole`
     * and, on a person's very first sign-in (the only time those fields move
     * off `null`), clobber it back to `null` via the sync queue's full-document
     * `.set()` — breaking every write firestore.rules gates on
     * `activeCongregationId` until the next sign-in. */
    suspend fun cacheFromServer(person: Person) {
        offline.cacheFromServer(COLLECTION, person.id, person)
    }

    /** Profile-menu avatar upload — one fixed Storage path per person (a
     * re-upload simply overwrites it), same pattern
     * [AnnouncementRepository.uploadImage] already uses. Returns the
     * download URL; the caller saves it onto [Person.profileImageUrl]. */
    suspend fun uploadProfileImage(personId: String, imageUri: String): String =
        files.upload("people/$personId/profile", imageUri)

    /** Mirrors server-side Person changes into the local cache; call once per
     * session (see [RoleAssignmentRepository.startRemoteSync] for the pattern). */
    fun startRemoteSync(): Flow<Unit> =
        remote.mirror(COLLECTION, Person::class) { it.id }
}

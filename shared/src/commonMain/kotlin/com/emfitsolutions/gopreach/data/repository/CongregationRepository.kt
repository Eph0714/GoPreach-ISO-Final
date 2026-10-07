package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.ElderTitleEntity
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.Territory
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import com.emfitsolutions.gopreach.data.sync.saveNow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Congregation Master File (spec §4.1) — Super-Admin CRUD only. Also owns
 * uniqueness of [Congregation.code], which Firestore cannot enforce natively.
 */
class CongregationRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    private val collection = "congregations"

    fun observeAll(): Flow<List<Congregation>> = offline.observeCollection(collection)

    suspend fun isCodeAvailable(code: String, excludingId: String? = null): Boolean =
        observeAll().first().none { it.code.equals(code, ignoreCase = true) && it.id != excludingId }

    suspend fun save(congregation: Congregation): Congregation {
        val id = congregation.id.ifBlank { remote.newId(collection) }
        val withId = congregation.copy(id = id)
        offline.save(collection, id, withId)
        return withId
    }

    suspend fun delete(congregationId: String) = offline.delete(collection, congregationId)

    /** Saves and pushes to the server right now — the Circuit Overseer assignment transaction reads the server copy,
     * so a brand-new congregation must exist there before it can be assigned. */
    suspend fun saveNow(congregation: Congregation): Congregation {
        val id = congregation.id.ifBlank { remote.newId(collection) }
        val withId = congregation.copy(id = id)
        offline.saveNow(remote, collection, id, withId)
        return withId
    }

    /** Removes a congregation locally and on the server right now (used to undo a [saveNow] whose assignment failed). */
    suspend fun deleteNow(congregationId: String) {
        offline.delete(collection, congregationId)
        runCatching { remote.deleteNow(collection, congregationId) }
    }

    fun startRemoteSync(): Flow<Unit> =
        remote.mirror(collection, Congregation::class) { it.id }
}

/** Groups within a congregation (spec §3: "CRUD Groups + assign 1 Elder"). */
class GroupRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    private val collection = "groups"

    /** Field Service Groups, alphabetically (FS GROUP 2 before FS GROUP 10) — so every dropdown and list that reads them is in order. */
    fun observeAll(): Flow<List<Group>> = offline.observeCollection<Group>(collection).map { list -> list.sortedWith(com.emfitsolutions.gopreach.domain.GroupNameOrder) }

    suspend fun save(group: Group): Group {
        val id = group.id.ifBlank { remote.newId(collection) }
        val withId = group.copy(id = id)
        offline.save(collection, id, withId)
        return withId
    }

    suspend fun delete(groupId: String) = offline.delete(collection, groupId)

    fun startRemoteSync(): Flow<Unit> =
        remote.mirror(collection, Group::class) { it.id }
}

/** Regular Elder "specific title" lookup table (spec §3), full CRUD for admins. */
class ElderTitleRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    private val collection = "elderTitles"

    fun observeActive(): Flow<List<ElderTitleEntity>> =
        offline.observeCollection<ElderTitleEntity>(collection).map { list -> list.filter { it.active } }

    fun observeAll(): Flow<List<ElderTitleEntity>> = offline.observeCollection(collection)

    suspend fun save(title: ElderTitleEntity): ElderTitleEntity {
        val id = title.id.ifBlank { remote.newId(collection) }
        val withId = title.copy(id = id)
        offline.save(collection, id, withId)
        return withId
    }

    suspend fun delete(titleId: String) = offline.delete(collection, titleId)

    fun startRemoteSync(): Flow<Unit> =
        remote.mirror(collection, ElderTitleEntity::class) { it.id }
}

/** Territory Master File (spec §3, §5.1). */
class TerritoryRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    private val collection = "territories"

    fun observeAll(): Flow<List<Territory>> = offline.observeCollection(collection)

    suspend fun save(territory: Territory): Territory {
        val id = territory.id.ifBlank { remote.newId(collection) }
        val withId = territory.copy(id = id)
        offline.save(collection, id, withId)
        return withId
    }

    suspend fun delete(territoryId: String) = offline.delete(collection, territoryId)

    fun startRemoteSync(): Flow<Unit> =
        remote.mirror(collection, Territory::class) { it.id }
}

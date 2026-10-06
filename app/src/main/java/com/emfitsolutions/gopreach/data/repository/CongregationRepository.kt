package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.ElderTitleEntity
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.Territory
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

/**
 * Congregation Master File (spec §4.1) — Super-Admin CRUD only. Also owns
 * uniqueness of [Congregation.code], which Firestore cannot enforce natively.
 */
@Singleton
class CongregationRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val collection = "congregations"

    fun observeAll(): Flow<List<Congregation>> = offline.observeCollection(collection)

    suspend fun isCodeAvailable(code: String, excludingId: String? = null): Boolean =
        observeAll().first().none { it.code.equals(code, ignoreCase = true) && it.id != excludingId }

    suspend fun save(congregation: Congregation): Congregation {
        val id = congregation.id.ifBlank { firestore.collection(collection).document().id }
        val withId = congregation.copy(id = id)
        offline.save(collection, id, withId)
        return withId
    }

    suspend fun delete(congregationId: String) = offline.delete(collection, congregationId)

    fun startRemoteSync(): Flow<Unit> =
        mirrorFirestoreCollection(firestore, offline, appScope, collection, Congregation::class.java) { it.id }
}

/** Groups within a congregation (spec §3: "CRUD Groups + assign 1 Elder"). */
@Singleton
class GroupRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val collection = "groups"

    /** Field Service Groups, alphabetically (FS GROUP 2 before FS GROUP 10) — so every dropdown and list that reads them is in order. */
    fun observeAll(): Flow<List<Group>> = offline.observeCollection<Group>(collection).map { list -> list.sortedWith(com.emfitsolutions.gopreach.domain.GroupNameOrder) }

    suspend fun save(group: Group): Group {
        val id = group.id.ifBlank { firestore.collection(collection).document().id }
        val withId = group.copy(id = id)
        offline.save(collection, id, withId)
        return withId
    }

    suspend fun delete(groupId: String) = offline.delete(collection, groupId)

    fun startRemoteSync(): Flow<Unit> =
        mirrorFirestoreCollection(firestore, offline, appScope, collection, Group::class.java) { it.id }
}

/** Regular Elder "specific title" lookup table (spec §3), full CRUD for admins. */
@Singleton
class ElderTitleRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val collection = "elderTitles"

    fun observeActive(): Flow<List<ElderTitleEntity>> =
        offline.observeCollection<ElderTitleEntity>(collection).map { list -> list.filter { it.active } }

    fun observeAll(): Flow<List<ElderTitleEntity>> = offline.observeCollection(collection)

    suspend fun save(title: ElderTitleEntity): ElderTitleEntity {
        val id = title.id.ifBlank { firestore.collection(collection).document().id }
        val withId = title.copy(id = id)
        offline.save(collection, id, withId)
        return withId
    }

    suspend fun delete(titleId: String) = offline.delete(collection, titleId)

    fun startRemoteSync(): Flow<Unit> =
        mirrorFirestoreCollection(firestore, offline, appScope, collection, ElderTitleEntity::class.java) { it.id }
}

/** Territory Master File (spec §3, §5.1). */
@Singleton
class TerritoryRepository @Inject constructor(
    private val offline: OfflineFirestoreRepository,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val collection = "territories"

    fun observeAll(): Flow<List<Territory>> = offline.observeCollection(collection)

    suspend fun save(territory: Territory): Territory {
        val id = territory.id.ifBlank { firestore.collection(collection).document().id }
        val withId = territory.copy(id = id)
        offline.save(collection, id, withId)
        return withId
    }

    suspend fun delete(territoryId: String) = offline.delete(collection, territoryId)

    fun startRemoteSync(): Flow<Unit> =
        mirrorFirestoreCollection(firestore, offline, appScope, collection, Territory::class.java) { it.id }
}

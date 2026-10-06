package com.emfitsolutions.gopreach.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.emfitsolutions.gopreach.data.local.CachedDocumentEntity
import kotlinx.coroutines.flow.Flow

/** Projection of [CachedDocumentEntity] for [CacheDao.observeSyncStates]. */
data class DocSyncRow(val documentId: String, val syncState: String)

@Dao
interface CacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CachedDocumentEntity)

    @Query("SELECT * FROM cached_documents WHERE collectionPath = :collectionPath ORDER BY updatedAt DESC")
    fun observeCollection(collectionPath: String): Flow<List<CachedDocumentEntity>>

    /** Just each document's [DocSyncRow.syncState] in [collectionPath] — cheap enough to
     * observe for a per-record "Pending Sync / Synced / Sync Failed" badge. */
    @Query("SELECT documentId, syncState FROM cached_documents WHERE collectionPath = :collectionPath")
    fun observeSyncStates(collectionPath: String): Flow<List<DocSyncRow>>

    /** Same as [observeCollection], but for cross-cutting subcollections keyed
     * by a variable parent id — e.g. every "visits" row under every
     * Interested Person, not just one. [pathPattern] is a plain
     * SQL LIKE pattern (e.g. `"interestedPeople/%/visits"`); the caller is
     * responsible for filtering the results down further (e.g. by
     * publisherPersonId) since this only narrows by path shape. */
    @Query("SELECT * FROM cached_documents WHERE collectionPath LIKE :pathPattern ORDER BY updatedAt DESC")
    fun observeCollectionsMatching(pathPattern: String): Flow<List<CachedDocumentEntity>>

    /** Every cached document, any collection — the source for a full backup export
     * (spec §5.1 Backup & Restore, Super-Admin only). */
    @Query("SELECT * FROM cached_documents")
    suspend fun getAll(): List<CachedDocumentEntity>

    @Query("SELECT * FROM cached_documents WHERE collectionPath = :collectionPath AND documentId = :documentId")
    suspend fun get(collectionPath: String, documentId: String): CachedDocumentEntity?

    @Query("DELETE FROM cached_documents WHERE collectionPath = :collectionPath AND documentId = :documentId")
    suspend fun delete(collectionPath: String, documentId: String)

    @Query("UPDATE cached_documents SET syncState = :syncState WHERE collectionPath = :collectionPath AND documentId = :documentId")
    suspend fun updateSyncState(collectionPath: String, documentId: String, syncState: String)

    @Delete
    suspend fun deleteEntity(entity: CachedDocumentEntity)
}

package com.emfitsolutions.gopreach.data.sync

import kotlinx.coroutines.flow.Flow
import kotlin.reflect.KClass

/**
 * What a repository needs from "the server" besides its local cache: new ids, a live feed of a collection into the cache,
 * and a one-shot pull. Android implements it with Firestore today; the Hostinger/iOS implementation is driven by
 * [SyncEngine] instead, so there it does very little.
 */
interface RemoteCollections {
    /** A new, unique document id for [collectionPath]. */
    fun newId(collectionPath: String): String

    /**
     * Keeps [collectionPath] mirrored into the local cache while the returned flow is collected. [equalTo] restricts the feed to
     * documents whose field equals the value (e.g. a publisher's own records). [idOf] reads the id off a downloaded model.
     */
    fun <T : Any> mirror(
        collectionPath: String,
        kClass: KClass<T>,
        equalTo: Pair<String, String>? = null,
        idOf: (T) -> String,
    ): Flow<Unit>

    /** Writes one document to the server right now (the rare write that cannot wait for the next sync). Throws if it fails. */
    suspend fun pushNow(collectionPath: String, documentId: String, data: Any)

    /** Deletes one document on the server right now. Throws if it fails. */
    suspend fun deleteNow(collectionPath: String, documentId: String)

    /** True if [collectionPath] has at least one document on the SERVER (not the local cache). */
    suspend fun hasAny(collectionPath: String): Boolean

    /** Number of documents (capped at [limit]) in [collectionPath] whose [field] equals [value], counted on the SERVER. */
    suspend fun countWhere(collectionPath: String, field: String, value: String, limit: Int = 1): Int

    /**
     * Like [mirror], for a *collection group* (every subcollection called [groupId], e.g. "visits" under every interested person).
     * Each downloaded document is cached under [pathOf] its model, since the group spans many parent paths.
     */
    fun <T : Any> mirrorGroup(
        groupId: String,
        kClass: KClass<T>,
        equalTo: Pair<String, String>? = null,
        pathOf: (T) -> String,
        idOf: (T) -> String,
    ): Flow<Unit>

    /** One request/response pull of [collectionPath] into the cache (the fallback when a live feed cannot be sustained). */
    suspend fun <T : Any> pullOnce(
        collectionPath: String,
        kClass: KClass<T>,
        equalTo: Pair<String, String>? = null,
        idOf: (T) -> String,
    )
}

/**
 * Same local-cache write as `save`, but also pushes the document to the server immediately and marks it synced — for the rare
 * write that cannot wait for the next sync (a freshly enrolled account must exist on the server before its owner's first
 * sign-in). Cache-first: if the immediate push throws, the document is still safely queued. Only safe when the caller
 * already knows the device is online.
 */
suspend inline fun <reified T : Any> OfflineFirestoreRepository.saveNow(remote: RemoteCollections, collectionPath: String, documentId: String, data: T) {
    save(collectionPath, documentId, data)
    try {
        remote.pushNow(collectionPath, documentId, data)
        markSynced(collectionPath, documentId)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // stays queued; the next sync delivers it
    }
}

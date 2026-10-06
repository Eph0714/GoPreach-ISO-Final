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

    /** One request/response pull of [collectionPath] into the cache (the fallback when a live feed cannot be sustained). */
    suspend fun <T : Any> pullOnce(
        collectionPath: String,
        kClass: KClass<T>,
        equalTo: Pair<String, String>? = null,
        idOf: (T) -> String,
    )
}

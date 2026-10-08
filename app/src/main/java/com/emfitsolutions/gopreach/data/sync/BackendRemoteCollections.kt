package com.emfitsolutions.gopreach.data.sync

import com.emfitsolutions.gopreach.data.json.DocJson
import com.emfitsolutions.gopreach.data.remote.PushOp
import com.emfitsolutions.gopreach.data.remote.SyncApi
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import java.util.UUID
import kotlin.reflect.KClass

/**
 * [RemoteCollections] for the Hostinger backend. There are no live listeners: [SyncEngine] pushes the offline queue and pulls what
 * changed. The rare "write right now" calls go to the server's push endpoint, and the few reads (the sign-in username lookup,
 * does-anything-exist and count checks) use the server too, so no Firestore request is needed for them.
 */
class BackendRemoteCollections(
    private val api: SyncApi,
    private val syncEngine: SyncEngine,
) : RemoteCollections {
    private val gson = Gson()

    override fun newId(collectionPath: String): String = UUID.randomUUID().toString().replace("-", "").take(20)

    override fun <T : Any> mirror(collectionPath: String, kClass: KClass<T>, equalTo: Pair<String, String>?, idOf: (T) -> String): Flow<Unit> = emptyFlow()

    override fun <T : Any> mirrorGroup(groupId: String, kClass: KClass<T>, equalTo: Pair<String, String>?, pathOf: (T) -> String, idOf: (T) -> String): Flow<Unit> = emptyFlow()

    override suspend fun pushNow(collectionPath: String, documentId: String, data: Any) {
        val json = DocJson.parseToJsonElement(gson.toJson(data)) as JsonObject
        val body = JsonObject(json.filterKeys { it != "id" })
        val result = api.push(listOf(PushOp(collectionPath, documentId, "set", body))).firstOrNull()
        if (result == null || !result.isOk) throw IllegalStateException(result?.reason ?: "The server did not accept the change.")
    }

    override suspend fun deleteNow(collectionPath: String, documentId: String) {
        val result = api.push(listOf(PushOp(collectionPath, documentId, "delete"))).firstOrNull()
        if (result == null || !result.isOk) throw IllegalStateException(result?.reason ?: "The server did not accept the change.")
    }

    override suspend fun hasAny(collectionPath: String): Boolean =
        api.pull(since = 0, collections = listOf(collectionPath), limit = 5).changes.any { !it.deleted && it.collection == collectionPath }

    override suspend fun countWhere(collectionPath: String, field: String, value: String, limit: Int): Int {
        var cursor = 0L
        var found = 0
        do {
            val page = api.pull(since = cursor, collections = listOf(collectionPath), limit = 500)
            found += page.changes.count { c ->
                c.collection == collectionPath && !c.deleted && ((c.data as? JsonObject)?.get(field) as? JsonPrimitive)?.content == value
            }
            cursor = maxOf(cursor, page.cursor)
            if (found >= limit) return found
        } while (page.hasMore)
        return found
    }

    override suspend fun <T : Any> findFirst(collectionPath: String, field: String, value: String, kClass: KClass<T>): T? {
        val element: JsonObject? = if (collectionPath == "people" && field == "username") {
            api.lookupUsername(value)
        } else {
            var cursor = 0L
            var hit: JsonObject? = null
            do {
                val page = api.pull(since = cursor, collections = listOf(collectionPath), limit = 500)
                hit = page.changes.firstOrNull { c ->
                    c.collection == collectionPath && !c.deleted && ((c.data as? JsonObject)?.get(field) as? JsonPrimitive)?.content == value
                }?.let { JsonObject((it.data as JsonObject) + ("id" to JsonPrimitive(it.id))) }
                cursor = maxOf(cursor, page.cursor)
            } while (hit == null && page.hasMore)
            hit
        }
        @Suppress("UNCHECKED_CAST")
        return element?.let { DocJson.decodeFromJsonElement(serializer(kClass.java) as kotlinx.serialization.KSerializer<T>, it) }
    }

    /** The regular sync already downloads everything this device may read; refreshing "once" is simply one sync round. */
    override suspend fun <T : Any> pullOnce(collectionPath: String, kClass: KClass<T>, equalTo: Pair<String, String>?, idOf: (T) -> String) {
        syncEngine.syncOnce()
    }
}

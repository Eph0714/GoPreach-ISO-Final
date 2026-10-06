package com.emfitsolutions.gopreach.data.sync

import com.emfitsolutions.gopreach.data.local.CachedDocumentEntity
import com.emfitsolutions.gopreach.data.local.PendingSyncOperationEntity
import com.emfitsolutions.gopreach.data.local.dao.CacheDao
import com.emfitsolutions.gopreach.data.local.dao.DocSyncRow
import com.emfitsolutions.gopreach.data.local.dao.SyncQueueDao
import com.emfitsolutions.gopreach.data.model.SyncOperationType
import com.emfitsolutions.gopreach.data.model.SyncState
import com.emfitsolutions.gopreach.data.remote.PullChange
import com.emfitsolutions.gopreach.data.remote.PullPage
import com.emfitsolutions.gopreach.data.remote.PushOp
import com.emfitsolutions.gopreach.data.remote.PushResult
import com.emfitsolutions.gopreach.data.remote.SyncApi
import com.emfitsolutions.gopreach.data.remote.SyncTransportException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeCacheDao : CacheDao {
    val rows = mutableMapOf<Pair<String, String>, CachedDocumentEntity>()
    override suspend fun upsert(entity: CachedDocumentEntity) { rows[entity.collectionPath to entity.documentId] = entity }
    override fun observeCollection(collectionPath: String): Flow<List<CachedDocumentEntity>> = flowOf(rows.values.filter { it.collectionPath == collectionPath })
    override fun observeSyncStates(collectionPath: String): Flow<List<DocSyncRow>> = flowOf(emptyList())
    override fun observeCollectionsMatching(pathPattern: String): Flow<List<CachedDocumentEntity>> = flowOf(emptyList())
    override suspend fun getAll() = rows.values.toList()
    override suspend fun get(collectionPath: String, documentId: String) = rows[collectionPath to documentId]
    override suspend fun delete(collectionPath: String, documentId: String) { rows.remove(collectionPath to documentId) }
    override suspend fun updateSyncState(collectionPath: String, documentId: String, syncState: String) {
        rows[collectionPath to documentId]?.let { rows[collectionPath to documentId] = it.copy(syncState = syncState) }
    }
    override suspend fun deleteEntity(entity: CachedDocumentEntity) { rows.remove(entity.collectionPath to entity.documentId) }
}

class FakeQueueDao : SyncQueueDao {
    val ops = mutableListOf<PendingSyncOperationEntity>()
    private var nextId = 1L
    override suspend fun enqueue(operation: PendingSyncOperationEntity): Long { val id = nextId++; ops += operation.copy(id = id); return id }
    override suspend fun getAllPending() = ops.filter { !it.isPermanentFailure }.sortedBy { it.createdAt }
    override fun observePendingCount(): Flow<Int> = flowOf(ops.count { !it.isPermanentFailure })
    override fun observePermanentFailureCount(): Flow<Int> = flowOf(ops.count { it.isPermanentFailure })
    override suspend fun remove(operation: PendingSyncOperationEntity) { ops.removeAll { it.id == operation.id } }
    override suspend fun removeForDocument(collectionPath: String, documentId: String) { ops.removeAll { it.collectionPath == collectionPath && it.documentId == documentId } }
    override suspend fun recordFailure(id: Long, error: String) = update(id) { it.copy(retryCount = it.retryCount + 1, lastError = error) }
    override suspend fun markPermanentFailure(id: Long, error: String) = update(id) { it.copy(retryCount = it.retryCount + 1, lastError = error, isPermanentFailure = true) }
    override fun observePermanentFailures(): Flow<List<PendingSyncOperationEntity>> = flowOf(ops.filter { it.isPermanentFailure })
    override suspend fun retryPermanentFailure(id: Long) = update(id) { it.copy(isPermanentFailure = false) }
    override suspend fun retryAllPermanentFailures() { ops.replaceAll { it.copy(isPermanentFailure = false) } }
    private fun update(id: Long, f: (PendingSyncOperationEntity) -> PendingSyncOperationEntity) {
        val i = ops.indexOfFirst { it.id == id }
        if (i >= 0) ops[i] = f(ops[i])
    }
}

class FakeApi : SyncApi {
    var pushResults: (PushOp) -> PushResult = { PushResult(it.collection, it.id, "ok") }
    var pages = mutableListOf<PullPage>()
    var down = false
    val pushed = mutableListOf<PushOp>()
    val pullSince = mutableListOf<Long>()
    override suspend fun push(ops: List<PushOp>): List<PushResult> {
        if (down) throw SyncTransportException("offline")
        pushed += ops
        return ops.map(pushResults)
    }
    override suspend fun pull(since: Long, collections: List<String>?, limit: Int): PullPage {
        if (down) throw SyncTransportException("offline")
        pullSince += since
        return pages.removeFirstOrNull() ?: PullPage(cursor = since)
    }
}

class SyncEngineTest {
    private val cache = FakeCacheDao()
    private val queue = FakeQueueDao()
    private val api = FakeApi()
    private val engine = SyncEngine(api, cache, queue)

    private suspend fun queueSet(collection: String, id: String, json: String) {
        cache.upsert(CachedDocumentEntity(collection, id, json, SyncState.PENDING.name, 1))
        queue.enqueue(PendingSyncOperationEntity(collectionPath = collection, documentId = id, operationType = SyncOperationType.UPDATE.name, payloadJson = json, createdAt = 1))
    }

    @Test fun uploadsQueuedEditsAndMarksThemSynced() = runTest {
        queueSet("people", "juan", """{"id":"juan","firstName":"Juan"}""")
        val report = engine.syncOnce()
        assertEquals(1, report.uploaded)
        assertEquals("set", api.pushed.single().op)
        assertTrue(queue.ops.isEmpty())
        assertEquals(SyncState.SYNCED.name, cache.get("people", "juan")!!.syncState)
    }

    @Test fun deniedEditIsKeptAsPermanentFailureNotDropped() = runTest {
        api.pushResults = { PushResult(it.collection, it.id, "denied", reason = "Other congregation") }
        queueSet("people", "x", """{"id":"x"}""")
        val report = engine.syncOnce()
        assertEquals(1, report.rejected)
        val op = queue.ops.single()
        assertTrue(op.isPermanentFailure)
        assertTrue(op.lastError!!.contains("Other congregation"))
    }

    @Test fun networkFailureKeepsTheQueueAndSkipsDownload() = runTest {
        queueSet("people", "juan", """{"id":"juan"}""")
        api.down = true
        val report = engine.syncOnce()
        assertEquals("offline", report.transportError)
        assertEquals(1, queue.ops.size)
        assertTrue(!queue.ops.single().isPermanentFailure)
        assertTrue(api.pullSince.isEmpty())
    }

    @Test fun deleteIsPushedAsDelete() = runTest {
        queue.enqueue(PendingSyncOperationEntity(collectionPath = "people", documentId = "gone", operationType = SyncOperationType.DELETE.name, payloadJson = null, createdAt = 1))
        engine.flushOutbox()
        assertEquals("delete", api.pushed.single().op)
        assertNull(api.pushed.single().data)
    }

    @Test fun downloadCachesDocumentsWithTheirIdAndAdvancesTheCursor() = runTest {
        api.pages += PullPage(listOf(PullChange("people", "ana", buildJsonObject { put("firstName", "Ana") }, seq = 5)), cursor = 5, hasMore = true)
        api.pages += PullPage(listOf(PullChange("people", "bob", null, deleted = true, seq = 6)), cursor = 6)
        cache.upsert(CachedDocumentEntity("people", "bob", "{}", SyncState.SYNCED.name, 1))
        val report = engine.pull()
        assertEquals(2, report.downloaded)
        val ana = cache.get("people", "ana")!!
        assertTrue(ana.payloadJson.contains("\"id\":\"ana\""), ana.payloadJson)
        assertEquals(SyncState.SYNCED.name, ana.syncState)
        assertNull(cache.get("people", "bob"))
        assertEquals(listOf(0L, 5L), api.pullSince)
        engine.pull()
        assertEquals(6L, api.pullSince.last())
    }

    @Test fun usesTheCollectionsOwnIdField() = runTest {
        val e = SyncEngine(api, cache, queue, idFieldByCollection = mapOf("sharedLocations" to "publisherPersonId"))
        api.pages += PullPage(listOf(PullChange("sharedLocations", "ana", buildJsonObject { put("lat", 1) }, seq = 1)), cursor = 1)
        e.pull()
        assertTrue(cache.get("sharedLocations", "ana")!!.payloadJson.contains("\"publisherPersonId\":\"ana\""))
    }

    @Test fun downloadNeverOverwritesAnUnsentLocalEdit() = runTest {
        queueSet("people", "juan", """{"id":"juan","firstName":"Local"}""")
        api.pages += PullPage(listOf(PullChange("people", "juan", buildJsonObject { put("firstName", "Server") }, seq = 9)), cursor = 9)
        engine.pull()
        assertTrue(cache.get("people", "juan")!!.payloadJson.contains("Local"))
    }
}

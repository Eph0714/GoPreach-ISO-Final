package com.emfitsolutions.gopreach.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpSyncApiTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun pushSendsBearerTokenAndParsesResults() = runTest {
        var seenAuth: String? = null
        var seenBody = ""
        val engine = MockEngine { req ->
            seenAuth = req.headers[HttpHeaders.Authorization]
            seenBody = req.body.toByteArray().decodeToString()
            respond("""{"results":[{"collection":"people","id":"a","status":"ok","version":1,"seq":3},{"collection":"people","id":"b","status":"denied","reason":"no"}]}""", HttpStatusCode.OK, json)
        }
        val api = HttpSyncApi(HttpClient(engine), "https://api.test", { "tok" })
        val results = api.push(listOf(PushOp("people", "a", "set", buildJsonObject { put("x", 1) }), PushOp("people", "b", "delete")))
        assertEquals("Bearer tok", seenAuth)
        assertTrue(seenBody.contains("\"op\":\"set\""), seenBody)
        assertTrue(results[0].isOk)
        assertEquals("no", results[1].reason)
    }

    @Test fun pullBuildsQueryAndParsesTombstones() = runTest {
        var url = ""
        val engine = MockEngine { req ->
            url = req.url.toString()
            respond("""{"changes":[{"collection":"people","id":"a","data":{"n":1},"deleted":false,"version":1,"seq":4},{"collection":"people","id":"b","data":null,"deleted":true,"version":2,"seq":5}],"cursor":5,"hasMore":false}""", HttpStatusCode.OK, json)
        }
        val page = HttpSyncApi(HttpClient(engine), "https://api.test", { "t" }).pull(3, listOf("people", "groups"), 100)
        assertTrue(url.contains("since=3") && url.contains("limit=100") && url.contains("collections="), url)
        assertEquals(2, page.changes.size)
        assertTrue(page.changes[1].deleted)
        assertEquals(5L, page.cursor)
    }

    @Test fun serverErrorBecomesTransportException() = runTest {
        val engine = MockEngine { respond("boom", HttpStatusCode.ServiceUnavailable) }
        val e = assertFailsWith<SyncTransportException> { HttpSyncApi(HttpClient(engine), "https://api.test", { "t" }).pull(0) }
        assertEquals(503, e.statusCode)
    }
}

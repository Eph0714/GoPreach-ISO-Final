package com.emfitsolutions.gopreach.data.remote

import com.emfitsolutions.gopreach.data.json.DocJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

/**
 * Ktor implementation of [SyncApi] for the Hostinger backend.
 * [baseUrl] is e.g. "https://gopreach.example.com"; [idToken] returns a fresh Firebase ID token (the server verifies it).
 */
class HttpSyncApi(
    private val client: HttpClient,
    private val baseUrl: String,
    private val idToken: suspend () -> String,
) : SyncApi {

    override suspend fun push(ops: List<PushOp>): List<PushResult> {
        if (ops.isEmpty()) return emptyList()
        val body = DocJson.encodeToString(PushRequest.serializer(), PushRequest(ops))
        val text = call {
            client.post("$baseUrl/v1/sync/push") {
                header(HttpHeaders.Authorization, "Bearer ${idToken()}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return DocJson.decodeFromString(PushResponse.serializer(), text).results
    }

    override suspend fun pull(since: Long, collections: List<String>?, limit: Int): PullPage {
        val text = call {
            client.get("$baseUrl/v1/sync/pull") {
                header(HttpHeaders.Authorization, "Bearer ${idToken()}")
                parameter("since", since)
                parameter("limit", limit)
                if (!collections.isNullOrEmpty()) parameter("collections", collections.joinToString(","))
            }
        }
        return DocJson.decodeFromString(PullPage.serializer(), text)
    }

    private suspend fun call(request: suspend () -> HttpResponse): String {
        val response = try {
            request()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SyncTransportException("Network error: ${e.message}", cause = e)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw SyncTransportException("Server answered ${response.status.value}: ${text.take(200)}", response.status.value)
        return text
    }
}

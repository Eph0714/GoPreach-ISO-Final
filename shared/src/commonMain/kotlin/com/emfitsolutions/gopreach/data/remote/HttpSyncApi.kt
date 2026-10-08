package com.emfitsolutions.gopreach.data.remote

import com.emfitsolutions.gopreach.data.json.DocJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.delete
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

    override suspend fun postJson(path: String, body: kotlinx.serialization.json.JsonObject): ApiReply {
        val response = try {
            client.post("$baseUrl$path") {
                header(HttpHeaders.Authorization, "Bearer ${idToken()}")
                contentType(ContentType.Application.Json)
                setBody(DocJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SyncTransportException("Network error: ${e.message}", cause = e)
        }
        val parsed = runCatching { DocJson.parseToJsonElement(response.bodyAsText()) as? kotlinx.serialization.json.JsonObject }.getOrNull()
        return ApiReply(response.status.value, parsed)
    }

    override suspend fun uploadFile(path: String, bytes: ByteArray, mime: String): String {
        val text = call {
            client.put("$baseUrl/v1/files") {
                header(HttpHeaders.Authorization, "Bearer ${idToken()}")
                parameter("path", path)
                contentType(ContentType.parse(mime))
                setBody(bytes)
            }
        }
        val url = (DocJson.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject)?.get("url") as? kotlinx.serialization.json.JsonPrimitive
        return url?.content ?: throw SyncTransportException("The server did not return a file address.")
    }

    override suspend fun deleteFile(path: String) {
        call {
            client.delete("$baseUrl/v1/files") {
                header(HttpHeaders.Authorization, "Bearer ${idToken()}")
                parameter("path", path)
            }
        }
    }

    override suspend fun lookupUsername(username: String): kotlinx.serialization.json.JsonObject? {
        val text = call {
            client.post("$baseUrl/v1/public/lookup-username") {
                contentType(ContentType.Application.Json)
                setBody(DocJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.JsonObject(mapOf("username" to kotlinx.serialization.json.JsonPrimitive(username)))))
            }
        }
        return (DocJson.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject)?.get("person") as? kotlinx.serialization.json.JsonObject
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

package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.json.DocJson
import com.emfitsolutions.gopreach.platform.KeyValueStores
import com.emfitsolutions.gopreach.platform.edit
import com.emfitsolutions.gopreach.platform.nowMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive

private const val PREFS = "gopreach_auth_session"
private const val K_ACCESS = "access"
private const val K_REFRESH = "refresh"
private const val K_EXPIRES = "expiresAt"
private const val K_PERSON = "personId"
private const val K_LAST_SIGN_IN = "lastSignInAt"
private const val EARLY_REFRESH_MS = 60_000L

/**
 * GoPreach's own sign-in against the Hostinger API (no Firebase). The phone keeps a short-lived access token and a long-lived,
 * single-use refresh token in encrypted storage, so the person stays signed in across app restarts exactly as Firebase Auth did.
 * The same class serves Android and iOS (Ktor + the shared secure key-value store).
 */
class HttpAuthService(
    private val client: HttpClient,
    private val baseUrl: String,
    stores: KeyValueStores,
    private val scope: CoroutineScope,
) : AuthService {
    private val prefs by lazy { stores.openSecure(PREFS) }
    private val lock = Mutex()
    private val state = MutableStateFlow<AuthUser?>(null)
    private var started = false

    private fun ensureLoaded() {
        if (started) return
        started = true
        val person = runCatching { prefs.getString(K_PERSON, null) }.getOrNull()
        val refresh = runCatching { prefs.getString(K_REFRESH, null) }.getOrNull()
        if (person != null && refresh != null) state.value = userFor(person, prefs.getLong(K_LAST_SIGN_IN, 0L).takeIf { it > 0L })
    }

    private fun userFor(personId: String, lastSignIn: Long?) = AuthUser(personId, authEmailFor(personId), lastSignIn)

    override val currentUser: AuthUser? get() { ensureLoaded(); return state.value }

    override fun authState(): Flow<AuthUser?> { ensureLoaded(); return state.asStateFlow() }

    private fun save(reply: JsonObject, signedInNow: Boolean) {
        val personId = reply.string("personId") ?: throw AuthFailure.Other("The server sent no account.")
        val lastSignIn = if (signedInNow) nowMillis() else prefs.getLong(K_LAST_SIGN_IN, 0L)
        prefs.edit {
            putString(K_ACCESS, reply.string("accessToken"))
            putString(K_REFRESH, reply.string("refreshToken"))
            putLong(K_EXPIRES, reply["expiresAt"]?.jsonPrimitive?.long ?: 0L)
            putString(K_PERSON, personId)
            putLong(K_LAST_SIGN_IN, lastSignIn)
        }
        state.value = userFor(personId, lastSignIn.takeIf { it > 0L })
    }

    private fun clear() {
        prefs.edit { remove(K_ACCESS); remove(K_REFRESH); remove(K_EXPIRES); remove(K_PERSON); remove(K_LAST_SIGN_IN) }
        state.value = null
    }

    override suspend fun signIn(email: String, password: String) {
        ensureLoaded()
        val reply = post("/v1/auth/login", buildJsonObject { put("email", JsonPrimitive(email)); put("password", JsonPrimitive(password)) }, token = null)
        lock.withLock { save(reply, signedInNow = true) }
    }

    override fun signOut() {
        ensureLoaded()
        val refresh = prefs.getString(K_REFRESH, null)
        clear()
        if (refresh != null) scope.launch { runCatching { post("/v1/auth/logout", buildJsonObject { put("refreshToken", JsonPrimitive(refresh)) }, token = null) } }
    }

    override suspend fun idToken(force: Boolean): String? = lock.withLock {
        ensureLoaded()
        val access = prefs.getString(K_ACCESS, null)
        val refresh = prefs.getString(K_REFRESH, null) ?: return@withLock null
        if (!force && access != null && prefs.getLong(K_EXPIRES, 0L) - EARLY_REFRESH_MS > nowMillis()) return@withLock access
        try {
            val reply = post("/v1/auth/refresh", buildJsonObject { put("refreshToken", JsonPrimitive(refresh)) }, token = null)
            save(reply, signedInNow = false)
            reply.string("accessToken")
        } catch (e: AuthFailure.InvalidCredentials) {
            clear() // the server no longer knows this session (signed out elsewhere, password changed, account removed)
            null
        }
    }

    override suspend fun reauthenticate(password: String) {
        val token = idToken(false) ?: throw AuthFailure.InvalidUser()
        val reply = post("/v1/auth/reauth", buildJsonObject { put("password", JsonPrimitive(password)) }, token)
        lock.withLock { save(reply, signedInNow = false) }
    }

    override suspend fun updatePassword(newPassword: String) {
        val token = idToken(false) ?: throw AuthFailure.InvalidUser()
        val reply = post("/v1/auth/password", buildJsonObject { put("newPassword", JsonPrimitive(newPassword)) }, token)
        lock.withLock { save(reply, signedInNow = false) }
    }

    override suspend fun createAccount(email: String, password: String) {
        val token = idToken(false) ?: throw AuthFailure.InvalidUser()
        val personId = personIdFromAuthEmail(email) ?: throw AuthFailure.Other("Bad account address.")
        post("/v1/auth/accounts", buildJsonObject { put("personId", JsonPrimitive(personId)); put("password", JsonPrimitive(password)) }, token)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** One POST; maps every failure to an [AuthFailure]. Returns the reply's JSON object. */
    private suspend fun post(path: String, body: JsonObject, token: String?): JsonObject {
        val response: HttpResponse = try {
            client.post("$baseUrl$path") {
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(DocJson.encodeToString(JsonObject.serializer(), body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AuthFailure.Network(e)
        }
        val text = response.bodyAsText()
        val json = runCatching { DocJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
        val code = response.status.value
        if (code in 200..299) return json ?: JsonObject(emptyMap())
        val error = json?.string("error")
        throw when {
            code == 401 -> AuthFailure.InvalidCredentials()
            code == 429 -> AuthFailure.TooManyRequests()
            code == 404 && error == "invalid-user" -> AuthFailure.InvalidUser()
            code == 403 && error == "requires-recent-login" -> AuthFailure.Other("For security, please sign in again before changing your password.")
            code == 400 && error == "weak-password" -> AuthFailure.Other("The password is too weak. Use at least 6 characters.")
            code == 409 -> AuthFailure.Other("An account for this person already exists.")
            code == 503 -> AuthFailure.Other("Sign-in is not available right now. Please try again later.")
            else -> AuthFailure.Other("Sign-in failed (${code}).")
        }
    }
}

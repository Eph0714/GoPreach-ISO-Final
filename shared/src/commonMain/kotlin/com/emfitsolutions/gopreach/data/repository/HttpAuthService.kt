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
private const val K_ENDED = "sessionEndedElsewhere"
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
    /** What this phone calls itself to the server (shown on the other device); only what the system reports. */
    private val deviceLabel: String? = null,
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

    override suspend fun signIn(email: String, password: String, endOtherSession: Boolean) {
        ensureLoaded()
        val reply = post("/v1/auth/login", buildJsonObject {
            put("email", JsonPrimitive(email)); put("password", JsonPrimitive(password)); put("endOtherSession", JsonPrimitive(endOtherSession))
            deviceLabel?.let { put("device", JsonPrimitive(it)) }
        }, token = null)
        lock.withLock { prefs.edit { remove(K_ENDED) }; save(reply, signedInNow = true) }
    }

    override fun takeSessionEndedNotice(): Boolean {
        ensureLoaded()
        val ended = runCatching { prefs.getString(K_ENDED, null) != null }.getOrDefault(false)
        if (ended) prefs.edit { remove(K_ENDED) }
        return ended
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
        } catch (e: AuthFailure.SessionEnded) {
            clear()
            prefs.edit { putString(K_ENDED, "1") } // the sign-in screen explains why
            null
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

    override suspend fun adminResetPassword(personId: String, newPassword: String) {
        val token = idToken(false) ?: throw AuthFailure.InvalidUser()
        post("/v1/auth/accounts/$personId/password", buildJsonObject { put("password", JsonPrimitive(newPassword)) }, token)
    }

    override suspend fun passkeyLoginOptions(): PasskeyChallenge {
        val reply = post("/v1/auth/passkey/login/options", JsonObject(emptyMap()), token = null)
        val options = reply["options"] ?: throw AuthFailure.Other("The server sent no passkey challenge.")
        return PasskeyChallenge(reply.string("sessionId"), options.toString())
    }

    override suspend fun passkeyLogin(sessionId: String, responseJson: String, endOtherSession: Boolean): String? {
        ensureLoaded()
        val response = DocJson.parseToJsonElement(responseJson)
        val reply = post("/v1/auth/passkey/login/verify", buildJsonObject {
            put("sessionId", JsonPrimitive(sessionId)); put("response", response); put("endOtherSession", JsonPrimitive(endOtherSession))
            deviceLabel?.let { put("device", JsonPrimitive(it)) }
        }, token = null)
        lock.withLock { save(reply, signedInNow = true) }
        return reply.string("username")
    }

    override suspend fun passkeyRegisterOptions(): String {
        val token = idToken(false) ?: throw AuthFailure.InvalidUser()
        val reply = post("/v1/auth/passkey/register/options", JsonObject(emptyMap()), token)
        return (reply["options"] ?: throw AuthFailure.Other("The server sent no passkey options.")).toString()
    }

    override suspend fun passkeyRegister(responseJson: String, name: String) {
        val token = idToken(false) ?: throw AuthFailure.InvalidUser()
        val response = DocJson.parseToJsonElement(responseJson)
        post("/v1/auth/passkey/register/verify", buildJsonObject { put("response", response); put("name", JsonPrimitive(name)) }, token)
    }

    override suspend fun passkeyList(): List<PasskeyInfo> {
        val token = idToken(false) ?: throw AuthFailure.InvalidUser()
        val reply = post("/v1/auth/passkey/list", JsonObject(emptyMap()), token)
        return (reply["passkeys"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            PasskeyInfo(
                id = o.string("id") ?: return@mapNotNull null,
                name = o.string("name") ?: "Passkey",
                createdAt = (o["createdAt"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                lastUsedAt = (o["lastUsedAt"] as? JsonPrimitive)?.content?.toLongOrNull(),
            )
        }
    }

    override suspend fun passkeyDelete(id: String): Boolean {
        val token = idToken(false) ?: throw AuthFailure.InvalidUser()
        val reply = post("/v1/auth/passkey/delete", buildJsonObject { put("id", JsonPrimitive(id)) }, token)
        return (reply["ok"] as? JsonPrimitive)?.content == "true"
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
            code == 401 && error == "session-ended" -> AuthFailure.SessionEnded()
            code == 401 -> AuthFailure.InvalidCredentials()
            code == 409 && error == "session-active" -> AuthFailure.SessionActive(ActiveSessionInfo(json?.string("device"), (json?.get("lastActiveAt") as? JsonPrimitive)?.content?.toLongOrNull()))
            code == 429 -> AuthFailure.TooManyRequests()
            code == 404 && error == "invalid-user" -> AuthFailure.InvalidUser()
            code == 403 && error == "requires-recent-login" -> AuthFailure.Other("For security, please sign in again before changing your password.")
            code == 400 && error == "weak-password" -> AuthFailure.Other("The password is too weak. Use at least 6 characters.")
            code == 409 -> AuthFailure.Other("An account for this person already exists.")
            code == 403 && error == "Not allowed" -> AuthFailure.Other("You are not allowed to manage this account.")
            code == 404 && error == "invalid-user" -> AuthFailure.Other("This person has no sign-in account yet.")
            code == 404 -> AuthFailure.Other("The server does not support this yet. It needs the latest server update.")
            code == 503 && error == "passkeys-not-configured" -> AuthFailure.Other("Passkeys aren't set up on the server yet.")
            code == 400 && (error == "not-verified" || error == "challenge-expired") -> AuthFailure.Other("That passkey could not be verified. Please try again.")
            code == 503 -> AuthFailure.Other("Sign-in is not available right now. Please try again later.")
            else -> AuthFailure.Other("Sign-in failed (${code}).")
        }
    }
}

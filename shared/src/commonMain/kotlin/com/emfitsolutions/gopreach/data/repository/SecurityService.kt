package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.json.DocJson
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The two-factor and device-session state of the signed-in account (what the Security & Device Management screen shows). */
data class SecurityStatus(
    val enabled: Boolean, val totpConfigured: Boolean, val recoveryEmailMasked: String?, val recoveryEmailVerified: Boolean,
    val recoveryCodesLeft: Int, val emailAvailable: Boolean, val trustedDays: Int, val trustedDevices: Int,
    /** "Approve new devices": a device that is not trusted only gets a login request until one of the account's own devices approves it. */
    val deviceApproval: Boolean = false,
)

/** One session of the account, as the server lists it. No token or password is ever part of it. [status] is active / expired / revoked. */
data class DeviceSession(
    val sessionId: String, val current: Boolean, val deviceId: String?, val name: String, val model: String?, val type: String, val os: String?, val osVersion: String?,
    val appVersion: String?, val ip: String?, val location: String?, val authMethod: String?, val loginAt: Long, val lastActiveAt: Long, val status: String, val trusted: Boolean, val revokedBy: String?,
)

data class TrustedDevice(val id: String, val deviceId: String?, val name: String, val since: Long, val expiresAt: Long)
data class DevicesOverview(val devices: List<DeviceSession>, val trusted: List<TrustedDevice>)

/** One DEVICE (however many sessions it has had): the most recent session's facts, whether it is this one, signed in, trusted. */
data class DeviceGroup(
    val key: String, val deviceId: String?, val sessionId: String?, val name: String, val type: String, val os: String?, val osVersion: String?, val model: String?, val appVersion: String?,
    val ip: String?, val location: String?, val current: Boolean, val active: Boolean, val trusted: Boolean, val trustedUntil: Long?, val sessions: Int, val lastActiveAt: Long, val loginAt: Long,
)

/**
 * One entry per device, the device in use first, then the most recently active. A device that is only signed out is history (the login history lists it) and is left out;
 * a trusted device that has no session is listed, so it can still be removed.
 */
fun groupDevices(sessions: List<DeviceSession>, trusted: List<TrustedDevice>): List<DeviceGroup> {
    val trustOf = trusted.filter { it.deviceId != null }.associateBy { it.deviceId!! }
    val groups = LinkedHashMap<String, DeviceGroup>()
    sessions.forEach { d ->
        val key = d.deviceId ?: d.sessionId
        val active = d.status == "active"
        val old = groups[key]
        val newest = old == null || d.lastActiveAt >= old.lastActiveAt
        groups[key] = DeviceGroup(
            key = key, deviceId = d.deviceId, sessionId = if (newest) d.sessionId else old!!.sessionId,
            name = if (newest) d.name else old!!.name, type = if (newest) d.type else old!!.type, os = if (newest) d.os else old!!.os, osVersion = if (newest) d.osVersion else old!!.osVersion,
            model = if (newest) d.model else old!!.model, appVersion = if (newest) d.appVersion else old!!.appVersion, ip = if (newest) d.ip else old!!.ip, location = if (newest) d.location else old!!.location,
            current = (old?.current ?: false) || d.current, active = (old?.active ?: false) || active, trusted = (old?.trusted ?: false) || d.trusted,
            trustedUntil = d.deviceId?.let { trustOf[it]?.expiresAt }, sessions = (old?.sessions ?: 0) + if (active) 1 else 0,
            lastActiveAt = maxOf(old?.lastActiveAt ?: 0L, d.lastActiveAt), loginAt = minOf(old?.loginAt ?: Long.MAX_VALUE, d.loginAt),
        )
    }
    trustOf.forEach { (deviceId, t) ->
        val g = groups[deviceId]
        if (g == null) groups[deviceId] = DeviceGroup(deviceId, deviceId, null, t.name, "other", null, null, null, null, null, null, false, false, true, t.expiresAt, 0, 0L, t.since)
        else groups[deviceId] = g.copy(trusted = true, trustedUntil = t.expiresAt)
    }
    return groups.values.filter { it.active || it.trusted }.sortedWith(compareByDescending<DeviceGroup> { it.current }.thenByDescending { it.lastActiveAt })
}
data class SecurityEvent(val id: String, val at: Long, val type: String, val result: String, val device: String?, val by: String?, val username: String? = null, val personId: String? = null, val seen: Boolean = true)

/** New-device request limits (Super Admin): requests one account may raise within [windowMinutes], and how long a request waits ([expiryMinutes]). */
data class ApprovalLimits(
    val maxRequests: Int = 5, val windowMinutes: Int = 15, val expiryMinutes: Int = 5,
    /** Wrong passwords on one account before it is paused, and the minutes they are counted in. */
    val passwordMaxFails: Int = 10, val passwordWindowMinutes: Int = 15,
    /** Wrong second-step codes before the sign-in must start again, and how long the second step stays open. */
    val twoFactorMaxTries: Int = 5, val twoFactorMinutes: Int = 10,
    /** The website's sign-in attempts per minutes from one internet address. */
    val webLoginMax: Int = 10, val webLoginMinutes: Int = 1,
)

/** The login-history filters (all optional): result = success/failure/rejected, [types] = event codes, [device] = text in the device name, [fromMs]/[toMs] = time range. */
data class HistoryFilter(val result: String? = null, val types: List<String> = emptyList(), val device: String = "", val fromMs: Long? = null, val toMs: Long? = null) {
    val isActive get() = result != null || types.isNotEmpty() || device.isNotBlank() || fromMs != null || toMs != null
}
data class TotpSetup(val secret: String, val otpauthUri: String)

/** A session in the Super Admin's (or a congregation Admin's) monitoring list. */
data class AdminSession(
    val sessionId: String, val personId: String, val username: String?, val name: String?, val congregation: String?, val roles: List<String>, val deviceName: String,
    val type: String, val os: String?, val osVersion: String?, val appVersion: String?, val loginAt: Long, val lastActiveAt: Long, val status: String, val authMethod: String?,
)

/** A refusal from the security endpoints. [code] is the server's reason (for example `requires-recent-login`, `invalid-code`); [message] is safe to show. */
class SecurityFailure(val code: String, message: String) : Exception(message)

/**
 * Two-factor settings, the device list, remote logout, trusted devices and the security log. Every action is carried out by the server, which ends a session for real
 * (see backend/src/security.js): this client never hides a row on its own. Anything that changes security needs a recently typed password: call
 * [AuthService.reauthenticate] first.
 */
interface SecurityService {
    suspend fun status(): SecurityStatus
    suspend fun devices(): DevicesOverview
    suspend fun events(limit: Int = 60, filter: HistoryFilter = HistoryFilter()): List<SecurityEvent>
    /** The notification centre: unseen notices, or every notice (each with its seen flag) when [all]. */
    suspend fun notices(all: Boolean = false): List<SecurityEvent>
    /** Is this phone's session still alive? A session logged out from another device answers 401 and the phone signs itself out (see [call]). */
    suspend fun checkSession()
    suspend fun markNoticesSeen()
    suspend fun totpSetup(): TotpSetup
    /** Turns two-factor on; returns the recovery codes, shown once. */
    suspend fun totpConfirm(code: String): List<String>
    /** [code] is a current authenticator code or a recovery code. */
    suspend fun disableTwoFactor(code: String)
    suspend fun regenerateRecoveryCodes(code: String): List<String>
    suspend fun setRecoveryEmail(email: String)
    suspend fun verifyRecoveryEmail(code: String)
    suspend fun renameDevice(sessionId: String, name: String)
    suspend fun revokeDevice(sessionId: String)
    suspend fun revokeOtherDevices(): Int
    suspend fun revokeAllDevices(): Int
    /** Trusts THIS device after a verified code; the credential is kept by the device itself and sent at the next sign-in. */
    suspend fun trustThisDevice(code: String): String?
    suspend fun removeTrusted(id: String)
    /** Renames a device: every session it has had carries the new name. */
    suspend fun renameDeviceById(deviceId: String, name: String)
    /** Removes a device: logged out everywhere it is signed in, its trust ends. (The device in use is logged out with revokeDevice.) */
    suspend fun removeDevice(deviceId: String)
    /** Turns "approve new devices" on or off (needs a recently typed password). Turning it on trusts THIS device. */
    suspend fun setDeviceApproval(enabled: Boolean)

    suspend fun adminSessions(query: Map<String, String>): List<AdminSession>
    suspend fun adminRevokeSession(sessionId: String)
    suspend fun adminRevokeAll(personId: String): Int
    suspend fun adminApprovalLimits(): ApprovalLimits
    suspend fun saveApprovalLimits(limits: ApprovalLimits)
    suspend fun adminHistory(personId: String): List<SecurityEvent>
    suspend fun adminEvents(personId: String?, type: String?): List<SecurityEvent>
}

class HttpSecurityService(
    private val client: HttpClient,
    private val baseUrl: String,
    private val auth: AuthService,
    /** Where the trusted-device credential a successful trust request returns is kept (the auth service's secure store). */
    private val saveTrustedToken: (String) -> Unit = {},
) : SecurityService {

    private suspend fun call(method: HttpMethod, path: String, body: JsonObject? = null): JsonObject {
        val token = auth.idToken(false) ?: throw SecurityFailure("not-signed-in", "Your session has ended. Please sign in again.")
        val response = try {
            client.request("$baseUrl$path") {
                this.method = method
                header(HttpHeaders.Authorization, "Bearer $token")
                if (body != null) { contentType(ContentType.Application.Json); setBody(DocJson.encodeToString(JsonObject.serializer(), body)) }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            throw SecurityFailure("network", "No internet connection. Please check your connection and try again.")
        }
        val json = runCatching { DocJson.parseToJsonElement(response.bodyAsText()) as? JsonObject }.getOrNull()
        val code = response.status.value
        if (code in 200..299) return json ?: JsonObject(emptyMap())
        val error = json?.str("error").orEmpty()
        if (code == 401 && error == "session-ended") { auth.idToken(true); throw SecurityFailure("session-ended", "Your session has ended. Please sign in again.") }
        throw SecurityFailure(error.ifEmpty { "error-$code" }, when {
            error == "this-device" -> "This is the device you are using. Use Log out for it."
            error == "requires-recent-login" -> "Type your current password again to confirm."
            error == "invalid-code" -> "That code is not right."
            error == "not-enabled" -> "Two-factor sign-in is not turned on."
            error == "already-enabled" -> "Two-factor sign-in is already on."
            error == "nothing-to-confirm" -> "Nothing is waiting to be confirmed. Start again."
            error == "email-not-available" -> "This server cannot send e-mail yet."
            error == "bad-email" -> "Enter a valid e-mail address."
            error == "not-found" -> "That item no longer exists, or it is not yours to manage."
            code == 403 -> "You are not allowed to do this."
            code == 429 -> "Too many attempts. Please wait a few minutes."
            else -> "The server could not complete this ($code)."
        })
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.let { runCatching { it.boolean }.getOrDefault(false) } ?: false
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.list(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }

    /** A six-digit code is an authenticator code; anything else is a recovery code. */
    private fun proof(code: String): JsonObject {
        val v = code.trim()
        val digits = v.filter { !it.isWhitespace() }
        return buildJsonObject { if (digits.length == 6 && digits.all { it.isDigit() }) put("code", JsonPrimitive(digits)) else put("recoveryCode", JsonPrimitive(v)) }
    }

    private fun event(o: JsonObject) = SecurityEvent(
        id = o.str("id").orEmpty(), at = o.long("at"), type = o.str("type").orEmpty(), result = o.str("result") ?: "success",
        device = o.obj("meta")?.str("device"), by = o.str("by"), username = o.str("username"), personId = o.str("personId"),
        seen = o.bool("seen"),
    )

    override suspend fun status(): SecurityStatus {
        val r = call(HttpMethod.Get, "/v1/security/status")
        val t = r.obj("twoFactor")
        val email = t?.obj("recoveryEmail")
        return SecurityStatus(
            enabled = t?.bool("enabled") == true, totpConfigured = t?.bool("totpConfigured") == true, recoveryEmailMasked = email?.str("masked"), recoveryEmailVerified = email?.bool("verified") == true,
            recoveryCodesLeft = t?.long("recoveryCodesLeft")?.toInt() ?: 0, emailAvailable = r.bool("emailAvailable"), trustedDays = r.long("trustedDays").toInt(), trustedDevices = r.long("trustedDevices").toInt(),
            deviceApproval = r.bool("deviceApproval"),
        )
    }

    override suspend fun devices(): DevicesOverview {
        val r = call(HttpMethod.Get, "/v1/security/devices")
        return DevicesOverview(
            devices = r.list("devices").map { o ->
                DeviceSession(
                    sessionId = o.str("sessionId").orEmpty(), current = o.bool("current"), deviceId = o.str("deviceId"), name = o.str("name") ?: "Unknown device", model = o.str("model"), type = o.str("type") ?: "other",
                    os = o.str("os"), osVersion = o.str("osVersion"), appVersion = o.str("appVersion"), ip = o.str("ip"), location = o.str("location"), authMethod = o.str("authMethod"),
                    loginAt = o.long("loginAt"), lastActiveAt = o.long("lastActiveAt"), status = o.str("status") ?: "active", trusted = o.bool("trusted"), revokedBy = o.str("revokedBy"),
                )
            },
            trusted = r.list("trusted").map { TrustedDevice(it.str("id").orEmpty(), it.str("deviceId"), it.str("name") ?: "Device", it.long("since"), it.long("expiresAt")) },
        )
    }

    override suspend fun events(limit: Int, filter: HistoryFilter): List<SecurityEvent> {
        val qs = buildList {
            add("limit=$limit")
            filter.result?.let { add("result=${it.encodeURLParameter()}") }
            if (filter.types.isNotEmpty()) add("type=${filter.types.joinToString(",").encodeURLParameter()}")
            if (filter.device.isNotBlank()) add("q=${filter.device.trim().encodeURLParameter()}")
            filter.fromMs?.let { add("from=$it") }
            filter.toMs?.let { add("to=$it") }
        }.joinToString("&")
        return call(HttpMethod.Get, "/v1/security/events?$qs").list("events").map(::event)
    }
    override suspend fun notices(all: Boolean) = call(HttpMethod.Get, "/v1/security/notices" + if (all) "?all=1" else "").list("notices").map(::event)
    override suspend fun checkSession() { call(HttpMethod.Get, "/v1/auth/session") }
    override suspend fun markNoticesSeen() { call(HttpMethod.Post, "/v1/security/notices/seen", JsonObject(emptyMap())) }

    override suspend fun totpSetup(): TotpSetup {
        val r = call(HttpMethod.Post, "/v1/security/2fa/totp/setup", JsonObject(emptyMap()))
        return TotpSetup(r.str("secret").orEmpty(), r.str("otpauthUri").orEmpty())
    }

    override suspend fun totpConfirm(code: String) = call(HttpMethod.Post, "/v1/security/2fa/totp/confirm", buildJsonObject { put("code", JsonPrimitive(code.filter { !it.isWhitespace() })) }).strings("recoveryCodes")
    override suspend fun disableTwoFactor(code: String) { call(HttpMethod.Post, "/v1/security/2fa/disable", proof(code)) }
    override suspend fun regenerateRecoveryCodes(code: String) = call(HttpMethod.Post, "/v1/security/2fa/recovery-codes", proof(code)).strings("recoveryCodes")
    override suspend fun setRecoveryEmail(email: String) { call(HttpMethod.Post, "/v1/security/recovery-email", buildJsonObject { put("email", JsonPrimitive(email.trim())) }) }
    override suspend fun verifyRecoveryEmail(code: String) { call(HttpMethod.Post, "/v1/security/recovery-email/verify", buildJsonObject { put("code", JsonPrimitive(code.trim())) }) }
    override suspend fun renameDevice(sessionId: String, name: String) { call(HttpMethod.Post, "/v1/security/devices/${sessionId.encodeURLParameter()}/rename", buildJsonObject { put("name", JsonPrimitive(name.trim())) }) }
    override suspend fun revokeDevice(sessionId: String) { call(HttpMethod.Post, "/v1/security/devices/${sessionId.encodeURLParameter()}/revoke", JsonObject(emptyMap())) }
    override suspend fun revokeOtherDevices() = call(HttpMethod.Post, "/v1/security/devices/revoke-others", JsonObject(emptyMap())).long("revoked").toInt()
    override suspend fun revokeAllDevices() = call(HttpMethod.Post, "/v1/security/devices/revoke-all", JsonObject(emptyMap())).long("revoked").toInt()

    override suspend fun trustThisDevice(code: String): String? {
        val token = call(HttpMethod.Post, "/v1/security/trusted/current", proof(code)).str("trustedToken")
        token?.let(saveTrustedToken)
        return token
    }

    override suspend fun setDeviceApproval(enabled: Boolean) {
        val token = call(HttpMethod.Post, "/v1/security/device-approval", buildJsonObject { put("enabled", JsonPrimitive(enabled)) }).str("trustedToken")
        token?.let(saveTrustedToken)
    }

    override suspend fun renameDeviceById(deviceId: String, name: String) { call(HttpMethod.Post, "/v1/security/device/rename", buildJsonObject { put("deviceId", JsonPrimitive(deviceId)); put("name", JsonPrimitive(name.trim())) }) }
    override suspend fun removeDevice(deviceId: String) { call(HttpMethod.Post, "/v1/security/device/remove", buildJsonObject { put("deviceId", JsonPrimitive(deviceId)) }) }

    override suspend fun removeTrusted(id: String) { call(HttpMethod.Post, "/v1/security/trusted/${id.encodeURLParameter()}/remove", JsonObject(emptyMap())) }

    override suspend fun adminSessions(query: Map<String, String>): List<AdminSession> {
        val qs = query.filterValues { it.isNotBlank() }.entries.joinToString("&") { "${it.key}=${it.value.encodeURLParameter()}" }
        return call(HttpMethod.Get, "/v1/admin/security/sessions" + if (qs.isEmpty()) "" else "?$qs").list("sessions").map { o ->
            AdminSession(
                sessionId = o.str("sessionId").orEmpty(), personId = o.str("personId").orEmpty(), username = o.str("username"), name = o.str("name"), congregation = o.str("congregation"),
                roles = o.strings("roles"), deviceName = o.str("deviceName") ?: "Unknown device", type = o.str("type") ?: "other", os = o.str("os"), osVersion = o.str("osVersion"),
                appVersion = o.str("appVersion"), loginAt = o.long("loginAt"), lastActiveAt = o.long("lastActiveAt"), status = o.str("status") ?: "active", authMethod = o.str("authMethod"),
            )
        }
    }

    override suspend fun adminRevokeSession(sessionId: String) { call(HttpMethod.Post, "/v1/admin/security/sessions/${sessionId.encodeURLParameter()}/revoke", JsonObject(emptyMap())) }
    override suspend fun adminRevokeAll(personId: String) = call(HttpMethod.Post, "/v1/admin/security/users/${personId.encodeURLParameter()}/revoke-all", JsonObject(emptyMap())).long("revoked").toInt()
    override suspend fun adminApprovalLimits(): ApprovalLimits {
        val s = call(HttpMethod.Get, "/v1/admin/security/approval-limits").obj("settings")
        fun n(key: String, standard: Int) = s?.long(key)?.toInt()?.takeIf { it > 0 } ?: standard
        return ApprovalLimits(
            n("maxRequests", 5), n("windowMinutes", 15), n("expiryMinutes", 5), n("passwordMaxFails", 10), n("passwordWindowMinutes", 15),
            n("twoFactorMaxTries", 5), n("twoFactorMinutes", 10), n("webLoginMax", 10), n("webLoginMinutes", 1),
        )
    }
    override suspend fun saveApprovalLimits(limits: ApprovalLimits) {
        call(HttpMethod.Post, "/v1/admin/security/approval-limits", buildJsonObject {
            put("maxRequests", JsonPrimitive(limits.maxRequests)); put("windowMinutes", JsonPrimitive(limits.windowMinutes)); put("expiryMinutes", JsonPrimitive(limits.expiryMinutes))
            put("passwordMaxFails", JsonPrimitive(limits.passwordMaxFails)); put("passwordWindowMinutes", JsonPrimitive(limits.passwordWindowMinutes))
            put("twoFactorMaxTries", JsonPrimitive(limits.twoFactorMaxTries)); put("twoFactorMinutes", JsonPrimitive(limits.twoFactorMinutes))
            put("webLoginMax", JsonPrimitive(limits.webLoginMax)); put("webLoginMinutes", JsonPrimitive(limits.webLoginMinutes))
        })
    }
    override suspend fun adminHistory(personId: String) = call(HttpMethod.Get, "/v1/admin/security/users/${personId.encodeURLParameter()}/history").list("events").map(::event)
    override suspend fun adminEvents(personId: String?, type: String?): List<SecurityEvent> {
        val qs = listOfNotNull(personId?.takeIf { it.isNotBlank() }?.let { "personId=${it.encodeURLParameter()}" }, type?.takeIf { it.isNotBlank() }?.let { "type=${it.encodeURLParameter()}" }).joinToString("&")
        return call(HttpMethod.Get, "/v1/admin/security/events" + if (qs.isEmpty()) "" else "?$qs").list("events").map(::event)
    }
}

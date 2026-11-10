package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.json.DocJson
import com.emfitsolutions.gopreach.data.remote.SyncApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Where an enrolled account stands with its temporary password, as the SERVER decided it from the sign-in itself (never from a session, and never the password):
 * [TEMP_UNUSED] the temporary password has not been used yet, [TEMP_USED] they signed in with it and the change is still due, [PASSWORD_CHANGED] they set their own,
 * [DISABLED] the account may not sign in. The same four the website shows.
 */
enum class PasswordStatus(val label: String) {
    TEMP_UNUSED("Temporary password - not used yet"),
    TEMP_USED("Temporary password used - change due"),
    PASSWORD_CHANGED("Password changed"),
    DISABLED("Account disabled"),
}

data class AccountStatusInfo(val status: PasswordStatus, val firstLoginAt: Long?, val lastLoginAt: Long?, val passwordChangedAt: Long?)

/** `GET /v1/auth/account-status`: only the accounts the signed-in person may manage (Super Admin: all; an admin role: their congregation; an FS Group officer: their group). */
class AccountStatusRepository(private val api: SyncApi) {
    private val _statuses = MutableStateFlow<Map<String, AccountStatusInfo>>(emptyMap())
    val statuses: StateFlow<Map<String, AccountStatusInfo>> = _statuses.asStateFlow()

    /** Fetches the current statuses. A failure (offline, an older server) keeps what is shown; it never throws. */
    suspend fun refresh() {
        try {
            val reply = api.callServer("GET", "/v1/auth/account-status")
            if (reply.ok) _statuses.value = parse(reply.text)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Forgets everything (another account signed in: the previous person's view must never show). */
    fun clear() { _statuses.value = emptyMap() }

    companion object {
        fun parse(text: String): Map<String, AccountStatusInfo> {
            val statuses = (DocJson.parseToJsonElement(text) as? JsonObject)?.get("statuses") as? JsonObject ?: return emptyMap()
            val out = HashMap<String, AccountStatusInfo>()
            for ((personId, value) in statuses) {
                val o = value as? JsonObject ?: continue
                val status = runCatching { PasswordStatus.valueOf(o["status"]?.jsonPrimitive?.contentOrNull.orEmpty()) }.getOrNull() ?: continue
                fun long(k: String) = (o[k] as? JsonPrimitive)?.longOrNull
                out[personId] = AccountStatusInfo(status, long("firstLoginAt"), long("lastLoginAt"), long("passwordChangedAt"))
            }
            return out
        }
    }
}

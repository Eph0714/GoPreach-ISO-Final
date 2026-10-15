package com.emfitsolutions.gopreach.data.remote

import com.emfitsolutions.gopreach.platform.nowMillis
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The authoritative date and time, asked of the server, so which service months may be reported never depends only on the user's device
 * clock. If the server cannot be reached the device clock is used and the reading says so; the server checks the month again when the
 * report is actually sent, so a wrong device clock can never get a future month through.
 */
class ServerClock(private val api: SyncApi) {
    data class Reading(val now: Long, val fromServer: Boolean)

    suspend fun read(): Reading = runCatching {
        val reply = api.postJson("/v1/time", JsonObject(emptyMap()))
        val server = if (reply.status in 200..299) reply.body?.get("now")?.jsonPrimitive?.longOrNull else null
        if (server != null) Reading(server, true) else Reading(nowMillis(), false)
    }.getOrElse { Reading(nowMillis(), false) }
}

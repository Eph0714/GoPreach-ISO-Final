package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.platform.nowMillis

/**
 * One entry made once, not three times: a brand-new record (blank id) that is saved again with the very same content by the very same
 * person within [windowMillis] is the same entry arriving twice - a repeated tap on a slow device, a queued click, a re-delivered
 * callback - not a second entry. [idFor] hands back the id the first save used, so the repeat overwrites that document instead of
 * adding another. Different content, another person, or a later time is always a new entry. In memory only: it guards one session.
 */
class RecentCreateGuard(private val windowMillis: Long = 30_000, private val clock: () -> Long = ::nowMillis) {
    private data class Seen(val id: String, val at: Long)

    private val seen = HashMap<String, Seen>()
    private val lock = GuardLock()

    /** The id to save a brand-new record under: the earlier id when [signature] was already created within the window, else [newId]. */
    fun idFor(signature: String, newId: () -> String): String = lock.withLock {
        val now = clock()
        seen.entries.removeAll { now - it.value.at > windowMillis }
        val earlier = seen[signature]
        if (earlier != null) return@withLock earlier.id
        val id = newId()
        seen[signature] = Seen(id, now)
        id
    }
}

/** A small mutual-exclusion lock for the guard (Android: a synchronized block, iOS: an NSLock). */
internal expect class GuardLock() {
    fun <T> withLock(block: () -> T): T
}

/** One guard for the whole app: every repository that creates a record from a form asks it for the id of a brand-new entry. */
internal val RecentCreates = RecentCreateGuard()

/**
 * The id for a brand-new [entity] of [collection]: the same entity (same content apart from its own timestamps) created again within the
 * window gets the same id, so a repeated save overwrites rather than adds a copy.
 */
internal fun RecentCreateGuard.idFor(collection: String, entity: Any, newId: () -> String): String =
    idFor(collection + "|" + entity.toString().replace(Regex("""\b(createdAt|updatedAt|stageEnteredAt|gpsCapturedAt|gpsUpdatedAt)=[^,)]*"""), ""), newId)

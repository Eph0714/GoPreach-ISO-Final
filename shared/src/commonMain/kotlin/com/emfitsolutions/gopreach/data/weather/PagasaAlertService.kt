package com.emfitsolutions.gopreach.data.weather

import com.emfitsolutions.gopreach.domain.weather.CapParser
import com.emfitsolutions.gopreach.domain.weather.OfficialAlert
import com.emfitsolutions.gopreach.domain.weather.WeatherException
import com.emfitsolutions.gopreach.domain.weather.WeatherFailure
import com.emfitsolutions.gopreach.domain.weather.WeatherPlace
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException

/** Official warnings for a place. Kept as an interface so screens and tests never depend on the network. */
interface OfficialAlertService {
    /** Active official alerts whose area contains [place], most severe first. Throws [WeatherException] when the source cannot be read. */
    suspend fun alertsFor(place: WeatherPlace, nowMillis: Long, force: Boolean = false): List<OfficialAlert>
}

/**
 * PAGASA public alert feed (https://publicalert.pagasa.dost.gov.ph/feeds/), an Atom feed of CAP documents.
 * The feed states "Licensed under CC BY 4.0", so the screen shows the attribution. The whole feed is fetched once and
 * reused for [TTL_MS], then matched to each place by area polygon on the device; no coordinates are sent anywhere.
 */
class PagasaAlertService(
    private val client: HttpClient,
    private val feedUrl: String = FEED_URL,
    private val timeoutMillis: Long = 25_000L,
) : OfficialAlertService {
    private val lock = Mutex()
    private var cache: List<OfficialAlert> = emptyList()
    private var cachedAt = Long.MIN_VALUE

    override suspend fun alertsFor(place: WeatherPlace, nowMillis: Long, force: Boolean): List<OfficialAlert> {
        val all = lock.withLock {
            val age = nowMillis - cachedAt
            if (age !in 0..(if (force) MIN_REFETCH_MS else TTL_MS)) {
                cache = load(nowMillis)
                cachedAt = nowMillis
            }
            cache
        }
        return all.filter { it.isActive(nowMillis) && it.covers(place.lat, place.lng) }
            .sortedWith(compareByDescending<OfficialAlert> { it.severityRank }.thenByDescending { it.sentMillis ?: 0L })
    }

    private suspend fun load(now: Long): List<OfficialAlert> {
        try {
            return withTimeout(timeoutMillis) {
                val response = client.get(feedUrl)
                if (response.status.value !in 200..299) throw WeatherException(WeatherFailure.SERVER, "The PAGASA alert service answered with an error (${response.status.value}).")
                val entries = parseFeed(response.bodyAsText()).filter { (it.updatedMillis ?: now) > now - MAX_AGE_MS }
                val gate = Semaphore(PARALLEL)
                val documents = coroutineScope {
                    entries.map { e ->
                        async {
                            gate.withPermit {
                                try {
                                    client.get(e.url).takeIf { it.status.value in 200..299 }?.bodyAsText()?.let(CapParser::parse)
                                } catch (c: CancellationException) {
                                    throw c
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        }
                    }.awaitAll()
                }
                if (entries.isNotEmpty() && documents.all { it == null }) throw WeatherException(WeatherFailure.BAD_RESPONSE, "The PAGASA alerts could not be read.")
                documents.filterNotNull().distinctBy { it.id }
            }
        } catch (e: WeatherException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            throw WeatherException(WeatherFailure.TIMEOUT, "The PAGASA alert service took too long to answer.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WeatherException(WeatherFailure.OFFLINE, "Could not reach the PAGASA alert service. Check your internet connection.", e)
        }
    }

    internal data class FeedEntry(val url: String, val updatedMillis: Long?)

    companion object {
        const val FEED_URL = "https://publicalert.pagasa.dost.gov.ph/feeds/"
        const val TTL_MS = 5 * 60_000L
        const val MIN_REFETCH_MS = 30_000L

        /** Alerts expire, but a feed entry older than this is not worth downloading. */
        const val MAX_AGE_MS = 48 * 3_600_000L
        private const val PARALLEL = 6

        internal fun parseFeed(xml: String): List<FeedEntry> =
            Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
                val body = m.groupValues[1]
                val url = Regex("<link[^>]*href=\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.firstOrNull { it.endsWith(".cap") } ?: return@mapNotNull null
                val updated = Regex("<updated>([^<]+)</updated>").find(body)?.groupValues?.get(1)?.let(CapParser::time)
                FeedEntry(url.replace("&amp;", "&"), updated)
            }.toList()
    }
}

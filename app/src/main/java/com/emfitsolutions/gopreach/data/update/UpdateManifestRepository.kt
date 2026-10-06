package com.emfitsolutions.gopreach.data.update

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UpdateManifest"

/**
 * The "update server" for GoPreach's auto-update system is GitHub Releases
 * itself — not a bespoke backend. GitHub already publishes exactly the fields
 * a proper update manifest needs (version, a stable per-release download URL,
 * release notes, release date, and a SHA-256 digest of the asset) via a free,
 * publicly-readable, unauthenticated, always-current API endpoint:
 * `repos/{owner}/{repo}/releases/latest`. Standing up and hosting a separate
 * update server (with its own uptime, security, and release-publishing
 * tooling to maintain) would just be reimplementing what this endpoint
 * already does reliably — GitHub's CDN serves the actual APK bytes too, so
 * there's no separate file host to run either. Publishing v1.2.0, v1.3.0,
 * etc. later is just `gh release create vX.Y.Z <apk>`; nothing here needs to
 * change to pick that up, since this always asks for "latest".
 */
@Singleton
class UpdateManifestRepository @Inject constructor(
    private val gson: Gson,
    private val cache: UpdateManifestCache,
) {
    /** Public GitHub repo — no auth token needed for read access. */
    private val manifestUrl = "https://api.github.com/repos/Eph0714/GoPreach/releases/latest"

    /**
     * GitHub's stable "always the newest asset" redirect — lives on
     * `github.com` itself, not `api.github.com`, so it is **not** subject to
     * the REST API's 60-requests/hour-per-IP limit at all. Used as a
     * fallback below when the API call fails for any reason: some mobile
     * carriers/regions throttle or block `api.github.com` specifically
     * (bot/scraping mitigation) while plain `github.com` stays reachable, so
     * this can succeed even when the richer API call can't.
     */
    private val stableApkUrl = "https://github.com/Eph0714/GoPreach/releases/latest/download/GoPreach.apk"

    private data class GithubAsset(
        val name: String?,
        @SerializedName("browser_download_url") val browserDownloadUrl: String?,
        val digest: String?,
    )

    private data class GithubRelease(
        @SerializedName("tag_name") val tagName: String?,
        val body: String?,
        @SerializedName("published_at") val publishedAt: String?,
        val assets: List<GithubAsset>?,
    )

    suspend fun fetchLatest(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        val apiResult = runCatching { fetchFromApi() }
        apiResult.getOrNull()?.let { return@withContext Result.success(it) }
        val apiError = apiResult.exceptionOrNull()!!
        Log.w(TAG, "API fetch failed (${apiError.message}) — trying the stable redirect URL instead")

        // Wrapped in its own runCatching so a failure *here* (e.g. the same
        // "no connection at all" that broke the API call) can't clobber
        // [apiError] — whatever the API call's own exception said is almost
        // always the more informative one to eventually report (rate limit,
        // captive portal, malformed response, etc.).
        val fallback = runCatching { fetchFromStableRedirect() }.getOrNull()
        if (fallback != null) return@withContext Result.success(fallback)

        // Both paths failed outright — a stale-but-usable cached link still
        // beats a hard failure, especially for "Share App", which just
        // needs *a* working download link, not necessarily the newest.
        val cached = cache.read()
        if (cached != null) {
            Log.w(TAG, "Both update paths failed — serving cached release ${cached.version} instead")
            return@withContext Result.success(cached)
        }

        // Log.e, not Log.w — a silent [UpdateViewModel.check] drops this
        // straight to Idle with no UI of any kind (by design: don't
        // surface a transient network blip as an error dialog), which
        // means this log line is the *only* place the actual cause is
        // ever recorded.
        Log.e(TAG, "Update check failed", apiError)
        Result.failure(Exception(friendlyMessage(apiError)))
    }

    /** Maps a raw network exception to the specific, actionable message a
     * Publisher actually sees — "Couldn't fetch the latest download link.
     * Check your connection and try again" used to show for *every* kind of
     * failure (offline, DNS, TLS, rate-limited, malformed response), which
     * made a real, fixable server-side problem look identical to "your
     * phone has no signal." [fetchFromApi] already throws a specific,
     * pre-written message for the failures it can identify precisely (rate
     * limit, captive portal, missing asset, HTTP errors); this only covers
     * the raw JVM/IO exceptions those don't catch. */
    private fun friendlyMessage(e: Throwable): String = when (e) {
        is java.net.UnknownHostException ->
            "No internet connection. Please check your Wi-Fi or mobile data."
        is java.net.SocketTimeoutException ->
            "The server took too long to respond. Please try again."
        is javax.net.ssl.SSLException ->
            "Couldn't establish a secure connection. Please try again."
        is java.net.ConnectException ->
            "Couldn't reach GitHub. Please check your connection and try again."
        else -> e.message?.takeUnless { it.isBlank() }
            ?: "Couldn't fetch the latest download link. Check your connection and try again."
    }

    /** The primary path: GitHub's REST API, which also gives per-release
     * notes/date/checksum that the [stableApkUrl] redirect can't. Throws on
     * any failure rather than returning a [Result] — [fetchLatest] is what
     * decides whether to fall back or give up. */
    private fun fetchFromApi(): UpdateInfo {
        val connection = URL(manifestUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        // GitHub's REST API documents this as a hard requirement — a
        // request with no (or an unrecognized) User-Agent can be
        // rejected outright. HttpURLConnection's own JVM-default
        // User-Agent isn't guaranteed to satisfy that on every Android
        // build, so this is set explicitly rather than left to chance —
        // a request silently rejected here is exactly what "automatic
        // detection of new update is not working" looks like from the
        // user's side (nothing is shown either way; see [Idle]'s
        // handling below for why a failed *check* stays silent).
        connection.setRequestProperty("User-Agent", "GoPreach-Android")
        // A conditional request GitHub answers with 304 Not Modified
        // does NOT count against the 60-requests/hour-per-IP limit —
        // see [UpdateManifestCache]'s doc comment for why this is the
        // actual fix for devices sharing a network with others.
        cache.etag?.let { connection.setRequestProperty("If-None-Match", it) }
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000

        val responseCode = connection.responseCode

        if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
            cache.read()?.let { return it }
            // No local cache to serve (cleared data, etc.) despite the ETag
            // matching — nothing to return; fall through to a hard failure
            // below since there's genuinely nothing left to try here.
        }

        if (responseCode !in 200..299) {
            // GitHub's unauthenticated REST API is capped at 60
            // requests/hour per IP; exceeding it returns 403 with
            // X-RateLimit-Remaining: 0. Called out specifically (rather
            // than folded into the generic HTTP-error message below) so
            // it's diagnosable from a logcat capture instead of looking
            // identical to every other kind of failure.
            val rateLimited = responseCode == 403 && connection.getHeaderField("X-RateLimit-Remaining") == "0"
            val message = if (rateLimited) {
                "GitHub download limit reached for this network — please try again in a few minutes"
            } else {
                val errorBody = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                "Update check failed (HTTP $responseCode)${errorBody?.let { ": $it" } ?: ""}"
            }
            throw Exception(message)
        }

        val newEtag = connection.getHeaderField("ETag")
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        // A captive Wi-Fi portal (airport/hotel/guest network) answers *any*
        // HTTPS request with its own HTML login page and a 200 status — Gson
        // would otherwise happily "parse" that HTML into a GithubRelease with
        // every field null (it doesn't validate against Kotlin's non-null
        // types, only reflection-assigns whatever JSON keys happen to
        // match), which then failed later with a bare, unhelpful
        // NullPointerException. Checking the body actually looks like JSON
        // first turns that into one clear, diagnosable message instead.
        if (body.trimStart().firstOrNull() != '{') {
            throw Exception("Received an unexpected response — you may be on a Wi-Fi network that needs sign-in")
        }
        val release = gson.fromJson(body, GithubRelease::class.java)
        val tagName = release.tagName?.takeUnless { it.isBlank() }
            ?: throw Exception("Update check failed: response was missing a version")
        // The APK asset — the one non-source-code file GitHub attaches to the release.
        val asset = release.assets?.firstOrNull { it.name?.endsWith(".apk") == true && it.browserDownloadUrl != null }
            ?: throw Exception("Latest release has no APK asset")

        val notes = release.body?.trim().takeUnless { it.isNullOrBlank() } ?: "Bug fixes and improvements."
        val info = UpdateInfo(
            version = tagName.removePrefix("v"),
            apkUrl = asset.browserDownloadUrl!!,
            releaseNotes = notes,
            releaseDate = release.publishedAt?.substringBefore("T") ?: "",
            sha256 = asset.digest?.removePrefix("sha256:"),
            // "Required/Critical Update" — see UpdateInfo.isCritical's
            // own doc comment for the `[CRITICAL]` marker convention.
            isCritical = notes.contains("[CRITICAL]", ignoreCase = true),
        )
        cache.save(newEtag, info)
        return info
    }

    /** The fallback path used when [fetchFromApi] fails for any reason —
     * resolves [stableApkUrl]'s redirect to read off the current version,
     * without touching `api.github.com` (and its rate limit) or parsing any
     * JSON at all. No release notes/checksum this way (GitHub's redirect
     * doesn't carry them), but a working download link is the whole point:
     * this is what actually gets "Share App"/"Check for Updates" a real
     * link on a network where the API path is blocked or exhausted. Returns
     * null (rather than throwing) when even this can't resolve a version,
     * so [fetchLatest] can report the *original* API error instead of this
     * fallback's own. */
    private fun fetchFromStableRedirect(): UpdateInfo? {
        val connection = URL(stableApkUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "GoPreach-Android")
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000

        val responseCode = connection.responseCode
        if (responseCode !in 300..399) return null
        // e.g. "https://github.com/Eph0714/GoPreach/releases/download/v1.117.0/GoPreach.apk"
        val location = connection.getHeaderField("Location") ?: return null
        val tag = Regex("/releases/download/([^/]+)/").find(location)?.groupValues?.get(1) ?: return null

        Log.w(TAG, "Resolved latest release via stable redirect: $tag")
        return UpdateInfo(
            version = tag.removePrefix("v"),
            apkUrl = stableApkUrl,
            releaseNotes = "Bug fixes and improvements.",
            releaseDate = "",
            sha256 = null,
            isCritical = false,
        )
    }

    /** True when [latestVersion] is newer than [currentVersion] — both plain
     * dotted version strings (e.g. "1.0.0"), no "v" prefix. Compares numerically
     * per segment rather than as a string, so "1.9.0" < "1.10.0" correctly. */
    fun isNewer(currentVersion: String, latestVersion: String): Boolean {
        val current = currentVersion.split(".").map { it.toIntOrNull() ?: 0 }
        val latest = latestVersion.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(current.size, latest.size)) {
            val c = current.getOrElse(i) { 0 }
            val l = latest.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        return false
    }
}

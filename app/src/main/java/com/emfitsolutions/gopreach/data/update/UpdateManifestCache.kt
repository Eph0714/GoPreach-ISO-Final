package com.emfitsolutions.gopreach.data.update

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "gopreach_update_manifest_cache"
private const val KEY_ETAG = "etag"
private const val KEY_VERSION = "version"
private const val KEY_APK_URL = "apk_url"
private const val KEY_RELEASE_NOTES = "release_notes"
private const val KEY_RELEASE_DATE = "release_date"
private const val KEY_SHA256 = "sha256"
private const val KEY_IS_CRITICAL = "is_critical"

/**
 * The last successful `releases/latest` response this device saw — this is
 * what actually fixes "Couldn't fetch the latest download link" on devices
 * sharing a network (a Kingdom Hall Wi-Fi, say) with others running
 * GoPreach: GitHub's unauthenticated REST API is capped at 60 requests/hour
 * **per IP**, shared by every device behind the same router, and every
 * automatic check, manual "Check for Updates" tap, and "Share App" tap was
 * an unconditional GET that always counted against it.
 *
 * GitHub does not count a *conditional* request (one sent with
 * `If-None-Match: <etag>` that comes back `304 Not Modified`) against the
 * rate limit at all. Caching the ETag here and always sending it means every
 * check after the first one on a given device is free — and if the shared
 * IP's limit is already exhausted when a request does need a real answer,
 * [UpdateManifestRepository] falls back to whatever's cached here instead of
 * failing outright, so the device still has *a* working download link.
 */
@Singleton
class UpdateManifestCache @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val etag: String? get() = prefs.getString(KEY_ETAG, null)

    fun read(): UpdateInfo? {
        val version = prefs.getString(KEY_VERSION, null) ?: return null
        val apkUrl = prefs.getString(KEY_APK_URL, null) ?: return null
        return UpdateInfo(
            version = version,
            apkUrl = apkUrl,
            releaseNotes = prefs.getString(KEY_RELEASE_NOTES, "").orEmpty(),
            releaseDate = prefs.getString(KEY_RELEASE_DATE, "").orEmpty(),
            sha256 = prefs.getString(KEY_SHA256, null),
            isCritical = prefs.getBoolean(KEY_IS_CRITICAL, false),
        )
    }

    fun save(etag: String?, info: UpdateInfo) {
        prefs.edit {
            putString(KEY_ETAG, etag)
            putString(KEY_VERSION, info.version)
            putString(KEY_APK_URL, info.apkUrl)
            putString(KEY_RELEASE_NOTES, info.releaseNotes)
            putString(KEY_RELEASE_DATE, info.releaseDate)
            putString(KEY_SHA256, info.sha256)
            putBoolean(KEY_IS_CRITICAL, info.isCritical)
        }
    }
}

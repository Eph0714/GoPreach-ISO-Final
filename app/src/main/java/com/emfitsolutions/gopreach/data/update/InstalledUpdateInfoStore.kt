package com.emfitsolutions.gopreach.data.update

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What [InstalledUpdateInfoStore] remembers about the most recent update
 * this app installed through its own in-app updater — the Settings
 * screen's "App Version" section reads this back to show "details of the
 * newly installed update" once [version] matches the app's own running
 * [com.emfitsolutions.gopreach.BuildConfig.VERSION_NAME]. */
data class InstalledUpdateInfo(
    val version: String,
    val releaseNotes: String,
    val apkUrl: String,
    val installedAt: Long,
)

private const val PREFS_NAME = "gopreach_update_history"
private const val KEY_VERSION = "installed_version"
private const val KEY_RELEASE_NOTES = "installed_release_notes"
private const val KEY_APK_URL = "installed_apk_url"
private const val KEY_INSTALLED_AT = "installed_at"

/**
 * "Add a details of the newly installed update. Add a link for the updated
 * apk file after installation so that the user can share the app to
 * others" — recorded the moment [com.emfitsolutions.gopreach.ui.components
 * .update.UpdateViewModel.updateNow] hands the downloaded APK off to
 * Android's Package Installer (the last point this app is definitely still
 * running to write it — see that call site's comment), then read back by
 * Settings on every later launch. Local/per-device only, same pattern as
 * [com.emfitsolutions.gopreach.data.repository.ThemePreferenceRepository] —
 * this is a record of what *this device* installed, not account data.
 */
@Singleton
class InstalledUpdateInfoStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(info: InstalledUpdateInfo) {
        prefs.edit {
            putString(KEY_VERSION, info.version)
            putString(KEY_RELEASE_NOTES, info.releaseNotes)
            putString(KEY_APK_URL, info.apkUrl)
            putLong(KEY_INSTALLED_AT, info.installedAt)
        }
    }

    /** Null until the very first in-app update completes on this device —
     * an app that arrived via Play/manual sideload/a fresh install has no
     * history yet, which the Settings screen shows a plain fallback for. */
    fun read(): InstalledUpdateInfo? {
        val version = prefs.getString(KEY_VERSION, null) ?: return null
        return InstalledUpdateInfo(
            version = version,
            releaseNotes = prefs.getString(KEY_RELEASE_NOTES, "").orEmpty(),
            apkUrl = prefs.getString(KEY_APK_URL, "").orEmpty(),
            installedAt = prefs.getLong(KEY_INSTALLED_AT, 0L),
        )
    }
}

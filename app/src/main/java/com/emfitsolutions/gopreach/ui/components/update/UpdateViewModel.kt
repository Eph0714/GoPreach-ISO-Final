package com.emfitsolutions.gopreach.ui.components.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.BuildConfig
import com.emfitsolutions.gopreach.data.repository.AuthRepository
import com.emfitsolutions.gopreach.data.sync.ConnectivityObserver
import com.emfitsolutions.gopreach.data.update.ApkDownloader
import com.emfitsolutions.gopreach.data.update.DownloadEvent
import com.emfitsolutions.gopreach.data.update.InstalledUpdateInfo
import com.emfitsolutions.gopreach.data.update.InstalledUpdateInfoStore
import com.emfitsolutions.gopreach.data.update.UpdateInfo
import com.emfitsolutions.gopreach.data.update.UpdateInstaller
import com.emfitsolutions.gopreach.data.update.UpdateManifestRepository
import com.emfitsolutions.gopreach.data.update.UpdateReminderStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

private const val TAG = "UpdateViewModel"

/** Mirrors the exact flow the app's auto-update UI walks through. */
sealed class UpdateCheckState {
    data object Idle : UpdateCheckState()
    data object Checking : UpdateCheckState()
    data class UpToDate(val version: String) : UpdateCheckState()
    data class Available(val info: UpdateInfo, val currentVersion: String) : UpdateCheckState()
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateCheckState()
    data class ReadyToInstall(val info: UpdateInfo, val apkFile: File) : UpdateCheckState()
    data class Failed(val info: UpdateInfo?, val message: String) : UpdateCheckState()
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manifestRepository: UpdateManifestRepository,
    private val downloader: ApkDownloader,
    private val installer: UpdateInstaller,
    private val installedUpdateInfoStore: InstalledUpdateInfoStore,
    private val reminderStore: UpdateReminderStore,
    private val authRepository: AuthRepository,
    connectivityObserver: ConnectivityObserver,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val state: StateFlow<UpdateCheckState> = _state.asStateFlow()

    /** Bug fix ("auto update available is not working"): [checkOnAppStart]
     * only ever fires once per *process*, and the offline→online trigger
     * below only fires on an actual connectivity transition — neither one
     * fires just from the user backgrounding and re-foregrounding the app,
     * which is by far the most common way a phone is actually used
     * (process death, the only thing that would re-run [checkOnAppStart],
     * is comparatively rare while the app stays resident in memory). A
     * device that's continuously online could go a long time — hours,
     * sometimes days — between real automatic checks, which read as "auto
     * update available is not working" even though a genuinely newer
     * release existed the whole time. [checkOnAppForeground] (wired from
     * MainActivity's own Activity lifecycle — a single-Activity app, so
     * that's equivalent to "the app just came to the foreground") adds that
     * missing trigger; every automatic path now funnels through
     * [maybeAutoCheck] so they share one throttle instead of each needing
     * its own guard, and don't hammer GitHub's 60-requests/hour
     * unauthenticated rate limit on rapid app-switching. */
    private var lastAutoCheckAtMillis = 0L
    private val autoCheckThrottleMillis = java.util.concurrent.TimeUnit.MINUTES.toMillis(15)
    private val currentVersion: String get() = BuildConfig.VERSION_NAME

    private fun maybeAutoCheck() {
        val now = System.currentTimeMillis()
        if (now - lastAutoCheckAtMillis < autoCheckThrottleMillis) return
        lastAutoCheckAtMillis = now
        check(silent = true)
    }

    /** "Add a details of the newly installed update" — Settings' "App
     * Version" section. Non-null only when the last update this app
     * installed *through its own updater* is the version actually running
     * right now (see [InstalledUpdateInfoStore]'s doc comment for why that
     * can be null: a fresh install/sideload/Play install has no such
     * history yet). */
    val installedUpdateInfo: InstalledUpdateInfo?
        get() = installedUpdateInfoStore.read()?.takeIf { it.version == currentVersion }

    init {
        // "Refresh, Automatic Updates, Offline Sync" spec §12: "When the device
        // becomes online: Automatically check for application updates" — entirely
        // separate from [checkOnAppStart] (the once-per-process check) and never
        // triggered by Refresh or Sync to Server. Only fires on an actual
        // offline→online *transition*, not on the initial subscribe (which would
        // otherwise double up with checkOnAppStart for a launch that's already
        // online) and not repeatedly while already online.
        var wasOnline: Boolean? = null
        connectivityObserver.observe()
            .onEach { online ->
                val previouslyOffline = wasOnline == false
                wasOnline = online
                if (online && previouslyOffline) maybeAutoCheck()
            }
            .launchIn(viewModelScope)
    }

    /** Called once from the app's root composable — checks silently and only
     * surfaces anything when an update genuinely exists, so a fresh launch
     * that's already current shows nothing at all (spec: don't repeatedly
     * annoy the user with unnecessary notifications). */
    fun checkOnAppStart() = maybeAutoCheck()

    /** Called every time the app returns to the foreground (see
     * [lastAutoCheckAtMillis]'s doc comment for why this trigger was
     * missing) — throttled the same way every other automatic trigger is,
     * so switching back to GoPreach repeatedly doesn't re-check every time. */
    fun checkOnAppForeground() = maybeAutoCheck()

    /** "Upon User Login... immediately after the user successfully logs in,
     * the system should check whether a newer version is available" —
     * called from LoginScreen the moment sign-in succeeds. Shares
     * [maybeAutoCheck]'s throttle with every other automatic trigger, so it
     * doesn't duplicate whatever [checkOnAppStart] already did moments
     * earlier for the same app launch — this mainly matters for a session
     * that's been running a while (biometric re-login, switching accounts)
     * where enough time has passed for a genuinely fresh check to be worth
     * it. */
    fun checkOnLogin() = maybeAutoCheck()

    /** Called from Settings' "Check for Updates" — always shows a result,
     * including the explicit "GoPreach is up to date" message, and ignores
     * any "Remind Me Later" snooze in effect (the user asked directly). */
    fun checkManually() = check(silent = false)

    private fun check(silent: Boolean) {
        _state.value = UpdateCheckState.Checking
        viewModelScope.launch {
            manifestRepository.fetchLatest()
                .onSuccess { info ->
                    val isNewer = manifestRepository.isNewer(currentVersion, info.version)
                    // "The notification should not continuously interrupt the
                    // user after selecting Remind Me Later" — a silent/
                    // automatic check honors an active snooze for this exact
                    // version; a critical update is never snoozed (spec: "do
                    // not provide the Remind Me Later option for mandatory
                    // updates" — nothing to honor if it was never offered),
                    // and an explicit manual check (silent = false) always
                    // shows regardless, since the user asked directly.
                    val snoozed = silent && !info.isCritical && reminderStore.isSnoozed(info.version)
                    _state.value = when {
                        isNewer && !snoozed -> UpdateCheckState.Available(info, currentVersion)
                        isNewer -> UpdateCheckState.Idle
                        else -> UpdateCheckState.UpToDate(currentVersion)
                    }
                }
                .onFailure { e ->
                    Log.w(TAG, "Update check failed: ${e.message}")
                    // A *silent* (automatic) check failing — e.g. offline, or a
                    // transient network blip — isn't the "Update Failed" flow;
                    // that's reserved for a failed download/install, so it drops
                    // back to Idle with no UI. But an explicit "Check for
                    // Updates" tap (silent = false) is the user directly asking
                    // for a result — leaving it at Idle silently swallowed the
                    // failure with the button visibly doing nothing (bug: a
                    // rate-limited or offline manual check showed no feedback
                    // at all).
                    _state.value = if (silent) {
                        UpdateCheckState.Idle
                    } else {
                        UpdateCheckState.Failed(null, e.message ?: "Couldn't check for updates. Check your connection and try again.")
                    }
                }
            if (silent && _state.value is UpdateCheckState.UpToDate) {
                _state.value = UpdateCheckState.Idle
            }
        }
    }

    /** [REMIND ME LATER] — never offered for a critical update (see
     * [UpdateInfo.isCritical]'s doc comment); the screen itself never shows
     * the button in that case, but this is the actual enforcement so a
     * theoretical stray call can't snooze a mandatory update either. */
    fun remindLater() {
        val info = (_state.value as? UpdateCheckState.Available)?.info ?: return
        if (info.isCritical) return
        reminderStore.snooze(info.version)
        _state.value = UpdateCheckState.Idle
    }

    /** A critical update's dialog has no close button/back-press dismissal
     * on the UI side (see [UpdateDialog]'s `onDismissRequest = {}` for that
     * state) — this is the matching backstop so a stray call here can't
     * dismiss one either. Every other state (UpToDate/Failed/a *non*-
     * critical Available closed some other way than Remind Me Later, if it
     * ever is) dismisses normally. */
    fun dismiss() {
        val info = (_state.value as? UpdateCheckState.Available)?.info
        if (info?.isCritical == true) return
        _state.value = UpdateCheckState.Idle
    }

    /** [UPDATE NOW] — download, then request installation immediately.
     * Per explicit request, GoPreach's own post-download checksum check
     * (the "Verifying Update..." step) is skipped — the APK goes straight
     * from download to the install handoff. Android's own Package Installer
     * still enforces its own signature check on the way in (an update
     * signed with a different key than the installed app is refused
     * regardless of anything here) — that's the OS-level guarantee this app
     * was never trying to duplicate; only GoPreach's *own additional*
     * integrity check is what's being skipped now. */
    fun updateNow() {
        val info = (_state.value as? UpdateCheckState.Available)?.info
            ?: (_state.value as? UpdateCheckState.Failed)?.info
            ?: return
        viewModelScope.launch {
            try {
                downloader.clearDownloads()
                val fileName = "GoPreach-${info.version}.apk"
                var lastFile: File? = null
                downloader.download(info.apkUrl, fileName).collect { event ->
                    when (event) {
                        is DownloadEvent.Progress -> _state.value = UpdateCheckState.Downloading(info, event.percent)
                        is DownloadEvent.Done -> lastFile = event.file
                    }
                }
                val apkFile = lastFile ?: throw Exception("Download did not complete")

                // Recorded *before* handing off to the Package Installer —
                // this is the last point the app is definitely still
                // running to write it; the process may well be replaced
                // once installation actually happens.
                installedUpdateInfoStore.save(
                    InstalledUpdateInfo(
                        version = info.version,
                        releaseNotes = info.releaseNotes,
                        apkUrl = info.apkUrl,
                        installedAt = System.currentTimeMillis(),
                    ),
                )
                // No snooze should ever outlive the update it was for.
                reminderStore.clear()
                // Bug fix: an update install replaces the APK but never
                // touches app data — Firebase Auth's persisted session (see
                // UserSession's doc comment: "survives process death, no
                // separate remember-me needed") survived right through it,
                // so relaunching after the install silently landed back on
                // whoever's dashboard was open before, with no login prompt
                // at all ("the app logs in automatically even without user
                // intervention after the update"). Signing out here, right
                // before handing off to the Package Installer, means the
                // next launch — whether the OS auto-reopens the app or the
                // user taps Open — lands on the Login screen instead. The
                // biometric "remember me" shortcut (CredentialStore) is
                // untouched, so it's still one fingerprint tap away, not a
                // full re-type of the password.
                authRepository.signOut()
                _state.value = UpdateCheckState.ReadyToInstall(info, apkFile)
            } catch (e: Exception) {
                Log.w(TAG, "Update failed: ${e.message}", e)
                _state.value = UpdateCheckState.Failed(info, "The new version could not be downloaded. Your current version is still available.")
            }
        }
    }

    /** Called by the UI right before launching the install Intent — Android's
     * own Package Installer takes over from here (progress, signature check,
     * confirmation), which is why there's no "Installing…" state machine step
     * beyond this: this app hands off and gets out of the way, per spec §3. */
    fun installIntentFor(apkFile: File): Intent = installer.installIntent(apkFile)

    fun canInstall(): Boolean = installer.canInstall()
    fun requestInstallPermissionIntent(): Intent = installer.requestInstallPermissionIntent()

    fun retry() {
        val info = (_state.value as? UpdateCheckState.Failed)?.info
        if (info != null) {
            // A failed download/install already knows which release it was
            // trying to install — re-offer that one instead of a fresh check.
            _state.value = UpdateCheckState.Available(info, currentVersion)
        } else {
            // A failed *check* (see [check]'s `silent = false` branch) has no
            // release to re-offer yet — re-run the check itself instead of
            // just going back to Idle, which used to make "TRY AGAIN" a no-op.
            check(silent = false)
        }
    }

    /** The text handed to Android's native share sheet — always the *current*
     * latest APK URL fetched moments ago, never a version baked into the app. */
    fun shareText(info: UpdateInfo): String =
        "GoPreach ${info.version} is now available!\nDownload the latest version here:\n${info.apkUrl}"

    /** "Add a link for the updated apk file after installation so that the
     * user can share the app to others" — Settings' "Share App" button.
     * Always fetches fresh from GitHub Releases rather than reusing
     * [installedUpdateInfo], so it keeps working (and points at the right
     * link) for a device with no update history yet, and never shares a
     * stale link if a newer release has shipped since this device last
     * updated. */
    suspend fun fetchLatestForShare(): Result<UpdateInfo> = manifestRepository.fetchLatest()
}

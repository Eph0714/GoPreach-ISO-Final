package com.emfitsolutions.gopreach

import org.koin.android.ext.android.inject
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.export.IncomingBibleTextImportHolder
import com.emfitsolutions.gopreach.data.repository.ThemePreference
import com.emfitsolutions.gopreach.data.repository.ThemePreferenceRepository
import com.emfitsolutions.gopreach.data.sync.RemoteSyncCoordinator
import com.emfitsolutions.gopreach.data.sync.SyncScheduler
import com.emfitsolutions.gopreach.ui.components.SyncMessageHost
import com.emfitsolutions.gopreach.ui.components.update.UpdateHost
import com.emfitsolutions.gopreach.ui.components.update.UpdateViewModel
import com.emfitsolutions.gopreach.ui.navigation.GoPreachNavGraph
import com.emfitsolutions.gopreach.ui.screens.settings.InactivityTracker
import com.emfitsolutions.gopreach.ui.screens.settings.SessionTimeoutHost
import com.emfitsolutions.gopreach.ui.theme.GoPreachTheme

class MainActivity : AppCompatActivity() {
    // AppCompatActivity, not a bare ComponentActivity/FragmentActivity: androidx
    // .biometric.BiometricPrompt (used by the login screen's fingerprint/face
    // sign-in) needs a FragmentManager to host its internal dialog fragment —
    // AppCompatActivity is itself a FragmentActivity, so that's covered. (The
    // per-app language feature this class used to also justify itself by has
    // since been removed — GoPreach is English-only now — but this is still
    // needed for biometric login.) AppCompatActivity is itself a
    // ComponentActivity, so setContent{}/enableEdgeToEdge() below are unaffected.

    val themePreferenceRepository: ThemePreferenceRepository by inject()

    val nameOrderPreference: com.emfitsolutions.gopreach.data.repository.NameOrderPreference by inject()

    val syncScheduler: SyncScheduler by inject()

    val remoteSyncCoordinator: RemoteSyncCoordinator by inject()

    val inactivityTracker: InactivityTracker by inject()

    /** Any touch/key/trackball event counts as "using the app" for Session Timeout. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        inactivityTracker.touch()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)
        setContent {
            val preference by themePreferenceRepository.preference.collectAsStateWithLifecycle()
            val colorOption by themePreferenceRepository.colorOption.collectAsStateWithLifecycle()
            val customColor by themePreferenceRepository.customColor.collectAsStateWithLifecycle()
            val darkTheme = when (preference) {
                ThemePreference.SYSTEM -> isSystemInDarkTheme()
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
            }
            // Dynamic (wallpaper-derived) color is deliberately off — spec §1 asks for
            // a clean, consistent look, which a fixed brand palette delivers more
            // reliably than colors that shift with the user's wallpaper. colorOption
            // is the user's own per-device accent color pick (Settings screen) instead;
            // customColor only matters when that pick is ThemeColorOption.CUSTOM (a
            // color wheel/eyedropper choice).
            val nameOrder by nameOrderPreference.order.collectAsStateWithLifecycle()
            androidx.compose.runtime.CompositionLocalProvider(
                com.emfitsolutions.gopreach.ui.components.LocalNameOrder provides nameOrder,
                com.emfitsolutions.gopreach.platform.LocalPlatformActions provides com.emfitsolutions.gopreach.data.print.rememberAndroidPlatformActions(),
            ) {
            GoPreachTheme(darkTheme = darkTheme, dynamicColor = false, colorOption = colorOption, customColor = customColor) {
                // enableEdgeToEdge() opts this app out of the system's automatic
                // windowSoftInputMode="adjustResize" handling — Compose has to react to
                // the IME inset itself, or the keyboard simply draws on top of whatever
                // field/button was underneath it instead of the content shrinking to
                // make room. imePadding() here, once, is what actually gets every form
                // and dialog in the app to scroll its focused field (and its Save
                // button) up above the keyboard instead of behind it.
                Surface(modifier = Modifier.fillMaxSize().imePadding()) {
                    GoPreachNavGraph()

                    // Checked once per app process, regardless of whether the user is
                    // signed in yet — "Application Starts -> Check Update Server" per spec.
                    val updateViewModel: UpdateViewModel = koinViewModel(viewModelStoreOwner = this@MainActivity)
                    LaunchedEffect(Unit) { updateViewModel.checkOnAppStart() }

                    // "Automatically start synchronization when the application
                    // opens and a reliable Internet connection is available" —
                    // SyncScheduler's own automatic triggers (an offline→online
                    // *transition*, or a write happening while already online)
                    // don't cover a cold start that's already online with
                    // pending items left over from a previous session (nothing
                    // transitions, and nothing new is being written) — this is
                    // the same gap [checkOnAppStart]/[checkOnAppForeground]
                    // above already exist to close for update checks.
                    // triggerSyncIfOnline() is a cheap, idempotent no-op when
                    // there's nothing pending or the device is offline, so
                    // calling it unconditionally here (and again on every
                    // foreground below) is safe.
                    LaunchedEffect(Unit) {
                        remoteSyncCoordinator.retryIfNeeded()
                        syncScheduler.triggerSyncIfOnline()
                    }

                    // Bug fix ("auto update available is not working"): a
                    // process staying alive across many background/
                    // foreground cycles (by far the most common way this app
                    // is actually used) never re-ran checkOnAppStart above
                    // (once per process) and could easily go a long time
                    // without an actual connectivity transition either — so
                    // it could sit on an old version for hours/days with no
                    // automatic re-check at all. This is a single-Activity
                    // app, so the Activity's own lifecycle IS the app's
                    // foreground/background lifecycle — ON_START here means
                    // "the app just came to the foreground," including every
                    // time after the very first launch.
                    val lifecycleOwner = LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_START) {
                                updateViewModel.checkOnAppForeground()
                                // Same "app returns from background to
                                // foreground" trigger, for pending sync
                                // instead of app updates — see this file's
                                // own comment on the LaunchedEffect above.
                                remoteSyncCoordinator.retryIfNeeded()
                                syncScheduler.triggerSyncIfOnline()
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }

                    UpdateHost(updateViewModel)

                    // App-wide "Changes saved locally...", "Synchronization
                    // completed successfully.", etc. system messages — one
                    // instance for the whole process, same reasoning as
                    // UpdateHost above.
                    SyncMessageHost()

                    // "Session Timeout Setting" — logs the user out after the
                    // configured period of inactivity.
                    SessionTimeoutHost()
                }
            }
            }
        }
    }

    // singleTask (see AndroidManifest.xml) — an already-running instance is
    // handed a new "tap the shared file to import it" intent here instead of
    // a fresh Activity being created for it.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /** "If the receiving Publisher downloads and taps the exported file, it
     * must import automatically" — stashes the incoming file's Uri where
     * [com.emfitsolutions.gopreach.ui.screens.bibletext.BibleTextRecordScreen]
     * (the only place with both a signed-in Publisher id and their existing
     * Events) picks it up and runs the exact same import path as its own
     * manual "Import" menu item. */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { IncomingBibleTextImportHolder.set(it) }
        }
    }
}

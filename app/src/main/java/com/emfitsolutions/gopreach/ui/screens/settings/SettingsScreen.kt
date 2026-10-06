package com.emfitsolutions.gopreach.ui.screens.settings

import android.app.Activity
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.BuildConfig
import com.emfitsolutions.gopreach.R
import com.emfitsolutions.gopreach.data.repository.ThemePreference
import com.emfitsolutions.gopreach.notifications.AlarmScheduler
import kotlinx.coroutines.launch
import com.emfitsolutions.gopreach.ui.components.ThemeOptionRow
import com.emfitsolutions.gopreach.ui.components.update.UpdateViewModel
import com.emfitsolutions.gopreach.ui.theme.ThemeColorOption
import androidx.compose.ui.window.DialogProperties

/** Display preference — per-device, not tied to any account (spec §1: "modern
 * Android UI"). Available to every signed-in role. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    // "Theme Color Settings — Simplified User Experience" (spec §16) — the
    // wheel/eyedropper/preset picker itself now lives on its own screen
    // (see ThemeColorSettingsScreen), reachable from here (same reach this
    // screen always had) and from the Control Panel drawer section; this
    // screen keeps only Appearance (light/dark)/Notifications/App Version.
    onNavigateToThemeColorSettings: () -> Unit,
    currentPersonId: String = "",
    showDeletedRecordsSettings: Boolean = false,
    onOpenDeletedRecords: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val colorOption by viewModel.colorOption.collectAsStateWithLifecycle()
    val customColor by viewModel.customColor.collectAsStateWithLifecycle()
    // Explicitly Activity-scoped (not the default nav-entry scope) so this is
    // the *same* instance MainActivity's UpdateHost renders the result of —
    // otherwise tapping "Check for Updates" here would update a ViewModel
    // nothing on screen is actually observing.
    val updateViewModel: UpdateViewModel = hiltViewModel(LocalContext.current as ComponentActivity)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.dashboard_back_cd))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.settings_appearance_title), style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    ThemeOptionRow(stringResource(R.string.settings_theme_system), ThemePreference.SYSTEM, theme, viewModel::setTheme)
                    ThemeOptionRow(stringResource(R.string.settings_theme_light), ThemePreference.LIGHT, theme, viewModel::setTheme)
                    ThemeOptionRow(stringResource(R.string.settings_theme_dark), ThemePreference.DARK, theme, viewModel::setTheme)
                }
            }

            Text(stringResource(R.string.settings_theme_color_title), style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onNavigateToThemeColorSettings)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.theme_color_settings_title)) },
                    supportingContent = { Text(stringResource(R.string.settings_theme_color_subtitle)) },
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    if (colorOption == ThemeColorOption.CUSTOM) customColor else (colorOption.swatch?.light ?: customColor),
                                    CircleShape,
                                )
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        )
                    },
                )
            }

            com.emfitsolutions.gopreach.ui.components.NameOrderSettingsSection()

            PrintSettingsSection()

            NotificationSoundSection(viewModel = viewModel)

            // Everyone can open their own deleted records; only the roles that manage them see the retention setting.
            if (currentPersonId.isNotBlank()) {
                DataManagementSection(
                    currentPersonId = currentPersonId,
                    canChangeRetention = showDeletedRecordsSettings,
                    onOpenDeletedRecords = onOpenDeletedRecords,
                )
            }

            AppVersionSection(updateViewModel = updateViewModel)
        }
    }
}

/**
 * "Allow all the users to manage notification sound... browse to mobile
 * notification sounds" — one setting shared by every incoming notification
 * this app posts (Transfer Request, Announcement, Calendar Alarm alike; see
 * [com.emfitsolutions.gopreach.notifications.NotificationHelper]'s doc
 * comment). Uses the system's own ringtone picker
 * ([RingtoneManager.ACTION_RINGTONE_PICKER]) rather than a custom list, so it
 * shows exactly the same notification sounds the user's phone already offers
 * everywhere else. Also surfaces the "Alarms & reminders" system permission
 * when it's missing (API 31+) — without it, Calendar Alarms still ring, just
 * not necessarily at the exact minute.
 */
@Composable
private fun NotificationSoundSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val soundUri by viewModel.notificationSoundUri.collectAsStateWithLifecycle()
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsStateWithLifecycle()
    val soundsEnabled by viewModel.notificationSoundsEnabled.collectAsStateWithLifecycle()
    val popupsEnabled by viewModel.popupNotificationsEnabled.collectAsStateWithLifecycle()
    val transferRequestsEnabled by viewModel.transferRequestNotificationsEnabled.collectAsStateWithLifecycle()
    val announcementsEnabled by viewModel.announcementNotificationsEnabled.collectAsStateWithLifecycle()
    val messagesEnabled by viewModel.messageNotificationsEnabled.collectAsStateWithLifecycle()
    val importantEnabled by viewModel.importantNotificationsEnabled.collectAsStateWithLifecycle()
    var exactAlarmsAllowed by remember { mutableStateOf(AlarmScheduler.canScheduleExactAlarms(context)) }
    var diagnosticsResult by remember { mutableStateOf<List<com.emfitsolutions.gopreach.notifications.NotificationDiagnostic>?>(null) }

    val pickRingtone = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val picked: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            viewModel.setNotificationSound(picked)
        }
    }

    Text(stringResource(R.string.settings_notifications_title), style = MaterialTheme.typography.titleMedium)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_notifications_title)) },
                supportingContent = { Text(stringResource(R.string.settings_notifications_subtitle)) },
                leadingContent = { Icon(Icons.Rounded.Notifications, contentDescription = null) },
                trailingContent = {
                    Switch(checked = notificationsEnabled, onCheckedChange = viewModel::setNotificationsEnabled)
                },
            )
            // "NOTIFICATION SETTINGS: Enable Notification Sounds / Transfer
            // Request Notifications / Announcement Notifications / Show
            // Popup Notifications" — four finer-grained toggles layered on
            // top of the master switch above; each dims and disables while
            // the master switch is off, same "there's nothing to configure
            // if it's all off anyway" convention "Notification Sound" below
            // already used before this section existed.
            androidx.compose.material3.HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_sounds_title)) },
                supportingContent = { Text(stringResource(R.string.settings_sounds_subtitle)) },
                leadingContent = { Icon(Icons.Rounded.MusicNote, contentDescription = null) },
                modifier = Modifier.alpha(if (notificationsEnabled) 1f else 0.5f),
                trailingContent = {
                    Switch(checked = soundsEnabled, onCheckedChange = viewModel::setNotificationSoundsEnabled, enabled = notificationsEnabled)
                },
            )
            androidx.compose.material3.HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_popups_title)) },
                supportingContent = { Text(stringResource(R.string.settings_popups_subtitle)) },
                leadingContent = { Icon(Icons.Rounded.Notifications, contentDescription = null) },
                modifier = Modifier.alpha(if (notificationsEnabled) 1f else 0.5f),
                trailingContent = {
                    Switch(checked = popupsEnabled, onCheckedChange = viewModel::setPopupNotificationsEnabled, enabled = notificationsEnabled)
                },
            )
            androidx.compose.material3.HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_transfer_requests_title)) },
                supportingContent = { Text(stringResource(R.string.settings_transfer_requests_subtitle)) },
                leadingContent = { Icon(Icons.Rounded.SwapHoriz, contentDescription = null) },
                modifier = Modifier.alpha(if (notificationsEnabled) 1f else 0.5f),
                trailingContent = {
                    Switch(checked = transferRequestsEnabled, onCheckedChange = viewModel::setTransferRequestNotificationsEnabled, enabled = notificationsEnabled)
                },
            )
            androidx.compose.material3.HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_announcements_title)) },
                supportingContent = { Text(stringResource(R.string.settings_announcements_subtitle)) },
                leadingContent = { Icon(Icons.Rounded.Campaign, contentDescription = null) },
                modifier = Modifier.alpha(if (notificationsEnabled) 1f else 0.5f),
                trailingContent = {
                    Switch(checked = announcementsEnabled, onCheckedChange = viewModel::setAnnouncementNotificationsEnabled, enabled = notificationsEnabled)
                },
            )
            androidx.compose.material3.HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_group_chat_messages_title)) },
                supportingContent = { Text(stringResource(R.string.settings_group_chat_messages_subtitle)) },
                leadingContent = { Icon(Icons.Rounded.ChatBubble, contentDescription = null) },
                modifier = Modifier.alpha(if (notificationsEnabled) 1f else 0.5f),
                trailingContent = {
                    Switch(checked = messagesEnabled, onCheckedChange = viewModel::setMessageNotificationsEnabled, enabled = notificationsEnabled)
                },
            )
            androidx.compose.material3.HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_system_notifications_title)) },
                supportingContent = { Text(stringResource(R.string.settings_system_notifications_subtitle)) },
                leadingContent = { Icon(Icons.Rounded.PriorityHigh, contentDescription = null) },
                modifier = Modifier.alpha(if (notificationsEnabled) 1f else 0.5f),
                trailingContent = {
                    Switch(checked = importantEnabled, onCheckedChange = viewModel::setImportantNotificationsEnabled, enabled = notificationsEnabled)
                },
            )
            androidx.compose.material3.HorizontalDivider()
            val notificationSoundTitle = stringResource(R.string.settings_notification_sound_title)
            ListItem(
                headlineContent = { Text(notificationSoundTitle) },
                supportingContent = { Text(ringtoneTitle(context, soundUri, stringResource(R.string.settings_notification_sound_default), stringResource(R.string.settings_notification_sound_custom))) },
                leadingContent = { Icon(Icons.Rounded.MusicNote, contentDescription = null) },
                modifier = Modifier
                    .alpha(if (notificationsEnabled) 1f else 0.5f)
                    .clickable(enabled = notificationsEnabled) {
                        val intent = android.content.Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            putExtra(
                                RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                            )
                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, soundUri)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, notificationSoundTitle)
                        }
                        pickRingtone.launch(intent)
                    },
            )
            Text(
                stringResource(R.string.settings_notification_sound_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !exactAlarmsAllowed) {
                androidx.compose.material3.HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_exact_alarms_title)) },
                    supportingContent = { Text(stringResource(R.string.settings_exact_alarms_subtitle)) },
                    leadingContent = { Icon(Icons.Rounded.Alarm, contentDescription = null) },
                    modifier = Modifier.clickable {
                        context.startActivity(
                            android.content.Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:${context.packageName}")
                            },
                        )
                        exactAlarmsAllowed = AlarmScheduler.canScheduleExactAlarms(context)
                    },
                )
            }
            androidx.compose.material3.HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_test_notification_title)) },
                supportingContent = { Text(stringResource(R.string.settings_test_notification_subtitle)) },
                leadingContent = { Icon(Icons.Rounded.NotificationsActive, contentDescription = null) },
                modifier = Modifier.clickable {
                    diagnosticsResult = viewModel.runNotificationDiagnostics()
                    if (diagnosticsResult?.all { it.passed } == true) {
                        viewModel.sendTestNotification()
                    }
                },
            )
        }
    }

    diagnosticsResult?.let { results ->
        val allPassed = results.all { it.passed }
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { diagnosticsResult = null },
            title = { Text(stringResource(if (allPassed) R.string.settings_test_notification_sent_title else R.string.settings_test_notification_failed_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    results.forEach { diagnostic ->
                        Text(
                            if (diagnostic.passed) "✓ ${diagnostic.label}" else "✗ ${diagnostic.label}",
                            color = if (diagnostic.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!diagnostic.passed) {
                            Text(
                                diagnostic.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp, top = 2.dp),
                            )
                        }
                    }
                    if (allPassed) {
                        Text(
                            stringResource(R.string.settings_silent_mode_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { diagnosticsResult = null }) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }
}

/** Best-effort human-readable name for [uri] via [RingtoneManager] — falls
 * back to a generic label rather than crashing if the picked sound was later
 * uninstalled/removed (e.g. a ringtone from an app the user removed).
 * [defaultLabel]/[customLabel] are resolved by the caller ([stringResource]
 * only works inside a @Composable, and this plain function isn't one). */
private fun ringtoneTitle(context: android.content.Context, uri: Uri?, defaultLabel: String, customLabel: String): String {
    if (uri == null) return defaultLabel
    return runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }
        .getOrNull() ?: customLabel
}

/**
 * "Add a details of the newly installed update. Add a link for the updated
 * apk file after installation so that the user can share the app to
 * others. Put it inside the Settings / 'App Version' Folder." — replaces
 * the old plain "About" section. [UpdateViewModel.installedUpdateInfo] is
 * this device's own update history (see that store's doc comment for why
 * it can be empty); "Share App" always fetches the current latest release
 * fresh rather than relying on that history, so it works from a first
 * install too.
 */
@Composable
private fun AppVersionSection(updateViewModel: UpdateViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isSharing by remember { mutableStateOf(false) }
    var shareError by remember { mutableStateOf<String?>(null) }
    val installedUpdateInfo = remember { updateViewModel.installedUpdateInfo }

    Text(stringResource(R.string.settings_app_version_title), style = MaterialTheme.typography.titleMedium)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_version_label, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)

            if (installedUpdateInfo != null && installedUpdateInfo.releaseNotes.isNotBlank()) {
                Text(
                    stringResource(R.string.settings_whats_new_title),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(installedUpdateInfo.releaseNotes, style = MaterialTheme.typography.bodySmall)
            }

            if (shareError != null) {
                Text(shareError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = updateViewModel::checkManually,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) { Text(stringResource(R.string.settings_check_updates)) }

            val shareFetchError = stringResource(R.string.settings_share_fetch_error)
            val shareChooserTitle = stringResource(R.string.settings_share_chooser_title)
            OutlinedButton(
                onClick = {
                    isSharing = true
                    shareError = null
                    coroutineScope.launch {
                        updateViewModel.fetchLatestForShare()
                            .onSuccess { info -> launchAppShare(context, updateViewModel.shareText(info), shareChooserTitle) }
                            // The underlying failure (e.g. "GitHub download
                            // limit reached for this network") is far more
                            // actionable than a generic connectivity message
                            // when it's actually available — that message
                            // used to always say "check your connection"
                            // even when the real cause was a shared-network
                            // rate limit that had nothing to do with this
                            // device's own connectivity.
                            .onFailure { e -> shareError = e.message ?: shareFetchError }
                        isSharing = false
                    }
                },
                enabled = !isSharing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isSharing) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                } else {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                }
                Text(stringResource(R.string.settings_share_app))
            }
        }
    }
}

/** [chooserTitle] is resolved by the caller ([stringResource] only works
 * inside a @Composable). "GoPreach" itself (the share subject) is the app's
 * own name, not translated anywhere else in this app either — see
 * strings.xml's app_name entry. */
private fun launchAppShare(context: android.content.Context, text: String, chooserTitle: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, "GoPreach")
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    context.startActivity(android.content.Intent.createChooser(intent, chooserTitle))
}


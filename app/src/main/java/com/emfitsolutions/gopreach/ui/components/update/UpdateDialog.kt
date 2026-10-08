package com.emfitsolutions.gopreach.ui.components.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import androidx.compose.ui.window.DialogProperties

/**
 * Hosted once, near the app's root (see MainActivity) — reflects whatever
 * [UpdateViewModel.state] currently is. Renders nothing for Idle/Checking, so
 * mounting this doesn't itself show anything; only a real Available/
 * Downloading/Verifying/ReadyToInstall/Failed/UpToDate state does.
 */
@Composable
fun UpdateHost(viewModel: UpdateViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Once the download is verified, hand off to the system Package Installer
    // immediately — there's no separate in-app "Installing…" screen because
    // installation itself is entirely Android's own UI from this point on.
    // The hand-off must never leave the user stuck on "Installing Update...": if the system installer never appears, was cancelled, or the
    // user comes back from the "install unknown apps" setting, the dialog switches to a state with Install Again / Close.
    var attempt by remember { mutableIntStateOf(0) }
    var launched by remember { mutableStateOf(false) }
    var stalled by remember { mutableStateOf(false) }
    LaunchedEffect(state, attempt) {
        val ready = state as? UpdateCheckState.ReadyToInstall
        if (ready == null) { launched = false; stalled = false; return@LaunchedEffect }
        stalled = false
        val ok = runCatching {
            if (!viewModel.canInstall()) context.startActivity(viewModel.requestInstallPermissionIntent())
            else context.startActivity(viewModel.installIntentFor(ready.apkFile))
        }.isSuccess
        launched = ok
        if (!ok) { stalled = true; return@LaunchedEffect }
        // No installer window after a while: let the user retry instead of waiting forever.
        delay(12_000)
        stalled = true
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, state) {
        val observer = LifecycleEventObserver { _, event ->
            // Back in the app while still not installed (the installer was dismissed, or the permission screen was closed).
            if (event == Lifecycle.Event.ON_RESUME && launched && state is UpdateCheckState.ReadyToInstall) {
                stalled = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (val s = state) {
        is UpdateCheckState.Available -> {
            // "Required/Critical Update... the user must update before
            // continuing to use certain parts of the application. Do not
            // provide the Remind Me Later option" — no dismiss request
            // (back press/outside-tap do nothing), no Remind Me Later, no
            // Share APK button either (this dialog isn't going anywhere
            // until Update Now is tapped, so there's nothing to relay to
            // someone else in the meantime).
            AlertDialog(
                properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
                onDismissRequest = if (s.info.isCritical) ({}) else viewModel::dismiss,
                title = { Text(if (s.info.isCritical) "Update Required" else "Update Available") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (s.info.isCritical) {
                                "This update is required and includes an important fix. Please update to continue using GoPreach."
                            } else {
                                "A new version of GoPreach is available."
                            },
                        )
                        Text("Current Version: ${s.currentVersion}")
                        Text("New Version: ${s.info.version}", fontWeight = FontWeight.Bold)
                        if (s.info.releaseNotes.isNotBlank()) {
                            Text(s.info.releaseNotes, style = MaterialTheme.typography.bodySmall)
                        }
                        if (!s.info.isCritical) {
                            val shareText = viewModel.shareText(s.info)
                            TextButton(
                                onClick = { launchShare(context, shareText) },
                                modifier = Modifier.padding(top = 4.dp),
                            ) {
                                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                                Text("SHARE APK")
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = viewModel::updateNow) { Text("UPDATE NOW") }
                },
                dismissButton = {
                    // "Remind Me Later" — optional updates only (see above).
                    if (!s.info.isCritical) {
                        OutlinedButton(onClick = viewModel::remindLater) { Text("REMIND ME LATER") }
                    }
                },
            )
        }

        is UpdateCheckState.Downloading -> {
            AlertDialog(
                properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
                onDismissRequest = {},
                title = { Text("Updating GoPreach...") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Downloading version ${s.info.version}")
                        LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("${s.percent}%")
                    }
                },
                confirmButton = {},
            )
        }

        is UpdateCheckState.ReadyToInstall -> {
            AlertDialog(
                properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
                onDismissRequest = viewModel::dismiss,
                title = { Text(if (stalled) "Update Not Installed Yet" else "Installing Update...") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!stalled) {
                            CircularProgressIndicator()
                            Text("Handing off to Android's installer for version ${s.info.version}.")
                        } else {
                            Text(
                                "Android's installer did not finish installing version ${s.info.version}. If it asked for permission to install apps from GoPreach, allow it " +
                                    "and tap Install Again. Your current version still works.",
                            )
                        }
                    }
                },
                confirmButton = {
                    if (stalled) Button(onClick = { attempt++ }) { Text("INSTALL AGAIN") }
                },
                dismissButton = {
                    if (!s.info.isCritical) TextButton(onClick = viewModel::dismiss) { Text(if (stalled) "CLOSE" else "CANCEL") }
                },
            )
        }

        is UpdateCheckState.Failed -> {
            AlertDialog(
                properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
                onDismissRequest = viewModel::dismiss,
                title = { Text("Update Failed") },
                text = {
                    val mandatory = s.info?.isCritical == true
                    Text(
                        if (mandatory) "${s.message}\n\nThis update is required to continue using GoPreach."
                        else "${s.message}\n\nYour current version is still available.",
                    )
                },
                confirmButton = {
                    Button(onClick = viewModel::retry) { Text("TRY AGAIN") }
                },
                dismissButton = {
                    if (s.info?.isCritical != true) {
                        TextButton(onClick = viewModel::dismiss) { Text("Close") }
                    }
                },
            )
        }

        is UpdateCheckState.UpToDate -> {
            AlertDialog(
                properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
                onDismissRequest = viewModel::dismiss,
                title = { Text("GoPreach is up to date.") },
                text = { Text("Version ${s.version}") },
                confirmButton = {
                    TextButton(onClick = viewModel::dismiss) { Text("OK") }
                },
            )
        }

        UpdateCheckState.Idle, UpdateCheckState.Checking -> Unit
    }
}

private fun launchShare(context: android.content.Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "GoPreach update")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share GoPreach update"))
}

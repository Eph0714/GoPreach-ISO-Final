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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
fun UpdateHost(viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Once the download is verified, hand off to the system Package Installer
    // immediately — there's no separate in-app "Installing…" screen because
    // installation itself is entirely Android's own UI from this point on.
    LaunchedEffect(state) {
        val ready = state as? UpdateCheckState.ReadyToInstall ?: return@LaunchedEffect
        if (!viewModel.canInstall()) {
            context.startActivity(viewModel.requestInstallPermissionIntent())
            return@LaunchedEffect
        }
        context.startActivity(viewModel.installIntentFor(ready.apkFile))
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
                onDismissRequest = {},
                title = { Text("Installing Update...") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator()
                        Text("Handing off to Android's installer for version ${s.info.version}.")
                    }
                },
                confirmButton = {},
            )
        }

        is UpdateCheckState.Failed -> {
            AlertDialog(
                properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
                onDismissRequest = viewModel::dismiss,
                title = { Text("Update Failed") },
                text = { Text("${s.message}\n\nYour current version is still available.") },
                confirmButton = {
                    Button(onClick = viewModel::retry) { Text("TRY AGAIN") }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismiss) { Text("Close") }
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

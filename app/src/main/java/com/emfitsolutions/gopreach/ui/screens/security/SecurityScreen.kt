package com.emfitsolutions.gopreach.ui.screens.security

import com.emfitsolutions.gopreach.ui.components.ActionKind
import com.emfitsolutions.gopreach.ui.components.ActionButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import com.emfitsolutions.gopreach.data.repository.SecurityStatus
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.repository.DeviceGroup
import com.emfitsolutions.gopreach.data.repository.DeviceSession
import com.emfitsolutions.gopreach.data.repository.groupDevices
import com.emfitsolutions.gopreach.data.repository.HistoryFilter
import com.emfitsolutions.gopreach.data.repository.SecurityEvent
import com.emfitsolutions.gopreach.ui.components.AlertDialog
import org.koin.compose.viewmodel.koinViewModel

internal fun whenText(ms: Long): String =
    if (ms <= 0L) "-" else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(ms))

private fun typeWord(t: String) = when (t) { "phone" -> "Phone"; "tablet" -> "Tablet"; "web" -> "Web browser"; "desktop" -> "Computer"; else -> "Device" }
private fun methodWord(m: String?) = when (m) {
    "password" -> "Password"; "password+totp" -> "Password and app code"; "password+recovery-code" -> "Password and recovery code"; "password+email-code" -> "Password and e-mail code"
    "trusted-device" -> "Trusted device"; "passkey" -> "Passkey"; else -> m ?: "-"
}
private fun statusWord(s: String) = when (s) { "active" -> "Active"; "expired" -> "Expired"; "revoked" -> "Logged out"; else -> s }
private fun revokedWord(by: String?) = when {
    by == null -> ""; by.startsWith("admin:") -> " - by an administrator"; by == "system" -> " - after a password change"; else -> " - by you"
}

/**
 * A dialog that confirms a security change with the current password (and, where the change is to two-factor itself, a current code). [message] says what is about to
 * happen; nothing is sent until the person confirms.
 */
@Composable
fun SecurityConfirmDialog(
    title: String, message: String, confirmLabel: String, busy: Boolean, error: String?, askCode: Boolean = false, destructive: Boolean = false,
    onConfirm: (password: String, code: String) -> Unit, onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                PasswordField(value = password, onValueChange = { password = it }, label = "Current password", enabled = !busy)
                if (askCode) OutlinedTextField(
                    value = code, onValueChange = { code = it.take(14) }, singleLine = true, label = { Text("Authenticator code or recovery code") }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && password.isNotBlank() && (!askCode || code.isNotBlank()), onClick = { onConfirm(password, code) }) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { ActionButton(ActionKind.Cancel, onClick = onDismiss, label = "Cancel", enabled = !busy) },
    )
}

private sealed interface Ask {
    data object Setup : Ask
    data object Disable : Ask
    data object Regenerate : Ask
    data object RecoveryEmail : Ask
    data object TrustThis : Ask
    data class DeviceApproval(val enable: Boolean) : Ask
    data object LogOutOthers : Ask
    data object LogOutAll : Ask
    data class LogOut(val device: DeviceSession) : Ask
    data class Remove(val group: DeviceGroup) : Ask
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(onBack: () -> Unit, viewModel: SecurityViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var ask by remember { mutableStateOf<Ask?>(null) }
    var renaming by remember { mutableStateOf<DeviceGroup?>(null) }
    var emailToSet by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val status = state.status

    // A confirmation closes by itself once the server answered.
    LaunchedEffectOnResult(state.busy, state.error) { done -> if (done) ask = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security & Device Management") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.loading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            state.message?.let { m -> item { Banner(m, error = false, onDismiss = viewModel::clearMessage) } }
            if (ask == null) state.error?.let { m -> item { Banner(m, error = true, onDismiss = viewModel::clearMessage) } }
            if (state.notices.isNotEmpty()) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Security notice", fontWeight = FontWeight.Bold)
                        state.notices.forEach { n -> Text("${whenText(n.at)} - ${SecurityEventNames[n.type] ?: n.type}" + (n.device?.let { ": $it" } ?: ""), style = MaterialTheme.typography.bodySmall) }
                        Text("If you do not recognise a device below, log it out and change your password.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ---- summary: how well the account is protected, at a glance ----
            if (status != null) item { ProtectionSummary(status, state.devices.count { it.status == "active" }) }
            item { SectionHeader("Sign-in protection", "Two layers that keep a stranger out even if they learn your password.") }

            // ---- two-factor ----
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Two-factor authentication", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            AssistChip(onClick = {}, label = { Text(if (status?.enabled == true) "ON" else "OFF") })
                        }
                        Text("After your password, GoPreach asks for a code from an authenticator app (or a recovery code) before a new device gets in.", style = MaterialTheme.typography.bodySmall)
                        if (status != null && !status.enabled) {
                            AuthenticatorAppHelp(onOpen = { runCatching { uriHandler.openUri(it) } })
                            Button(onClick = { ask = Ask.Setup }, modifier = Modifier.fillMaxWidth()) { Text("Step 2: get my setup key") }
                        }
                        if (status != null && status.enabled) {
                            Fact("Recovery codes left", status.recoveryCodesLeft.toString())
                            Fact("Recovery e-mail", status.recoveryEmailMasked?.let { it + if (status.recoveryEmailVerified) "" else " (not confirmed)" } ?: "None")
                            Fact("Trusted devices", status.trustedDevices.toString())
                            if (status.recoveryCodesLeft <= 2) Text("You are running low on recovery codes. Make new ones.", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { ask = Ask.Regenerate }, modifier = Modifier.fillMaxWidth()) { Text("Make new recovery codes") }
                            OutlinedButton(onClick = { ask = Ask.RecoveryEmail }, enabled = status.emailAvailable, modifier = Modifier.fillMaxWidth()) {
                                Text(if (status.emailAvailable) "Recovery e-mail" else "Recovery e-mail (server cannot send e-mail yet)")
                            }
                            if (status.recoveryEmailMasked != null && !status.recoveryEmailVerified) {
                                var code by remember { mutableStateOf("") }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    OutlinedTextField(value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) }, label = { Text("Code from the e-mail") }, singleLine = true, modifier = Modifier.weight(1f))
                                    ActionButton(ActionKind.Save, onClick = { viewModel.verifyRecoveryEmail(code) }, label = "Confirm", enabled = code.length == 6)
                                }
                            }
                            OutlinedButton(onClick = { ask = Ask.Disable }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Turn two-factor off") }
                        }
                    }
                }
            }

            // ---- approve new devices ----
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Approve new devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("When this is on, a device you have not trusted that enters your password only gets a request. Nothing opens until you approve it from a device that is already signed in, or confirm with an e-mail code. Your password alone is never enough.", style = MaterialTheme.typography.bodySmall)
                        Fact("Status", if (status?.deviceApproval == true) "On" else "Off")
                        if (status != null) {
                            if (status.deviceApproval) OutlinedButton(onClick = { ask = Ask.DeviceApproval(false) }, modifier = Modifier.fillMaxWidth()) { Text("Turn off") }
                            else Button(onClick = { ask = Ask.DeviceApproval(true) }, modifier = Modifier.fillMaxWidth()) { Text("Turn on") }
                        }
                    }
                }
            }

            item { SectionHeader("Your devices", "Where you are signed in right now, and the devices that were. Log out anything you do not recognise.") }
            // ---- devices: each device once, with Rename and Remove ----
            val deviceGroups = groupDevices(state.devices, state.trusted)
            items(deviceGroups, key = { it.key }) { g ->
                Card(shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(g.name + if (g.current) "  (This device)" else "", fontWeight = FontWeight.Bold)
                        Text(
                            (if (g.active) "Signed in" + (if (g.sessions > 1) " (${g.sessions} sessions)" else "") else "Not signed in") +
                                (if (g.trusted) "  -  Trusted" + (g.trustedUntil?.let { " until ${whenText(it)}" } ?: "") else ""),
                            color = if (g.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                        )
                        Text(typeWord(g.type) + (listOfNotNull(g.os, g.osVersion).joinToString(" ").takeIf { it.isNotBlank() }?.let { "  -  $it" } ?: "") + "  -  Last active ${whenText(g.lastActiveAt)}", style = MaterialTheme.typography.bodySmall)
                        var more by remember(g.key) { mutableStateOf(false) }
                        if (more) {
                            Fact("Model", g.model ?: "-")
                            Fact("GoPreach version", g.appVersion ?: "-")
                            Fact("First signed in", if (g.loginAt == Long.MAX_VALUE) "-" else whenText(g.loginAt))
                            Fact("Approximate place", g.location ?: g.ip ?: "Not available")
                            Fact("Device ID", g.deviceId ?: "-", mono = true)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            ActionButton(ActionKind.Edit, onClick = { renaming = g }, label = "Rename")
                            if (!g.current) ActionButton(ActionKind.Delete, onClick = { ask = Ask.Remove(g) }, label = "Remove")
                            if (g.current && status?.enabled == true && !g.trusted) OutlinedButton(onClick = { ask = Ask.TrustThis }) { Text("Mark as trusted") }
                            TextButton(onClick = { more = !more }) { Text(if (more) "Less" else "Details") }
                        }
                    }
                }
            }
            if (!state.loading && deviceGroups.isEmpty()) item { Text("No devices yet.", style = MaterialTheme.typography.bodySmall) }
            // ---- log out devices ----
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Log out devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Logging a device out ends its session on the server at once: it can no longer open GoPreach and must sign in again (with a code, if two-factor is on).", style = MaterialTheme.typography.bodySmall)
                        val others = state.devices.count { !it.current && it.status == "active" }
                        OutlinedButton(onClick = { ask = Ask.LogOutOthers }, enabled = others > 0, modifier = Modifier.fillMaxWidth()) { Text("Log out other devices ($others)") }
                        Button(onClick = { ask = Ask.LogOutAll }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Log Out All Devices") }
                    }
                }
            }

            item { SectionHeader("Activity", "What happened on your account lately.") }
            // ---- notification centre ----
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Notifications", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("New device requests, devices added or removed, and changes to your two-factor settings.", style = MaterialTheme.typography.bodySmall)
                        if (state.everyNotice.isEmpty()) Text("No notifications.", style = MaterialTheme.typography.bodySmall)
                        state.everyNotice.take(30).forEach { n ->
                            Text(
                                (if (n.seen) "" else "NEW  ") + "${whenText(n.at)} - ${SecurityEventNames[n.type] ?: n.type}" + (n.device?.let { ": $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall, fontWeight = if (n.seen) FontWeight.Normal else FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            // ---- log ----
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Login history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        HistoryFilters(state.historyFilter, state.historyLoading, viewModel::setHistoryFilter)
                        if (state.events.isEmpty()) Text(if (state.historyFilter.isActive) "Nothing matches these filters." else "Nothing yet.", style = MaterialTheme.typography.bodySmall)
                        state.events.forEach { e -> EventRow(e) }
                    }
                }
            }
        }
    }

    // ---- dialogs ----
    val confirmError = state.error
    when (val a = ask) {
        Ask.Setup -> SecurityConfirmDialog("Set up an authenticator app", "Confirm with your password to get your setup key.", "Continue", state.busy, confirmError, onConfirm = { p, _ -> viewModel.startSetup(p) }, onDismiss = { ask = null })
        Ask.Disable -> SecurityConfirmDialog("Turn two-factor off?", "Trusted devices will be forgotten and new devices will need only your password.", "Turn off", state.busy, confirmError, askCode = true, destructive = true, onConfirm = { p, c -> viewModel.disable(p, c) }, onDismiss = { ask = null })
        Ask.Regenerate -> SecurityConfirmDialog("Make new recovery codes?", "The old recovery codes will stop working.", "Make new codes", state.busy, confirmError, askCode = true, onConfirm = { p, c -> viewModel.regenerate(p, c) }, onDismiss = { ask = null })
        Ask.TrustThis -> SecurityConfirmDialog("Trust this device?", "It will skip the code until the trust expires or you remove it. Only do this on your own device.", "Trust this device", state.busy, confirmError, askCode = true, onConfirm = { p, c -> viewModel.trustThisDevice(p, c) }, onDismiss = { ask = null })
        Ask.LogOutOthers -> SecurityConfirmDialog("Log out other devices?", "Every other device is logged out on the server and must sign in again. This device stays signed in.", "Log out other devices", state.busy, confirmError, destructive = true, onConfirm = { p, _ -> viewModel.revokeOthers(p) }, onDismiss = { ask = null })
        Ask.LogOutAll -> SecurityConfirmDialog(
            "Log out all devices?",
            "Are you sure you want to log out all devices? This will terminate all active GoPreach sessions associated with your account. You will need to log in again on your devices.",
            "Log Out All Devices", state.busy, confirmError, destructive = true, onConfirm = { p, _ -> viewModel.revokeAll(p) }, onDismiss = { ask = null },
        )
        Ask.RecoveryEmail -> RecoveryEmailDialog(state.busy, confirmError, onConfirm = { email, p -> emailToSet = email; viewModel.setRecoveryEmail(p, email) }, onDismiss = { ask = null })
        is Ask.LogOut -> SecurityConfirmDialog(
            "Log out ${a.device.name}?", if (a.device.current) "This is the device you are using now: you will be signed out here too." else "That device is logged out on the server at once and must sign in again.",
            "Log out", state.busy, confirmError, destructive = true, onConfirm = { p, _ -> viewModel.revoke(a.device, p) }, onDismiss = { ask = null },
        )
        is Ask.DeviceApproval -> SecurityConfirmDialog(
            if (a.enable) "Approve new devices?" else "Stop asking for approval?",
            if (a.enable) "This device becomes the trusted one. Any other device that enters your password will wait for your approval." else "New devices will sign in with your password (and your code, if two-factor is on).",
            if (a.enable) "Turn on" else "Turn off", state.busy, confirmError, onConfirm = { p, _ -> viewModel.setDeviceApproval(a.enable, p) }, onDismiss = { ask = null },
        )
        is Ask.Remove -> SecurityConfirmDialog("Remove ${a.group.name}?", "It is logged out wherever it is signed in and must sign in again with your password (and a code, if two-factor is on). Nothing is remembered on it.", "Remove", state.busy, confirmError, destructive = true, onConfirm = { p, _ -> viewModel.removeDevice(a.group, p) }, onDismiss = { ask = null },
        )
        null -> Unit
    }

    state.setup?.let { setup ->
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = viewModel::cancelSetup,
            title = { Text("Your setup key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    AuthenticatorAppHelp(onOpen = { runCatching { uriHandler.openUri(it) } })
                    Text("Step 2: add GoPreach to the app with this key", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("Open the authenticator app and tap + (Add account). Choose \"Enter a setup key\" (Microsoft: \"Other account\", then \"Or enter code manually\"). Account name: GoPreach. Type or paste the key below and keep Time based selected, then tap Add. Or tap \"Open app\" to skip typing.", style = MaterialTheme.typography.bodySmall)
                    Text(setup.secret.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(setup.secret)) }) { Text("Copy key") }
                        OutlinedButton(onClick = { runCatching { uriHandler.openUri(setup.otpauthUri) } }) { Text("Open app") }
                    }
                    Text("Step 3: type the 6-digit code to finish", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("The app now lists GoPreach with a 6-digit number that changes every 30 seconds. Type the current number. From then on a new device needs your password and that number.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) }, singleLine = true, label = { Text("6-digit code") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(enabled = !state.busy && code.length == 6, onClick = { viewModel.confirmSetup(code) }) { Text("Turn on") } },
            dismissButton = { ActionButton(ActionKind.Cancel, onClick = viewModel::cancelSetup, label = "Cancel") },
        )
    }

    state.recoveryCodes?.let { codes ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Your recovery codes") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Keep them somewhere safe, away from this phone. Each works once, if you lose your authenticator app. They are shown only now.", style = MaterialTheme.typography.bodySmall)
                    codes.forEach { Text(it, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(codes.joinToString("\n"))) }) { Text("Copy all") }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::dismissRecoveryCodes) { Text("I saved them") } },
        )
    }

    renaming?.let { d ->
        var name by remember(d.key) { mutableStateOf(d.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename device") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, singleLine = true, label = { Text("Device name") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { ActionButton(ActionKind.Save, onClick = { viewModel.renameDevice(d, name); renaming = null }, label = "Save", enabled = name.isNotBlank()) },
            dismissButton = { ActionButton(ActionKind.Cancel, onClick = { renaming = null }, label = "Cancel") },
        )
    }
}

/** Runs [onDone] (true) the moment a busy request finishes without an error. */
@Composable
private fun LaunchedEffectOnResult(busy: Boolean, error: String?, onDone: (Boolean) -> Unit) {
    var wasBusy by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(busy) {
        if (wasBusy && !busy && error == null) onDone(true)
        wasBusy = busy
    }
}

@Composable
private fun RecoveryEmailDialog(busy: Boolean, error: String?, onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Recovery e-mail") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A confirmed address lets you get a sign-in code by e-mail when your phone is not at hand.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = email, onValueChange = { email = it }, singleLine = true, label = { Text("E-mail address") }, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                PasswordField(value = password, onValueChange = { password = it }, label = "Current password", enabled = !busy)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { ActionButton(ActionKind.Save, onClick = { onConfirm(email, password) }, label = "Send a code", enabled = !busy && email.contains("@") && password.isNotBlank()) },
        dismissButton = { ActionButton(ActionKind.Cancel, onClick = onDismiss, label = "Cancel", enabled = !busy) },
    )
}

@Composable
internal fun Fact(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = if (mono) FontFamily.Monospace else null, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun Banner(text: String, error: Boolean, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            ActionButton(ActionKind.Save, onClick = onDismiss, label = "OK")
        }
    }
}

/** A password box with a Show / Hide button, so the person can check what was typed. */
@Composable
internal fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth()) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onValueChange, singleLine = true, label = { Text(label) }, enabled = enabled, modifier = modifier,
        visualTransformation = if (shown) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { shown = !shown }) {
                Icon(if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (shown) "Hide password" else "Show password")
            }
        },
    )
}

/** Step 1 of setting up two-factor: what an authenticator app is and where to get one (Play Store links; any authenticator app works). */
@Composable
internal fun AuthenticatorAppHelp(onOpen: (String) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Step 1: get an authenticator app", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("It is a free app that shows a new 6-digit code every 30 seconds. Install one of these (any one is fine), then come back. Already have one? Skip to step 2.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { onOpen("https://play.google.com/store/apps/details?id=com.google.android.apps.authenticator2") }, modifier = Modifier.fillMaxWidth()) { Text("Get Google Authenticator") }
            OutlinedButton(onClick = { onOpen("https://play.google.com/store/apps/details?id=com.azure.authenticator") }, modifier = Modifier.fillMaxWidth()) { Text("Get Microsoft Authenticator") }
        }
    }
}

/** A section title with one plain line under it. */
@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Your account is well protected" and four status tiles: two-factor, approve new devices, recovery codes left, devices signed in. */
@Composable
private fun ProtectionSummary(status: SecurityStatus, activeDevices: Int) {
    val on = status.enabled
    val approval = status.deviceApproval
    val good = Color(0xFF1F8A4C); val amber = Color(0xFFD98A00); val red = Color(0xFFC0392B); val grey = Color(0xFF9AA0A6)
    val (tone, headline, advice) = when {
        on && approval -> Triple(good, "Your account is well protected", "Two-factor sign-in and new-device approval are both on.")
        on -> Triple(amber, "Your account is partly protected", "Turn on Approve new devices so nobody can sign in with only your password.")
        approval -> Triple(amber, "Your account is partly protected", "Turn on two-factor sign-in so a password alone is never enough.")
        else -> Triple(red, "Add a second step to protect your account", "Turn on two-factor sign-in and Approve new devices. It takes about two minutes.")
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = tone.copy(alpha = 0.12f))) {
            Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                androidx.compose.foundation.layout.Box(Modifier.size(52.dp).background(tone, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Icon(Icons.Rounded.Security, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Column {
                    Text(headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                    Text(advice, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatusTile("Two-factor", if (on) "On" else "Off", if (on) good else grey, Modifier.weight(1f))
            StatusTile("New devices", if (approval) "Approved" else "Open", if (approval) good else grey, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatusTile("Recovery codes", if (on) status.recoveryCodesLeft.toString() else "-", if (!on) grey else if (status.recoveryCodesLeft <= 2) amber else good, Modifier.weight(1f))
            StatusTile("Devices signed in", activeDevices.toString(), good, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatusTile(label: String, value: String, dot: Color, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.foundation.layout.Box(Modifier.size(10.dp).background(dot, androidx.compose.foundation.shape.CircleShape))
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

/** Login-history filters: result, kind of activity, a time range and device text; every change is applied by the server. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HistoryFilters(filter: HistoryFilter, loading: Boolean, onChange: (HistoryFilter) -> Unit) {
    val day = 24L * 60 * 60 * 1000
    val now = System.currentTimeMillis()
    val periods = listOf("Any time" to null, "Last 24 hours" to day, "Last 7 days" to 7 * day, "Last 30 days" to 30 * day)
    val from = filter.fromMs
    val period = periods.firstOrNull { (_, span) -> span != null && from != null && kotlin.math.abs((now - from) - span) < 3 * 60 * 60 * 1000L }?.first ?: if (from == null) "Any time" else null
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("All results" to null, "OK" to "success", "Failed" to "failure", "Rejected" to "rejected").forEach { (label, value) ->
                androidx.compose.material3.FilterChip(selected = filter.result == value, onClick = { onChange(filter.copy(result = value)) }, label = { Text(label) })
            }
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.material3.FilterChip(selected = filter.types.isEmpty(), onClick = { onChange(filter.copy(types = emptyList())) }, label = { Text("Everything") })
            SecurityHistoryKinds.forEach { (label, codes) ->
                androidx.compose.material3.FilterChip(selected = filter.types == codes, onClick = { onChange(filter.copy(types = codes)) }, label = { Text(label) })
            }
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            periods.forEach { (label, span) ->
                androidx.compose.material3.FilterChip(selected = period == label, onClick = { onChange(filter.copy(fromMs = span?.let { System.currentTimeMillis() - it }, toMs = null)) }, label = { Text(label) })
            }
        }
        var device by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(filter.device) }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = device, onValueChange = { device = it.take(60) }, label = { Text("Device name") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { onChange(filter.copy(device = device)) }) { Text("Search") }
        }
        if (filter.isActive) TextButton(onClick = { device = ""; onChange(HistoryFilter()) }) { Text("Clear filters") }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun EventRow(e: SecurityEvent) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text((e.username?.let { "$it - " } ?: "") + (SecurityEventNames[e.type] ?: e.type), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(when (e.result) { "success" -> "OK"; "rejected" -> "Rejected"; else -> "Failed" }, style = MaterialTheme.typography.bodySmall, color = if (e.result == "success") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
        Text(whenText(e.at) + (e.device?.let { " - $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(modifier = Modifier.padding(top = 3.dp))
    }
}

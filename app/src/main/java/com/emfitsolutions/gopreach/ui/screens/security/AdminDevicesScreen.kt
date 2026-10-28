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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.repository.AdminSession
import com.emfitsolutions.gopreach.data.repository.ApprovalLimits
import com.emfitsolutions.gopreach.ui.components.AlertDialog
import org.koin.compose.viewmodel.koinViewModel

private val ROLE_FILTERS = listOf(
    "" to "Any role", "SUPER_ADMIN" to "Super Admin", "ADMIN_PER_CONGREGATION" to "Admin", "COORDINATOR_ELDER" to "Coordinator Elder", "SERVICE_OVERSEER" to "Service Overseer",
    "SECRETARY" to "Secretary", "REGULAR_ELDER" to "Regular Elder", "MINISTERIAL_SERVANT" to "Ministerial Servant", "CIRCUIT_OVERSEER" to "Circuit Overseer",
    "REGULAR_PIONEER" to "Regular Pioneer", "AUXILIARY_PIONEER" to "Auxiliary Pioneer", "SPECIAL_PIONEER" to "Special Pioneer", "REGULAR_PUBLISHER" to "Publisher",
)

private fun roleWord(r: String) = r.lowercase().split('_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

/**
 * Device & Session Management. The Super Admin sees every device; the Admin of a congregation sees the devices of that congregation only (the server enforces both).
 * Passwords, codes and tokens are never part of what is shown. Ending a session takes effect on the server at once and is recorded in the security log.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDevicesScreen(onBack: () -> Unit, viewModel: AdminDevicesViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var revoke by remember { mutableStateOf<AdminSession?>(null) }
    var revokeAll by remember { mutableStateOf<AdminSession?>(null) }
    var roleMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device & Session Management") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Passwords, codes and tokens are never shown. Ending a session takes effect on the server at once.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = state.query, onValueChange = viewModel::setQuery, singleLine = true, label = { Text("Username, name, congregation or device") }, modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { TextButton(onClick = viewModel::search) { Text("Search") } },
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("" to "Any status", "active" to "Active", "expired" to "Expired", "revoked" to "Logged out").forEach { (k, l) ->
                            FilterChip(selected = state.status == k, onClick = { viewModel.setStatus(k) }, label = { Text(l) })
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("" to "Any device", "phone" to "Phone", "tablet" to "Tablet", "web" to "Web").forEach { (k, l) ->
                            FilterChip(selected = state.type == k, onClick = { viewModel.setType(k) }, label = { Text(l) })
                        }
                    }
                    androidx.compose.foundation.layout.Box {
                        OutlinedButton(onClick = { roleMenu = true }) { Text(ROLE_FILTERS.firstOrNull { it.first == state.role }?.second ?: "Any role") }
                        androidx.compose.material3.DropdownMenu(expanded = roleMenu, onDismissRequest = { roleMenu = false }) {
                            ROLE_FILTERS.forEach { (k, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { roleMenu = false; viewModel.setRole(k) }) }
                        }
                    }
                }
            }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.message?.let { m -> item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Row(Modifier.padding(start = 14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text(m, Modifier.weight(1f)); ActionButton(ActionKind.Save, onClick = viewModel::clearMessage, label = "OK") } } } }
            state.error?.let { m -> item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) { Row(Modifier.padding(start = 14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text(m, Modifier.weight(1f)); ActionButton(ActionKind.Save, onClick = viewModel::clearMessage, label = "OK") } } } }
            item { Text("${state.sessions.size} session${if (state.sessions.size == 1) "" else "s"}", fontWeight = FontWeight.Bold) }
            items(state.sessions, key = { it.sessionId }) { s ->
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text((s.name?.takeIf { it.isNotBlank() } ?: s.username ?: s.personId) + (s.username?.let { "  ($it)" } ?: ""), fontWeight = FontWeight.Bold)
                        Fact("Congregation", s.congregation ?: "-")
                        Fact("Roles", s.roles.joinToString(", ") { roleWord(it) }.ifBlank { "-" })
                        Fact("Device", s.deviceName + " - " + when (s.type) { "phone" -> "Phone"; "tablet" -> "Tablet"; "web" -> "Web browser"; else -> "Device" })
                        Fact("System", listOfNotNull(s.os, s.osVersion).joinToString(" ").ifBlank { "-" })
                        Fact("GoPreach version", s.appVersion ?: "-")
                        Fact("Signed in", whenText(s.loginAt))
                        Fact("Last active", whenText(s.lastActiveAt))
                        Fact("Status", when (s.status) { "active" -> "Active"; "expired" -> "Expired"; "revoked" -> "Logged out"; else -> s.status })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.showHistory(s.personId, s.username ?: s.personId) }) { Text("History") }
                            if (s.status == "active") {
                                ActionButton(ActionKind.Delete, onClick = { revoke = s }, label = "Revoke")
                                OutlinedButton(onClick = { revokeAll = s }) { Text("Revoke all", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
            if (!state.loading && state.sessions.isEmpty()) item { Text("No sessions match.", style = MaterialTheme.typography.bodySmall) }
        }
    }

    revoke?.let { s ->
        AlertDialog(
            onDismissRequest = { revoke = null }, title = { Text("End this session?") },
            text = { Text("${s.username ?: s.personId} on ${s.deviceName} will be logged out on the server at once and must sign in again.") },
            confirmButton = { TextButton(onClick = { viewModel.revoke(s.sessionId); revoke = null }) { Text("Revoke", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { ActionButton(ActionKind.Cancel, onClick = { revoke = null }, label = "Cancel") },
        )
    }
    revokeAll?.let { s ->
        AlertDialog(
            onDismissRequest = { revokeAll = null }, title = { Text("End all sessions?") },
            text = { Text("Every session of ${s.username ?: s.personId} is ended on the server. They must sign in again on every device.") },
            confirmButton = { TextButton(onClick = { viewModel.revokeAll(s.personId); revokeAll = null }) { Text("Revoke all", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { ActionButton(ActionKind.Cancel, onClick = { revokeAll = null }, label = "Cancel") },
        )
    }
    state.history?.let { (label, events) ->
        AlertDialog(
            onDismissRequest = viewModel::closeHistory, title = { Text("Login history: $label") },
            text = { Column(Modifier.fillMaxWidth()) { if (events.isEmpty()) Text("Nothing recorded."); events.take(40).forEach { EventRow(it) } } },
            confirmButton = { ActionButton(ActionKind.Cancel, onClick = viewModel::closeHistory, label = "Close") },
        )
    }
}

private val LOG_FILTERS = listOf("" to "Every event") + SecurityEventNames.entries.map { it.key to it.value }

/** Super Admin: how many new-device requests one account may raise, in how many minutes, and how long a request waits. Needs the password again. */
@Composable
private fun ApprovalLimitsCard(limits: ApprovalLimits, busy: Boolean, message: String?, error: String?, onSave: (ApprovalLimits, String) -> Unit) {
    var max by remember(limits) { mutableStateOf(limits.maxRequests.toString()) }
    var window by remember(limits) { mutableStateOf(limits.windowMinutes.toString()) }
    var expiry by remember(limits) { mutableStateOf(limits.expiryMinutes.toString()) }
    var password by remember { mutableStateOf("") }
    val parsed = ApprovalLimits(max.toIntOrNull() ?: -1, window.toIntOrNull() ?: -1, expiry.toIntOrNull() ?: -1)
    val valid = parsed.maxRequests in 1..20 && parsed.windowMinutes in 1..120 && parsed.expiryMinutes in 1..30
    androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("New-device request limits", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text("For accounts that turned on Approve new devices. Each account may raise at most this many requests in the time window; more tries are refused (Too many attempts) until it passes. Changes apply at once and are logged below.", style = MaterialTheme.typography.bodySmall)
            val number = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            OutlinedTextField(value = max, onValueChange = { max = it.filter(Char::isDigit).take(2) }, label = { Text("Requests per account (1-20)") }, singleLine = true, keyboardOptions = number, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = window, onValueChange = { window = it.filter(Char::isDigit).take(3) }, label = { Text("Within (minutes, 1-120)") }, singleLine = true, keyboardOptions = number, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = expiry, onValueChange = { expiry = it.filter(Char::isDigit).take(2) }, label = { Text("A request waits (minutes, 1-30)") }, singleLine = true, keyboardOptions = number, modifier = Modifier.fillMaxWidth())
            PasswordField(value = password, onValueChange = { password = it }, label = "Your password")
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            androidx.compose.material3.Button(onClick = { onSave(parsed, password); password = "" }, enabled = valid && password.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Saving..." else "Save limits") }
        }
    }
}

/** Security Audit Logs: the append-only record of security events across GoPreach. Super Admin only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityLogScreen(onBack: () -> Unit, viewModel: SecurityLogViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security Audit Logs") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { ApprovalLimitsCard(state.limits, state.limitsBusy, state.limitsMessage, state.limitsError, viewModel::saveLimits) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Append-only: nothing here can be changed or deleted, and it never holds a password, code, recovery code or token.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = state.personId, onValueChange = viewModel::setPerson, singleLine = true, label = { Text("Person ID (optional)") }, modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { TextButton(onClick = viewModel::load) { Text("Filter") } },
                    )
                    androidx.compose.foundation.layout.Box {
                        OutlinedButton(onClick = { menu = true }) { Text(LOG_FILTERS.firstOrNull { it.first == state.type }?.second ?: "Every event") }
                        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            LOG_FILTERS.forEach { (k, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { menu = false; viewModel.setType(k) }) }
                        }
                    }
                }
            }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            items(state.events, key = { it.id }) { EventRow(it) }
            if (!state.loading && state.events.isEmpty() && state.error == null) item { Text("No events.", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

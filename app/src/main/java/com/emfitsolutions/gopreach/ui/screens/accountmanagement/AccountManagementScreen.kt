package com.emfitsolutions.gopreach.ui.screens.accountmanagement

import androidx.compose.foundation.layout.Arrangement
import com.emfitsolutions.gopreach.ui.components.RecordFound
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.flowOf
import com.emfitsolutions.gopreach.data.model.AccountStatus
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.repository.AuthResult
import com.emfitsolutions.gopreach.ui.components.CongregationFilterDropdown
import com.emfitsolutions.gopreach.ui.components.SelectCongregationPrompt
import com.emfitsolutions.gopreach.ui.components.rememberCongregationContext
import com.emfitsolutions.gopreach.ui.components.FormDialog
import com.emfitsolutions.gopreach.ui.components.ReadOnlyField
import com.emfitsolutions.gopreach.ui.components.rememberActionToast

/**
 * Account / Credential Management — congregation- and role-scoped username
 * changes + account-status control (spec §1-§5/§10). [actingRole]/
 * [actingCongregationId] come from the signed-in session (see GoPreachNavGraph's
 * `currentRole`/`ownCongregationId`, the same pair every other congregation-
 * scoped screen in this app already threads down) — this screen never
 * re-derives them itself, so it can never disagree with what the rest of the
 * app already decided the session's scope is.
 *
 * Reset Password is deliberately shown but disabled (see its dialog's copy):
 * Firebase's client SDK can only change the *signed-in* account's own
 * password — resetting someone else's needs a Cloud Function backend this
 * project doesn't have yet (see BUILD_PLAN.md's Account Management phase).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountManagementScreen(
    actingRole: AdminRole?,
    actingCongregationId: String?,
    currentPersonId: String,
    onBack: () -> Unit,
    viewModel: AccountManagementViewModel = hiltViewModel(),
) {
    val availableTypes = remember(actingRole) { viewModel.availableAccountTypes(actingRole) }
    var selectedType by remember(availableTypes) { mutableStateOf(availableTypes.firstOrNull()) }
    val isSuperAdmin = actingRole == AdminRole.SUPER_ADMIN
    var congregationFilter by rememberCongregationContext("account_management")
    val needsCongregation = isSuperAdmin && congregationFilter == null
    var searchQuery by remember { mutableStateOf("") }
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()

    val type = selectedType
    val rowsFlow = remember(actingRole, actingCongregationId, type, congregationFilter, searchQuery, needsCongregation) {
        if (type == null || needsCongregation) flowOf(emptyList()) else
            viewModel.rowsFor(actingRole, actingCongregationId, type, congregationFilter, searchQuery)
    }
    val rows by rowsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    var pendingUsernameChange by remember { mutableStateOf<AccountRow?>(null) }
    var pendingResetPasswordInfo by remember { mutableStateOf<AccountRow?>(null) }
    val showToast = rememberActionToast()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account Management") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isSuperAdmin) {
                CongregationFilterDropdown(
                    congregations = congregations,
                    selectedCongregationId = congregationFilter,
                    onSelected = { congregationFilter = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                availableTypes.forEach { accountType ->
                    FilterChip(
                        selected = accountType == selectedType,
                        onClick = { selectedType = accountType },
                        label = { Text(accountType.label) },
                    )
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search by name, username, or congregation") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (needsCongregation) {
                SelectCongregationPrompt()
            } else if (rows.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RecordFound(0)
                    Text(
                        if (type == null) "No account types available for your role." else "No accounts found.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { RecordFound(rows.size) }
                    items(rows, key = { it.person.id }) { row ->
                        AccountRowCard(
                            row = row,
                            onChangeUsername = { pendingUsernameChange = row },
                            onResetPassword = { pendingResetPasswordInfo = row },
                            onSetStatus = { status -> viewModel.setAccountStatus(row, status, currentPersonId) },
                        )
                    }
                }
            }
        }
    }

    val usernameTarget = pendingUsernameChange
    if (usernameTarget != null) {
        ChangeUsernameDialog(
            row = usernameTarget,
            onDismiss = { pendingUsernameChange = null },
            onConfirm = { newUsername ->
                viewModel.changeUsername(usernameTarget, newUsername, currentPersonId) { result ->
                    when (result) {
                        is AuthResult.Success -> {
                            showToast("Username updated for ${usernameTarget.person.fullName}.")
                            pendingUsernameChange = null
                        }
                        is AuthResult.Error -> showToast(result.message)
                    }
                }
            },
        )
    }

    val resetTarget = pendingResetPasswordInfo
    if (resetTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingResetPasswordInfo = null },
            title = { Text("Reset Password") },
            text = {
                Text(
                    "Password reset for other accounts requires a small backend service " +
                        "that isn't set up yet for this project (it needs the Firebase Blaze " +
                        "plan plus a Cloud Function — Firebase's app-side login system can only " +
                        "ever change the password of whoever is currently signed in). Ask " +
                        "${resetTarget.person.fullName} to use their existing temporary-credential " +
                        "sign-in from enrollment, or wait for this to be enabled.",
                )
            },
            confirmButton = { TextButton(onClick = { pendingResetPasswordInfo = null }) { Text("Got It") } },
        )
    }
}

@Composable
private fun AccountRowCard(
    row: AccountRow,
    onChangeUsername: () -> Unit,
    onResetPassword: () -> Unit,
    onSetStatus: (AccountStatus) -> Unit,
) {
    var statusMenuOpen by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(row.person.fullName, style = MaterialTheme.typography.titleMedium)
                Text("@${row.person.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${row.accountType.label} · ${row.congregationName}", style = MaterialTheme.typography.bodySmall)
                AssistChip(onClick = {}, label = { Text(row.person.accountStatus.name) })
            }
            IconButton(onClick = onChangeUsername) { Icon(Icons.Rounded.Edit, contentDescription = "Change username") }
            IconButton(onClick = onResetPassword) { Icon(Icons.Rounded.VpnKey, contentDescription = "Reset password") }
            Box {
                IconButton(onClick = { statusMenuOpen = true }) {
                    Icon(
                        when (row.person.accountStatus) {
                            AccountStatus.ACTIVE -> Icons.Rounded.CheckCircle
                            AccountStatus.INACTIVE -> Icons.Rounded.Block
                            AccountStatus.SUSPENDED -> Icons.Rounded.PauseCircle
                        },
                        contentDescription = "Change status (currently ${row.person.accountStatus})",
                    )
                }
                DropdownMenu(expanded = statusMenuOpen, onDismissRequest = { statusMenuOpen = false }) {
                    AccountStatus.entries.forEach { status ->
                        DropdownMenuItem(
                            text = { Text(status.name) },
                            onClick = { statusMenuOpen = false; onSetStatus(status) },
                        )
                    }
                }
            }
        }
    }
}

/** Spec §7 — current username shown read-only, new-username field, inline
 * validation, then [FormDialog]'s own confirm step doubles as the
 * confirmation dialog step 5 asks for. */
@Composable
private fun ChangeUsernameDialog(
    row: AccountRow,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var newUsername by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    FormDialog(
        onDismissRequest = onDismiss,
        title = "Change Username",
        onConfirm = {
            val trimmed = newUsername.trim()
            if (trimmed.isBlank()) {
                errorMessage = "Enter a new username."
            } else if (trimmed == row.person.username) {
                errorMessage = "That's already this account's username."
            } else {
                errorMessage = null
                onConfirm(trimmed)
            }
        },
        confirmLabel = "Save",
        errorMessage = errorMessage,
        hasUnsavedChanges = newUsername.isNotBlank(),
    ) {
        ReadOnlyField("Current Username", row.person.username)
        OutlinedTextField(
            value = newUsername,
            onValueChange = { newUsername = it },
            label = { Text("New Username") },
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

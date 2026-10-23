package com.emfitsolutions.gopreach.ui.screens.accountmanagement

import androidx.compose.material3.HorizontalDivider
import com.emfitsolutions.gopreach.ui.components.RecordViewToggle
import com.emfitsolutions.gopreach.ui.components.UniversalColumn
import com.emfitsolutions.gopreach.ui.components.recordItems
import com.emfitsolutions.gopreach.ui.components.rememberRecordViewMode
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import com.emfitsolutions.gopreach.ui.components.AlertDialog
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
import com.emfitsolutions.gopreach.ui.components.Scaffold
import com.emfitsolutions.gopreach.ui.components.HideInFullScreen
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
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.flowOf
import com.emfitsolutions.gopreach.data.model.AccountStatus
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.repository.AuthResult
import com.emfitsolutions.gopreach.ui.navigation.Destinations
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
    onCreateAccount: (String) -> Unit = {},
    /** Shortcuts to the account screens that live inside this module: Circuit Overseer Accounts (Super Admin) and User Management (restricted users). */
    onOpenCircuitAccounts: (() -> Unit)? = null,
    onOpenRestrictedUsers: (() -> Unit)? = null,
    viewModel: AccountManagementViewModel = koinViewModel(),
) {
    val availableTypes = remember(actingRole) { viewModel.availableAccountTypes(actingRole) }
    var selectedType by remember(availableTypes) { mutableStateOf(availableTypes.firstOrNull()) }
    val isSuperAdmin = actingRole == AdminRole.SUPER_ADMIN
    var congregationFilter by rememberCongregationContext("account_management")
    // Circuit Overseers and "All accounts" do not belong to one congregation, so the Super Admin needs no congregation for them.
    val needsCongregation = isSuperAdmin && congregationFilter == null && selectedType != AccountType.CIRCUIT_OVERSEER && selectedType != AccountType.ALL
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<AccountStatus?>(null) }
    var showAudit by remember { mutableStateOf(false) }
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()

    val type = selectedType
    val rowsFlow = remember(actingRole, actingCongregationId, type, congregationFilter, searchQuery, needsCongregation, statusFilter) {
        if (type == null || needsCongregation) flowOf(emptyList()) else
            viewModel.rowsFor(actingRole, actingCongregationId, type, congregationFilter, searchQuery, statusFilter)
    }
    val auditRows by remember(actingRole, actingCongregationId) { viewModel.auditHistory(actingRole, actingCongregationId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val enrollRoute = when (type) {
        AccountType.PUBLISHER -> Destinations.ENROLL_PUBLISHER
        AccountType.ELDER -> Destinations.ENROLL_ELDER
        AccountType.MINISTERIAL_SERVANT -> Destinations.ENROLL_MINISTERIAL_SERVANT
        AccountType.ADMIN -> Destinations.ENROLL_ADMIN
        else -> null
    }
    val rows by rowsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    var pendingUsernameChange by remember { mutableStateOf<AccountRow?>(null) }
    var pendingResetPasswordInfo by remember { mutableStateOf<AccountRow?>(null) }
    var lookupTarget by remember { mutableStateOf<com.emfitsolutions.gopreach.data.model.Person?>(null) }
    val showToast = rememberActionToast()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account Management") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (!showAudit && enrollRoute != null) TextButton(onClick = { onCreateAccount(enrollRoute) }) { Text("+ Create Account") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = !showAudit, onClick = { showAudit = false }, label = { Text("All User Accounts") })
                FilterChip(selected = showAudit, onClick = { showAudit = true }, label = { Text("Audit History") })
                onOpenCircuitAccounts?.let { FilterChip(selected = false, onClick = it, label = { Text("Circuit Overseers", maxLines = 1, softWrap = false) }) }
                onOpenRestrictedUsers?.let { FilterChip(selected = false, onClick = it, label = { Text("Restricted Users", maxLines = 1, softWrap = false) }) }
            }
            if (showAudit) {
                if (auditRows.isEmpty()) {
                    Text("No account changes recorded yet.", modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
                } else LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(auditRows) { a ->
                        androidx.compose.material3.OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${a.action} · ${a.account}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text("By ${a.actor}", style = MaterialTheme.typography.bodySmall)
                                if (a.details.isNotBlank()) Text(a.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(java.text.SimpleDateFormat("MMM d, yyyy h:mm a", java.util.Locale.getDefault()).format(java.util.Date(a.timestamp)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                return@Column
            }
            HideInFullScreen(true) {
            if (isSuperAdmin) {
                CongregationFilterDropdown(
                    congregations = congregations,
                    selectedCongregationId = congregationFilter,
                    onSelected = { congregationFilter = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                availableTypes.forEach { accountType ->
                    FilterChip(
                        selected = accountType == selectedType,
                        onClick = { selectedType = accountType },
                        label = { Text(accountType.label, maxLines = 1, softWrap = false) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = statusFilter == null, onClick = { statusFilter = null }, label = { Text("Any status") })
                AccountStatus.entries.forEach { st ->
                    FilterChip(selected = statusFilter == st, onClick = { statusFilter = st }, label = { Text(st.name.lowercase().replaceFirstChar { it.uppercase() }) })
                }
            }

            }
            HideInFullScreen(false) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search name, username, role, congregation or FS Group", maxLines = 1) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") } },
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )

            }
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
                val viewMode = rememberRecordViewMode("account_management")
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(
                            "${rows.size} " + if (rows.size == 1) "account" else "accounts",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item { RecordViewToggle(viewMode) }
                    recordItems(
                        viewMode, rows, { it.person.id },
                        listOf(
                                UniversalColumn("Name", 200.dp) { it.person.fullName },
                                UniversalColumn("Username", 150.dp) { it.person.username },
                                UniversalColumn("Role / circuit", 200.dp) { (it.roles.ifBlank { it.accountType.label }) + (it.circuitCode?.let { c -> " · $c" } ?: "") },
                                UniversalColumn("Congregation", 180.dp) { it.congregationName },
                                UniversalColumn("Status", 110.dp) { it.person.accountStatus.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
                        ),
                    ) { row ->
                        AccountRowCard(
                            row = row,
                            onChangeUsername = { pendingUsernameChange = row },
                            onResetPassword = { pendingResetPasswordInfo = row },
                            onSetStatus = { status -> viewModel.setAccountStatus(row, status, currentPersonId) },
                            onViewTemporarySignIn = { viewModel.logTemporarySignInViewed(row, currentPersonId); lookupTarget = row.person },
                        )
                    }
                }
            }
        }
    }

    pendingUsernameChange?.let { row ->
        ChangeUsernameFlow(row, viewModel, currentPersonId, showToast, onDismiss = { pendingUsernameChange = null })
    }
    lookupTarget?.let { com.emfitsolutions.gopreach.ui.components.TempCredentialLookupDialog(person = it, onDismiss = { lookupTarget = null }) }
    pendingResetPasswordInfo?.let { row ->
        TemporaryPasswordFlow(row, viewModel, currentPersonId, showToast, keepReadable = !isSuperAdmin, onDismiss = { pendingResetPasswordInfo = null })
    }
}

@Composable
private fun AccountRowCard(
    row: AccountRow,
    onChangeUsername: () -> Unit,
    onResetPassword: () -> Unit,
    onSetStatus: (AccountStatus) -> Unit,
    onViewTemporarySignIn: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    val status = row.person.accountStatus
    val (pillContainer, pillContent) = when (status) {
        AccountStatus.ACTIVE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        AccountStatus.INACTIVE -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        AccountStatus.SUSPENDED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    val initials = row.person.fullName.split(',', ' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    androidx.compose.material3.OutlinedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onChangeUsername)) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(initials, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.person.fullName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text("@${row.person.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text((row.roles.ifBlank { row.accountType.label }) + (row.circuitCode?.let { " · Circuit $it" } ?: "") + " · ${row.congregationName}" + (row.group?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text("Password: ••••••••", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    status.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = pillContent,
                    modifier = Modifier.padding(top = 2.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(pillContainer).padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Account options") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Change username") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onChangeUsername() },
                    )
                    DropdownMenuItem(
                        text = { Text("Set temporary password") },
                        leadingIcon = { Icon(Icons.Rounded.VpnKey, contentDescription = null) },
                        onClick = { menuOpen = false; onResetPassword() },
                    )
                    if (row.person.isTemporaryCredential) DropdownMenuItem(
                        text = { Text("View temporary sign-in") },
                        leadingIcon = { Icon(Icons.Rounded.VpnKey, contentDescription = null) },
                        onClick = { menuOpen = false; onViewTemporarySignIn() },
                    )
                    HorizontalDivider()
                    AccountStatus.entries.filter { it != status }.forEach { target ->
                        DropdownMenuItem(
                            text = { Text("Set ${target.name.lowercase()}") },
                            leadingIcon = {
                                Icon(
                                    when (target) {
                                        AccountStatus.ACTIVE -> Icons.Rounded.CheckCircle
                                        AccountStatus.INACTIVE -> Icons.Rounded.Block
                                        AccountStatus.SUSPENDED -> Icons.Rounded.PauseCircle
                                    },
                                    contentDescription = null,
                                )
                            },
                            onClick = { menuOpen = false; onSetStatus(target) },
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
internal fun ChangeUsernameDialog(
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

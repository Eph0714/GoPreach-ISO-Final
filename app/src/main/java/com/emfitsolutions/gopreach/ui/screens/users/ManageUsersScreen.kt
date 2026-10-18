package com.emfitsolutions.gopreach.ui.screens.users

import androidx.compose.foundation.layout.Arrangement
import com.emfitsolutions.gopreach.ui.components.RecordViewToggle
import com.emfitsolutions.gopreach.ui.components.UniversalColumn
import com.emfitsolutions.gopreach.ui.components.recordItems
import com.emfitsolutions.gopreach.ui.components.rememberRecordViewMode
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.emfitsolutions.gopreach.ui.components.Scaffold
import com.emfitsolutions.gopreach.ui.components.HideInFullScreen
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.AccountStatus
import com.emfitsolutions.gopreach.ui.components.DeleteChoiceDialog

/**
 * "User Management" module (spec §3) — every Circuit Overseer / custom
 * restricted account. Reached only when the signed-in user is a Super-Admin, or
 * an Admin explicitly granted MANAGE_USERS (spec §14) — enforced by the nav
 * graph, not this screen. [canPermanentlyDelete] is Super-Admin-only, per the
 * "Admin Record Deletion" spec's scoping decision (see BUILD_PLAN.md).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageUsersScreen(
    currentPersonId: String,
    canPermanentlyDelete: Boolean,
    onBack: () -> Unit,
    onAddNew: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: ManageUsersViewModel = koinViewModel(),
) {
    val allUsers by viewModel.users.collectAsStateWithLifecycle()
    var showInactive by remember { mutableStateOf(false) }
    val users = allUsers.filter { showInactive || it.person.accountStatus == AccountStatus.ACTIVE }
    var pendingDelete by remember { mutableStateOf<RestrictedUserRow?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("User Management") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        floatingActionButton = {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = onAddNew,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Add User") },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            HideInFullScreen(true) {
            androidx.compose.material3.FilterChip(
                selected = showInactive,
                onClick = { showInactive = !showInactive },
                label = { Text("Include inactive") },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            }
        if (users.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                RecordFound(0)
                Text(
                    "No Circuit Overseer or custom users yet. Tap + to add one — you'll choose exactly what they can see and where.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
                val viewMode = rememberRecordViewMode("manage_users")
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "${users.size} " + if (users.size == 1) "user" else "users",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                    item { RecordViewToggle(viewMode) }
                    recordItems(
                        viewMode, users, { it.person.id },
                        listOf(
                            UniversalColumn<com.emfitsolutions.gopreach.ui.screens.users.RestrictedUserRow>("Name", 200.dp) { it.person.fullName },
                            UniversalColumn("Username", 150.dp) { it.person.username },
                            UniversalColumn("Status", 110.dp) { it.person.accountStatus.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
                            UniversalColumn("Permissions", 110.dp) { (it.grant?.resolvedPermissions?.size ?: 0).toString() },
                        ),
                        onRowClick = { onEdit(it.person.id) },
                        gap = 12.dp,
                    ) { row ->
                    val status = row.person.accountStatus
                    com.emfitsolutions.gopreach.ui.screens.elders.PersonRecordCard(
                        name = row.person.fullName,
                        detail = "Circuit Overseer · @${row.person.username}",
                        active = status == AccountStatus.ACTIVE,
                        readOnly = false,
                        hasTemporarySignIn = false,
                        chips = listOf("${row.grant?.resolvedPermissions?.size ?: 0} permissions") +
                            (if (status == AccountStatus.SUSPENDED) listOf("Suspended") else emptyList()),
                        onEdit = { onEdit(row.person.id) },
                        onDelete = { pendingDelete = row },
                        onRestore = { viewModel.setAccountStatus(row, AccountStatus.ACTIVE, currentPersonId) },
                        onShowSignIn = {},
                        menuExtras = { dismiss ->
                            AccountStatus.entries.filter { it != status && it != AccountStatus.INACTIVE }.forEach { target ->
                                DropdownMenuItem(
                                    text = { Text("Set ${target.name.lowercase()}") },
                                    onClick = { dismiss(); viewModel.setAccountStatus(row, target, currentPersonId) },
                                )
                            }
                        },
                    )
                }
            }
        }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        DeleteChoiceDialog(
            recordLabel = toDelete.person.fullName,
            canPermanentlyDelete = canPermanentlyDelete,
            onDismiss = { pendingDelete = null },
            onMoveToInactive = { viewModel.setAccountStatus(toDelete, AccountStatus.INACTIVE, currentPersonId) },
            onDeletePermanently = { viewModel.permanentlyDelete(toDelete, currentPersonId) },
        )
    }
}

@Composable
private fun StatusChip(status: AccountStatus) {
    AssistChip(onClick = {}, label = { Text(status.name) })
}

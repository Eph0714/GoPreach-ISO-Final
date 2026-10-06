package com.emfitsolutions.gopreach.ui.screens.users

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
import androidx.compose.material3.Scaffold
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
import androidx.hilt.navigation.compose.hiltViewModel
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
    viewModel: ManageUsersViewModel = hiltViewModel(),
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
            FloatingActionButton(onClick = onAddNew) { Icon(Icons.Rounded.Add, contentDescription = "Add User") }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = showInactive, onCheckedChange = { showInactive = it })
                Text("Show Inactive")
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { RecordFound(users.size) }
                items(users, key = { it.person.id }) { row ->
                    var menuOpen by remember { mutableStateOf(false) }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(row.person.fullName, style = MaterialTheme.typography.titleMedium)
                                Text("Circuit Overseer", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "@${row.person.username}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    AssistChip(onClick = {}, label = { Text("${row.grant?.resolvedPermissions?.size ?: 0} permissions") })
                                    StatusChip(row.person.accountStatus)
                                }
                            }
                            Row {
                                IconButton(onClick = { onEdit(row.person.id) }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = "Edit access")
                                }
                                if (row.person.accountStatus == AccountStatus.ACTIVE) {
                                    IconButton(onClick = { pendingDelete = row }) {
                                        Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                                    }
                                } else {
                                    IconButton(onClick = { viewModel.setAccountStatus(row, AccountStatus.ACTIVE, currentPersonId) }) {
                                        Icon(Icons.Rounded.RestoreFromTrash, contentDescription = "Reactivate")
                                    }
                                }
                                Box {
                                    IconButton(onClick = { menuOpen = true }) {
                                        Icon(
                                            when (row.person.accountStatus) {
                                                AccountStatus.ACTIVE -> Icons.Rounded.CheckCircle
                                                AccountStatus.INACTIVE -> Icons.Rounded.Block
                                                AccountStatus.SUSPENDED -> Icons.Rounded.PauseCircle
                                            },
                                            contentDescription = "Change status (currently ${row.person.accountStatus})",
                                        )
                                    }
                                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                        AccountStatus.entries.forEach { status ->
                                            DropdownMenuItem(
                                                text = { Text(status.name) },
                                                onClick = {
                                                    menuOpen = false
                                                    viewModel.setAccountStatus(row, status, currentPersonId)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
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

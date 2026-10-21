package com.emfitsolutions.gopreach.ui.screens.accountmanagement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.repository.AuthResult
import com.emfitsolutions.gopreach.ui.components.AlertDialog

/** An [AccountRow] for a Publisher, for screens (Manage Publishers) that already know the person. */
fun publisherAccountRow(person: Person): AccountRow =
    AccountRow(person = person, accountType = AccountType.PUBLISHER, targetAdminRole = null, congregationId = person.activeCongregationId, congregationName = "")

/**
 * Change a person's username: the current one, a field for the new one, the server-side uniqueness check, and a success or error message.
 * Used by Account Management and by Manage Publishers, so there is one behaviour in both places.
 */
@Composable
fun ChangeUsernameFlow(
    row: AccountRow,
    viewModel: AccountManagementViewModel,
    actingPersonId: String,
    showToast: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ChangeUsernameDialog(
        row = row,
        onDismiss = onDismiss,
        onConfirm = { newUsername ->
            viewModel.changeUsername(row, newUsername, actingPersonId) { result ->
                when (result) {
                    is AuthResult.Success -> { showToast("Username updated for ${row.person.fullName}."); onDismiss() }
                    is AuthResult.Error -> showToast(result.message)
                }
            }
        },
    )
}

/**
 * Set a temporary password: asks first, sets a random one on the server, then shows it once with a Copy button. The person chooses their own at the next
 * sign-in; a password they chose themselves is never readable. Used by Account Management and by Manage Publishers.
 */
@Composable
fun TemporaryPasswordFlow(
    row: AccountRow,
    viewModel: AccountManagementViewModel,
    actingPersonId: String,
    showToast: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var working by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val shown = password
    if (shown == null) {
        AlertDialog(
            onDismissRequest = { if (!working) onDismiss() },
            title = { Text("Set temporary password") },
            text = {
                Text(
                    "A new temporary password will be set for ${row.person.fullName} (@${row.person.username}). Their current password stops working. " +
                        "Give them the temporary password; they choose their own when they sign in.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !working,
                    onClick = {
                        working = true
                        viewModel.resetPassword(row, actingPersonId) { result ->
                            working = false
                            result.onSuccess { password = it }.onFailure { showToast(it.message ?: "Could not set the password."); onDismiss() }
                        }
                    },
                ) { Text(if (working) "Setting..." else "Set password") }
            },
            dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text("Cancel") } },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Temporary password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${row.person.fullName} (@${row.person.username}) signs in with this password, then chooses their own. It is shown only now.")
                    Text(shown, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
            dismissButton = {
                TextButton(onClick = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("Temporary password", shown))
                    showToast("Password copied.")
                }) { Text("Copy") }
            },
        )
    }
}

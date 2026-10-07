package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.AccountStatus
import com.emfitsolutions.gopreach.ui.components.ReadOnlyField
import com.emfitsolutions.gopreach.ui.components.formatRecordTimestamp
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.SimpleDropdown
import org.koin.compose.viewmodel.koinViewModel

/**
 * Add / Edit / View one Circuit Overseer. [personId] null = add; non-null = edit, or view when [readOnly].
 * Required fields are starred. A congregation already held by a different overseer is shown but cannot be
 * ticked — it becomes available only after it is unticked on its current overseer's account.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircuitOverseerFormScreen(
    personId: String?,
    readOnly: Boolean,
    currentPersonId: String,
    onBack: () -> Unit,
    viewModel: CircuitOverseerFormViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val codes by viewModel.codes.collectAsStateWithLifecycle()
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()
    val showToast = rememberActionToast()
    var showPasswordInfo by remember { mutableStateOf(false) }

    LaunchedEffect(personId) { if (personId != null) viewModel.loadForEdit(personId) }
    LaunchedEffect(state.done) {
        if (state.done) {
            showToast(if (state.isEdit) "Circuit Overseer saved." else "Circuit Overseer account created.")
            onBack()
        }
    }

    state.credentials?.let { credentials ->
        NewOverseerCredentials(credentials, onDone = onBack)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (readOnly) "Circuit Overseer" else if (personId == null) "New Circuit Overseer" else "Edit Circuit Overseer") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        if (state.loading) {
            Column(modifier = Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.padding(32.dp))
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = state.lastName, onValueChange = viewModel::onLastName, label = { Text("Last Name *") },
                singleLine = true, readOnly = readOnly, visualTransformation = VisualTransformation.None, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.firstName, onValueChange = viewModel::onFirstName, label = { Text("First Name *") },
                singleLine = true, readOnly = readOnly, visualTransformation = VisualTransformation.None, modifier = Modifier.fillMaxWidth(),
            )
            if (!state.isEdit) {
                Text(
                    "A temporary username and password are generated when you save. Give them to the Circuit Overseer — they must change both at first sign-in.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                ReadOnlyField("Username", state.username)
                ReadOnlyField("Account", if (state.temporary) "Temporary Account — has not signed in yet" else "Active")
                ReadOnlyField("Password", "••••••••")
                if (!readOnly) OutlinedButton(onClick = { showPasswordInfo = true }) { Text("Change Password") }
            }

            HorizontalDivider()
            SimpleDropdown(
                label = "Assigned Circuit *",
                selectedLabel = state.circuitCode.orEmpty(),
                options = codes.map { it.code to (it.code + it.description.takeIf { d -> d.isNotBlank() }?.let { d -> " — $d" }.orEmpty()) },
                onSelected = { if (!readOnly) viewModel.onCircuitCode(it) },
            )
            if (codes.isEmpty() && !readOnly) {
                Text(
                    "No available Circuit Code. Every active code already has a Circuit Overseer — add one under Circuit Codes.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
            }

            Text("Assigned Congregations *", style = MaterialTheme.typography.titleSmall)
            if (congregations.isEmpty()) Text("No congregations exist yet.", style = MaterialTheme.typography.bodySmall)
            congregations.forEach { option ->
                val taken = option.takenBy != null
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(
                        checked = option.congregation.id in state.selectedCongregationIds,
                        onCheckedChange = { viewModel.onCongregationToggled(option.congregation.id, it) },
                        enabled = !taken && !readOnly,
                    )
                    Column {
                        Text(option.congregation.name, color = if (taken) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        if (taken) Text("Assigned to ${option.takenBy}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (state.isEdit) {
                HorizontalDivider()
                Text("Status", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccountStatus.entries.forEach { s ->
                        FilterChip(selected = state.status == s, onClick = { if (!readOnly) viewModel.onStatus(s) }, label = { Text(s.name) })
                    }
                }
                ReadOnlyField("Date Added", formatRecordTimestamp(state.createdAt))
            }

            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (!readOnly) {
                Button(
                    onClick = { viewModel.save(currentPersonId) },
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.saving) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text(if (state.isEdit) "Save Changes" else "Create Circuit Overseer")
                }
            }
        }
    }

    if (showPasswordInfo) {
        AlertDialog(
            onDismissRequest = { showPasswordInfo = false },
            title = { Text("Change Password") },
            text = {
                Text(
                    "The existing password is kept unchanged. GoPreach's sign-in service only lets an account change its own " +
                        "password — it cannot be set for someone else from this app. Ask ${state.firstName.ifBlank { "the Circuit Overseer" }} " +
                        "to change it from Account Settings → Change Password after signing in.",
                )
            },
            confirmButton = { TextButton(onClick = { showPasswordInfo = false }) { Text("Got It") } },
        )
    }
}

/** Shown once, right after creation: the temporary sign-in for the new overseer (first sign-in forces a change). */
@Composable
private fun NewOverseerCredentials(credentials: com.emfitsolutions.gopreach.data.repository.TempCredentials, onDone: () -> Unit) {
    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Circuit Overseer created", style = MaterialTheme.typography.headlineSmall)
            Text("Status: Temporary Account. Give these to the Circuit Overseer — at first sign-in they must choose their own username and password, after which these stop working.")
            Text("Temporary username: ${credentials.username}", style = MaterialTheme.typography.titleMedium)
            Text("Temporary password: ${credentials.temporaryPassword}", style = MaterialTheme.typography.titleMedium)
            com.emfitsolutions.gopreach.ui.components.ShareableSetupLink(credentials.username, credentials.temporaryPassword, credentials.shareableLink)
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

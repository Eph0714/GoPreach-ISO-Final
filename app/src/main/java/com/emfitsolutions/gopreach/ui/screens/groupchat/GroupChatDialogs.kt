package com.emfitsolutions.gopreach.ui.screens.groupchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.R
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.GroupChat
import com.emfitsolutions.gopreach.ui.components.FormDialog
import com.emfitsolutions.gopreach.ui.components.requiredFieldsMessage
import androidx.compose.ui.window.DialogProperties

/**
 * "Select Congregation" dropdown — Super-Admin only (spec §5: "a real
 * dropdown only for Super-Admin"). Everyone else's congregation is fixed
 * and shown read-only instead (spec §4: "the assigned congregation should
 * be displayed as read-only... no congregation selector that allows access
 * to other congregations").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupChatCongregationPicker(congregations: List<Congregation>, selectedId: String?, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = congregations.firstOrNull { it.id == selectedId }?.name ?: ""
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.chat_select_congregation)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            congregations.forEach { congregation ->
                DropdownMenuItem(text = { Text(congregation.name) }, onClick = { onSelected(congregation.id); expanded = false })
            }
        }
    }
}

/**
 * "Include a search box to easily search participants by Full Name,
 * Publisher Name, Role" + "Select Multiple Participants" — the reusable
 * congregation-scoped picker both [CreateGroupChatDialog] and
 * [ManageParticipantsDialog] build on.
 */
@Composable
private fun ParticipantPicker(
    candidates: List<ParticipantCandidate>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(candidates, query) {
        if (query.isBlank()) candidates
        else candidates.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.publisherName.contains(query, ignoreCase = true) ||
                it.roleLabel.contains(query, ignoreCase = true)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.chat_search_participants)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.chat_selected_count, selectedIds.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (filtered.isEmpty()) {
            Text(
                stringResource(R.string.chat_no_participants_found),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            // Deliberately NOT a LazyColumn: FormDialog's own content Column
            // is already vertically scrollable (that's what keeps Save/
            // Create reachable above the keyboard — see its doc comment), and
            // nesting a second same-axis scrollable inside it let this list
            // swallow the drag gesture before it ever reached the Create/Save
            // button below, making the dialog effectively unscrollable past
            // this list on a real device ("there is no save button" — it was
            // there, just unreachable). A plain Column has no scroll gesture
            // of its own to compete for, so the *one* outer scroll always
            // reaches the button; congregation rosters here are small enough
            // (tens, not thousands) that this costs nothing in practice.
            Column(modifier = Modifier.fillMaxWidth()) {
                filtered.forEach { candidate ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = candidate.personId in selectedIds, onCheckedChange = { onToggle(candidate.personId) })
                        Column {
                            Text(candidate.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(candidate.roleLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** "The Coordinator Elder must be able to create and manage a group chat" —
 * name/description + congregation (fixed, unless Super-Admin) + participant
 * picker, all in one create step. */
@Composable
fun CreateGroupChatDialog(
    fixedCongregationId: String?,
    congregations: List<Congregation>,
    currentPersonId: String,
    viewModel: GroupChatViewModel,
    onDismiss: () -> Unit,
    onCreated: (GroupChat) -> Unit,
) {
    var groupName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    // Bug fix — same "Congregation/Group is required" even after picking one,
    // confirmed root-caused (and fixed) in ManageGroupsScreen's GroupDialog:
    // `rememberSaveable` (immune to a config change wiping a plain `remember`)
    // and re-deriving the resolved id fresh inside submit() rather than
    // trusting a val computed here — a real, confirmed-on-device Compose
    // staleness where that kind of pre-computed val can go stale inside a
    // local closure even though the live picked-id state stays correct.
    var pickedCongregationId by rememberSaveable { mutableStateOf(fixedCongregationId) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val congregationId = fixedCongregationId ?: pickedCongregationId

    val candidatesFlow = remember(congregationId) { viewModel.candidatesFor(congregationId) }
    val candidates by candidatesFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    fun submit() {
        val resolvedCongregationId = fixedCongregationId ?: pickedCongregationId
        val message = requiredFieldsMessage(
            "Group Chat Name" to groupName.isNotBlank(),
            "Congregation" to (resolvedCongregationId != null),
        )
        if (message != null) {
            errorMessage = message
            return
        }
        viewModel.createGroupChat(
            congregationId = resolvedCongregationId!!,
            groupName = groupName.trim(),
            description = description.trim(),
            participantIds = selectedIds.toList(),
            createdByPersonId = currentPersonId,
            onCreated = onCreated,
        )
    }

    FormDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.chat_new_group_chat),
        onConfirm = ::submit,
        confirmLabel = stringResource(R.string.chat_create),
        errorMessage = errorMessage,
        maxContentHeight = 560.dp,
        hasUnsavedChanges = groupName.isNotBlank() || description.isNotBlank() ||
            pickedCongregationId != fixedCongregationId || selectedIds.isNotEmpty(),
    ) {
        if (fixedCongregationId == null) {
            GroupChatCongregationPicker(congregations = congregations, selectedId = pickedCongregationId, onSelected = { pickedCongregationId = it; selectedIds = emptySet() })
        }
        OutlinedTextField(
            value = groupName,
            onValueChange = { groupName = it },
            label = { Text(stringResource(R.string.chat_group_chat_name)) },
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text(stringResource(R.string.chat_description_optional)) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        if (congregationId != null) {
            Text(stringResource(R.string.chat_participants_label), style = MaterialTheme.typography.labelLarge)
            ParticipantPicker(
                candidates = candidates,
                selectedIds = selectedIds,
                onToggle = { id -> selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id },
            )
        }
    }
}

/** Group Chat's own "Settings" — rename/describe + add/remove participants,
 * still congregation-locked to the chat's own [GroupChat.congregationId]
 * (spec §3: a Coordinator Elder/Admin may never move a chat to, or add
 * participants from, another congregation).
 *
 * Also carries the "Delete Group Chat" danger action. This dialog is only
 * ever shown to someone [canManageGroupChats] already gated (Coordinator
 * Elder/Admin/Super-Admin — see [com.emfitsolutions.gopreach.ui.screens
 * .groupchat.GroupChatScreen]'s `canManageSettings`), so no extra role check
 * is needed here in the UI; the real, congregation-scoped authority check
 * still happens server-side in firestore.rules' `canManageGroupChatsFor` (a
 * Coordinator Elder/Admin may only delete a chat in their *own* active
 * congregation; only the Super-Admin may delete across all of them). */
@Composable
fun ManageParticipantsDialog(
    chat: GroupChat,
    currentPersonId: String,
    viewModel: GroupChatViewModel,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit = {},
) {
    var groupName by remember(chat.id) { mutableStateOf(chat.groupName) }
    var description by remember(chat.id) { mutableStateOf(chat.description) }
    var selectedIds by remember(chat.id) { mutableStateOf(chat.participantIds.toSet()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val candidatesFlow = remember(chat.congregationId) { viewModel.candidatesFor(chat.congregationId) }
    val candidates by candidatesFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    fun submit() {
        val message = requiredFieldsMessage("Group Chat Name" to groupName.isNotBlank())
        if (message != null) {
            errorMessage = message
            return
        }
        viewModel.updateGroupChat(chat.id, groupName.trim(), description.trim())
        viewModel.updateParticipants(chat.id, chat.congregationId, selectedIds.toList(), currentPersonId)
        onDismiss()
    }

    FormDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.chat_settings_title),
        onConfirm = ::submit,
        confirmLabel = stringResource(R.string.action_save),
        errorMessage = errorMessage,
        hasUnsavedChanges = groupName != chat.groupName || description != chat.description ||
            selectedIds != chat.participantIds.toSet(),
        maxContentHeight = 560.dp,
    ) {
        OutlinedTextField(
            value = groupName,
            onValueChange = { groupName = it },
            label = { Text(stringResource(R.string.chat_group_chat_name)) },
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text(stringResource(R.string.chat_description_optional)) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.chat_participants_label), style = MaterialTheme.typography.labelLarge)
        ParticipantPicker(
            candidates = candidates,
            selectedIds = selectedIds,
            onToggle = { id -> selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id },
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            TextButton(
                onClick = { showDeleteConfirm = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.chat_delete_group_chat)) }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.chat_delete_group_chat_title)) },
            text = { Text(stringResource(R.string.chat_delete_group_chat_message, chat.groupName)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.deleteGroupChat(chat, currentPersonId) {
                        onDismiss()
                        onDeleted()
                    }
                }) { Text(stringResource(R.string.chat_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

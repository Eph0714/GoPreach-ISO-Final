package com.emfitsolutions.gopreach.ui.screens.contactrecord

import android.content.ActivityNotFoundException
import com.emfitsolutions.gopreach.ui.components.RecordFound
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import kotlinx.coroutines.delay

/**
 * "Contact Record" module (visible to Super-Admin, Admin, Coordinator Elder
 * — "Congregation Elder" in this module's own naming — Service Overseer, and
 * Regular Elder; see GoPreachNavGraph's CONTACT_RECORD composable) — one
 * consolidated, read-only directory of every Publisher, Interested Person
 * (Searching/Return Visit/Bible Study), Coordinator Elder, Service Overseer,
 * Regular Elder, and Ministerial Servant's contact details, so finding
 * someone's number/address no longer means checking five separate Manage
 * screens one at a time. Congregation-scoped the same way every other Manage
 * screen here is (`visibleCongregationId == null` for Super-Admin's "all
 * congregations") — enforced in [ContactRecordViewModel.rowsFor]'s own query,
 * not just by this screen choosing what to display.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactRecordScreen(
    visibleCongregationId: String?,
    onBack: () -> Unit,
    viewModel: ContactRecordViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val showToast = rememberActionToast()
    val isSuperAdmin = visibleCongregationId == null
    var congregationFilter by com.emfitsolutions.gopreach.ui.components.rememberCongregationContext("contact_records")
    val needsCongregation = isSuperAdmin && congregationFilter == null
    val rowsFlow = remember(visibleCongregationId, congregationFilter) {
        if (needsCongregation) kotlinx.coroutines.flow.flowOf(emptyList()) else viewModel.rowsFor(visibleCongregationId ?: congregationFilter)
    }
    val rows by rowsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // "Add an additional Congregation filter" — Super-Admin only; every
    // other role's rows are already fixed to their own single congregation
    // upstream (see [visibleCongregationId] itself), so there's nothing for
    // that filter to do for them — per spec §7, simply not shown.
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()

    var roleFilter by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    // "Debounce text search... do not repeatedly reload the entire contact
    // database for every keystroke" — the data itself is already a live,
    // locally-held Flow (no per-keystroke network query to begin with), but
    // [filteredRows] below still re-filters the whole list on every change;
    // debouncing keeps that re-filter from running on every single
    // keystroke for a large congregation's directory.
    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        delay(250)
        debouncedQuery = searchQuery
    }

    val filteredRows = remember(rows, roleFilter, congregationFilter, debouncedQuery) {
        val query = debouncedQuery.trim()
        rows
            .filter { roleFilter == null || roleFilter in it.sourceLabels }
            .filter { congregationFilter == null || it.congregationId == congregationFilter }
            .filter { row ->
                query.isBlank() ||
                    row.name.contains(query, ignoreCase = true) ||
                    row.contact.contains(query, ignoreCase = true) ||
                    row.congregationName.contains(query, ignoreCase = true) ||
                    row.sourceLabels.any { it.contains(query, ignoreCase = true) }
            }
    }

    var selectedRow by remember { mutableStateOf<ContactRow?>(null) }

    fun callContact(phone: String) {
        if (!isValidPhoneNumber(phone)) {
            showToast("No phone number is available for this contact.")
            return
        }
        try {
            context.startActivity(dialIntent(phone))
        } catch (_: ActivityNotFoundException) {
            showToast("No app available to make a call on this device.")
        }
    }

    fun messageContact(phone: String) {
        if (!isValidPhoneNumber(phone)) {
            showToast("No phone number is available for this contact.")
            return
        }
        try {
            context.startActivity(smsIntent(phone))
        } catch (_: ActivityNotFoundException) {
            showToast("No app available to send a message on this device.")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contact Record") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search contacts") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (isSuperAdmin) {
                ContactCongregationDropdown(
                    congregations = congregations,
                    selectedId = congregationFilter,
                    onSelected = { congregationFilter = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            ContactRoleDropdown(
                selected = roleFilter,
                onSelected = { roleFilter = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )

            Text(
                "${filteredRows.size} of ${rows.size} contact${if (rows.size == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (needsCongregation) {
                com.emfitsolutions.gopreach.ui.components.SelectCongregationPrompt()
            } else if (filteredRows.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RecordFound(0)
                    Text(
                        // "No contact records found." / "No contacts match
                        // your search." — spec §14's own two distinct
                        // empty-state messages.
                        if (rows.isEmpty()) "No contact records found." else "No contacts match your search.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { RecordFound(filteredRows.size) }
                    items(filteredRows, key = { "${it.name}|${it.congregationName}|${it.sourceLabels}" }) { row ->
                        ContactRowCard(
                            row = row,
                            onClick = { selectedRow = row },
                            onCall = { callContact(row.contact) },
                            onMessage = { messageContact(row.contact) },
                        )
                    }
                }
            }
        }
    }

    selectedRow?.let { row ->
        ContactDetailsSheet(
            row = row,
            onDismiss = { selectedRow = null },
            onCall = { callContact(row.contact) },
            onMessage = { messageContact(row.contact) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactCongregationDropdown(
    congregations: List<com.emfitsolutions.gopreach.data.model.Congregation>,
    selectedId: String?,
    onSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = congregations.firstOrNull { it.id == selectedId }?.name ?: "Select Congregation"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Congregation") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            congregations.forEach { c ->
                DropdownMenuItem(text = { Text(c.name) }, onClick = { onSelected(c.id); expanded = false })
            }
        }
    }
}

/** "Add the exact role filters" — All + the six named categories, in the
 * exact order given. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactRoleDropdown(
    selected: String?,
    onSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected ?: "All",
            onValueChange = {},
            readOnly = true,
            label = { Text("Role") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("All") }, onClick = { onSelected(null); expanded = false })
            CONTACT_ROLE_FILTERS.forEach { label ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onSelected(label); expanded = false })
            }
        }
    }
}

@Composable
private fun ContactRowCard(
    row: ContactRow,
    onClick: () -> Unit,
    onCall: () -> Unit,
    onMessage: () -> Unit,
) {
    val hasPhone = isValidPhoneNumber(row.contact)
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ContactAvatar(row.profileImageUrl, size = 40.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(row.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        row.sourceLabels.sorted().joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                row.congregationName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.contact.isNotBlank()) {
                Text("📱 ${row.contact}", style = MaterialTheme.typography.bodySmall)
            }
            if (row.address.isNotBlank()) {
                Text("${row.addressLabel}: ${row.address}", style = MaterialTheme.typography.bodySmall)
            }

            // "Every valid contact must have Call and Message actions" —
            // shown for every row (an Interested Person's blank [contact]
            // included), just visually muted and routed to the "no phone
            // number available" message on tap instead of a silent no-op,
            // rather than a fully disabled control nobody can query.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                val actionColor = if (hasPhone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                OutlinedButton(onClick = onCall) {
                    Icon(Icons.Rounded.Call, contentDescription = null, tint = actionColor, modifier = Modifier.size(18.dp))
                    Text("Call", color = actionColor, modifier = Modifier.padding(start = 6.dp))
                }
                Row(modifier = Modifier.padding(start = 12.dp)) {
                    OutlinedButton(onClick = onMessage) {
                        Icon(Icons.Rounded.Sms, contentDescription = null, tint = actionColor, modifier = Modifier.size(18.dp))
                        Text("Message", color = actionColor, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

/** "When the user opens a Contact Record, display the complete authorized
 * contact information" plus prominent Call/Message — a bottom sheet rather
 * than a separate navigation destination, matching the Territory Map's own
 * marker-details pattern already established in this app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactDetailsSheet(
    row: ContactRow,
    onDismiss: () -> Unit,
    onCall: () -> Unit,
    onMessage: () -> Unit,
) {
    val hasPhone = isValidPhoneNumber(row.contact)
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ContactAvatar(row.profileImageUrl, size = 56.dp)
                Column {
                    Text(row.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        row.sourceLabels.sorted().joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text("Congregation: ${row.congregationName}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
            if (row.contact.isNotBlank()) {
                Text("Mobile: ${row.contact}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            if (row.address.isNotBlank()) {
                Text("${row.addressLabel}: ${row.address}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            if (!hasPhone) {
                Text(
                    "No phone number is available for this contact.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCall, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Call", modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = onMessage, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Sms, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Message", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun ContactAvatar(profileImageUrl: String?, size: androidx.compose.ui.unit.Dp) {
    if (profileImageUrl != null) {
        AsyncImage(
            model = profileImageUrl,
            contentDescription = "Profile photo",
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
        )
    } else {
        Icon(
            Icons.Rounded.AccountCircle,
            contentDescription = "Profile photo",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(size),
        )
    }
}

/** "Validate that it is not empty. Handle malformed numbers gracefully" —
 * permissive on purpose (this is a directory of numbers Publishers/Elders
 * typed in themselves in whatever local format they use, not a field this
 * app validates at entry); a number counts as usable as long as it has at
 * least one digit in it. [dialIntent]/[smsIntent] pass the stored string
 * through completely unchanged either way — "preserve the stored number
 * format," never reformatted. */
private fun isValidPhoneNumber(raw: String): Boolean = raw.trim().any { it.isDigit() }

/** ACTION_DIAL (not ACTION_CALL) — opens the device's own dialer pre-filled
 * with the number; the user still has to press the call button themselves,
 * and no CALL_PHONE permission is ever needed for this. */
private fun dialIntent(phone: String): Intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone.trim())}"))

/** ACTION_SENDTO with an smsto: URI — opens the device's own messaging app
 * with the recipient pre-filled and the draft empty; never sends anything
 * on its own, and needs no SEND_SMS permission. */
private fun smsIntent(phone: String): Intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(phone.trim())}"))

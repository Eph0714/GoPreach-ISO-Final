package com.emfitsolutions.gopreach.ui.screens.publishers

import androidx.compose.foundation.layout.Arrangement
import com.emfitsolutions.gopreach.ui.components.RecordFound
import androidx.compose.foundation.layout.Box
import com.emfitsolutions.gopreach.data.model.displayName
import com.emfitsolutions.gopreach.ui.components.NameFieldsInOrder
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.AccountStatus
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Gender
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.ui.components.DeleteChoiceDialog
import com.emfitsolutions.gopreach.ui.components.EditSectionHeader
import com.emfitsolutions.gopreach.ui.components.FormDialog
import com.emfitsolutions.gopreach.ui.components.PublisherFormFields
import com.emfitsolutions.gopreach.ui.components.PublisherFormState
import com.emfitsolutions.gopreach.ui.components.ReadOnlyField
import kotlinx.coroutines.launch
import com.emfitsolutions.gopreach.ui.components.TempCredentialLookupDialog
import com.emfitsolutions.gopreach.ui.components.formatRecordTimestamp
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.components.requiredFieldsMessage

/** Spec §3/§5.1 — Manage Publishers (all categories).
 * [canPermanentlyDelete] is Super-Admin-only, per the "Admin Record Deletion"
 * spec's scoping decision (see BUILD_PLAN.md). "Move to Inactive" for a
 * Publisher is the pre-existing [PublisherCategory.REMOVED_PUBLISHER]
 * recategorization — Removed publishers are hidden unless "Show Inactive" is on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagePublishersScreen(
    currentPersonId: String,
    visibleCongregationId: String?,
    canPermanentlyDelete: Boolean,
    /** A restricted user with `VIEW_PUBLISHERS` but not `MANAGE_PUBLISHERS` —
     * hides Add/Edit/Delete/Reactivate and the inline Category picker, which
     * otherwise changes data with no separate "edit" gesture to gate behind. */
    readOnly: Boolean = false,
    onBack: () -> Unit,
    onAddNew: () -> Unit,
    viewModel: ManagePublishersViewModel = koinViewModel(),
) {
    // Super-Admin only (visibleCongregationId == null from the caller) — an
    // Admin/Coordinator Elder is already scoped to their own single
    // congregation upstream, so there's nothing for them to filter.
    val congregations by viewModel.congregations.collectAsStateWithLifecycle(initialValue = emptyList())
    // A Super-Admin must pick a congregation before any publisher is shown; it stays the module's context (and is what
    // Add Publisher starts in) until they change it. Everyone else is fixed to their own congregation.
    val selectedCongregationId by viewModel.selectedCongregationId.collectAsStateWithLifecycle()
    // Opened from a Quick Access card (Regular Pioneers, ...): start filtered to that category until cleared.
    var categoryFilter by remember { mutableStateOf(PublisherListPreset.take()) }
    val effectiveCongregationId = visibleCongregationId ?: selectedCongregationId
    if (effectiveCongregationId == null) {
        SelectCongregationGate(congregations = congregations, onContinue = viewModel::selectCongregation, onBack = onBack)
        return
    }
    val congregationName = congregations.firstOrNull { it.id == effectiveCongregationId }?.name
    // Search + filter. Changing the congregation clears the text and puts the filter back to "All" (they are keyed on it).
    var filterMode by remember(effectiveCongregationId) { mutableStateOf(PublisherFilterMode.ALL) }
    var searchQuery by remember(effectiveCongregationId) { mutableStateOf("") }
    var groupFilterId by remember(effectiveCongregationId) { mutableStateOf<String?>(null) }
    var statusFilter by remember(effectiveCongregationId) { mutableStateOf<AccountStatus?>(null) }
    val groupChoices by remember(effectiveCongregationId) { viewModel.groupsFor(effectiveCongregationId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val rowsFlow = remember(effectiveCongregationId) { viewModel.rowsFor(effectiveCongregationId) }
    val allRows by rowsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var showInactive by remember { mutableStateOf(false) }
    val shown = allRows.filter { showInactive || it.category != PublisherCategory.REMOVED_PUBLISHER }
        .filter { categoryFilter == null || it.category == categoryFilter }
    // The same filtered rows drive the list AND the count above it. They are already limited to this congregation.
    val filter = PublisherListFilter(filterMode, searchQuery, groupFilterId, statusFilter)
    val rows = filter.apply(shown)
    val countText = filter.countLabel(rows.size, groupChoices.firstOrNull { it.id == groupFilterId }?.name ?: if (groupFilterId == NO_GROUP_FILTER) "Unassigned" else null)
    val filtering = filter.query.isNotBlank() || (filterMode == PublisherFilterMode.GROUP && groupFilterId != null) || (filterMode == PublisherFilterMode.STATUS && statusFilter != null)
    var lookupTarget by remember { mutableStateOf<Person?>(null) }
    var pendingEdit by remember { mutableStateOf<PublisherRow?>(null) }
    var pendingDelete by remember { mutableStateOf<PublisherRow?>(null) }
    val showToast = rememberActionToast()
    var permanentDeleteImpact by remember { mutableStateOf<ManagePublishersViewModel.DeleteImpact?>(null) }
    var permanentDeleteChecked by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (congregationName != null) "Publishers – $congregationName" else "Publishers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            // "Publisher UI – Make the Main Button Larger and More
            // Professional" spec — an ExtendedFloatingActionButton (icon +
            // short label) rather than the plain icon-only FAB every other
            // Manage screen still uses: larger touch target, clearer intent
            // at a glance, still Material3-standard corner radius/elevation/
            // ripple/typography, so it reads as "more polished," not "bigger
            // for its own sake" (explicitly not wanted per the spec).
            if (!readOnly) {
                ExtendedFloatingActionButton(
                    onClick = onAddNew,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("ADD PUBLISHER") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp, pressedElevation = 8.dp),
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (visibleCongregationId == null) {
                // Switching congregation refreshes the list, filters, counts and clears the search (it is keyed on the id).
                CongregationSwitcher(
                    congregations = congregations,
                    selectedId = effectiveCongregationId,
                    onSelected = viewModel::selectCongregation,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The input follows the filter: text for All / By Name, the group list for By Group, the status list for By Status.
                Box(modifier = Modifier.weight(1f)) {
                    when (filterMode) {
                        PublisherFilterMode.GROUP -> FilterChoiceDropdown(
                            label = "Field Service Group",
                            selectedText = when (groupFilterId) {
                                null -> "All groups"
                                NO_GROUP_FILTER -> "Unassigned"
                                else -> groupChoices.firstOrNull { it.id == groupFilterId }?.name.orEmpty()
                            },
                            options = listOf<Pair<String?, String>>(null to "All groups") + groupChoices.map { it.id to it.name } + (NO_GROUP_FILTER to "Unassigned"),
                            onSelected = { groupFilterId = it },
                        )
                        PublisherFilterMode.STATUS -> FilterChoiceDropdown(
                            label = "Status",
                            selectedText = statusFilter?.let { PublisherListFilter.statusLabel(it) } ?: "All statuses",
                            options = listOf<Pair<AccountStatus?, String>>(null to "All statuses") + AccountStatus.entries.map { it to PublisherListFilter.statusLabel(it) },
                            onSelected = { statusFilter = it },
                        )
                        else -> OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search Publisher...") },
                            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") }
                                }
                            },
                            singleLine = true,
                            visualTransformation = VisualTransformation.None,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Box(modifier = Modifier.width(132.dp)) {
                    FilterChoiceDropdown(
                        label = "Filter",
                        selectedText = filterMode.label,
                        options = PublisherFilterMode.entries.map { it to it.label },
                        onSelected = {
                            filterMode = it
                            // Each mode starts clean so a leftover search/choice from another mode never hides rows.
                            searchQuery = ""; groupFilterId = null; statusFilter = null
                        },
                    )
                }
            }
            categoryFilter?.let { category ->
                androidx.compose.material3.InputChip(
                    selected = true,
                    onClick = { categoryFilter = null },
                    label = { Text(category.name.lowercase().split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } } + " only") },
                    trailingIcon = { Icon(androidx.compose.material.icons.Icons.Rounded.Close, contentDescription = "Clear category filter") },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            RecordFound(rows.size, Modifier.padding(horizontal = 16.dp))
            // Print / Excel / PDF all use the same rows as the list below (current search, filter, group, status).
            val context = androidx.compose.ui.platform.LocalContext.current
            var exportKind by remember { mutableStateOf<String?>(null) }
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(
                    Triple("Print", Icons.Rounded.Print, "print"),
                    Triple("Export Excel", Icons.Rounded.TableChart, "excel"),
                    Triple("Export PDF", Icons.Rounded.PictureAsPdf, "pdf"),
                ).forEach { (label, icon, kind) ->
                    androidx.compose.material3.OutlinedButton(
                        onClick = { exportKind = kind },
                        enabled = rows.isNotEmpty() || allRows.isNotEmpty(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(34.dp),
                    ) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(label, fontSize = 12.sp)
                    }
                }
            }
            exportKind?.let { kind ->
                val filterText = listOfNotNull(
                    searchQuery.takeIf { it.isNotBlank() }?.let { "Search: $it" },
                    if (filterMode == PublisherFilterMode.GROUP && groupFilterId != null) "Group: " + (groupChoices.firstOrNull { it.id == groupFilterId }?.name ?: "Unassigned") else null,
                    if (filterMode == PublisherFilterMode.STATUS && statusFilter != null) "Status: " + PublisherListFilter.statusLabel(statusFilter!!) else null,
                    categoryFilter?.let { "Category: " + it.displayName },
                ).joinToString("; ")
                PublisherExportDialog(
                    kind = kind,
                    filteredCount = rows.size,
                    allCount = allRows.size,
                    onDismiss = { exportKind = null },
                    onConfirm = { all, format ->
                        val chosen = if (all) allRows else rows
                        val name = congregationName ?: "Congregation"
                        val text = if (all) "All records" else filterText
                        when (kind) {
                            "print" -> com.emfitsolutions.gopreach.data.export.PublisherRecordsExporter.print(context, chosen, name, text, format)
                            "pdf" -> com.emfitsolutions.gopreach.data.export.PublisherRecordsExporter.sharePdf(context, chosen, name, text, format)
                            else -> com.emfitsolutions.gopreach.data.export.PublisherRecordsExporter.shareExcel(context, chosen, name)
                        }
                        exportKind = null
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = showInactive, onCheckedChange = { showInactive = it })
                Text("Show Inactive (Removed)")
            }
        if (rows.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (filtering) {
                    // A normal zero-result search is not an error.
                    Text("No Publisher Found", style = MaterialTheme.typography.titleMedium)
                    Text("No publisher matches the selected search/filter criteria.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("No publishers enrolled yet. Tap + to enroll one.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.person.id }) { row ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(row.person.fullName, style = MaterialTheme.typography.titleMedium)
                                Row {
                                    if (row.person.isTemporaryCredential && !readOnly) {
                                        IconButton(onClick = { lookupTarget = row.person }) {
                                            Icon(
                                                Icons.Rounded.Key,
                                                contentDescription = "View temporary sign-in",
                                                tint = MaterialTheme.colorScheme.secondary,
                                            )
                                        }
                                    }
                                    if (!readOnly) {
                                        IconButton(onClick = { pendingEdit = row }) {
                                            Icon(Icons.Rounded.Edit, contentDescription = "Edit")
                                        }
                                        if (row.category != PublisherCategory.REMOVED_PUBLISHER) {
                                            IconButton(onClick = { pendingDelete = row }) {
                                                Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                                            }
                                        } else {
                                            IconButton(
                                                onClick = {
                                                    viewModel.changeCategory(row, PublisherCategory.REGULAR_PUBLISHER, currentPersonId)
                                                    showToast("\"${row.person.fullName}\" reactivated.")
                                                },
                                            ) {
                                                Icon(Icons.Rounded.RestoreFromTrash, contentDescription = "Reactivate")
                                            }
                                        }
                                    }
                                }
                            }
                            Text("Group: ${row.groupName}", style = MaterialTheme.typography.bodySmall)
                            Text("Contact: ${row.person.contact}", style = MaterialTheme.typography.bodySmall)
                            row.person.remarks?.takeIf { it.isNotBlank() }?.let { Text("Remarks: $it", style = MaterialTheme.typography.bodySmall) }
                            if (row.possibleDuplicateOf != null) {
                                // "Check if there are duplicate names,
                                // evaluate it if they are the same person" —
                                // a heads-up only; nothing here merges or
                                // deletes anything automatically. The admin
                                // reviews both records (Edit/Delete icons
                                // above) and decides.
                                Text(
                                    "⚠ Possible duplicate of \"${row.possibleDuplicateOf}\" — review both records.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (readOnly) {
                                ReadOnlyField("Category", row.category.displayName)
                            } else {
                                CategoryDropdown(
                                    selected = row.category,
                                    onSelected = { newCategory ->
                                        viewModel.changeCategory(row, newCategory, currentPersonId)
                                        showToast("Publisher Status Category updated successfully.")
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

    lookupTarget?.let { person ->
        TempCredentialLookupDialog(person = person, onDismiss = { lookupTarget = null })
    }

    val toEdit = pendingEdit
    if (toEdit != null) {
        EditPublisherDialog(
            row = toEdit,
            currentPersonId = currentPersonId,
            viewModel = viewModel,
            // Only a Super-Admin (not tied to one congregation) may move a publisher to another congregation.
            canChangeCongregation = visibleCongregationId == null,
            congregations = congregations,
            onDismiss = { pendingEdit = null },
        )
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        LaunchedEffect(toDelete.person.id) {
            permanentDeleteImpact = viewModel.permanentDeleteImpact(toDelete.person.id)
            permanentDeleteChecked = true
        }
        if (permanentDeleteChecked) {
            DeleteChoiceDialog(
                recordLabel = toDelete.person.fullName,
                canPermanentlyDelete = canPermanentlyDelete,
                permanentDeleteImpactSummary = permanentDeleteImpact?.message(),
                permanentWarningTitle = permanentDeleteImpact?.takeIf { it.relatedRecords > 0 }?.let { "⚠️ WARNING: This Publisher has assigned records." },
                permanentConfirmLabel = permanentDeleteImpact?.takeIf { it.relatedRecords > 0 }?.let { "Yes, Delete Publisher" },
                onDismiss = { pendingDelete = null; permanentDeleteChecked = false },
                onMoveToInactive = { viewModel.changeCategory(toDelete, PublisherCategory.REMOVED_PUBLISHER, currentPersonId) },
                onDeletePermanently = { viewModel.permanentlyDelete(toDelete, currentPersonId) },
            )
        }
    }
}

/** Shows the complete stored Publisher record when editing — every Person
 * field, plus Category and Group (spec: "enable the user to edit all
 * entities like the Groups and all data"), not just Address/Contact. Category/
 * Group changes go through [ManagePublishersViewModel.changeCategory]/
 * [changeGroup] (they live on the [RoleAssignment], not the [Person] document)
 * while every other field saves through [ManagePublishersViewModel.updatePerson]
 * — both fire from this one Save Changes button so editing here still feels
 * like one record, not three separate saves. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditPublisherDialog(
    row: PublisherRow,
    currentPersonId: String,
    viewModel: ManagePublishersViewModel,
    canChangeCongregation: Boolean,
    congregations: List<Congregation>,
    onDismiss: () -> Unit,
) {
    // Every Publisher field in one value — the same set the Add Publisher form asks for.
    val initialForm = remember(row) { PublisherFormState.from(row.person, row.category, row.assignment.groupId, row.assignment.congregationId) }
    var form by remember(row) { mutableStateOf(initialForm) }
    var capturingLocation by remember { mutableStateOf(false) }
    var locationError by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val showToast = rememberActionToast()

    // The groups offered follow the congregation chosen in the form (they belong to it).
    val groupsFlow = remember(form.congregationId) { viewModel.groupsFor(form.congregationId) }
    var confirmCongregationMove by remember { mutableStateOf(false) }
    val groups by groupsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun captureLocation() {
        capturingLocation = true
        locationError = null
        scope.launch {
            val captured = viewModel.captureLocation()
            capturingLocation = false
            if (captured == null) {
                locationError = "Could not get a GPS fix. Make sure location is turned on and try again."
            } else {
                form = form.copy(
                    latitudeText = captured.lat.toString(),
                    longitudeText = captured.lng.toString(),
                    province = captured.province ?: form.province,
                    cityMunicipality = captured.city ?: form.cityMunicipality,
                    barangay = captured.barangay ?: form.barangay,
                )
            }
        }
    }
    val locationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) captureLocation() }

    fun submit(moveConfirmed: Boolean = false) {
        val message = requiredFieldsMessage(
            "First Name" to form.firstName.isNotBlank(),
            "Last Name" to form.lastName.isNotBlank(),
            "Full Address" to form.address.isNotBlank(),
            "Contact" to form.contact.isNotBlank(),
        ) ?: form.formProblem
        if (message != null) {
            errorMessage = message
            return
        }
        val newCongregationId = form.congregationId ?: row.assignment.congregationId
        val movingCongregation = newCongregationId != null && newCongregationId != row.assignment.congregationId
        // Changing someone's congregation is a deliberate act: ask first, save nothing until confirmed.
        if (movingCongregation && !moveConfirmed) {
            confirmCongregationMove = true
            return
        }
        viewModel.updatePerson(form.applyTo(row.person))
        val newCategory = form.category ?: row.category
        if (newCategory != row.category) viewModel.changeCategory(row, newCategory, currentPersonId)
        if (movingCongregation && newCongregationId != null) viewModel.changeCongregation(row, newCongregationId, form.groupId, currentPersonId)
        else if (form.groupId != row.assignment.groupId) viewModel.changeGroup(row, form.groupId, currentPersonId)
        showToast("\"${row.person.fullName}\" saved.")
        onDismiss()
    }

    if (confirmCongregationMove) {
        CongregationMoveConfirmation(
            newName = congregations.firstOrNull { it.id == form.congregationId }?.name ?: "the selected congregation",
            onConfirm = { confirmCongregationMove = false; submit(moveConfirmed = true) },
            onDismiss = { confirmCongregationMove = false },
        )
    }

    FormDialog(
        onDismissRequest = onDismiss,
        title = "Edit ${row.person.fullName}",
        onConfirm = { submit() },
        confirmLabel = "Save Changes",
        errorMessage = errorMessage,
        maxContentHeight = 560.dp,
        hasUnsavedChanges = form != initialForm,
    ) {
                EditSectionHeader("Publisher Information")
                PublisherFormFields(
                    form = form,
                    onChange = { form = it },
                    groups = groups,
                    congregations = congregations,
                    congregationEditable = canChangeCongregation,
                    allowUnassigned = true,
                    capturingLocation = capturingLocation,
                    locationError = locationError,
                    onUseCurrentLocation = {
                        if (viewModel.hasLocationPermission()) captureLocation()
                        else locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    },
                )

                EditSectionHeader("System Information")
                ReadOnlyField("Username", row.person.username)
                ReadOnlyField("Date Added", formatRecordTimestamp(row.person.createdAt))
    }
}

/** Confirms a deliberate change of a publisher's congregation (shown from [EditPublisherDialog]). */
@Composable
private fun CongregationMoveConfirmation(newName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Congregation?") },
        text = {
            Text("This publisher will be moved to $newName. Their field service group is replaced by the one chosen here (or cleared). Reports and records they already made keep the congregation they were made in.")
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Change Congregation") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Super-Admin's first step in the Publisher module: choose the congregation whose publishers to work with. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectCongregationGate(congregations: List<Congregation>, onContinue: (String) -> Unit, onBack: () -> Unit) {
    var pickedId by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Publishers") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Select Congregation", style = MaterialTheme.typography.headlineSmall)
            Text("Choose the congregation whose publishers you want to view and manage.", style = MaterialTheme.typography.bodyMedium)
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = congregations.firstOrNull { it.id == pickedId }?.name.orEmpty(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Congregation") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    congregations.forEach { c ->
                        DropdownMenuItem(text = { Text(c.name) }, onClick = { pickedId = c.id; expanded = false })
                    }
                }
            }
            Button(onClick = { pickedId?.let(onContinue) }, enabled = pickedId != null, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
        }
    }
}

/** Switch the module's congregation (Super-Admin). Same list as the gate; no "All" — a congregation is always chosen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CongregationSwitcher(
    congregations: List<Congregation>,
    selectedId: String?,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = congregations.firstOrNull { it.id == selectedId }?.name.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Congregation (tap to change)") },
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

/** A small labelled dropdown over (key, label) options — the Publisher list's filter, group and status pickers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> FilterChoiceDropdown(label: String, selectedText: String, options: List<Pair<T, String>>, onSelected: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelected(key); expanded = false })
            }
        }
    }
}

/** "In enrolling publisher record for superadmin, show a dropdown to select
 * a congregation as filter" — Super-Admin-only (see the `visibleCongregationId
 * == null` gate at the call site); narrows the list to one congregation,
 * "All Congregations" (the default) showing every one at once same as before. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CongregationFilterDropdown(
    congregations: List<Congregation>,
    selectedId: String?,
    onSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = congregations.firstOrNull { it.id == selectedId }?.name ?: "All Congregations"
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
            DropdownMenuItem(text = { Text("All Congregations") }, onClick = { onSelected(null); expanded = false })
            congregations.forEach { c ->
                DropdownMenuItem(text = { Text(c.name) }, onClick = { onSelected(c.id); expanded = false })
            }
        }
    }
}

/** Every Group in this publisher's own congregation, plus "Unassigned"
 * (`null`) — the same clear-back-out option a fresh enrollment leaves them
 * in before an admin ever places them into a Group. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupDropdown(
    groups: List<Group>,
    selectedGroupId: String?,
    onSelected: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = groups.firstOrNull { it.id == selectedGroupId }?.name ?: "Unassigned"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Field Service Group") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Unassigned") }, onClick = { onSelected(null); expanded = false })
            groups.forEach { g ->
                DropdownMenuItem(text = { Text(g.name) }, onClick = { onSelected(g.id); expanded = false })
            }
        }
    }
}

/** "Allow the user to Edit the Publishers Status" — [Person.accountStatus]
 * (ACTIVE/INACTIVE/SUSPENDED), the account's sign-in eligibility. Same
 * three-way choice [ManageUsersScreen] already exposes for restricted
 * users, offered here as a plain dropdown to match this dialog's other
 * Assignment-section fields rather than that screen's icon-menu shape. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountStatusDropdown(selected: AccountStatus, onSelected: (AccountStatus) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.name,
            onValueChange = {},
            readOnly = true,
            label = { Text("Status") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AccountStatus.entries.forEach { status ->
                DropdownMenuItem(
                    text = { Text(status.name) },
                    onClick = {
                        onSelected(status)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdown(selected: PublisherCategory, onSelected: (PublisherCategory) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Category") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PublisherCategory.entries.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category.displayName) },
                    onClick = {
                        onSelected(category)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Asks what to print/export: the records currently listed (with the active search and filters) or all of them, and — for print and PDF — table or detailed list. */
@Composable
private fun PublisherExportDialog(
    kind: String,
    filteredCount: Int,
    allCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (all: Boolean, format: com.emfitsolutions.gopreach.data.export.PublisherPrintFormat) -> Unit,
) {
    var all by remember { mutableStateOf(false) }
    var format by remember { mutableStateOf(com.emfitsolutions.gopreach.data.export.PublisherPrintFormat.TABLE) }
    val count = if (all) allCount else filteredCount
    val verb = when (kind) { "print" -> "Printing"; "pdf" -> "Exporting"; else -> "Exporting" }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(when (kind) { "print" -> "Print Publisher Records"; "pdf" -> "Export to PDF"; else -> "Export to Excel" }) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Records", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = !all, onClick = { all = false })
                    Text("Current list ($filteredCount)", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = all, onClick = { all = true })
                    Text("All records ($allCount)", style = MaterialTheme.typography.bodyMedium)
                }
                if (kind != "excel") {
                    Text("Print Format", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                    com.emfitsolutions.gopreach.data.export.PublisherPrintFormat.entries.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(selected = format == f, onClick = { format = f })
                            Text(f.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Text("$verb $count Publisher Record${if (count == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(enabled = count > 0, onClick = { onConfirm(all, format) }) { Text(when (kind) { "print" -> "Print"; "pdf" -> "Export PDF"; else -> "Export Excel" }) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

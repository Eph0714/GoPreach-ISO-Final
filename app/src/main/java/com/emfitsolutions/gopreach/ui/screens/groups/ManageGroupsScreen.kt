package com.emfitsolutions.gopreach.ui.screens.groups

import androidx.compose.foundation.verticalScroll
import com.emfitsolutions.gopreach.ui.components.RecordFound
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.RegularElderRole
import com.emfitsolutions.gopreach.domain.GroupAccessScope
import com.emfitsolutions.gopreach.ui.components.CongregationFilterDropdown
import com.emfitsolutions.gopreach.ui.components.SelectCongregationPrompt
import com.emfitsolutions.gopreach.ui.components.rememberCongregationContext
import com.emfitsolutions.gopreach.ui.components.DeleteChoiceDialog
import com.emfitsolutions.gopreach.ui.components.EditSectionHeader
import com.emfitsolutions.gopreach.ui.components.FormDialog
import com.emfitsolutions.gopreach.ui.components.GroupColorPalette
import com.emfitsolutions.gopreach.ui.components.ReadOnlyField
import com.emfitsolutions.gopreach.ui.components.displayLabel
import com.emfitsolutions.gopreach.ui.components.formatRecordTimestamp
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.components.requiredFieldsMessage

/** Spec: "CRUD Groups" — each Group needs exactly one Elder in each of three
 * roles (Overseer/Servant/Assistant), not the single Elder this used to allow.
 * [fixedCongregationId] scopes both the list and the create dialog for Admin/
 * Coordinator Elder; null lets a Super-Admin pick a congregation per group instead.
 * [canPermanentlyDelete] is Super-Admin-only, per the "Admin Record Deletion"
 * spec's scoping decision (see BUILD_PLAN.md). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageGroupsScreen(
    fixedCongregationId: String?,
    currentPersonId: String,
    canPermanentlyDelete: Boolean,
    /** A restricted user with `VIEW_GROUPS` but not `MANAGE_GROUPS` — hides
     * Add/Edit/Delete/Reactivate. */
    readOnly: Boolean = false,
    /** RBAC scope — a group-level user (Overseer/Servant/Assistant only) sees and edits just their own group
     * and can never add, deactivate or delete one. Null keeps the legacy behaviour (grant-based users). */
    scope: GroupAccessScope? = null,
    onBack: () -> Unit,
    viewModel: ManageGroupsViewModel = hiltViewModel(),
) {
    val canAddOrRemove = !readOnly && (scope == null || scope.canAddOrRemoveGroups)
    val congregations by viewModel.congregations.collectAsStateWithLifecycle(initialValue = emptyList())
    // "Add a filter for Congregation" (Super-Admin only).
    var congregationFilter by rememberCongregationContext("field_service_groups")
    val effectiveCongregationId = fixedCongregationId ?: congregationFilter
    val needsCongregation = fixedCongregationId == null && congregationFilter == null
    val rowsFlow = remember(effectiveCongregationId, needsCongregation, scope) { if (needsCongregation) kotlinx.coroutines.flow.flowOf(emptyList()) else viewModel.rowsFor(effectiveCongregationId, scope) }
    val allRows by rowsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var showInactive by remember { mutableStateOf(false) }
    val rows = allRows.filter { showInactive || it.group.status == RecordStatus.ACTIVE }
    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingEdit by remember { mutableStateOf<Group?>(null) }
    var pendingDelete by remember { mutableStateOf<Group?>(null) }
    var permanentDeleteImpactSummary by remember { mutableStateOf<String?>(null) }
    var permanentDeleteChecked by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Field Service Groups") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            if (canAddOrRemove) {
                FloatingActionButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = "New Field Service Group")
                }
            }
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
            if (fixedCongregationId == null) {
                CongregationFilterDropdown(
                    congregations = congregations,
                    selectedCongregationId = congregationFilter,
                    onSelected = { congregationFilter = it },
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
                Text("No field service groups yet. Tap + to add one.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { RecordFound(rows.size) }
                items(rows, key = { it.group.id }) { row ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // "Same Group = Same Color" — the same
                                    // swatch the Territory Map/Group Report
                                    // use for this Group (GroupColorPalette
                                    // is the shared source both read).
                                    Box(
                                        modifier = Modifier.size(14.dp)
                                            .clip(CircleShape)
                                            .background(GroupColorPalette.parseHex(row.group.color ?: GroupColorPalette.UNASSIGNED_COLOR)),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(row.group.name, style = MaterialTheme.typography.titleMedium)
                                }
                                if (!readOnly) {
                                    Row {
                                        IconButton(onClick = { pendingEdit = row.group }) {
                                            Icon(Icons.Rounded.Edit, contentDescription = "Edit field service group")
                                        }
                                        if (!canAddOrRemove) {
                                            // group-level user: edit own group only
                                        } else if (row.group.status == RecordStatus.ACTIVE) {
                                            IconButton(onClick = { pendingDelete = row.group }) {
                                                Icon(Icons.Rounded.Delete, contentDescription = "Delete field service group")
                                            }
                                        } else {
                                            IconButton(onClick = { viewModel.setStatus(row.group, RecordStatus.ACTIVE, currentPersonId) }) {
                                                Icon(Icons.Rounded.RestoreFromTrash, contentDescription = "Reactivate")
                                            }
                                        }
                                    }
                                }
                            }
                            if (row.group.status == RecordStatus.INACTIVE) {
                                Text("Inactive", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            Text(
                                "${RegularElderRole.GROUP_OVERSEER.displayLabel()}: ${row.overseerName ?: "Unassigned"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "${RegularElderRole.GROUP_SERVANT.displayLabel()}: ${row.servantName ?: "Unassigned"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "${RegularElderRole.GROUP_ASSISTANT.displayLabel()}: ${row.assistantName ?: "Unassigned"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (row.group.regularElderPersonId != null && !row.group.isComplete) {
                                Text(
                                    "Existing assignment on file — open Edit to place them in a role.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                            if (!row.group.isComplete) {
                                val missing = row.group.missingRoles().joinToString(", ") { it.displayLabel() }
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text(
                                            "Field Service Group Assignment Incomplete",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                        Text(
                                            "Missing: $missing",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
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

    if (showCreateDialog) {
        GroupDialog(
            fixedCongregationId = fixedCongregationId,
            existingGroup = null,
            currentPersonId = currentPersonId,
            viewModel = viewModel,
            usedColors = allRows.filter { it.group.status == RecordStatus.ACTIVE }.mapNotNull { it.group.color },
            legacyFallbackColor = null,
            onDismiss = { showCreateDialog = false },
        )
    }

    val toEditGroup = pendingEdit
    if (toEditGroup != null) {
        GroupDialog(
            fixedCongregationId = fixedCongregationId,
            existingGroup = toEditGroup,
            currentPersonId = currentPersonId,
            viewModel = viewModel,
            usedColors = allRows.filter { it.group.status == RecordStatus.ACTIVE && it.group.id != toEditGroup.id }.mapNotNull { it.group.color },
            // A legacy Group (saved before Group.color existed) has no color
            // of its own yet — default the picker to whatever the Territory
            // Map is *already* showing for it (same creation-order/curated-
            // palette formula as TerritoryMapScreen.groupColorById) rather
            // than a fresh random pick, so opening Edit and saving without
            // touching the swatch can never silently change a color members
            // are already used to seeing on the map.
            legacyFallbackColor = allRows.map { it.group }.sortedBy { it.createdAt }.indexOfFirst { it.id == toEditGroup.id }
                .let { index -> if (index < 0) null else GroupColorPalette.CURATED.getOrNull(index) ?: GroupColorPalette.colorForGroupId(toEditGroup.id) },
            onDismiss = { pendingEdit = null },
        )
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        LaunchedEffect(toDelete.id) {
            permanentDeleteImpactSummary = viewModel.permanentDeleteImpactSummary(toDelete.id)
            permanentDeleteChecked = true
        }
        if (permanentDeleteChecked) {
            DeleteChoiceDialog(
                recordLabel = toDelete.name,
                canPermanentlyDelete = canPermanentlyDelete,
                permanentDeleteImpactSummary = permanentDeleteImpactSummary,
                onDismiss = { pendingDelete = null; permanentDeleteChecked = false },
                onMoveToInactive = { viewModel.setStatus(toDelete, RecordStatus.INACTIVE, currentPersonId) },
                onDeletePermanently = { viewModel.permanentlyDelete(toDelete.id, currentPersonId) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun GroupDialog(
    fixedCongregationId: String?,
    existingGroup: Group?,
    currentPersonId: String,
    viewModel: ManageGroupsViewModel,
    /** Every other active Group's own assigned color in this list — used to
     * auto-suggest a not-already-taken curated color for a brand-new Group
     * (see [GroupColorPalette.nextAvailableColor]) so "Different Group =
     * Different Color" holds by default without the admin having to think
     * about it; irrelevant once [existingGroup] already has its own color. */
    usedColors: List<String>,
    /** Only used when [existingGroup] has no [Group.color] of its own yet —
     * see the caller's own doc comment on why this must be the color the
     * map is already showing, never a fresh pick. */
    legacyFallbackColor: String?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existingGroup?.name ?: "") }
    // "The color must always come from the Group Record assignment and must
    // never be determined randomly or individually for each member" — a new
    // Group starts from the next unused curated color (still just a
    // starting point the admin can override below); an existing Group keeps
    // its own saved color, or (legacy, pre-color Group) whatever the map
    // already shows for it, untouched unless the admin picks a different one.
    var selectedColor by remember {
        mutableStateOf(
            existingGroup?.color
                ?: legacyFallbackColor
                ?: GroupColorPalette.nextAvailableColor(usedColors, seedId = java.util.UUID.randomUUID().toString()),
        )
    }
    val initialColor = remember { selectedColor }
    val showToast = rememberActionToast()
    val congregations by viewModel.congregations.collectAsStateWithLifecycle(initialValue = emptyList())
    // Bug fix ("Congregation/Group is required" even after picking one):
    // this used to hold the whole selected [Congregation] in a plain
    // `remember`, keyed on the live `congregations` list — which both (a)
    // got a new List instance on every Firestore snapshot re-emission, and
    // (b) is plain `remember`, so it's wiped outright by any configuration
    // change (rotation, a system dark/light switch that recreates the
    // Activity, ...) that happens between picking a congregation and tapping
    // Save. A `String?` id in `rememberSaveable` is immune to both: it isn't
    // keyed on the list at all, and it survives a config change via the
    // instance-state Bundle the same way a plain text field's typed-in value
    // already does elsewhere in this app.
    var pickedCongregationId by rememberSaveable { mutableStateOf(existingGroup?.congregationId ?: com.emfitsolutions.gopreach.ui.components.CongregationContextStore.get("field_service_groups")) }
    val pickedCongregation = congregations.firstOrNull { it.id == pickedCongregationId }
    // Scoped roles (Admin/Coordinator Elder) already have exactly one congregation;
    // only a Super-Admin needs to pick one here.
    val congregationId = fixedCongregationId ?: pickedCongregationId ?: existingGroup?.congregationId

    var overseer by remember { mutableStateOf<Person?>(null) }
    var servant by remember { mutableStateOf<Person?>(null) }
    var assistant by remember { mutableStateOf<Person?>(null) }
    var preselected by remember { mutableStateOf(existingGroup == null) }
    // "Discard changes?" needs to compare against what was actually
    // preselected from the existing Group, not the placeholder `null` these
    // three start at before that preselection runs below — same reasoning
    // as the Elder edit dialogs' own initialIsGroupOverseer/
    // initialPublisherCategory snapshots.
    var initialOverseer by remember { mutableStateOf<Person?>(null) }
    var initialServant by remember { mutableStateOf<Person?>(null) }
    var initialAssistant by remember { mutableStateOf<Person?>(null) }

    val overseerCandidates by remember(congregationId, servant, assistant) {
        if (congregationId != null) {
            viewModel.availableEldersFor(congregationId, RegularElderRole.GROUP_OVERSEER, setOfNotNull(servant?.id, assistant?.id))
        } else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val servantCandidates by remember(congregationId, overseer, assistant) {
        if (congregationId != null) {
            // The Group Servant comes from the Ministerial Servant list, not from the Regular Elders.
            viewModel.availableMinisterialServantsFor(congregationId, setOfNotNull(overseer?.id, assistant?.id))
        } else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    // "'Group Assistant' can be browse from Publishers Record" — unlike
    // Overseer/Servant (Elder-only above), this candidate list also includes
    // active Publishers, not just Regular Elders (see
    // ManageGroupsViewModel.availableAssistantCandidatesFor).
    val assistantCandidates by remember(congregationId, overseer, servant) {
        if (congregationId != null) {
            viewModel.availableAssistantCandidatesFor(congregationId, setOfNotNull(overseer?.id, servant?.id))
        } else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    // Pre-fill from the existing Group's three role slots once their candidate
    // lists have loaded — a legacy single-Elder Group (regularElderPersonId set,
    // the three role fields still null) has nothing to pre-fill here, which is
    // exactly the "assign the appropriate role" gap the admin fills in manually below.
    if (!preselected && (overseerCandidates.isNotEmpty() || servantCandidates.isNotEmpty() || assistantCandidates.isNotEmpty())) {
        overseer = overseerCandidates.firstOrNull { it.id == existingGroup?.overseerPersonId }
        // A group saved before this change may still name a Regular Elder as its servant — keep
        // showing (and keeping) them until someone picks a Ministerial Servant instead.
        servant = servantCandidates.firstOrNull { it.id == existingGroup?.servantPersonId }
            ?: (overseerCandidates + assistantCandidates.map { it.person }).firstOrNull { it.id == existingGroup?.servantPersonId }
        assistant = assistantCandidates.firstOrNull { it.person.id == existingGroup?.assistantPersonId }?.person
        initialOverseer = overseer
        initialServant = servant
        initialAssistant = assistant
        preselected = true
    }

    val legacyElderName = existingGroup?.regularElderPersonId?.let { legacyId ->
        (overseerCandidates + servantCandidates + assistantCandidates.map { it.person }).firstOrNull { it.id == legacyId }?.fullName
    }

    // "[ADD MEMBERS] Browse from Publishers Record" — every active Publisher
    // in this congregation, checkbox-selectable. Selection starts from
    // whoever is already assigned to this Group (empty for a brand-new one,
    // since it has no id — and therefore no members — yet); [membersPreselected]
    // is the same "wait for the candidate list, then pre-fill once" pattern
    // [preselected] above already uses for the three Elder role dropdowns.
    val memberCandidates by remember(congregationId) {
        if (congregationId != null) viewModel.membersFor(congregationId) else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    var checkedMemberIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var initialCheckedMemberIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var membersPreselected by remember { mutableStateOf(existingGroup == null) }
    if (!membersPreselected && memberCandidates.isNotEmpty()) {
        val preselectedIds = memberCandidates.filter { it.assignment.groupId == existingGroup?.id }.map { it.person.id }.toSet()
        checkedMemberIds = preselectedIds
        initialCheckedMemberIds = preselectedIds
        membersPreselected = true
    }

    var errorMessage by remember { mutableStateOf<String?>(null) }

    // "Field Service Group Name (Required, avoid duplicate name in a
    // congregation)" — collected fresh whenever the congregation (or which
    // group is being edited) changes; checked locally on submit, same
    // "cheap client-side recheck" pattern the Congregation code-uniqueness
    // check already uses.
    val namesInUse by remember(congregationId, existingGroup?.id) {
        if (congregationId != null) viewModel.namesInUse(congregationId, existingGroup?.id) else kotlinx.coroutines.flow.flowOf(emptySet())
    }.collectAsStateWithLifecycle(initialValue = emptySet())

    fun submit() {
        // Bug fix — confirmed live on-device: tapping Create/Save could
        // invoke a *stale* `submit()` closure from an earlier recomposition,
        // one captured back when `congregationId` (a plain `val`, snapshotted
        // once per recomposition) was still null, before a congregation had
        // been picked. `pickedCongregationId` itself was always correct
        // (it's a live `State` read, not a captured snapshot — confirmed via
        // logcat, which showed the real picked id alongside a null
        // `congregationId` in the very same log line), so re-deriving the
        // resolved id HERE, live, at the moment submit() actually runs —
        // rather than trusting whichever `congregationId` val this
        // particular closure happened to close over — is immune to that
        // staleness regardless of which recomposition produced this closure.
        val resolvedCongregationId = fixedCongregationId ?: pickedCongregationId ?: existingGroup?.congregationId
        val message = requiredFieldsMessage(
            "Field Service Group Name" to name.isNotBlank(),
            "Congregation" to (resolvedCongregationId != null),
        )
        if (message != null) {
            errorMessage = message
            return
        }
        if (name.trim().uppercase() in namesInUse) {
            errorMessage = "A field service group named \"${name.trim()}\" already exists in this congregation."
            return
        }
        viewModel.saveWithMembers(
            group = Group(
                id = existingGroup?.id ?: "",
                congregationId = resolvedCongregationId!!,
                name = name.trim(),
                regularElderPersonId = existingGroup?.regularElderPersonId,
                color = selectedColor,
                overseerPersonId = overseer?.id,
                servantPersonId = servant?.id,
                assistantPersonId = assistant?.id,
                createdAt = existingGroup?.createdAt ?: System.currentTimeMillis(),
            ),
            candidates = memberCandidates,
            selectedMemberPersonIds = checkedMemberIds,
            actorPersonId = currentPersonId,
        )
        showToast(if (existingGroup == null) "Field service group added." else "Field service group saved.")
        onDismiss()
    }

    FormDialog(
        onDismissRequest = onDismiss,
        title = if (existingGroup == null) "New Field Service Group" else "Edit Field Service Group",
        onConfirm = ::submit,
        confirmLabel = if (existingGroup == null) "Create" else "Save",
        errorMessage = errorMessage,
        maxContentHeight = 480.dp,
        hasUnsavedChanges = name != (existingGroup?.name ?: "") ||
            pickedCongregationId != existingGroup?.congregationId ||
            overseer?.id != initialOverseer?.id || servant?.id != initialServant?.id || assistant?.id != initialAssistant?.id ||
            checkedMemberIds != initialCheckedMemberIds ||
            selectedColor != initialColor,
    ) {
                if (fixedCongregationId == null) {
                    CongregationPickerDropdown(
                        congregations = congregations,
                        selected = pickedCongregation,
                        onSelected = { pickedCongregationId = it.id },
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.uppercase(); errorMessage = null },
                    label = { Text("Field Service Group Name") },
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
                // "The Group Color must be applied consistently... in
                // Territory Map markers, Territory Scope/territory color,
                // Group detail view, Group member display" — every member's
                // map icon uses whatever is picked here (see
                // TerritoryMapScreen.groupColorById), and changing it later
                // and saving refreshes all of those automatically since they
                // all read this same field live off the Group record.
                EditSectionHeader("Group Color")
                Text(
                    "Every member's Territory Map icon and territory scope will use this color.",
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    GroupColorPalette.CURATED.forEach { hex ->
                        val isSelected = hex.equals(selectedColor, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(GroupColorPalette.parseHex(hex))
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape,
                                )
                                .clickable { selectedColor = hex },
                        )
                    }
                }
                if (legacyElderName != null && existingGroup?.isComplete == false) {
                    Text(
                        "Existing assignment: $legacyElderName — pick which role they hold below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                ElderRoleDropdown(
                    label = RegularElderRole.GROUP_OVERSEER.displayLabel(),
                    elders = overseerCandidates,
                    selected = overseer,
                    onSelected = { overseer = it },
                )
                ElderRoleDropdown(
                    label = RegularElderRole.GROUP_SERVANT.displayLabel(),
                    elders = servantCandidates,
                    selected = servant,
                    onSelected = { servant = it },
                )
                AssistantRoleDropdown(
                    label = RegularElderRole.GROUP_ASSISTANT.displayLabel(),
                    candidates = assistantCandidates,
                    selected = assistant,
                    onSelected = { assistant = it },
                )

                if (congregationId != null) {
                    EditSectionHeader("Members")
                    if (memberCandidates.isEmpty()) {
                        Text(
                            "No publishers enrolled in this congregation yet.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            "A publisher can only belong to one group at a time — checking someone already in another group transfers them here.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        memberCandidates.forEach { candidate ->
                            val checked = candidate.person.id in checkedMemberIds
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { isChecked ->
                                        checkedMemberIds = if (isChecked) {
                                            checkedMemberIds + candidate.person.id
                                        } else {
                                            checkedMemberIds - candidate.person.id
                                        }
                                    },
                                )
                                Column {
                                    Text(candidate.person.fullName)
                                    if (candidate.currentGroupName != null && candidate.currentGroupName != existingGroup?.name) {
                                        Text(
                                            "Currently in: ${candidate.currentGroupName}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (existingGroup != null) {
                    EditSectionHeader("System Information")
                    ReadOnlyField("Record ID", existingGroup.id)
                    ReadOnlyField("Status", existingGroup.status.name)
                    ReadOnlyField("Date Added", formatRecordTimestamp(existingGroup.createdAt))
                }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CongregationPickerDropdown(
    congregations: List<Congregation>,
    selected: Congregation?,
    onSelected: (Congregation) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.name ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text("Congregation") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            congregations.forEach { congregation ->
                DropdownMenuItem(
                    text = { Text(congregation.name) },
                    onClick = {
                        onSelected(congregation)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** One role's Elder picker — [elders] is already filtered to that role (plus
 * unclassified legacy Elders) and already excludes whoever the *other two*
 * dropdowns currently hold, so cross-role duplicate selection isn't reachable
 * through this UI at all. A "None" option lets a role be cleared. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ElderRoleDropdown(label: String, elders: List<Person>, selected: Person?, onSelected: (Person?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.fullName ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("None") },
                onClick = {
                    onSelected(null)
                    expanded = false
                },
            )
            elders.forEach { elder ->
                DropdownMenuItem(
                    text = { Text(elder.fullName) },
                    onClick = {
                        onSelected(elder)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Group Assistant's own picker — "'Group Assistant' can be browse from
 * Publishers Record": [candidates] mixes Regular Elders and Publishers (see
 * [ManageGroupsViewModel.availableAssistantCandidatesFor]), so each option is
 * labeled "(Elder)"/"(Publisher)" to keep which pool a name came from clear —
 * the only real difference from [ElderRoleDropdown] otherwise. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantRoleDropdown(label: String, candidates: List<PersonCandidate>, selected: Person?, onSelected: (Person?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.fullName ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("None") },
                onClick = {
                    onSelected(null)
                    expanded = false
                },
            )
            candidates.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text("${candidate.person.fullName} (${if (candidate.isElder) "Elder" else "Publisher"})") },
                    onClick = {
                        onSelected(candidate.person)
                        expanded = false
                    },
                )
            }
        }
    }
}

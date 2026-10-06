package com.emfitsolutions.gopreach.ui.screens.credithours

import androidx.compose.foundation.layout.Arrangement
import com.emfitsolutions.gopreach.ui.components.RecordFound
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.CreditHourCategory
import com.emfitsolutions.gopreach.ui.components.FormDialog
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.components.requiredFieldsMessage

/**
 * Credit Hour Categories management (Super-Admin / Admin): the list the
 * Credit Hours dropdown reads from. Add, edit (name + Active/Inactive),
 * activate/deactivate in place, and delete — where a category already used
 * by Credit Hour entries is never physically deleted, only deactivated, so
 * historical entries keep a valid category. Inactive categories disappear
 * from new entries but still show on (and can be kept by) old ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditHourCategoriesScreen(
    onBack: () -> Unit,
    viewModel: CreditHourCategoriesViewModel = hiltViewModel(),
) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val deleteCheck by viewModel.deleteCheck.collectAsStateWithLifecycle()
    val toast = rememberActionToast()
    LaunchedEffect(viewModel) { viewModel.messages.collect { toast(it) } }

    var editing by remember { mutableStateOf<CreditHourCategory?>(null) }
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Credit Hour Categories") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Add Category") },
            )
        },
    ) { padding ->
        val list = categories
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RecordFound(0)
                Text("No Credit Hour categories available.", style = MaterialTheme.typography.titleSmall)
                Text("Tap Add Category to create the first one.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
                        Text("Category", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text("Active", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.size(width = 96.dp, height = 1.dp))
                    }
                    HorizontalDivider()
                }
                item { RecordFound(list.size) }
                items(list, key = { it.id }) { category ->
                    CategoryRow(
                        category = category,
                        onToggleActive = { viewModel.setActive(category, it) },
                        onEdit = { editing = category },
                        onDelete = { viewModel.requestDelete(category) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                item {
                    Text(
                        "Inactive categories are hidden from new Credit Hour entries but stay on existing ones.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }

    if (adding) {
        CategoryDialog(
            initial = null,
            onDismiss = { adding = false },
            onSave = { name, active ->
                viewModel.save(CreditHourCategory(name = name, active = active))
                adding = false
            },
        )
    }
    editing?.let { category ->
        CategoryDialog(
            initial = category,
            onDismiss = { editing = null },
            onSave = { name, active ->
                // Same document id — every Credit Hour entry linked to it
                // shows the new name automatically.
                viewModel.save(category.copy(name = name, active = active))
                editing = null
            },
        )
    }

    when (val check = deleteCheck) {
        null -> Unit
        CategoryDeleteCheck.Checking -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Checking usage…") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Looking for Credit Hour entries that use this category.")
                }
            },
            confirmButton = {},
        )
        is CategoryDeleteCheck.Unused -> AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text("Delete \"${check.category.name}\"?") },
            text = { Text("No Credit Hour entries use this category. It will be permanently removed from the list.") },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDelete(check.category) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDelete) { Text("Cancel") } },
        )
        is CategoryDeleteCheck.InUse -> AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text("Can't delete \"${check.category.name}\"") },
            text = {
                Text(
                    if (check.couldNotVerify) {
                        "Couldn't reach the server to confirm this category is unused, so it can't be deleted right now. You can deactivate it instead — it will be hidden from new entries, and existing entries keep it."
                    } else {
                        "This category is used by existing Credit Hour entries, so it can't be deleted. Deactivate it instead — it will be hidden from new entries, and existing entries keep it."
                    },
                )
            },
            confirmButton = {
                if (check.category.active) {
                    TextButton(onClick = { viewModel.deactivateInstead(check.category) }) { Text("Deactivate") }
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDelete) { Text("Close") } },
        )
    }
}

@Composable
private fun CategoryRow(
    category: CreditHourCategory,
    onToggleActive: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                category.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (category.active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusBadge(category.active)
        }
        Switch(checked = category.active, onCheckedChange = onToggleActive)
        IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, contentDescription = "Edit ${category.name}") }
        IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = "Delete ${category.name}") }
    }
}

@Composable
private fun StatusBadge(active: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        modifier = Modifier.padding(top = 2.dp),
    ) {
        Text(
            if (active) "Active" else "Inactive",
            style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDialog(
    initial: CreditHourCategory?,
    onDismiss: () -> Unit,
    onSave: (name: String, active: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var active by remember { mutableStateOf(initial?.active ?: true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    FormDialog(
        onDismissRequest = onDismiss,
        title = if (initial == null) "Add Credit Hour Category" else "Edit Credit Hour Category",
        onConfirm = {
            errorMessage = requiredFieldsMessage("Category Name" to name.isNotBlank())
            if (errorMessage == null) onSave(name.trim(), active)
        },
        errorMessage = errorMessage,
        hasUnsavedChanges = name != initial?.name.orEmpty() || active != (initial?.active ?: true),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; errorMessage = null },
            label = { Text("Category Name") },
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Status", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf(true to "Active", false to "Inactive").forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = active == value,
                    onClick = { active = value },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                ) { Text(label) }
            }
        }
    }
}

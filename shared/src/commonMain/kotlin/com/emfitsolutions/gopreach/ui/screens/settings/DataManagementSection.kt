package com.emfitsolutions.gopreach.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.emfitsolutions.gopreach.data.model.AppSettings
import com.emfitsolutions.gopreach.ui.screens.deletedrecords.DeletedRecordsViewModel

/**
 * Settings → Data Management → Deleted Records: whether records that sit in Deleted Records are permanently deleted
 * automatically, and after how long. Off by default — then nothing is ever removed automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataManagementSection(
    currentPersonId: String,
    /** Super-Admin / Admin / Coordinator Elder / Service Overseer / Secretary. Everyone else only opens their own deleted records. */
    canChangeRetention: Boolean,
    onOpenDeletedRecords: () -> Unit,
    viewModel: DeletedRecordsViewModel = koinViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }

    Text("Data Management", style = MaterialTheme.typography.titleMedium)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Deleted Records", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Automatically Permanently Delete Deleted Records", modifier = Modifier.weight(1f).padding(end = 12.dp))
                Switch(
                    checked = settings.trashAutoDeleteEnabled,
                    enabled = canChangeRetention,
                    onCheckedChange = { viewModel.saveRetention(it, settings.trashRetentionDays, currentPersonId) },
                )
            }
            if (settings.trashAutoDeleteEnabled) {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = "${settings.trashRetentionDays} Days",
                        onValueChange = {},
                        readOnly = true,
                        enabled = canChangeRetention,
                        label = { Text("Delete permanently after") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        visualTransformation = VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = expanded && canChangeRetention, onDismissRequest = { expanded = false }) {
                        AppSettings.TRASH_RETENTION_OPTIONS.forEach { days ->
                            DropdownMenuItem(
                                text = { Text("$days Days") },
                                onClick = { viewModel.saveRetention(true, days, currentPersonId); expanded = false },
                            )
                        }
                    }
                }
                Text(
                    "Deleted records will remain available for restoration until the selected retention period expires. After that period, they will be permanently deleted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Deleted records will remain available until manually restored or permanently deleted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onOpenDeletedRecords, modifier = Modifier.fillMaxWidth()) { Text("Open Deleted Records") }
        }
    }
}

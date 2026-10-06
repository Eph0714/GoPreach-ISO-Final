package com.emfitsolutions.gopreach.ui.components

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.layout.fillMaxWidth
import com.emfitsolutions.gopreach.data.model.Congregation

/**
 * "Add a filter for Congregation" — the one Super-Admin-only Congregation
 * filter dropdown every module below shares, so it looks and behaves
 * identically everywhere rather than each screen growing its own copy
 * (previously the case for the two spots this replaces — see
 * [ManagePublisherReportsScreen]/[ShareLocationScreen]/
 * [SuperAdminInterestedRecordsScreen]'s own call sites, which used a free-text
 * box or an ad hoc control before this).
 *
 * Deliberately just a display convenience: [selectedCongregationId] narrows
 * an already-unrestricted (Super-Admin) view, exactly like
 * [com.emfitsolutions.gopreach.ui.screens.territories.TerritoryMapScreen]'s
 * own "Search By: Congregation" filter — it is never itself the access
 * boundary (that's still whatever `visibleCongregationId`/
 * `fixedCongregationId` the nav graph already resolves from the signed-in
 * session), so every caller only ever renders this when that underlying
 * scope is already "every congregation" (Super-Admin) — see each call site's
 * own `isSuperAdmin` gate.
 *
 * `null` means "All Congregations" (the default, and the only option a
 * scoped role would ever have anyway).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CongregationFilterDropdown(
    congregations: List<Congregation>,
    selectedCongregationId: String?,
    onSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Congregation",
    requireSelection: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = selectedCongregationId?.let { id -> congregations.firstOrNull { it.id == id }?.name } ?: if (requireSelection) "Select Congregation" else "All Congregations"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (!requireSelection) DropdownMenuItem(text = { Text("All Congregations") }, onClick = { onSelected(null); expanded = false })
            congregations.sortedBy { it.name }.forEach { congregation ->
                DropdownMenuItem(text = { Text(congregation.name) }, onClick = { onSelected(congregation.id); expanded = false })
            }
        }
    }
}

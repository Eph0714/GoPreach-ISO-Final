package com.emfitsolutions.gopreach.ui.screens.pipeline

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.TerritoryAssignmentBarangay
import com.emfitsolutions.gopreach.domain.GroupAccessScope
import com.emfitsolutions.gopreach.ui.screens.territories.normalizePlaceName

/** One municipality + barangay a person may filter their records by: it comes from a territory assignment they are entitled to see. */
data class AuthorizedArea(val municipality: String, val barangay: String)

/** The three ways the lists can be viewed. */
object AreaFilterMode {
    const val ALL = 0
    const val MUNICIPALITY = 1
    const val BARANGAY = 2
    val labels = listOf("All Authorized Records", "Filter by Municipality", "Filter by Barangay")
}

/**
 * The municipalities and barangays a person may pick in the filter, taken only from territory assignments inside their own scope:
 * a congregation-wide role sees its congregation's territories, a Super Admin the congregation being viewed, and everyone else only the territories
 * of the FS Group(s) they belong to ([myGroupIds]). Another group's territory, or another congregation's, never appears.
 */
fun authorizedAreas(
    scope: GroupAccessScope,
    congregationId: String,
    myGroupIds: Set<String>,
    claims: List<TerritoryAssignmentBarangay>,
): List<AuthorizedArea> =
    claims
        .filter { it.congregationId == congregationId && it.muncityName.isNotBlank() && it.barangayName.isNotBlank() }
        .filter { claim ->
            when (scope) {
                GroupAccessScope.AllCongregations -> true
                is GroupAccessScope.Congregation -> scope.congregationId == claim.congregationId
                is GroupAccessScope.OwnGroup, GroupAccessScope.None -> claim.groupId in myGroupIds
            }
        }
        .map { AuthorizedArea(it.muncityName.trim(), it.barangayName.trim()) }
        .distinctBy { normalizePlaceName(it.municipality) + "|" + normalizePlaceName(it.barangay) }
        .sortedWith(compareBy({ it.municipality.lowercase() }, { it.barangay.lowercase() }))

/**
 * Narrows records to a chosen municipality (and barangay). A record with no municipality or barangay saved never matches a filter, so missing or
 * ambiguous location data can never pull a record into a view it should not be in; it still shows under All Authorized Records.
 */
fun List<InterestedPerson>.filterByArea(mode: Int, municipality: String?, barangay: String?): List<InterestedPerson> {
    if (mode == AreaFilterMode.ALL || municipality.isNullOrBlank()) return this
    val m = normalizePlaceName(municipality)
    val b = if (mode == AreaFilterMode.BARANGAY && !barangay.isNullOrBlank()) normalizePlaceName(barangay) else null
    return filter { normalizePlaceName(it.cityMunicipality) == m && (b == null || normalizePlaceName(it.barangay) == b) }
}

/** The municipalities to offer (each once, alphabetical). */
internal fun List<AuthorizedArea>.municipalities(): List<String> =
    map { it.municipality }.distinctBy { normalizePlaceName(it) }.sortedBy { it.lowercase() }

/** The barangays of [municipality] to offer. Empty until a municipality is chosen. */
internal fun List<AuthorizedArea>.barangaysOf(municipality: String?): List<String> =
    if (municipality.isNullOrBlank()) emptyList()
    else filter { normalizePlaceName(it.municipality) == normalizePlaceName(municipality) }.map { it.barangay }.distinctBy { normalizePlaceName(it) }.sortedBy { it.lowercase() }

/**
 * "View: All Authorized Records / Filter by Municipality / Filter by Barangay", the dropdowns they need (only authorized choices), and Clear.
 * Changing the municipality drops a barangay that no longer belongs to it.
 */
@Composable
internal fun AreaFilterBar(
    areas: List<AuthorizedArea>,
    mode: Int,
    municipality: String?,
    barangay: String?,
    onChange: (mode: Int, municipality: String?, barangay: String?) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = mode != AreaFilterMode.ALL
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Find a record by name (or by the place or publisher shown on it); works together with the area filter below.
        androidx.compose.material3.OutlinedTextField(
            value = query, onValueChange = onQueryChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search by name") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = { if (query.isNotEmpty()) androidx.compose.material3.IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") } },
            shape = RoundedCornerShape(12.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterDropdown(
                value = AreaFilterMode.labels[mode],
                options = AreaFilterMode.labels.mapIndexed { i, l -> i to l },
                selected = mode,
                onSelect = { m -> onChange(m, if (m == AreaFilterMode.ALL) null else municipality, if (m == AreaFilterMode.BARANGAY) barangay else null) },
                modifier = Modifier.weight(1f),
            )
            if (active) TextButton(onClick = { onChange(AreaFilterMode.ALL, null, null) }) { Text("Clear") }
        }
        if (mode != AreaFilterMode.ALL) {
            val municipalities = areas.municipalities()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterDropdown(
                    value = municipality ?: "Choose a municipality",
                    options = municipalities.map { it to it },
                    selected = municipality,
                    onSelect = { m -> onChange(mode, m, null) },
                    enabled = municipalities.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
                if (mode == AreaFilterMode.BARANGAY) {
                    val barangays = areas.barangaysOf(municipality)
                    FilterDropdown(
                        value = barangay ?: if (municipality == null) "Pick the municipality first" else "Choose a barangay",
                        options = barangays.map { it to it },
                        selected = barangay,
                        onSelect = { b -> onChange(mode, municipality, b) },
                        enabled = barangays.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (municipalities.isEmpty()) {
                Text("There are no territories assigned to you to filter by.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun <T> FilterDropdown(
    value: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    Box(modifier = modifier) {
        Surface(
            onClick = { open = true },
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            color = primary.copy(alpha = if (enabled) 0.08f else 0.03f),
            border = BorderStroke(1.dp, primary.copy(alpha = if (enabled) 0.4f else 0.15f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(modifier = Modifier.heightIn(min = 44.dp).padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    color = if (enabled) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = if (enabled) primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label, fontWeight = if (key == selected) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { open = false; onSelect(key) },
                )
            }
        }
    }
}

/** Whether a record matches what was typed: any part of the name, or of its place, case-insensitive. A blank search matches everything. */
fun InterestedPerson.matchesSearch(query: String, publisherName: String? = null): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return listOfNotNull(name, address, cityMunicipality, barangay, publisherName).any { it.lowercase().contains(q) }
}
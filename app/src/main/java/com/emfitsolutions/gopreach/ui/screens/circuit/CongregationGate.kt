package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.SimpleDropdown

/**
 * The congregation chosen for the detailed Circuit Overseer lists — only if it is one of [congregations] (the ones this
 * account may see). Anything else (a stale choice from another circuit/account, a removed assignment) counts as "none".
 */
fun validCongregation(congregations: List<Congregation>, chosenId: String?): Congregation? =
    congregations.firstOrNull { it.id == chosenId }

/**
 * "Select a Congregation" — shown before ANY detailed record is loaded or listed. Offers only [congregations]
 * (the circuit's); there is deliberately no "All Congregations" choice here.
 */
@Composable
fun SelectCongregationPrompt(congregations: List<Congregation>, onSelect: (String) -> Unit, modifier: Modifier = Modifier, hint: String? = null) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Select a Congregation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            hint ?: "Choose a congregation under your assigned Circuit to view its records.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SimpleDropdown(
            label = "Congregation",
            selectedLabel = "Select Congregation",
            options = congregations.map { it.id to it.name },
            onSelected = onSelect,
        )
        if (congregations.isEmpty()) Text("No congregations are assigned to this account yet.", style = MaterialTheme.typography.bodySmall)
    }
}

/** "Congregation: X   [Change Congregation]" — stays visible while a congregation's records are shown. */
@Composable
fun SelectedCongregationBar(name: String, onChange: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Congregation: $name", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(end = 8.dp))
        OutlinedButton(onClick = onChange) { Text("Change Congregation") }
    }
}

package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.domain.RosterSelection

/**
 * Publisher selection of Meeting and Cart Assignment: the congregation's publishers as a checkbox list. A search box narrows it (the same words in any order), Select All ticks every
 * publisher that is shown, Clear removes every tick, and the number selected is always visible; the whole row is the tap target. The value is still ONE comma-separated text, so nothing
 * about how an assignment is stored, who may change it or how My Assignments finds a person changes. A name typed by hand earlier that is not on the roster is listed first and can be
 * unticked; "Not on the list?" adds such a name by hand, as before.
 */
@Composable
fun PublisherChecklistField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    roster: List<String>,
    modifier: Modifier = Modifier,
) {
    val selected = remember(value) { RosterSelection.parse(value) }
    var query by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }
    val shown = remember(roster, query) { RosterSelection.visible(roster, query) }
    val outside = remember(selected, roster) { RosterSelection.outsideRoster(selected, roster) }
    val rowShape = RoundedCornerShape(12.dp)

    fun set(names: List<String>) = onValueChange(RosterSelection.format(names))
    fun addManual() {
        val name = manual.trim()
        if (name.isEmpty()) return
        if (!RosterSelection.isSelected(selected, name)) set(selected + name)
        manual = ""
    }

    @Composable
    fun PersonRow(name: String, note: String? = null) {
        val on = RosterSelection.isSelected(selected, name)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(rowShape)
                .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface)
                .clickable { set(RosterSelection.toggle(selected, name)) }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = on, onCheckedChange = { set(RosterSelection.toggle(selected, name)) })
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${selected.size} selected", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        StandardSearchField(value = query, onValueChange = { query = it }, placeholder = "Search Publisher name...", modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { set(RosterSelection.selectAll(selected, shown)) }, enabled = shown.isNotEmpty()) { Text("Select All") }
            TextButton(onClick = { set(RosterSelection.clear()) }, enabled = selected.isNotEmpty()) { Text("Clear Selection") }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .clip(rowShape)
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                outside.forEach { PersonRow(it, note = "Not on the congregation list") }
                shown.forEach { PersonRow(it) }
                if (shown.isEmpty() && outside.isEmpty()) {
                    Text(
                        if (roster.isEmpty()) "No publishers in this congregation." else "No matching Publisher was found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
        OutlinedTextField(
            value = manual,
            onValueChange = { manual = it },
            label = { Text("Not on the list? Type a name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { addManual() }),
            trailingIcon = { TextButton(onClick = { addManual() }, enabled = manual.isNotBlank()) { Text("Add") } },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

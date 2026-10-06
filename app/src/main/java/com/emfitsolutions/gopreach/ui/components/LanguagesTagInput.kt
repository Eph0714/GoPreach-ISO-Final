package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** A curated starting list, not an exhaustive/enforced enum — "Other"
 * always falls through to free text, so a congregation using a language
 * not listed here is never blocked from recording it. */
private val COMMON_LANGUAGES = listOf(
    "Tagalog",
    "English",
    "Iloko",
    "Ibanag",
    "Itawes",
    "Ivatan",
    "Gaddang",
    "Yogad",
    "Pangasinan",
    "Kapampangan",
    "Cebuano",
    "Hiligaynon",
    "Waray",
    "Bikol",
    "Kankanaey",
    "Ifugao",
)
private const val OTHER_OPTION = "Other (specify)"

/**
 * "Language(s) Used" — Congregation enrollment/edit. A dropdown of common
 * languages (spec: "make it a dropdown option"), plus "Other (specify)" for
 * anything not listed — never a hard block on a real language this list
 * happens to be missing. Picking one adds it as a removable chip;
 * [languages] is the current list, owned by the caller.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LanguagesTagInput(
    languages: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedOption by remember { mutableStateOf<String?>(null) }
    var otherText by remember { mutableStateOf("") }

    fun commitOther() {
        val trimmed = otherText.trim()
        if (trimmed.isNotEmpty() && languages.none { it.equals(trimmed, ignoreCase = true) }) {
            onAdd(trimmed)
        }
        otherText = ""
        selectedOption = null
    }

    fun commitSelected() {
        val option = selectedOption ?: return
        if (languages.none { it.equals(option, ignoreCase = true) }) onAdd(option)
        selectedOption = null
    }

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.weight(1f),
            ) {
                OutlinedTextField(
                    value = selectedOption ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Language Used") },
                    placeholder = { Text("Select a language") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    // Already-added languages are hidden here so the same
                    // one can't be selected (and silently no-op'd) twice.
                    COMMON_LANGUAGES.filter { option -> languages.none { it.equals(option, ignoreCase = true) } }
                        .forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    selectedOption = option
                                    expanded = false
                                },
                            )
                        }
                    DropdownMenuItem(
                        text = { Text(OTHER_OPTION) },
                        onClick = {
                            selectedOption = OTHER_OPTION
                            expanded = false
                        },
                    )
                }
            }
            IconButton(onClick = { commitSelected() }, enabled = selectedOption != null && selectedOption != OTHER_OPTION) {
                Icon(Icons.Rounded.Add, contentDescription = "Add language")
            }
        }

        // "Other" needs a name typed in before it can actually be added —
        // shown only once picked, rather than always-visible clutter.
        if (selectedOption == OTHER_OPTION) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                OutlinedTextField(
                    value = otherText,
                    onValueChange = { otherText = it },
                    label = { Text("Specify Language") },
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitOther() }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { commitOther() }, enabled = otherText.isNotBlank()) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add language")
                }
            }
        }

        if (languages.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                languages.forEach { language ->
                    AssistChip(
                        onClick = { onRemove(language) },
                        label = { Text(language) },
                        trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = "Remove $language") },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }
        }
    }
}

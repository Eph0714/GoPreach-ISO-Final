package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The drop-down of one set of ministry records: [who] ("My" or "Congregation") followed by Searching / Return Visit / Bible Study Record, tinted in the
 * list's own colour (black, green, blue). [selected] is 0..2, or null when this drop-down is not the one in use (it then shows [placeholder]).
 * [allowAll] adds an "All" entry first, which calls [onPick] with null.
 */
@Composable
fun MinistryPicker(placeholder: String, who: String, selected: Int?, onPick: (Int?) -> Unit, modifier: Modifier = Modifier, allowAll: Boolean = false) {
    val kinds = listOf("Searching Record", "Return Visit Record", "Bible Study Record")
    val tones = listOf(MaterialTheme.colorScheme.onSurface, Color(0xFF2E7D32), Color(0xFF1565C0))
    val tone = selected?.let { tones[it] } ?: MaterialTheme.colorScheme.primary
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(12.dp),
            color = tone.copy(alpha = if (selected != null) 0.10f else 0.04f),
            border = BorderStroke(if (selected != null) 1.5.dp else 1.dp, tone.copy(alpha = if (selected != null) 0.6f else 0.3f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(modifier = Modifier.heightIn(min = 44.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    selected?.let { "$who ${kinds[it]}" } ?: placeholder,
                    style = MaterialTheme.typography.labelLarge, fontWeight = if (selected != null) FontWeight.SemiBold else FontWeight.Normal,
                    color = tone, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Choose $who records", tint = tone)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (allowAll) DropdownMenuItem(text = { Text("$who Records (all)", fontWeight = if (selected == null) FontWeight.Bold else FontWeight.Normal) }, onClick = { open = false; onPick(null) })
            kinds.forEachIndexed { index, label ->
                DropdownMenuItem(
                    text = { Text("$who $label", color = tones[index], fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { open = false; onPick(index) },
                )
            }
        }
    }
}
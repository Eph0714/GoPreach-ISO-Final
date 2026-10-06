package com.emfitsolutions.gopreach.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.data.print.OrientationMode
import com.emfitsolutions.gopreach.data.print.PaperSize
import com.emfitsolutions.gopreach.data.print.PrintPreferences

/**
 * Settings → Printing: the default paper size and orientation every report prints with. "Automatic" picks
 * portrait for narrow reports and landscape for wide tables. The print preview that opens before printing still
 * lets you change either one for that print, and the pages re-flow to match.
 */
@Composable
fun PrintSettingsSection() {
    val context = LocalContext.current
    val prefs = remember { PrintPreferences(context) }
    var paper by remember { mutableStateOf(prefs.paperSize) }
    var orientation by remember { mutableStateOf(prefs.orientation) }

    Text("Printing", style = MaterialTheme.typography.titleMedium)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text("Paper size", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            PaperSize.entries.forEach { option ->
                ChoiceRow(option.label, paper == option) { paper = option; prefs.paperSize = option }
            }
            Text("Orientation", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
            OrientationMode.entries.forEach { option ->
                ChoiceRow(option.label, orientation == option) { orientation = option; prefs.orientation = option }
            }
            Text(
                "Reports are laid out to fit the page at a readable size. You can still change the paper or orientation in the print preview.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

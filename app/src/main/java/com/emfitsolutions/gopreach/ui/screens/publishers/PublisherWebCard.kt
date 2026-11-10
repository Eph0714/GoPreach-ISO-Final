package com.emfitsolutions.gopreach.ui.screens.publishers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emfitsolutions.gopreach.data.repository.PasswordStatus

/** The colour of a status chip, as on the website: a tinted pill with the same colour for its text. null = neutral. */
private fun chipColor(good: Boolean?, danger: Boolean = false): Color? = when {
    danger -> Color(0xFFB91C1C)
    good == true -> Color(0xFF15803D)
    good == false -> Color(0xFFB45309)
    else -> null
}

@Composable
private fun WebChip(text: String, color: Color?) {
    val fg = color ?: MaterialTheme.colorScheme.onSurfaceVariant
    val bg = color?.copy(alpha = 0.16f) ?: MaterialTheme.colorScheme.surfaceVariant
    Text(
        text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/**
 * A publisher as the website lists it: a large rounded white card with the name in capitals, a status chip under it (where the account stands with its temporary password),
 * then "Details", the edit pencil and the more-actions menu on the right.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PublisherWebCard(
    name: String,
    passwordStatus: PasswordStatus?,
    removed: Boolean,
    duplicateOf: String?,
    readOnly: Boolean,
    onDetails: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name.uppercase(), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                val hasChips = passwordStatus != null || removed || duplicateOf != null
                if (hasChips) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    passwordStatus?.let {
                        WebChip(it.label, when (it) {
                            PasswordStatus.TEMP_UNUSED, PasswordStatus.TEMP_USED -> chipColor(false)
                            PasswordStatus.PASSWORD_CHANGED -> chipColor(true)
                            PasswordStatus.DISABLED -> null
                        })
                    }
                    if (removed) WebChip("Removed publisher", chipColor(null, danger = true))
                    if (duplicateOf != null) WebChip("Possible duplicate", chipColor(false))
                }
            }
            TextButton(onClick = onDetails, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                Text("Details", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            if (!readOnly) {
                IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More actions", tint = MaterialTheme.colorScheme.onSurface) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (!removed) {
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; onDelete() },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Restore") },
                                leadingIcon = { Icon(Icons.Rounded.RestoreFromTrash, contentDescription = null) },
                                onClick = { menu = false; onRestore() },
                            )
                        }
                    }
                }
            }
        }
    }
}

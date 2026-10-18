package com.emfitsolutions.gopreach.ui.screens.elders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One simple, scannable person row for the Elders and Ministerial Servants lists: an initials avatar, the name, one quiet
 * line of detail, the status, and a single Edit button. Everything else (delete / restore / temporary sign-in) sits in
 * one "more" menu so a first-time user sees one obvious action per record instead of a column of icons and small print.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonRecordCard(
    name: String,
    detail: String,
    /** Lines of [detail] shown before it is cut off (the Publisher card shows all of a publisher's data). */
    detailMaxLines: Int = 4,
    /** When set, the card shows only the name and a "View details" button beside it; the details open from that button. */
    onViewDetails: (() -> Unit)? = null,
    active: Boolean,
    readOnly: Boolean,
    hasTemporarySignIn: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    onShowSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    chips: List<String> = emptyList(),
    /** A short warning shown under the details (for example a possible duplicate). */
    note: String? = null,
    /** Replaces the initials avatar's tint (for example a Field Service Group's own colour). */
    avatarColor: androidx.compose.ui.graphics.Color? = null,
    /** False hides Delete / Restore (for a user who may edit this record but not remove it). */
    canRemove: Boolean = true,
    /** Extra items for the "more" menu (each must call [dismiss] when chosen). */
    menuExtras: @Composable (dismiss: () -> Unit) -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    val initials = name.split(',', ' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    OutlinedCard(modifier = modifier.fillMaxWidth().clickable(enabled = !readOnly, onClick = onEdit)) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The simple card has no avatar, so the whole name has room.
            if (onViewDetails == null) Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(avatarColor ?: MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(initials, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = if (onViewDetails != null) 2 else 1, overflow = TextOverflow.Ellipsis)
                if (onViewDetails == null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = detailMaxLines, overflow = TextOverflow.Ellipsis)
                if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                // The simple card shows the name only; an inactive (removed) record is still marked so it is not mistaken for an active one.
                if (onViewDetails == null || !active) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                    if (onViewDetails == null || !active) StatusPill(if (active) "Active" else "Inactive", good = active)
                    if (onViewDetails == null) chips.forEach { StatusPill(it, good = null) }
                }
            }
            if (onViewDetails != null) androidx.compose.material3.TextButton(onClick = onViewDetails, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) { Text("Details", style = MaterialTheme.typography.labelLarge, maxLines = 1) }
            if (!readOnly) {
                IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        menuExtras { menu = false }
                        if (hasTemporarySignIn) {
                            DropdownMenuItem(
                                text = { Text("View temporary sign-in") },
                                leadingIcon = { Icon(Icons.Rounded.VpnKey, contentDescription = null) },
                                onClick = { menu = false; onShowSignIn() },
                            )
                        }
                        if (!canRemove) {
                        } else if (active) {
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
            } else if (hasTemporarySignIn) {
                IconButton(onClick = onShowSignIn) { Icon(Icons.Rounded.VpnKey, contentDescription = "View temporary sign-in") }
            }
        }
    }
}

/** A small rounded label: green-ish primary for Active, red for Inactive, neutral for role chips. */
@Composable
private fun StatusPill(text: String, good: Boolean?) {
    val (container, content) = when (good) {
        true -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        false -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        null -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(container).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

package com.emfitsolutions.gopreach.ui.components.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.data.model.DrawingStatus
import com.emfitsolutions.gopreach.data.model.DrawingSyncState
import com.emfitsolutions.gopreach.data.model.TerritoryDrawing
import java.text.DateFormat
import java.util.Date

private fun hexColor(hex: String): Color = Color(runCatching { android.graphics.Color.parseColor(hex) }.getOrDefault(android.graphics.Color.GRAY))

/** The status color dot used everywhere a status is shown, so green/amber/red always mean the same thing. */
@Composable
fun StatusDot(status: DrawingStatus?, size: androidx.compose.ui.unit.Dp = 14.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(hexColor(status?.colorHex ?: UNSET_STATUS_COLOR)).border(1.dp, Color.White.copy(alpha = 0.9f), CircleShape))
}

/**
 * The drawing dock — a panel BELOW the map (not over it), so the map canvas stays unobstructed while drawing: the
 * "DRAWING MODE" strip with Hide/Show Markers, the Polygon Lasso tools (Undo, Redo, Remove Last/Selected Point, Clear),
 * Finish, then — once the polygon is closed — the Drawing Name, the required Territory Status, opacity and Remarks,
 * and Cancel / Save.
 */
@Composable
fun DrawingDock(state: MapDrawingState, modifier: Modifier = Modifier) {
    if (!state.active) return
    val active = state.activeItem
    val placing = state.isPlacingCorners
    val hint = when {
        state.tool == DrawingTool.PAN -> "Move and zoom the map freely. Tap Polygon to place corners."
        active == null -> "Tap the map to place the first corner. Each new corner is joined to the last by a straight line."
        placing && active.ring.size < 3 -> "Add at least 3 corner points to create the territory."
        placing -> "Keep tapping corners, then tap Finish to close the polygon."
        else -> "Drag a corner to move it. Tap a corner to select it, or a small dot on a line to add a corner."
    }
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp, modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFB3261E)) {
                    Text("POLYGON LASSO", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
                Spacer(Modifier.weight(1f))
                // Hide / Show Markers without leaving Draw Mode (map position, selection and drawing progress untouched).
                androidx.compose.material3.TextButton(onClick = { state.markersHidden = !state.markersHidden }) {
                    Icon(if (state.markersHidden) androidx.compose.material.icons.Icons.Rounded.Visibility else androidx.compose.material.icons.Icons.Rounded.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(if (state.markersHidden) "  Show Markers" else "  Hide Markers", style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(hint, style = MaterialTheme.typography.bodySmall, color = if (placing && (active?.ring?.size ?: 0) < 3 && active != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                DockButton(
                    icon = Icons.Rounded.Gesture,
                    label = "Polygon",
                    selected = state.tool == DrawingTool.POINTS,
                    onClick = { state.tool = if (state.tool == DrawingTool.POINTS) DrawingTool.PAN else DrawingTool.POINTS },
                )
                DockButton(icon = Icons.AutoMirrored.Rounded.Undo, label = "Undo", enabled = state.canUndo, onClick = { state.undo() })
                DockButton(icon = Icons.AutoMirrored.Rounded.Redo, label = "Redo", enabled = state.canRedo, onClick = { state.redo() })
                DockButton(
                    icon = Icons.AutoMirrored.Rounded.Backspace,
                    label = if (state.selectedVertex != null && active?.closed == true) "Remove" else "Last Point",
                    enabled = active != null && (if (active.closed) state.selectedVertex != null && active.ring.size > 3 else active.ring.isNotEmpty()),
                    onClick = { if (active?.closed == true) state.removeSelectedCorner() else state.removeLastCorner() },
                )
                DockButton(icon = Icons.Rounded.DeleteSweep, label = "Clear", enabled = state.hasDraft, onClick = { state.clear() })
            }
            if (placing) {
                Button(onClick = { state.finishHandler?.invoke() }, enabled = state.canFinish, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Finish — close the polygon")
                }
            }
            if (active != null && !placing) {
                OutlinedTextField(
                    value = active.name,
                    onValueChange = { state.setName(it.take(120)) },
                    label = { Text("Drawing name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Territory Status", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    DrawingStatus.entries.forEach { s ->
                        val selected = active.status == s
                        Surface(
                            onClick = { state.selectStatus(s) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) hexColor(s.colorHex).copy(alpha = 0.25f) else Color.Transparent,
                            border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) hexColor(s.borderHex) else MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                StatusDot(s, 14.dp)
                                Text(s.label, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }
                if (active.status == null) Text("Required — pick one to save.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Opacity", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
                    Slider(
                        value = active.opacity,
                        onValueChange = { state.selectOpacity(it) },
                        onValueChangeFinished = { state.commitOpacity() },
                        valueRange = 0.05f..0.7f,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${(active.opacity * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedTextField(
                    value = active.remarks,
                    onValueChange = { state.setRemarks(it.take(1000)) },
                    label = { Text("Notes / remarks (optional)") },
                    placeholder = { Text("e.g. Covered the eastern portion. Continue from the main road next time.") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { state.cancelHandler?.invoke() }, enabled = !state.saving, modifier = Modifier.weight(1f).height(44.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Cancel")
                }
                Button(onClick = { state.saveHandler?.invoke() }, enabled = state.hasDraft && !placing && !state.saving, modifier = Modifier.weight(1f).height(44.dp)) {
                    if (state.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Save")
                }
            }
        }
    }
}

@Composable
private fun DockButton(icon: ImageVector, label: String, onClick: () -> Unit, selected: Boolean = false, enabled: Boolean = true) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.size(width = 56.dp, height = 50.dp)) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(34.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            ),
        ) { Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp)) }
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
    }
}

/**
 * "Territory Status" as ONE compact row in the control area ABOVE the map (never over it): a color dot, label and
 * live count per status, tappable as the status filter (tap again, or "All", to clear).
 */
@Composable
fun StatusBar(
    counts: Map<DrawingStatus, Int>,
    filter: DrawingStatus?,
    onFilter: (DrawingStatus?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Territory Status", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 2.dp))
        FilterChip(selected = filter == null, onClick = { onFilter(null) }, label = { Text("All", style = MaterialTheme.typography.labelMedium) })
        DrawingStatus.entries.forEach { s ->
            FilterChip(
                selected = filter == s,
                onClick = { onFilter(if (filter == s) null else s) },
                leadingIcon = { StatusDot(s, 12.dp) },
                label = { Text("${s.label} ${counts[s] ?: 0}", style = MaterialTheme.typography.labelMedium, maxLines = 1) },
            )
        }
    }
}

/** What the info panel offers the viewer for a selected drawing. */
data class DrawingPanelActions(
    val canManage: Boolean,
    val onEdit: () -> Unit,
    val onChangeStatus: (DrawingStatus) -> Unit,
    val onDelete: () -> Unit,
    val onRetrySync: () -> Unit,
    val onClose: () -> Unit,
)

/** The panel shown when an existing drawing is tapped: its status and remarks, who made it, where. */
@Composable
fun DrawingInfoPanel(
    drawing: TerritoryDrawing,
    groupName: String?,
    congregationName: String?,
    syncState: DrawingSyncState,
    actions: DrawingPanelActions,
    modifier: Modifier = Modifier,
) {
    val dateTime = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    var statusMenu by remember { mutableStateOf(false) }

    Surface(shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 16.dp, bottomEnd = 16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp, modifier = modifier) {
        Column(Modifier.padding(16.dp).heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(drawing.status, 16.dp)
                Text(drawing.name.ifBlank { "Territory Drawing" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = actions.onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SyncChip(syncState)
                // Seeing a drawing is not permission to change it: no edit controls below for these.
                if (!actions.canManage) Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text("View Only", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
                }
            }
            HorizontalDivider()
            InfoLine("Status", "${drawing.status.label} — ${drawing.status.meaning}")
            InfoLine("Remarks", drawing.remarks.ifBlank { "—" })
            InfoLine("Territory", drawing.territoryName.ifBlank { "Unassigned area" })
            InfoLine("FS Group", groupName ?: "None (not in an FS Group territory)")
            InfoLine("Congregation", congregationName ?: "—")
            InfoLine("Opacity", "${(drawing.fillOpacity * 100).toInt()}%")
            InfoLine("Created by", listOf(drawing.userName, drawing.userRole).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" })
            InfoLine("Created", dateTime.format(Date(drawing.createdAt)))
            InfoLine("Last modified", dateTime.format(Date(drawing.updatedAt)) + drawing.updatedByName.takeIf { it.isNotBlank() }?.let { " by $it" }.orEmpty())
            if (syncState == DrawingSyncState.SYNC_FAILED) {
                OutlinedButton(onClick = actions.onRetrySync, modifier = Modifier.fillMaxWidth()) { Text("Retry Sync") }
            }
            if (actions.canManage) {
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = actions.onEdit, modifier = Modifier.weight(1f)) { Text("Edit", maxLines = 1) }
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { statusMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("Change Status", maxLines = 1) }
                        DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                            DrawingStatus.entries.forEach { s ->
                                DropdownMenuItem(
                                    leadingIcon = { StatusDot(s) },
                                    text = { Text(s.label) },
                                    onClick = { statusMenu = false; actions.onChangeStatus(s) },
                                )
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = actions.onDelete,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(width = 104.dp, height = 20.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** "Synced / Pending Sync / Syncing / Sync Failed" badge. */
@Composable
fun SyncChip(state: DrawingSyncState) {
    val (bg, fg) = when (state) {
        DrawingSyncState.SYNCED -> Color(0xFFE6F4EA) to Color(0xFF137333)
        DrawingSyncState.SYNCING -> Color(0xFFE8F0FE) to Color(0xFF1A73E8)
        DrawingSyncState.PENDING_SYNC -> Color(0xFFFEF7E0) to Color(0xFF9A6700)
        DrawingSyncState.SYNC_FAILED -> Color(0xFFFCE8E6) to Color(0xFFB3261E)
    }
    Surface(shape = RoundedCornerShape(50), color = bg) {
        Text(state.label, color = fg, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

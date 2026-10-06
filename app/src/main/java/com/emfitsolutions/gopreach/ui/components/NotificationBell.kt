package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.data.repository.NotificationCategory
import com.emfitsolutions.gopreach.ui.screens.notifications.NotificationItem

private fun NotificationCategory.icon(): ImageVector = when (this) {
    NotificationCategory.TRANSFER_REQUEST -> Icons.Rounded.SwapHoriz
    NotificationCategory.MONTHLY_REPORT -> Icons.AutoMirrored.Rounded.Assignment
    NotificationCategory.ANNOUNCEMENT -> Icons.Rounded.Campaign
    NotificationCategory.CALENDAR_SCHEDULE -> Icons.Rounded.CalendarMonth
    // Never actually reaches this balloon (see NotificationCategory.MESSAGE's
    // own doc comment) — exhaustive branch only, for when the shared enum
    // changes.
    NotificationCategory.MESSAGE -> Icons.Rounded.ChatBubble
}

/**
 * The unified notification balloon (spec: "Add a notification balloon for
 * Service Overseer, Elders, Admin, publisher and super admin") — a bell icon
 * with an unseen-count badge that opens a dropdown of every
 * [NotificationItem] the caller passed in, each tappable through to wherever
 * it's actually handled (see [com.emfitsolutions.gopreach.ui.screens
 * .notifications.NotificationCenterViewModel]'s doc comment for the four
 * event sources and their routes). Congregation/role scoping already
 * happened by the time [items] reaches here — this component only renders.
 */
@Composable
fun NotificationBell(
    items: List<NotificationItem>,
    unseenCount: Int,
    onOpen: () -> Unit,
    onItemClick: (NotificationItem) -> Unit,
    /** "Delete Notification" — dismisses one item; disappears from this
     * device's balloon only, never touches the underlying record. Optional
     * so existing call sites keep compiling unchanged; omit to hide the
     * per-row dismiss affordance entirely. */
    onDismiss: ((NotificationItem) -> Unit)? = null,
    /** "Clear Old Notifications" / a "Clear All" action — dismisses every
     * currently-listed item at once. */
    onClearAll: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    iconTint: Color = Color.White,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true; onOpen() }) {
            BadgedBox(badge = {
                if (unseenCount > 0) Badge { Text(if (unseenCount > 99) "99+" else unseenCount.toString()) }
            }) {
                Icon(Icons.Rounded.Notifications, contentDescription = "Notifications", tint = iconTint)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 280.dp, max = 340.dp),
        ) {
            if (items.isEmpty()) {
                Text(
                    "No notifications yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).imePadding()) {
                    if (onClearAll != null) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { onClearAll(); expanded = false }) { Text("Clear All") }
                        }
                        HorizontalDivider()
                    }
                    items.forEach { item ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(
                                        item.category.icon(),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 2.dp, end = 8.dp),
                                    )
                                    // weight(1f) so this column is actually
                                    // constrained to the space left after the
                                    // icon, instead of measuring against the
                                    // dropdown's full width and overlapping it.
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            item.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (item.subtitle.isNotBlank()) {
                                            Text(
                                                item.subtitle,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Text(
                                            formatRecordTimestamp(item.timestamp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    // "Delete Notification" — a small dismiss
                                    // affordance per row, distinct from tapping
                                    // the row itself (which navigates instead).
                                    if (onDismiss != null) {
                                        IconButton(onClick = { onDismiss(item) }, modifier = Modifier.size(24.dp)) {
                                            Icon(
                                                Icons.Rounded.Close,
                                                contentDescription = "Dismiss notification",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            },
                            onClick = { expanded = false; onItemClick(item) },
                        )
                    }
                }
            }
        }
    }
}

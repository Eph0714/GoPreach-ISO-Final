package com.emfitsolutions.gopreach.ui.components

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The side panel's navigation groups, shared by EVERY role's drawer (admin-track roles and publishers alike): one visual language -
 * each group has its own icon and a subtle accent colour (used only for small details, never large backgrounds). Which modules a role
 * sees in a group is decided by that role's own permission checks; the group only decides where an already-authorized module is shown.
 */
enum class NavGroup(val title: String, val icon: ImageVector, private val light: Color, private val dark: Color) {
    CONTROL_PANEL("CONTROL PANEL", Icons.Rounded.Dashboard, Color(0xFFD99A00), Color(0xFFF2B705)),
    INPUT("INPUT", Icons.Rounded.EditNote, Color(0xFF0097A7), Color(0xFF4DD0E1)),
    RECORD("RECORD", Icons.Rounded.FolderOpen, Color(0xFF2E7D32), Color(0xFF81C784)),
    REPORTS("REPORTS", Icons.Rounded.Assessment, Color(0xFF7B1FA2), Color(0xFFCE93D8)),
    MANAGEMENT("MANAGEMENT", Icons.Rounded.ManageAccounts, Color(0xFFEF6C00), Color(0xFFFFB74D)),
    SETTINGS("SETTINGS", Icons.Rounded.Settings, Color(0xFF616161), Color(0xFFBDBDBD)),
    TOOLS("TOOLS", Icons.Rounded.Build, Color(0xFF00796B), Color(0xFF4DB6AC)),
    ;

    /** The accent for the current light / dark appearance (kept readable in both). */
    @Composable
    fun accent(): Color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) dark else light
}

/** True when the user turned system animations off: group expansion then switches state without moving anything. */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
}

/**
 * One collapsible group: a header (group icon, title, a dot when the active module is inside, a chevron that rotates) and its members,
 * which fade, slide and grow into view only when the header is tapped. Starting state and toggling belong to the caller, so opening
 * one group never opens another. The header is a real button for accessibility ("Expanded" / "Collapsed" is announced).
 */
@Composable
fun NavGroupSection(
    group: NavGroup,
    expanded: Boolean,
    containsActive: Boolean,
    itemCount: Int,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val accent = group.accent()
    val reduced = rememberReducedMotion()
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, if (reduced) tween(0) else tween(220, easing = FastOutSlowInEasing), label = "navChevron")
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = if (expanded) "Collapse " + group.title else "Expand " + group.title, role = Role.Button, onClick = onToggle)
                .semantics {
                    contentDescription = group.title + ", " + itemCount + (if (itemCount == 1) " module" else " modules") + if (containsActive) ", contains the current page" else ""
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(group.icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            }
            Text(
                group.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (containsActive) FontWeight.ExtraBold else FontWeight.Bold,
                letterSpacing = androidx.compose.ui.unit.TextUnit(0.8f, androidx.compose.ui.unit.TextUnitType.Sp),
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            // Not colour alone: a dot marks the group that holds the page you are on, even while it is collapsed.
            if (containsActive) Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.rotate(chevron))
        }
        AnimatedVisibility(
            visible = expanded,
            enter = if (reduced) EnterTransition.None else expandVertically(tween(220, easing = FastOutSlowInEasing)) + fadeIn(tween(200)) + slideInVertically(tween(220, easing = FastOutSlowInEasing)) { -it / 10 },
            exit = if (reduced) ExitTransition.None else shrinkVertically(tween(200, easing = FastOutSlowInEasing)) + fadeOut(tween(140)) + slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { -it / 10 },
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), content = content)
        }
    }
}

/**
 * One module inside a group: indented, 48dp tall, the icon in the group's accent, one line of label, an optional badge. The page you are
 * on gets a soft background, a bold label and a left accent bar (so it is recognisable without relying on colour).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun NavMenuRow(
    icon: ImageVector,
    label: String,
    group: NavGroup,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: Int = 0,
    onLongClick: (() -> Unit)? = null,
) {
    val accent = group.accent()
    val clickable = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
    } else Modifier.clickable(role = Role.Button, onClick = onClick)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.14f) else Color.Transparent)
            .then(clickable)
            .semantics { this.selected = selected },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().padding(vertical = 8.dp).background(if (selected) accent else Color.Transparent, RoundedCornerShape(2.dp)))
        Row(
            Modifier.padding(start = 28.dp, end = 12.dp).weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = null, tint = accent.copy(alpha = if (selected) 1f else 0.85f), modifier = Modifier.size(22.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (badge > 0) Badge { Text(badge.toString()) }
        }
    }
}

/** The panel's top: the app identity and the signed-in user (picture or initials, name, role, scope), compact and visually apart from the groups. */
@Composable
fun NavProfileHeader(
    appName: String,
    userName: String,
    roleLabel: String,
    scopeLabel: String,
    profileImageUrl: String?,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFFF2B705)), contentAlignment = Alignment.Center) {
                Text(appName.take(1).uppercase(), fontWeight = FontWeight.ExtraBold, color = Color.Black)
            }
            Text(appName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        if (userName.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    if (!profileImageUrl.isNullOrBlank()) {
                        coil.compose.AsyncImage(model = profileImageUrl, contentDescription = null, modifier = Modifier.size(44.dp).clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    } else {
                        Text(
                            userName.split(' ', ',').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() },
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(userName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (roleLabel.isNotBlank()) Text(roleLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (scopeLabel.isNotBlank()) Text(scopeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

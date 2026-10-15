package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emfitsolutions.gopreach.ui.components.co.CoCard
import kotlinx.coroutines.delay

/** Fades and lifts its content in once, a few milliseconds after the previous item ([index]), so a grid appears smoothly instead of popping in. */
@Composable
internal fun AppearIn(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(index * 35L); shown = true }
    val progress by animateFloatAsState(if (shown) 1f else 0f, tween(260), label = "appear")
    Box(modifier.graphicsLayer { alpha = progress; translationY = (1f - progress) * 16f }) { content() }
}

/** A small, plain icon in the item's color code, on a faint tint of the same color - no heavy filled box. */
@Composable
internal fun OverviewIconBadge(icon: ImageVector, color: Color, size: Int = 28) {
    Box(Modifier.size(size.dp).background(color.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size((size * 0.6f).dp))
    }
}

/**
 * One statistic of the Circuit Overview, on a single row: icon, its name, and the quantity (bold, right-aligned). Under it, when known, how the
 * figure changed from the previous month. It only displays; navigation lives in the shortcuts below. A figure that is not available shows "—",
 * never a zero, and no change is shown unless the previous month is known for every congregation.
 */
@Composable
internal fun OverviewStatCard(icon: ImageVector, color: Color, label: String, value: Int?, modifier: Modifier = Modifier, change: Int? = null) {
    val animated by animateIntAsState(value ?: 0, animationSpec = tween(600), label = "stat")
    Column(
        modifier = modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverviewIconBadge(icon, color, size = 24)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(if (value == null) "—" else "%,d".format(animated), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
        }
        if (value != null && change != null) {
            val text = when {
                change > 0 -> "+$change vs last month"
                change < 0 -> "$change vs last month"
                else -> "No change"
            }
            val tone = when { change > 0 -> Color(0xFF2E7D32); change < 0 -> Color(0xFFC62828); else -> MaterialTheme.colorScheme.onSurfaceVariant }
            Text(text, style = MaterialTheme.typography.labelSmall, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 32.dp))
        }
    }
}

/** A navigation shortcut of the Circuit Overview: icon, short title and a one-line caption. Press feedback and ripple come from [CoCard]. */
@Composable
internal fun OverviewShortcut(icon: ImageVector, color: Color, title: String, caption: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    CoCard(modifier.heightIn(min = 60.dp), onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            OverviewIconBadge(icon, color, size = 28)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The header's notification indicator: how many submitted reports the overseer has not opened yet. Tapping it opens Received Reports. */
@Composable
internal fun NewReportsIndicator(count: Int, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.NotificationsActive, contentDescription = "New reports", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
        Text(if (count > 99) "99+" else count.toString(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

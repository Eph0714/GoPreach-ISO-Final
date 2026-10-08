package com.emfitsolutions.gopreach.ui.components.co

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.ui.components.map.HideSystemBarsEffect
import com.emfitsolutions.gopreach.ui.screens.circuit.CongregationQuickStats

/*
 * The Circuit Overseer account's one design system. Every CO screen takes its colors, buttons, icons, cards, headers, search box,
 * status badges, congregation picker and full-screen control from here, so the same action always looks the same.
 *
 *   Icons    : Material Icons "Rounded" only (one family, one stroke weight), 20dp in buttons, 24dp for navigation.
 *   Buttons  : [CoButton] - one shape (12dp corners), one height (48dp), icon + label.
 *   Colors   : [CoPalette] - the app's primary accent plus fixed semantic success / warning / danger, with a lighter set on dark surfaces.
 */

/** The meaning of a color — never pick a color for an individual button, pick the kind. */
enum class CoKind { Primary, Secondary, Success, Warning, Danger, Neutral }

/** Semantic colors that stay readable on both light and dark surfaces. */
class CoPalette(val primary: Color, val success: Color, val warning: Color, val danger: Color, val onFilled: Color)

@Composable
fun coPalette(): CoPalette {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) {
        CoPalette(MaterialTheme.colorScheme.primary, Color(0xFF66BB6A), Color(0xFFFFB74D), Color(0xFFEF9A9A), Color(0xFF10131A))
    } else {
        CoPalette(MaterialTheme.colorScheme.primary, Color(0xFF2E7D32), Color(0xFFEF6C00), Color(0xFFC62828), Color.White)
    }
}

@Composable
fun CoKind.color(): Color {
    val p = coPalette()
    return when (this) {
        CoKind.Primary -> p.primary
        CoKind.Secondary -> MaterialTheme.colorScheme.secondary
        CoKind.Success -> p.success
        CoKind.Warning -> p.warning
        CoKind.Danger -> p.danger
        CoKind.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private val ButtonShape = RoundedCornerShape(12.dp)
private val ButtonHeight = 48.dp

/**
 * The only button of the Circuit Overseer account.
 *  - Primary / Success / Warning / Danger : filled, strong color, high-contrast label (Open, Save, Submit, Compare, Assign, Transfer / Approve / Pending / Delete).
 *  - Secondary                            : tonal (Change Congregation, Filter, View, Refresh, Search, Preview).
 *  - Neutral                              : outlined (Cancel, Back).
 * [fillWidth] gives a full-width button; otherwise it wraps its label and never wraps the label onto two lines.
 */
@Composable
fun CoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: CoKind = CoKind.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    fillWidth: Boolean = false,
) {
    val p = coPalette()
    val mod = modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier).heightIn(min = ButtonHeight)
    val padding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
    }
    when (kind) {
        CoKind.Secondary -> FilledTonalButton(onClick, mod, enabled, ButtonShape, contentPadding = padding, content = content)
        CoKind.Neutral -> OutlinedButton(onClick, mod, enabled, ButtonShape, contentPadding = padding, content = content)
        else -> Button(
            onClick, mod, enabled, ButtonShape,
            colors = ButtonDefaults.buttonColors(containerColor = kind.color(), contentColor = p.onFilled),
            contentPadding = padding, content = content,
        )
    }
}

/** A card with the one corner radius, border and padding used everywhere; clickable when [onClick] is given. */
@Composable
fun CoCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, tween(90), label = "press")
    Card(
        modifier = modifier.scale(scale).clip(shape).then(if (onClick != null) Modifier.clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), onClick = onClick) else Modifier),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, accent?.copy(alpha = 0.55f) ?: MaterialTheme.colorScheme.outlineVariant),
    ) { content() }
}

/** A small status chip. */
@Composable
fun CoStatusBadge(text: String, kind: CoKind, modifier: Modifier = Modifier) {
    val c = kind.color()
    Text(
        text, modifier = modifier.clip(CircleShape).background(c.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = c, maxLines = 1,
    )
}

/** The one search box: fixed height and radius, search icon, clear button once something is typed. */
@Composable
fun CoSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, singleLine = true, modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, maxLines = 1) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") } },
        shape = RoundedCornerShape(12.dp),
    )
}

/**
 * One congregation as a selectable card: icon, name, "82 Publishers · 9 Elders · 6 Groups", chevron.
 * [stats] is null while counts are not known (the line is then left out rather than showing zeros).
 */
@Composable
fun CoCongregationCard(congregation: Congregation, stats: CongregationQuickStats?, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    val primary = coPalette().primary
    CoCard(modifier = modifier.fillMaxWidth(), onClick = onClick, accent = if (selected) primary else null) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Business, contentDescription = null, tint = primary) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(congregation.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (stats != null) {
                    Text(
                        "${stats.publishers} Publishers · ${stats.elders} Elders · ${stats.groups} Groups",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * "SELECT CONGREGATION": the visual list every congregation-specific CO module shows first. It is only ever given the congregations
 * this account may see (the security rules already keep every other congregation off the device), and it is never a dropdown.
 */
@Composable
fun CoCongregationPicker(
    congregations: List<Congregation>,
    stats: Map<String, CongregationQuickStats>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "SELECT CONGREGATION",
    hint: String? = null,
) {
    var query by remember { mutableStateOf("") }
    val shown = congregations.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }.sortedBy { it.name.lowercase() }
    Column(modifier = modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = coPalette().primary)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (congregations.size > 5) CoSearchField(query, { query = it }, "Search congregation...")
        if (congregations.isEmpty()) {
            Text("No congregations are assigned to this account yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (shown.isEmpty()) {
            Text("No congregation matches your search.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Inside a screen that gives a bounded height the list scrolls by itself; inside one that does not, the cards simply stack.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (constraints.hasBoundedHeight) {
                LazyColumn(Modifier.heightIn(max = maxHeight), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.id }) { c -> CoCongregationCard(c, stats[c.id], onClick = { onSelect(c.id) }) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.forEach { c -> CoCongregationCard(c, stats[c.id], onClick = { onSelect(c.id) }) }
                }
            }
        }
    }
}

/**
 * The header every congregation-specific module shows once a congregation is chosen: module title, the congregation (prominent),
 * an optional supporting line, and [Change Congregation] plus any extra [actions] (Compare, Print, full screen ...).
 */
@Composable
fun CoModuleHeader(
    congregationName: String,
    onChangeCongregation: (() -> Unit)?,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val primary = coPalette().primary
    Column(modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) Text(title.uppercase(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = primary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Business, contentDescription = null, tint = primary, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(congregationName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onChangeCongregation != null) CoButton("Change Congregation", onChangeCongregation, kind = CoKind.Secondary, icon = Icons.Rounded.SwapHoriz)
            actions()
        }
    }
}

/** The universal full-screen control for a module header: Full Screen <-> Exit Full Screen. */
@Composable
fun CoFullScreenButton(active: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (active) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
            contentDescription = if (active) "Exit Full Screen" else "Full Screen",
        )
    }
}

/** Hides the system bars while [active]; pair it with [CoFullScreenButton]. */
@Composable
fun CoFullScreenEffect(active: Boolean) = HideSystemBarsEffect(active)

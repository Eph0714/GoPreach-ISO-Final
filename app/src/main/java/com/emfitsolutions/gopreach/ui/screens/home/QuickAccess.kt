package com.emfitsolutions.gopreach.ui.screens.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.AssignmentInd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HowToReg
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PinDrop
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.DashboardModuleLayout
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.repository.DashboardModuleLayoutRepository
import com.emfitsolutions.gopreach.ui.components.SideItem
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.navigation.Destinations
import com.emfitsolutions.gopreach.ui.screens.dashboard.CongregationStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The built-in Quick Access cards, in the default order. [isStat] cards show a live record count instead of a description. */
enum class QuickAccessItem(
    val label: String,
    val description: String,
    val icon: ImageVector,
    val accent: Color,
    val isStat: Boolean,
    val route: String,
    val category: PublisherCategory? = null,
) {
    TERRITORY_ASSIGNMENT("Territory Assignment", "Manage and assign territories", Icons.Rounded.PinDrop, Color(0xFF00897B), false, Destinations.MANAGE_TERRITORY_ASSIGNMENTS),
    TERRITORY_MAP("Territory Map", "View territory locations", Icons.Rounded.Map, Color(0xFF558B2F), false, Destinations.MANAGE_TERRITORIES_BASE),
    PUBLISHER_ASSIGNMENT("Publisher Assignment", "Manage Searching, Return Visit, Bible Study and Publisher assignments", Icons.Rounded.AssignmentInd, Color(0xFF3F51B5), false, Destinations.PUBLISHER_ASSIGNMENT),
    PUBLISHER_MODULE("Publisher Module", "Manage publishers", Icons.Rounded.Groups, Color(0xFF1E88E5), false, Destinations.MANAGE_PUBLISHERS),
    FIELD_SERVICE_REPORT("Field Service Report", "Open the field service report", Icons.Rounded.Assessment, Color(0xFFEF6C00), false, Destinations.FIELD_SERVICE_REPORT),
    COMPARATIVE_REPORT("Comparative Report", "Compare report periods", Icons.AutoMirrored.Rounded.CompareArrows, Color(0xFF6D4C41), false, Destinations.COMPARATIVE_REPORT),
    REGULAR_PIONEERS("Regular Pioneers", "Regular pioneer records", Icons.Rounded.Star, Color(0xFFC79100), true, Destinations.MANAGE_PUBLISHERS, PublisherCategory.REGULAR_PIONEER),
    AUXILIARY_PIONEERS("Auxiliary Pioneer", "Auxiliary pioneer records", Icons.Rounded.EventAvailable, Color(0xFF8E24AA), true, Destinations.MANAGE_PUBLISHERS, PublisherCategory.AUXILIARY_PIONEER),
    SPECIAL_PIONEERS("Special Pioneers", "Special pioneer records", Icons.Rounded.WorkspacePremium, Color(0xFFD81B60), true, Destinations.MANAGE_PUBLISHERS, PublisherCategory.SPECIAL_PIONEER),
    UNBAPTIZED_PUBLISHERS("Unbaptized Publishers", "Unbaptized publisher records", Icons.Rounded.PersonAdd, Color(0xFF43A047), true, Destinations.MANAGE_PUBLISHERS, PublisherCategory.UNBAPTIZED_PUBLISHER),
    TOTAL_ELDERS("Total Elders", "Elder records", Icons.Rounded.Shield, Color(0xFF546E7A), true, Destinations.MANAGE_ELDERS),
    TOTAL_MINISTERIAL("Total Ministerial", "Ministerial servant records", Icons.Rounded.HowToReg, Color(0xFF00ACC1), true, Destinations.MANAGE_MINISTERIAL_SERVANTS),
    ;

    fun count(stats: CongregationStats): Int = when (this) {
        REGULAR_PIONEERS -> stats.regularPioneers
        AUXILIARY_PIONEERS -> stats.auxiliaryPioneers
        SPECIAL_PIONEERS -> stats.specialPioneers
        UNBAPTIZED_PUBLISHERS -> stats.unbaptizedPublishers
        TOTAL_ELDERS -> stats.totalElders
        TOTAL_MINISTERIAL -> stats.totalMinisterial
        else -> 0
    }
}

/** One card on the grid: a built-in item or any module copied from the side panel. Opening it is plain navigation. */
data class QuickAccessEntry(
    val id: String,
    val label: String,
    val description: String,
    val icon: ImageVector,
    val accent: Color,
    val route: String,
    val category: PublisherCategory? = null,
    val statCount: ((CongregationStats) -> Int)? = null,
)

private const val CUSTOM_PREFIX = "ROUTE:"
private const val DROP_PLACEHOLDER = "__drop__"

private fun QuickAccessItem.toEntry() = QuickAccessEntry(name, label, description, icon, accent, route, category, if (isStat) ({ s -> count(s) }) else null)

/** The built-in card that stands for a route (so Territory Map dragged from the side panel is the same card, never a duplicate). */
private val builtInIdByRoute: Map<String, String> =
    QuickAccessItem.entries.filter { it.category == null }.associate { it.route to it.name }

private fun idForSideItem(item: SideItem): String = builtInIdByRoute[item.route] ?: (CUSTOM_PREFIX + item.route)

private fun SideItem.toEntry(id: String) = QuickAccessEntry(id, label, "Open $label", icon, Color(0xFF546E7A), route)

/**
 * Hoisted state for a drag that starts on a side-panel item and is dropped on the Quick Access grid. The side panel
 * writes [item]/[rootPos] (window coordinates) while the finger moves; [QuickAccessSection] reads them to highlight the
 * drop area and handles the release through [onDrop].
 */
class QuickAccessDragState {
    var item by mutableStateOf<SideItem?>(null)
    var rootPos by mutableStateOf(Offset.Zero)
    var onDrop: ((SideItem, Offset) -> Unit)? = null
}

/** Stores each account's Quick Access choice in its own dashboard-layout document: follows the account across logins and devices. */
class QuickAccessViewModel(
    private val repository: DashboardModuleLayoutRepository,
) : ViewModel() {
    fun layout(personId: String): Flow<DashboardModuleLayout> = repository.observeFor(personId)

    fun save(personId: String, ids: List<String>) {
        viewModelScope.launch { repository.saveQuickAccess(personId, ids, configured = true) }
    }

    fun reset(personId: String) {
        viewModelScope.launch { repository.saveQuickAccess(personId, emptyList(), configured = false) }
    }
}

private val CardHeight = 84.dp
private val Gap = 10.dp

/**
 * Admin dashboard "Quick Access": a per-user grid of shortcut cards (built-in modules and live record counts, plus any
 * module copied from the side panel by long-press-and-drag). Cards can be reordered by long-press drag and removed
 * with the X. It is only navigation: a card whose module the signed-in role cannot open is never shown, so a saved card
 * can never grant access — [allowed] covers the built-ins and [sideItems] (what the side panel currently offers) the rest.
 */
@Composable
fun QuickAccessSection(
    personId: String,
    stats: CongregationStats,
    allowed: Set<QuickAccessItem>,
    sideItems: List<SideItem>,
    dragState: QuickAccessDragState,
    onOpen: (QuickAccessEntry) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QuickAccessViewModel = koinViewModel(),
) {
    val layout by viewModel.layout(personId).collectAsStateWithLifecycle(initialValue = DashboardModuleLayout(personId = personId))

    val catalog: Map<String, QuickAccessEntry> = remember(allowed, sideItems) {
        buildMap {
            sideItems.forEach { item ->
                val id = idForSideItem(item)
                if (id.startsWith(CUSTOM_PREFIX)) put(id, item.toEntry(id))
            }
            allowed.forEach { put(it.name, it.toEntry()) }
        }
    }
    val defaultIds = remember(allowed) { QuickAccessItem.entries.filter { it in allowed }.map { it.name } }
    val fromSaved = remember(layout.quickAccessOrder, layout.quickAccessConfigured, defaultIds, catalog) {
        val saved = layout.quickAccessOrder
        val ids = when {
            layout.quickAccessConfigured -> saved
            // An order saved before customizing existed: keep it, and add the newer default cards after it.
            saved.isNotEmpty() -> saved + defaultIds.filter { it !in saved }
            else -> defaultIds
        }
        ids.filter { it in catalog }.distinct()
    }
    // Local copy while editing / just after a change; cleared once the saved order catches up.
    var local by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(fromSaved) { local = null }
    val order = local ?: fromSaved

    fun commit(ids: List<String>) {
        local = ids
        viewModel.save(personId, ids)
    }

    var dragging by remember { mutableStateOf<String?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var justDragged by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var gridOrigin by remember { mutableStateOf(Offset.Zero) }
    var gridSizePx by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val showToast = rememberActionToast()

    // Geometry shared by the internal drag and a drag arriving from the side panel.
    var cols by remember { mutableStateOf(2) }
    var cellWpx by remember { mutableStateOf(1f) }
    val cellHpx = with(density) { CardHeight.toPx() }
    val gapPx = with(density) { Gap.toPx() }

    fun indexAt(rootPos: Offset, count: Int): Int {
        val rel = rootPos - gridOrigin
        val col = (rel.x / (cellWpx + gapPx)).toInt().coerceIn(0, cols - 1)
        val row = (rel.y / (cellHpx + gapPx)).toInt().coerceAtLeast(0)
        return (row * cols + col).coerceIn(0, count)
    }

    fun overGrid(rootPos: Offset): Boolean {
        val margin = with(density) { 28.dp.toPx() }
        return rootPos.x in (gridOrigin.x - margin)..(gridOrigin.x + gridSizePx.x + margin) &&
            rootPos.y in (gridOrigin.y - margin)..(gridOrigin.y + gridSizePx.y + margin * 2)
    }

    val external = dragState.item
    val externalId = external?.let { idForSideItem(it) }
    val externalHover = external != null && overGrid(dragState.rootPos)
    val alreadyThere = externalId != null && externalId in order
    val hoverIndex = if (externalHover && !alreadyThere) indexAt(dragState.rootPos, order.size) else null

    val currentDrop by rememberUpdatedState<(SideItem, Offset) -> Unit> { item, pos ->
        if (overGrid(pos)) {
            val id = idForSideItem(item)
            if (id in catalog) {
                if (id in order) {
                    showToast("Already in Quick Access")
                } else {
                    commit(order.toMutableList().apply { add(indexAt(pos, order.size), id) })
                }
            }
        }
    }
    DisposableEffect(dragState) {
        dragState.onDrop = { item, pos -> currentDrop(item, pos) }
        onDispose { dragState.onDrop = null }
    }

    val zoneColor by animateColorAsState(
        when {
            external != null && externalHover -> MaterialTheme.colorScheme.primary
            external != null -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            else -> Color.Transparent
        },
        tween(160),
        label = "dropZone",
    )

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (external != null) {
            Text(
                if (alreadyThere && externalHover) "Already in Quick Access" else "Drop here to add \"${external.label}\" to Quick Access",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        } else if (!layout.quickAccessConfigured) {
            Text(
                "Tip: Long-press a module from the side panel and drag it to Quick Access.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val columnCount = when {
                maxWidth < 340.dp -> 1
                maxWidth < 600.dp -> 2
                maxWidth < 900.dp -> 3
                else -> 4
            }
            val cellW = (maxWidth - Gap * (columnCount - 1)) / columnCount
            val cellWPx = with(density) { cellW.toPx() }
            if (cols != columnCount) cols = columnCount
            if (cellWpx != cellWPx) cellWpx = cellWPx

            val slots: List<String> = remember(order, hoverIndex) {
                if (hoverIndex == null) order else order.toMutableList().apply { add(hoverIndex.coerceAtMost(size), DROP_PLACEHOLDER) }
            }
            val rows = (slots.size + columnCount - 1) / columnCount
            val totalHeight = if (rows == 0) CardHeight else CardHeight * rows + Gap * (rows - 1)

            fun slot(index: Int) = Offset((index % columnCount) * (cellWPx + gapPx), (index / columnCount) * (cellHpx + gapPx))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(totalHeight)
                    .onGloballyPositioned {
                        gridOrigin = it.positionInRoot()
                        gridSizePx = Offset(it.size.width.toFloat(), it.size.height.toFloat())
                    },
            ) {
                if (slots.isEmpty()) {
                    Text(
                        "Quick Access is empty. Long-press a module in the side panel and drag it here, or reset the layout.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                slots.forEachIndexed { index, id ->
                    androidx.compose.runtime.key(id) {
                        val isDragging = dragging == id
                        val target = slot(index)
                        val animated by animateIntOffsetAsState(
                            targetValue = IntOffset(target.x.roundToInt(), target.y.roundToInt()),
                            animationSpec = tween(220),
                            label = "quickAccessSlot",
                        )
                        val position = if (isDragging) IntOffset(dragPos.x.roundToInt(), dragPos.y.roundToInt()) else animated
                        if (id == DROP_PLACEHOLDER) {
                            DropPlaceholder(modifier = Modifier.offset { position }.width(cellW).height(CardHeight))
                        } else {
                            val entry = catalog[id]
                            if (entry != null) {
                                fun moveBy(delta: Int) {
                                    val from = order.indexOf(id)
                                    val to = (from + delta).coerceIn(0, order.lastIndex)
                                    if (from >= 0 && to != from) commit(order.toMutableList().apply { add(to, removeAt(from)) })
                                }

                                QuickAccessCard(
                                    entry = entry,
                                    count = entry.statCount?.invoke(stats),
                                    entranceDelayMillis = defaultIds.indexOf(id).coerceAtLeast(0) * 45L,
                                    isDragging = isDragging,
                                    onMoveEarlier = { moveBy(-1) },
                                    onMoveLater = { moveBy(1) },
                                    onRemove = { commit(order - id) },
                                    onClick = { if (!justDragged) onOpen(entry) },
                                    modifier = Modifier
                                        .offset { position }
                                        .width(cellW)
                                        .height(CardHeight)
                                        .zIndex(if (isDragging) 1f else 0f)
                                        .pointerInput(id) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    dragging = id
                                                    justDragged = true
                                                    dragPos = slot((local ?: fromSaved).indexOf(id))
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    dragPos += amount
                                                    val current = local ?: fromSaved
                                                    val from = current.indexOf(id)
                                                    val centerX = dragPos.x + cellWPx / 2
                                                    val centerY = dragPos.y + cellHpx / 2
                                                    val col = (centerX / (cellWPx + gapPx)).toInt().coerceIn(0, columnCount - 1)
                                                    val row = (centerY / (cellHpx + gapPx)).toInt().coerceAtLeast(0)
                                                    val to = (row * columnCount + col).coerceIn(0, current.lastIndex)
                                                    if (from >= 0 && to != from) local = current.toMutableList().apply { add(to, removeAt(from)) }
                                                },
                                                onDragEnd = {
                                                    dragging = null
                                                    viewModel.save(personId, local ?: fromSaved)
                                                    scope.launch { delay(250); justDragged = false }
                                                },
                                                onDragCancel = {
                                                    dragging = null
                                                    scope.launch { delay(250); justDragged = false }
                                                },
                                            )
                                        },
                                )
                            }
                        }
                    }
                }
            }
            // Highlighted outline while a side-panel module is being dragged toward the grid.
            if (external != null) {
                Box(modifier = Modifier.matchParentSize().border(2.dp, zoneColor, RoundedCornerShape(18.dp)))
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { showReset = true }) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Reset Quick Access Layout", style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("Reset Quick Access Layout?") },
            text = { Text("This will restore the default Quick Access items and their original order.") },
            confirmButton = {
                TextButton(onClick = {
                    local = defaultIds
                    viewModel.reset(personId)
                    showReset = false
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { showReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DropPlaceholder(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
        modifier = modifier,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text("Drop here", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun QuickAccessCard(
    entry: QuickAccessEntry,
    count: Int?,
    entranceDelayMillis: Long,
    isDragging: Boolean,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onRemove: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density

    // Staggered entrance: fade and rise into place, once per card.
    var entered by remember(entry.id) { mutableStateOf(false) }
    LaunchedEffect(entry.id) { delay(entranceDelayMillis); entered = true }
    val enterAlpha by animateFloatAsState(if (entered) 1f else 0f, tween(260), label = "enterAlpha")
    val enterRise by animateFloatAsState(if (entered) 0f else 24f, tween(260), label = "enterRise")

    val scale by animateFloatAsState(
        targetValue = when {
            isDragging -> 1.04f
            pressed -> 0.96f
            hovered || focused -> 1.02f
            else -> 1f
        },
        animationSpec = tween(120),
        label = "scale",
    )
    val elevation by animateDpAsState(
        targetValue = when {
            isDragging -> 14.dp
            hovered || focused -> 6.dp
            else -> 2.dp
        },
        animationSpec = tween(140),
        label = "elevation",
    )
    val iconLift by animateFloatAsState(if (hovered || focused || pressed) -2f else 0f, tween(140), label = "iconLift")

    val description = buildString {
        append(entry.label)
        if (count != null) append(", $count") else append(", ${entry.description}")
        append(". Opens ${entry.label}. Long press and drag to rearrange.")
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = elevation,
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) entry.accent else MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = enterRise
            }
            .alpha(enterAlpha)
            .semantics {
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction("Move earlier") { onMoveEarlier(); true },
                    CustomAccessibilityAction("Move later") { onMoveLater(); true },
                    CustomAccessibilityAction("Remove from Quick Access") { onRemove(); true },
                )
            }
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) {
                // A brief press-in before navigating, so the tap reads as a deliberate action.
                scope.launch { delay(70); onClick() }
            },
    ) {
        Box {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 26.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.size(42.dp).background(entry.accent.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        entry.icon,
                        contentDescription = null,
                        tint = entry.accent,
                        modifier = Modifier.size(24.dp).graphicsLayer { translationY = iconLift * density },
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(
                        entry.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (count != null) {
                        Text(count.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = entry.accent)
                    } else {
                        Text(
                            entry.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // Unobtrusive remove control: only takes the card off Quick Access, the module stays in the side panel.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(28.dp)
                    .clickable(onClickLabel = "Remove ${entry.label} from Quick Access") { onRemove() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Remove ${entry.label} from Quick Access",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

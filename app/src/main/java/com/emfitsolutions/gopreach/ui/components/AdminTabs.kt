package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HowToReg
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emfitsolutions.gopreach.ui.navigation.Destinations

/** The Admin Main Form's tabs, each in its own colour: Home (the dashboard), Publishers, Field Service, Attendance and Territory Map. */
enum class AdminTab(val label: String, val icon: ImageVector, val color: Color, val route: String) {
    HOME("Home", Icons.Rounded.Dashboard, Color(0xFF7E57C2), Destinations.ADMIN_HOME),
    PUBLISHERS("Publishers", Icons.Rounded.People, Color(0xFF1E6FD9), Destinations.MANAGE_PUBLISHERS),
    FIELD_SERVICE("Field Service", Icons.Rounded.Assessment, Color(0xFF2E8B57), Destinations.FIELD_SERVICE_REPORT),
    ATTENDANCE("Attendance", Icons.Rounded.HowToReg, Color(0xFFE39A00), Destinations.MEETING_ATTENDANCE),
    TERRITORY("Territory Map", Icons.Rounded.Map, Color(0xFF00838F), Destinations.MANAGE_TERRITORIES_BASE),
}

/** Which tabs the signed-in admin role may use (set by the admin Main Form) and how to move between them (set by the navigation graph). */
object AdminTabsStore {
    var allowed by mutableStateOf<Set<AdminTab>>(emptySet())
    var navigate: ((AdminTab) -> Unit)? = null
}

/**
 * The tab strip of the Admin app, like the Publisher's Main Form: an icon over a label, the open tab filled with a soft tint and underlined in its
 * colour. It shows on the Main Form and on each of its modules, so moving between them is one tap. Hidden when the role has only Home.
 */
@Composable
fun AdminMainTabs(selected: AdminTab) {
    val allowed = AdminTabsStore.allowed
    val tabs = AdminTab.entries.filter { it == AdminTab.HOME || it in allowed }
    if (tabs.size < 2) return
    val line = MaterialTheme.colorScheme.outlineVariant
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
        Column {
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                tabs.forEachIndexed { index, tab ->
                    val on = tab == selected
                    if (index > 0) Box(modifier = Modifier.width(1.dp).fillMaxHeight().padding(vertical = 10.dp).background(line))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(if (on) tab.color.copy(alpha = 0.12f) else Color.Transparent)
                            .selectable(selected = on, role = Role.Tab, onClick = { if (!on) AdminTabsStore.navigate?.invoke(tab) })
                            .padding(top = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(tab.icon, contentDescription = null, tint = if (on) tab.color else tab.color.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
                        Text(
                            tab.label, fontSize = 10.5.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            color = if (on) tab.color else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp),
                        )
                        Box(modifier = Modifier.padding(top = 7.dp).fillMaxWidth().height(3.dp).background(if (on) tab.color else tab.color.copy(alpha = 0.3f)))
                    }
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(line))
        }
    }
}
/** The Circuit Overseer's tabs: the five modules they use most, each in its own colour. */
enum class CircuitTab(val label: String, val icon: ImageVector, val color: Color, val route: String) {
    MANUAL_RECORD("Previous Manual Record", Icons.Rounded.History, Color(0xFF00897B), Destinations.CO_MANUAL_RECORD),
    RECEIVED("Received Reports", Icons.Rounded.Inbox, Color(0xFF2E8B57), Destinations.RECEIVED_REPORTS),
    COMPARATIVE("Comparative Reports", Icons.Rounded.CompareArrows, Color(0xFF1E6FD9), Destinations.CONGREGATION_COMPARATIVE),
    ATTENDANCE("Meeting Attendance", Icons.Rounded.Groups, Color(0xFFE39A00), Destinations.MEETING_ATTENDANCE),
    FILES("File Manager", Icons.Rounded.Folder, Color(0xFF6D4C41), Destinations.CO_FILE_MANAGER),
}

/** How to move between the Circuit Overseer's tabs (set by the navigation graph). */
object CircuitTabsStore {
    var navigate: ((CircuitTab) -> Unit)? = null
    /** Back to the Circuit Overseer's main form (set by the navigation graph). */
    var goHome: (() -> Unit)? = null
}

/** The Circuit Overseer's tab strip, drawn exactly like the Admin / Publisher one. [selected] is null on the Main Form (no tab open). */
@Composable
fun CircuitMainTabs(selected: CircuitTab?) {
    // Inside a module the strip starts with a Home icon that goes back to the main form (on the main form itself there is nothing to go back to).
    TabStrip(CircuitTab.entries.map { StripTab(it.label, it.icon, it.color) }, selected?.ordinal ?: -1, maxLabelLines = 2, onHome = if (selected != null) ({ CircuitTabsStore.goHome?.invoke() }) else null) { i -> CircuitTabsStore.navigate?.invoke(CircuitTab.entries[i]) }
}

private class StripTab(val label: String, val icon: ImageVector, val color: Color)

@Composable
private fun TabStrip(tabs: List<StripTab>, selected: Int, maxLabelLines: Int, onHome: (() -> Unit)? = null, onSelect: (Int) -> Unit) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
        Column {
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                if (onHome != null) {
                    Column(
                        modifier = Modifier.width(56.dp).fillMaxHeight().clickable(onClickLabel = "Back to the main form", role = Role.Button, onClick = onHome).padding(top = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Rounded.Home, contentDescription = "Back to the main form", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Text("Main form", fontSize = 10.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, lineHeight = 11.sp, color = MaterialTheme.colorScheme.primary, maxLines = 2, modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp).weight(1f))
                    }
                    Box(modifier = Modifier.width(1.dp).fillMaxHeight().padding(vertical = 10.dp).background(line))
                }
                tabs.forEachIndexed { index, tab ->
                    val on = index == selected
                    if (index > 0) Box(modifier = Modifier.width(1.dp).fillMaxHeight().padding(vertical = 10.dp).background(line))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(if (on) tab.color.copy(alpha = 0.12f) else Color.Transparent)
                            .selectable(selected = on, role = Role.Tab, onClick = { if (!on) onSelect(index) })
                            .padding(top = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(tab.icon, contentDescription = null, tint = if (on) tab.color else tab.color.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
                        Text(
                            tab.label, fontSize = 10.5.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, textAlign = TextAlign.Center, lineHeight = 12.sp,
                            color = if (on) tab.color else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = maxLabelLines, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp).weight(1f),
                        )
                        Box(modifier = Modifier.padding(top = 7.dp).fillMaxWidth().height(3.dp).background(if (on) tab.color else tab.color.copy(alpha = 0.3f)))
                    }
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(line))
        }
    }
}

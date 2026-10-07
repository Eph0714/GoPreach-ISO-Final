package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class QuickAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * The dashboard's header — a flat, solid-color bar with a greeting and a
 * one-line status caption, redesigned to match a "simple, organized,
 * professional" reference (a plain welcome banner, no gradient, no big
 * pill badges) rather than the earlier finance-app-style gradient hero.
 * Sync/connectivity is still shown — this is an offline-first app and that
 * status still matters — just as a quiet caption line under the greeting,
 * the same way the reference shows a plain "August 2026 · Since Aug 20,
 * 2026" line under its own welcome text, instead of two large status pills.
 */
@Composable
fun DashboardHero(
    greetingName: String,
    roleLabel: String?,
    isOnline: Boolean,
    pendingSyncCount: Int,
    quickActions: List<QuickAction>,
    topEndAction: @Composable () -> Unit,
    /** A control anchored at the very left edge — the Side Panel's hamburger
     * toggle in particular, which needs to read as a leading (left-side)
     * header control, distinct from [topEndAction]'s trailing (right-side)
     * icons like sync status/settings. Null renders nothing here, same
     * layout as before this existed. */
    leadingAction: (@Composable () -> Unit)? = null,
    /** A slimmer header (smaller greeting and role line, tighter spacing) — the Circuit Overseer's. */
    compact: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .padding(bottom = if (compact) 8.dp else 16.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (leadingAction != null) leadingAction()
                }
                // Bug fix: [topEndAction] can emit more than one composable
                // (the notification bell *and* the profile menu button) —
                // invoking it bare here made each of those a direct sibling
                // of the outer Row, so Arrangement.SpaceBetween spread all
                // three top-level children (leading action, bell, profile
                // button) evenly across the full width instead of grouping
                // the two trailing ones together — the bell landed in the
                // middle of the header instead of next to the profile
                // picture at the right edge. Wrapping the call in its own
                // Row makes everything [topEndAction] emits count as a
                // single trailing child, so SpaceBetween only ever sees two
                // groups: leading (left) and trailing (right).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    topEndAction()
                }
            }

            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = if (compact) 16.dp else 20.dp, vertical = if (compact) 2.dp else 8.dp)) {
                Text(
                    "Welcome, $greetingName!",
                    color = Color.White,
                    style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                    fontWeight = if (compact) FontWeight.SemiBold else FontWeight.Bold,
                )
                // "Remove All Synced and Online Users" — only the role label
                // remains under the greeting.
                if (roleLabel != null) {
                    Text(
                        roleLabel,
                        color = Color.White.copy(alpha = 0.85f),
                        style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (quickActions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    quickActions.forEach { action ->
                        QuickActionButton(label = action.label, icon = action.icon, onClick = action.onClick)
                    }
                }
            }
        }
    }
}

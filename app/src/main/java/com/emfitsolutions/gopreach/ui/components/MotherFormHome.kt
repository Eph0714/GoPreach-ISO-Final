package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.clickable
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.emfitsolutions.gopreach.ui.navigation.Destinations

/**
 * What the Home button asks before leaving: whether the main form on screen holds changes that are not saved. A form that has such state reports it through
 * [rememberUnsavedChangesBackHandler] (the same signal its Back button uses), so there is one definition of "unsaved" per screen.
 */
object UnsavedFormState {
    var dirty by mutableStateOf(false)
}

/** Opens the person's own homepage (the Admin or the Publisher main form). Provided by [MotherFormHomeHost]; a screen that draws its own full-screen UI uses it for its own Home control. */
val LocalGoHome = compositionLocalOf<(() -> Unit)?> { null }

/**
 * The one "Back to Homepage" for every main (mother) form. It is a slim bar above the screen, drawn once here instead of in each screen, so the behaviour cannot
 * drift and no screen gets two. Child screens (add / edit / detail / wizards) and dialogs are not in [Destinations.MOTHER_FORMS], so they get none.
 * It hides while a record list is in its full-screen mode (like the screen's own app bar). Going home never saves or discards anything by itself: with unsaved
 * changes it asks first, and the normal Back button is untouched.
 */
@Composable
fun MotherFormHomeHost(navController: NavHostController, homeRoute: String, content: @Composable () -> Unit) {
    val entry by navController.currentBackStackEntryAsState()
    // "Back to Home" is on every opened form (the website has the same yellow button under its header); the back arrow still goes one step back.
    val showBar = Destinations.hasHomeButton(entry?.destination?.route) && !RecordFullScreen.active && !HomeBarState.suppressed
    var confirmLeave by remember { mutableStateOf(false) }
    val goHome = {
        // Everything opened from the main form is closed: back to the homepage already on the stack, or open it when it is not there.
        if (!navController.popBackStack(homeRoute, false)) navController.navigate(homeRoute) { launchSingleTop = true }
        UnsavedFormState.dirty = false
    }
    val onHomeClick = { if (UnsavedFormState.dirty) confirmLeave = true else goHome() }
    // A screen that leaves the screen can no longer have unsaved changes of its own.
    DisposableEffect(entry?.destination?.route) { onDispose { UnsavedFormState.dirty = false } }

    CompositionLocalProvider(LocalGoHome provides onHomeClick) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showBar) BackToHomepageBar(onHomeClick)
            // With the bar showing, the status bar is already cleared above it: the screen's own app bar must not add that gap again.
            Box(modifier = Modifier.weight(1f).fillMaxWidth().then(if (showBar) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) { content() }
        }
    }

    if (confirmLeave) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmLeave = false },
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text("Leave without saving?", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
            text = { Text("This form has changes that are not saved. If you go to the homepage now, they will be lost.", textAlign = TextAlign.Center) },
            confirmButton = {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(onClick = { confirmLeave = false }, modifier = Modifier.fillMaxWidth()) { Text("Stay on this form") }
                    TextButton(onClick = { confirmLeave = false; goHome() }, modifier = Modifier.fillMaxWidth()) { Text("Leave without saving") }
                }
            },
        )
    }
}

/** The GoPreach medium-yellow accent (the same as the website's Back to Homepage button) and its dark text. */
private val HomeButtonYellow = androidx.compose.ui.graphics.Color(0xFFE9B83B)
private val HomeButtonOnYellow = androidx.compose.ui.graphics.Color(0xFF231B05)

/** Screens that cover the whole window with their own UI (the full-screen map) turn the bar off while they do and offer [LocalGoHome] themselves. */
object HomeBarState {
    var suppressed by mutableStateOf(false)
}

@Composable
private fun BackToHomepageBar(onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Surface(
                onClick = onClick,
                shape = RoundedCornerShape(50),
                color = HomeButtonYellow,
                contentColor = HomeButtonOnYellow,
                modifier = Modifier.padding(0.dp),
            ) {
                Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Home, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("Back to Home", modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

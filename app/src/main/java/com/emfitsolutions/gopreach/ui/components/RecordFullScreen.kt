package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material3.FabPosition
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * "Full screen" for record lists: while [active], the app bar / tabs ([Scaffold]) and every [HideInFullScreen] block (filters,
 * search) are hidden so the records get the whole screen; the Full Screen chip in [RecordViewToggle] (which stays in the list)
 * switches back. One flag is enough because only one record list is on screen at a time; [rememberRecordViewMode] clears it
 * when that list leaves the screen.
 */
object RecordFullScreen {
    var active by mutableStateOf(false)
}

/**
 * Filters and other controls above a record list. Hidden in full screen, and — when [hideWhileTyping] — also while the
 * keyboard is open, so the search field and the records keep the room the keyboard leaves instead of the filters taking it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HideInFullScreen(hideWhileTyping: Boolean = false, content: @Composable () -> Unit) {
    val keyboard = WindowInsets.isImeVisible
    if (!RecordFullScreen.active && !(hideWhileTyping && keyboard)) content()
}

/**
 * Drop-in replacement for Material 3's Scaffold on record screens: identical, except the [topBar] is not shown in full screen.
 */
@Composable
fun Scaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = contentColorFor(containerColor),
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    androidx.compose.material3.Scaffold(
        modifier = modifier,
        topBar = { if (!RecordFullScreen.active) topBar() },
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        floatingActionButtonPosition = floatingActionButtonPosition,
        containerColor = containerColor,
        contentColor = contentColor,
        contentWindowInsets = contentWindowInsets,
        content = content,
    )
}

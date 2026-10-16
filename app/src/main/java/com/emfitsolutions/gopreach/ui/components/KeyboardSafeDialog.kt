package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Drop-in replacement for Material3's AlertDialog that never lets the on-screen keyboard cover the form.
 *
 * Material's dialog window is edge-to-edge on current Android, so it does not move or shrink when the keyboard opens and the Save / Cancel
 * buttons end up underneath it. This one lays out inside the area the keyboard leaves free: the title and the buttons always stay visible,
 * and the body (the form) scrolls in between. Same parameters and look as the Material dialog, so a screen only changes its import.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
    /** True for a body that does not scroll by itself (a plain form): it then scrolls above the buttons when the keyboard leaves little room. Leave false when the body already scrolls or holds a list. */
    scrollBody: Boolean = false,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
            securePolicy = properties.securePolicy,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // The window must not be panned by the system: this layout moves itself above the keyboard (imePadding), so the system's own
        // panning would only push the dialog's top and buttons off screen.
        val dialogWindow = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        androidx.compose.runtime.SideEffect {
            dialogWindow?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = maxOf(keyboardClearance(), with(androidx.compose.ui.platform.LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }))
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .pointerInput(properties.dismissOnClickOutside) {
                    detectTapGestures { if (properties.dismissOnClickOutside) onDismissRequest() }
                },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = modifier.widthIn(min = 280.dp, max = 560.dp).pointerInput(Unit) { detectTapGestures { } },
                shape = shape,
                color = containerColor,
                tonalElevation = tonalElevation,
            ) {
                Column(Modifier.padding(24.dp)) {
                    icon?.let {
                        CompositionLocalProvider(LocalContentColor provides iconContentColor) {
                            Box(Modifier.padding(bottom = 16.dp).align(Alignment.CenterHorizontally)) { it() }
                        }
                    }
                    title?.let {
                        CompositionLocalProvider(LocalContentColor provides titleContentColor) {
                            Box(
                                Modifier.padding(bottom = 16.dp).align(if (icon == null) Alignment.Start else Alignment.CenterHorizontally),
                            ) {
                                androidx.compose.material3.ProvideTextStyle(
                                    MaterialTheme.typography.headlineSmall.copy(textAlign = if (icon == null) TextAlign.Start else TextAlign.Center),
                                ) { it() }
                            }
                        }
                    }
                    text?.let {
                        CompositionLocalProvider(LocalContentColor provides textContentColor) {
                            Box(Modifier.weight(1f, fill = false).fillMaxWidth().then(if (scrollBody) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
                                androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.bodyMedium) { it() }
                            }
                        }
                    }
                    FlowRow(
                        modifier = Modifier.padding(top = 24.dp).align(Alignment.End),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}

/**
 * How tall the on-screen keyboard currently is, measured in the main window (where the keyboard's size is reported reliably) and passed
 * down. Dialog and bottom-sheet windows are separate windows that do not get the keyboard's size, so they read it from here instead.
 */
val LocalKeyboardHeight = androidx.compose.runtime.compositionLocalOf { 0.dp }

/** Lifts a dialog or sheet layout above the keyboard. Use instead of imePadding() inside a Dialog / ModalBottomSheet window. */
@Composable
fun Modifier.keyboardPadding(): Modifier = this.padding(bottom = keyboardClearance())

/** The keyboard height plus a small margin, so the last row is never flush against - or under - the keys. 0 when the keyboard is closed. */
@Composable
fun keyboardClearance(): Dp = LocalKeyboardHeight.current.let { if (it > 0.dp) it + 28.dp else 0.dp }

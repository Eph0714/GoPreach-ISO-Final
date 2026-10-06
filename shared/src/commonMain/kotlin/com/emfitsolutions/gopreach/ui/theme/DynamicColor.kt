package com.emfitsolutions.gopreach.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** The system's wallpaper-derived color scheme where the platform has one (Android 12+); null elsewhere. */
@Composable
expect fun platformDynamicColorScheme(darkTheme: Boolean): ColorScheme?

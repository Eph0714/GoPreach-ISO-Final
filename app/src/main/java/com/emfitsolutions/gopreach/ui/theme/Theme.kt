package com.emfitsolutions.gopreach.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private fun lightColorsFor(swatch: ThemeColorSwatch): ColorScheme = lightColorScheme(
    primary = swatch.light,
    onPrimary = Color.White,
    primaryContainer = swatch.lightContainer,
    onPrimaryContainer = Color.White,
    secondary = swatch.secondary,
    onSecondary = Color.White,
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceContainerLight,
    onSurfaceVariant = OnSurfaceLight,
    outline = OutlineLight,
    outlineVariant = OutlineLight,
    error = StatusError,
    onError = Color.White,
)

private fun darkColorsFor(swatch: ThemeColorSwatch): ColorScheme = darkColorScheme(
    primary = swatch.darkBright,
    onPrimary = Color.Black,
    primaryContainer = swatch.darkContainer,
    onPrimaryContainer = Color.White,
    secondary = swatch.secondaryDark,
    onSecondary = Color.Black,
    background = DarkBackground121212,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceContainerDark,
    onSurfaceVariant = OnSurfaceDark,
    outline = OutlineDark,
    outlineVariant = OutlineDark,
    error = StatusError,
    onError = Color.White,
)

/**
 * GoPreach Material 3 theme. Uses dynamic color on Android 12+ where available,
 * falling back to the fixed brand palette otherwise, and follows the system
 * light/dark setting. [colorOption] is the user's own per-device accent color
 * choice (Settings screen) — [ThemeColorOption.PURPLE] is the original brand
 * default. [customColor] is only read when [colorOption] is
 * [ThemeColorOption.CUSTOM] (a color wheel/eyedropper pick), which has no
 * fixed swatch of its own — see [generateSwatch].
 */
@Composable
fun GoPreachTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    colorOption: ThemeColorOption = ThemeColorOption.PURPLE,
    customColor: Color = PrimaryPurple,
    content: @Composable () -> Unit,
) {
    val swatch = colorOption.swatch ?: generateSwatch(customColor)
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorsFor(swatch)
        else -> lightColorsFor(swatch)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = GoPreachTypography,
        shapes = GoPreachShapes,
        content = content,
    )
}

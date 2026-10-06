package com.emfitsolutions.gopreach.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

// GoPreach brand palette — light theme: Purple + White, per explicit user
// request ("GoPreach App: Purple Brand Logo and Purple/White Theme"). Hue
// re-matched to a muted violet swatch supplied directly by the user
// (previously a brighter, more saturated purple).
val PrimaryPurple = Color(0xFF5F4B8B)
val PrimaryPurpleDark = Color(0xFF453569)
val PrimaryPurpleLight = Color(0xFF8571B3)
// A distinct, still-purple secondary tone (not an unrelated accent color —
// spec §9: "do not leave old green/blue/yellow accents") for links/secondary
// emphasis, kept visibly different from the primary purple for hierarchy.
val SecondaryPurple = Color(0xFF9A8BC4)

val SurfaceLight = Color(0xFFFFFFFF) // white primary background, per spec §2/§11
val SurfaceContainerLight = Color(0xFFF6F1FA) // faint purple-tinted neutral, for card/section hierarchy only
val OutlineLight = Color(0xFFD8CCE6)
val OnSurfaceLight = Color(0xFF1C1424)

// GoPreach brand palette — dark theme: Purple + #121212, per explicit user
// request. #121212 is Android's own recommended dark-theme baseline
// background (Material Design dark theme spec) — used verbatim, not
// approximated.
val PrimaryPurpleBright = Color(0xFFA594D1) // lighter tint of the same violet, for contrast on #121212
val PrimaryPurpleContainerDark = Color(0xFF3D2F5C)
val DarkBackground121212 = Color(0xFF121212)
val SecondaryPurpleDark = Color(0xFFC4B8E0)

val SurfaceDark = Color(0xFF121212) // #121212, per explicit request
val SurfaceContainerDark = Color(0xFF1E1E1E) // slightly lifted, for card/section hierarchy on #121212
val OutlineDark = Color(0xFF4A3F58)
val OnSurfaceDark = Color(0xFFFFFFFF) // white text on dark theme

val StatusPending = Color(0xFFE0A526) // "pending sync" indicator — a genuine status color, kept (spec §9's exception)
val StatusSynced = Color(0xFF2E9E5B)
val StatusError = Color(0xFFC63737)

/** One set of accent hues for [ThemeColorOption] — everything else (surfaces,
 * backgrounds, white/#121212 base) stays exactly as the brand theme already
 * defines it; only the primary/secondary accent shifts per user choice. */
data class ThemeColorSwatch(
    val light: Color,
    val lightContainer: Color,
    val secondary: Color,
    val darkBright: Color,
    val darkContainer: Color,
    val secondaryDark: Color,
)

/**
 * "For theme color, let the users select from color wheel... eyedrop a
 * color" — a picked seed color has no hand-tuned container/secondary/dark
 * tones the way the six fixed presets above do, so this derives the same
 * six-role shape from just the one seed, via plain HSV math (no new
 * dependency — [android.graphics.Color]'s HSV conversion is a platform API,
 * not a library). [light] is clamped to a value/saturation range that keeps
 * [Theme.kt]'s hard-coded `onPrimary = Color.White` legible even against a
 * very pale or very saturated pick — the applied color may differ slightly
 * from the exact picked pixel for that reason, same trade-off any accent-
 * color picker with a fixed contrasting label color has to make.
 */
fun generateSwatch(seed: Color): ThemeColorSwatch {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(seed.toArgb(), hsv)
    val hue = hsv[0]
    val saturation = hsv[1]

    fun tone(sat: Float, value: Float): Color =
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))))

    return ThemeColorSwatch(
        // Clamped so white text (Theme.kt's fixed onPrimary) stays readable
        // regardless of how light or how saturated the original pick was.
        light = tone(saturation.coerceIn(0.35f, 0.95f), 0.55f),
        lightContainer = tone(saturation * 0.55f, 0.82f),
        secondary = tone(saturation * 0.45f, 0.72f),
        darkBright = tone(saturation * 0.55f, 0.82f),
        darkContainer = tone(saturation.coerceAtMost(0.85f), 0.32f),
        secondaryDark = tone(saturation * 0.35f, 0.88f),
    )
}

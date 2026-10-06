package com.emfitsolutions.gopreach.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Per-device accent color choice (Settings screen) — each user can pick their
 * own on their own phone; this is display-only and never synced to the
 * server or shared with anyone else (same "per-device, not account data"
 * pattern as [com.emfitsolutions.gopreach.data.repository.ThemePreference]
 * light/dark). [PURPLE] is the original brand default and stays first/default.
 *
 * [swatch] is null only for [CUSTOM] — a color wheel/eyedropper pick has no
 * fixed, hand-tuned swatch the way the six presets do; [Theme.kt] derives
 * one on the fly via [generateSwatch] from
 * [com.emfitsolutions.gopreach.data.repository.ThemePreferenceRepository]'s
 * separately-stored custom color instead.
 */
enum class ThemeColorOption(val label: String, val swatch: ThemeColorSwatch?) {
    PURPLE(
        "Purple",
        ThemeColorSwatch(
            light = PrimaryPurple,
            lightContainer = PrimaryPurpleLight,
            secondary = SecondaryPurple,
            darkBright = PrimaryPurpleBright,
            darkContainer = PrimaryPurpleContainerDark,
            secondaryDark = SecondaryPurpleDark,
        ),
    ),
    BLUE(
        "Blue",
        ThemeColorSwatch(
            light = Color(0xFF2F5FA8),
            lightContainer = Color(0xFF6C93C7),
            secondary = Color(0xFF7FA5D6),
            darkBright = Color(0xFF9AC0EE),
            darkContainer = Color(0xFF203A5C),
            secondaryDark = Color(0xFFB8D3F0),
        ),
    ),
    GREEN(
        "Green",
        ThemeColorSwatch(
            light = Color(0xFF2E7D4F),
            lightContainer = Color(0xFF6BAE87),
            secondary = Color(0xFF80C29B),
            darkBright = Color(0xFF8FD8AC),
            darkContainer = Color(0xFF1E4A31),
            secondaryDark = Color(0xFFBDE8CE),
        ),
    ),
    TEAL(
        "Teal",
        ThemeColorSwatch(
            light = Color(0xFF1F7A72),
            lightContainer = Color(0xFF5FA79F),
            secondary = Color(0xFF7CC2BB),
            darkBright = Color(0xFF8FDAD2),
            darkContainer = Color(0xFF154842),
            secondaryDark = Color(0xFFBDE9E4),
        ),
    ),
    ORANGE(
        "Orange",
        ThemeColorSwatch(
            light = Color(0xFFB5591D),
            lightContainer = Color(0xFFD08A57),
            secondary = Color(0xFFDDA679),
            darkBright = Color(0xFFEEB37F),
            darkContainer = Color(0xFF6B3812),
            secondaryDark = Color(0xFFF3D0B0),
        ),
    ),
    RED(
        "Red",
        ThemeColorSwatch(
            light = Color(0xFFB0342E),
            lightContainer = Color(0xFFCE726D),
            secondary = Color(0xFFDB918D),
            darkBright = Color(0xFFE99C97),
            darkContainer = Color(0xFF6B1F1B),
            secondaryDark = Color(0xFFF2C2C0),
        ),
    ),
    CUSTOM("Custom", null),
}

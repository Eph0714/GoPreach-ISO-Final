package com.emfitsolutions.gopreach.data.repository

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.edit
import com.emfitsolutions.gopreach.ui.theme.PrimaryPurple
import com.emfitsolutions.gopreach.ui.theme.ThemeColorOption
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemePreference { SYSTEM, LIGHT, DARK }

private const val PREFS_NAME = "gopreach_settings"
private const val KEY_THEME = "theme_preference"
private const val KEY_COLOR = "theme_color_option"
private const val KEY_CUSTOM_COLOR = "theme_custom_color_argb"

/**
 * User's light/dark display preference, and their accent color choice
 * ([ThemeColorOption]) — separate from any account data, so both apply
 * instantly on this device and survive sign-out, and are never synced to the
 * server or shared with anyone else (spec §1 asks for a "modern Android UI";
 * a per-device display choice like this is a standard expectation, not
 * something tied to a Person record — each user picks their own on their own
 * phone).
 */
@Singleton
class ThemePreferenceRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _preference = MutableStateFlow(readStoredTheme())
    val preference: StateFlow<ThemePreference> = _preference

    private val _colorOption = MutableStateFlow(readStoredColor())
    val colorOption: StateFlow<ThemeColorOption> = _colorOption

    /** Only meaningful when [colorOption] is [ThemeColorOption.CUSTOM] — the
     * exact seed color picked from the color wheel or eyedropper (see
     * [com.emfitsolutions.gopreach.ui.theme.generateSwatch]). */
    private val _customColor = MutableStateFlow(readStoredCustomColor())
    val customColor: StateFlow<Color> = _customColor

    private fun readStoredTheme(): ThemePreference =
        runCatching { ThemePreference.valueOf(prefs.getString(KEY_THEME, ThemePreference.SYSTEM.name)!!) }
            .getOrDefault(ThemePreference.SYSTEM)

    private fun readStoredColor(): ThemeColorOption =
        runCatching { ThemeColorOption.valueOf(prefs.getString(KEY_COLOR, ThemeColorOption.PURPLE.name)!!) }
            .getOrDefault(ThemeColorOption.PURPLE)

    private fun readStoredCustomColor(): Color =
        Color(prefs.getInt(KEY_CUSTOM_COLOR, PrimaryPurple.toArgb()))

    fun setPreference(value: ThemePreference) {
        prefs.edit { putString(KEY_THEME, value.name) }
        _preference.value = value
    }

    fun setColorOption(value: ThemeColorOption) {
        prefs.edit { putString(KEY_COLOR, value.name) }
        _colorOption.value = value
    }

    /** Picking a custom color both stores it and switches [colorOption] to
     * [ThemeColorOption.CUSTOM] in one step — there's no separate "apply my
     * custom color" toggle a user could forget to flip. */
    fun setCustomColor(color: Color) {
        prefs.edit { putInt(KEY_CUSTOM_COLOR, color.toArgb()) }
        _customColor.value = color
        setColorOption(ThemeColorOption.CUSTOM)
    }
}

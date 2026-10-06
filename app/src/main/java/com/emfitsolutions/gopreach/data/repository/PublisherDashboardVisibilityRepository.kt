package com.emfitsolutions.gopreach.data.repository

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "gopreach_settings"
private const val KEY_SHOW_DASHBOARD = "publisher_show_dashboard"

/** "Show the dashboard at initial launch" — a per-device Publisher
 * preference, same SharedPreferences-backed, sign-out-surviving shape as
 * [ThemePreferenceRepository] (a display choice, not account data — never
 * synced or shared). Defaults to shown, so a fresh install/device/login
 * starts with the Dashboard (and, driven by it, My Planner) visible. */
@Singleton
class PublisherDashboardVisibilityRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _showDashboard = MutableStateFlow(prefs.getBoolean(KEY_SHOW_DASHBOARD, true))
    val showDashboard: StateFlow<Boolean> = _showDashboard

    fun setShowDashboard(value: Boolean) {
        prefs.edit { putBoolean(KEY_SHOW_DASHBOARD, value) }
        _showDashboard.value = value
    }
}

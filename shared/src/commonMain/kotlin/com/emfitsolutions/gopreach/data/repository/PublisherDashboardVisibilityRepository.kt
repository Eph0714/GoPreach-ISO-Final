package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.platform.KeyValueStores
import com.emfitsolutions.gopreach.platform.edit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val PREFS_NAME = "gopreach_settings"
private const val KEY_SHOW_DASHBOARD = "publisher_show_dashboard"

/** "Show the dashboard at initial launch" — a per-device Publisher
 * preference, same SharedPreferences-backed, sign-out-surviving shape as
 * [ThemePreferenceRepository] (a display choice, not account data — never
 * synced or shared). Defaults to shown, so a fresh install/device/login
 * starts with the Dashboard (and, driven by it, My Planner) visible. */
class PublisherDashboardVisibilityRepository(
    stores: KeyValueStores,
) {
    private val prefs = stores.open(PREFS_NAME)

    private val _showDashboard = MutableStateFlow(prefs.getBoolean(KEY_SHOW_DASHBOARD, true))
    val showDashboard: StateFlow<Boolean> = _showDashboard

    fun setShowDashboard(value: Boolean) {
        prefs.edit { putBoolean(KEY_SHOW_DASHBOARD, value) }
        _showDashboard.value = value
    }
}

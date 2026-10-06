package com.emfitsolutions.gopreach.data.repository

import android.content.Context
import androidx.core.content.edit
import com.emfitsolutions.gopreach.domain.NameOrder
import com.emfitsolutions.gopreach.domain.NameOrderState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_NAME_ORDER = "name_order"

/** Last-name-first or first-name-first — a per-device display choice, same as the theme: it applies right
 * away, survives sign-out and is never synced to anyone else. Defaults to last name first. */
@Singleton
class NameOrderPreference @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("gopreach_settings", Context.MODE_PRIVATE)

    private val _order = MutableStateFlow(read())
    val order: StateFlow<NameOrder> = _order

    init { NameOrderState.current = _order.value }

    fun set(order: NameOrder) {
        prefs.edit { putString(KEY_NAME_ORDER, order.name) }
        NameOrderState.current = order
        _order.value = order
    }

    private fun read(): NameOrder =
        runCatching { NameOrder.valueOf(prefs.getString(KEY_NAME_ORDER, NameOrder.LAST_FIRST.name)!!) }.getOrDefault(NameOrder.LAST_FIRST)
}

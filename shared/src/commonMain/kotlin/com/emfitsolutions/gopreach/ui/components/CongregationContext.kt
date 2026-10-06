package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Super-Admin congregation context: the Congregation a Super-Admin picked in a
 * given module stays selected for the whole app session — across add/edit
 * screens, detail screens, and back navigation — instead of living in a
 * screen-local `remember` that is thrown away every time the list leaves
 * composition. Never auto-selected: a module starts at `null` ("Select
 * Congregation") until the Super-Admin explicitly picks one.
 */
object CongregationContextStore {
    private val selections = mutableStateMapOf<String, String?>()
    fun get(moduleKey: String): String? = selections[moduleKey]
    fun set(moduleKey: String, congregationId: String?) { selections[moduleKey] = congregationId }
    fun clearAll() = selections.clear()
}

/** Delegate-friendly state bound to [CongregationContextStore] under [moduleKey]. */
class CongregationContextState(private val moduleKey: String) {
    var value: String?
        get() = CongregationContextStore.get(moduleKey)
        set(v) = CongregationContextStore.set(moduleKey, v)
    operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): String? = value
    operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, v: String?) { value = v }
}

@Composable
fun rememberCongregationContext(moduleKey: String): CongregationContextState = remember(moduleKey) { CongregationContextState(moduleKey) }

/** Empty state shown until a Super-Admin selects a Congregation. */
@Composable
fun SelectCongregationPrompt(modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            "Please select a Congregation to view records.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

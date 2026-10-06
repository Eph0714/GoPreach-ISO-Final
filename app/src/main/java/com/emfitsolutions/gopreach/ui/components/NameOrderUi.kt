package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.repository.NameOrderPreference
import com.emfitsolutions.gopreach.domain.NameOrder

/** The name order currently picked (see [NameOrderPreference]); forms read it to order their name fields. */
val LocalNameOrder = compositionLocalOf { NameOrder.LAST_FIRST }

class NameOrderViewModel(private val preference: NameOrderPreference) : ViewModel() {
    fun set(order: NameOrder) = preference.set(order)
}

/** "Last name first / First name first" — one tap, applies at once, remembered on this device. */
@Composable
fun NameOrderToggle(modifier: Modifier = Modifier, viewModel: NameOrderViewModel = koinViewModel()) {
    val current = LocalNameOrder.current
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        NameOrder.entries.forEachIndexed { index, order ->
            SegmentedButton(
                selected = current == order,
                onClick = { viewModel.set(order) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = NameOrder.entries.size),
            ) { Text(order.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
        }
    }
}

/**
 * The name fields of a person form, in the order the user chose, with the toggle above them. Only the chosen
 * order is shown: last name first puts Last Name above First Name; first name first puts First Name (then the
 * optional Middle Initial) above Last Name. The Extension Name always comes last.
 */
@Composable
fun NameFieldsInOrder(
    first: @Composable () -> Unit,
    last: @Composable () -> Unit,
    middle: (@Composable () -> Unit)? = null,
    extension: (@Composable () -> Unit)? = null,
) {
    NameOrderToggle()
    val ordered = buildList<@Composable () -> Unit> {
        if (LocalNameOrder.current == NameOrder.LAST_FIRST) {
            add(last); add(first); middle?.let { add(it) }
        } else {
            add(first); middle?.let { add(it) }; add(last)
        }
        extension?.let { add(it) }
    }
    // Responsive: stacked on phones, two per row once there is room (tablet / landscape), never squeezed.
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    if (!wide) {
        ordered.forEach { it() }
    } else {
        ordered.chunked(2).forEach { pair ->
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { field -> androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) { field() } }
                if (pair.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Settings: the name-order choice, with a live example. */
@Composable
fun NameOrderSettingsSection() {
    val current = LocalNameOrder.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Name Order", style = MaterialTheme.typography.titleMedium)
        NameOrderToggle()
        Text(
            "Example: " + com.emfitsolutions.gopreach.domain.formatPersonName("Juan", "D", "Dela Cruz", null, current).uppercase() +
                ". Applies to forms, lists and reports.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

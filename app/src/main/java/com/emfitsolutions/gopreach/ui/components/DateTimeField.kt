package com.emfitsolutions.gopreach.ui.components

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * A button that opens the native date picker then time picker and reports the
 * result as epoch millis — used everywhere a [com.emfitsolutions.gopreach.data.model.Schedule]
 * start/end time is entered (Chat Schedule spec §5.1, Calendar spec §6.2), so
 * that widget isn't rebuilt per feature.
 */
@Composable
fun DateTimeField(
    label: String,
    valueMillis: Long?,
    onValueChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val displayFormat = remember { SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()) }

    OutlinedButton(
        onClick = {
            val calendar = Calendar.getInstance().apply { valueMillis?.let { timeInMillis = it } }
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    TimePickerDialog(
                        context,
                        { _, hour, minute ->
                            calendar.set(year, month, day, hour, minute)
                            onValueChange(calendar.timeInMillis)
                        },
                        calendar.get(Calendar.HOUR_OF_DAY),
                        calendar.get(Calendar.MINUTE),
                        false,
                    ).show()
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH),
            ).show()
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(if (valueMillis != null) "$label: ${displayFormat.format(valueMillis)}" else "Set $label")
    }
}

/**
 * A standalone "Visit Date"-style field: tapping opens only the native date
 * picker (no time picker chained after it) and replaces just the
 * year/month/day of [valueMillis], leaving its time-of-day untouched — the
 * companion [TimeOnlyField] owns that half. Together they let a single
 * underlying timestamp (spec: "store a single underlying datetime value...
 * presenting it as two separate editable fields") be edited as two separate
 * fields without either one clobbering the other's part of it.
 */
@Composable
fun DateOnlyField(label: String, valueMillis: Long, onValueChange: (Long) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val displayFormat = remember { SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()) }
    val calendar = remember(valueMillis) { Calendar.getInstance().apply { timeInMillis = valueMillis } }

    OutlinedButton(
        onClick = {
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    val updated = (calendar.clone() as Calendar).apply { set(year, month, day) }
                    onValueChange(updated.timeInMillis)
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH),
            ).show()
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Text("$label: ${displayFormat.format(valueMillis)}")
    }
}

/** The time-of-day companion to [DateOnlyField] — see its doc comment. */
@Composable
fun TimeOnlyField(label: String, valueMillis: Long, onValueChange: (Long) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val displayFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val calendar = remember(valueMillis) { Calendar.getInstance().apply { timeInMillis = valueMillis } }

    OutlinedButton(
        onClick = {
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    val updated = (calendar.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute) }
                    onValueChange(updated.timeInMillis)
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                false,
            ).show()
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Text("$label: ${displayFormat.format(valueMillis)}")
    }
}

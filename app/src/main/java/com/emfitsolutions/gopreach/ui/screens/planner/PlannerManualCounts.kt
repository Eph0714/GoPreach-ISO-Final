package com.emfitsolutions.gopreach.ui.screens.planner

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import com.emfitsolutions.gopreach.data.model.ManualMinistryCount
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.platform.Date
import com.emfitsolutions.gopreach.platform.Locale
import com.emfitsolutions.gopreach.platform.SimpleDateFormat
import com.emfitsolutions.gopreach.ui.components.ActionButton
import com.emfitsolutions.gopreach.ui.components.ActionKind
import com.emfitsolutions.gopreach.ui.components.FormDialog
import java.util.Calendar

/** What the Add / Edit quantity dialog is working on: a new entry for [stage] on [dayStart], or [existing] being edited. */
internal data class ManualCountRequest(val stage: PipelineStage, val dayStart: Long, val existing: ManualMinistryCount? = null)

/**
 * "Add quantity" for Return Visits / Bible Studies in My Planner: the Publisher types how many, without opening a record. Same shape as the
 * Credit Hours dialog (date, amount, note); an existing entry can also be deleted. A submitted month is refused by the view model.
 */
@Composable
internal fun ManualCountDialog(
    request: ManualCountRequest,
    onDismiss: () -> Unit,
    onSave: (quantity: Int, dayStart: Long, note: String?) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val context = LocalContext.current
    val existing = request.existing
    val noun = if (request.stage == PipelineStage.RETURN_VISIT) "Return Visits" else "Bible Studies"
    var quantityText by remember { mutableStateOf(existing?.quantity?.toString() ?: "") }
    var dayStart by remember { mutableStateOf(existing?.dayStart?.takeIf { it > 0L } ?: request.dayStart) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val dateText = remember(dayStart) { SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date(dayStart)) }
    val hasUnsavedChanges = existing?.let { quantityText != it.quantity.toString() || dayStart != it.dayStart || note != it.note.orEmpty() }
        ?: (quantityText.isNotBlank() || note.isNotBlank())

    FormDialog(
        onDismissRequest = onDismiss,
        title = if (existing == null) "Add $noun" else "Edit $noun",
        hasUnsavedChanges = hasUnsavedChanges,
        errorMessage = errorMessage,
        onConfirm = {
            val quantity = quantityText.toIntOrNull() ?: 0
            errorMessage = if (quantity < 1) "Enter how many $noun (1 or more)." else null
            if (errorMessage == null) onSave(quantity, dayStart, note)
        },
    ) {
        OutlinedTextField(
            value = quantityText,
            onValueChange = { quantityText = it.filter(Char::isDigit).take(3); errorMessage = null },
            label = { Text("Quantity") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        Box {
            OutlinedTextField(
                value = dateText,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text("Date") },
                trailingIcon = { Icon(Icons.Rounded.CalendarMonth, contentDescription = null) },
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(
                modifier = Modifier.matchParentSize().clickable(role = Role.Button, onClickLabel = "Choose date") {
                    val calendar = Calendar.getInstance().apply { timeInMillis = dayStart }
                    DatePickerDialog(
                        context,
                        { _, year, month, day ->
                            dayStart = Calendar.getInstance().apply { set(year, month, day, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                        },
                        calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH),
                    ).show()
                },
            )
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(200) },
            label = { Text("Note (optional)") },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        if (onDelete != null) ActionButton(ActionKind.Delete, onClick = onDelete, label = "Delete")
    }
}

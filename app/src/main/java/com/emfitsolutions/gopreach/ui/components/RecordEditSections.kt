package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared building blocks for the "show the complete record when editing" pass
 * across every Manage screen's Edit dialog (Congregations, Groups, Publishers,
 * Admins, Elders, Interested Persons) — a section header for grouping fields
 * (Personal Information / Assignment / Status / System Information, etc.) and a
 * plain label+value row for information the user can see but not change here
 * (record ID, created date, created by, category/role — anything that already
 * has its own dedicated control elsewhere, like the category dropdown or the
 * Delete/Reactivate flow, rather than being edited inline in this form).
 */
@Composable
fun EditSectionHeader(title: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
    }
}

@Composable
fun ReadOnlyField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
    }
}

private val recordDateFormat = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())

/** Formats a stored epoch-millis timestamp for a [ReadOnlyField] — "—" for the
 * zero-value default a record gets before it's ever actually set. */
fun formatRecordTimestamp(epochMillis: Long): String =
    if (epochMillis <= 0L) "—" else recordDateFormat.format(Date(epochMillis))

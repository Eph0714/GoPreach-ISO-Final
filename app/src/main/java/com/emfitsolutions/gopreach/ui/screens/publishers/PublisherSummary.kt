package com.emfitsolutions.gopreach.ui.screens.publishers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.data.model.AccountStatus
import com.emfitsolutions.gopreach.data.model.PublisherCategory

/**
 * The mandatory end-of-report Summary of the Publisher list, counted from exactly the rows being shown (after the congregation, search
 * and filters), so the screen, print, PDF and Excel agree. First row = headings (Metric | Value).
 */
object PublisherSummary {
    fun rows(publishers: List<PublisherRow>): List<List<String>> {
        fun cat(vararg c: PublisherCategory) = publishers.count { it.category in c }
        fun status(s: AccountStatus) = publishers.count { it.person.accountStatus == s }
        return listOf(
            listOf("Metric", "Value"),
            listOf("Total Publishers", publishers.size.toString()),
            listOf("Active", status(AccountStatus.ACTIVE).toString()),
            listOf("Inactive", status(AccountStatus.INACTIVE).toString()),
            listOf("Suspended", status(AccountStatus.SUSPENDED).toString()),
            listOf("Regular Pioneers", cat(PublisherCategory.REGULAR_PIONEER).toString()),
            listOf("Special Pioneers", cat(PublisherCategory.SPECIAL_PIONEER).toString()),
            listOf("Auxiliary Pioneers", cat(PublisherCategory.AUXILIARY_PIONEER).toString()),
            listOf("Unbaptized Publishers", cat(PublisherCategory.UNBAPTIZED_PUBLISHER).toString()),
        )
    }

    fun html(rows: List<List<String>>): String = buildString {
        fun e(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        append("<h3 style=\"margin:14px 0 4px 0\">Summary</h3><table style=\"width:auto\">")
        rows.drop(1).forEach { r -> append("<tr><td>").append(e(r[0])).append("</td><td style=\"text-align:right;font-weight:bold\">").append(e(r[1])).append("</td></tr>") }
        append("</table>")
    }
}

@Composable
internal fun PublisherSummaryCard(rows: List<List<String>>, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Summary", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            rows.drop(1).forEach { r ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(r[0], style = MaterialTheme.typography.bodyMedium)
                    Text(r[1], style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.data.repository.TempCredentials

/**
 * Shown right after enrolling an Admin/Coordinator Elder/Regular Elder/Publisher
 * (spec §4.2-4.4, §4.6): the one-time temp username/password plus a shareable
 * link, so the enrolling role can hand them to the new person. Not a login
 * field, so this text is plain by design — nothing here should be masked.
 */
@Composable
fun TempCredentialsResultCard(
    credentials: TempCredentials,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Account Created", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Share these one-time credentials with the enrollee. They'll be forced to set their own username and password on first login.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Username: ${credentials.username}", style = MaterialTheme.typography.bodyLarge)
            Text("Temporary Password: ${credentials.temporaryPassword}", style = MaterialTheme.typography.bodyLarge)
            ShareableSetupLink(credentials.username, credentials.temporaryPassword, credentials.shareableLink)
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

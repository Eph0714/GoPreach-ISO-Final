package com.emfitsolutions.gopreach.ui.components

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * The setup link shown alongside a freshly enrolled (or still-pending, see
 * [TempCredentialLookupDialog]) account's temp username/password — tappable,
 * launching the system Share sheet with everything the enrollee needs
 * (username, temp password, and the link itself) so whoever enrolled them can
 * hand it off via SMS/chat/email in one tap instead of manually selecting and
 * copying the plain-text link. Underlined + a share icon so it visibly reads
 * as tappable rather than plain informational text.
 */
@Composable
fun ShareableSetupLink(username: String, temporaryPassword: String, shareableLink: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier.clickable {
            val shareText = "GoPreach sign-in\nUsername: $username\nTemporary Password: $temporaryPassword\nSetup Link: $shareableLink"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            context.startActivity(Intent.createChooser(intent, "Share Sign-In Credentials"))
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Share, contentDescription = "Share setup link", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            "Setup Link: $shareableLink",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        )
    }
}

package com.emfitsolutions.gopreach.ui.components

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.data.model.Person
import androidx.compose.ui.window.DialogProperties

/**
 * Tapping a Person row on a Manage-X list (Admins, Publishers, Coordinator/
 * Regular Elders) surfaces this when that person still hasn't completed their
 * forced first-login password change — a quick way to recover a temp
 * username/password without re-enrolling them, for whoever lost the original
 * share link. Once the person changes their password, [Person.temporaryPassword]
 * is cleared server-side and this dialog has nothing left to show, by design.
 *
 * Plain text only — no setup link here (that combined link+share flow stays
 * on [TempCredentialsResultCard], right after enrollment, where it's most
 * useful); this lookup's own Share action hands off just the two plain
 * lines below, nothing else.
 */
@Composable
fun TempCredentialLookupDialog(person: Person, onDismiss: () -> Unit) {
    val tempPassword = person.temporaryPassword
    val context = LocalContext.current
    AlertDialog(
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
        onDismissRequest = onDismiss,
        title = { Text("Temporary Sign-In") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tempPassword == null) {
                    // The Publisher already chose their own credentials (first-time setup is finishing), so the
                    // temporary ones no longer work and their new username is theirs alone.
                    Text("${person.fullName} has already chosen their own username and password and is finishing first-time setup. There is no temporary sign-in left to share.")
                } else {
                    Text("${person.fullName} hasn't signed in and changed their password yet. Share this again if needed:")
                    Text("Username: ${person.username}", fontWeight = FontWeight.Bold)
                }
                if (tempPassword != null) {
                    Text("Temporary Password: $tempPassword", fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.clickable {
                            val shareText = "Username: ${person.username}\nTemporary Password: $tempPassword"
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Sign-In Credentials"))
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = "Share username and password", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

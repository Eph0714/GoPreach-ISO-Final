package com.emfitsolutions.gopreach.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.ui.window.DialogProperties

/**
 * The top-right profile avatar + menu, present on every role's Main Form
 * (Super-Admin, Admin, Coordinator/Regular Elder, Publisher, ...) per spec:
 * a circular photo (or a blank placeholder icon when [profileImageUrl] is
 * null) that opens a menu showing the signed-in person's name and role,
 * then [View Profile Image] / [Update Profile Image] / [Log Out].
 *
 * [onOpenSettings] — non-null only where the caller removed the separate
 * top-bar Settings icon (Admin-track Main Forms: "remove the setting from
 * the main form... put the notification bell on the upper right side next
 * to user image" — the gear icon used to sit between the bell and this
 * avatar). Rather than dropping the Settings screen (theme, notification
 * sound, app version/updates) entirely, it moves in here so it's still one
 * tap away, just off the top bar itself.
 */
@Composable
fun ProfileMenuButton(
    fullName: String,
    roleLabel: String,
    profileImageUrl: String?,
    onImagePicked: (Uri) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    onOpenSettings: (() -> Unit)? = null,
    // Non-null only for a Publisher's own top bar — the Publisher-track
    // Main Form has no other path to AccountSettingsScreen (its bottom-nav
    // "Profile" tab goes to the theme/notification Settings screen instead,
    // and its side drawer omits the Account Settings row Admin-track drawers
    // show) — without this, name/username/password changes and "Add Module:
    // Preaching Availability" would be unreachable for a Publisher account.
    onOpenAccountSettings: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var showViewImage by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onImagePicked(uri)
    }

    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            ProfileAvatar(profileImageUrl, size = 32.dp, tint = tint)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(fullName.uppercase(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    roleLabel.uppercase(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("View Profile Image") },
                leadingIcon = { Icon(Icons.Rounded.Visibility, contentDescription = null) },
                onClick = { expanded = false; showViewImage = true },
            )
            DropdownMenuItem(
                text = { Text("Update Profile Image") },
                leadingIcon = { Icon(Icons.Rounded.PhotoCamera, contentDescription = null) },
                onClick = { expanded = false; pickImage.launch("image/*") },
            )
            if (onOpenAccountSettings != null) {
                DropdownMenuItem(
                    text = { Text("Account Settings") },
                    leadingIcon = { Icon(Icons.Rounded.Password, contentDescription = null) },
                    onClick = { expanded = false; onOpenAccountSettings() },
                )
            }
            if (onOpenSettings != null) {
                DropdownMenuItem(
                    text = { Text("Settings") },
                    leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    onClick = { expanded = false; onOpenSettings() },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Log Out") },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null) },
                onClick = { expanded = false; onSignOut() },
            )
        }
    }

    if (showViewImage) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { showViewImage = false },
            title = { Text(fullName) },
            text = {
                if (profileImageUrl != null) {
                    AsyncImage(
                        model = profileImageUrl,
                        contentDescription = "Profile photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
                    )
                } else {
                    Text("No profile image has been set yet.", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { showViewImage = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun ProfileAvatar(profileImageUrl: String?, size: androidx.compose.ui.unit.Dp, tint: Color) {
    if (profileImageUrl != null) {
        AsyncImage(
            model = profileImageUrl,
            contentDescription = "Profile",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.15f)),
        )
    } else {
        Icon(Icons.Rounded.AccountCircle, contentDescription = "Profile", tint = tint, modifier = Modifier.size(size))
    }
}

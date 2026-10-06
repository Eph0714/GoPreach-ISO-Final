package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.notifications.AlarmRingService

/**
 * "Allow also the user to stop the alarm" — a Calendar Alarm keeps ringing
 * (looping sound + vibration, see [AlarmRingService]) until stopped, and the
 * notification's own "Stop Alarm" action isn't always reachable (shade
 * dismissed, user already back in the app) — this banner is the in-app
 * equivalent, shown at the top of every role's Main Form for as long as
 * [AlarmRingService.isRinging] is true.
 */
@Composable
fun AlarmRingingBanner() {
    val context = LocalContext.current
    val isRinging by AlarmRingService.isRinging.collectAsStateWithLifecycle()
    if (!isRinging) return

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Alarm, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    "Calendar alarm ringing",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Button(
                onClick = { AlarmRingService.stop(context) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Stop Alarm") }
        }
    }
}

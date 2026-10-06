package com.emfitsolutions.gopreach.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.emfitsolutions.gopreach.MainActivity
import com.emfitsolutions.gopreach.R
import com.emfitsolutions.gopreach.data.repository.NotificationSoundRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

private const val TAG = "AlarmRingService"

/**
 * "Allow also the user to stop the alarm" — a foreground Service (not just a
 * notification) because ringing an alarm means looping a sound and vibrating
 * *indefinitely* until the user acts, which a plain one-shot notification
 * can't do. Started by [CalendarAlarmReceiver]; stopped either by the
 * notification's own "Stop Alarm" action ([AlarmStopReceiver]) or by the
 * in-app "Stop Alarm" banner (see [isRinging], shown on every role's Main
 * Form while this is running).
 */
@AndroidEntryPoint
class AlarmRingService : Service() {

    @Inject lateinit var notificationSoundRepository: NotificationSoundRepository

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Calendar Event" }
        NotificationHelper.ensureChannel(this)
        startForeground(ALARM_NOTIFICATION_ID, buildNotification(title))
        startRinging()
        _isRinging.value = true
        return START_NOT_STICKY
    }

    private fun startRinging() {
        val soundUri = notificationSoundRepository.soundUri.value
            ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        runCatching {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(this@AlarmRingService, soundUri)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { Log.e(TAG, "Failed to start alarm sound", it) }

        runCatching {
            vibrator = getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 800, 400), 0)
            }
        }.onFailure { Log.e(TAG, "Failed to start alarm vibration", it) }
    }

    private fun buildNotification(title: String): Notification {
        val stopPendingIntent = PendingIntent.getBroadcast(
            this, 0, Intent(this, AlarmStopReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CALENDAR_ALARM_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Calendar Alarm")
            .setContentText(title)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(contentPendingIntent)
            .setFullScreenIntent(contentPendingIntent, true)
            .addAction(0, "Stop Alarm", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        runCatching { mediaPlayer?.stop() }
        mediaPlayer?.release()
        mediaPlayer = null
        vibrator?.cancel()
        _isRinging.value = false
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_SCHEDULE_ID = "scheduleId"
        const val ALARM_NOTIFICATION_ID = 9500

        private val _isRinging = MutableStateFlow(false)
        /** Observed by every Main Form to show an in-app "Stop Alarm" banner
         * — the notification action alone isn't enough if the user has
         * already opened the app and the notification shade isn't visible. */
        val isRinging: StateFlow<Boolean> = _isRinging

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, AlarmRingService::class.java))
            androidx.core.app.NotificationManagerCompat.from(context).cancel(ALARM_NOTIFICATION_ID)
        }
    }
}

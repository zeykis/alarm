package com.wakealarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AlarmForegroundService : Service() {

    companion object {
        const val CHANNEL_ID_PERSISTENT = "alarm_service_persistent"
        const val ACTION_STOP_ALARM = "com.wakealarm.STOP_ALARM"
        const val EXTRA_SERVER_URL = "server_url"
        const val NOTIFICATION_ID = 1

        // Exposed so AlarmActivity's Stop button can silence playback immediately.
        var isAlarmSounding = false
            private set
    }

    private var webSocket: WebSocket? = null
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectAttempt = 0
    private var serverUrl: String = ""
    private var shouldRun = false

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALARM) {
            stopAlarmSound()
            return START_STICKY
        }

        val url = intent?.getStringExtra(EXTRA_SERVER_URL)
        if (!url.isNullOrBlank()) {
            serverUrl = url
        }

        shouldRun = true
        startForeground(NOTIFICATION_ID, buildPersistentNotification("Connecting..."))
        connect()
        return START_STICKY
    }

    override fun onDestroy() {
        shouldRun = false
        webSocket?.close(1000, "Service stopped")
        stopAlarmSound()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------- WebSocket handling --------------------

    private fun connect() {
        if (!shouldRun || serverUrl.isBlank()) return

        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                updatePersistentNotification("Connected — waiting")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.optString("type") == "ALARM") {
                        triggerAlarm()
                    }
                } catch (e: Exception) {
                    // Ignore malformed messages
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                updatePersistentNotification("Disconnected — retrying")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                updatePersistentNotification("Connection error — retrying")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!shouldRun) return
        reconnectAttempt++
        val delaySeconds = minOf(30, 3 * reconnectAttempt).toLong()
        handler.postDelayed({ connect() }, delaySeconds * 1000)
    }

    // -------------------- Alarm playback --------------------

    private fun triggerAlarm() {
        isAlarmSounding = true
        updatePersistentNotification("ALARM TRIGGERED")

        // Wake the screen briefly so the full-screen activity is visible.
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock?.release()
        wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "WakeAlarm:AlarmWakeLock"
        )
        wakeLock?.acquire(60_000)

        playAlarmSound()

        val alarmIntent = Intent(this, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(alarmIntent)
    }

    private fun playAlarmSound() {
        stopAlarmSoundInternal()

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Push the ALARM stream to max so it's actually loud regardless of media/ringer volume.
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

        val alarmUri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(this@AlarmForegroundService, alarmUri)
            isLooping = true
            prepare()
            start()
        }
    }

    fun stopAlarmSound() {
        isAlarmSounding = false
        stopAlarmSoundInternal()
        wakeLock?.let { if (it.isHeld) it.release() }
        updatePersistentNotification("Connected — waiting")
    }

    private fun stopAlarmSoundInternal() {
        mediaPlayer?.apply {
            try {
                if (isPlaying) stop()
            } catch (e: Exception) { /* ignore */ }
            release()
        }
        mediaPlayer = null
    }

    // -------------------- Notifications --------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID_PERSISTENT,
            "Alarm connection status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows whether the wake-up alarm connection is active"
            setSound(null, null)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildPersistentNotification(statusText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID_PERSISTENT)
            .setContentTitle("Wake Alarm")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()
    }

    private fun updatePersistentNotification(statusText: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildPersistentNotification(statusText))
    }
}

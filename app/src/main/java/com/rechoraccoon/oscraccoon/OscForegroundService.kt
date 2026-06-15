package com.rechoraccoon.oscraccoon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class OscForegroundService : Service() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val ACTION_UPDATE = "UPDATE"
        const val EXTRA_MAIN_TEMPLATE = "main_template"
        const val EXTRA_CYCLING_MESSAGES = "cycling_messages"
        const val EXTRA_CYCLE_INTERVAL = "cycle_interval"
        var isRunning = false
        var randomCycling = false

        fun formatTemplate(template: String, nowPlaying: NowPlaying, currentCycling: String): String {
            val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            // Duration from local player if available, otherwise blank
            val duration = if (LocalMediaState.durationMs > 0) {
                val posMin = (LocalMediaState.positionMs / 1000) / 60
                val posSec = (LocalMediaState.positionMs / 1000) % 60
                val durMin = (LocalMediaState.durationMs / 1000) / 60
                val durSec = (LocalMediaState.durationMs / 1000) % 60
                "%d:%02d/%d:%02d".format(posMin, posSec, durMin, durSec)
            } else ""
            return template
                .replace("{song}", nowPlaying.title.ifEmpty { "Nothing Playing" })
                .replace("{artist}", nowPlaying.artist.ifEmpty { "Unknown" })
                .replace("{duration}", duration)
                .replace("{cycling}", currentCycling)
                .replace("{time}", time)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var mainTemplate = "🎵 {song} by {artist}\n{cycling}\n{time}"
    @Volatile private var cyclingMessages = listOf<String>()
    @Volatile private var cycleIntervalSeconds = 1
    private var currentCycleIndex = 0
    private var cycleTickCounter = 0
    private val random = Random()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); createNotificationChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                mainTemplate = intent.getStringExtra(EXTRA_MAIN_TEMPLATE) ?: mainTemplate
                cyclingMessages = intent.getStringArrayListExtra(EXTRA_CYCLING_MESSAGES) ?: listOf()
                cycleIntervalSeconds = intent.getIntExtra(EXTRA_CYCLE_INTERVAL, 1)
                startForeground(1, buildNotification())
                acquireWakeLock()
                isRunning = true
                startOscLoop()
            }
            ACTION_UPDATE -> {
                mainTemplate = intent?.getStringExtra(EXTRA_MAIN_TEMPLATE) ?: mainTemplate
                cyclingMessages = intent?.getStringArrayListExtra(EXTRA_CYCLING_MESSAGES) ?: listOf()
                cycleIntervalSeconds = intent?.getIntExtra(EXTRA_CYCLE_INTERVAL, 1) ?: 1
                if (currentCycleIndex >= cyclingMessages.size) currentCycleIndex = 0
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
        wakeLock?.release()
        OscSender.clearChatbox()
    }

    private fun startOscLoop() {
        serviceScope.launch {
            while (isActive) {
                val nowPlaying = LastFmService.nowPlaying.value
                cycleTickCounter++
                if (cycleTickCounter >= cycleIntervalSeconds) {
                    cycleTickCounter = 0
                    if (cyclingMessages.isNotEmpty()) {
                        currentCycleIndex = if (randomCycling && cyclingMessages.size > 1) {
                            var next: Int
                            do { next = random.nextInt(cyclingMessages.size) } while (next == currentCycleIndex)
                            next
                        } else (currentCycleIndex + 1) % cyclingMessages.size
                    }
                }
                val cycling = if (cyclingMessages.isNotEmpty()) cyclingMessages[currentCycleIndex] else ""
                OscSender.sendChatboxMessage(formatTemplate(mainTemplate, nowPlaying, cycling))
                delay(1000L)
            }
        }
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel("osc_raccoon_service", "OSC Raccoon Service", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, "osc_raccoon_service")
            .setContentTitle("OSC Raccoon Running")
            .setContentText("Sending music info to VRChat chatbox")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

    private fun acquireWakeLock() {
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OSCRaccoon::WakeLock")
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
    }
}

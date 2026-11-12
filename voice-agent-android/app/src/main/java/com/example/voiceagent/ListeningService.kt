package com.example.voiceagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps a small "Voice Agent is listening" notification while always-listening is enabled.
 * Android requires this for an app to keep using the microphone in the background,
 * and it gives you a one-tap "Disable" button.
 */
class ListeningService : Service() {

    companion object {
        private const val CHANNEL = "listening"
        private const val ACTION_DISABLE = "com.example.voiceagent.DISABLE"

        fun start(ctx: Context) {
            try { ctx.startForegroundService(Intent(ctx, ListeningService::class.java)) } catch (_: Exception) {}
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, ListeningService::class.java)) } catch (_: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISABLE) {
            Prefs.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Always listening", NotificationManager.IMPORTANCE_LOW))
        val disable = PendingIntent.getService(
            this, 1, Intent(this, ListeningService::class.java).setAction(ACTION_DISABLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val wake = Prefs.wakeWord(this)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Voice Agent is listening")
            .setContentText("Say “$wake”, then your command")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Disable", disable).build())
            .build()
        try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: Exception) {
            // Android refused (e.g. mic permission missing). The agent keeps working without it.
            stopSelf()
        }
        return START_NOT_STICKY
    }
}

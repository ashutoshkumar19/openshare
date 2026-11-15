package com.openshare.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class TransferForegroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Transfer active - keep app open"
        ensureForeground(text)
        return START_STICKY
    }

    private fun ensureForeground(text: String) {
        val channelId = "openshare_transfer"
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(channelId, "Transfers", NotificationManager.IMPORTANCE_LOW)
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
            val notif: Notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("OpenShare")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Throwable) {
            // FGS/notification may fail (e.g. missing POST_NOTIFICATIONS on Android 13+); keep service running best-effort
            try { startService(Intent(this, javaClass)) } catch (_: Throwable) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIF_ID = 1001
        const val EXTRA_TEXT = "text"
        fun start(ctx: Context, text: String = "Transfer active - keep app open") {
            try {
                val i = Intent(ctx, TransferForegroundService::class.java).putExtra(EXTRA_TEXT, text)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Throwable) {}
        }
        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, TransferForegroundService::class.java)) } catch (_: Throwable) {}
        }
    }
}

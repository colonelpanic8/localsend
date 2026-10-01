package org.localsend.localsend_app.eva

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.localsend.localsend_app.R

/**
 * Foreground service that keeps the process alive while an EVA send waits for
 * its receiver to close the message. Starting it can be refused when nothing
 * visible is bound to LocalSend; the send then continues without it.
 */
class EvaSendService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alias = intent?.getStringExtra(EXTRA_ALIAS).orEmpty()
        val notification = notification(this, alias)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Stopping before startForeground crashes the app, so a release that came first takes effect here.
        val stillWanted = synchronized(lock) {
            if (wanted) running = this
            wanted
        }
        if (!stillWanted) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        synchronized(lock) { if (running === this) running = null }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // The daily data-sync allowance ran out; sends continue unprotected.
        stopSelf()
    }

    class KeepAlive(private val context: Context) : SendTransfers.KeepAlive {
        override fun hold(deviceAlias: String) {
            synchronized(lock) { wanted = true }
            val intent = Intent(context, EvaSendService::class.java).putExtra(EXTRA_ALIAS, deviceAlias)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        override fun release() {
            val service = synchronized(lock) {
                wanted = false
                running
            }
            service?.stopSelf()
        }
    }

    companion object {
        private const val CHANNEL_ID = "eva_sends"
        private const val NOTIFICATION_ID = 0x45564131
        private const val EXTRA_ALIAS = "alias"

        private val lock = Any()
        private var wanted = false
        private var running: EvaSendService? = null

        private fun notification(context: Context, alias: String): Notification {
            val text = if (alias.isEmpty()) "Waiting for the message to be closed" else "Showing on $alias until it is closed there"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Sends from EVA", NotificationManager.IMPORTANCE_LOW))
                return Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher_quicktile_foreground)
                    .setContentTitle("Sending text from EVA")
                    .setContentText(text)
                    .setOngoing(true)
                    .build()
            }
            @Suppress("DEPRECATION")
            return Notification.Builder(context)
                .setSmallIcon(R.mipmap.ic_launcher_quicktile_foreground)
                .setContentTitle("Sending text from EVA")
                .setContentText(text)
                .setOngoing(true)
                .build()
        }
    }
}

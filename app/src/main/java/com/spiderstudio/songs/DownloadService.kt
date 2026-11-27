package com.spiderstudio.songs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** خدمة أمامية فقط لإبقاء التطبيق حياً أثناء التنزيل وعرض الإشعار. المنطق كله بـ Queue. */
class DownloadService : Service() {

    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "التنزيلات", NotificationManager.IMPORTANCE_LOW)
        )
        Queue.notifier = { pct, text -> nm.notify(NOTIF_ID, build(pct, text)) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIF_ID,
            build(0, "جاري التحضير..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Queue.notifier = null
        super.onDestroy()
    }

    private fun build(pct: Int, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Amrtune")
            .setContentText(text)
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1
    }
}

package com.marvis.ai.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import androidx.core.content.IntentCompat

/** Foreground service required by Android 10+ to hold a MediaProjection (type mediaProjection on 14+). */
class ScreenCaptureService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("capture", "Screen sharing", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "capture")
            .setContentTitle("Marvis can see your screen")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true).build()
        startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, "data", Intent::class.java) }
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        ScreenCaptureHelper.attach(this, mgr.getMediaProjection(code, data))
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() { ScreenCaptureHelper.detach(); super.onDestroy() }
}

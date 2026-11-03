package com.marvis.ai.device

import android.service.notification.NotificationListenerService

/**
 * Intentionally empty. Android only lets an app control other apps' media sessions (play, pause, seek) if it holds
 * an enabled notification listener, so this class exists purely to unlock MediaSession access. Marvis never reads,
 * stores or sends your notifications. Without it, media commands fall back to plain media key presses.
 */
class MarvisNotificationListener : NotificationListenerService()

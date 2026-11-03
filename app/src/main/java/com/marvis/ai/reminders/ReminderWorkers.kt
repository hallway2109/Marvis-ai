package com.marvis.ai.reminders

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.marvis.ai.MainActivity
import com.marvis.ai.MarvisApp
import com.marvis.ai.ai.Emotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1)
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInputData(workDataOf("id" to id))
                .build())
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { (context.applicationContext as MarvisApp).reminders.rescheduleAll() } finally { pending.finish() }
        }
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MarvisApp
        val r = app.db.reminderDao().get(inputData.getLong("id", -1)) ?: return Result.success()
        val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, MarvisApp.CHANNEL_REMINDERS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (r.kind == "alarm") "Marvis alarm" else "Marvis reminder")
            .setContentText(r.title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (r.kind == "alarm") NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open).setAutoCancel(true).build()
        app.getSystemService(NotificationManager::class.java).notify(r.id.toInt(), n)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            app.brain.say("Reminder: ${r.title}", Emotion.EXCITED)   // voice feedback
        }
        app.reminders.onFired(r)
        return Result.success()
    }
}

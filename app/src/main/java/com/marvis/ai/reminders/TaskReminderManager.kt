package com.marvis.ai.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Room = source of truth. AlarmManager = precise wake-up. WorkManager (ReminderWorker) = fires the notification
 * and re-arms repeating items, so everything survives reboots (BootReceiver) and process death.
 */
class TaskReminderManager(private val ctx: Context, private val dao: ReminderDao) {
    private val alarms = ctx.getSystemService(AlarmManager::class.java)

    fun observe() = dao.observeActive()
    suspend fun list() = dao.active()

    suspend fun create(title: String, at: LocalDateTime, repeat: Repeat = Repeat.NONE, kind: String = "reminder"): Reminder {
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val r = Reminder(title = title, triggerAt = millis, repeat = repeat.name, kind = kind)
        val saved = r.copy(id = dao.insert(r))
        schedule(saved)
        return saved
    }

    suspend fun cancel(id: Long) {
        alarms.cancel(pending(id))
        dao.delete(id)
    }

    suspend fun rescheduleAll() = dao.active().forEach { schedule(it) }

    /** Called by the worker after the notification fired. */
    suspend fun onFired(r: Reminder) {
        val next = when (Repeat.from(r.repeat)) {
            Repeat.DAILY -> r.triggerAt + 24L * 3600_000
            Repeat.WEEKLY -> r.triggerAt + 7L * 24 * 3600_000
            Repeat.NONE -> null
        }
        if (next == null) dao.update(r.copy(done = true))
        else { val n = r.copy(triggerAt = maxOf(next, System.currentTimeMillis() + 60_000)); dao.update(n); schedule(n) }
    }

    fun canScheduleExact() = alarms.canScheduleExactAlarms()

    private fun schedule(r: Reminder) {
        val pi = pending(r.id)
        if (canScheduleExact()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAt, pi)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAt, pi) // inexact fallback
    }

    private fun pending(id: Long) = PendingIntent.getBroadcast(
        ctx, id.toInt(),
        Intent(ctx, ReminderReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    @Suppress("unused") fun describe(r: Reminder) = Instant.ofEpochMilli(r.triggerAt)
}

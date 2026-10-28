package com.marvis.ai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.room.Room
import com.marvis.ai.ai.MarvisBrain
import com.marvis.ai.ai.SettingsStore
import com.marvis.ai.memory.MemoryManager
import com.marvis.ai.reminders.MarvisDatabase
import com.marvis.ai.reminders.TaskReminderManager
import com.marvis.ai.security.SecurityStore

class MarvisApp : Application() {
    companion object {
        const val CHANNEL_REMINDERS = "reminders"
        lateinit var instance: MarvisApp
    }

    lateinit var db: MarvisDatabase
    val settings by lazy { SettingsStore(this) }
    val security by lazy { SecurityStore(this) }
    val memory by lazy { MemoryManager(this) }
    val reminders by lazy { TaskReminderManager(this, db.reminderDao()) }
    /** One brain for the whole process: shared by the voice service, overlay orb, UI and reminders. */
    val brain by lazy { MarvisBrain(this, settings, memory, security) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = Room.databaseBuilder(this, MarvisDatabase::class.java, "marvis.db").build()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Reminders & alarms", NotificationManager.IMPORTANCE_HIGH))
    }
}

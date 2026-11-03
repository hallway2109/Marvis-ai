package com.marvis.ai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.room.Room
import com.marvis.ai.ai.MarvisBrain
import com.marvis.ai.ai.SettingsStore
import com.marvis.ai.device.SmartAudio
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
    /** Fades media down while Marvis talks and rewinds it afterwards. */
    val smartAudio by lazy {
        SmartAudio(this) { brain.state.value.speaking }.also { sa ->
            sa.enabled = { settings.smartMute.value }
            sa.onNote = { note -> brain.note(note) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = Room.databaseBuilder(this, MarvisDatabase::class.java, "marvis.db").build()
        smartAudio.restoreIfNeeded()          // a crash while muted must never leave the volume at zero
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Reminders & alarms", NotificationManager.IMPORTANCE_HIGH))
    }
}

package com.marvis.ai.reminders

import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class Repeat {
    NONE, DAILY, WEEKLY;
    companion object { fun from(s: String?) = entries.firstOrNull { it.name.equals(s, true) } ?: NONE }
}

@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val triggerAt: Long,          // epoch millis
    val repeat: String = Repeat.NONE.name,
    val kind: String = "reminder", // reminder | alarm | task
    val done: Boolean = false,
)

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE done = 0 ORDER BY triggerAt") fun observeActive(): Flow<List<Reminder>>
    @Query("SELECT * FROM reminders WHERE done = 0 ORDER BY triggerAt") suspend fun active(): List<Reminder>
    @Query("SELECT * FROM reminders WHERE id = :id") suspend fun get(id: Long): Reminder?
    @Insert suspend fun insert(r: Reminder): Long
    @Update suspend fun update(r: Reminder)
    @Query("DELETE FROM reminders WHERE id = :id") suspend fun delete(id: Long)
}

@Database(entities = [Reminder::class], version = 1, exportSchema = false)
abstract class MarvisDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao
}

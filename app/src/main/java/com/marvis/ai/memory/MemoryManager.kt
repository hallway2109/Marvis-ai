package com.marvis.ai.memory

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "memories")
data class Memory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<Memory>>

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<Memory>

    @Query("SELECT * FROM memories WHERE text LIKE '%' || :kw || '%' ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun search(kw: String, limit: Int): List<Memory>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun get(id: Long): Memory?

    @Query("SELECT COALESCE(SUM(LENGTH(CAST(text AS BLOB))), 0) FROM memories")
    suspend fun usedBytes(): Long

    @Query("SELECT COALESCE(SUM(LENGTH(CAST(text AS BLOB))), 0) FROM memories")
    fun observeUsedBytes(): Flow<Long>

    @Query("SELECT COUNT(*) FROM memories")
    fun observeCount(): Flow<Int>

    @Insert suspend fun insert(m: Memory): Long
    @Update suspend fun update(m: Memory)
    @Query("DELETE FROM memories WHERE id = :id") suspend fun delete(id: Long)
}

@Database(entities = [Memory::class], version = 1, exportSchema = false)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun dao(): MemoryDao
}

enum class SaveResult { OK, EMPTY, FULL }

/**
 * Persistent long-term memory (Room/SQLite) capped at 50 MB of text.
 * Entries are individual notes; the AI sees only the few most relevant ones per question, never the whole 50 MB.
 * Stored in the app's private storage (not encrypted).
 */
class MemoryManager(ctx: Context) {
    companion object {
        const val CAPACITY_BYTES = 50L * 1024 * 1024
        const val MAX_ENTRY_CHARS = 20_000
        private val STOP_WORDS = setOf(
            "this", "that", "with", "what", "have", "from", "your", "about", "tell", "please", "marvis",
            "kya", "hai", "mujhe", "batao", "aap", "mera", "meri", "kaise", "kitna")
    }

    private val dao = Room.databaseBuilder(ctx.applicationContext, MemoryDatabase::class.java, "marvis_memory.db")
        .build().dao()

    fun observeRecent(limit: Int) = dao.observeRecent(limit)
    fun observeUsedBytes() = dao.observeUsedBytes()
    fun observeCount() = dao.observeCount()
    suspend fun usedBytes() = dao.usedBytes()

    suspend fun add(text: String): SaveResult {
        val t = text.trim().take(MAX_ENTRY_CHARS)
        if (t.isEmpty()) return SaveResult.EMPTY
        if (dao.usedBytes() + t.toByteArray().size > CAPACITY_BYTES) return SaveResult.FULL
        val now = System.currentTimeMillis()
        dao.insert(Memory(text = t, createdAt = now, updatedAt = now))
        return SaveResult.OK
    }

    suspend fun update(id: Long, text: String): SaveResult {
        val t = text.trim().take(MAX_ENTRY_CHARS)
        if (t.isEmpty()) return SaveResult.EMPTY
        val old = dao.get(id) ?: return SaveResult.EMPTY
        val delta = t.toByteArray().size - old.text.toByteArray().size
        if (delta > 0 && dao.usedBytes() + delta > CAPACITY_BYTES) return SaveResult.FULL
        dao.update(old.copy(text = t, updatedAt = System.currentTimeMillis()))
        return SaveResult.OK
    }

    suspend fun delete(id: Long) = dao.delete(id)

    /** Voice path: "Remember this" / "Ye memory me save karo". */
    suspend fun saveFromVoice(text: String): SaveResult = add(text)

    /** A small, relevant slice of memory (recent notes plus keyword matches) to put in the prompt. */
    suspend fun buildContext(query: String, maxChars: Int = 2500): String {
        val found = LinkedHashMap<Long, Memory>()
        dao.recent(4).forEach { found[it.id] = it }
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 4 && it !in STOP_WORDS }
            .distinct().sortedByDescending { it.length }.take(4)
        for (w in words) dao.search(w, 3).forEach { found[it.id] = it }
        val sb = StringBuilder()
        for (m in found.values) {
            val line = "- " + m.text.replace('\n', ' ').take(600)
            if (sb.length + line.length > maxChars) break
            sb.append(line).append('\n')
        }
        return sb.toString().trim()
    }
}

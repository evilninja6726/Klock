package com.klock.app.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "alarms",
    indices = [Index(value = ["enabled", "nextTriggerEpochMillis"])]
)
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val hour: Int,
    val minute: Int,
    val label: String,
    val enabled: Boolean,
    val repeatDaysCsv: String,
    val selectedTopicIdsCsv: String,
    val difficultyPreset: String,
    val requiredQuestions: Int,
    val vibrate: Boolean,
    val ringtoneUri: String?,
    val nextTriggerEpochMillis: Long?,
    val createdAtMillis: Long
)

@Entity(tableName = "active_alarm_sessions")
data class ActiveAlarmSessionEntity(
    @PrimaryKey val slotKey: Int = ACTIVE_SLOT_KEY,
    val sessionId: String,
    val payloadJson: String,
    val isCompleted: Boolean,
    val updatedAtMillis: Long
) {
    companion object {
        const val ACTIVE_SLOT_KEY = 1
        const val LAST_COMPLETED_SLOT_KEY = 2
    }
}

@Entity(
    tableName = "question_history",
    indices = [
        Index(value = ["fingerprint"]),
        Index(value = ["answeredAtMillis"]),
        Index(value = ["topicId"])
    ]
)
data class QuestionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: String,
    val alarmId: Long,
    val fingerprint: String,
    val topicId: String,
    val topicName: String,
    val subtopicName: String?,
    val difficulty: String,
    val format: String,
    val provenance: String,
    val prompt: String,
    val codeSnippet: String?,
    val userAnswer: String,
    val canonicalAnswer: String,
    val explanation: String,
    val sourceTitle: String,
    val sourceProvider: String,
    val sourceUrl: String,
    val attemptsTaken: Int,
    val solvedCorrectly: Boolean,
    val answeredAtMillis: Long
)

@Entity(tableName = "topic_stats")
data class TopicStatEntity(
    @PrimaryKey val topicId: String,
    val totalSolved: Int,
    val firstTrySolved: Int,
    val totalWrongAttempts: Int,
    val lastAskedAtMillis: Long
)

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY hour ASC, minute ASC, id ASC")
    fun observeAll(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms ORDER BY hour ASC, minute ASC, id ASC")
    suspend fun getAll(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE enabled = 1 ORDER BY hour ASC, minute ASC")
    suspend fun getEnabled(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): AlarmEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(entity: AlarmEntity): Long

    @Query("UPDATE alarms SET enabled = :enabled, nextTriggerEpochMillis = :nextTrigger WHERE id = :id")
    suspend fun updateEnabledAndTrigger(id: Long, enabled: Boolean, nextTrigger: Long?)

    @Query("UPDATE alarms SET nextTriggerEpochMillis = :nextTrigger WHERE id = :id")
    suspend fun updateNextTrigger(id: Long, nextTrigger: Long?)

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM active_alarm_sessions WHERE slotKey = :slotKey LIMIT 1")
    fun observeSlot(slotKey: Int): Flow<ActiveAlarmSessionEntity?>

    @Query("SELECT * FROM active_alarm_sessions WHERE slotKey = :slotKey LIMIT 1")
    suspend fun getSlot(slotKey: Int): ActiveAlarmSessionEntity?

    @Upsert
    suspend fun upsertSlot(entity: ActiveAlarmSessionEntity)

    @Query("DELETE FROM active_alarm_sessions WHERE slotKey = :slotKey")
    suspend fun deleteSlot(slotKey: Int)
}

@Dao
interface QuestionHistoryDao {
    @Query("SELECT * FROM question_history ORDER BY answeredAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<QuestionHistoryEntity>>

    @Query("SELECT DISTINCT fingerprint FROM question_history ORDER BY answeredAtMillis DESC LIMIT :limit")
    suspend fun getRecentFingerprints(limit: Int): List<String>

    @Query("SELECT DISTINCT sourceUrl FROM question_history ORDER BY answeredAtMillis DESC LIMIT :limit")
    suspend fun getRecentSourceUrls(limit: Int): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: QuestionHistoryEntity): Long

    @Query(
        """
        DELETE FROM question_history
        WHERE id NOT IN (
            SELECT id FROM question_history ORDER BY answeredAtMillis DESC LIMIT :keepLatest
        )
        """
    )
    suspend fun pruneOld(keepLatest: Int)

    @Query("SELECT * FROM topic_stats")
    suspend fun getAllTopicStats(): List<TopicStatEntity>

    @Query("SELECT * FROM topic_stats WHERE topicId = :topicId LIMIT 1")
    suspend fun getTopicStat(topicId: String): TopicStatEntity?

    @Upsert
    suspend fun upsertTopicStat(entity: TopicStatEntity)
}

@Database(
    entities = [
        AlarmEntity::class,
        ActiveAlarmSessionEntity::class,
        QuestionHistoryEntity::class,
        TopicStatEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class KlockDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun sessionDao(): SessionDao
    abstract fun questionHistoryDao(): QuestionHistoryDao

    companion object {
        fun build(context: Context, lowRamMode: Boolean): KlockDatabase {
            val journalMode = if (lowRamMode) {
                JournalMode.TRUNCATE
            } else {
                JournalMode.WRITE_AHEAD_LOGGING
            }
            return Room.databaseBuilder(
                context.applicationContext,
                KlockDatabase::class.java,
                "klock.db"
            )
                .setJournalMode(journalMode)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}

package com.klock.app.domain.repository

import com.klock.app.domain.model.ActiveAlarmSession
import com.klock.app.domain.model.Alarm
import com.klock.app.domain.model.AlarmQuestion
import com.klock.app.domain.model.AppSettings
import com.klock.app.domain.model.DifficultyPreset
import com.klock.app.domain.model.QuestionHistoryRecord
import com.klock.app.domain.model.TopicConfig
import com.klock.app.domain.model.TopicPerformanceStat
import kotlinx.coroutines.flow.Flow

interface AlarmRepository {
    fun observeAlarms(): Flow<List<Alarm>>
    suspend fun getAllAlarms(): List<Alarm>
    suspend fun getEnabledAlarms(): List<Alarm>
    suspend fun getAlarmById(id: Long): Alarm?
    suspend fun upsertAlarm(alarm: Alarm): Long
    suspend fun setAlarmEnabled(id: Long, enabled: Boolean, nextTriggerMillis: Long?)
    suspend fun updateNextTrigger(id: Long, nextTriggerMillis: Long?)
    suspend fun deleteAlarm(id: Long)
}

interface SessionRepository {
    fun observeActiveSession(): Flow<ActiveAlarmSession?>
    suspend fun getActiveSession(): ActiveAlarmSession?
    suspend fun saveActiveSession(session: ActiveAlarmSession)
    suspend fun clearActiveSession()
    fun observeCompletedSession(sessionId: String): Flow<ActiveAlarmSession?>
    suspend fun getLastCompletedSession(): ActiveAlarmSession?
}

interface QuestionHistoryRepository {
    fun observeRecentHistory(limit: Int = 100): Flow<List<QuestionHistoryRecord>>
    suspend fun getRecentFingerprints(limit: Int = 300): Set<String>
    suspend fun getRecentSourceUrls(limit: Int = 60): Set<String>
    suspend fun recordQuestionOutcome(record: QuestionHistoryRecord)
    suspend fun getTopicPerformanceStats(): Map<String, TopicPerformanceStat>
    suspend fun pruneOldFingerprints(keepLatest: Int = 500)
}

interface SettingsRepository {
    val settingsFlow: Flow<AppSettings>
    suspend fun getSettings(): AppSettings
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings)
}

interface QuestionEngine {
    suspend fun fetchFreshQuestions(
        topics: List<TopicConfig>,
        difficultyPreset: DifficultyPreset,
        count: Int = Alarm.DEFAULT_REQUIRED_QUESTIONS,
        excludedFingerprints: Set<String> = emptySet()
    ): Result<List<AlarmQuestion>>
}

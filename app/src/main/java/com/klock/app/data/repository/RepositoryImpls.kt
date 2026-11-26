package com.klock.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.klock.app.core.security.SecurityGuards
import com.klock.app.data.local.ActiveAlarmSessionEntity
import com.klock.app.data.local.AlarmDao
import com.klock.app.data.local.AlarmEntity
import com.klock.app.data.local.QuestionHistoryDao
import com.klock.app.data.local.QuestionHistoryEntity
import com.klock.app.data.local.SessionDao
import com.klock.app.data.local.TopicStatEntity
import com.klock.app.domain.model.ActiveAlarmSession
import com.klock.app.domain.model.Alarm
import com.klock.app.domain.model.AppSettings
import com.klock.app.domain.model.DifficultyPreset
import com.klock.app.domain.model.QuestionDifficulty
import com.klock.app.domain.model.QuestionFormat
import com.klock.app.domain.model.QuestionHistoryRecord
import com.klock.app.domain.model.QuestionProvenance
import com.klock.app.domain.model.ThemePreference
import com.klock.app.domain.model.TopicConfig
import com.klock.app.domain.model.TopicPerformanceStat
import com.klock.app.domain.model.UserProfile
import com.klock.app.domain.repository.AlarmRepository
import com.klock.app.domain.repository.QuestionHistoryRepository
import com.klock.app.domain.repository.SessionRepository
import com.klock.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.klockDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "klock_settings"
)

class AlarmRepositoryImpl(
    private val alarmDao: AlarmDao
) : AlarmRepository {

    override fun observeAlarms(): Flow<List<Alarm>> =
        alarmDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getAllAlarms(): List<Alarm> =
        alarmDao.getAll().map { it.toDomain() }

    override suspend fun getEnabledAlarms(): List<Alarm> =
        alarmDao.getEnabled().map { it.toDomain() }

    override suspend fun getAlarmById(id: Long): Alarm? =
        alarmDao.getById(id)?.toDomain()

    override suspend fun upsertAlarm(alarm: Alarm): Long =
        alarmDao.insertOrReplace(alarm.toEntity())

    override suspend fun setAlarmEnabled(id: Long, enabled: Boolean, nextTriggerMillis: Long?) {
        alarmDao.updateEnabledAndTrigger(id, enabled, nextTriggerMillis)
    }

    override suspend fun updateNextTrigger(id: Long, nextTriggerMillis: Long?) {
        alarmDao.updateNextTrigger(id, nextTriggerMillis)
    }

    override suspend fun deleteAlarm(id: Long) {
        alarmDao.deleteById(id)
    }
}

class SessionRepositoryImpl(
    private val sessionDao: SessionDao,
    private val json: Json = DefaultRepoJson
) : SessionRepository {

    override fun observeActiveSession(): Flow<ActiveAlarmSession?> =
        sessionDao.observeSlot(ActiveAlarmSessionEntity.ACTIVE_SLOT_KEY).map { entity ->
            entity?.let { decodeSessionSafely(it.payloadJson) }
        }

    override suspend fun getActiveSession(): ActiveAlarmSession? =
        sessionDao.getSlot(ActiveAlarmSessionEntity.ACTIVE_SLOT_KEY)
            ?.let { decodeSessionSafely(it.payloadJson) }

    override suspend fun saveActiveSession(session: ActiveAlarmSession) {
        val encoded = json.encodeToString(session)
        if (session.completed) {
            sessionDao.upsertSlot(
                ActiveAlarmSessionEntity(
                    slotKey = ActiveAlarmSessionEntity.LAST_COMPLETED_SLOT_KEY,
                    sessionId = session.sessionId,
                    payloadJson = encoded,
                    isCompleted = true,
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
            sessionDao.deleteSlot(ActiveAlarmSessionEntity.ACTIVE_SLOT_KEY)
        } else {
            sessionDao.upsertSlot(
                ActiveAlarmSessionEntity(
                    slotKey = ActiveAlarmSessionEntity.ACTIVE_SLOT_KEY,
                    sessionId = session.sessionId,
                    payloadJson = encoded,
                    isCompleted = false,
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
        }
    }

    override suspend fun clearActiveSession() {
        sessionDao.deleteSlot(ActiveAlarmSessionEntity.ACTIVE_SLOT_KEY)
    }

    override fun observeCompletedSession(sessionId: String): Flow<ActiveAlarmSession?> =
        sessionDao.observeSlot(ActiveAlarmSessionEntity.LAST_COMPLETED_SLOT_KEY).map { entity ->
            entity?.let { decodeSessionSafely(it.payloadJson) }
                ?.takeIf { it.sessionId == sessionId || sessionId.isBlank() }
        }

    override suspend fun getLastCompletedSession(): ActiveAlarmSession? =
        sessionDao.getSlot(ActiveAlarmSessionEntity.LAST_COMPLETED_SLOT_KEY)
            ?.let { decodeSessionSafely(it.payloadJson) }

    private fun decodeSessionSafely(payload: String): ActiveAlarmSession? =
        try {
            json.decodeFromString<ActiveAlarmSession>(payload)
        } catch (_: Exception) {
            null
        }

    companion object {
        val DefaultRepoJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

class QuestionHistoryRepositoryImpl(
    private val dao: QuestionHistoryDao
) : QuestionHistoryRepository {

    override fun observeRecentHistory(limit: Int): Flow<List<QuestionHistoryRecord>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getRecentFingerprints(limit: Int): Set<String> =
        dao.getRecentFingerprints(limit).toSet()

    override suspend fun getRecentSourceUrls(limit: Int): Set<String> =
        dao.getRecentSourceUrls(limit).toSet()

    override suspend fun recordQuestionOutcome(record: QuestionHistoryRecord) {
        dao.insert(record.toEntity())
        if (record.solvedCorrectly) {
            val current = dao.getTopicStat(record.topicId)
            val wrongDelta = (record.attemptsTaken - 1).coerceAtLeast(0)
            val firstTryInc = if (record.attemptsTaken <= 1) 1 else 0
            val updated = if (current == null) {
                TopicStatEntity(
                    topicId = record.topicId,
                    totalSolved = 1,
                    firstTrySolved = firstTryInc,
                    totalWrongAttempts = wrongDelta,
                    lastAskedAtMillis = record.answeredAtMillis
                )
            } else {
                current.copy(
                    totalSolved = current.totalSolved + 1,
                    firstTrySolved = current.firstTrySolved + firstTryInc,
                    totalWrongAttempts = current.totalWrongAttempts + wrongDelta,
                    lastAskedAtMillis = maxOf(current.lastAskedAtMillis, record.answeredAtMillis)
                )
            }
            dao.upsertTopicStat(updated)
        }
    }

    override suspend fun getTopicPerformanceStats(): Map<String, TopicPerformanceStat> {
        return dao.getAllTopicStats().associate { row ->
            row.topicId to TopicPerformanceStat(
                topicId = row.topicId,
                totalSolved = row.totalSolved,
                firstTrySolved = row.firstTrySolved,
                totalWrongAttempts = row.totalWrongAttempts,
                lastAskedAtMillis = row.lastAskedAtMillis
            )
        }
    }

    override suspend fun pruneOldFingerprints(keepLatest: Int) {
        dao.pruneOld(keepLatest)
    }
}

class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = SessionRepositoryImpl.DefaultRepoJson
) : SettingsRepository {

    constructor(context: Context) : this(context.applicationContext.klockDataStore)

    override val settingsFlow: Flow<AppSettings> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs -> prefs.toAppSettings() }

    override suspend fun getSettings(): AppSettings = settingsFlow.first()

    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val current = prefs.toAppSettings()
            val updated = transform(current)
            prefs[KEY_THEME] = updated.themePreference.name
            prefs[KEY_DIFFICULTY] = updated.defaultDifficulty.name
            prefs[KEY_TOPICS_JSON] = json.encodeToString(updated.configuredTopics)
            prefs[KEY_USER_PROFILE_JSON] = json.encodeToString(updated.userProfile)
            prefs[KEY_24H] = updated.use24HourFormat
            prefs[KEY_VIBRATE] = updated.vibrateByDefault
            prefs[KEY_GRADUAL_VOLUME] = updated.gradualVolumeRamp
            prefs[KEY_AI_ENABLED] = updated.enableAiSynthesisWhenKeyConfigured
            if (SecurityGuards.isValidPublicHttpsUrl(updated.aiEndpointUrl)) {
                prefs[KEY_AI_ENDPOINT] = updated.aiEndpointUrl.trim()
            }
        }
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val theme = this[KEY_THEME]?.let {
            runCatching { ThemePreference.valueOf(it) }.getOrNull()
        } ?: ThemePreference.SYSTEM
        val diff = this[KEY_DIFFICULTY]?.let {
            runCatching { DifficultyPreset.valueOf(it) }.getOrNull()
        } ?: DifficultyPreset.ADAPTIVE
        val topics = this[KEY_TOPICS_JSON]?.let { encoded ->
            runCatching { json.decodeFromString<List<TopicConfig>>(encoded) }.getOrNull()
        } ?: emptyList()
        val profile = this[KEY_USER_PROFILE_JSON]?.let { encoded ->
            runCatching { json.decodeFromString<UserProfile>(encoded) }.getOrNull()
        } ?: UserProfile()

        return AppSettings(
            themePreference = theme,
            defaultDifficulty = diff,
            configuredTopics = topics,
            userProfile = profile,
            use24HourFormat = this[KEY_24H] ?: false,
            vibrateByDefault = this[KEY_VIBRATE] ?: true,
            gradualVolumeRamp = this[KEY_GRADUAL_VOLUME] ?: false,
            enableAiSynthesisWhenKeyConfigured = this[KEY_AI_ENABLED] ?: false,
            aiEndpointUrl = this[KEY_AI_ENDPOINT]
                ?: "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
        )
    }

    companion object {
        private val KEY_THEME = stringPreferencesKey("theme_preference")
        private val KEY_DIFFICULTY = stringPreferencesKey("default_difficulty")
        private val KEY_TOPICS_JSON = stringPreferencesKey("configured_topics_json")
        private val KEY_USER_PROFILE_JSON = stringPreferencesKey("user_profile_json")
        private val KEY_24H = booleanPreferencesKey("use_24h_format")
        private val KEY_VIBRATE = booleanPreferencesKey("vibrate_by_default")
        private val KEY_GRADUAL_VOLUME = booleanPreferencesKey("gradual_volume_ramp")
        private val KEY_AI_ENABLED = booleanPreferencesKey("ai_synthesis_enabled")
        private val KEY_AI_ENDPOINT = stringPreferencesKey("ai_endpoint_url")
    }
}

interface ByokSecretStore {
    fun getApiKey(): String?
    fun setApiKey(apiKey: String?)
    fun hasApiKey(): Boolean = !getApiKey().isNullOrBlank()
}

class EncryptedByokSecretStore(context: Context) : ByokSecretStore {
    private val appContext = context.applicationContext

    private val prefs: SharedPreferences? by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "klock_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrNull()
    }

    @Volatile
    private var volatileFallbackKey: String? = null

    override fun getApiKey(): String? {
        val store = prefs
        return if (store != null) {
            store.getString(KEY_BYOK_API_KEY, null)?.takeIf { it.isNotBlank() }
        } else {
            volatileFallbackKey
        }
    }

    override fun setApiKey(apiKey: String?) {
        val cleaned = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        val store = prefs
        if (store != null) {
            store.edit().apply {
                if (cleaned == null) remove(KEY_BYOK_API_KEY)
                else putString(KEY_BYOK_API_KEY, cleaned)
            }.apply()
        } else {
            volatileFallbackKey = cleaned
        }
    }

    companion object {
        private const val KEY_BYOK_API_KEY = "byok_cloud_llm_key"
    }
}

// --- Mappers ---

fun AlarmEntity.toDomain(): Alarm {
    val days = repeatDaysCsv.split(",")
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it in 1..7 }
        .toSet()
    val topicIds = selectedTopicIdsCsv.split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()
    val preset = runCatching { DifficultyPreset.valueOf(difficultyPreset) }
        .getOrDefault(DifficultyPreset.ADAPTIVE)
    return Alarm(
        id = id,
        hour = hour.coerceIn(0, 23),
        minute = minute.coerceIn(0, 59),
        label = label,
        enabled = enabled,
        repeatDays = days,
        selectedTopicIds = topicIds,
        difficultyPreset = preset,
        requiredQuestions = requiredQuestions.coerceIn(
            Alarm.MIN_REQUIRED_QUESTIONS,
            Alarm.MAX_REQUIRED_QUESTIONS
        ),
        vibrate = vibrate,
        ringtoneUri = ringtoneUri,
        nextTriggerEpochMillis = nextTriggerEpochMillis,
        createdAtMillis = createdAtMillis
    )
}

fun Alarm.toEntity(): AlarmEntity = AlarmEntity(
    id = id,
    hour = hour.coerceIn(0, 23),
    minute = minute.coerceIn(0, 59),
    label = label.trim().take(80),
    enabled = enabled,
    repeatDaysCsv = repeatDays.filter { it in 1..7 }.sorted().joinToString(","),
    selectedTopicIdsCsv = selectedTopicIds.map { it.trim() }.filter { it.isNotBlank() }.joinToString(","),
    difficultyPreset = difficultyPreset.name,
    requiredQuestions = requiredQuestions.coerceIn(
        Alarm.MIN_REQUIRED_QUESTIONS,
        Alarm.MAX_REQUIRED_QUESTIONS
    ),
    vibrate = vibrate,
    ringtoneUri = ringtoneUri,
    nextTriggerEpochMillis = nextTriggerEpochMillis,
    createdAtMillis = createdAtMillis
)

fun QuestionHistoryEntity.toDomain(): QuestionHistoryRecord = QuestionHistoryRecord(
    id = id,
    sessionId = sessionId,
    alarmId = alarmId,
    fingerprint = fingerprint,
    topicId = topicId,
    topicName = topicName,
    subtopicName = subtopicName,
    difficulty = runCatching { QuestionDifficulty.valueOf(difficulty) }.getOrDefault(QuestionDifficulty.MEDIUM),
    format = runCatching { QuestionFormat.valueOf(format) }.getOrDefault(QuestionFormat.MULTIPLE_CHOICE),
    provenance = runCatching { QuestionProvenance.valueOf(provenance) }.getOrDefault(QuestionProvenance.SOURCE_GROUNDED_EXTRACTED),
    prompt = prompt,
    codeSnippet = codeSnippet,
    userAnswer = userAnswer,
    canonicalAnswer = canonicalAnswer,
    explanation = explanation,
    sourceTitle = sourceTitle,
    sourceProvider = sourceProvider,
    sourceUrl = sourceUrl,
    attemptsTaken = attemptsTaken,
    solvedCorrectly = solvedCorrectly,
    answeredAtMillis = answeredAtMillis
)

fun QuestionHistoryRecord.toEntity(): QuestionHistoryEntity = QuestionHistoryEntity(
    id = id,
    sessionId = sessionId,
    alarmId = alarmId,
    fingerprint = fingerprint,
    topicId = topicId,
    topicName = topicName,
    subtopicName = subtopicName,
    difficulty = difficulty.name,
    format = format.name,
    provenance = provenance.name,
    prompt = prompt,
    codeSnippet = codeSnippet,
    userAnswer = userAnswer,
    canonicalAnswer = canonicalAnswer,
    explanation = explanation,
    sourceTitle = sourceTitle,
    sourceProvider = sourceProvider,
    sourceUrl = sourceUrl,
    attemptsTaken = attemptsTaken,
    solvedCorrectly = solvedCorrectly,
    answeredAtMillis = answeredAtMillis
)

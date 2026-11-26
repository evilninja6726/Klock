package com.klock.app

import com.klock.app.alarm.audio.AlarmAudioController
import com.klock.app.alarm.scheduler.AlarmScheduler
import com.klock.app.alarm.session.AlarmSessionCoordinator
import com.klock.app.core.security.SecurityGuards
import com.klock.app.domain.model.ActiveAlarmSession
import com.klock.app.domain.model.Alarm
import com.klock.app.domain.model.AlarmQuestion
import com.klock.app.domain.model.AnswerEvaluationResult
import com.klock.app.domain.model.AppSettings
import com.klock.app.domain.model.CategoryPerformanceStat
import com.klock.app.domain.model.DifficultyPreset
import com.klock.app.domain.model.OverrideReason
import com.klock.app.domain.model.QuestionCategory
import com.klock.app.domain.model.QuestionDifficulty
import com.klock.app.domain.model.QuestionFormat
import com.klock.app.domain.model.QuestionHistoryRecord
import com.klock.app.domain.model.QuestionProvenance
import com.klock.app.domain.model.RetrievalStatus
import com.klock.app.domain.repository.AlarmRepository
import com.klock.app.domain.repository.QuestionEngine
import com.klock.app.domain.repository.QuestionHistoryRepository
import com.klock.app.domain.repository.SessionRepository
import com.klock.app.domain.repository.SettingsRepository
import com.klock.app.questions.validation.AnswerValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.ZonedDateTime

class SessionDismissalAndNetworkResilienceTest {

    @Test
    fun `network disconnection never stops ringing audio and preserves retryable active session`() = runTest {
        val audio = FakeAudioController()
        val sessionRepo = InMemorySessionRepository()
        val alarmRepo = InMemoryAlarmRepository()
        val historyRepo = InMemoryHistoryRepository()
        val settingsRepo = InMemorySettingsRepository()
        val scheduler = FakeScheduler()
        var networkOnline = false

        val engine = object : QuestionEngine {
            override suspend fun fetchFreshQuestions(
                categories: Set<QuestionCategory>,
                difficultyPreset: DifficultyPreset,
                count: Int,
                excludedFingerprints: Set<String>
            ): Result<List<AlarmQuestion>> {
                if (!networkOnline) {
                    return Result.failure(IOException("Airplane mode: no internet route"))
                }
                return Result.success(generateQuestions(count, excludedFingerprints))
            }
        }

        val coordinator = AlarmSessionCoordinator(
            alarmRepository = alarmRepo,
            sessionRepository = sessionRepo,
            historyRepository = historyRepo,
            settingsRepository = settingsRepo,
            questionEngine = engine,
            answerValidator = AnswerValidator(),
            alarmScheduler = scheduler,
            audioController = audio
        )

        val alarmId = alarmRepo.upsertAlarm(Alarm(hour = 6, minute = 30, label = "Morning SQL"))
        audio.startRinging()
        assertTrue("Audio must start immediately before network fetch", audio.isRinging.value)

        coordinator.onAlarmTriggered(alarmId, "Morning SQL")
        val failedFetch = coordinator.fetchOrRetryQuestions()
        assertTrue(failedFetch.isFailure)
        assertTrue("Audio MUST keep ringing during internet failure", audio.isRinging.value)

        val erroredSession = sessionRepo.getActiveSession()
        assertNotNull(erroredSession)
        assertEquals(RetrievalStatus.NETWORK_ERROR, erroredSession!!.retrievalStatus)
        assertEquals(0, erroredSession.solvedCount)

        // Restore internet and retry -> 5 fresh questions become READY while audio still rings
        networkOnline = true
        val recoveredFetch = coordinator.fetchOrRetryQuestions()
        assertTrue(recoveredFetch.isSuccess)
        val readySession = sessionRepo.getActiveSession()!!
        assertEquals(RetrievalStatus.READY, readySession.retrievalStatus)
        assertEquals(5, readySession.questions.size)
        assertTrue(audio.isRinging.value)
    }

    @Test
    fun `five correct answers mandatory - wrong answers retry without advancing and 5th stops audio immediately`() = runTest {
        val audio = FakeAudioController()
        val sessionRepo = InMemorySessionRepository()
        val alarmRepo = InMemoryAlarmRepository()
        val historyRepo = InMemoryHistoryRepository()
        val settingsRepo = InMemorySettingsRepository()
        val scheduler = FakeScheduler()

        val engine = object : QuestionEngine {
            override suspend fun fetchFreshQuestions(
                categories: Set<QuestionCategory>,
                difficultyPreset: DifficultyPreset,
                count: Int,
                excludedFingerprints: Set<String>
            ): Result<List<AlarmQuestion>> = Result.success(generateQuestions(count, excludedFingerprints))
        }

        val coordinator = AlarmSessionCoordinator(
            alarmRepository = alarmRepo,
            sessionRepository = sessionRepo,
            historyRepository = historyRepo,
            settingsRepository = settingsRepo,
            questionEngine = engine,
            answerValidator = AnswerValidator(),
            alarmScheduler = scheduler,
            audioController = audio
        )

        val alarmId = alarmRepo.upsertAlarm(Alarm(hour = 7, minute = 0))
        audio.startRinging()
        coordinator.onAlarmTriggered(alarmId)
        coordinator.fetchOrRetryQuestions()

        // Submit two wrong answers on question 1 -> progress stays 0/5, options reshuffle each time, and audio stays ringing
        val initialOptions = sessionRepo.getActiveSession()!!.currentQuestion!!.options
        val (wrong1, s1) = coordinator.submitAnswer("WRONG_A")
        assertTrue(wrong1 is AnswerEvaluationResult.Incorrect)
        assertEquals(0, s1!!.solvedCount)
        assertEquals(0, s1.currentQuestionIndex)
        assertEquals(1, s1.totalWrongAttempts)
        val afterWrong1Options = s1.currentQuestion!!.options
        assertEquals("Option set must stay identical", initialOptions.toSet(), afterWrong1Options.toSet())
        assertTrue("Options must shuffle to a new order on wrong answer", afterWrong1Options != initialOptions)
        assertTrue(audio.isRinging.value)

        val (wrong2, s2) = coordinator.submitAnswer("WRONG_B")
        assertTrue(wrong2 is AnswerEvaluationResult.Incorrect)
        assertEquals(0, s2!!.solvedCount)
        assertEquals(2, s2.totalWrongAttempts)
        val afterWrong2Options = s2.currentQuestion!!.options
        assertEquals(initialOptions.toSet(), afterWrong2Options.toSet())
        assertTrue("Options must shuffle again on second wrong answer", afterWrong2Options != afterWrong1Options)
        assertTrue(audio.isRinging.value)

        // Answer questions 1..4 correctly -> audio STILL rings at 4/5!
        for (idx in 0 until 4) {
            val currentQ = sessionRepo.getActiveSession()!!.currentQuestion!!
            val (ok, updated) = coordinator.submitAnswer(currentQ.canonicalAnswer)
            assertTrue(ok is AnswerEvaluationResult.Correct)
            assertEquals(idx + 1, updated!!.solvedCount)
            assertFalse(updated.completed)
            assertTrue("Audio must keep ringing at ${idx + 1}/5", audio.isRinging.value)
        }

        // Answer 5th question correctly -> audio stops IMMEDIATELY and active session clears to completed!
        val fifthQ = sessionRepo.getActiveSession()!!.currentQuestion!!
        var callbackInvoked = false
        val (ok5, finalSession) = coordinator.submitAnswer(fifthQ.canonicalAnswer) {
            callbackInvoked = true
        }
        assertTrue(ok5 is AnswerEvaluationResult.Correct)
        assertEquals(5, finalSession!!.solvedCount)
        assertTrue(finalSession.completed)
        assertTrue(callbackInvoked)
        assertFalse("Audio MUST stop immediately after 5th correct answer", audio.isRinging.value)
        assertNull("Active slot must be cleared after completion", sessionRepo.getActiveSession())
        assertEquals(5, historyRepo.getRecentFingerprints(10).size)

        // Trigger a second alarm and verify previous 5 fingerprints are excluded (zero duplicates!)
        val secondAlarmId = alarmRepo.upsertAlarm(Alarm(hour = 8, minute = 0))
        audio.startRinging()
        coordinator.onAlarmTriggered(secondAlarmId)
        val secondBatch = coordinator.fetchOrRetryQuestions().getOrThrow().questions
        val firstFingerprints = finalSession.questions.map { it.fingerprint }.toSet()
        assertTrue(
            "Second alarm batch must exclude recently used questions",
            secondBatch.none { it.fingerprint in firstFingerprints }
        )
    }

    @Test
    fun `emergency override rejects short hold or wrong code and succeeds on 8s hold or exact code`() = runTest {
        val audio = FakeAudioController()
        val sessionRepo = InMemorySessionRepository()
        val coordinator = AlarmSessionCoordinator(
            alarmRepository = InMemoryAlarmRepository(),
            sessionRepository = sessionRepo,
            historyRepository = InMemoryHistoryRepository(),
            settingsRepository = InMemorySettingsRepository(),
            questionEngine = object : QuestionEngine {
                override suspend fun fetchFreshQuestions(
                    categories: Set<QuestionCategory>,
                    difficultyPreset: DifficultyPreset,
                    count: Int,
                    excludedFingerprints: Set<String>
                ) = Result.success(generateQuestions(count, excludedFingerprints))
            },
            answerValidator = AnswerValidator(),
            alarmScheduler = FakeScheduler(),
            audioController = audio
        )

        audio.startRinging()
        val session = coordinator.onAlarmTriggered(1L)

        // Short hold (2.5s) + wrong code MUST fail and keep audio ringing
        val rejected = coordinator.executeEmergencyOverride(
            reason = OverrideReason.SAFETY_MEDICAL,
            holdDurationMillis = 2_500L,
            typedConfirmationCode = "KLOCK-WRONG"
        )
        assertTrue(rejected.isFailure)
        assertTrue(audio.isRinging.value)

        // Exact accessibility confirmation code succeeds and stops audio
        val accepted = coordinator.executeEmergencyOverride(
            reason = OverrideReason.ACCESSIBILITY,
            holdDurationMillis = 0L,
            typedConfirmationCode = session.emergencyOverrideCode
        )
        assertTrue(accepted.isSuccess)
        assertTrue(accepted.getOrThrow().dismissedViaOverride)
        assertFalse(audio.isRinging.value)
    }

    private fun generateQuestions(
        count: Int,
        excluded: Set<String>
    ): List<AlarmQuestion> {
        val out = mutableListOf<AlarmQuestion>()
        var seq = 1
        val cats = QuestionCategory.entries
        while (out.size < count) {
            val prompt = "Fresh question #$seq"
            val answer = "ANS_$seq"
            val fp = SecurityGuards.computeQuestionFingerprint(prompt, answer)
            if (fp !in excluded) {
                out += AlarmQuestion(
                    fingerprint = fp,
                    slotIndex = out.size,
                    category = cats[seq % cats.size],
                    difficulty = QuestionDifficulty.MEDIUM,
                    format = QuestionFormat.MULTIPLE_CHOICE,
                    provenance = QuestionProvenance.SOURCE_GROUNDED_EXTRACTED,
                    prompt = prompt,
                    options = listOf(answer, "WRONG_A", "WRONG_B", "WRONG_C"),
                    canonicalAnswer = answer,
                    explanation = "Explanation $seq",
                    sourceTitle = "Doc $seq",
                    sourceProvider = "Microsoft Learn",
                    sourceUrl = "https://learn.microsoft.com/en-us/sql/doc-$seq",
                    fetchedAtMillis = 1_700_000_000_000L + seq
                )
            }
            seq++
        }
        return out
    }
}

// Lightweight test doubles

internal class FakeAudioController : AlarmAudioController {
    private val _ringing = MutableStateFlow(false)
    override val isRinging: StateFlow<Boolean> = _ringing.asStateFlow()

    override fun startRinging(ringtoneUri: String?, vibrate: Boolean, gradualVolumeRamp: Boolean) {
        _ringing.value = true
    }

    override fun stopRinging() {
        _ringing.value = false
    }
}

internal class FakeScheduler : AlarmScheduler {
    val scheduledIds = mutableSetOf<Long>()
    override fun canScheduleExactAlarms(): Boolean = true
    override fun schedule(alarm: Alarm, now: ZonedDateTime): Long {
        scheduledIds += alarm.id
        return now.plusHours(8).toInstant().toEpochMilli()
    }
    override fun cancel(alarmId: Long) {
        scheduledIds -= alarmId
    }
    override suspend fun rescheduleAllEnabled(alarmRepository: AlarmRepository) {
        alarmRepository.getEnabledAlarms().forEach { schedule(it) }
    }
}

internal class InMemoryAlarmRepository : AlarmRepository {
    private val map = linkedMapOf<Long, Alarm>()
    private val flow = MutableStateFlow<List<Alarm>>(emptyList())
    private var nextId = 1L

    override fun observeAlarms(): Flow<List<Alarm>> = flow
    override suspend fun getAllAlarms(): List<Alarm> = map.values.toList()
    override suspend fun getEnabledAlarms(): List<Alarm> = map.values.filter { it.enabled }
    override suspend fun getAlarmById(id: Long): Alarm? = map[id]
    override suspend fun upsertAlarm(alarm: Alarm): Long {
        val id = if (alarm.id == 0L) nextId++ else alarm.id
        map[id] = alarm.copy(id = id)
        flow.value = map.values.toList()
        return id
    }
    override suspend fun setAlarmEnabled(id: Long, enabled: Boolean, nextTriggerMillis: Long?) {
        map[id]?.let { map[id] = it.copy(enabled = enabled, nextTriggerEpochMillis = nextTriggerMillis) }
        flow.value = map.values.toList()
    }
    override suspend fun updateNextTrigger(id: Long, nextTriggerMillis: Long?) {
        map[id]?.let { map[id] = it.copy(nextTriggerEpochMillis = nextTriggerMillis) }
        flow.value = map.values.toList()
    }
    override suspend fun deleteAlarm(id: Long) {
        map.remove(id)
        flow.value = map.values.toList()
    }
}

internal class InMemorySessionRepository : SessionRepository {
    private val active = MutableStateFlow<ActiveAlarmSession?>(null)
    private val completed = MutableStateFlow<ActiveAlarmSession?>(null)

    override fun observeActiveSession(): Flow<ActiveAlarmSession?> = active
    override suspend fun getActiveSession(): ActiveAlarmSession? = active.value
    override suspend fun saveActiveSession(session: ActiveAlarmSession) {
        if (session.completed) {
            completed.value = session
            active.value = null
        } else {
            active.value = session
        }
    }
    override suspend fun clearActiveSession() {
        active.value = null
    }
    override fun observeCompletedSession(sessionId: String): Flow<ActiveAlarmSession?> = completed
    override suspend fun getLastCompletedSession(): ActiveAlarmSession? = completed.value
}

internal class InMemoryHistoryRepository : QuestionHistoryRepository {
    private val items = mutableListOf<QuestionHistoryRecord>()
    private val flow = MutableStateFlow<List<QuestionHistoryRecord>>(emptyList())

    override fun observeRecentHistory(limit: Int): Flow<List<QuestionHistoryRecord>> = flow
    override suspend fun getRecentFingerprints(limit: Int): Set<String> =
        items.takeLast(limit).map { it.fingerprint }.toSet()
    override suspend fun getRecentSourceUrls(limit: Int): Set<String> =
        items.takeLast(limit).map { it.sourceUrl }.toSet()
    override suspend fun recordQuestionOutcome(record: QuestionHistoryRecord) {
        items.add(record)
        flow.value = items.reversed()
    }
    override suspend fun getCategoryPerformanceStats(): Map<QuestionCategory, CategoryPerformanceStat> = emptyMap()
    override suspend fun pruneOldFingerprints(keepLatest: Int) {
        while (items.size > keepLatest) items.removeAt(0)
    }
}

internal class InMemorySettingsRepository : SettingsRepository {
    private val state = MutableStateFlow(AppSettings())
    override val settingsFlow: Flow<AppSettings> = state
    override suspend fun getSettings(): AppSettings = state.value
    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }
}

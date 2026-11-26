package com.klock.app.alarm.session

import com.klock.app.alarm.audio.AlarmAudioController
import com.klock.app.alarm.scheduler.AlarmScheduler
import com.klock.app.core.device.DeviceMemoryTier
import com.klock.app.core.security.SecurityGuards
import com.klock.app.domain.model.ActiveAlarmSession
import com.klock.app.domain.model.Alarm
import com.klock.app.domain.model.AnswerEvaluationResult
import com.klock.app.domain.model.DifficultyPreset
import com.klock.app.domain.model.OverrideReason
import com.klock.app.domain.model.QuestionHistoryRecord
import com.klock.app.domain.model.RetrievalStatus
import com.klock.app.domain.model.TopicConfig
import com.klock.app.domain.repository.AlarmRepository
import com.klock.app.domain.repository.QuestionEngine
import com.klock.app.domain.repository.QuestionHistoryRepository
import com.klock.app.domain.repository.SessionRepository
import com.klock.app.domain.repository.SettingsRepository
import com.klock.app.questions.adaptive.TopicRecommender
import com.klock.app.questions.validation.AnswerValidator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Coordinates the lifecycle of an active ringing alarm session (1 to 100 questions):
 * - Fetches in progressive chunks (10 questions per chunk) so even a 100-question alarm becomes
 *   ready to answer in ~1.5 seconds while remaining batches load seamlessly as the user progresses.
 * - Shuffles MCQ options into a distinct random order on every wrong answer.
 * - Stops audio immediately once all required questions are solved (or on validated emergency override).
 */
class AlarmSessionCoordinator(
    private val alarmRepository: AlarmRepository,
    private val sessionRepository: SessionRepository,
    private val historyRepository: QuestionHistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val questionEngine: QuestionEngine,
    private val answerValidator: AnswerValidator,
    private val alarmScheduler: AlarmScheduler,
    private val audioController: AlarmAudioController,
    private val memoryTier: DeviceMemoryTier = DeviceMemoryTier.STANDARD_RAM
) {
    private val mutex = Mutex()

    suspend fun onAlarmTriggered(
        alarmId: Long,
        fallbackLabel: String = ""
    ): ActiveAlarmSession {
        return mutex.withLock {
            val existing = sessionRepository.getActiveSession()
            if (existing != null && !existing.completed) {
                return@withLock existing
            }

            val alarm = alarmRepository.getAlarmById(alarmId)
            if (alarm != null) {
                if (alarm.isRepeating) {
                    val nextMillis = alarmScheduler.schedule(alarm)
                    alarmRepository.updateNextTrigger(alarm.id, nextMillis)
                } else {
                    alarmScheduler.cancel(alarm.id)
                    alarmRepository.setAlarmEnabled(alarm.id, enabled = false, nextTriggerMillis = null)
                }
            }

            val reqCount = (alarm?.requiredQuestions ?: Alarm.DEFAULT_REQUIRED_QUESTIONS)
                .coerceIn(Alarm.MIN_REQUIRED_QUESTIONS, Alarm.MAX_REQUIRED_QUESTIONS)

            val freshSession = ActiveAlarmSession(
                sessionId = UUID.randomUUID().toString(),
                alarmId = alarm?.id ?: alarmId,
                alarmLabel = alarm?.label?.ifBlank { fallbackLabel } ?: fallbackLabel,
                startedAtMillis = System.currentTimeMillis(),
                requiredCount = reqCount,
                solvedCount = 0,
                currentQuestionIndex = 0,
                currentAttemptCount = 0,
                totalWrongAttempts = 0,
                questions = emptyList(),
                solvedFingerprints = emptySet(),
                retrievalStatus = RetrievalStatus.FETCHING,
                retrievalErrorMessage = null,
                emergencyOverrideCode = SecurityGuards.generateEmergencyOverrideCode(),
                completed = false
            )
            sessionRepository.saveActiveSession(freshSession)
            freshSession
        }
    }

    suspend fun fetchOrRetryQuestions(): Result<ActiveAlarmSession> {
        val currentSnapshot = mutex.withLock {
            val active = sessionRepository.getActiveSession()
                ?: return Result.failure(IllegalStateException("No active alarm session"))
            if (active.questions.size >= active.requiredCount &&
                active.retrievalStatus == RetrievalStatus.READY
            ) {
                return Result.success(active)
            }
            val nextStatus = if (active.currentQuestion != null) {
                RetrievalStatus.READY
            } else {
                RetrievalStatus.FETCHING
            }
            val updated = active.copy(
                retrievalStatus = nextStatus,
                retrievalErrorMessage = null
            )
            sessionRepository.saveActiveSession(updated)
            updated
        }

        val remainingNeeded = (currentSnapshot.requiredCount - currentSnapshot.questions.size)
            .coerceAtLeast(0)
        if (remainingNeeded == 0) {
            return mutex.withLock {
                val updated = (sessionRepository.getActiveSession() ?: currentSnapshot).copy(
                    retrievalStatus = RetrievalStatus.READY,
                    retrievalErrorMessage = null
                )
                sessionRepository.saveActiveSession(updated)
                Result.success(updated)
            }
        }

        // Fetch in chunks of up to 10 so even 100-question alarms start quickly with low RAM usage!
        val batchSize = remainingNeeded.coerceAtMost(FETCH_CHUNK_SIZE)
        val alarm = alarmRepository.getAlarmById(currentSnapshot.alarmId)
        val settings = settingsRepository.getSettings()
        val resolvedTopics = resolveTopicsForAlarm(alarm, settings.configuredTopics, settings.userProfile.currentActivityOrStudy, settings.userProfile.age)
        val preset: DifficultyPreset = alarm?.difficultyPreset ?: settings.defaultDifficulty

        val recentFingerprints = historyRepository.getRecentFingerprints(
            limit = memoryTier.historyRetentionCount
        ) + currentSnapshot.solvedFingerprints + currentSnapshot.questions.map { it.fingerprint }

        val fetchedResult = questionEngine.fetchFreshQuestions(
            topics = resolvedTopics,
            difficultyPreset = preset,
            count = batchSize,
            excludedFingerprints = recentFingerprints
        )

        return mutex.withLock {
            val latest = sessionRepository.getActiveSession() ?: currentSnapshot
            fetchedResult.fold(
                onSuccess = { newQuestions ->
                    val combined = (latest.questions + newQuestions)
                        .distinctBy { it.fingerprint }
                        .take(latest.requiredCount)
                        .mapIndexed { idx, q -> q.copy(slotIndex = idx) }
                    val hasCurrentSlot = combined.size > latest.currentQuestionIndex
                    if (!hasCurrentSlot) {
                        val partialState = latest.copy(
                            questions = combined,
                            retrievalStatus = RetrievalStatus.NETWORK_ERROR,
                            retrievalErrorMessage = "Check your internet connection and tap Try Again."
                        )
                        sessionRepository.saveActiveSession(partialState)
                        Result.failure(IllegalStateException(partialState.retrievalErrorMessage ?: "Incomplete batch"))
                    } else {
                        val readyState = latest.copy(
                            questions = combined,
                            retrievalStatus = RetrievalStatus.READY,
                            retrievalErrorMessage = null
                        )
                        sessionRepository.saveActiveSession(readyState)
                        Result.success(readyState)
                    }
                },
                onFailure = { error ->
                    val nextStatus = if (latest.questions.size > latest.currentQuestionIndex) {
                        RetrievalStatus.READY
                    } else {
                        RetrievalStatus.NETWORK_ERROR
                    }
                    val errorState = latest.copy(
                        retrievalStatus = nextStatus,
                        retrievalErrorMessage = error.message
                            ?: "Unable to load questions. Connect to the internet and tap Try Again."
                    )
                    sessionRepository.saveActiveSession(errorState)
                    Result.failure(error)
                }
            )
        }
    }

    private fun resolveTopicsForAlarm(
        alarm: Alarm?,
        configuredTopics: List<TopicConfig>,
        userActivity: String,
        userAge: Int?
    ): List<TopicConfig> {
        val enabledUserTopics = configuredTopics.filter { it.enabled && it.name.isNotBlank() }
        val pool = configuredTopics.filter { it.name.isNotBlank() }
        if (alarm != null && alarm.selectedTopicIds.isNotEmpty()) {
            val matched = pool.filter { it.id in alarm.selectedTopicIds }
            if (matched.isNotEmpty()) return matched
        }
        if (enabledUserTopics.isNotEmpty()) return enabledUserTopics
        if (pool.isNotEmpty()) return pool
        return TopicRecommender.recommendTopics(userActivity, userAge)
    }

    suspend fun submitAnswer(
        rawAnswer: String,
        onSessionCompleted: () -> Unit = {}
    ): Pair<AnswerEvaluationResult, ActiveAlarmSession?> {
        val pair = mutex.withLock {
            val active = sessionRepository.getActiveSession()
                ?: return@withLock AnswerEvaluationResult.Incorrect(
                    hint = "No active alarm session found.",
                    attemptNumber = 1
                ) to null

            val question = active.currentQuestion
                ?: return@withLock AnswerEvaluationResult.Incorrect(
                    hint = "Loading next question…",
                    attemptNumber = active.currentAttemptCount + 1
                ) to active

            val nextAttemptNumber = active.currentAttemptCount + 1
            val eval = answerValidator.evaluate(question, rawAnswer, nextAttemptNumber)

            when (eval) {
                is AnswerEvaluationResult.Correct -> {
                    val now = System.currentTimeMillis()
                    historyRepository.recordQuestionOutcome(
                        QuestionHistoryRecord(
                            sessionId = active.sessionId,
                            alarmId = active.alarmId,
                            fingerprint = question.fingerprint,
                            topicId = question.topicId,
                            topicName = question.topicName,
                            subtopicName = question.subtopicName,
                            difficulty = question.difficulty,
                            format = question.format,
                            provenance = question.provenance,
                            prompt = question.prompt,
                            codeSnippet = question.codeSnippet,
                            userAnswer = rawAnswer.trim(),
                            canonicalAnswer = question.canonicalAnswer,
                            explanation = question.explanation,
                            sourceTitle = question.sourceTitle,
                            sourceProvider = question.sourceProvider,
                            sourceUrl = question.sourceUrl,
                            attemptsTaken = nextAttemptNumber,
                            solvedCorrectly = true,
                            answeredAtMillis = now
                        )
                    )
                    historyRepository.pruneOldFingerprints(memoryTier.historyRetentionCount)

                    val newSolvedCount = active.solvedCount + 1
                    val isFinished = newSolvedCount >= active.requiredCount
                    val nextIdx = if (isFinished) {
                        active.currentQuestionIndex
                    } else {
                        (active.currentQuestionIndex + 1).coerceAtMost(active.requiredCount - 1)
                    }
                    val needsNextBatch = !isFinished && nextIdx >= active.questions.size

                    val updatedSession = active.copy(
                        solvedCount = newSolvedCount,
                        currentQuestionIndex = nextIdx,
                        currentAttemptCount = 0,
                        solvedFingerprints = active.solvedFingerprints + question.fingerprint,
                        retrievalStatus = if (needsNextBatch) RetrievalStatus.FETCHING else active.retrievalStatus,
                        completed = isFinished,
                        completedAtMillis = if (isFinished) now else null
                    )
                    sessionRepository.saveActiveSession(updatedSession)

                    if (isFinished) {
                        audioController.stopRinging()
                        onSessionCompleted()
                    }
                    eval to updatedSession
                }

                is AnswerEvaluationResult.Incorrect -> {
                    val reshuffledQuestions = active.questions.mapIndexed { idx, q ->
                        if (idx == active.currentQuestionIndex && q.options.size > 1) {
                            q.copy(options = shuffleOptionsDistinctFromCurrent(q.options))
                        } else {
                            q
                        }
                    }
                    val updatedSession = active.copy(
                        questions = reshuffledQuestions,
                        currentAttemptCount = nextAttemptNumber,
                        totalWrongAttempts = active.totalWrongAttempts + 1
                    )
                    sessionRepository.saveActiveSession(updatedSession)
                    eval to updatedSession
                }
            }
        }

        // Top up next 10-question chunk seamlessly when buffer has <= 3 questions remaining
        val updated = pair.second
        if (updated != null &&
            !updated.completed &&
            updated.questions.size < updated.requiredCount &&
            (updated.questions.size - updated.currentQuestionIndex) <= 3
        ) {
            fetchOrRetryQuestions()
            val refreshed = sessionRepository.getActiveSession()
            if (refreshed != null) {
                return pair.first to refreshed
            }
        }

        return pair
    }

    private fun shuffleOptionsDistinctFromCurrent(options: List<String>): List<String> {
        if (options.size <= 1) return options
        repeat(8) {
            val candidate = options.shuffled()
            if (candidate != options) return candidate
        }
        return options.drop(1) + options.first()
    }

    suspend fun executeEmergencyOverride(
        reason: OverrideReason,
        holdDurationMillis: Long,
        typedConfirmationCode: String,
        onOverrideCompleted: () -> Unit = {}
    ): Result<ActiveAlarmSession> {
        return mutex.withLock {
            val active = sessionRepository.getActiveSession()
                ?: return@withLock Result.failure(IllegalStateException("No active session"))

            val codeMatches = typedConfirmationCode.trim()
                .equals(active.emergencyOverrideCode, ignoreCase = true)
            val holdSatisfied = holdDurationMillis >= MINIMUM_OVERRIDE_HOLD_MS

            if (!codeMatches && !holdSatisfied) {
                return@withLock Result.failure(
                    IllegalArgumentException(
                        "Hold for 8 seconds or enter code ${active.emergencyOverrideCode}."
                    )
                )
            }

            val now = System.currentTimeMillis()
            val completed = active.copy(
                completed = true,
                dismissedViaOverride = true,
                overrideReason = reason,
                completedAtMillis = now
            )
            sessionRepository.saveActiveSession(completed)
            audioController.stopRinging()
            onOverrideCompleted()
            Result.success(completed)
        }
    }

    companion object {
        const val MINIMUM_OVERRIDE_HOLD_MS = 8_000L
        const val FETCH_CHUNK_SIZE = 10
    }
}

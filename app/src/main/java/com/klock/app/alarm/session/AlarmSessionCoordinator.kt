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
import com.klock.app.domain.model.QuestionCategory
import com.klock.app.domain.model.QuestionHistoryRecord
import com.klock.app.domain.model.RetrievalStatus
import com.klock.app.domain.repository.AlarmRepository
import com.klock.app.domain.repository.QuestionEngine
import com.klock.app.domain.repository.QuestionHistoryRepository
import com.klock.app.domain.repository.SessionRepository
import com.klock.app.domain.repository.SettingsRepository
import com.klock.app.questions.validation.AnswerValidator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Coordinates the lifecycle of an active ringing alarm session:
 * - Persists every state transition in Room so process death never resets progress or bypasses questions.
 * - Keeps audio playback completely independent from network retrieval failures.
 * - Requires 5 correct answers (or deliberate validated emergency override) before stopping audio.
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
        val session = mutex.withLock {
            val existing = sessionRepository.getActiveSession()
            if (existing != null && !existing.completed) {
                return@withLock existing
            }

            val alarm = alarmRepository.getAlarmById(alarmId)
            val settings = settingsRepository.getSettings()

            // Advance schedule immediately for repeating alarms or disable one-shot alarm
            if (alarm != null) {
                if (alarm.isRepeating) {
                    val nextMillis = alarmScheduler.schedule(alarm)
                    alarmRepository.updateNextTrigger(alarm.id, nextMillis)
                } else {
                    alarmScheduler.cancel(alarm.id)
                    alarmRepository.setAlarmEnabled(alarm.id, enabled = false, nextTriggerMillis = null)
                }
            }

            val freshSession = ActiveAlarmSession(
                sessionId = UUID.randomUUID().toString(),
                alarmId = alarm?.id ?: alarmId,
                alarmLabel = alarm?.label?.ifBlank { fallbackLabel } ?: fallbackLabel,
                startedAtMillis = System.currentTimeMillis(),
                requiredCount = alarm?.requiredQuestions ?: Alarm.DEFAULT_REQUIRED_QUESTIONS,
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
        return session
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
            val fetching = active.copy(
                retrievalStatus = RetrievalStatus.FETCHING,
                retrievalErrorMessage = null
            )
            sessionRepository.saveActiveSession(fetching)
            fetching
        }

        val alarm = alarmRepository.getAlarmById(currentSnapshot.alarmId)
        val settings = settingsRepository.getSettings()
        val categories: Set<QuestionCategory> =
            alarm?.categories?.takeIf { it.isNotEmpty() } ?: settings.defaultCategories
        val preset: DifficultyPreset =
            alarm?.difficultyPreset ?: settings.defaultDifficulty

        val recentFingerprints = historyRepository.getRecentFingerprints(
            limit = memoryTier.historyRetentionCount
        ) + currentSnapshot.solvedFingerprints

        val neededCount = (currentSnapshot.requiredCount - currentSnapshot.questions.size)
            .coerceAtLeast(0)

        if (neededCount == 0) {
            return mutex.withLock {
                val updated = (sessionRepository.getActiveSession() ?: currentSnapshot).copy(
                    retrievalStatus = RetrievalStatus.READY,
                    retrievalErrorMessage = null
                )
                sessionRepository.saveActiveSession(updated)
                Result.success(updated)
            }
        }

        val fetchedResult = questionEngine.fetchFreshQuestions(
            categories = categories,
            difficultyPreset = preset,
            count = neededCount,
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
                    if (combined.size < latest.requiredCount) {
                        val partialState = latest.copy(
                            questions = combined,
                            retrievalStatus = RetrievalStatus.NETWORK_ERROR,
                            retrievalErrorMessage = "Retrieved ${combined.size}/${latest.requiredCount} fresh questions. Check internet connection and tap Retry for the remaining questions."
                        )
                        sessionRepository.saveActiveSession(partialState)
                        Result.failure(IllegalStateException(partialState.retrievalErrorMessage ?: "Incomplete retrieval"))
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
                    val errorState = latest.copy(
                        retrievalStatus = RetrievalStatus.NETWORK_ERROR,
                        retrievalErrorMessage = error.message
                            ?: "Unable to reach live educational sources. Alarm remains active — reconnect to the internet and tap Retry."
                    )
                    sessionRepository.saveActiveSession(errorState)
                    Result.failure(error)
                }
            )
        }
    }

    suspend fun submitAnswer(
        rawAnswer: String,
        onSessionCompleted: () -> Unit = {}
    ): Pair<AnswerEvaluationResult, ActiveAlarmSession?> {
        return mutex.withLock {
            val active = sessionRepository.getActiveSession()
                ?: return@withLock AnswerEvaluationResult.Incorrect(
                    hint = "No active alarm session found.",
                    attemptNumber = 1
                ) to null

            val question = active.currentQuestion
                ?: return@withLock AnswerEvaluationResult.Incorrect(
                    hint = "Questions are still loading from the internet.",
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
                            category = question.category,
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
                    val updatedSession = active.copy(
                        solvedCount = newSolvedCount,
                        currentQuestionIndex = if (isFinished) {
                            active.currentQuestionIndex
                        } else {
                            (active.currentQuestionIndex + 1).coerceAtMost(active.requiredCount - 1)
                        },
                        currentAttemptCount = 0,
                        solvedFingerprints = active.solvedFingerprints + question.fingerprint,
                        completed = isFinished,
                        completedAtMillis = if (isFinished) now else null
                    )
                    sessionRepository.saveActiveSession(updatedSession)

                    if (isFinished) {
                        // Stop alarm audio IMMEDIATELY upon the fifth correct answer!
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
    }

    private fun shuffleOptionsDistinctFromCurrent(options: List<String>): List<String> {
        if (options.size <= 1) return options
        repeat(8) {
            val candidate = options.shuffled()
            if (candidate != options) return candidate
        }
        // Deterministic fallback rotation if random shuffle hit identical order 8 times
        return options.drop(1) + options.first()
    }

    /**
     * Safety & Accessibility Emergency Override:
     * Requires EITHER completing an 8-second continuous hold gesture (`holdCompletedMillis >= 8_000L`)
     * OR entering the exact session-specific `KLOCK-XXXXXX` override code (accessible for screen-reader
     * and switch-access users who cannot perform long press-and-hold gestures).
     */
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
                        "Hold continuously for 8 seconds or enter confirmation code ${active.emergencyOverrideCode}."
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
    }
}

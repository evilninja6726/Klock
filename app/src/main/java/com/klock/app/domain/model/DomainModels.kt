package com.klock.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Alarm(
    val id: Long = 0L,
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val enabled: Boolean = true,
    /** ISO day-of-week numbers: 1 = Monday .. 7 = Sunday. Empty = one-shot alarm. */
    val repeatDays: Set<Int> = emptySet(),
    val categories: Set<QuestionCategory> = QuestionCategory.ALL,
    val difficultyPreset: DifficultyPreset = DifficultyPreset.ADAPTIVE,
    val requiredQuestions: Int = DEFAULT_REQUIRED_QUESTIONS,
    val vibrate: Boolean = true,
    val ringtoneUri: String? = null,
    val nextTriggerEpochMillis: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    val isRepeating: Boolean get() = repeatDays.isNotEmpty()

    companion object {
        const val DEFAULT_REQUIRED_QUESTIONS = 5
    }
}

@Serializable
data class AlarmQuestion(
    val fingerprint: String,
    val slotIndex: Int,
    val category: QuestionCategory,
    val difficulty: QuestionDifficulty,
    val format: QuestionFormat,
    val provenance: QuestionProvenance,
    val prompt: String,
    val codeSnippet: String? = null,
    val options: List<String> = emptyList(),
    val canonicalAnswer: String,
    val acceptableAlternatives: List<String> = emptyList(),
    val requiredKeywords: List<String> = emptyList(),
    val numericalTarget: Double? = null,
    val numericalTolerance: Double = 0.01,
    val explanation: String,
    val sourceTitle: String,
    val sourceProvider: String,
    val sourceUrl: String,
    val sourceExcerpt: String? = null,
    val fetchedAtMillis: Long
)

@Serializable
enum class RetrievalStatus {
    FETCHING,
    READY,
    NETWORK_ERROR
}

@Serializable
enum class OverrideReason(val displayName: String) {
    SAFETY_MEDICAL("Safety or Medical Emergency"),
    ACCESSIBILITY("Accessibility Accommodation"),
    PROLONGED_OUTAGE("Complete Internet Outage")
}

@Serializable
data class ActiveAlarmSession(
    val sessionId: String,
    val alarmId: Long,
    val alarmLabel: String,
    val startedAtMillis: Long,
    val requiredCount: Int = Alarm.DEFAULT_REQUIRED_QUESTIONS,
    val solvedCount: Int = 0,
    val currentQuestionIndex: Int = 0,
    val currentAttemptCount: Int = 0,
    val totalWrongAttempts: Int = 0,
    val questions: List<AlarmQuestion> = emptyList(),
    val solvedFingerprints: Set<String> = emptySet(),
    val retrievalStatus: RetrievalStatus = RetrievalStatus.FETCHING,
    val retrievalErrorMessage: String? = null,
    val emergencyOverrideCode: String,
    val completed: Boolean = false,
    val dismissedViaOverride: Boolean = false,
    val overrideReason: OverrideReason? = null,
    val completedAtMillis: Long? = null
) {
    val currentQuestion: AlarmQuestion?
        get() = questions.getOrNull(currentQuestionIndex)

    val isReadyToDismiss: Boolean
        get() = solvedCount >= requiredCount
}

data class QuestionHistoryRecord(
    val id: Long = 0L,
    val sessionId: String,
    val alarmId: Long,
    val fingerprint: String,
    val category: QuestionCategory,
    val difficulty: QuestionDifficulty,
    val format: QuestionFormat,
    val provenance: QuestionProvenance,
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

data class CategoryPerformanceStat(
    val category: QuestionCategory,
    val totalSolved: Int,
    val firstTrySolved: Int,
    val totalWrongAttempts: Int,
    val lastAskedAtMillis: Long
) {
    val firstTryAccuracy: Double
        get() = if (totalSolved <= 0) 0.65 else firstTrySolved.toDouble() / totalSolved.toDouble()
}

enum class ThemePreference(val displayName: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark")
}

data class AppSettings(
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val defaultDifficulty: DifficultyPreset = DifficultyPreset.ADAPTIVE,
    val defaultCategories: Set<QuestionCategory> = QuestionCategory.ALL,
    val use24HourFormat: Boolean = false,
    val vibrateByDefault: Boolean = true,
    val gradualVolumeRamp: Boolean = false,
    val enableAiSynthesisWhenKeyConfigured: Boolean = false,
    val aiEndpointUrl: String = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
)

sealed interface AnswerEvaluationResult {
    data class Correct(
        val normalizedAnswer: String,
        val feedback: String
    ) : AnswerEvaluationResult

    data class Incorrect(
        val hint: String,
        val attemptNumber: Int
    ) : AnswerEvaluationResult
}

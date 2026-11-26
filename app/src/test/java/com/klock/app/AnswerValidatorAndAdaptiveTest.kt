package com.klock.app

import com.klock.app.domain.model.Alarm
import com.klock.app.domain.model.AlarmQuestion
import com.klock.app.domain.model.AnswerEvaluationResult
import com.klock.app.domain.model.AppSettings
import com.klock.app.domain.model.DifficultyPreset
import com.klock.app.domain.model.QuestionDifficulty
import com.klock.app.domain.model.QuestionFormat
import com.klock.app.domain.model.QuestionProvenance
import com.klock.app.domain.model.SubtopicConfig
import com.klock.app.domain.model.TopicConfig
import com.klock.app.domain.model.TopicPerformanceStat
import com.klock.app.questions.adaptive.AdaptivePlanner
import com.klock.app.questions.adaptive.TopicRecommender
import com.klock.app.questions.validation.AnswerValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerValidatorAndAdaptiveTest {

    private val validator = AnswerValidator()

    @Test
    fun `fresh install has zero default topics and default 5 questions up to max 100`() {
        val freshSettings = AppSettings()
        assertTrue("No default topics should exist before onboarding", freshSettings.configuredTopics.isEmpty())
        assertFalse(freshSettings.userProfile.onboardingCompleted)
        assertEquals(5, Alarm.DEFAULT_REQUIRED_QUESTIONS)
        assertEquals(100, Alarm.MAX_REQUIRED_QUESTIONS)
        assertEquals(5, Alarm(hour = 7, minute = 0).requiredQuestions)
    }

    @Test
    fun `TopicRecommender suggests domain topics with subtopics and supports completely custom fields`() {
        val dataSuggestions = TopicRecommender.recommendTopics("Data & Analytics", age = 23)
        assertTrue(dataSuggestions.any { it.name == "SQL" && it.subtopics.isNotEmpty() })
        assertTrue(dataSuggestions.any { it.name == "Power BI" })

        val medSuggestions = TopicRecommender.recommendTopics("MBBS Medicine", age = 21)
        assertTrue(medSuggestions.any { it.name.contains("Physiology", ignoreCase = true) })

        val customSuggestions = TopicRecommender.recommendTopics("Astrophysics, Jazz Theory", age = 29)
        assertEquals(listOf("Astrophysics", "Jazz Theory"), customSuggestions.map { it.name })
    }

    @Test
    fun `multiple choice accepts exact text case insensitively and option letter`() {
        val q = sampleQuestion(
            format = QuestionFormat.MULTIPLE_CHOICE,
            topicName = "Power BI",
            options = listOf("SUM", "CALCULATE", "RELATED", "EARLIER"),
            canonical = "CALCULATE"
        )
        assertTrue(validator.evaluate(q, "calculate") is AnswerEvaluationResult.Correct)
        assertTrue(validator.evaluate(q, "B") is AnswerEvaluationResult.Correct)
        assertTrue(validator.evaluate(q, "A") is AnswerEvaluationResult.Incorrect)
    }

    @Test
    fun `adaptive planner rotates custom topics and subtopics and scales up to 100 questions`() {
        val planner = AdaptivePlanner()
        val customTopics = listOf(
            TopicConfig(
                name = "SQL",
                subtopics = listOf(
                    SubtopicConfig("Joins", enabled = true),
                    SubtopicConfig("Window Functions", enabled = true),
                    SubtopicConfig("Disabled Subtopic", enabled = false)
                )
            ),
            TopicConfig(
                name = "Macroeconomics",
                subtopics = listOf(SubtopicConfig("Inflation", enabled = true))
            )
        )

        val plan100 = planner.buildPlan(
            topics = customTopics,
            preset = DifficultyPreset.ADAPTIVE,
            count = 100
        )
        assertEquals(100, plan100.size)
        assertEquals(QuestionDifficulty.EASY, plan100.first().difficulty)
        assertEquals(QuestionDifficulty.HARD, plan100.last().difficulty)

        val sqlSubtopicsUsed = plan100.filter { it.topic.name == "SQL" }.mapNotNull { it.subtopicName }.toSet()
        assertEquals(setOf("Joins", "Window Functions"), sqlSubtopicsUsed)

        val masteredStats = mapOf(
            "sql" to TopicPerformanceStat(
                topicId = "sql",
                totalSolved = 10,
                firstTrySolved = 10,
                totalWrongAttempts = 0,
                lastAskedAtMillis = 1_000L
            )
        )
        val tunedPlan = planner.buildPlan(
            topics = listOf(customTopics.first()),
            preset = DifficultyPreset.ADAPTIVE,
            count = 5,
            stats = masteredStats
        )
        assertEquals(QuestionDifficulty.EASY_MEDIUM, tunedPlan[0].difficulty)
        assertEquals(QuestionDifficulty.HARD, tunedPlan[4].difficulty)
    }

    private fun sampleQuestion(
        format: QuestionFormat,
        topicName: String,
        canonical: String,
        options: List<String> = emptyList()
    ): AlarmQuestion = AlarmQuestion(
        fingerprint = "fp_${format.name}",
        slotIndex = 0,
        topicId = TopicConfig.slugifyTopicId(topicName),
        topicName = topicName,
        difficulty = QuestionDifficulty.MEDIUM,
        format = format,
        provenance = QuestionProvenance.SOURCE_GROUNDED_EXTRACTED,
        prompt = "Sample prompt",
        options = options,
        canonicalAnswer = canonical,
        explanation = "Verified explanation",
        sourceTitle = "Reference Title",
        sourceProvider = "Microsoft Learn",
        sourceUrl = "https://learn.microsoft.com/en-us/sql/",
        fetchedAtMillis = 1_700_000_000_000L
    )
}

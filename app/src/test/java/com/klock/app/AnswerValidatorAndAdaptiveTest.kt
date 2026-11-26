package com.klock.app

import com.klock.app.domain.model.AlarmQuestion
import com.klock.app.domain.model.DifficultyPreset
import com.klock.app.domain.model.QuestionCategory
import com.klock.app.domain.model.QuestionDifficulty
import com.klock.app.domain.model.QuestionFormat
import com.klock.app.domain.model.QuestionProvenance
import com.klock.app.questions.adaptive.AdaptivePlanner
import com.klock.app.questions.validation.AnswerValidator
import com.klock.app.domain.model.AnswerEvaluationResult
import com.klock.app.domain.model.CategoryPerformanceStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerValidatorAndAdaptiveTest {

    private val validator = AnswerValidator()

    @Test
    fun `supported categories contain strictly SQL Python PowerBI Marketing and Business`() {
        val expected = setOf(
            QuestionCategory.SQL,
            QuestionCategory.PYTHON,
            QuestionCategory.POWER_BI,
            QuestionCategory.MARKETING,
            QuestionCategory.BUSINESS
        )
        assertEquals(expected, QuestionCategory.ALL)
        assertEquals(5, QuestionCategory.entries.size)
    }

    @Test
    fun `multiple choice accepts exact text case insensitively and option letter`() {
        val q = sampleQuestion(
            format = QuestionFormat.MULTIPLE_CHOICE,
            category = QuestionCategory.POWER_BI,
            options = listOf("SUM", "CALCULATE", "RELATED", "EARLIER"),
            canonical = "CALCULATE"
        )
        assertTrue(validator.evaluate(q, "calculate") is AnswerEvaluationResult.Correct)
        assertTrue(validator.evaluate(q, "B") is AnswerEvaluationResult.Correct)
        assertTrue(validator.evaluate(q, "A") is AnswerEvaluationResult.Incorrect)
    }

    @Test
    fun `sql query validation handles formatting tolerance and blocks injected destructive DDL`() {
        val q = sampleQuestion(
            format = QuestionFormat.SQL_QUERY,
            category = QuestionCategory.SQL,
            canonical = "SELECT customer_id, SUM(amount) FROM orders GROUP BY customer_id HAVING SUM(amount) > 500",
            requiredKeywords = listOf(
                "SELECT", "customer_id", "SUM(amount)", "FROM orders",
                "GROUP BY customer_id", "HAVING", "SUM(amount)", "500"
            )
        )
        val formattedQuery = """
            select `customer_id`, sum(amount)
            from [orders]
            group by customer_id
            having sum(amount) > 500;
        """.trimIndent()
        assertTrue(validator.evaluate(q, formattedQuery) is AnswerEvaluationResult.Correct)

        val injectedDrop = "$formattedQuery DROP TABLE orders"
        assertTrue(validator.evaluate(q, injectedDrop) is AnswerEvaluationResult.Incorrect)
    }

    @Test
    fun `python output validation normalizes list whitespace and quotes`() {
        val q = sampleQuestion(
            format = QuestionFormat.PYTHON_OUTPUT,
            category = QuestionCategory.PYTHON,
            canonical = "[6, 12, 18]"
        )
        assertTrue(validator.evaluate(q, "[6,12,18]") is AnswerEvaluationResult.Correct)
        assertTrue(validator.evaluate(q, "[ 6 , 12 , 18 ]") is AnswerEvaluationResult.Correct)
        assertTrue(validator.evaluate(q, "[6, 12]") is AnswerEvaluationResult.Incorrect)
    }

    @Test
    fun `numerical and scenario validation accepts currency commas percentages and tolerance`() {
        val q = sampleQuestion(
            format = QuestionFormat.SCENARIO,
            category = QuestionCategory.MARKETING,
            canonical = "45",
            numericalTarget = 45.0,
            numericalTolerance = 0.05
        )
        assertTrue(validator.evaluate(q, "$45.00") is AnswerEvaluationResult.Correct)
        assertTrue(validator.evaluate(q, "45%") is AnswerEvaluationResult.Correct)
        assertTrue(validator.evaluate(q, "45.03") is AnswerEvaluationResult.Correct)
        assertTrue(validator.evaluate(q, "46") is AnswerEvaluationResult.Incorrect)
    }

    @Test
    fun `short text validation tolerates minor typo and leading articles`() {
        val q = sampleQuestion(
            format = QuestionFormat.SHORT_TEXT,
            category = QuestionCategory.BUSINESS,
            canonical = "Gross margin"
        )
        assertTrue(validator.evaluate(q, "the gross margin") is AnswerEvaluationResult.Correct)
        assertTrue(validator.evaluate(q, "gros margin") is AnswerEvaluationResult.Correct)
        assertTrue(validator.evaluate(q, "net loss") is AnswerEvaluationResult.Incorrect)
    }

    @Test
    fun `adaptive planner produces 5-step progression and distributes across selected categories`() {
        val planner = AdaptivePlanner()
        val plan = planner.buildPlan(
            selectedCategories = QuestionCategory.ALL,
            preset = DifficultyPreset.ADAPTIVE,
            count = 5
        )
        assertEquals(5, plan.size)
        assertEquals(
            listOf(
                QuestionDifficulty.EASY,
                QuestionDifficulty.EASY_MEDIUM,
                QuestionDifficulty.MEDIUM,
                QuestionDifficulty.MEDIUM_HARD,
                QuestionDifficulty.HARD
            ),
            plan.map { it.difficulty }
        )
        assertEquals(5, plan.map { it.category }.toSet().size)

        // High-accuracy history shifts adaptive difficulty harder on later slots
        val masteredStats = mapOf(
            QuestionCategory.SQL to CategoryPerformanceStat(
                category = QuestionCategory.SQL,
                totalSolved = 10,
                firstTrySolved = 10,
                totalWrongAttempts = 0,
                lastAskedAtMillis = 1_000L
            )
        )
        val tunedPlan = planner.buildPlan(
            selectedCategories = setOf(QuestionCategory.SQL),
            preset = DifficultyPreset.ADAPTIVE,
            count = 5,
            stats = masteredStats
        )
        assertEquals(QuestionDifficulty.EASY_MEDIUM, tunedPlan[0].difficulty)
        assertEquals(QuestionDifficulty.HARD, tunedPlan[4].difficulty)
    }

    private fun sampleQuestion(
        format: QuestionFormat,
        category: QuestionCategory,
        canonical: String,
        options: List<String> = emptyList(),
        requiredKeywords: List<String> = emptyList(),
        numericalTarget: Double? = null,
        numericalTolerance: Double = 0.01
    ): AlarmQuestion = AlarmQuestion(
        fingerprint = "fp_${format.name}",
        slotIndex = 0,
        category = category,
        difficulty = QuestionDifficulty.MEDIUM,
        format = format,
        provenance = QuestionProvenance.SOURCE_GROUNDED_EXTRACTED,
        prompt = "Sample prompt",
        options = options,
        canonicalAnswer = canonical,
        requiredKeywords = requiredKeywords,
        numericalTarget = numericalTarget,
        numericalTolerance = numericalTolerance,
        explanation = "Verified explanation",
        sourceTitle = "Reference Title",
        sourceProvider = "Microsoft Learn",
        sourceUrl = "https://learn.microsoft.com/en-us/sql/",
        fetchedAtMillis = 1_700_000_000_000L
    )
}

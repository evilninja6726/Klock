package com.klock.app.questions.validation

import com.klock.app.domain.model.AlarmQuestion
import com.klock.app.domain.model.AnswerEvaluationResult
import com.klock.app.domain.model.QuestionFormat
import java.util.Locale
import kotlin.math.abs

/**
 * Pure, deterministic, zero-code-execution answer evaluator.
 * Never invokes Runtime.exec, ProcessBuilder, ScriptEngine, reflection, or WebView JS.
 */
class AnswerValidator {

    fun evaluate(
        question: AlarmQuestion,
        rawInput: String,
        attemptNumber: Int = 1
    ): AnswerEvaluationResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return AnswerEvaluationResult.Incorrect(
                hint = "Enter an answer before submitting.",
                attemptNumber = attemptNumber
            )
        }
        if (trimmed.length > MAX_ANSWER_LENGTH) {
            return AnswerEvaluationResult.Incorrect(
                hint = "Answer is too long (maximum $MAX_ANSWER_LENGTH characters).",
                attemptNumber = attemptNumber
            )
        }

        val isMatch = when (question.format) {
            QuestionFormat.MULTIPLE_CHOICE -> evaluateMultipleChoice(question, trimmed)
            QuestionFormat.NUMERICAL -> evaluateNumerical(question, trimmed)
            QuestionFormat.SQL_QUERY -> evaluateSql(question, trimmed)
            QuestionFormat.PYTHON_OUTPUT -> evaluatePythonOutput(question, trimmed)
            QuestionFormat.CODE_ANSWER -> evaluateCodeAnswer(question, trimmed)
            QuestionFormat.SHORT_TEXT -> evaluateShortText(question, trimmed)
            QuestionFormat.SCENARIO -> {
                if (question.options.isNotEmpty()) {
                    evaluateMultipleChoice(question, trimmed) || evaluateShortText(question, trimmed)
                } else if (question.numericalTarget != null) {
                    evaluateNumerical(question, trimmed)
                } else {
                    evaluateShortText(question, trimmed)
                }
            }
        }

        return if (isMatch) {
            AnswerEvaluationResult.Correct(
                normalizedAnswer = question.canonicalAnswer,
                feedback = question.explanation
            )
        } else {
            AnswerEvaluationResult.Incorrect(
                hint = buildHint(question, attemptNumber),
                attemptNumber = attemptNumber
            )
        }
    }

    private fun evaluateMultipleChoice(question: AlarmQuestion, input: String): Boolean {
        val normalizedInput = normalizeProse(input)
        val normalizedCanonical = normalizeProse(question.canonicalAnswer)
        if (normalizedInput == normalizedCanonical) return true

        if (question.acceptableAlternatives.any { normalizeProse(it) == normalizedInput }) {
            return true
        }

        // Allow selecting by single letter A/B/C/D if options exist
        if (input.length == 1 && question.options.isNotEmpty()) {
            val idx = input.uppercase(Locale.ROOT)[0] - 'A'
            if (idx in question.options.indices) {
                return normalizeProse(question.options[idx]) == normalizedCanonical
            }
        }
        return false
    }

    private fun evaluateNumerical(question: AlarmQuestion, input: String): Boolean {
        val parsed = parseNumericExpressionSafely(input) ?: return false
        val target = question.numericalTarget
            ?: parseNumericExpressionSafely(question.canonicalAnswer)
            ?: return false

        if (abs(parsed - target) <= question.numericalTolerance) return true

        // If the user wrote "25%" for target 25.0 (or 0.25 when canonical has %)
        if (input.endsWith("%")) {
            val rawWithoutPercent = parseNumericExpressionSafely(input.removeSuffix("%").trim())
            if (rawWithoutPercent != null && abs(rawWithoutPercent - target) <= question.numericalTolerance) {
                return true
            }
        }
        return false
    }

    private fun evaluateSql(question: AlarmQuestion, input: String): Boolean {
        val normInput = normalizeSql(input)
        val allAccepted = listOf(question.canonicalAnswer) + question.acceptableAlternatives
        if (allAccepted.any { normalizeSql(it) == normInput }) {
            return true
        }

        // Reject destructive DDL/DML if not part of canonical query
        val upperInput = normInput.uppercase(Locale.ROOT)
        val upperCanonical = normalizeSql(question.canonicalAnswer).uppercase(Locale.ROOT)
        val forbiddenTokens = listOf("DROP ", "TRUNCATE ", "ALTER ")
        if (forbiddenTokens.any { it in upperInput && it !in upperCanonical }) {
            return false
        }

        if (question.requiredKeywords.isNotEmpty()) {
            var searchFrom = 0
            for (kw in question.requiredKeywords) {
                val normKw = normalizeSql(kw).uppercase(Locale.ROOT)
                val idx = upperInput.indexOf(normKw, startIndex = searchFrom)
                if (idx < 0) return false
                searchFrom = idx + normKw.length
            }
            return true
        }
        return false
    }

    private fun evaluatePythonOutput(question: AlarmQuestion, input: String): Boolean {
        val candidates = listOf(question.canonicalAnswer) + question.acceptableAlternatives
        val normInput = normalizePythonOutput(input)
        if (candidates.any { normalizePythonOutput(it) == normInput }) return true

        // Also accept unquoted string literal when canonical output is a plain string or vice versa
        val unquotedInput = normInput.removeSurrounding("\"").removeSurrounding("'")
        return candidates.any {
            val normCand = normalizePythonOutput(it)
            normCand == unquotedInput ||
                normCand.removeSurrounding("\"").removeSurrounding("'") == unquotedInput
        }
    }

    private fun evaluateCodeAnswer(question: AlarmQuestion, input: String): Boolean {
        val normInput = normalizeCode(input)
        val candidates = listOf(question.canonicalAnswer) + question.acceptableAlternatives
        if (candidates.any { normalizeCode(it) == normInput }) return true

        if (question.requiredKeywords.isNotEmpty()) {
            return question.requiredKeywords.all { kw ->
                normInput.contains(normalizeCode(kw), ignoreCase = false)
            }
        }
        return false
    }

    private fun evaluateShortText(question: AlarmQuestion, input: String): Boolean {
        val normInput = normalizeProse(input)
        if (normInput.isEmpty()) return false

        val candidates = (listOf(question.canonicalAnswer) + question.acceptableAlternatives)
            .map { normalizeProse(it) }
            .filter { it.isNotEmpty() }

        for (candidate in candidates) {
            if (normInput == candidate) return true
            // Allow minor spelling typo (distance <= 1 for >= 6 chars, <= 2 for >= 12 chars)
            val maxDist = when {
                candidate.length >= 12 -> 2
                candidate.length >= 6 -> 1
                else -> 0
            }
            if (maxDist > 0 && levenshteinBounded(normInput, candidate, maxDist) <= maxDist) {
                return true
            }
        }

        if (question.requiredKeywords.size >= 2) {
            val matchedAll = question.requiredKeywords.all { kw ->
                normInput.contains(normalizeProse(kw))
            }
            if (matchedAll) return true
        }
        return false
    }

    private fun buildHint(question: AlarmQuestion, attempt: Int): String {
        return if (question.options.isNotEmpty()) {
            "Wrong answer — options shuffled. Try again."
        } else {
            "Wrong answer (attempt $attempt). Try again."
        }
    }

    companion object {
        private const val MAX_ANSWER_LENGTH = 512

        fun normalizeProse(raw: String): String {
            val lower = raw.lowercase(Locale.ROOT)
                .replace(Regex("[\"'`“”‘’]"), "")
                .replace(Regex("[^a-z0-9+#./\\-\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            return lower
                .removePrefix("the ")
                .removePrefix("a ")
                .removePrefix("an ")
                .trim()
        }

        fun normalizeSql(raw: String): String {
            return raw.trim()
                .removeSuffix(";")
                .replace(Regex("[`\"\\[\\]]"), "")
                .replace(Regex("\\s*,\\s*"), ", ")
                .replace(Regex("\\s*\\(\\s*"), "(")
                .replace(Regex("\\s*\\)\\s*"), ")")
                .replace(Regex("\\s*=\\s*"), " = ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .lowercase(Locale.ROOT)
        }

        fun normalizePythonOutput(raw: String): String {
            return raw.trim()
                .replace("'", "\"")
                .replace(Regex("\\s*,\\s*"), ", ")
                .replace(Regex("\\[\\s+"), "[")
                .replace(Regex("\\s+]"), "]")
                .replace(Regex("\\(\\s+"), "(")
                .replace(Regex("\\s+\\)"), ")")
                .replace(Regex("\\s+"), " ")
        }

        fun normalizeCode(raw: String): String {
            return raw.trim()
                .removeSuffix(";")
                .replace("'", "\"")
                .replace(Regex("\\s*([=><+\\-*/(),.:\\[\\]])\\s*"), "$1")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        fun parseNumericExpressionSafely(raw: String): Double? {
            val cleaned = raw.trim()
                .replace("$", "")
                .replace("€", "")
                .replace("£", "")
                .replace("₹", "")
                .replace(",", "")
                .trim()

            if (cleaned.isEmpty()) return null

            val withoutUnits = cleaned.removeSuffix("%").trim()
            withoutUnits.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return it }

            // Simple a/b fraction support (e.g. "3/4")
            if ('/' in withoutUnits && withoutUnits.count { it == '/' } == 1) {
                val parts = withoutUnits.split('/')
                val num = parts[0].trim().toDoubleOrNull()
                val den = parts[1].trim().toDoubleOrNull()
                if (num != null && den != null && den != 0.0) {
                    val ratio = num / den
                    if (ratio.isFinite()) return ratio
                }
            }
            return null
        }

        fun levenshteinBounded(a: String, b: String, maxDistance: Int): Int {
            if (a == b) return 0
            if (abs(a.length - b.length) > maxDistance) return maxDistance + 1
            val prev = IntArray(b.length + 1) { it }
            val curr = IntArray(b.length + 1)
            for (i in 1..a.length) {
                curr[0] = i
                var rowMin = curr[0]
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    curr[j] = minOf(
                        prev[j] + 1,
                        curr[j - 1] + 1,
                        prev[j - 1] + cost
                    )
                    if (curr[j] < rowMin) rowMin = curr[j]
                }
                if (rowMin > maxDistance) return maxDistance + 1
                System.arraycopy(curr, 0, prev, 0, prev.size)
            }
            return prev[b.length]
        }
    }
}

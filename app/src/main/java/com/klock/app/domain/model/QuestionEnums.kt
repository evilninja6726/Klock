package com.klock.app.domain.model

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class SubtopicConfig(
    val name: String,
    val enabled: Boolean = true
)

@Serializable
data class TopicConfig(
    val name: String,
    val id: String = slugifyTopicId(name),
    val enabled: Boolean = true,
    val subtopics: List<SubtopicConfig> = emptyList()
) {
    val activeSubtopics: List<String>
        get() = subtopics.filter { it.enabled && it.name.isNotBlank() }.map { it.name.trim() }

    companion object {
        fun slugifyTopicId(rawName: String): String {
            return rawName.trim()
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .ifBlank { "topic_${System.currentTimeMillis()}" }
        }
    }
}

@Serializable
data class UserProfile(
    val name: String = "",
    val age: Int? = null,
    val currentActivityOrStudy: String = "",
    val onboardingCompleted: Boolean = false
)

@Serializable
enum class QuestionDifficulty(
    val displayName: String,
    val shortTag: String,
    val rank: Int
) {
    EASY("Easy", "E", 1),
    EASY_MEDIUM("Easy/Medium", "E/M", 2),
    MEDIUM("Medium", "M", 3),
    MEDIUM_HARD("Medium/Hard", "M/H", 4),
    HARD("Hard", "H", 5);

    fun shifted(delta: Int): QuestionDifficulty {
        val idx = (ordinal + delta).coerceIn(0, entries.lastIndex)
        return entries[idx]
    }
}

@Serializable
enum class DifficultyPreset(
    val displayName: String,
    val description: String
) {
    ADAPTIVE(
        "Adaptive",
        "Starts easy and gets harder as you go"
    ),
    EASY(
        "Easy",
        "Simple, everyday questions"
    ),
    NORMAL(
        "Normal",
        "Balanced mix of easy and medium questions"
    ),
    HARD(
        "Hard",
        "Challenging questions"
    ),
    EXPERT(
        "Expert",
        "All questions at maximum difficulty"
    );

    fun baseProgression(count: Int = 5): List<QuestionDifficulty> {
        val template = when (this) {
            ADAPTIVE -> listOf(
                QuestionDifficulty.EASY,
                QuestionDifficulty.EASY_MEDIUM,
                QuestionDifficulty.MEDIUM,
                QuestionDifficulty.MEDIUM_HARD,
                QuestionDifficulty.HARD
            )
            EASY -> listOf(
                QuestionDifficulty.EASY,
                QuestionDifficulty.EASY,
                QuestionDifficulty.EASY_MEDIUM,
                QuestionDifficulty.EASY_MEDIUM,
                QuestionDifficulty.MEDIUM
            )
            NORMAL -> listOf(
                QuestionDifficulty.EASY,
                QuestionDifficulty.EASY_MEDIUM,
                QuestionDifficulty.MEDIUM,
                QuestionDifficulty.MEDIUM,
                QuestionDifficulty.MEDIUM_HARD
            )
            HARD -> listOf(
                QuestionDifficulty.MEDIUM,
                QuestionDifficulty.MEDIUM_HARD,
                QuestionDifficulty.MEDIUM_HARD,
                QuestionDifficulty.HARD,
                QuestionDifficulty.HARD
            )
            EXPERT -> List(5) { QuestionDifficulty.HARD }
        }
        if (count <= 0) return emptyList()
        return List(count) { idx ->
            val mappedIndex = ((idx.toDouble() / count.coerceAtLeast(1)) * template.size)
                .toInt()
                .coerceIn(0, template.lastIndex)
            template[mappedIndex]
        }
    }
}

@Serializable
enum class QuestionFormat(val displayName: String) {
    MULTIPLE_CHOICE("Multiple Choice"),
    SQL_QUERY("SQL Query"),
    PYTHON_OUTPUT("Python Output"),
    CODE_ANSWER("Code Expression"),
    NUMERICAL("Numerical"),
    SHORT_TEXT("Short Answer"),
    SCENARIO("Scenario Analysis")
}

@Serializable
enum class QuestionProvenance(
    val badgeLabel: String,
    val description: String
) {
    DIRECTLY_RETRIEVED(
        "DIRECTLY RETRIEVED",
        "Retrieved directly from a live community Q&A or verified dataset endpoint"
    ),
    SOURCE_GROUNDED_EXTRACTED(
        "SOURCE-GROUNDED · LIVE",
        "Constructed directly from freshly fetched live documentation or encyclopedia text"
    ),
    AI_GENERATED_SOURCE_GROUNDED(
        "AI · SOURCE-GROUNDED",
        "Generated via user-configured AI strictly grounded in freshly fetched live source material"
    )
}

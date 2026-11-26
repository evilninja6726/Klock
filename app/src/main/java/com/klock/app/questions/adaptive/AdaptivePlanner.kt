package com.klock.app.questions.adaptive

import com.klock.app.domain.model.DifficultyPreset
import com.klock.app.domain.model.QuestionDifficulty
import com.klock.app.domain.model.TopicConfig
import com.klock.app.domain.model.TopicPerformanceStat
import kotlin.random.Random

data class QuestionSlotPlan(
    val slotIndex: Int,
    val topic: TopicConfig,
    val subtopicName: String?,
    val difficulty: QuestionDifficulty
)

/**
 * Plans question slots across arbitrary user-configured [TopicConfig]s and their [TopicConfig.activeSubtopics],
 * scaling smoothly from 1 to 100 questions.
 */
class AdaptivePlanner(
    private val random: Random = Random.Default
) {

    fun buildPlan(
        topics: List<TopicConfig>,
        preset: DifficultyPreset,
        count: Int = 5,
        stats: Map<String, TopicPerformanceStat> = emptyMap(),
        startSlotIndex: Int = 0,
        totalSessionCount: Int = count,
        nowMillis: Long = System.currentTimeMillis()
    ): List<QuestionSlotPlan> {
        if (count <= 0) return emptyList()
        val activePool = topics.filter { it.enabled && it.name.isNotBlank() }
            .ifEmpty { TopicRecommender.recommendTopics("") }

        val fullDifficulties = preset.baseProgression(totalSessionCount.coerceAtLeast(count + startSlotIndex))

        val scoredTopics = activePool.map { topic ->
            val stat = stats[topic.id]
            val weaknessBoost = if (stat == null || stat.totalSolved == 0) {
                0.35
            } else {
                (1.0 - stat.firstTryAccuracy).coerceIn(0.05, 0.85)
            }
            val hoursSinceAsked = if (stat == null || stat.lastAskedAtMillis <= 0L) {
                48.0
            } else {
                ((nowMillis - stat.lastAskedAtMillis).coerceAtLeast(0L) / 3_600_000.0)
                    .coerceAtMost(72.0)
            }
            val recencyFactor = (hoursSinceAsked / 72.0) * 0.45
            val jitter = random.nextDouble(0.0, 0.20)
            topic to (weaknessBoost + recencyFactor + jitter)
        }.sortedByDescending { it.second }.map { it.first }

        val orderedTopics = if (preset == DifficultyPreset.ADAPTIVE && scoredTopics.size > 1) {
            scoredTopics.sortedByDescending { t -> stats[t.id]?.firstTryAccuracy ?: 0.65 }
        } else {
            scoredTopics
        }

        val subtopicCounters = mutableMapOf<String, Int>()

        return List(count) { offset ->
            val globalIdx = startSlotIndex + offset
            val topic = orderedTopics[globalIdx % orderedTopics.size]
            val subtopics = topic.activeSubtopics
            val chosenSubtopic = if (subtopics.isEmpty()) {
                null
            } else {
                val turn = subtopicCounters.getOrDefault(topic.id, globalIdx / orderedTopics.size)
                subtopicCounters[topic.id] = turn + 1
                subtopics[turn % subtopics.size]
            }

            val baseDiff = fullDifficulties.getOrElse(globalIdx) { QuestionDifficulty.MEDIUM }
            val tunedDiff = if (preset == DifficultyPreset.ADAPTIVE) {
                tuneAdaptiveDifficulty(
                    base = baseDiff,
                    stat = stats[topic.id],
                    slotIndex = globalIdx,
                    totalSlots = totalSessionCount
                )
            } else {
                baseDiff
            }

            QuestionSlotPlan(
                slotIndex = globalIdx,
                topic = topic,
                subtopicName = chosenSubtopic,
                difficulty = tunedDiff
            )
        }
    }

    private fun tuneAdaptiveDifficulty(
        base: QuestionDifficulty,
        stat: TopicPerformanceStat?,
        slotIndex: Int,
        totalSlots: Int
    ): QuestionDifficulty {
        if (stat == null || stat.totalSolved < 3) return base
        return when {
            stat.firstTryAccuracy >= 0.85 -> {
                val bumped = base.shifted(+1)
                if (slotIndex == 0 && bumped.rank > QuestionDifficulty.MEDIUM.rank) {
                    QuestionDifficulty.EASY_MEDIUM
                } else {
                    bumped
                }
            }
            stat.firstTryAccuracy < 0.42 -> {
                val eased = base.shifted(-1)
                if (slotIndex == totalSlots - 1 && eased.rank < QuestionDifficulty.MEDIUM.rank) {
                    QuestionDifficulty.MEDIUM
                } else {
                    eased
                }
            }
            else -> base
        }
    }
}

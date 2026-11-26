package com.klock.app.questions.adaptive

import com.klock.app.domain.model.CategoryPerformanceStat
import com.klock.app.domain.model.DifficultyPreset
import com.klock.app.domain.model.QuestionCategory
import com.klock.app.domain.model.QuestionDifficulty
import kotlin.random.Random

data class QuestionSlotPlan(
    val slotIndex: Int,
    val category: QuestionCategory,
    val difficulty: QuestionDifficulty
)

/**
 * Plans the 5 question slots for an alarm trigger:
 * 1. Distributes categories intelligently across all user-selected categories (avoiding monotony,
 *    rotating under-practiced and weaker categories into active practice).
 * 2. Applies default progression (1: Easy -> 2: Easy/Medium -> 3: Medium -> 4: Medium/Hard -> 5: Hard)
 *    for ADAPTIVE mode and modulates difficulty using historical first-try accuracy.
 */
class AdaptivePlanner(
    private val random: Random = Random.Default
) {

    fun buildPlan(
        selectedCategories: Set<QuestionCategory>,
        preset: DifficultyPreset,
        count: Int = 5,
        stats: Map<QuestionCategory, CategoryPerformanceStat> = emptyMap(),
        nowMillis: Long = System.currentTimeMillis()
    ): List<QuestionSlotPlan> {
        if (count <= 0) return emptyList()
        val pool = selectedCategories.ifEmpty { QuestionCategory.ALL }.toList()
        val baseDifficulties = preset.baseProgression(count)

        // Score each category: lower first-try accuracy and longer time since last asked get higher priority
        val scoredCategories = pool.map { cat ->
            val stat = stats[cat]
            val weaknessBoost = if (stat == null || stat.totalSolved == 0) {
                0.35 // Unexplored category boost
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
            cat to (weaknessBoost + recencyFactor + jitter)
        }.sortedByDescending { it.second }.map { it.first }

        // Select distinct categories first up to pool.size, then cycle if count > pool.size
        val chosenCategories = List(count) { idx ->
            scoredCategories[idx % scoredCategories.size]
        }

        // Order slot 0 with a comfortable/higher-accuracy category for smooth wake-up, escalating to weakest
        val orderedCategories = if (preset == DifficultyPreset.ADAPTIVE && chosenCategories.size > 1) {
            chosenCategories.sortedByDescending { cat ->
                stats[cat]?.firstTryAccuracy ?: 0.65
            }
        } else {
            chosenCategories
        }

        return List(count) { idx ->
            val category = orderedCategories[idx]
            val baseDiff = baseDifficulties[idx]
            val tunedDiff = if (preset == DifficultyPreset.ADAPTIVE) {
                tuneAdaptiveDifficulty(baseDiff, stats[category], slotIndex = idx, totalSlots = count)
            } else {
                baseDiff
            }
            QuestionSlotPlan(
                slotIndex = idx,
                category = category,
                difficulty = tunedDiff
            )
        }
    }

    private fun tuneAdaptiveDifficulty(
        base: QuestionDifficulty,
        stat: CategoryPerformanceStat?,
        slotIndex: Int,
        totalSlots: Int
    ): QuestionDifficulty {
        if (stat == null || stat.totalSolved < 3) return base
        // Keep slot 0 accessible (never shift above MEDIUM on wake-up question 1)
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
                // Keep final question at least MEDIUM even when easing
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

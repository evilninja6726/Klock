package com.klock.app.alarm.scheduler

import com.klock.app.domain.model.Alarm
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

object AlarmTimeCalculator {

    /**
     * Computes the exact next epoch millisecond when [alarm] should ring strictly after [now].
     */
    fun computeNextTriggerMillis(
        alarm: Alarm,
        now: ZonedDateTime = ZonedDateTime.now()
    ): Long {
        return computeNextTriggerZoned(alarm.hour, alarm.minute, alarm.repeatDays, now)
            .toInstant()
            .toEpochMilli()
    }

    fun computeNextTriggerZoned(
        hour: Int,
        minute: Int,
        repeatDays: Set<Int>,
        now: ZonedDateTime = ZonedDateTime.now()
    ): ZonedDateTime {
        val safeHour = hour.coerceIn(0, 23)
        val safeMinute = minute.coerceIn(0, 59)
        val candidateToday = now
            .withHour(safeHour)
            .withMinute(safeMinute)
            .withSecond(0)
            .withNano(0)

        val validDays = repeatDays.filter { it in 1..7 }.toSet()
        if (validDays.isEmpty()) {
            return if (candidateToday.isAfter(now)) {
                candidateToday
            } else {
                candidateToday.plusDays(1)
            }
        }

        for (offsetDays in 0L..7L) {
            val candidate = candidateToday.plusDays(offsetDays)
            val isoDay = candidate.dayOfWeek.value // 1 (Monday) .. 7 (Sunday)
            if (isoDay in validDays && candidate.isAfter(now)) {
                return candidate
            }
        }
        return candidateToday.plusDays(7)
    }

    fun formatCountdown(
        targetEpochMillis: Long,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): String {
        val diffMillis = targetEpochMillis - nowEpochMillis
        if (diffMillis <= 0L) return "Ringing soon"
        val totalMinutes = Duration.ofMillis(diffMillis).toMinutes()
        if (totalMinutes < 1L) return "Rings in < 1 min"
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        return buildString {
            append("Rings in ")
            if (days > 0) append("${days}d ")
            if (hours > 0) append("${hours}h ")
            if (minutes > 0 || (days == 0L && hours == 0L)) append("${minutes}m")
        }.trim()
    }

    fun formatTriggerClock(
        hour: Int,
        minute: Int,
        use24Hour: Boolean = false
    ): Pair<String, String?> {
        return if (use24Hour) {
            String.format("%02d:%02d", hour, minute) to null
        } else {
            val (displayHour, isPm) = to12Hour(hour)
            val period = if (isPm) "PM" else "AM"
            String.format("%d:%02d", displayHour, minute) to period
        }
    }

    /** Returns (hour12 in 1..12, isPm). */
    fun to12Hour(hour24: Int): Pair<Int, Boolean> {
        val safe = hour24.coerceIn(0, 23)
        val isPm = safe >= 12
        val h12 = when {
            safe == 0 -> 12
            safe > 12 -> safe - 12
            else -> safe
        }
        return h12 to isPm
    }

    /** Converts (hour12 in 1..12, isPm) to 0..23 hour24. */
    fun to24Hour(hour12: Int, isPm: Boolean): Int {
        val normalized = hour12.coerceIn(1, 12) % 12
        return if (isPm) normalized + 12 else normalized
    }

    fun epochToZoned(epochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zoneId)
}

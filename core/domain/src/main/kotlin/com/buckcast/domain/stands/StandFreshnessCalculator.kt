package com.buckcast.domain.stands

import java.time.Duration
import java.time.Instant

enum class StandFreshnessLevel(val displayName: String) {
    FRESH("Fresh"),
    WARM("Warm"),
    PRESSURED("Pressured"),
    BURNED("Burned"),
}

/**
 * Historical sit session used to evaluate human intrusion pressure at a stand.
 */
data class StandSitRecord(
    val startTime: Instant,
    val endTime: Instant,
    val windedDeer: Boolean,
) {
    init {
        require(!endTime.isBefore(startTime)) {
            "Sit endTime ($endTime) cannot precede startTime ($startTime)"
        }
    }

    val durationHours: Double
        get() = Duration.between(startTime, endTime).toSeconds() / 3600.0
}

/**
 * Stand freshness evaluation combining sit recency, 14-day hunting load, and winded incidents.
 */
data class StandFreshnessResult(
    val level: StandFreshnessLevel,
    val pressureScore: Double,
    val daysSinceLastSit: Double?,
    val rolling14DaySitHours: Double,
    val rolling14DaySitCount: Int,
    val windedIncidents14Day: Int,
    val recommendedRestDays: Int,
    val reason: String,
)

/**
 * Pure-Kotlin evaluator computing stand freshness (`FRESH` / `WARM` / `PRESSURED` / `BURNED`)
 * from days since last sit, 14-day rolling sit hours, and deer winded/bumped events.
 */
object StandFreshnessCalculator {

    private const val ROLLING_WINDOW_HOURS = 14.0 * 24.0

    fun evaluate(
        sits: List<StandSitRecord>,
        now: Instant,
    ): StandFreshnessResult {
        val pastSits = sits.filter { !it.endTime.isAfter(now) }
        if (pastSits.isEmpty()) {
            return StandFreshnessResult(
                level = StandFreshnessLevel.FRESH,
                pressureScore = 0.0,
                daysSinceLastSit = null,
                rolling14DaySitHours = 0.0,
                rolling14DaySitCount = 0,
                windedIncidents14Day = 0,
                recommendedRestDays = 0,
                reason = "No sits recorded; stand is completely unpressured.",
            )
        }

        val lastSitEnd = pastSits.maxOf { it.endTime }
        val daysSinceLast = Duration.between(lastSitEnd, now).toSeconds() / 86_400.0

        val windowStart = now.minusSeconds((ROLLING_WINDOW_HOURS * 3600.0).toLong())
        val recentSits = pastSits.filter { !it.endTime.isBefore(windowStart) }

        val rollingHours = recentSits.sumOf { sit ->
            val clippedStart = if (sit.startTime.isBefore(windowStart)) windowStart else sit.startTime
            (Duration.between(clippedStart, sit.endTime).toSeconds() / 3600.0).coerceAtLeast(0.0)
        }
        val rollingCount = recentSits.size
        val windedIncidents = recentSits.count { it.windedDeer }

        val recencyPenalty = when {
            daysSinceLast < 1.0 -> 28.0
            daysSinceLast < 3.0 -> 18.0
            daysSinceLast < 5.0 -> 10.0
            daysSinceLast < 7.0 -> 4.0
            else -> 0.0
        }

        val hoursPenalty = (rollingHours * 2.5).coerceAtMost(55.0)

        val windedPenalty = recentSits
            .filter { it.windedDeer }
            .sumOf { sit ->
                val ageDays = Duration.between(sit.endTime, now).toSeconds() / 86_400.0
                when {
                    ageDays <= 3.0 -> 45.0
                    ageDays <= 7.0 -> 30.0
                    else -> 15.0
                }
            }

        val pressureScore = (recencyPenalty + hoursPenalty + windedPenalty).coerceIn(0.0, 100.0)

        val level = when {
            pressureScore >= 70.0 -> StandFreshnessLevel.BURNED
            pressureScore >= 45.0 -> StandFreshnessLevel.PRESSURED
            pressureScore >= 20.0 -> StandFreshnessLevel.WARM
            else -> StandFreshnessLevel.FRESH
        }

        val recommendedRestDays = when (level) {
            StandFreshnessLevel.FRESH -> 0
            StandFreshnessLevel.WARM -> 2
            StandFreshnessLevel.PRESSURED -> 5
            StandFreshnessLevel.BURNED -> if (windedIncidents >= 2) 14 else 10
        }

        val reason = when (level) {
            StandFreshnessLevel.FRESH ->
                "Rested (${String.format(java.util.Locale.US, "%.1f", daysSinceLast)}d since last sit, low 14d pressure)."
            StandFreshnessLevel.WARM ->
                "Light recent pressure (${rollingCount} sit(s), ${String.format(java.util.Locale.US, "%.1f", rollingHours)}h in 14d)."
            StandFreshnessLevel.PRESSURED ->
                "Elevated 14-day hunting load (${String.format(java.util.Locale.US, "%.1f", rollingHours)}h across $rollingCount sits)."
            StandFreshnessLevel.BURNED ->
                if (windedIncidents > 0) {
                    "Stand burned: $windedIncidents winded/bumped incident(s) and ${String.format(java.util.Locale.US, "%.1f", rollingHours)}h pressure in 14d."
                } else {
                    "Stand over-hunted (${String.format(java.util.Locale.US, "%.1f", rollingHours)}h in 14d); rest $recommendedRestDays days."
                }
        }

        return StandFreshnessResult(
            level = level,
            pressureScore = pressureScore,
            daysSinceLastSit = daysSinceLast,
            rolling14DaySitHours = rollingHours,
            rolling14DaySitCount = rollingCount,
            windedIncidents14Day = windedIncidents,
            recommendedRestDays = recommendedRestDays,
            reason = reason,
        )
    }
}

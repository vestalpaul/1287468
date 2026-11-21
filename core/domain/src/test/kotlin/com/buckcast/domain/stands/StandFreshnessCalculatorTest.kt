package com.buckcast.domain.stands

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class StandFreshnessCalculatorTest {

    private val now = Instant.parse("2026-11-21T18:00:00Z")

    @Test
    fun `unhunted stand is FRESH with zero pressure score`() {
        val result = StandFreshnessCalculator.evaluate(emptyList(), now)
        assertEquals(StandFreshnessLevel.FRESH, result.level)
        assertEquals(0.0, result.pressureScore, 1e-6)
        assertNull(result.daysSinceLastSit)
        assertEquals(0, result.recommendedRestDays)
    }

    @Test
    fun `single recent morning sit moves stand to WARM and heavy 14-day load reaches PRESSURED`() {
        val warmSit = listOf(
            StandSitRecord(
                startTime = now.minus(2, ChronoUnit.DAYS).minus(4, ChronoUnit.HOURS),
                endTime = now.minus(2, ChronoUnit.DAYS),
                windedDeer = false,
            ),
        )
        val warm = StandFreshnessCalculator.evaluate(warmSit, now)
        assertEquals(StandFreshnessLevel.WARM, warm.level)
        assertEquals(4.0, warm.rolling14DaySitHours, 1e-6)
        assertEquals(1, warm.rolling14DaySitCount)

        val pressuredSits = listOf(1L, 3L, 6L, 9L, 12L).map { daysAgo ->
            StandSitRecord(
                startTime = now.minus(daysAgo, ChronoUnit.DAYS).minus(4, ChronoUnit.HOURS),
                endTime = now.minus(daysAgo, ChronoUnit.DAYS),
                windedDeer = false,
            )
        }
        val pressured = StandFreshnessCalculator.evaluate(pressuredSits, now)
        assertEquals(StandFreshnessLevel.PRESSURED, pressured.level)
        assertEquals(20.0, pressured.rolling14DaySitHours, 1e-6)
        assertTrue(pressured.recommendedRestDays >= 5)
    }

    @Test
    fun `recent winded incident pushes stand to BURNED and older sits over 14 days decay back to FRESH`() {
        val burnedSits = listOf(
            StandSitRecord(
                startTime = now.minus(18, ChronoUnit.HOURS),
                endTime = now.minus(15, ChronoUnit.HOURS),
                windedDeer = true,
            ),
        )
        val burned = StandFreshnessCalculator.evaluate(burnedSits, now)
        assertEquals(StandFreshnessLevel.BURNED, burned.level)
        assertEquals(1, burned.windedIncidents14Day)
        assertTrue(burned.pressureScore >= 70.0)
        assertTrue(burned.recommendedRestDays >= 10)

        val agedOutSits = listOf(
            StandSitRecord(
                startTime = now.minus(20, ChronoUnit.DAYS).minus(4, ChronoUnit.HOURS),
                endTime = now.minus(20, ChronoUnit.DAYS),
                windedDeer = true,
            ),
        )
        val recovered = StandFreshnessCalculator.evaluate(agedOutSits, now)
        assertEquals(StandFreshnessLevel.FRESH, recovered.level)
        assertEquals(0.0, recovered.rolling14DaySitHours, 1e-6)
        assertEquals(0, recovered.windedIncidents14Day)
    }
}

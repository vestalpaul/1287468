package com.buckcast.domain.astronomy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

class LunarCalculatorTest {

    private val desMoinesLat = 41.5868
    private val desMoinesLon = -93.6250
    private val centralZone = ZoneId.of("America/Chicago")

    @Test
    fun `known 2026 USNO New, First Quarter, Full, and Last Quarter dates match phase and illumination`() {
        // USNO Nov 2026 UTC instants
        val lastQuarter = LunarCalculator.calculatePhaseAt(Instant.parse("2026-11-01T20:28:00Z"))
        assertEquals(MoonPhase.LAST_QUARTER, lastQuarter.phase)
        assertEquals(0.75, lastQuarter.phaseFraction, 0.02)
        assertEquals(50.0, lastQuarter.illuminationPercent, 2.5)

        val newMoon = LunarCalculator.calculatePhaseAt(Instant.parse("2026-11-09T07:02:00Z"))
        assertEquals(MoonPhase.NEW_MOON, newMoon.phase)
        assertTrue(newMoon.phaseFraction < 0.02 || newMoon.phaseFraction > 0.98)
        assertTrue(newMoon.illuminationPercent < 1.5, "New moon illumination should be < 1.5%, was ${newMoon.illuminationPercent}")

        val firstQuarter = LunarCalculator.calculatePhaseAt(Instant.parse("2026-11-17T11:47:00Z"))
        assertEquals(MoonPhase.FIRST_QUARTER, firstQuarter.phase)
        assertEquals(0.25, firstQuarter.phaseFraction, 0.02)
        assertEquals(50.0, firstQuarter.illuminationPercent, 2.5)

        val fullMoon = LunarCalculator.calculatePhaseAt(Instant.parse("2026-11-24T14:53:00Z"))
        assertEquals(MoonPhase.FULL_MOON, fullMoon.phase)
        assertEquals(0.50, fullMoon.phaseFraction, 0.02)
        assertTrue(fullMoon.illuminationPercent > 98.5, "Full moon illumination should be > 98.5%, was ${fullMoon.illuminationPercent}")
    }

    @Test
    fun `Des Moines Nov 11 moonrise and moonset match published tables within 5 minutes`() {
        val info = LunarCalculator.calculateDay(
            date = LocalDate.of(2026, 11, 11),
            latitude = desMoinesLat,
            longitude = desMoinesLon,
            zoneId = centralZone,
        )

        assertEquals(MoonPhase.WAXING_CRESCENT, info.phase)
        assertEquals(5.8, info.illuminationPercent, 3.0)
        assertNotNull(info.moonrise)
        assertNotNull(info.moonset)

        val riseDiffSec = abs(Duration.between(LocalTime.of(9, 36), info.moonrise!!.toLocalTime()).seconds)
        val setDiffSec = abs(Duration.between(LocalTime.of(18, 14), info.moonset!!.toLocalTime()).seconds)
        assertTrue(riseDiffSec <= 300L, "Moonrise expected ~09:36, got ${info.moonrise!!.toLocalTime()}")
        assertTrue(setDiffSec <= 300L, "Moonset expected ~18:14, got ${info.moonset!!.toLocalTime()}")
        assertTrue(info.lunarAgeDays in 0.0..29.6)
    }

    @Test
    fun `lunar cycle includes civil days with no moonrise or no moonset returning null`() {
        // Across one full 30-day lunation at mid-latitude, a 24.8h lunar day guarantees >= 1 no-moonrise day and >= 1 no-moonset day.
        val days = (0L..30L).map { offset ->
            LunarCalculator.calculateDay(
                date = LocalDate.of(2026, 11, 1).plusDays(offset),
                latitude = desMoinesLat,
                longitude = desMoinesLon,
                zoneId = centralZone,
            )
        }
        assertTrue(days.any { it.moonrise == null }, "Expected at least one civil day with null moonrise")
        assertTrue(days.any { it.moonset == null }, "Expected at least one civil day with null moonset")
        assertTrue(days.any { it.moonOverhead != null }, "Expected overhead meridian transit times")
        assertTrue(days.any { it.moonUnderfoot != null }, "Expected underfoot meridian transit times")
    }
}

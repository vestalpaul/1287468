package com.buckcast.domain.astronomy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

class NoaaSolarCalculatorTest {

    private val desMoinesLat = 41.5868
    private val desMoinesLon = -93.6250
    private val centralZone = ZoneId.of("America/Chicago")

    @Test
    fun `Des Moines Nov 11 solar events match NOAA and USNO within 2 minutes`() {
        val date = LocalDate.of(2026, 11, 11)
        val info = NoaaSolarCalculator.calculateDay(date, desMoinesLat, desMoinesLon, centralZone)

        assertEquals(SolarDayCondition.NORMAL, info.condition)
        assertNotNull(info.civilDawn)
        assertNotNull(info.sunrise)
        assertNotNull(info.sunset)
        assertNotNull(info.civilDusk)

        // Official Des Moines Nov 11 CST: Dawn 06:29, Sunrise 06:58, Noon 11:58, Sunset 16:58, Dusk 17:28
        assertWithinMinutes(LocalTime.of(6, 29), info.civilDawn!!.toLocalTime(), 2L, "civilDawn")
        assertWithinMinutes(LocalTime.of(6, 58), info.sunrise!!.toLocalTime(), 2L, "sunrise")
        assertWithinMinutes(LocalTime.of(11, 58), info.solarNoon.toLocalTime(), 2L, "solarNoon")
        assertWithinMinutes(LocalTime.of(16, 58), info.sunset!!.toLocalTime(), 2L, "sunset")
        assertWithinMinutes(LocalTime.of(17, 28), info.civilDusk!!.toLocalTime(), 2L, "civilDusk")

        // Strict chronological ordering
        assertTrue(info.civilDawn!!.isBefore(info.sunrise))
        assertTrue(info.sunrise!!.isBefore(info.solarNoon))
        assertTrue(info.solarNoon.isBefore(info.sunset))
        assertTrue(info.sunset!!.isBefore(info.civilDusk))

        // Exact -30m / +30m legal light offsets
        assertEquals(Duration.ofMinutes(30), Duration.between(info.legalShootingStart, info.sunrise))
        assertEquals(Duration.ofMinutes(30), Duration.between(info.sunset, info.legalShootingEnd))
        assertTrue(
            info.daylightDuration.toMinutes() in 598L..602L,
            "Daylight duration expected ~600m, got ${info.daylightDuration.toMinutes()}m",
        )
    }

    @Test
    fun `custom jurisdiction legal light offsets shift legal start and end cleanly`() {
        val date = LocalDate.of(2026, 11, 11)
        val rule = LegalLightRule(beforeSunriseMinutes = 20L, afterSunsetMinutes = 25L)
        val info = NoaaSolarCalculator.calculateDay(date, desMoinesLat, desMoinesLon, centralZone, rule)

        assertEquals(Duration.ofMinutes(20), Duration.between(info.legalShootingStart, info.sunrise))
        assertEquals(Duration.ofMinutes(25), Duration.between(info.sunset, info.legalShootingEnd))
    }

    @Test
    fun `DST transition days maintain valid local wall-clock ordering and daylight durations`() {
        // Spring forward (2026-03-08) and Fall back (2026-11-01) in America/Chicago
        val springInfo = NoaaSolarCalculator.calculateDay(
            LocalDate.of(2026, 3, 8),
            desMoinesLat,
            desMoinesLon,
            centralZone,
        )
        assertTrue(springInfo.sunrise!!.offset.totalSeconds == -5 * 3600)
        assertTrue(springInfo.sunrise!!.isBefore(springInfo.sunset))

        val fallInfo = NoaaSolarCalculator.calculateDay(
            LocalDate.of(2026, 11, 1),
            desMoinesLat,
            desMoinesLon,
            centralZone,
        )
        assertTrue(fallInfo.sunrise!!.offset.totalSeconds == -6 * 3600)
        assertTrue(fallInfo.sunrise!!.isBefore(fallInfo.sunset))
        assertEquals(Duration.ofMinutes(30), Duration.between(fallInfo.legalShootingStart, fallInfo.sunrise))
    }

    @Test
    fun `solar position gives normalized azimuth and reasonable midday elevation`() {
        val date = LocalDate.of(2026, 6, 21)
        val info = NoaaSolarCalculator.calculateDay(date, desMoinesLat, desMoinesLon, centralZone)
        val noonPos = NoaaSolarCalculator.calculatePosition(info.solarNoon.toInstant(), desMoinesLat, desMoinesLon)

        // Summer solstice noon elevation at 41.59°N is ~ 90 - 41.59 + 23.44 = 71.85°
        assertEquals(71.85, noonPos.elevationDeg, 0.5)
        assertEquals(180.0, noonPos.azimuthDeg, 2.0)
        assertTrue(noonPos.azimuthDeg in 0.0..<360.0)
    }

    @Test
    fun `high-latitude Arctic summer and winter return POLAR_DAY and POLAR_NIGHT`() {
        val arcticZone = ZoneId.of("Arctic/Longyearbyen")
        val polarDay = NoaaSolarCalculator.calculateDay(LocalDate.of(2026, 6, 21), 78.2232, 15.6267, arcticZone)
        assertEquals(SolarDayCondition.POLAR_DAY, polarDay.condition)
        assertNull(polarDay.sunrise)
        assertNull(polarDay.sunset)
        assertEquals(Duration.ofHours(24), polarDay.daylightDuration)

        val polarNight = NoaaSolarCalculator.calculateDay(LocalDate.of(2026, 12, 21), 78.2232, 15.6267, arcticZone)
        assertEquals(SolarDayCondition.POLAR_NIGHT, polarNight.condition)
        assertNull(polarNight.sunrise)
        assertNull(polarNight.sunset)
        assertNull(polarNight.civilDawn)
        assertEquals(Duration.ZERO, polarNight.daylightDuration)
    }

    private fun assertWithinMinutes(
        expected: LocalTime,
        actual: LocalTime,
        toleranceMinutes: Long,
        label: String,
    ) {
        val diffSeconds = abs(Duration.between(expected, actual).seconds)
        assertTrue(
            diffSeconds <= toleranceMinutes * 60L,
            "$label expected ~$expected but was $actual (diff=${diffSeconds}s)",
        )
    }
}

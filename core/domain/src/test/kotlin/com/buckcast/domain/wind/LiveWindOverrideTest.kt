package com.buckcast.domain.wind

import com.buckcast.domain.math.AngleMath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveWindOverrideTest {

    @Test
    fun `windFromMilkweedFloatDeg converts downwind float bearing into meteorological wind-FROM degrees`() {
        assertEquals(180.0, windFromMilkweedFloatDeg(0.0), 1e-9)
        assertEquals(315.0, windFromMilkweedFloatDeg(135.0), 1e-9)
        assertEquals(90.0, windFromMilkweedFloatDeg(270.0), 1e-9)
        assertEquals(179.5, windFromMilkweedFloatDeg(359.5), 1e-9)
        assertEquals(0.0, windFromMilkweedFloatDeg(180.0), 1e-9)

        assertThrows(IllegalArgumentException::class.java) {
            windFromMilkweedFloatDeg(Double.NaN)
        }
    }

    @Test
    fun `LiveWindOverride expires cleanly after 4 hour default duration`() {
        val t0 = 1_795_000_000_000L
        val override = LiveWindOverride.fromMilkweedFloat(
            downwindFloatHeadingDeg = 135.0,
            windSpeedMph = 6.0,
            setAtEpochMs = t0,
        )
        assertEquals(315.0, override.windFromDeg, 1e-9)
        assertEquals(t0 + LiveWindOverride.DEFAULT_DURATION_MS, override.expiresAtEpochMs)
        assertFalse(override.isExpired(t0))
        assertFalse(override.isExpired(t0 + LiveWindOverride.DEFAULT_DURATION_MS - 1L))
        assertTrue(override.isExpired(t0 + LiveWindOverride.DEFAULT_DURATION_MS))
        assertTrue(override.isExpired(t0 + LiveWindOverride.DEFAULT_DURATION_MS + 60_000L))
    }

    @Test
    fun `AngleMath roundWholeDegrees wraps 359_5 and negative zero safely to 0 without ever producing 360`() {
        assertEquals(0, AngleMath.roundWholeDegrees(359.5))
        assertEquals(0, AngleMath.roundWholeDegrees(359.98))
        assertEquals(0, AngleMath.roundWholeDegrees(-0.2))
        assertEquals(359, AngleMath.roundWholeDegrees(359.4))
        assertEquals(0, AngleMath.roundWholeDegrees(0.0))
        assertThrows(IllegalArgumentException::class.java) {
            AngleMath.roundWholeDegrees(Double.NaN)
        }
    }
}

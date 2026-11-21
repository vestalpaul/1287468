package com.buckcast.domain.wind

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ThermalDriftCalculatorTest {

    private val zone = ZoneId.of("America/Chicago")
    private val sunrise = ZonedDateTime.of(2026, 11, 11, 7, 0, 0, 0, zone)
    private val sunset = ZonedDateTime.of(2026, 11, 11, 17, 0, 0, 0, zone)

    @Test
    fun `pre-dawn katabatic cooling drains downhill toward slopeAspectDeg`() {
        val result = ThermalDriftCalculator.calculate(
            ThermalInput(
                slopeAspectDeg = 180.0, // South-facing downhill slope
                slopeGradePercent = 25.0,
                currentTime = sunrise.minusMinutes(15),
                sunrise = sunrise,
                sunset = sunset,
                cloudCoverPercent = 10.0,
                ambientWindSpeedMph = 0.0,
                ambientWindFromDeg = 0.0,
            ),
        )

        assertEquals(ThermalRegime.KATABATIC_DOWNHILL, result.regime)
        assertEquals(180.0, result.thermalDriftToDeg, 1e-6)
        assertEquals(180.0, result.effectiveScentToDeg, 1e-6)
        assertTrue(result.thermalSpeedMph > 1.0)
        assertFalse(result.isTransitionSwirlRisk)
        assertFalse(result.isLeewardRotorRisk)
    }

    @Test
    fun `midday anabatic warming rises uphill opposite slopeAspectDeg`() {
        val result = ThermalDriftCalculator.calculate(
            ThermalInput(
                slopeAspectDeg = 180.0, // South downhill -> North uphill (0°)
                slopeGradePercent = 25.0,
                currentTime = sunrise.plusHours(5),
                sunrise = sunrise,
                sunset = sunset,
                cloudCoverPercent = 0.0,
                ambientWindSpeedMph = 0.0,
                ambientWindFromDeg = 0.0,
            ),
        )

        assertEquals(ThermalRegime.ANABATIC_UPHILL, result.regime)
        assertEquals(0.0, result.thermalDriftToDeg, 1e-6)
        assertEquals(0.0, result.effectiveScentToDeg, 1e-6)
        assertTrue(result.thermalSpeedMph > 1.0)
        assertFalse(result.isTransitionSwirlRisk)
    }

    @Test
    fun `morning and evening reversal windows trigger transition swirl risk`() {
        val morning = ThermalDriftCalculator.calculate(
            ThermalInput(
                slopeAspectDeg = 90.0,
                slopeGradePercent = 20.0,
                currentTime = sunrise.plusMinutes(75),
                sunrise = sunrise,
                sunset = sunset,
                cloudCoverPercent = 20.0,
                ambientWindSpeedMph = 3.0,
                ambientWindFromDeg = 270.0,
            ),
        )
        assertEquals(ThermalRegime.MORNING_TRANSITION, morning.regime)
        assertTrue(morning.isTransitionSwirlRisk)

        val evening = ThermalDriftCalculator.calculate(
            ThermalInput(
                slopeAspectDeg = 90.0,
                slopeGradePercent = 20.0,
                currentTime = sunset.minusMinutes(30),
                sunrise = sunrise,
                sunset = sunset,
                cloudCoverPercent = 20.0,
                ambientWindSpeedMph = 3.0,
                ambientWindFromDeg = 270.0,
            ),
        )
        assertEquals(ThermalRegime.EVENING_TRANSITION, evening.regime)
        assertTrue(evening.isTransitionSwirlRisk)
    }

    @Test
    fun `flat terrain yields NEUTRAL_FLAT with zero thermal drift`() {
        val flat = ThermalDriftCalculator.calculate(
            ThermalInput(
                slopeAspectDeg = 220.0,
                slopeGradePercent = 1.0,
                currentTime = sunrise.minusMinutes(30),
                sunrise = sunrise,
                sunset = sunset,
                cloudCoverPercent = 0.0,
                ambientWindSpeedMph = 6.0,
                ambientWindFromDeg = 315.0,
            ),
        )
        assertEquals(ThermalRegime.NEUTRAL_FLAT, flat.regime)
        assertEquals(0.0, flat.thermalSpeedMph, 1e-9)
        assertEquals(135.0, flat.effectiveScentToDeg, 1e-6)
        assertEquals(6.0, flat.effectiveSpeedMph, 1e-6)
        assertEquals(0.0, flat.thermalDominanceRatio, 1e-9)
    }

    @Test
    fun `cloud cover and high synoptic wind suppress thermal speed and vector-combine cleanly`() {
        val clearCalm = ThermalDriftCalculator.calculate(
            ThermalInput(
                slopeAspectDeg = 180.0,
                slopeGradePercent = 30.0,
                currentTime = sunrise.plusHours(5),
                sunrise = sunrise,
                sunset = sunset,
                cloudCoverPercent = 0.0,
                ambientWindSpeedMph = 2.0,
                ambientWindFromDeg = 270.0, // blows East (90°), thermal blows North (0°)
            ),
        )
        val overcastWindy = ThermalDriftCalculator.calculate(
            ThermalInput(
                slopeAspectDeg = 180.0,
                slopeGradePercent = 30.0,
                currentTime = sunrise.plusHours(5),
                sunrise = sunrise,
                sunset = sunset,
                cloudCoverPercent = 100.0,
                ambientWindSpeedMph = 18.0,
                ambientWindFromDeg = 270.0,
            ),
        )
        assertTrue(clearCalm.thermalSpeedMph > overcastWindy.thermalSpeedMph * 5.0)
        // Cross-slope wind (90° at 2 mph) + North thermal (0°) pulls effective scent vector into NE quadrant (0..90°)
        assertTrue(clearCalm.effectiveScentToDeg in 0.0..90.0)
        assertTrue(clearCalm.thermalDominanceRatio > overcastWindy.thermalDominanceRatio)
    }

    @Test
    fun `leeward ridge rotor risk triggers when wind over 8 mph blows downslope behind stand`() {
        // Slope faces East (90° downhill). West wind (from 270°) blows East (90°), right downslope over the ridge!
        val rotorHazard = ThermalDriftCalculator.calculate(
            ThermalInput(
                slopeAspectDeg = 90.0,
                slopeGradePercent = 22.0,
                currentTime = sunrise.plusHours(4),
                sunrise = sunrise,
                sunset = sunset,
                cloudCoverPercent = 30.0,
                ambientWindSpeedMph = 12.0,
                ambientWindFromDeg = 270.0,
            ),
        )
        assertTrue(rotorHazard.isLeewardRotorRisk)

        // Same direction at 7.5 mph (<= 8 mph) does not trigger leeward rotor hazard
        val gentleWind = ThermalDriftCalculator.calculate(
            ThermalInput(
                slopeAspectDeg = 90.0,
                slopeGradePercent = 22.0,
                currentTime = sunrise.plusHours(4),
                sunrise = sunrise,
                sunset = sunset,
                cloudCoverPercent = 30.0,
                ambientWindSpeedMph = 7.5,
                ambientWindFromDeg = 270.0,
            ),
        )
        assertFalse(gentleWind.isLeewardRotorRisk)

        // Strong East wind blowing UPHILL (scentTo=270°, opposite 90°) does not create a leeward rotor behind stand
        val upslopeWind = ThermalDriftCalculator.calculate(
            ThermalInput(
                slopeAspectDeg = 90.0,
                slopeGradePercent = 22.0,
                currentTime = sunrise.plusHours(4),
                sunrise = sunrise,
                sunset = sunset,
                cloudCoverPercent = 30.0,
                ambientWindSpeedMph = 14.0,
                ambientWindFromDeg = 90.0,
            ),
        )
        assertFalse(upslopeWind.isLeewardRotorRisk)
    }
}

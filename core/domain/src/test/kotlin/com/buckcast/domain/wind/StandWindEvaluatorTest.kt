package com.buckcast.domain.wind

import com.buckcast.domain.math.BearingSector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class StandWindEvaluatorTest {

    private val oakRidgeStand = StandWindConfig(
        standId = "stand-oak-ridge",
        standName = "Oak Ridge Pinch",
        preferredWindFromSectors = listOf(BearingSector(300.0, 45.0)), // NW through N to NE
        cautionMarginDeg = 15.0,
        deerZoneBearingsDeg = listOf(0.0), // Expected doe bedding North (upwind for a North-wind stand)
        scentConeHalfAngleDeg = 22.5,
        calmWindThresholdMph = 3.0,
    )

    @Test
    fun `wrap-around north sector evaluates OPTIMAL, GOOD, CAUTION, and UNSAFE accurately`() {
        val optimal = StandWindEvaluator.evaluate(oakRidgeStand, ambientWindFromDeg = 355.0, ambientWindSpeedMph = 8.0)
        assertEquals(StandWindVerdict.OPTIMAL, optimal.verdict)
        assertTrue(optimal.score >= 88.0)
        assertTrue(optimal.effectiveScentToDeg in 0.0..<360.0)
        assertTrue(optimal.scentConeMinDeg in 0.0..<360.0)
        assertTrue(optimal.scentConeMaxDeg in 0.0..<360.0)

        val edgeGood = StandWindEvaluator.evaluate(oakRidgeStand, ambientWindFromDeg = 40.0, ambientWindSpeedMph = 8.0)
        assertEquals(StandWindVerdict.GOOD, edgeGood.verdict)

        val marginalCaution = StandWindEvaluator.evaluate(oakRidgeStand, ambientWindFromDeg = 55.0, ambientWindSpeedMph = 8.0)
        assertEquals(StandWindVerdict.CAUTION, marginalCaution.verdict)

        val blownOut = StandWindEvaluator.evaluate(oakRidgeStand, ambientWindFromDeg = 200.0, ambientWindSpeedMph = 8.0)
        assertEquals(StandWindVerdict.UNSAFE, blownOut.verdict)
    }

    @Test
    fun `deer zone scent cone intersection forces UNSAFE even if sector were unconstrained`() {
        val openBlind = StandWindConfig(
            standId = "blind-1",
            standName = "Food Plot Blind",
            preferredWindFromSectors = emptyList(),
            deerZoneBearingsDeg = listOf(90.0), // Deer approach from East
            scentConeHalfAngleDeg = 22.5,
        )
        // West wind (270°) blows scent directly East (90°) over deer zone
        val unsafe = StandWindEvaluator.evaluate(openBlind, ambientWindFromDeg = 270.0, ambientWindSpeedMph = 9.0)
        assertEquals(StandWindVerdict.UNSAFE, unsafe.verdict)
        assertEquals(listOf(90.0), unsafe.compromisedDeerZonesDeg)
    }

    @Test
    fun `calm winds and leeward rotor trigger swirling CAUTION warnings`() {
        val calm = StandWindEvaluator.evaluate(oakRidgeStand, ambientWindFromDeg = 350.0, ambientWindSpeedMph = 1.5)
        assertEquals(StandWindVerdict.CAUTION, calm.verdict)
        assertTrue(calm.isSwirlingRisk)

        val zone = ZoneId.of("America/Chicago")
        val sunrise = ZonedDateTime.of(2026, 11, 11, 7, 0, 0, 0, zone)
        val sunset = ZonedDateTime.of(2026, 11, 11, 17, 0, 0, 0, zone)
        val rotorThermal = ThermalDriftCalculator.calculate(
            ThermalInput(
                slopeAspectDeg = 180.0, // South downhill slope
                slopeGradePercent = 20.0,
                currentTime = sunrise.plusHours(4),
                sunrise = sunrise,
                sunset = sunset,
                cloudCoverPercent = 20.0,
                ambientWindSpeedMph = 12.0,
                ambientWindFromDeg = 0.0, // North wind blows South downslope over ridge behind stand
            ),
        )
        val withRotor = StandWindEvaluator.evaluate(
            oakRidgeStand,
            ambientWindFromDeg = 0.0,
            ambientWindSpeedMph = 12.0,
            thermalDrift = rotorThermal,
        )
        assertEquals(StandWindVerdict.CAUTION, withRotor.verdict)
        assertTrue(withRotor.isLeewardRotorRisk)
        assertTrue(withRotor.isSwirlingRisk)
        assertTrue(withRotor.compromisedDeerZonesDeg.isEmpty())
    }

    @Test
    fun `WindAlignmentClassifier enforces 5-degree hysteresis at 45 and 135 degree thresholds`() {
        // Initial classification at 44° -> WIND_IN_FACE
        val initial = WindAlignmentClassifier.classify(facingHeadingDeg = 0.0, windFromDeg = 44.0)
        assertEquals(WindAlignmentBadge.WIND_IN_FACE, initial)

        // Jitter across 45° up to 49° stays WIND_IN_FACE due to 5° hysteresis (cutoff is 50°)
        val jitterAt49 = WindAlignmentClassifier.classify(
            facingHeadingDeg = 0.0,
            windFromDeg = 49.0,
            previousBadge = initial,
        )
        assertEquals(WindAlignmentBadge.WIND_IN_FACE, jitterAt49)

        // Crossing 51° transitions to CROSSWIND
        val crosswind = WindAlignmentClassifier.classify(
            facingHeadingDeg = 0.0,
            windFromDeg = 51.0,
            previousBadge = jitterAt49,
        )
        assertEquals(WindAlignmentBadge.CROSSWIND, crosswind)

        // Jitter back down to 41° stays CROSSWIND (cutoff is 40°)
        val stayCrosswind = WindAlignmentClassifier.classify(
            facingHeadingDeg = 0.0,
            windFromDeg = 41.0,
            previousBadge = crosswind,
        )
        assertEquals(WindAlignmentBadge.CROSSWIND, stayCrosswind)

        // Crossing 141° transitions to WIND_AT_BACK, then stays there at 131°
        val backWind = WindAlignmentClassifier.classify(
            facingHeadingDeg = 0.0,
            windFromDeg = 141.0,
            previousBadge = stayCrosswind,
        )
        assertEquals(WindAlignmentBadge.WIND_AT_BACK, backWind)
        assertEquals(
            WindAlignmentBadge.WIND_AT_BACK,
            WindAlignmentClassifier.classify(facingHeadingDeg = 0.0, windFromDeg = 131.0, previousBadge = backWind),
        )
    }
}

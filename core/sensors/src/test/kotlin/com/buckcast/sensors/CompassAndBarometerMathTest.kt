package com.buckcast.sensors

import android.hardware.SensorManager
import android.view.Surface
import com.buckcast.domain.math.AngleMath
import com.buckcast.domain.math.GeoPoint
import com.buckcast.domain.math.GeoUnits
import com.buckcast.domain.wind.WindAlignmentBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CompassAndBarometerMathTest {

    @Test
    fun `skyward posture switches above 55 deg with 5 deg hysteresis and nose-down tilts never flip upright`() {
        var posture = CompassPosture.FLAT_MAP

        // 55° exactly stays FLAT_MAP
        posture = CompassFusionMath.resolvePostureFromSkywardZ(sin(Math.toRadians(55.0)), posture)
        assertEquals(CompassPosture.FLAT_MAP, posture)

        // Nose-down -70° (r7 < 0) stays FLAT_MAP
        posture = CompassFusionMath.resolvePostureFromSkywardZ(sin(Math.toRadians(-70.0)), posture)
        assertEquals(CompassPosture.FLAT_MAP, posture)

        // Skyward 56° enters UPRIGHT_AIMING
        posture = CompassFusionMath.resolvePostureFromSkywardZ(sin(Math.toRadians(56.0)), posture)
        assertEquals(CompassPosture.UPRIGHT_AIMING, posture)

        // 52° inside hysteresis band holds UPRIGHT_AIMING
        posture = CompassFusionMath.resolvePostureFromSkywardZ(sin(Math.toRadians(52.0)), posture)
        assertEquals(CompassPosture.UPRIGHT_AIMING, posture)

        // 49° drops back to FLAT_MAP
        posture = CompassFusionMath.resolvePostureFromSkywardZ(sin(Math.toRadians(49.0)), posture)
        assertEquals(CompassPosture.FLAT_MAP, posture)
    }

    @Test
    fun `3D rotation matrix column projection maintains under 0_5 deg continuity across posture switch for headings 0, 90, 215, 330`() {
        val targetHeadings = listOf(0.0, 90.0, 215.0, 330.0)
        val pitchAngles = listOf(0.0, 30.0, 55.0, 60.0, 75.0, 85.0)

        for (heading in targetHeadings) {
            var posture = CompassPosture.FLAT_MAP
            for (pitch in pitchAngles) {
                val matrix = buildWorldRotationMatrix(headingDeg = heading, skywardPitchDeg = pitch)
                posture = CompassFusionMath.resolvePostureFromSkywardZ(matrix[7].toDouble(), posture)

                val extracted = CompassFusionMath.extractMagneticAzimuthFromMatrix(matrix, posture)
                val delta = AngleMath.angularDistanceDegrees(heading, extracted)
                assertTrue(
                    "Heading=$heading° pitch=$pitch° posture=$posture produced $extracted° (delta=$delta°)",
                    delta < 0.5,
                )
            }
        }
    }

    @Test
    fun `displayAxisRemapFor handles all 4 surface rotations`() {
        assertEquals(SensorManager.AXIS_X to SensorManager.AXIS_Y, CompassFusionMath.displayAxisRemapFor(Surface.ROTATION_0))
        assertEquals(SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X, CompassFusionMath.displayAxisRemapFor(Surface.ROTATION_90))
        assertEquals(SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y, CompassFusionMath.displayAxisRemapFor(Surface.ROTATION_180))
        assertEquals(SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X, CompassFusionMath.displayAxisRemapFor(Surface.ROTATION_270))
    }

    @Test
    fun `computeTrueHeading applies declination, formats 359_98 as 0_0, and smooths across 0-360 seam`() {
        val initial = CompassFusionMath.computeTrueHeading(
            magneticAzimuthDeg = 358.0,
            declinationDeg = 5.0,
            previousSmoothedDeg = null,
        )
        assertEquals(3.0, initial, 1e-6)

        val smoothed = CompassFusionMath.computeTrueHeading(
            magneticAzimuthDeg = 348.0,
            declinationDeg = 5.0,
            previousSmoothedDeg = initial,
            emaAlpha = 0.25,
        )
        assertEquals(0.5, smoothed, 1e-6)
        assertTrue(smoothed in 0.0..<360.0)

        assertEquals("0.0°", CompassFusionMath.formatHeadingTenths(359.98))
        assertEquals("0.0°", CompassFusionMath.formatHeadingTenths(-0.02))
    }

    @Test
    fun `mapAccuracy flags low and unreliable magnetometer states as CALIBRATION_NEEDED`() {
        assertEquals(CompassAccuracy.HIGH, CompassFusionMath.mapAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_HIGH))
        assertEquals(CompassAccuracy.MEDIUM, CompassFusionMath.mapAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM))
        assertEquals(CompassAccuracy.CALIBRATION_NEEDED, CompassFusionMath.mapAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_LOW))
        assertEquals(CompassAccuracy.CALIBRATION_NEEDED, CompassFusionMath.mapAccuracy(SensorManager.SENSOR_STATUS_UNRELIABLE))
    }

    @Test
    fun `DeclinationCache avoids recomputing within 10 miles and recomputes past 10 miles`() {
        var calls = 0
        val cache = DeclinationCache { lat, _, _, _ ->
            calls++
            if (lat > 41.7) 2.5 else 1.8
        }

        val anchor = GeoPoint(41.5868, -93.6250, 950.0)
        assertEquals(1.8, cache.getDeclinationDegrees(anchor, 1_795_000_000_000L), 1e-6)
        assertEquals(1, calls)

        val nearby = GeoPoint(41.6368, -93.6250, 950.0)
        assertEquals(1.8, cache.getDeclinationDegrees(nearby, 1_795_000_005_000L), 1e-6)
        assertEquals(1, calls)

        val shifted = GeoPoint(41.7900, -93.6250, 950.0)
        assertEquals(2.5, cache.getDeclinationDegrees(shifted, 1_795_000_010_000L), 1e-6)
        assertEquals(2, calls)
    }

    @Test
    fun `classifyWindAlignment enforces 5 degree hysteresis`() {
        val initial = CompassFusionMath.classifyWindAlignment(0.0, 44.0, null)
        assertEquals(WindAlignmentBadge.WIND_IN_FACE, initial)
        assertEquals(WindAlignmentBadge.WIND_IN_FACE, CompassFusionMath.classifyWindAlignment(0.0, 49.0, initial))
        assertEquals(WindAlignmentBadge.CROSSWIND, CompassFusionMath.classifyWindAlignment(0.0, 51.0, initial))
    }

    @Test
    fun `Barometer trend is strictly MSL-to-MSL, reads 0_00 across 80 ft climb, and resets on altitude lock change`() {
        val evaluator = SeaLevelPressureTrendEvaluator()
        val t0 = 1_795_000_000_000L

        // Before GPS altitude lock -> uncorrected station reading does not establish an MSL trend baseline
        assertNull(evaluator.record(t0, seaLevelPressureInHg = 29.10, isSeaLevelCorrected = false))

        // Constant sea-level pressure = 1018.0 hPa while hunter climbs 80 ft (900 ft -> 980 ft)
        val constantMslHpa = 1018.0
        val station900FtHpa = constantMslHpa * (1.0 - GeoUnits.feetToMeters(900.0) / 44_330.0).pow(5.255)
        val station980FtHpa = constantMslHpa * (1.0 - GeoUnits.feetToMeters(980.0) / 44_330.0).pow(5.255)

        val (msl1, corr1) = BarometerMath.stationToSeaLevelInHg(station900FtHpa, 900.0)
        val (msl2, corr2) = BarometerMath.stationToSeaLevelInHg(station980FtHpa, 980.0)
        assertTrue(corr1 && corr2)

        // First locked MSL reading resets prior raw baseline and returns null until >= 15 min passes
        assertNull(evaluator.record(t0 + 60_000L, msl1, isSeaLevelCorrected = true))

        // 30 minutes later at 980 ft under identical sea-level pressure -> 0.00 inHg trend
        val trend = evaluator.record(t0 + 31L * 60_000L, msl2, isSeaLevelCorrected = true)
        assertEquals(0.00, trend!!, 0.002)

        // Losing GPS lock resets trend cleanly
        assertNull(evaluator.record(t0 + 32L * 60_000L, 29.05, isSeaLevelCorrected = false))
        assertNull(evaluator.record(t0 + 33L * 60_000L, msl2, isSeaLevelCorrected = true))
    }

    /**
     * Constructs a 3x3 device-to-world (East, North, Up) rotation matrix for a phone facing [headingDeg]
     * and pitched skyward by [skywardPitchDeg] (0° = flat screen up, 90° = vertical portrait facing forward).
     */
    private fun buildWorldRotationMatrix(headingDeg: Double, skywardPitchDeg: Double): FloatArray {
        val psi = Math.toRadians(headingDeg)
        val theta = Math.toRadians(skywardPitchDeg)

        val sinPsi = sin(psi)
        val cosPsi = cos(psi)
        val sinTheta = sin(theta)
        val cosTheta = cos(theta)

        // Horizontal forward unit vector (azimuth = headingDeg): (sinPsi, cosPsi, 0)
        // Right edge unit vector (+X): (cosPsi, -sinPsi, 0)
        // Top edge unit vector (+Y) tilted skyward by theta: (sinPsi*cosTheta, cosPsi*cosTheta, sinTheta)
        // Out-of-screen unit vector (+Z = +X cross +Y): (-sinPsi*sinTheta, -cosPsi*sinTheta, cosTheta)
        return floatArrayOf(
            cosPsi.toFloat(), (sinPsi * cosTheta).toFloat(), (-sinPsi * sinTheta).toFloat(),
            (-sinPsi).toFloat(), (cosPsi * cosTheta).toFloat(), (-cosPsi * sinTheta).toFloat(),
            0f, sinTheta.toFloat(), cosTheta.toFloat(),
        )
    }
}

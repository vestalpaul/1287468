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
    fun `real Android SensorManager quaternion and gravity-magnetic matrices maintain under 0_5 deg continuity across posture switch for headings 0, 90, 118, 215, 330`() {
        val targetHeadings = listOf(0.0, 90.0, 118.0, 215.0, 330.0)
        val pitchAngles = listOf(0.0, 30.0, 50.0, 55.0, 65.0, 85.0, 100.0)

        for (heading in targetHeadings) {
            var posture = CompassPosture.FLAT_MAP
            for (pitch in pitchAngles) {
                val rotVec = buildQuaternionRotationVector(headingDeg = heading, skywardPitchDeg = pitch)
                val rawMatrix = FloatArray(9)
                val remappedMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rawMatrix, rotVec)
                val (axisX, axisY) = CompassFusionMath.displayAxisRemapFor(Surface.ROTATION_0)
                SensorManager.remapCoordinateSystem(rawMatrix, axisX, axisY, remappedMatrix)

                posture = CompassFusionMath.resolvePostureFromSkywardZ(
                    r7 = remappedMatrix[7].toDouble(),
                    previousPosture = posture,
                    r8 = remappedMatrix[8].toDouble(),
                )
                val extracted = CompassFusionMath.extractMagneticAzimuthFromMatrix(remappedMatrix, posture)
                val delta = AngleMath.angularDistanceDegrees(heading, extracted)
                assertTrue(
                    "Quaternion heading=$heading° pitch=$pitch° posture=$posture produced $extracted° (delta=$delta°)",
                    delta < 0.5,
                )

                // Cross-check against physical gravity + magnetic vectors via SensorManager.getRotationMatrix
                if (pitch <= 90.0) {
                    val gmMatrix = buildPhysicalGravityMagneticMatrix(headingDeg = heading, skywardPitchDeg = pitch)
                    val gmExtracted = CompassFusionMath.extractMagneticAzimuthFromMatrix(gmMatrix, posture)
                    val gmDelta = AngleMath.angularDistanceDegrees(heading, gmExtracted)
                    assertTrue(
                        "Gravity/Mag heading=$heading° pitch=$pitch° posture=$posture produced $gmExtracted° (delta=$gmDelta°)",
                        gmDelta < 0.5,
                    )
                }
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
        // Compute station pressures via independent ICAO Standard Atmosphere (ISA) thermodynamic equation
        val constantMslHpa = 1018.0
        val station900FtHpa = isaPressureAtAltitudeFeet(seaLevelHpa = constantMslHpa, altitudeFeet = 900.0)
        val station980FtHpa = isaPressureAtAltitudeFeet(seaLevelHpa = constantMslHpa, altitudeFeet = 980.0)

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

    @Test
    fun `BarometerMath reduces published ICAO Standard Atmosphere station pressures back to 1013_25 hPa`() {
        // Published ICAO Standard Atmosphere table entries (P0 = 1013.25 hPa)
        val tableCases = listOf(
            Triple(1_000.0, 977.17, 0.2),
            Triple(5_000.0, 843.07, 0.3),
            Triple(10_000.0, 696.82, 0.6),
        )
        for ((altFeet, stationHpa, toleranceHpa) in tableCases) {
            val mslHpa = BarometerMath.stationToSeaLevelHpa(stationHpa = stationHpa, altitudeFeet = altFeet)
            assertEquals(
                "ISA sea-level reduction mismatch at $altFeet ft",
                1013.25,
                mslHpa,
                toleranceHpa,
            )
        }
    }

    @Test
    fun `landscape display rotations ROTATION_90 and ROTATION_270 recover exact heading under quaternion tilt`() {
        val targetHeadings = listOf(0.0, 72.5, 135.0, 240.0, 355.0)
        val pitchAngles = listOf(0.0, 40.0, 65.0, 85.0)
        val landscapeRotations = listOf(
            Surface.ROTATION_90 to 90.0,
            Surface.ROTATION_270 to -90.0,
        )

        for ((displayRotation, rollZDeg) in landscapeRotations) {
            val (axisX, axisY) = CompassFusionMath.displayAxisRemapFor(displayRotation)
            for (heading in targetHeadings) {
                var posture = CompassPosture.FLAT_MAP
                for (pitch in pitchAngles) {
                    val rotVec = buildQuaternionRotationVector(
                        headingDeg = heading,
                        skywardPitchDeg = pitch,
                        displayRollZDeg = rollZDeg,
                    )
                    val rawMatrix = FloatArray(9)
                    val remappedMatrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rawMatrix, rotVec)
                    SensorManager.remapCoordinateSystem(rawMatrix, axisX, axisY, remappedMatrix)

                    posture = CompassFusionMath.resolvePostureFromSkywardZ(
                        r7 = remappedMatrix[7].toDouble(),
                        previousPosture = posture,
                        r8 = remappedMatrix[8].toDouble(),
                    )
                    val extracted = CompassFusionMath.extractMagneticAzimuthFromMatrix(remappedMatrix, posture)
                    val delta = AngleMath.angularDistanceDegrees(heading, extracted)
                    assertTrue(
                        "rot=$displayRotation heading=$heading° pitch=$pitch° posture=$posture produced $extracted° (delta=$delta°)",
                        delta < 0.5,
                    )
                }
            }
        }
    }

    /**
     * Independent ICAO Standard Atmosphere (ISA) barometric pressure formula from first-principles
     * thermodynamic constants (T0 = 288.15 K, L = 0.0065 K/m, g0 = 9.80665 m/s^2, M = 0.0289644 kg/mol,
     * R = 8.3144598 J/(mol*K)).
     */
    private fun isaPressureAtAltitudeFeet(seaLevelHpa: Double, altitudeFeet: Double): Double {
        val hMeters = altitudeFeet * 0.3048
        val t0 = 288.15
        val lapseRate = 0.0065
        val g0 = 9.80665
        val molarMass = 0.0289644
        val gasConstant = 8.3144598
        val exponent = (g0 * molarMass) / (gasConstant * lapseRate)
        return seaLevelHpa * (1.0 - (lapseRate * hMeters) / t0).pow(exponent)
    }

    /**
     * Builds an Android `TYPE_ROTATION_VECTOR` `[qx, qy, qz, qw]` corresponding to rotating the screen
     * clockwise to [headingDeg] around World Up (+Z), pitching the screen top edge skyward by
     * [skywardPitchDeg] around screen +X, and applying optional [displayRollZDeg] around screen +Z
     * (`q = q_yaw(-heading) * q_pitch(+skywardPitch) * q_rollZ(displayRollZDeg)`).
     */
    private fun buildQuaternionRotationVector(
        headingDeg: Double,
        skywardPitchDeg: Double,
        displayRollZDeg: Double = 0.0,
    ): FloatArray {
        val halfYaw = Math.toRadians(-headingDeg) / 2.0
        val halfPitch = Math.toRadians(skywardPitchDeg) / 2.0
        val halfRoll = Math.toRadians(displayRollZDeg) / 2.0

        // q_yaw = (0, 0, sin(halfYaw), cos(halfYaw))
        val yZ = sin(halfYaw)
        val yW = cos(halfYaw)
        // q_pitch = (sin(halfPitch), 0, 0, cos(halfPitch))
        val pX = sin(halfPitch)
        val pW = cos(halfPitch)

        // q_yp = q_yaw * q_pitch
        val ypX = yW * pX
        val ypY = yZ * pX
        val ypZ = yZ * pW
        val ypW = yW * pW

        // q_roll = (0, 0, sin(halfRoll), cos(halfRoll))
        val rZ = sin(halfRoll)
        val rW = cos(halfRoll)

        // q = q_yp * q_roll
        val x = ypW * 0.0 + ypX * rW + ypY * rZ - ypZ * 0.0
        val y = ypW * 0.0 - ypX * rZ + ypY * rW + ypZ * 0.0
        val z = ypW * rZ + ypX * 0.0 - ypY * 0.0 + ypZ * rW
        val w = ypW * rW - ypX * 0.0 - ypY * 0.0 - ypZ * rZ
        return floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat(), w.toFloat())
    }

    /**
     * Derives the 3x3 rotation matrix directly via Android's `SensorManager.getRotationMatrix` from
     * rotated gravity and magnetic North vectors in device coordinates.
     */
    private fun buildPhysicalGravityMagneticMatrix(headingDeg: Double, skywardPitchDeg: Double): FloatArray {
        val psi = Math.toRadians(headingDeg)
        val theta = Math.toRadians(skywardPitchDeg)

        val sinPsi = sin(psi)
        val cosPsi = cos(psi)
        val sinTheta = sin(theta)
        val cosTheta = cos(theta)

        // World vector v=(vx, vy, vz) projected onto device basis (+X, +Y, +Z):
        // +X = (cosPsi, -sinPsi, 0)
        // +Y = (sinPsi*cosTheta, cosPsi*cosTheta, sinTheta)
        // +Z = (-sinPsi*sinTheta, -cosPsi*sinTheta, cosTheta)
        // Gravity (accelerometer measures +Up = (0, 0, 9.81)) in device frame:
        val g = 9.81
        val gravity = floatArrayOf(
            0f,
            (g * sinTheta).toFloat(),
            (g * cosTheta).toFloat(),
        )
        // Geomagnetic North (0, 50 uT, 0) in device frame:
        val mag = 50.0
        val geomagnetic = floatArrayOf(
            (mag * -sinPsi).toFloat(),
            (mag * cosPsi * cosTheta).toFloat(),
            (mag * -cosPsi * sinTheta).toFloat(),
        )
        val matrix = FloatArray(9)
        assertTrue(SensorManager.getRotationMatrix(matrix, null, gravity, geomagnetic))
        return matrix
    }
}

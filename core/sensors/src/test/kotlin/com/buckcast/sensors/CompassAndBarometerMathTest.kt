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
    fun `unified 3-state posture transitions enforce hysteresis across FLAT_MAP, UPRIGHT_AIMING, and AIM_DOWN`() {
        var posture = CompassPosture.FLAT_MAP

        // Casual flat palm tilt +15° (r7=sin(15)=0.259, r8=cos(15)=0.966 > 0.866) stays FLAT_MAP
        posture = CompassFusionMath.resolvePostureFromSkywardZ(
            r7 = sin(Math.toRadians(15.0)),
            previousPosture = posture,
            r8 = cos(Math.toRadians(15.0)),
        )
        assertEquals(CompassPosture.FLAT_MAP, posture)

        // Back-camera steep stand aim -59° (skyward=31° -> r7=sin(31)=0.515 > 0.25, r8=cos(31)=0.857 < 0.866) enters UPRIGHT_AIMING
        posture = CompassFusionMath.resolvePostureFromSkywardZ(
            r7 = sin(Math.toRadians(31.0)),
            previousPosture = posture,
            r8 = cos(Math.toRadians(31.0)),
        )
        assertEquals(CompassPosture.UPRIGHT_AIMING, posture)

        // Inside UPRIGHT_AIMING exit hysteresis band (skyward=25° -> r8=cos(25)=0.906 <= 0.927, r7=0.423 >= 0.15) holds UPRIGHT_AIMING
        posture = CompassFusionMath.resolvePostureFromSkywardZ(
            r7 = sin(Math.toRadians(25.0)),
            previousPosture = posture,
            r8 = cos(Math.toRadians(25.0)),
        )
        assertEquals(CompassPosture.UPRIGHT_AIMING, posture)

        // Flattened back to skyward=18° (r8=cos(18)=0.951 > 0.927) returns to FLAT_MAP
        posture = CompassFusionMath.resolvePostureFromSkywardZ(
            r7 = sin(Math.toRadians(18.0)),
            previousPosture = posture,
            r8 = cos(Math.toRadians(18.0)),
        )
        assertEquals(CompassPosture.FLAT_MAP, posture)

        // Nose-down -26° (r7 = sin(-26) = -0.438 < -0.423) enters AIM_DOWN
        posture = CompassFusionMath.resolvePostureFromSkywardZ(
            r7 = sin(Math.toRadians(-26.0)),
            previousPosture = posture,
            r8 = cos(Math.toRadians(-26.0)),
        )
        assertEquals(CompassPosture.AIM_DOWN, posture)

        // Inside AIM_DOWN exit hysteresis band (-20° -> r7 = -0.342 <= -0.309) holds AIM_DOWN
        posture = CompassFusionMath.resolvePostureFromSkywardZ(
            r7 = sin(Math.toRadians(-20.0)),
            previousPosture = posture,
            r8 = cos(Math.toRadians(-20.0)),
        )
        assertEquals(CompassPosture.AIM_DOWN, posture)

        // Raised above -17° (r7 = -0.292 > -0.309) returns to FLAT_MAP
        posture = CompassFusionMath.resolvePostureFromSkywardZ(
            r7 = sin(Math.toRadians(-17.0)),
            previousPosture = posture,
            r8 = cos(Math.toRadians(-17.0)),
        )
        assertEquals(CompassPosture.FLAT_MAP, posture)
    }

    @Test
    fun `flat palm, back-camera stand aim (-55 to +20), and nose-down stand aim (-30 to -70) hold signed pitch and under 0_5 deg azimuth across headings`() {
        val targetHeadings = listOf(0.0, 90.0, 118.0, 215.0, 330.0)
        val backCameraAimAngles = listOf(-55.0, -50.0, -30.0, 0.0, 20.0)
        val noseDownAimAngles = listOf(-30.0, -50.0, -70.0)

        for (heading in targetHeadings) {
            // 1. Flat palm (0..15 deg casual tilt) stays FLAT_MAP with |pitchDeg| <= 16 deg
            for (casualTilt in listOf(-10.0, 0.0, 15.0)) {
                val remapped = quaternionToRemappedMatrix(headingDeg = heading, skywardPitchDeg = casualTilt)
                val posture = CompassFusionMath.resolvePostureFromSkywardZ(
                    r7 = remapped[7].toDouble(),
                    previousPosture = CompassPosture.FLAT_MAP,
                    r8 = remapped[8].toDouble(),
                )
                assertEquals(CompassPosture.FLAT_MAP, posture)
                val pitch = CompassFusionMath.extractPitchDeg(remapped, posture)
                assertTrue("Casual flat tilt pitch=$pitch expected within ±16°", kotlin.math.abs(pitch) <= 16.0)
                val extracted = CompassFusionMath.extractMagneticAzimuthFromMatrix(remapped, posture)
                assertTrue(AngleMath.angularDistanceDegrees(heading, extracted) < 0.5)
            }

            // 2. Back-camera treestand aim (-55, -50, -30, 0, +20) -> UPRIGHT_AIMING, negative depression pitch, <0.5 deg error
            for (aimDeg in backCameraAimAngles) {
                val remapped = quaternionToRemappedMatrix(headingDeg = heading, skywardPitchDeg = 90.0 + aimDeg)
                val posture = CompassFusionMath.resolvePostureFromSkywardZ(
                    r7 = remapped[7].toDouble(),
                    previousPosture = CompassPosture.FLAT_MAP,
                    r8 = remapped[8].toDouble(),
                )
                assertEquals("Expected UPRIGHT_AIMING at back-camera aim=$aimDeg°", CompassPosture.UPRIGHT_AIMING, posture)
                val pitch = CompassFusionMath.extractPitchDeg(remapped, posture)
                assertEquals("Pitch mismatch at back-camera aim=$aimDeg°", aimDeg, pitch, 1.0)
                val extracted = CompassFusionMath.extractMagneticAzimuthFromMatrix(remapped, posture)
                val delta = AngleMath.angularDistanceDegrees(heading, extracted)
                assertTrue("Back-camera heading=$heading° aim=$aimDeg° -> $extracted° (delta=$delta°)", delta < 0.5)
            }

            // 3. Nose-down top-edge treestand aim (-30, -50, -70) -> AIM_DOWN, negative pitch, <0.5 deg error (zero 180 flip)
            for (aimDeg in noseDownAimAngles) {
                val remapped = quaternionToRemappedMatrix(headingDeg = heading, skywardPitchDeg = aimDeg)
                val posture = CompassFusionMath.resolvePostureFromSkywardZ(
                    r7 = remapped[7].toDouble(),
                    previousPosture = CompassPosture.FLAT_MAP,
                    r8 = remapped[8].toDouble(),
                )
                assertEquals("Expected AIM_DOWN at nose-down aim=$aimDeg°", CompassPosture.AIM_DOWN, posture)
                val pitch = CompassFusionMath.extractPitchDeg(remapped, posture)
                assertEquals("Pitch mismatch at nose-down aim=$aimDeg°", aimDeg, pitch, 1.0)
                val extracted = CompassFusionMath.extractMagneticAzimuthFromMatrix(remapped, posture)
                val delta = AngleMath.angularDistanceDegrees(heading, extracted)
                assertTrue("Nose-down heading=$heading° aim=$aimDeg° -> $extracted° (delta=$delta°)", delta < 0.5)
            }
        }
    }

    @Test
    fun `smooth sweep from flat to nose-down to flat to back-camera produces zero heading spikes and gimbal guard holds azimuth near nadir`() {
        val targetHeadings = listOf(0.0, 90.0, 118.0, 215.0, 330.0)
        // Sequence: flat(0) -> nose-down(-35, -50) -> flat(-10, 0, +10) -> back-camera(-50 [sky=40], -30 [sky=60], 0 [sky=90])
        val skywardSweep = listOf(0.0, -35.0, -50.0, -10.0, 0.0, 10.0, 40.0, 60.0, 90.0)
        val expectedPostures = listOf(
            CompassPosture.FLAT_MAP,
            CompassPosture.AIM_DOWN,
            CompassPosture.AIM_DOWN,
            CompassPosture.FLAT_MAP,
            CompassPosture.FLAT_MAP,
            CompassPosture.FLAT_MAP,
            CompassPosture.UPRIGHT_AIMING,
            CompassPosture.UPRIGHT_AIMING,
            CompassPosture.UPRIGHT_AIMING,
        )

        for (heading in targetHeadings) {
            var posture = CompassPosture.FLAT_MAP
            var lastAzimuth: Double? = null
            skywardSweep.forEachIndexed { idx, skyPitch ->
                val remapped = quaternionToRemappedMatrix(headingDeg = heading, skywardPitchDeg = skyPitch)
                posture = CompassFusionMath.resolvePostureFromSkywardZ(
                    r7 = remapped[7].toDouble(),
                    previousPosture = posture,
                    r8 = remapped[8].toDouble(),
                )
                assertEquals("Sweep step $idx (skyPitch=$skyPitch) heading=$heading", expectedPostures[idx], posture)
                val extracted = CompassFusionMath.extractMagneticAzimuthFromMatrix(remapped, posture, lastAzimuth)
                lastAzimuth = extracted
                val delta = AngleMath.angularDistanceDegrees(heading, extracted)
                assertTrue("Sweep step $idx heading=$heading° got $extracted° (delta=$delta°)", delta < 0.5)
            }
        }

        // Gimbal guard near -88° nadir holds previous azimuth instead of flipping noisily
        val nadirMatrix = quaternionToRemappedMatrix(headingDeg = 215.0, skywardPitchDeg = -88.0)
        val guarded = CompassFusionMath.extractMagneticAzimuthFromMatrix(
            remappedMatrix = nadirMatrix,
            posture = CompassPosture.AIM_DOWN,
            previousAzimuthDeg = 215.0,
        )
        assertEquals(215.0, guarded, 1e-6)
        assertEquals("+3°", CompassFusionMath.formatSignedPitchDeg(3.2))
        assertEquals("0°", CompassFusionMath.formatSignedPitchDeg(-0.2))
        assertEquals("-50°", CompassFusionMath.formatSignedPitchDeg(-50.0))
        assertEquals("--°", CompassFusionMath.formatSignedPitchDeg(Double.NaN))
        assertEquals("--°", CompassFusionMath.formatSignedPitchDeg(Double.POSITIVE_INFINITY))
    }

    private fun quaternionToRemappedMatrix(headingDeg: Double, skywardPitchDeg: Double): FloatArray {
        val rotVec = buildQuaternionRotationVector(headingDeg = headingDeg, skywardPitchDeg = skywardPitchDeg)
        val rawMatrix = FloatArray(9)
        val remappedMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rawMatrix, rotVec)
        val (axisX, axisY) = CompassFusionMath.displayAxisRemapFor(Surface.ROTATION_0)
        SensorManager.remapCoordinateSystem(rawMatrix, axisX, axisY, remappedMatrix)
        return remappedMatrix
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
    fun `computeTrueHeading applies declination, manual trim offset clamped to +-15 deg, and smooths across 0-360 seam`() {
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

        // Offset of +25° clamps to +15°, -25° clamps to -15°
        val clampedPlus = CompassFusionMath.computeTrueHeading(
            magneticAzimuthDeg = 355.0,
            declinationDeg = 0.0,
            previousSmoothedDeg = null,
            compassOffsetDeg = 25.0,
        )
        assertEquals(10.0, clampedPlus, 1e-6)

        val clampedMinus = CompassFusionMath.computeTrueHeading(
            magneticAzimuthDeg = 5.0,
            declinationDeg = 0.0,
            previousSmoothedDeg = null,
            compassOffsetDeg = -25.0,
        )
        assertEquals(350.0, clampedMinus, 1e-6)

        assertEquals("0.0°", CompassFusionMath.formatHeadingTenths(359.98))
        assertEquals("0.0°", CompassFusionMath.formatHeadingTenths(-0.02))
    }

    @Test
    fun `mapAccuracy maps all 4 Android sensor statuses and flags LOW and UNRELIABLE as needsCalibration`() {
        assertEquals(CompassAccuracy.HIGH, CompassFusionMath.mapAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_HIGH))
        assertFalse(CompassAccuracy.HIGH.needsCalibration)
        assertEquals(CompassAccuracy.MEDIUM, CompassFusionMath.mapAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM))
        assertFalse(CompassAccuracy.MEDIUM.needsCalibration)
        assertEquals(CompassAccuracy.LOW, CompassFusionMath.mapAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_LOW))
        assertTrue(CompassAccuracy.LOW.needsCalibration)
        assertEquals(CompassAccuracy.UNRELIABLE, CompassFusionMath.mapAccuracy(SensorManager.SENSOR_STATUS_UNRELIABLE))
        assertTrue(CompassAccuracy.UNRELIABLE.needsCalibration)
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
                    if (pitch == 40.0) {
                        assertEquals(CompassPosture.UPRIGHT_AIMING, posture)
                        assertEquals(-50.0, CompassFusionMath.extractPitchDeg(remappedMatrix, posture), 1.0)
                    }
                    val extracted = CompassFusionMath.extractMagneticAzimuthFromMatrix(remappedMatrix, posture)
                    val delta = AngleMath.angularDistanceDegrees(heading, extracted)
                    assertTrue(
                        "rot=$displayRotation heading=$heading° pitch=$pitch° posture=$posture produced $extracted° (delta=$delta°)",
                        delta < 0.5,
                    )
                }

                var noseDownPosture = CompassPosture.FLAT_MAP
                for (noseDownPitch in listOf(-35.0, -50.0)) {
                    val rotVec = buildQuaternionRotationVector(
                        headingDeg = heading,
                        skywardPitchDeg = noseDownPitch,
                        displayRollZDeg = rollZDeg,
                    )
                    val rawMatrix = FloatArray(9)
                    val remappedMatrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rawMatrix, rotVec)
                    SensorManager.remapCoordinateSystem(rawMatrix, axisX, axisY, remappedMatrix)

                    noseDownPosture = CompassFusionMath.resolvePostureFromSkywardZ(
                        r7 = remappedMatrix[7].toDouble(),
                        previousPosture = noseDownPosture,
                        r8 = remappedMatrix[8].toDouble(),
                    )
                    assertEquals(CompassPosture.AIM_DOWN, noseDownPosture)
                    assertEquals(noseDownPitch, CompassFusionMath.extractPitchDeg(remappedMatrix, noseDownPosture), 1.0)
                    val extracted = CompassFusionMath.extractMagneticAzimuthFromMatrix(remappedMatrix, noseDownPosture)
                    val delta = AngleMath.angularDistanceDegrees(heading, extracted)
                    assertTrue(
                        "rot=$displayRotation nose-down heading=$heading° pitch=$noseDownPitch° produced $extracted° (delta=$delta°)",
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

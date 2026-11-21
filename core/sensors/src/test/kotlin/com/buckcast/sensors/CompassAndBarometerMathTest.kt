package com.buckcast.sensors

import android.hardware.SensorManager
import android.view.Surface
import com.buckcast.domain.math.GeoPoint
import com.buckcast.domain.wind.WindAlignmentBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CompassAndBarometerMathTest {

    @Test
    fun `dual-posture tilt switches at 55 deg and holds 5 deg hysteresis`() {
        var posture = CompassPosture.FLAT_MAP

        // 55° stays FLAT_MAP until > 55°
        posture = CompassFusionMath.resolvePosture(55.0, posture)
        assertEquals(CompassPosture.FLAT_MAP, posture)

        // > 55° enters UPRIGHT_AIMING
        posture = CompassFusionMath.resolvePosture(-56.0, posture)
        assertEquals(CompassPosture.UPRIGHT_AIMING, posture)

        // 52° inside hysteresis band stays UPRIGHT_AIMING
        posture = CompassFusionMath.resolvePosture(-52.0, posture)
        assertEquals(CompassPosture.UPRIGHT_AIMING, posture)

        // < 50° drops back to FLAT_MAP
        posture = CompassFusionMath.resolvePosture(-49.0, posture)
        assertEquals(CompassPosture.FLAT_MAP, posture)
    }

    @Test
    fun `axisRemapFor projects +Y flat and -Z camera upright across all display rotations`() {
        assertEquals(
            SensorManager.AXIS_X to SensorManager.AXIS_Y,
            CompassFusionMath.axisRemapFor(CompassPosture.FLAT_MAP, Surface.ROTATION_0),
        )
        assertEquals(
            SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X,
            CompassFusionMath.axisRemapFor(CompassPosture.FLAT_MAP, Surface.ROTATION_90),
        )
        assertEquals(
            SensorManager.AXIS_X to SensorManager.AXIS_Z,
            CompassFusionMath.axisRemapFor(CompassPosture.UPRIGHT_AIMING, Surface.ROTATION_0),
        )
        assertEquals(
            SensorManager.AXIS_Z to SensorManager.AXIS_MINUS_X,
            CompassFusionMath.axisRemapFor(CompassPosture.UPRIGHT_AIMING, Surface.ROTATION_90),
        )
        assertEquals(
            SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Z,
            CompassFusionMath.axisRemapFor(CompassPosture.UPRIGHT_AIMING, Surface.ROTATION_180),
        )
        assertEquals(
            SensorManager.AXIS_MINUS_Z to SensorManager.AXIS_X,
            CompassFusionMath.axisRemapFor(CompassPosture.UPRIGHT_AIMING, Surface.ROTATION_270),
        )
    }

    @Test
    fun `computeTrueHeading applies declination, normalizes to 0-360, and smooths across 0-360 seam`() {
        // Magnetic 358° + 5° East declination = True 3°
        val initial = CompassFusionMath.computeTrueHeading(
            magneticAzimuthDeg = 358.0,
            declinationDeg = 5.0,
            previousSmoothedDeg = null,
        )
        assertEquals(3.0, initial, 1e-6)

        // Smooth jump from 3° toward 353° (shortest arc = -10°, alpha = 0.25 -> 0.5°)
        val smoothed = CompassFusionMath.computeTrueHeading(
            magneticAzimuthDeg = 348.0,
            declinationDeg = 5.0,
            previousSmoothedDeg = initial,
            emaAlpha = 0.25,
        )
        assertEquals(0.5, smoothed, 1e-6)
        assertTrue(smoothed in 0.0..<360.0)
    }

    @Test
    fun `mapAccuracy flags low and unreliable magnetometer states as CALIBRATION_NEEDED`() {
        assertEquals(CompassAccuracy.HIGH, CompassFusionMath.mapAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_HIGH))
        assertEquals(CompassAccuracy.MEDIUM, CompassFusionMath.mapAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM))
        assertEquals(
            CompassAccuracy.CALIBRATION_NEEDED,
            CompassFusionMath.mapAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_LOW),
        )
        assertEquals(
            CompassAccuracy.CALIBRATION_NEEDED,
            CompassFusionMath.mapAccuracy(SensorManager.SENSOR_STATUS_UNRELIABLE),
        )
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

        // ~3.5 miles north -> cached, zero recompute
        val nearby = GeoPoint(41.6368, -93.6250, 950.0)
        assertEquals(1.8, cache.getDeclinationDegrees(nearby, 1_795_000_005_000L), 1e-6)
        assertEquals(1, calls)

        // ~14 miles north -> recomputes declination
        val shifted = GeoPoint(41.7900, -93.6250, 950.0)
        assertEquals(2.5, cache.getDeclinationDegrees(shifted, 1_795_000_010_000L), 1e-6)
        assertEquals(2, calls)
    }

    @Test
    fun `classifyWindAlignment enforces 5 degree hysteresis`() {
        val initial = CompassFusionMath.classifyWindAlignment(0.0, 44.0, null)
        assertEquals(WindAlignmentBadge.WIND_IN_FACE, initial)
        assertEquals(
            WindAlignmentBadge.WIND_IN_FACE,
            CompassFusionMath.classifyWindAlignment(0.0, 49.0, initial),
        )
        assertEquals(
            WindAlignmentBadge.CROSSWIND,
            CompassFusionMath.classifyWindAlignment(0.0, 51.0, initial),
        )
    }

    @Test
    fun `BarometerMath reduces station hPa to sea-level inHg and LocalPressureTrendTracker computes 3h delta`() {
        // 984.0 hPa station pressure at 950 ft elevation (~290 m) -> ~1018.4 hPa (~30.07 inHg) sea level
        val (seaLevelInHg, corrected) = BarometerMath.stationToSeaLevelInHg(984.0, 950.0)
        assertTrue(corrected)
        assertEquals(30.07, seaLevelInHg, 0.03)

        val (rawOnlyInHg, rawCorrected) = BarometerMath.stationToSeaLevelInHg(984.0, null)
        assertFalse(rawCorrected)
        assertEquals(29.06, rawOnlyInHg, 0.02)

        val tracker = LocalPressureTrendTracker()
        val t0 = 1_795_000_000_000L
        assertNull(tracker.recordAndComputeTrend(t0, 30.00))
        // After 2 hours, rising +0.06 inHg
        val trend = tracker.recordAndComputeTrend(t0 + 2L * 3600L * 1000L, 30.06)
        assertEquals(0.06, trend!!, 1e-6)
    }
}

package com.buckcast.sensors

import android.hardware.GeomagneticField
import android.hardware.SensorManager
import android.view.Surface
import com.buckcast.domain.math.AngleMath
import com.buckcast.domain.math.GeoMath
import com.buckcast.domain.math.GeoPoint
import com.buckcast.domain.math.GeoUnits
import com.buckcast.domain.wind.WindAlignmentBadge
import com.buckcast.domain.wind.WindAlignmentClassifier
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sin

enum class CompassPosture {
    FLAT_MAP,
    UPRIGHT_AIMING,
}

enum class CompassAccuracy {
    HIGH,
    MEDIUM,
    CALIBRATION_NEEDED,
}

enum class CompassSensorSource {
    ROTATION_VECTOR,
    GEOMAGNETIC_ROTATION_VECTOR,
    UNAVAILABLE,
}

data class CompassReading(
    val trueHeadingDeg: Double,
    val magneticHeadingDeg: Double,
    val declinationDeg: Double,
    val pitchDeg: Double,
    val posture: CompassPosture,
    val accuracy: CompassAccuracy,
    val sensorSource: CompassSensorSource,
    val isSensorAvailable: Boolean,
    val timestampEpochMs: Long,
)

/**
 * Pure sensor-fusion math using direct 3x3 display-remapped rotation-matrix column projection,
 * skyward-only posture hysteresis (`R[7] > sin(55°)`), circular EMA smoothing, `[0.0, 360.0)`
 * normalization, safe tenths rounding, and 10-mile magnetic declination caching.
 */
object CompassFusionMath {

    const val UPRIGHT_ENTER_PITCH_DEG = 55.0
    const val UPRIGHT_EXIT_PITCH_DEG = 50.0
    const val DEFAULT_EMA_ALPHA = 0.20

    private val UPRIGHT_ENTER_SIN = sin(AngleMath.toRadians(UPRIGHT_ENTER_PITCH_DEG))
    private val UPRIGHT_EXIT_SIN = sin(AngleMath.toRadians(UPRIGHT_EXIT_PITCH_DEG))

    /**
     * Resolves device posture strictly from skyward tilt component `R[7]` (world Z of screen `+Y` edge)
     * with 5° hysteresis. Nose-down tilts (`r7 < 0`) never enter `UPRIGHT_AIMING`.
     */
    fun resolvePostureFromSkywardZ(
        r7: Double,
        previousPosture: CompassPosture,
        r8: Double = 1.0,
    ): CompassPosture {
        require(r7.isFinite()) { "r7 must be finite: $r7" }
        require(r8.isFinite()) { "r8 must be finite: $r8" }
        return when (previousPosture) {
            CompassPosture.FLAT_MAP ->
                if (r7 > UPRIGHT_ENTER_SIN || (r7 > 0.0 && r8 < 0.0)) {
                    CompassPosture.UPRIGHT_AIMING
                } else {
                    CompassPosture.FLAT_MAP
                }
            CompassPosture.UPRIGHT_AIMING ->
                if ((r8 < 0.0 && r7 >= 0.0) || r7 >= UPRIGHT_EXIT_SIN) {
                    CompassPosture.UPRIGHT_AIMING
                } else {
                    CompassPosture.FLAT_MAP
                }
        }
    }

    /**
     * Extracts signed skyward pitch angle in `[-90, 90]` degrees (`+` top edge skyward, `-` nose down).
     */
    fun extractSkywardPitchDeg(remappedMatrix: FloatArray): Double {
        require(remappedMatrix.size >= 9) { "Expected 3x3 rotation matrix" }
        return AngleMath.toDegrees(asin(remappedMatrix[7].toDouble().coerceIn(-1.0, 1.0)))
    }

    /**
     * Projects the active aiming axis directly from a display-rotation-remapped 3x3 world rotation matrix:
     * - `FLAT_MAP`: projects device `+Y` top edge (`atan2(R[1], R[4])`).
     * - `UPRIGHT_AIMING`: projects rear camera `-Z` axis (`atan2(-R[2], -R[5])`).
     */
    fun extractMagneticAzimuthFromMatrix(
        remappedMatrix: FloatArray,
        posture: CompassPosture,
    ): Double {
        require(remappedMatrix.size >= 9) { "Expected 3x3 rotation matrix" }
        val rad = when (posture) {
            CompassPosture.FLAT_MAP ->
                atan2(remappedMatrix[1].toDouble(), remappedMatrix[4].toDouble())
            CompassPosture.UPRIGHT_AIMING ->
                atan2(-remappedMatrix[2].toDouble(), -remappedMatrix[5].toDouble())
        }
        return AngleMath.normalizeDegrees(AngleMath.toDegrees(rad))
    }

    /**
     * Returns `(axisX, axisY)` for `SensorManager.remapCoordinateSystem` aligning raw sensor coordinates
     * with the current UI screen display rotation.
     */
    fun displayAxisRemapFor(displayRotation: Int): Pair<Int, Int> = when (displayRotation) {
        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
    }

    fun computeTrueHeading(
        magneticAzimuthDeg: Double,
        declinationDeg: Double,
        previousSmoothedDeg: Double?,
        emaAlpha: Double = DEFAULT_EMA_ALPHA,
    ): Double {
        val rawTrue = AngleMath.normalizeDegrees(magneticAzimuthDeg + declinationDeg)
        return if (previousSmoothedDeg == null) {
            rawTrue
        } else {
            AngleMath.circularEmaSmooth(previousSmoothedDeg, rawTrue, emaAlpha)
        }
    }

    fun formatHeadingTenths(degrees: Double): String = AngleMath.formatHeadingTenths(degrees)

    fun mapAccuracy(sensorAccuracy: Int): CompassAccuracy = when (sensorAccuracy) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> CompassAccuracy.HIGH
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> CompassAccuracy.MEDIUM
        else -> CompassAccuracy.CALIBRATION_NEEDED
    }

    fun classifyWindAlignment(
        facingHeadingDeg: Double,
        windFromDeg: Double,
        previousBadge: WindAlignmentBadge?,
    ): WindAlignmentBadge = WindAlignmentClassifier.classify(
        facingHeadingDeg = facingHeadingDeg,
        windFromDeg = windFromDeg,
        previousBadge = previousBadge,
    )
}

/**
 * Caches magnetic declination and recomputes `GeomagneticField` only when the user moves
 * more than 10 miles (`17,600 yards`) from the last declination fix.
 */
class DeclinationCache(
    private val declinationProvider: (lat: Double, lon: Double, altMeters: Double, timeMs: Long) -> Double =
        { lat, lon, altMeters, timeMs ->
            GeomagneticField(lat.toFloat(), lon.toFloat(), altMeters.toFloat(), timeMs).declination.toDouble()
        },
) {
    private var lastAnchor: GeoPoint? = null
    private var cachedDeclinationDeg: Double = 0.0
    var recomputeCount: Int = 0
        private set

    fun getDeclinationDegrees(
        location: GeoPoint?,
        timestampEpochMs: Long,
    ): Double {
        if (location == null) return cachedDeclinationDeg

        val anchor = lastAnchor
        val needsRecompute = anchor == null ||
            GeoMath.distanceYards(anchor, location) > TEN_MILES_IN_YARDS

        if (needsRecompute) {
            val altMeters = GeoUnits.feetToMeters(location.elevationFeet ?: 0.0)
            cachedDeclinationDeg = declinationProvider(
                location.latitude,
                location.longitude,
                altMeters,
                timestampEpochMs,
            )
            lastAnchor = location
            recomputeCount++
        }
        return cachedDeclinationDeg
    }

    companion object {
        const val TEN_MILES_IN_YARDS = 17_600.0
    }
}

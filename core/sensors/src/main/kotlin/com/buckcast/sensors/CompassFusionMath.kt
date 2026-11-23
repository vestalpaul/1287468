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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

enum class CompassPosture {
    FLAT_MAP,
    UPRIGHT_AIMING,
    AIM_DOWN,
}

enum class CompassAccuracy {
    HIGH,
    MEDIUM,
    LOW,
    UNRELIABLE,
    ;

    val needsCalibration: Boolean
        get() = this == LOW || this == UNRELIABLE
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
 * unified 3-state treestand posture hysteresis (`FLAT_MAP`, `UPRIGHT_AIMING`, `AIM_DOWN`),
 * signed pitch (`-` aiming downward, `0°` level, `+` tilted upward), circular EMA smoothing,
 * `[0.0, 360.0)` normalization, manual trim offset (`-15°..+15°`), safe tenths rounding,
 * and 10-mile magnetic declination caching.
 */
object CompassFusionMath {

    const val UPRIGHT_MIN_R7 = 0.25
    const val UPRIGHT_EXIT_MIN_R7 = 0.15
    const val UPRIGHT_ENTER_SCREEN_TILT_DEG = 30.0
    const val UPRIGHT_EXIT_SCREEN_TILT_DEG = 22.0
    const val AIM_DOWN_ENTER_PITCH_DEG = 25.0
    const val AIM_DOWN_EXIT_PITCH_DEG = 18.0
    const val AIM_DOWN_GIMBAL_MIN_HORIZ = 0.08
    const val MIN_COMPASS_OFFSET_DEG = -15.0
    const val MAX_COMPASS_OFFSET_DEG = 15.0
    const val DEFAULT_EMA_ALPHA = 0.20

    private val UPRIGHT_ENTER_MAX_R8 = cos(AngleMath.toRadians(UPRIGHT_ENTER_SCREEN_TILT_DEG)) + 1e-4
    private val UPRIGHT_EXIT_MIN_R8 = cos(AngleMath.toRadians(UPRIGHT_EXIT_SCREEN_TILT_DEG))
    private val AIM_DOWN_ENTER_MAX_R7 = -sin(AngleMath.toRadians(AIM_DOWN_ENTER_PITCH_DEG))
    private val AIM_DOWN_EXIT_MIN_R7 = -sin(AngleMath.toRadians(AIM_DOWN_EXIT_PITCH_DEG))

    /**
     * Resolves device posture from world-from-device rotation matrix components `R[7]`
     * (device `+Y` Up) and `R[8]` (device `+Z` screen normal Up) with hysteresis:
     * - `UPRIGHT_AIMING`: back-camera aiming (`-60°` down from treestand through `0°` level to `+60°` uphill),
     *   entering when `R[7] > 0.25` and `R[8] < cos(30°)` (`0.866`), exiting to `FLAT_MAP` when
     *   `R[8] > cos(22°)` (`0.927`) or `R[7] < 0.15`.
     * - `AIM_DOWN`: nose-down palm grip aiming (`-25°` to `-80°` below horizontal), entering when
     *   `R[7] < -sin(25°)` (`-0.423`), exiting to `FLAT_MAP` when `R[7] > -sin(18°)` (`-0.309`).
     */
    fun resolvePostureFromSkywardZ(
        r7: Double,
        previousPosture: CompassPosture,
        r8: Double = 1.0,
    ): CompassPosture {
        require(r7.isFinite()) { "r7 must be finite: $r7" }
        require(r8.isFinite()) { "r8 must be finite: $r8" }
        return when (previousPosture) {
            CompassPosture.FLAT_MAP -> when {
                r7 > UPRIGHT_MIN_R7 && r8 < UPRIGHT_ENTER_MAX_R8 -> CompassPosture.UPRIGHT_AIMING
                r7 < AIM_DOWN_ENTER_MAX_R7 -> CompassPosture.AIM_DOWN
                else -> CompassPosture.FLAT_MAP
            }
            CompassPosture.UPRIGHT_AIMING -> when {
                r7 < AIM_DOWN_ENTER_MAX_R7 -> CompassPosture.AIM_DOWN
                r8 > UPRIGHT_EXIT_MIN_R8 || r7 < UPRIGHT_EXIT_MIN_R7 -> CompassPosture.FLAT_MAP
                else -> CompassPosture.UPRIGHT_AIMING
            }
            CompassPosture.AIM_DOWN -> when {
                r7 > UPRIGHT_MIN_R7 && r8 < UPRIGHT_ENTER_MAX_R8 -> CompassPosture.UPRIGHT_AIMING
                r7 > AIM_DOWN_EXIT_MIN_R7 -> CompassPosture.FLAT_MAP
                else -> CompassPosture.AIM_DOWN
            }
        }
    }

    /**
     * Extracts signed pitch/elevation angle in `[-90, 90]` degrees following the unified sign convention
     * (**negative** when aiming downward, `0°` level, **positive** when tilted upward):
     * - `FLAT_MAP` / `AIM_DOWN`: `asin(R[7])` (tilting top `+Y` edge downward gives negative degrees).
     * - `UPRIGHT_AIMING`: `asin(-R[8])` (aiming back-camera `-Z` downward gives negative degrees).
     */
    fun extractPitchDeg(
        remappedMatrix: FloatArray,
        posture: CompassPosture,
    ): Double {
        require(remappedMatrix.size >= 9) { "Expected 3x3 rotation matrix" }
        val raw = when (posture) {
            CompassPosture.FLAT_MAP, CompassPosture.AIM_DOWN ->
                remappedMatrix[7].toDouble()
            CompassPosture.UPRIGHT_AIMING ->
                -remappedMatrix[8].toDouble()
        }
        require(raw.isFinite()) { "Matrix pitch component must be finite: $raw" }
        return AngleMath.toDegrees(asin(raw.coerceIn(-1.0, 1.0)))
    }

    /**
     * Formats signed pitch as `+3°`, `0°`, or `-50°`.
     */
    fun formatSignedPitchDeg(pitchDeg: Double): String {
        if (!pitchDeg.isFinite()) return "--°"
        val rounded = pitchDeg.coerceIn(-90.0, 90.0).roundToInt()
        return if (rounded > 0) "+${rounded}°" else "${rounded}°"
    }

    /**
     * Extracts rear-camera elevation/depression angle in `[-90, 90]` degrees (`asin(-R[8])`).
     */
    fun extractAimPitchDeg(remappedMatrix: FloatArray): Double {
        require(remappedMatrix.size >= 9) { "Expected 3x3 rotation matrix" }
        return AngleMath.toDegrees(asin((-remappedMatrix[8].toDouble()).coerceIn(-1.0, 1.0)))
    }

    /**
     * Extracts signed skyward pitch angle of the top `+Y` edge in `[-90, 90]` degrees (`asin(R[7])`).
     */
    fun extractSkywardPitchDeg(remappedMatrix: FloatArray): Double {
        require(remappedMatrix.size >= 9) { "Expected 3x3 rotation matrix" }
        return AngleMath.toDegrees(asin(remappedMatrix[7].toDouble().coerceIn(-1.0, 1.0)))
    }

    /**
     * Projects the active aiming axis directly from a display-rotation-remapped 3x3 world rotation matrix:
     * - `FLAT_MAP`: projects device `+Y` top edge (`atan2(R[1], R[4])`).
     * - `UPRIGHT_AIMING`: projects rear camera `-Z` axis (`atan2(-R[2], -R[5])`).
     * - `AIM_DOWN`: projects device `+Y` top edge (`atan2(R[1], R[4])`), holding [previousAzimuthDeg]
     *   if horizontal `+Y` norm drops below `0.08` near vertical nadir.
     */
    fun extractMagneticAzimuthFromMatrix(
        remappedMatrix: FloatArray,
        posture: CompassPosture,
        previousAzimuthDeg: Double? = null,
    ): Double {
        require(remappedMatrix.size >= 9) { "Expected 3x3 rotation matrix" }
        val rad = when (posture) {
            CompassPosture.FLAT_MAP ->
                atan2(remappedMatrix[1].toDouble(), remappedMatrix[4].toDouble())
            CompassPosture.UPRIGHT_AIMING ->
                atan2(-remappedMatrix[2].toDouble(), -remappedMatrix[5].toDouble())
            CompassPosture.AIM_DOWN -> {
                val horizNorm = hypot(remappedMatrix[1].toDouble(), remappedMatrix[4].toDouble())
                if (horizNorm < AIM_DOWN_GIMBAL_MIN_HORIZ && previousAzimuthDeg != null) {
                    return AngleMath.normalizeDegrees(previousAzimuthDeg)
                }
                atan2(remappedMatrix[1].toDouble(), remappedMatrix[4].toDouble())
            }
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
        compassOffsetDeg: Double = 0.0,
    ): Double {
        val clampedOffset = if (compassOffsetDeg.isFinite()) {
            compassOffsetDeg.coerceIn(MIN_COMPASS_OFFSET_DEG, MAX_COMPASS_OFFSET_DEG)
        } else {
            0.0
        }
        val rawTrue = AngleMath.normalizeDegrees(magneticAzimuthDeg + declinationDeg + clampedOffset)
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
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> CompassAccuracy.LOW
        else -> CompassAccuracy.UNRELIABLE
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

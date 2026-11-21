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
import kotlin.math.abs

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
 * Pure sensor-fusion math enforcing dual-posture remapping, circular EMA smoothing,
 * 5° posture/alignment hysteresis, `[0.0, 360.0)` normalization, and 10-mile declination caching.
 */
object CompassFusionMath {

    const val UPRIGHT_ENTER_PITCH_DEG = 55.0
    const val UPRIGHT_EXIT_PITCH_DEG = 50.0 // 5° posture hysteresis
    const val DEFAULT_EMA_ALPHA = 0.20

    fun resolvePosture(
        pitchDeg: Double,
        previousPosture: CompassPosture,
    ): CompassPosture {
        val absPitch = abs(pitchDeg)
        return when (previousPosture) {
            CompassPosture.FLAT_MAP ->
                if (absPitch > UPRIGHT_ENTER_PITCH_DEG) CompassPosture.UPRIGHT_AIMING else CompassPosture.FLAT_MAP
            CompassPosture.UPRIGHT_AIMING ->
                if (absPitch < UPRIGHT_EXIT_PITCH_DEG) CompassPosture.FLAT_MAP else CompassPosture.UPRIGHT_AIMING
        }
    }

    fun axisRemapFor(
        posture: CompassPosture,
        displayRotation: Int,
    ): Pair<Int, Int> = when (posture) {
        CompassPosture.FLAT_MAP -> when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        CompassPosture.UPRIGHT_AIMING -> when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Z to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Z
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Z to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Z
        }
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

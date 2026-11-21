package com.buckcast.domain.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Circular geometry, bearing normalization, and 2D vector helpers.
 *
 * All bearings and azimuths are measured clockwise from True North (0° = N, 90° = E,
 * 180° = S, 270° = W) and strictly normalized to the half-open interval `[0.0, 360.0)`.
 */
object AngleMath {

    private const val FULL_CIRCLE_DEG = 360.0
    private const val HALF_CIRCLE_DEG = 180.0
    private const val ZERO_VECTOR_EPSILON = 1e-9

    /**
     * Normalizes an angle in degrees into the strict half-open interval `[0.0, 360.0)`.
     * Rejects `NaN` and infinite values, and eliminates negative zero (`-0.0`).
     */
    fun normalizeDegrees(degrees: Double): Double {
        require(degrees.isFinite()) { "Angle must be finite, was: $degrees" }
        var normalized = degrees % FULL_CIRCLE_DEG
        if (normalized < 0.0) {
            normalized += FULL_CIRCLE_DEG
        }
        return if (normalized >= FULL_CIRCLE_DEG || normalized == 0.0) 0.0 else normalized
    }

    /**
     * Returns the opposite bearing (`(degrees + 180) mod 360`) normalized to `[0.0, 360.0)`.
     */
    fun oppositeBearing(degrees: Double): Double = normalizeDegrees(degrees + HALF_CIRCLE_DEG)

    /**
     * Computes the shortest signed angular delta from [fromDeg] to [toDeg] in `(-180.0, 180.0]`.
     * Positive values indicate clockwise rotation; negative values indicate counter-clockwise.
     */
    fun signedDeltaDegrees(fromDeg: Double, toDeg: Double): Double {
        val delta = normalizeDegrees(toDeg) - normalizeDegrees(fromDeg)
        val wrapped = when {
            delta <= -HALF_CIRCLE_DEG -> delta + FULL_CIRCLE_DEG
            delta > HALF_CIRCLE_DEG -> delta - FULL_CIRCLE_DEG
            else -> delta
        }
        return if (wrapped == 0.0) 0.0 else wrapped
    }

    /**
     * Computes the absolute angular separation between [aDeg] and [bDeg] in `[0.0, 180.0]`.
     */
    fun angularDistanceDegrees(aDeg: Double, bDeg: Double): Double =
        abs(signedDeltaDegrees(aDeg, bDeg))

    /**
     * Applies shortest-arc circular exponential moving average (EMA) smoothing across the 0°/360° seam.
     *
     * @param previousDeg Prior smoothed heading in degrees.
     * @param rawDeg New raw heading sample in degrees.
     * @param alpha Smoothing factor in `[0.0, 1.0]` where higher values respond faster.
     */
    fun circularEmaSmooth(previousDeg: Double, rawDeg: Double, alpha: Double): Double {
        require(alpha in 0.0..1.0) { "EMA alpha must be in [0.0, 1.0], was: $alpha" }
        val delta = signedDeltaDegrees(previousDeg, rawDeg)
        return normalizeDegrees(previousDeg + alpha * delta)
    }

    /**
     * Computes the weighted circular mean of [vectors] using East/North trigonometric decomposition.
     */
    fun addVectors(vararg vectors: Vector2D): Vector2D {
        require(vectors.isNotEmpty()) { "At least one vector is required." }
        var totalEast = 0.0
        var totalNorth = 0.0
        for (v in vectors) {
            val rad = toRadians(v.bearingDeg)
            totalEast += v.magnitude * sin(rad)
            totalNorth += v.magnitude * cos(rad)
        }
        val magnitude = hypot(totalEast, totalNorth)
        if (magnitude < ZERO_VECTOR_EPSILON) {
            return Vector2D(bearingDeg = vectors.first().bearingDeg, magnitude = 0.0)
        }
        val bearing = normalizeDegrees(toDegrees(atan2(totalEast, totalNorth)))
        return Vector2D(bearingDeg = bearing, magnitude = magnitude)
    }

    /**
     * Returns `true` if [angleDeg] lies inside the clockwise sector `[startDeg, endDeg]`.
     */
    fun isWithinClockwiseSector(angleDeg: Double, startDeg: Double, endDeg: Double): Boolean =
        BearingSector(startDeg, endDeg).contains(angleDeg)

    fun toRadians(degrees: Double): Double = degrees * (PI / HALF_CIRCLE_DEG)

    fun toDegrees(radians: Double): Double = radians * (HALF_CIRCLE_DEG / PI)
}

/**
 * 2D directional vector whose [bearingDeg] is normalized to `[0.0, 360.0)` clockwise from True North.
 */
data class Vector2D private constructor(
    val bearingDeg: Double,
    val magnitude: Double,
) {
    init {
        require(magnitude.isFinite() && magnitude >= 0.0) {
            "Vector magnitude must be finite and non-negative, was: $magnitude"
        }
    }

    val eastComponent: Double
        get() = magnitude * sin(AngleMath.toRadians(bearingDeg))

    val northComponent: Double
        get() = magnitude * cos(AngleMath.toRadians(bearingDeg))

    companion object {
        operator fun invoke(bearingDeg: Double, magnitude: Double): Vector2D =
            Vector2D(
                bearingDeg = AngleMath.normalizeDegrees(bearingDeg),
                magnitude = magnitude,
            )
    }
}

/**
 * Clockwise inclusive compass sector `[startDeg, endDeg]` supporting wrap-around across 0°/360°.
 */
data class BearingSector private constructor(
    val startDeg: Double,
    val endDeg: Double,
    val isFullCircle: Boolean,
) {
    /** Clockwise angular width of this sector in `[0.0, 360.0]`. */
    val spanDegrees: Double
        get() = if (isFullCircle) {
            360.0
        } else {
            val raw = endDeg - startDeg
            if (raw < 0.0) raw + 360.0 else raw
        }

    /**
     * Returns `true` if [angleDeg] falls within this clockwise sector (inclusive of boundaries).
     */
    fun contains(angleDeg: Double): Boolean {
        val norm = AngleMath.normalizeDegrees(angleDeg)
        if (isFullCircle) return true
        return if (startDeg <= endDeg) {
            norm >= startDeg && norm <= endDeg
        } else {
            norm >= startDeg || norm <= endDeg
        }
    }

    /**
     * Returns `0.0` if [angleDeg] is inside this sector, or the shortest angular distance in degrees
     * to the nearest sector edge (`(0.0, 180.0]`) when outside.
     */
    fun distanceOutsideDegrees(angleDeg: Double): Double {
        if (contains(angleDeg)) return 0.0
        val toStart = AngleMath.angularDistanceDegrees(angleDeg, startDeg)
        val toEnd = AngleMath.angularDistanceDegrees(angleDeg, endDeg)
        return minOf(toStart, toEnd)
    }

    /**
     * Returns the shortest angular margin in degrees to the nearest edge when [angleDeg] is inside,
     * or `0.0` when outside or full-circle.
     */
    fun clearanceInsideDegrees(angleDeg: Double): Double {
        if (isFullCircle) return 180.0
        if (!contains(angleDeg)) return 0.0
        val toStart = AngleMath.angularDistanceDegrees(angleDeg, startDeg)
        val toEnd = AngleMath.angularDistanceDegrees(angleDeg, endDeg)
        return minOf(toStart, toEnd)
    }

    companion object {
        operator fun invoke(startDeg: Double, endDeg: Double): BearingSector {
            require(startDeg.isFinite() && endDeg.isFinite()) {
                "Sector bounds must be finite: [$startDeg, $endDeg]"
            }
            val fullCircle = abs(endDeg - startDeg) >= 360.0
            return BearingSector(
                startDeg = AngleMath.normalizeDegrees(startDeg),
                endDeg = AngleMath.normalizeDegrees(endDeg),
                isFullCircle = fullCircle,
            )
        }
    }
}

/**
 * Standard 16-point compass rose ordered clockwise from North.
 */
enum class CompassDirection(val abbreviation: String, val centerDeg: Double) {
    N("N", 0.0),
    NNE("NNE", 22.5),
    NE("NE", 45.0),
    ENE("ENE", 67.5),
    E("E", 90.0),
    ESE("ESE", 112.5),
    SE("SE", 135.0),
    SSE("SSE", 157.5),
    S("S", 180.0),
    SSW("SSW", 202.5),
    SW("SW", 225.0),
    WSW("WSW", 247.5),
    W("W", 270.0),
    WNW("WNW", 292.5),
    NW("NW", 315.0),
    NNW("NNW", 337.5);

    companion object {
        private const val SECTOR_WIDTH_DEG = 22.5

        fun fromBearing(bearingDeg: Double): CompassDirection {
            val normalized = AngleMath.normalizeDegrees(bearingDeg)
            val index = (((normalized + SECTOR_WIDTH_DEG / 2.0) % 360.0) / SECTOR_WIDTH_DEG).toInt() % 16
            return entries[index]
        }
    }
}

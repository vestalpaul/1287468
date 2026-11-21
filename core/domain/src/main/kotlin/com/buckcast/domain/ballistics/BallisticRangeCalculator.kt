package com.buckcast.domain.ballistics

import com.buckcast.domain.math.AngleMath
import com.buckcast.domain.math.GeoMath
import com.buckcast.domain.math.GeoPoint
import com.buckcast.domain.math.GeoUnits
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Rifleman's rule (cosine-compensated) ballistic range result in US imperial yards and feet.
 *
 * @property lineOfSightYards Straight-line slant distance from shooter/tree-stand eye level to target.
 * @property horizontalCutYards Equivalent horizontal ballistic yardage (`lineOfSight * cos(angle)`).
 * @property elevationAngleDeg Signed vertical angle in `[-90, 90]` (`+` uphill, `-` downhill).
 * @property cosineFactor `cos(elevationAngle)` in `[0, 1]`.
 * @property verticalDeltaFeet Effective vertical rise (`+`) or drop (`-`) in feet from shooter to target.
 * @property yardageReductionYards Difference `lineOfSightYards - horizontalCutYards` (>= 0).
 */
data class BallisticRangeResult(
    val lineOfSightYards: Double,
    val horizontalCutYards: Double,
    val elevationAngleDeg: Double,
    val cosineFactor: Double,
    val verticalDeltaFeet: Double,
    val yardageReductionYards: Double,
)

/**
 * Pure-Kotlin line-of-sight and cosine-compensated horizontal yardage calculator incorporating
 * terrain elevation delta and elevated tree-stand height in feet.
 */
object BallisticRangeCalculator {

    /**
     * Computes ballistic cut range from map/horizontal distance, terrain elevation delta, and stand height.
     *
     * @param horizontalDistanceYards Map/horizontal ground distance from base of tree/stand to target in yards.
     * @param targetElevationDeltaFeet `targetElevationFeet - groundElevationAtStandFeet` (`+` target uphill, `-` downhill).
     * @param standHeightFeet Height of hunter above ground at the stand in feet (defaults to `0.0`).
     */
    fun calculateFromHorizontal(
        horizontalDistanceYards: Double,
        targetElevationDeltaFeet: Double,
        standHeightFeet: Double = 0.0,
    ): BallisticRangeResult {
        require(horizontalDistanceYards.isFinite() && horizontalDistanceYards >= 0.0) {
            "horizontalDistanceYards must be non-negative: $horizontalDistanceYards"
        }
        require(targetElevationDeltaFeet.isFinite()) {
            "targetElevationDeltaFeet must be finite: $targetElevationDeltaFeet"
        }
        require(standHeightFeet.isFinite() && standHeightFeet >= 0.0) {
            "standHeightFeet must be non-negative: $standHeightFeet"
        }

        val netVerticalFeet = targetElevationDeltaFeet - standHeightFeet
        val netVerticalYards = GeoUnits.feetToYards(netVerticalFeet)
        val losYards = hypot(horizontalDistanceYards, netVerticalYards)

        if (losYards <= 1e-9) {
            return BallisticRangeResult(
                lineOfSightYards = 0.0,
                horizontalCutYards = 0.0,
                elevationAngleDeg = if (netVerticalFeet < 0.0) -90.0 else if (netVerticalFeet > 0.0) 90.0 else 0.0,
                cosineFactor = 0.0,
                verticalDeltaFeet = netVerticalFeet,
                yardageReductionYards = 0.0,
            )
        }

        val angleRad = atan2(netVerticalYards, horizontalDistanceYards)
        val cosine = cos(angleRad).coerceIn(0.0, 1.0)
        val cutYards = losYards * cosine

        return BallisticRangeResult(
            lineOfSightYards = losYards,
            horizontalCutYards = cutYards,
            elevationAngleDeg = AngleMath.toDegrees(angleRad),
            cosineFactor = cosine,
            verticalDeltaFeet = netVerticalFeet,
            yardageReductionYards = (losYards - cutYards).coerceAtLeast(0.0),
        )
    }

    /**
     * Computes equivalent horizontal ballistic cut distance from rangefinder [lineOfSightYards]
     * and inclinometer [elevationAngleDeg] (`[-90, 90]`).
     */
    fun calculateFromLineOfSight(
        lineOfSightYards: Double,
        elevationAngleDeg: Double,
    ): BallisticRangeResult {
        require(lineOfSightYards.isFinite() && lineOfSightYards >= 0.0) {
            "lineOfSightYards must be non-negative: $lineOfSightYards"
        }
        require(elevationAngleDeg.isFinite() && elevationAngleDeg in -90.0..90.0) {
            "elevationAngleDeg must be in [-90, 90]: $elevationAngleDeg"
        }

        val angleRad = AngleMath.toRadians(elevationAngleDeg)
        val cosine = cos(angleRad).coerceIn(0.0, 1.0)
        val cutYards = lineOfSightYards * cosine
        val verticalFeet = GeoUnits.yardsToFeet(lineOfSightYards * sin(angleRad))

        return BallisticRangeResult(
            lineOfSightYards = lineOfSightYards,
            horizontalCutYards = cutYards,
            elevationAngleDeg = elevationAngleDeg,
            cosineFactor = cosine,
            verticalDeltaFeet = verticalFeet,
            yardageReductionYards = (lineOfSightYards - cutYards).coerceAtLeast(0.0),
        )
    }

    /**
     * Computes ballistic cut range directly between two WGS84 [GeoPoint]s with elevations in feet
     * and optional [standHeightFeet].
     */
    fun calculateFromCoordinates(
        standGroundLocation: GeoPoint,
        targetLocation: GeoPoint,
        standHeightFeet: Double = 0.0,
    ): BallisticRangeResult {
        val horizontalYards = GeoMath.distanceYards(standGroundLocation, targetLocation)
        val groundElevation = standGroundLocation.elevationFeet ?: 0.0
        val targetElevation = targetLocation.elevationFeet ?: groundElevation
        return calculateFromHorizontal(
            horizontalDistanceYards = horizontalYards,
            targetElevationDeltaFeet = targetElevation - groundElevation,
            standHeightFeet = standHeightFeet,
        )
    }
}

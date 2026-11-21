package com.buckcast.domain.ballistics

import com.buckcast.domain.math.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BallisticRangeCalculatorTest {

    @Test
    fun `elevated treestand shot on flat terrain cuts horizontal distance via cosine rule`() {
        // 30 horizontal yards, flat ground (delta 0 ft), hunter 20 ft up in tree
        val result = BallisticRangeCalculator.calculateFromHorizontal(
            horizontalDistanceYards = 30.0,
            targetElevationDeltaFeet = 0.0,
            standHeightFeet = 20.0,
        )

        // Vertical drop = -20 ft (-6.6667 yd); LOS = hypot(30, 6.6667) = 30.7318 yd
        assertEquals(30.7318, result.lineOfSightYards, 1e-3)
        assertEquals(30.0, result.horizontalCutYards, 1e-3)
        assertEquals(-20.0, result.verticalDeltaFeet, 1e-6)
        assertTrue(result.elevationAngleDeg < 0.0)
        assertTrue(result.yardageReductionYards > 0.7)
    }

    @Test
    fun `calculateFromLineOfSight applies exact cosine cut at 30 degree angle`() {
        val result = BallisticRangeCalculator.calculateFromLineOfSight(
            lineOfSightYards = 40.0,
            elevationAngleDeg = -30.0,
        )

        // 40 * cos(30°) = 34.641 yd
        assertEquals(34.6410, result.horizontalCutYards, 1e-3)
        assertEquals(5.3590, result.yardageReductionYards, 1e-3)
        assertEquals(-60.0, result.verticalDeltaFeet, 1e-3)
    }

    @Test
    fun `calculateFromCoordinates combines GPS horizontal distance, terrain elevation delta, and stand height`() {
        val standGround = GeoPoint(41.5868, -93.6250, elevationFeet = 920.0)
        // Roughly 40 yards due north on a downhill creek bottom (890 ft) with a 21 ft tree stand
        val target = GeoPoint(41.58713, -93.6250, elevationFeet = 890.0)

        val result = BallisticRangeCalculator.calculateFromCoordinates(
            standGroundLocation = standGround,
            targetLocation = target,
            standHeightFeet = 21.0,
        )

        // Net vertical drop = (890 - 920) - 21 = -51 ft = -17 yd
        assertEquals(-51.0, result.verticalDeltaFeet, 1e-6)
        assertTrue(result.lineOfSightYards > result.horizontalCutYards)
        assertTrue(result.elevationAngleDeg < -20.0)
    }
}

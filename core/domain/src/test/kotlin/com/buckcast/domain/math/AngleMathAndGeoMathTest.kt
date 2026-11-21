package com.buckcast.domain.math

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class AngleMathAndGeoMathTest {

    @ParameterizedTest
    @CsvSource(
        "0.0, 0.0",
        "-0.0, 0.0",
        "360.0, 0.0",
        "720.0, 0.0",
        "-90.0, 270.0",
        "-360.0, 0.0",
        "-0.5, 359.5",
        "405.25, 45.25",
        "359.999, 359.999",
    )
    fun `normalizeDegrees maps values strictly into half-open 0 to 360 interval`(
        input: Double,
        expected: Double,
    ) {
        val result = AngleMath.normalizeDegrees(input)
        assertTrue(result >= 0.0 && result < 360.0, "Expected [0, 360), got $result")
        assertFalse(result.toRawBits() == (-0.0).toRawBits(), "Negative zero must be eliminated")
        assertEquals(expected, result, 1e-9)
    }

    @Test
    fun `normalizeDegrees rejects NaN and infinities`() {
        assertThrows<IllegalArgumentException> { AngleMath.normalizeDegrees(Double.NaN) }
        assertThrows<IllegalArgumentException> { AngleMath.normalizeDegrees(Double.POSITIVE_INFINITY) }
        assertThrows<IllegalArgumentException> { AngleMath.normalizeDegrees(Double.NEGATIVE_INFINITY) }
    }

    @Test
    fun `signedDeltaDegrees and angularDistanceDegrees handle 0-360 seam cleanly`() {
        assertEquals(20.0, AngleMath.signedDeltaDegrees(350.0, 10.0), 1e-9)
        assertEquals(-20.0, AngleMath.signedDeltaDegrees(10.0, 350.0), 1e-9)
        assertEquals(180.0, AngleMath.signedDeltaDegrees(0.0, 180.0), 1e-9)
        assertEquals(180.0, AngleMath.signedDeltaDegrees(180.0, 0.0), 1e-9)
        assertEquals(20.0, AngleMath.angularDistanceDegrees(350.0, 10.0), 1e-9)
    }

    @Test
    fun `circularEmaSmooth steps across north seam without whipping through 180`() {
        val smoothedClockwise = AngleMath.circularEmaSmooth(previousDeg = 355.0, rawDeg = 5.0, alpha = 0.25)
        assertEquals(357.5, smoothedClockwise, 1e-9)

        val smoothedCounter = AngleMath.circularEmaSmooth(previousDeg = 2.0, rawDeg = 352.0, alpha = 0.5)
        assertEquals(357.0, smoothedCounter, 1e-9)
    }

    @Test
    fun `BearingSector handles wrap-around sectors across North`() {
        val northWrap = BearingSector(315.0, 45.0)
        assertEquals(90.0, northWrap.spanDegrees, 1e-9)
        assertTrue(northWrap.contains(0.0))
        assertTrue(northWrap.contains(350.0))
        assertTrue(northWrap.contains(30.0))
        assertTrue(northWrap.contains(315.0))
        assertTrue(northWrap.contains(45.0))
        assertFalse(northWrap.contains(180.0))

        assertEquals(0.0, northWrap.distanceOutsideDegrees(359.0), 1e-9)
        assertEquals(10.0, northWrap.distanceOutsideDegrees(305.0), 1e-9)
        assertEquals(15.0, northWrap.clearanceInsideDegrees(330.0), 1e-9)

        val fullCircle = BearingSector(0.0, 360.0)
        assertTrue(fullCircle.isFullCircle)
        assertTrue(fullCircle.contains(193.0))
    }

    @Test
    fun `addVectors combines orthogonal and opposing vectors accurately`() {
        val orthogonal = AngleMath.addVectors(Vector2D(0.0, 3.0), Vector2D(90.0, 4.0))
        assertEquals(5.0, orthogonal.magnitude, 1e-6)
        assertEquals(53.1301024, orthogonal.bearingDeg, 1e-4)

        val opposing = AngleMath.addVectors(Vector2D(45.0, 5.0), Vector2D(225.0, 5.0))
        assertEquals(0.0, opposing.magnitude, 1e-9)
    }

    @Test
    fun `CompassDirection maps 16-point rose boundaries accurately`() {
        assertEquals(CompassDirection.N, CompassDirection.fromBearing(359.0))
        assertEquals(CompassDirection.N, CompassDirection.fromBearing(0.0))
        assertEquals(CompassDirection.NNE, CompassDirection.fromBearing(22.5))
        assertEquals(CompassDirection.E, CompassDirection.fromBearing(90.0))
        assertEquals(CompassDirection.SW, CompassDirection.fromBearing(225.0))
        assertEquals(CompassDirection.NNW, CompassDirection.fromBearing(348.0))
    }

    @Test
    fun `GeoMath computes accurate Haversine yardage, bearings, and unit conversions`() {
        val p1 = GeoPoint(41.5868, -93.6250, elevationFeet = 950.0)
        // Roughly 0.001 degrees north (~121.6 yards)
        val p2 = GeoPoint(41.5878, -93.6250, elevationFeet = 970.0)

        val yards = GeoMath.distanceYards(p1, p2)
        assertEquals(121.6, yards, 2.0)
        assertEquals(0.0, GeoMath.initialBearingDegrees(p1, p2), 0.1)

        val mid = GeoMath.interpolate(p1, p2, 0.5)
        assertEquals(41.5873, mid.latitude, 1e-6)
        assertEquals(960.0, mid.elevationFeet!!, 1e-6)

        assertEquals(10.0, GeoUnits.kphToMph(GeoUnits.mphToKph(10.0)), 1e-9)
        assertEquals(32.0, GeoUnits.celsiusToDegF(0.0), 1e-9)
        assertEquals(29.92, GeoUnits.hpaToInHg(1013.20), 0.01)
    }
}

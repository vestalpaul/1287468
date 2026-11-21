package com.buckcast.domain.wind

import com.buckcast.domain.math.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EntryRouteWindAuditorTest {

    // Base point near Des Moines, IA; ~0.001 deg lat ≈ 121.6 yards N/S, ~0.001 deg lon at 41.6°N ≈ 91 yards E/W.
    private val truckParking = GeoPoint(41.5800, -93.6250)
    private val creekCrossing = GeoPoint(41.5815, -93.6250)
    private val standBase = GeoPoint(41.5830, -93.6250)

    private val routeNorth = EntryRoute(
        id = "route-creek-walkin",
        name = "Creek Bottom Walk-In",
        waypoints = listOf(truckParking, creekCrossing, standBase),
    )

    private val eastBedding = BeddingZone(
        id = "bed-east",
        name = "East Thick Cedar Bedding",
        center = GeoPoint(41.5815, -93.6232), // ~164 yards directly East of creekCrossing
        radiusYards = 40.0,
    )

    @Test
    fun `clean entry route when scent blows away from bedding zone`() {
        // Wind blows scent West (270°), away from East bedding
        val audit = EntryRouteWindAuditor.audit(
            route = routeNorth,
            beddingZones = listOf(eastBedding),
            effectiveScentToDeg = 270.0,
        )
        assertEquals(RouteWindVerdict.CLEAN, audit.verdict)
        assertTrue(audit.isClean)
        assertTrue(audit.compromisedSegments.isEmpty())
    }

    @Test
    fun `compromised entry route when walk-in passes upwind of bedding zone within scent reach`() {
        // Wind blows scent East (90°), right across East bedding 164 yards away
        val audit = EntryRouteWindAuditor.audit(
            route = routeNorth,
            beddingZones = listOf(eastBedding),
            effectiveScentToDeg = 90.0,
        )
        assertEquals(RouteWindVerdict.COMPROMISED, audit.verdict)
        assertFalse(audit.isClean)
        assertTrue(audit.compromisedSegments.isNotEmpty())
        assertTrue(audit.compromisedSegments.any { it.beddingZoneId == "bed-east" })
    }

    @Test
    fun `out-of-range bedding zone beyond scent reach stays CLEAN`() {
        val distantBedding = BeddingZone(
            id = "bed-far",
            name = "Far Ridge Bedding",
            center = GeoPoint(41.5815, -93.6190), // ~545 yards East (> 300 yd reach + 40 yd radius)
            radiusYards = 40.0,
        )
        val audit = EntryRouteWindAuditor.audit(
            route = routeNorth,
            beddingZones = listOf(distantBedding),
            effectiveScentToDeg = 90.0,
        )
        assertEquals(RouteWindVerdict.CLEAN, audit.verdict)
        assertTrue(audit.isClean)
    }

    @Test
    fun `route walking directly through bedding circle flags direct intrusion`() {
        val intrusionZone = BeddingZone(
            id = "bed-creek",
            name = "Creek Bend Bedding",
            center = creekCrossing,
            radiusYards = 35.0,
        )
        val audit = EntryRouteWindAuditor.audit(
            route = routeNorth,
            beddingZones = listOf(intrusionZone),
            effectiveScentToDeg = 270.0,
        )
        assertEquals(RouteWindVerdict.COMPROMISED, audit.verdict)
        assertTrue(audit.compromisedSegments.any { it.isDirectIntrusion })
    }
}

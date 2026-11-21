package com.buckcast.domain.wind

import com.buckcast.domain.math.AngleMath
import com.buckcast.domain.math.GeoMath
import com.buckcast.domain.math.GeoPoint
import kotlin.math.atan2
import kotlin.math.ceil

enum class RouteWindVerdict {
    CLEAN,
    CAUTION,
    COMPROMISED,
}

data class BeddingZone(
    val id: String,
    val name: String,
    val center: GeoPoint,
    val radiusYards: Double,
) {
    init {
        require(radiusYards.isFinite() && radiusYards > 0.0) {
            "Bedding zone radiusYards must be positive: $radiusYards"
        }
    }
}

data class EntryRoute(
    val id: String,
    val name: String,
    val waypoints: List<GeoPoint>,
) {
    init {
        require(waypoints.size >= 2) {
            "EntryRoute requires at least 2 waypoints, had: ${waypoints.size}"
        }
    }
}

data class CompromisedRouteSegment(
    val segmentIndex: Int,
    val samplePoint: GeoPoint,
    val beddingZoneId: String,
    val beddingZoneName: String,
    val distanceYards: Double,
    val bearingToBeddingDeg: Double,
    val angularDeltaFromScentDeg: Double,
    val isDirectIntrusion: Boolean,
)

data class EntryRouteAuditResult(
    val routeId: String,
    val routeName: String,
    val verdict: RouteWindVerdict,
    val isClean: Boolean,
    val effectiveScentToDeg: Double,
    val scentReachYards: Double,
    val compromisedSegments: List<CompromisedRouteSegment>,
    val summary: String,
)

/**
 * Audits stand entry/exit polyline routes against known bedding zones for downwind scent-cone intersections.
 */
object EntryRouteWindAuditor {

    const val DEFAULT_SCENT_CONE_HALF_ANGLE_DEG = 22.5
    const val DEFAULT_SCENT_REACH_YARDS = 300.0
    const val DEFAULT_SAMPLE_STEP_YARDS = 25.0

    fun audit(
        route: EntryRoute,
        beddingZones: List<BeddingZone>,
        effectiveScentToDeg: Double,
        scentConeHalfAngleDeg: Double = DEFAULT_SCENT_CONE_HALF_ANGLE_DEG,
        scentReachYards: Double = DEFAULT_SCENT_REACH_YARDS,
        sampleStepYards: Double = DEFAULT_SAMPLE_STEP_YARDS,
    ): EntryRouteAuditResult {
        require(scentConeHalfAngleDeg.isFinite() && scentConeHalfAngleDeg in 1.0..90.0) {
            "scentConeHalfAngleDeg must be in [1, 90]: $scentConeHalfAngleDeg"
        }
        require(scentReachYards.isFinite() && scentReachYards > 0.0) {
            "scentReachYards must be positive: $scentReachYards"
        }
        require(sampleStepYards.isFinite() && sampleStepYards > 0.0) {
            "sampleStepYards must be positive: $sampleStepYards"
        }

        val normalizedScentDeg = AngleMath.normalizeDegrees(effectiveScentToDeg)
        if (beddingZones.isEmpty()) {
            return EntryRouteAuditResult(
                routeId = route.id,
                routeName = route.name,
                verdict = RouteWindVerdict.CLEAN,
                isClean = true,
                effectiveScentToDeg = normalizedScentDeg,
                scentReachYards = scentReachYards,
                compromisedSegments = emptyList(),
                summary = "No bedding zones configured near ${route.name}.",
            )
        }

        val hits = mutableListOf<CompromisedRouteSegment>()

        for (segIdx in 0 until route.waypoints.lastIndex) {
            val start = route.waypoints[segIdx]
            val end = route.waypoints[segIdx + 1]
            val segmentLengthYards = GeoMath.distanceYards(start, end)
            val steps = maxOf(1, ceil(segmentLengthYards / sampleStepYards).toInt())

            for (step in 0..steps) {
                // Skip duplicate start point on subsequent segments (already audited as prior endpoint)
                if (segIdx > 0 && step == 0) continue

                val fraction = step.toDouble() / steps.toDouble()
                val samplePt = GeoMath.interpolate(start, end, fraction)

                for (zone in beddingZones) {
                    val distance = GeoMath.distanceYards(samplePt, zone.center)
                    if (distance <= zone.radiusYards) {
                        val bearing = GeoMath.initialBearingDegrees(samplePt, zone.center)
                        val delta = AngleMath.angularDistanceDegrees(normalizedScentDeg, bearing)
                        hits += CompromisedRouteSegment(
                            segmentIndex = segIdx,
                            samplePoint = samplePt,
                            beddingZoneId = zone.id,
                            beddingZoneName = zone.name,
                            distanceYards = distance,
                            bearingToBeddingDeg = bearing,
                            angularDeltaFromScentDeg = delta,
                            isDirectIntrusion = true,
                        )
                        continue
                    }

                    if (distance <= scentReachYards + zone.radiusYards) {
                        val bearingToZone = GeoMath.initialBearingDegrees(samplePt, zone.center)
                        val angularRadiusDeg = AngleMath.toDegrees(atan2(zone.radiusYards, distance))
                        val delta = AngleMath.angularDistanceDegrees(normalizedScentDeg, bearingToZone)
                        if (delta <= scentConeHalfAngleDeg + angularRadiusDeg) {
                            hits += CompromisedRouteSegment(
                                segmentIndex = segIdx,
                                samplePoint = samplePt,
                                beddingZoneId = zone.id,
                                beddingZoneName = zone.name,
                                distanceYards = distance,
                                bearingToBeddingDeg = bearingToZone,
                                angularDeltaFromScentDeg = delta,
                                isDirectIntrusion = false,
                            )
                        }
                    }
                }
            }
        }

        val verdict = when {
            hits.isEmpty() -> RouteWindVerdict.CLEAN
            hits.any { it.isDirectIntrusion || it.distanceYards <= scentReachYards * 0.8 } ->
                RouteWindVerdict.COMPROMISED
            else -> RouteWindVerdict.CAUTION
        }

        val affectedNames = hits.map { it.beddingZoneName }.distinct().joinToString(", ")
        val summary = when (verdict) {
            RouteWindVerdict.CLEAN ->
                "Route '${route.name}' stays clear of all bedding zones on ${normalizedScentDeg.toInt()}° scent vector."
            RouteWindVerdict.CAUTION ->
                "Route '${route.name}' fringes outer scent reach near: $affectedNames."
            RouteWindVerdict.COMPROMISED ->
                "Route '${route.name}' blows scent directly across bedding: $affectedNames."
        }

        return EntryRouteAuditResult(
            routeId = route.id,
            routeName = route.name,
            verdict = verdict,
            isClean = verdict == RouteWindVerdict.CLEAN,
            effectiveScentToDeg = normalizedScentDeg,
            scentReachYards = scentReachYards,
            compromisedSegments = hits,
            summary = summary,
        )
    }
}

package com.buckcast.feature.map

import com.buckcast.domain.math.AngleMath
import com.buckcast.domain.math.GeoPoint
import com.buckcast.domain.model.RouteType
import com.buckcast.domain.model.StandRoute
import com.buckcast.domain.model.Waypoint
import com.buckcast.domain.wind.StandWindVerdict
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class StandMapPin(
    val id: String,
    val name: String,
    val location: GeoPoint,
    val verdict: StandWindVerdict?,
    val colorHex: String,
    val isLocked: Boolean,
    val isSelected: Boolean,
)

/**
 * Pure-Kotlin geodesic geometry & GeoJSON builder (RFC 7946 `[lon, lat]`) for tactical overlays.
 * Uses pure StringBuilder JSON emission so JVM unit tests execute without Android `org.json` stubs.
 */
object TacticalGeoJsonBuilder {

    private const val EARTH_MEAN_RADIUS_METERS = 6_371_008.8
    private const val METERS_TO_YARDS = 1.0936132983377078

    /**
     * Computes the destination coordinate along initial true-north [bearingDeg] after [distanceYards].
     */
    fun destinationPoint(
        origin: GeoPoint,
        bearingDeg: Double,
        distanceYards: Double,
    ): GeoPoint {
        require(bearingDeg.isFinite()) { "bearingDeg must be finite: $bearingDeg" }
        require(distanceYards.isFinite() && distanceYards >= 0.0) {
            "distanceYards must be non-negative: $distanceYards"
        }
        if (distanceYards == 0.0) return GeoPoint(origin.latitude, origin.longitude)

        val distanceMeters = distanceYards / METERS_TO_YARDS
        val angularDist = distanceMeters / EARTH_MEAN_RADIUS_METERS
        val bearingRad = AngleMath.toRadians(AngleMath.normalizeDegrees(bearingDeg))
        val lat1 = AngleMath.toRadians(origin.latitude)
        val lon1 = AngleMath.toRadians(origin.longitude)

        val sinLat1 = sin(lat1)
        val cosLat1 = cos(lat1)
        val sinDelta = sin(angularDist)
        val cosDelta = cos(angularDist)

        val lat2 = asin((sinLat1 * cosDelta + cosLat1 * sinDelta * cos(bearingRad)).coerceIn(-1.0, 1.0))
        val lon2 = lon1 + atan2(
            sin(bearingRad) * sinDelta * cosLat1,
            cosDelta - sinLat1 * sin(lat2),
        )

        val normLon = ((AngleMath.toDegrees(lon2) + 540.0) % 360.0) - 180.0
        return GeoPoint(
            latitude = AngleMath.toDegrees(lat2).coerceIn(-90.0, 90.0),
            longitude = normLon,
        )
    }

    fun standPinColorHex(verdict: StandWindVerdict?, hasPreferredWind: Boolean): String =
        if (!hasPreferredWind || verdict == null) {
            "#9E9E9E"
        } else {
            when (verdict) {
                StandWindVerdict.OPTIMAL, StandWindVerdict.GOOD -> "#4CAF50"
                StandWindVerdict.CAUTION -> "#FFB300"
                StandWindVerdict.UNSAFE -> "#EF5350"
            }
        }

    fun emptyFeatureCollection(): String = """{"type":"FeatureCollection","features":[]}"""

    /**
     * Builds GeoJSON containing the user GPS point and True-North FOV sector polygon.
     */
    fun buildUserPuckAndFovGeoJson(
        location: GeoPoint?,
        trueHeadingDeg: Double,
        fovHalfAngleDeg: Double = 27.5,
        fovRangeYards: Double = 110.0,
    ): String {
        if (location == null) return emptyFeatureCollection()
        val normHeading = AngleMath.normalizeDegrees(trueHeadingDeg)
        val wedge = buildSectorRing(
            origin = location,
            centerBearingDeg = normHeading,
            halfAngleDeg = fovHalfAngleDeg,
            rangeYards = fovRangeYards,
            steps = 12,
        )
        val fovFeature = """{"type":"Feature","properties":{"kind":"fov"},"geometry":{"type":"Polygon","coordinates":[${coordsJson(wedge)}]}}"""
        val puckFeature = """{"type":"Feature","properties":{"kind":"puck"},"geometry":{"type":"Point","coordinates":${coordJson(location)}}}"""
        return featureCollection(listOf(fovFeature, puckFeature))
    }

    /**
     * Builds the downwind 300-yard tactical scent cone (single wedge or concentric multi-cone rings).
     */
    fun buildScentConeGeoJson(
        origin: GeoPoint?,
        scentToDeg: Double,
        lengthYards: Double = 300.0,
        halfAngleDeg: Double = 22.5,
        steps: Int = 20,
    ): String {
        if (origin == null) return emptyFeatureCollection()
        val ring = buildSectorRing(
            origin = origin,
            centerBearingDeg = AngleMath.normalizeDegrees(scentToDeg),
            halfAngleDeg = halfAngleDeg,
            rangeYards = lengthYards,
            steps = steps,
        )
        val feature = """{"type":"Feature","properties":{"kind":"scent_cone"},"geometry":{"type":"Polygon","coordinates":[${coordsJson(ring)}]}}"""
        return featureCollection(listOf(feature))
    }

    /**
     * Builds an ambient wind-direction arrow pointing in the direction the wind is blowing (FROM -> TO).
     */
    fun buildWindArrowGeoJson(
        origin: GeoPoint?,
        windFromDeg: Double,
        totalLengthYards: Double = 150.0,
    ): String {
        if (origin == null) return emptyFeatureCollection()
        val toDeg = AngleMath.oppositeBearing(windFromDeg)
        val fromDeg = AngleMath.normalizeDegrees(windFromDeg)
        val start = destinationPoint(origin, fromDeg, totalLengthYards * 0.35)
        val tip = destinationPoint(origin, toDeg, totalLengthYards * 0.65)
        return buildArrowFeatures(start = start, tip = tip, bearingDeg = toDeg, kind = "wind_arrow")
    }

    /**
     * Builds a secondary diurnal thermal slope drift vector when active (> 0.25 mph).
     */
    fun buildThermalDriftArrowGeoJson(
        origin: GeoPoint?,
        thermalToDeg: Double,
        thermalSpeedMph: Double,
    ): String {
        if (origin == null || thermalSpeedMph < 0.25) return emptyFeatureCollection()
        val lengthYards = (70.0 + thermalSpeedMph * 18.0).coerceIn(70.0, 210.0)
        val normTo = AngleMath.normalizeDegrees(thermalToDeg)
        val tip = destinationPoint(origin, normTo, lengthYards)
        return buildArrowFeatures(start = origin, tip = tip, bearingDeg = normTo, kind = "thermal_arrow")
    }

    /**
     * Builds toggleable concentric yardage range rings (50 / 100 / 200 / 300 yd) plus N-crown labels.
     */
    fun buildRangeRingsGeoJson(
        center: GeoPoint?,
        distancesYards: List<Double> = listOf(50.0, 100.0, 200.0, 300.0),
    ): String {
        if (center == null) return emptyFeatureCollection()
        val features = mutableListOf<String>()
        val steps = 48
        for (radiusYards in distancesYards) {
            val pts = ArrayList<GeoPoint>(steps + 1)
            for (i in 0..steps) {
                val bearing = (360.0 * i) / steps
                pts.add(destinationPoint(center, bearing, radiusYards))
            }
            features.add(
                """{"type":"Feature","properties":{"kind":"ring","yards":${radiusYards.toInt()}},"geometry":{"type":"LineString","coordinates":${coordsJson(pts)}}}""",
            )
            val labelPoint = destinationPoint(center, 0.0, radiusYards)
            features.add(
                """{"type":"Feature","properties":{"kind":"ring_label","label":"${radiusYards.toInt()} yd"},"geometry":{"type":"Point","coordinates":${coordJson(labelPoint)}}}""",
            )
        }
        return featureCollection(features)
    }

    fun buildStandsGeoJson(pins: List<StandMapPin>): String {
        if (pins.isEmpty()) return emptyFeatureCollection()
        val features = pins.map { pin ->
            """{"type":"Feature","properties":{"id":"${escapeJson(pin.id)}","name":"${escapeJson(pin.name)}","color":"${pin.colorHex}","locked":${pin.isLocked},"selected":${pin.isSelected}},"geometry":{"type":"Point","coordinates":${coordJson(pin.location)}}}"""
        }
        return featureCollection(features)
    }

    fun buildWaypointsGeoJson(
        waypoints: List<Waypoint>,
        lockedIds: Set<String>,
    ): String {
        if (waypoints.isEmpty()) return emptyFeatureCollection()
        val features = waypoints.map { wp ->
            val locked = wp.id in lockedIds
            val color = if (locked) "#757575" else "#26A69A"
            """{"type":"Feature","properties":{"id":"${escapeJson(wp.id)}","name":"${escapeJson(wp.name)}","category":"${wp.category.name}","color":"$color","locked":$locked},"geometry":{"type":"Point","coordinates":${coordJson(wp.location)}}}"""
        }
        return featureCollection(features)
    }

    fun buildRoutesGeoJson(
        savedRoutes: List<StandRoute>,
        draftPoints: List<GeoPoint>,
        draftRouteType: RouteType?,
    ): String {
        val features = mutableListOf<String>()
        for (route in savedRoutes) {
            if (route.coordinates.size >= 2) {
                val color = if (route.routeType == RouteType.ENTRY) "#4FC3F7" else "#FFB74D"
                features.add(
                    """{"type":"Feature","properties":{"kind":"route","routeType":"${route.routeType.name}","color":"$color"},"geometry":{"type":"LineString","coordinates":${coordsJson(route.coordinates)}}}""",
                )
            }
        }
        if (draftPoints.size >= 2) {
            val draftColor = if (draftRouteType == RouteType.EXIT) "#FFB74D" else "#4FC3F7"
            features.add(
                """{"type":"Feature","properties":{"kind":"draft_route","color":"$draftColor"},"geometry":{"type":"LineString","coordinates":${coordsJson(draftPoints)}}}""",
            )
        }
        for (pt in draftPoints) {
            features.add(
                """{"type":"Feature","properties":{"kind":"draft_vertex","color":"#FFFFFF"},"geometry":{"type":"Point","coordinates":${coordJson(pt)}}}""",
            )
        }
        return featureCollection(features)
    }

    private fun buildArrowFeatures(
        start: GeoPoint,
        tip: GeoPoint,
        bearingDeg: Double,
        kind: String,
    ): String {
        val headWingLengthYards = 24.0
        val leftWing = destinationPoint(tip, AngleMath.normalizeDegrees(bearingDeg + 150.0), headWingLengthYards)
        val rightWing = destinationPoint(tip, AngleMath.normalizeDegrees(bearingDeg - 150.0), headWingLengthYards)
        val shaft = """{"type":"Feature","properties":{"kind":"$kind"},"geometry":{"type":"LineString","coordinates":${coordsJson(listOf(start, tip))}}}"""
        val head = """{"type":"Feature","properties":{"kind":"$kind"},"geometry":{"type":"LineString","coordinates":${coordsJson(listOf(leftWing, tip, rightWing))}}}"""
        return featureCollection(listOf(shaft, head))
    }

    private fun buildSectorRing(
        origin: GeoPoint,
        centerBearingDeg: Double,
        halfAngleDeg: Double,
        rangeYards: Double,
        steps: Int,
    ): List<GeoPoint> {
        val startBearing = centerBearingDeg - halfAngleDeg
        val endBearing = centerBearingDeg + halfAngleDeg
        val points = ArrayList<GeoPoint>(steps + 3)
        points.add(origin)
        for (i in 0..steps) {
            val frac = i.toDouble() / steps.toDouble()
            val bearing = AngleMath.normalizeDegrees(startBearing + (endBearing - startBearing) * frac)
            points.add(destinationPoint(origin, bearing, rangeYards))
        }
        points.add(origin)
        return points
    }

    private fun featureCollection(features: List<String>): String =
        """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    private fun coordJson(point: GeoPoint): String =
        String.format(Locale.US, "[%.7f,%.7f]", point.longitude, point.latitude)

    private fun coordsJson(points: List<GeoPoint>): String =
        points.joinToString(prefix = "[", postfix = "]", separator = ",") { coordJson(it) }

    private fun escapeJson(value: String): String =
        buildString(value.length) {
            for (ch in value) {
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
}

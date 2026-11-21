package com.buckcast.domain.math

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS84 coordinate point with optional elevation in feet above sea level.
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val elevationFeet: Double? = null,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "Latitude must be in [-90.0, 90.0], was: $latitude"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "Longitude must be in [-180.0, 180.0], was: $longitude"
        }
        if (elevationFeet != null) {
            require(elevationFeet.isFinite()) {
                "Elevation must be finite when provided, was: $elevationFeet"
            }
        }
    }
}

/**
 * Geodetic distance, bearing, and interpolation functions using WGS84 spherical earth radius.
 */
object GeoMath {

    private const val EARTH_MEAN_RADIUS_METERS = 6_371_008.8
    private const val METERS_TO_YARDS = 1.0936132983377078

    /**
     * Computes great-circle surface distance between [from] and [to] in yards.
     */
    fun distanceYards(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = AngleMath.toRadians(from.latitude)
        val lat2 = AngleMath.toRadians(to.latitude)
        val dLat = lat2 - lat1
        val dLon = AngleMath.toRadians(to.longitude - from.longitude)

        val a = sin(dLat / 2.0).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2.0).pow(2)
        val c = 2.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
        val meters = EARTH_MEAN_RADIUS_METERS * c
        return meters * METERS_TO_YARDS
    }

    /**
     * Computes initial true-north bearing in `[0.0, 360.0)` from [from] toward [to].
     */
    fun initialBearingDegrees(from: GeoPoint, to: GeoPoint): Double {
        if (from.latitude == to.latitude && from.longitude == to.longitude) {
            return 0.0
        }
        val lat1 = AngleMath.toRadians(from.latitude)
        val lat2 = AngleMath.toRadians(to.latitude)
        val dLon = AngleMath.toRadians(to.longitude - from.longitude)

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return AngleMath.normalizeDegrees(AngleMath.toDegrees(atan2(y, x)))
    }

    /**
     * Linearly interpolates WGS84 latitude, longitude, and optional elevation for [fraction] in `[0.0, 1.0]`.
     */
    fun interpolate(from: GeoPoint, to: GeoPoint, fraction: Double): GeoPoint {
        require(fraction in 0.0..1.0) { "Interpolation fraction must be in [0.0, 1.0], was: $fraction" }
        val lat = from.latitude + (to.latitude - from.latitude) * fraction
        val lon = from.longitude + (to.longitude - from.longitude) * fraction
        val elev = if (from.elevationFeet != null && to.elevationFeet != null) {
            from.elevationFeet + (to.elevationFeet - from.elevationFeet) * fraction
        } else {
            null
        }
        return GeoPoint(latitude = lat, longitude = lon, elevationFeet = elev)
    }
}

/**
 * Explicit unit conversions used at the `:core:data` Open-Meteo boundary and domain helpers.
 */
object GeoUnits {
    private const val KPH_PER_MPH = 1.609344
    private const val HPA_PER_INHG = 33.8638866667
    private const val MM_PER_INCH = 25.4
    private const val FEET_PER_METER = 3.280839895013123
    private const val FEET_PER_YARD = 3.0

    fun kphToMph(kph: Double): Double = kph / KPH_PER_MPH
    fun mphToKph(mph: Double): Double = mph * KPH_PER_MPH

    fun celsiusToDegF(celsius: Double): Double = celsius * 9.0 / 5.0 + 32.0
    fun degFToCelsius(degF: Double): Double = (degF - 32.0) * 5.0 / 9.0

    fun hpaToInHg(hpa: Double): Double = hpa / HPA_PER_INHG
    fun inHgToHpa(inHg: Double): Double = inHg * HPA_PER_INHG

    fun mmToInches(mm: Double): Double = mm / MM_PER_INCH
    fun inchesToMm(inches: Double): Double = inches * MM_PER_INCH

    fun metersToFeet(meters: Double): Double = meters * FEET_PER_METER
    fun feetToMeters(feet: Double): Double = feet / FEET_PER_METER

    fun yardsToFeet(yards: Double): Double = yards * FEET_PER_YARD
    fun feetToYards(feet: Double): Double = feet / FEET_PER_YARD
}

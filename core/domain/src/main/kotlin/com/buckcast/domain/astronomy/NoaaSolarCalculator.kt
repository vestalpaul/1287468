package com.buckcast.domain.astronomy

import com.buckcast.domain.math.AngleMath
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

/**
 * Jurisdiction-configurable legal shooting light offset around sunrise and sunset.
 * Defaults to the standard Midwest `-30 min / +30 min` rule.
 */
data class LegalLightRule(
    val beforeSunriseMinutes: Long = 30L,
    val afterSunsetMinutes: Long = 30L,
) {
    init {
        require(beforeSunriseMinutes in 0L..180L) {
            "beforeSunriseMinutes must be in 0..180, was: $beforeSunriseMinutes"
        }
        require(afterSunsetMinutes in 0L..180L) {
            "afterSunsetMinutes must be in 0..180, was: $afterSunsetMinutes"
        }
    }
}

enum class SolarDayCondition {
    NORMAL,
    POLAR_DAY,
    POLAR_NIGHT,
}

/**
 * Instantaneous solar position with [azimuthDeg] strictly in `[0.0, 360.0)`.
 */
data class SolarPosition(
    val elevationDeg: Double,
    val azimuthDeg: Double,
)

/**
 * Daily NOAA solar and legal shooting light summary for a site and local date.
 */
data class SolarDayInfo(
    val date: LocalDate,
    val latitude: Double,
    val longitude: Double,
    val zoneId: ZoneId,
    val condition: SolarDayCondition,
    val civilDawn: ZonedDateTime?,
    val sunrise: ZonedDateTime?,
    val solarNoon: ZonedDateTime,
    val sunset: ZonedDateTime?,
    val civilDusk: ZonedDateTime?,
    val legalShootingStart: ZonedDateTime?,
    val legalShootingEnd: ZonedDateTime?,
    val daylightDuration: Duration,
)

/**
 * Pure-Kotlin implementation of the official NOAA Solar Calculation equations with
 * two-pass iterative refinement (< 1 minute accuracy at mid-latitudes) and DST-safe
 * [Instant]-to-[ZonedDateTime] conversion.
 */
object NoaaSolarCalculator {

    private const val SUNRISE_SUNSET_ZENITH_DEG = 90.833
    private const val CIVIL_TWILIGHT_ZENITH_DEG = 96.0
    private const val UNIX_EPOCH_JULIAN_DAY = 2_440_587.5
    private const val J2000_JULIAN_DAY = 2_451_545.0
    private const val DAYS_PER_JULIAN_CENTURY = 36_525.0
    private const val SECONDS_PER_DAY = 86_400.0
    private const val MILLIS_PER_DAY = 86_400_000.0

    fun calculateDay(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        zoneId: ZoneId,
        legalRule: LegalLightRule = LegalLightRule(),
    ): SolarDayInfo {
        validateCoordinates(latitude, longitude)

        val localNoonInstant = ZonedDateTime.of(date, LocalTime.NOON, zoneId).toInstant()
        val noonTransitInstant = refineSolarNoon(localNoonInstant, longitude)

        val sunriseInstant = refineCrossingEvent(
            anchorNoon = noonTransitInstant,
            latitude = latitude,
            longitude = longitude,
            zenithDeg = SUNRISE_SUNSET_ZENITH_DEG,
            isRising = true,
        )
        val sunsetInstant = refineCrossingEvent(
            anchorNoon = noonTransitInstant,
            latitude = latitude,
            longitude = longitude,
            zenithDeg = SUNRISE_SUNSET_ZENITH_DEG,
            isRising = false,
        )
        val civilDawnInstant = refineCrossingEvent(
            anchorNoon = noonTransitInstant,
            latitude = latitude,
            longitude = longitude,
            zenithDeg = CIVIL_TWILIGHT_ZENITH_DEG,
            isRising = true,
        )
        val civilDuskInstant = refineCrossingEvent(
            anchorNoon = noonTransitInstant,
            latitude = latitude,
            longitude = longitude,
            zenithDeg = CIVIL_TWILIGHT_ZENITH_DEG,
            isRising = false,
        )

        val noonParams = solarEphemeris(toJulianDay(noonTransitInstant))
        val noonCosHaSunrise = cosHourAngle(latitude, noonParams.declinationRad, SUNRISE_SUNSET_ZENITH_DEG)

        val condition = when {
            noonCosHaSunrise < -1.0 -> SolarDayCondition.POLAR_DAY
            noonCosHaSunrise > 1.0 -> SolarDayCondition.POLAR_NIGHT
            else -> SolarDayCondition.NORMAL
        }

        val daylightDuration = when {
            sunriseInstant != null && sunsetInstant != null ->
                Duration.between(sunriseInstant, sunsetInstant).coerceAtLeast(Duration.ZERO)
            condition == SolarDayCondition.POLAR_DAY -> Duration.ofHours(24)
            else -> Duration.ZERO
        }

        val sunriseZoned = sunriseInstant?.atZone(zoneId)
        val sunsetZoned = sunsetInstant?.atZone(zoneId)

        return SolarDayInfo(
            date = date,
            latitude = latitude,
            longitude = longitude,
            zoneId = zoneId,
            condition = condition,
            civilDawn = civilDawnInstant?.atZone(zoneId),
            sunrise = sunriseZoned,
            solarNoon = noonTransitInstant.atZone(zoneId),
            sunset = sunsetZoned,
            civilDusk = civilDuskInstant?.atZone(zoneId),
            legalShootingStart = sunriseZoned?.minusMinutes(legalRule.beforeSunriseMinutes),
            legalShootingEnd = sunsetZoned?.plusMinutes(legalRule.afterSunsetMinutes),
            daylightDuration = daylightDuration,
        )
    }

    /**
     * Computes topocentric apparent solar elevation and true-north azimuth at [instant].
     */
    fun calculatePosition(
        instant: Instant,
        latitude: Double,
        longitude: Double,
    ): SolarPosition {
        validateCoordinates(latitude, longitude)

        val jd = toJulianDay(instant)
        val ephemeris = solarEphemeris(jd)

        val utcMinutes = ((jd + 0.5) - floor(jd + 0.5)) * 1440.0
        val trueSolarTimeMin = ((utcMinutes + ephemeris.eqOfTimeMinutes + 4.0 * longitude) % 1440.0 + 1440.0) % 1440.0
        val hourAngleDeg = if (trueSolarTimeMin / 4.0 < 0.0) {
            trueSolarTimeMin / 4.0 + 180.0
        } else {
            trueSolarTimeMin / 4.0 - 180.0
        }

        val latRad = AngleMath.toRadians(latitude)
        val decRad = ephemeris.declinationRad
        val haRad = AngleMath.toRadians(hourAngleDeg)

        val cosZenith = (sin(latRad) * sin(decRad) + cos(latRad) * cos(decRad) * cos(haRad))
            .coerceIn(-1.0, 1.0)
        val zenithDeg = AngleMath.toDegrees(acos(cosZenith))
        val elevationDeg = 90.0 - zenithDeg

        val sinZenith = sin(AngleMath.toRadians(zenithDeg))
        val rawAzimuth = if (sinZenith < 1e-7) {
            180.0
        } else {
            val azDenom = cos(latRad) * sinZenith
            val cosAz = if (kotlin.math.abs(azDenom) < 1e-9) {
                0.0
            } else {
                ((sin(latRad) * cosZenith - sin(decRad)) / azDenom).coerceIn(-1.0, 1.0)
            }
            val azAngle = AngleMath.toDegrees(acos(cosAz))
            if (hourAngleDeg > 0.0) azAngle + 180.0 else 540.0 - azAngle
        }

        val atmosphericRefractionDeg = approximateSolarRefractionDeg(elevationDeg)
        return SolarPosition(
            elevationDeg = elevationDeg + atmosphericRefractionDeg,
            azimuthDeg = AngleMath.normalizeDegrees(rawAzimuth),
        )
    }

    internal fun toJulianDay(instant: Instant): Double =
        instant.epochSecond / SECONDS_PER_DAY +
            instant.nano / 86_400_000_000_000.0 +
            UNIX_EPOCH_JULIAN_DAY

    internal fun solarEphemeris(julianDay: Double): SolarEphemeris {
        val t = (julianDay - J2000_JULIAN_DAY) / DAYS_PER_JULIAN_CENTURY
        val geomMeanLongDeg = AngleMath.normalizeDegrees(
            280.46646 + t * (36000.76983 + t * 0.0003032),
        )
        val geomMeanAnomDeg = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val geomMeanAnomRad = AngleMath.toRadians(geomMeanAnomDeg)
        val eccent = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)

        val eqOfCenterDeg = sin(geomMeanAnomRad) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2.0 * geomMeanAnomRad) * (0.019993 - 0.000101 * t) +
            sin(3.0 * geomMeanAnomRad) * 0.000289

        val sunTrueLongDeg = geomMeanLongDeg + eqOfCenterDeg
        val omegaRad = AngleMath.toRadians(125.04 - 1934.136 * t)
        val sunAppLongDeg = sunTrueLongDeg - 0.00569 - 0.00478 * sin(omegaRad)

        val meanObliqDeg = 23.0 + (26.0 + (21.448 - t * (46.8150 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val obliqCorrDeg = meanObliqDeg + 0.00256 * cos(omegaRad)

        val obliqCorrRad = AngleMath.toRadians(obliqCorrDeg)
        val sunAppLongRad = AngleMath.toRadians(sunAppLongDeg)
        val geomMeanLongRad = AngleMath.toRadians(geomMeanLongDeg)

        val declinationRad = asin(sin(obliqCorrRad) * sin(sunAppLongRad))
        val tanHalfObliq = tan(obliqCorrRad / 2.0)
        val varY = tanHalfObliq * tanHalfObliq

        val eqOfTimeRad = varY * sin(2.0 * geomMeanLongRad) -
            2.0 * eccent * sin(geomMeanAnomRad) +
            4.0 * eccent * varY * sin(geomMeanAnomRad) * cos(2.0 * geomMeanLongRad) -
            0.5 * varY * varY * sin(4.0 * geomMeanLongRad) -
            1.25 * eccent * eccent * sin(2.0 * geomMeanAnomRad)
        val eqOfTimeMinutes = 4.0 * AngleMath.toDegrees(eqOfTimeRad)

        val sunRightAscensionRad = atan2(
            cos(obliqCorrRad) * sin(sunAppLongRad),
            cos(sunAppLongRad),
        )

        return SolarEphemeris(
            apparentLongitudeDeg = AngleMath.normalizeDegrees(sunAppLongDeg),
            declinationRad = declinationRad,
            rightAscensionRad = sunRightAscensionRad,
            eqOfTimeMinutes = eqOfTimeMinutes,
        )
    }

    private fun refineSolarNoon(localNoonInstant: Instant, longitude: Double): Instant {
        val baseJd = toJulianDay(localNoonInstant)
        val utcMidnightJd = floor(baseJd + 0.5) - 0.5
        val pass1Params = solarEphemeris(baseJd)
        val guessMin = 720.0 - 4.0 * longitude - pass1Params.eqOfTimeMinutes
        var noonJd = utcMidnightJd + guessMin / 1440.0

        // Snap to the solar transit closest to local civil noon (within +/- 12h) for extreme zones.
        while (noonJd - baseJd > 0.5) noonJd -= 1.0
        while (noonJd - baseJd < -0.5) noonJd += 1.0

        val pass2Params = solarEphemeris(noonJd)
        val refinedMin = 720.0 - 4.0 * longitude - pass2Params.eqOfTimeMinutes
        val anchorMidnightJd = floor(noonJd + 0.5) - 0.5
        var refinedNoonJd = anchorMidnightJd + refinedMin / 1440.0
        while (refinedNoonJd - baseJd > 0.5) refinedNoonJd -= 1.0
        while (refinedNoonJd - baseJd < -0.5) refinedNoonJd += 1.0

        return julianDayToInstant(refinedNoonJd)
    }

    private fun refineCrossingEvent(
        anchorNoon: Instant,
        latitude: Double,
        longitude: Double,
        zenithDeg: Double,
        isRising: Boolean,
    ): Instant? {
        val anchorJd = toJulianDay(anchorNoon)
        val anchorMidnightJd = floor(anchorJd + 0.5) - 0.5

        val pass1Params = solarEphemeris(anchorJd)
        val cosHa1 = cosHourAngle(latitude, pass1Params.declinationRad, zenithDeg)
        if (cosHa1 < -1.0 || cosHa1 > 1.0) return null

        val ha1Deg = AngleMath.toDegrees(acos(cosHa1))
        val signedHa1 = if (isRising) -ha1Deg else ha1Deg
        val minutes1 = 720.0 - 4.0 * (longitude - signedHa1) - pass1Params.eqOfTimeMinutes
        var eventJd = anchorMidnightJd + minutes1 / 1440.0
        while (eventJd - anchorJd > 0.75) eventJd -= 1.0
        while (eventJd - anchorJd < -0.75) eventJd += 1.0

        val pass2Params = solarEphemeris(eventJd)
        val cosHa2 = cosHourAngle(latitude, pass2Params.declinationRad, zenithDeg)
        if (cosHa2 < -1.0 || cosHa2 > 1.0) return null

        val ha2Deg = AngleMath.toDegrees(acos(cosHa2))
        val signedHa2 = if (isRising) -ha2Deg else ha2Deg
        val minutes2 = 720.0 - 4.0 * (longitude - signedHa2) - pass2Params.eqOfTimeMinutes
        val midnightForEvent = floor(eventJd + 0.5) - 0.5
        var refinedEventJd = midnightForEvent + minutes2 / 1440.0
        while (refinedEventJd - anchorJd > 0.75) refinedEventJd -= 1.0
        while (refinedEventJd - anchorJd < -0.75) refinedEventJd += 1.0

        return julianDayToInstant(refinedEventJd)
    }

    private fun cosHourAngle(latitudeDeg: Double, declinationRad: Double, zenithDeg: Double): Double {
        val latRad = AngleMath.toRadians(latitudeDeg)
        val cosZenith = cos(AngleMath.toRadians(zenithDeg))
        val denom = cos(latRad) * cos(declinationRad)
        if (kotlin.math.abs(denom) < 1e-12) {
            return if (sin(latRad) * sin(declinationRad) >= cosZenith) -2.0 else 2.0
        }
        return (cosZenith - sin(latRad) * sin(declinationRad)) / denom
    }

    private fun julianDayToInstant(julianDay: Double): Instant {
        val millisSinceEpoch = ((julianDay - UNIX_EPOCH_JULIAN_DAY) * MILLIS_PER_DAY).roundToLong()
        return Instant.ofEpochMilli(millisSinceEpoch)
    }

    private fun approximateSolarRefractionDeg(elevationDeg: Double): Double {
        if (elevationDeg > 85.0) return 0.0
        val te = tan(AngleMath.toRadians(elevationDeg))
        val arcSeconds = when {
            elevationDeg > 5.0 -> 58.1 / te - 0.07 / (te * te * te) + 0.000086 / (te * te * te * te * te)
            elevationDeg > -0.575 -> 1735.0 + elevationDeg * (-518.2 + elevationDeg * (103.4 + elevationDeg * (-12.79 + elevationDeg * 0.711)))
            else -> -20.772 / te
        }
        return arcSeconds / 3600.0
    }

    private fun validateCoordinates(latitude: Double, longitude: Double) {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "Latitude must be in [-90.0, 90.0], was: $latitude"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "Longitude must be in [-180.0, 180.0], was: $longitude"
        }
    }

    internal data class SolarEphemeris(
        val apparentLongitudeDeg: Double,
        val declinationRad: Double,
        val rightAscensionRad: Double,
        val eqOfTimeMinutes: Double,
    )
}

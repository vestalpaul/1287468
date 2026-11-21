package com.buckcast.domain.astronomy

import com.buckcast.domain.math.AngleMath
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class MoonPhase(val displayName: String) {
    NEW_MOON("New Moon"),
    WAXING_CRESCENT("Waxing Crescent"),
    FIRST_QUARTER("First Quarter"),
    WAXING_GIBBOUS("Waxing Gibbous"),
    FULL_MOON("Full Moon"),
    WANING_GIBBOUS("Waning Gibbous"),
    LAST_QUARTER("Last Quarter"),
    WANING_CRESCENT("Waning Crescent"),
}

/**
 * Daily lunar illumination, synodic phase, and rise/set/transit summary for a local civil date.
 */
data class LunarDayInfo(
    val date: LocalDate,
    val latitude: Double,
    val longitude: Double,
    val zoneId: ZoneId,
    val phaseFraction: Double,
    val illuminationPercent: Double,
    val lunarAgeDays: Double,
    val phase: MoonPhase,
    val moonrise: ZonedDateTime?,
    val moonset: ZonedDateTime?,
    val moonOverhead: ZonedDateTime?,
    val moonUnderfoot: ZonedDateTime?,
)

/**
 * Pure-Kotlin lunar ephemeris calculator based on Meeus (Chapter 47 & 48) periodic series.
 * Evaluates illumination/phase at local solar noon and locates moonrise, moonset, overhead,
 * and underfoot transits by sampling the 24-hour local civil day with bisection refinement.
 */
object LunarCalculator {

    private const val SYNODIC_MONTH_DAYS = 29.530588853
    private const val DELTA_T_DAYS = 69.2 / 86_400.0
    private const val J2000_JULIAN_DAY = 2_451_545.0
    private const val DAYS_PER_JULIAN_CENTURY = 36_525.0
    private const val EARTH_RADIUS_KM = 6_378.14
    private const val SUN_DISTANCE_KM = 149_598_000.0
    private const val REFRACTION_HORIZON_DEG = 0.5667
    private const val SEMIDIAMETER_PARALLAX_RATIO = 0.272481
    private const val SCAN_STEP_SECONDS = 1_200L // 20-minute intervals across the civil day
    private const val BISECTION_ITERATIONS = 16

    fun calculateDay(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        zoneId: ZoneId,
    ): LunarDayInfo {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "Latitude must be in [-90.0, 90.0], was: $latitude"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "Longitude must be in [-180.0, 180.0], was: $longitude"
        }

        val dayStart = date.atStartOfDay(zoneId)
        val dayEnd = date.plusDays(1).atStartOfDay(zoneId)
        val localNoonInstant = dayStart.toInstant().plusSeconds(
            (dayEnd.toInstant().epochSecond - dayStart.toInstant().epochSecond) / 2L,
        )

        val noonPhase = calculatePhaseAt(localNoonInstant)

        val startSec = dayStart.toInstant().epochSecond
        val endSec = dayEnd.toInstant().epochSecond

        var moonrise: Instant? = null
        var moonset: Instant? = null
        var overhead: Instant? = null
        var underfoot: Instant? = null

        var t0 = startSec
        var alt0 = horizonMarginDeg(Instant.ofEpochSecond(t0), latitude, longitude)
        var ha0 = localHourAngleDeg(Instant.ofEpochSecond(t0), longitude)

        while (t0 < endSec) {
            val t1 = minOf(t0 + SCAN_STEP_SECONDS, endSec)
            val inst1 = Instant.ofEpochSecond(t1)
            val alt1 = horizonMarginDeg(inst1, latitude, longitude)
            val ha1 = localHourAngleDeg(inst1, longitude)

            if (moonrise == null && alt0 < 0.0 && alt1 >= 0.0) {
                moonrise = bisectCrossing(t0, t1) { sec ->
                    horizonMarginDeg(Instant.ofEpochSecond(sec), latitude, longitude)
                }
            }
            if (moonset == null && alt0 >= 0.0 && alt1 < 0.0) {
                moonset = bisectCrossing(t0, t1) { sec ->
                    horizonMarginDeg(Instant.ofEpochSecond(sec), latitude, longitude)
                }
            }

            // Upper meridian transit (moon overhead): hour angle crosses 0° from negative (-180..0) to positive (0..180)
            if (overhead == null && ha0 < 0.0 && ha1 >= 0.0 && (ha1 - ha0) < 90.0) {
                overhead = bisectCrossing(t0, t1) { sec ->
                    localHourAngleDeg(Instant.ofEpochSecond(sec), longitude)
                }
            }

            // Lower meridian transit (moon underfoot): hour angle wraps from +180° to -180°
            if (underfoot == null && ha0 > 90.0 && ha1 < -90.0) {
                underfoot = bisectCrossing(t0, t1) { sec ->
                    val ha = localHourAngleDeg(Instant.ofEpochSecond(sec), longitude)
                    if (ha > 0.0) ha - 180.0 else ha + 180.0
                }
            }

            t0 = t1
            alt0 = alt1
            ha0 = ha1
        }

        return LunarDayInfo(
            date = date,
            latitude = latitude,
            longitude = longitude,
            zoneId = zoneId,
            phaseFraction = noonPhase.phaseFraction,
            illuminationPercent = noonPhase.illuminationPercent,
            lunarAgeDays = noonPhase.lunarAgeDays,
            phase = noonPhase.phase,
            moonrise = moonrise?.atZone(zoneId),
            moonset = moonset?.atZone(zoneId),
            moonOverhead = overhead?.atZone(zoneId),
            moonUnderfoot = underfoot?.atZone(zoneId),
        )
    }

    /**
     * Computes instantaneous lunar phase fraction (`[0.0, 1.0)`), illumination (`[0.0, 100.0]`),
     * lunar age in days, and [MoonPhase] classification at [instant].
     */
    fun calculatePhaseAt(instant: Instant): LunarPhaseSnapshot {
        val jd = NoaaSolarCalculator.toJulianDay(instant)
        val moon = geocentricPosition(jd)
        val sun = NoaaSolarCalculator.solarEphemeris(jd + DELTA_T_DAYS)

        val elongationDeg = AngleMath.normalizeDegrees(moon.eclipticLongitudeDeg - sun.apparentLongitudeDeg)
        val phaseFraction = elongationDeg / 360.0
        val lunarAgeDays = phaseFraction * SYNODIC_MONTH_DAYS

        val moonLatRad = AngleMath.toRadians(moon.eclipticLatitudeDeg)
        val dLonRad = AngleMath.toRadians(moon.eclipticLongitudeDeg - sun.apparentLongitudeDeg)
        val cosPsi = (cos(moonLatRad) * cos(dLonRad)).coerceIn(-1.0, 1.0)
        val psi = kotlin.math.acos(cosPsi)
        val phaseAngleRad = atan2(
            SUN_DISTANCE_KM * sin(psi),
            moon.distanceKm - SUN_DISTANCE_KM * cosPsi,
        )
        val illuminationPercent = (50.0 * (1.0 + cos(phaseAngleRad))).coerceIn(0.0, 100.0)

        return LunarPhaseSnapshot(
            phaseFraction = phaseFraction,
            illuminationPercent = illuminationPercent,
            lunarAgeDays = lunarAgeDays,
            phase = classifyPhase(phaseFraction),
        )
    }

    private fun classifyPhase(phaseFraction: Double): MoonPhase {
        val index = (((phaseFraction * 8.0) + 0.5).toInt()) % 8
        return MoonPhase.entries[index]
    }

    private fun horizonMarginDeg(instant: Instant, latitude: Double, longitude: Double): Double {
        val jd = NoaaSolarCalculator.toJulianDay(instant)
        val moon = geocentricPosition(jd)
        val gmstDeg = greenwichMeanSiderealTimeDeg(jd)
        val lstDeg = AngleMath.normalizeDegrees(gmstDeg + longitude)
        val hourAngleRad = AngleMath.toRadians(AngleMath.signedDeltaDegrees(moon.rightAscensionDeg, lstDeg))

        val latRad = AngleMath.toRadians(latitude)
        val decRad = moon.declinationRad
        val sinAlt = (sin(latRad) * sin(decRad) + cos(latRad) * cos(decRad) * cos(hourAngleRad))
            .coerceIn(-1.0, 1.0)
        val geocentricAltDeg = AngleMath.toDegrees(asin(sinAlt))

        val parallaxDeg = AngleMath.toDegrees(asin((EARTH_RADIUS_KM / moon.distanceKm).coerceIn(-1.0, 1.0)))
        val semidiameterDeg = SEMIDIAMETER_PARALLAX_RATIO * parallaxDeg
        val criticalGeocentricAltDeg = parallaxDeg - REFRACTION_HORIZON_DEG - semidiameterDeg

        return geocentricAltDeg - criticalGeocentricAltDeg
    }

    private fun localHourAngleDeg(instant: Instant, longitude: Double): Double {
        val jd = NoaaSolarCalculator.toJulianDay(instant)
        val moon = geocentricPosition(jd)
        val lstDeg = AngleMath.normalizeDegrees(greenwichMeanSiderealTimeDeg(jd) + longitude)
        return AngleMath.signedDeltaDegrees(moon.rightAscensionDeg, lstDeg)
    }

    private fun bisectCrossing(
        startSec: Long,
        endSec: Long,
        evaluator: (Long) -> Double,
    ): Instant {
        var lo = startSec
        var hi = endSec
        val vLo = evaluator(lo)
        repeat(BISECTION_ITERATIONS) {
            if (hi - lo <= 1L) return Instant.ofEpochSecond((lo + hi) / 2L)
            val mid = (lo + hi) / 2L
            val vMid = evaluator(mid)
            if ((vLo <= 0.0 && vMid <= 0.0) || (vLo > 0.0 && vMid > 0.0)) {
                lo = mid
            } else {
                hi = mid
            }
        }
        return Instant.ofEpochSecond((lo + hi) / 2L)
    }

    private fun greenwichMeanSiderealTimeDeg(julianDay: Double): Double {
        val t = (julianDay - J2000_JULIAN_DAY) / DAYS_PER_JULIAN_CENTURY
        val theta = 280.46061837 +
            360.98564736629 * (julianDay - J2000_JULIAN_DAY) +
            0.000387933 * t * t -
            (t * t * t) / 38_710_000.0
        return AngleMath.normalizeDegrees(theta)
    }

    private fun geocentricPosition(julianDayUt: Double): LunarPosition {
        val jde = julianDayUt + DELTA_T_DAYS
        val t = (jde - J2000_JULIAN_DAY) / DAYS_PER_JULIAN_CENTURY
        val t2 = t * t
        val t3 = t2 * t
        val t4 = t3 * t

        val lp = AngleMath.normalizeDegrees(
            218.3164477 + 481_267.88123421 * t - 0.0015786 * t2 + t3 / 538_841.0 - t4 / 65_194_000.0,
        )
        val d = AngleMath.normalizeDegrees(
            297.8501921 + 445_267.1114034 * t - 0.0018819 * t2 + t3 / 545_868.0 - t4 / 113_065_000.0,
        )
        val m = AngleMath.normalizeDegrees(
            357.5291092 + 35_999.0502909 * t - 0.0001536 * t2 + t3 / 24_490_000.0,
        )
        val mp = AngleMath.normalizeDegrees(
            134.9633964 + 477_198.8675055 * t + 0.0087414 * t2 + t3 / 69_699.0 - t4 / 14_712_000.0,
        )
        val f = AngleMath.normalizeDegrees(
            93.2720950 + 483_202.0175233 * t - 0.0036539 * t2 - t3 / 3_526_000.0 + t4 / 863_310_000.0,
        )

        val a1 = AngleMath.toRadians(119.75 + 131.849 * t)
        val a2 = AngleMath.toRadians(53.09 + 479_264.290 * t)
        val a3 = AngleMath.toRadians(313.45 + 481_266.484 * t)

        val e = 1.0 - 0.002516 * t - 0.0000074 * t2
        val e2 = e * e

        val dRad = AngleMath.toRadians(d)
        val mRad = AngleMath.toRadians(m)
        val mpRad = AngleMath.toRadians(mp)
        val fRad = AngleMath.toRadians(f)
        val lpRad = AngleMath.toRadians(lp)

        var sumL = 0.0
        var sumR = 0.0
        for (term in LONGITUDE_DISTANCE_TERMS) {
            val arg = term.d * dRad + term.m * mRad + term.mp * mpRad + term.f * fRad
            val eFactor = when (abs(term.m)) {
                1 -> e
                2 -> e2
                else -> 1.0
            }
            if (term.lonMicroDeg != 0.0) {
                sumL += term.lonMicroDeg * eFactor * sin(arg)
            }
            if (term.distMeters != 0.0) {
                sumR += term.distMeters * eFactor * cos(arg)
            }
        }
        sumL += 3958.0 * sin(a1) + 1962.0 * sin(lpRad - fRad) + 318.0 * sin(a2)

        var sumB = 0.0
        for (term in LATITUDE_TERMS) {
            val arg = term.d * dRad + term.m * mRad + term.mp * mpRad + term.f * fRad
            val eFactor = when (abs(term.m)) {
                1 -> e
                2 -> e2
                else -> 1.0
            }
            sumB += term.latMicroDeg * eFactor * sin(arg)
        }
        sumB += -2235.0 * sin(lpRad) +
            382.0 * sin(a3) +
            175.0 * sin(a1 - fRad) +
            175.0 * sin(a1 + fRad) +
            127.0 * sin(lpRad - mpRad) -
            115.0 * sin(lpRad + mpRad)

        val lambdaDeg = AngleMath.normalizeDegrees(lp + sumL / 1_000_000.0)
        val betaDeg = sumB / 1_000_000.0
        val distanceKm = 385_000.56 + sumR / 1_000.0

        val omegaDeg = 125.04452 - 1934.136261 * t
        val meanObliqDeg = 23.0 + (26.0 + (21.448 - t * (46.8150 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val nutObliqDeg = 0.00256 * cos(AngleMath.toRadians(omegaDeg))
        val obliqRad = AngleMath.toRadians(meanObliqDeg + nutObliqDeg)

        val lambdaRad = AngleMath.toRadians(lambdaDeg)
        val betaRad = AngleMath.toRadians(betaDeg)

        val raRad = atan2(
            sin(lambdaRad) * cos(obliqRad) - kotlin.math.tan(betaRad) * sin(obliqRad),
            cos(lambdaRad),
        )
        val decRad = asin(
            (sin(betaRad) * cos(obliqRad) + cos(betaRad) * sin(obliqRad) * sin(lambdaRad))
                .coerceIn(-1.0, 1.0),
        )

        return LunarPosition(
            eclipticLongitudeDeg = lambdaDeg,
            eclipticLatitudeDeg = betaDeg,
            distanceKm = distanceKm,
            rightAscensionDeg = AngleMath.normalizeDegrees(AngleMath.toDegrees(raRad)),
            declinationRad = decRad,
        )
    }

    private data class LonDistTerm(
        val d: Int,
        val m: Int,
        val mp: Int,
        val f: Int,
        val lonMicroDeg: Double,
        val distMeters: Double,
    )

    private data class LatTerm(
        val d: Int,
        val m: Int,
        val mp: Int,
        val f: Int,
        val latMicroDeg: Double,
    )

    private data class LunarPosition(
        val eclipticLongitudeDeg: Double,
        val eclipticLatitudeDeg: Double,
        val distanceKm: Double,
        val rightAscensionDeg: Double,
        val declinationRad: Double,
    )

    private val LONGITUDE_DISTANCE_TERMS = listOf(
        LonDistTerm(0, 0, 1, 0, 6_288_774.0, -20_905_355.0),
        LonDistTerm(2, 0, -1, 0, 1_274_027.0, -3_699_111.0),
        LonDistTerm(2, 0, 0, 0, 658_314.0, -2_955_968.0),
        LonDistTerm(0, 0, 2, 0, 213_618.0, -569_925.0),
        LonDistTerm(0, 1, 0, 0, -185_116.0, 48_888.0),
        LonDistTerm(0, 0, 0, 2, -114_332.0, -3_149.0),
        LonDistTerm(2, 0, -2, 0, 58_793.0, 246_158.0),
        LonDistTerm(2, -1, -1, 0, 57_066.0, -152_138.0),
        LonDistTerm(2, 0, 1, 0, 53_322.0, -170_733.0),
        LonDistTerm(2, -1, 0, 0, 45_758.0, -204_586.0),
        LonDistTerm(0, 1, -1, 0, -40_923.0, -129_620.0),
        LonDistTerm(1, 0, 0, 0, -34_720.0, 108_743.0),
        LonDistTerm(0, 1, 1, 0, -30_383.0, 104_755.0),
        LonDistTerm(2, 0, 0, -2, 15_327.0, 10_321.0),
        LonDistTerm(0, 0, 1, 2, -12_528.0, 0.0),
        LonDistTerm(0, 0, 1, -2, 10_980.0, 79_661.0),
        LonDistTerm(4, 0, -1, 0, 10_675.0, -34_782.0),
        LonDistTerm(0, 0, 3, 0, 10_034.0, -23_210.0),
        LonDistTerm(4, 0, -2, 0, 8_548.0, -21_636.0),
        LonDistTerm(2, 1, -1, 0, -7_888.0, 24_208.0),
        LonDistTerm(2, 1, 0, 0, -6_766.0, 30_824.0),
        LonDistTerm(1, 0, -1, 0, -5_163.0, -8_379.0),
        LonDistTerm(1, 1, 0, 0, 4_987.0, -16_675.0),
        LonDistTerm(2, -1, 1, 0, 4_036.0, -12_831.0),
        LonDistTerm(2, 0, 2, 0, 3_994.0, -10_445.0),
        LonDistTerm(4, 0, 0, 0, 3_861.0, -11_650.0),
        LonDistTerm(2, 0, -3, 0, 3_665.0, 14_403.0),
        LonDistTerm(0, 1, -2, 0, -2_689.0, -7_003.0),
        LonDistTerm(2, 0, -1, 2, -2_602.0, 0.0),
        LonDistTerm(2, -1, -2, 0, 2_390.0, 10_056.0),
    )

    private val LATITUDE_TERMS = listOf(
        LatTerm(0, 0, 0, 1, 5_128_122.0),
        LatTerm(0, 0, 1, 1, 280_602.0),
        LatTerm(0, 0, 1, -1, 277_693.0),
        LatTerm(2, 0, 0, -1, 173_237.0),
        LatTerm(2, 0, -1, 1, 55_413.0),
        LatTerm(2, 0, -1, -1, 46_271.0),
        LatTerm(2, 0, 0, 1, 32_573.0),
        LatTerm(0, 0, 2, 1, 17_198.0),
        LatTerm(2, 0, 1, -1, 9_266.0),
        LatTerm(0, 0, 2, -1, 8_822.0),
        LatTerm(2, -1, 0, -1, 8_216.0),
        LatTerm(2, 0, -2, -1, 4_324.0),
        LatTerm(2, 0, 1, 1, 4_200.0),
        LatTerm(2, 1, 0, -1, -3_359.0),
        LatTerm(2, -1, -1, 1, 2_463.0),
        LatTerm(2, -1, 0, 1, 2_211.0),
        LatTerm(2, -1, -1, -1, 2_065.0),
        LatTerm(0, 1, -1, -1, -1_870.0),
        LatTerm(4, 0, -1, -1, 1_828.0),
        LatTerm(0, 1, 0, 1, -1_794.0),
    )
}

data class LunarPhaseSnapshot(
    val phaseFraction: Double,
    val illuminationPercent: Double,
    val lunarAgeDays: Double,
    val phase: MoonPhase,
)

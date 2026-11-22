package com.buckcast.domain.scoring

import com.buckcast.domain.rut.RutDayAssessment
import java.util.Locale
import kotlin.math.abs

enum class HuntSession {
    AM,
    PM,
}

enum class MovementRating(val label: String) {
    SLOW("SLOW"),
    OKAY("OKAY"),
    GOOD("GOOD"),
    GREAT("GREAT");

    companion object {
        fun fromScore(score: Double): MovementRating = when {
            score >= 80.0 -> GREAT
            score >= 60.0 -> GOOD
            score >= 40.0 -> OKAY
            else -> SLOW
        }
    }
}

enum class MovementFactor(
    val displayName: String,
    val weight: Double,
) {
    BAROMETRIC_PRESSURE("Barometric Pressure & Trend", 0.25),
    TEMPERATURE_DELTA("Temperature vs. 7d Mean", 0.25),
    RUT_PHASE("Rut Phase", 0.20),
    WIND("Wind Speed & Gusts", 0.15),
    PRECIPITATION("Precipitation", 0.15),
}

enum class PrecipType {
    NONE,
    DRIZZLE,
    LIGHT_RAIN,
    HEAVY_RAIN,
    SNOW,
    SLEET_FREEZING_RAIN,
}

/**
 * Transparent US-imperial input model for one AM or PM hunting session.
 */
data class SessionWeatherInput(
    val session: HuntSession,
    val barometricPressureInHg: Double,
    val pressureTrend3hInHg: Double,
    val tempDegF: Double,
    val sevenDayMeanTempDegF: Double,
    val rutAssessment: RutDayAssessment,
    val windSpeedMph: Double,
    val windGustMph: Double? = null,
    val precipInchesPerHr: Double = 0.0,
    val precipType: PrecipType = PrecipType.NONE,
    val hoursSincePrecipEnded: Double? = null,
) {
    init {
        require(barometricPressureInHg.isFinite() && barometricPressureInHg in 25.0..33.0) {
            "Barometric pressure (inHg) out of range: $barometricPressureInHg"
        }
        require(pressureTrend3hInHg.isFinite()) { "Pressure trend must be finite: $pressureTrend3hInHg" }
        require(tempDegF.isFinite() && tempDegF in -60.0..130.0) {
            "Temperature (degF) out of range: $tempDegF"
        }
        require(sevenDayMeanTempDegF.isFinite() && sevenDayMeanTempDegF in -60.0..130.0) {
            "7-day mean temp (degF) out of range: $sevenDayMeanTempDegF"
        }
        require(windSpeedMph.isFinite() && windSpeedMph >= 0.0) {
            "Wind speed (mph) must be non-negative: $windSpeedMph"
        }
        if (windGustMph != null) {
            require(windGustMph.isFinite() && windGustMph >= 0.0) {
                "Wind gust (mph) must be non-negative: $windGustMph"
            }
        }
        require(precipInchesPerHr.isFinite() && precipInchesPerHr >= 0.0) {
            "Precip rate (in/hr) must be non-negative: $precipInchesPerHr"
        }
        if (hoursSincePrecipEnded != null) {
            require(hoursSincePrecipEnded.isFinite() && hoursSincePrecipEnded >= 0.0) {
                "hoursSincePrecipEnded must be non-negative: $hoursSincePrecipEnded"
            }
        }
    }
}

/**
 * Single factor contribution inside a transparent [MovementScoreReport].
 */
data class FactorBreakdown(
    val factor: MovementFactor,
    val rawScore: Double,
    val weight: Double,
    val weightedContribution: Double,
    val summary: String,
    val reason: String,
)

/**
 * Complete, auditable 5-factor movement score report for one hunting session.
 */
data class MovementScoreReport(
    val session: HuntSession,
    val overallScore: Double,
    val rating: MovementRating,
    val factors: List<FactorBreakdown>,
)

/**
 * Paired AM and PM daily forecast comparison.
 */
data class DailyMovementForecast(
    val amReport: MovementScoreReport,
    val pmReport: MovementScoreReport,
    val recommendedSession: HuntSession,
    val scoreDelta: Double,
)

/**
 * Transparent 5-Factor Whitetail AM/PM Movement Scorer:
 * - Barometric Pressure & 3h Trend: 25%
 * - Temperature vs. 7-Day Mean: 25%
 * - Rut Phase: 20%
 * - Wind Speed & Gusts: 15%
 * - Precipitation: 15%
 */
object MovementScorer {

    fun evaluateSession(input: SessionWeatherInput): MovementScoreReport {
        val breakdowns = listOf(
            scoreBarometricPressure(input),
            scoreTemperatureDelta(input),
            scoreRutPhase(input),
            scoreWind(input),
            scorePrecipitation(input),
        )
        val total = breakdowns.sumOf { it.weightedContribution }.coerceIn(0.0, 100.0)
        return MovementScoreReport(
            session = input.session,
            overallScore = total,
            rating = MovementRating.fromScore(total),
            factors = breakdowns,
        )
    }

    fun evaluateDay(
        amInput: SessionWeatherInput,
        pmInput: SessionWeatherInput,
    ): DailyMovementForecast {
        require(amInput.session == HuntSession.AM) { "amInput must use HuntSession.AM" }
        require(pmInput.session == HuntSession.PM) { "pmInput must use HuntSession.PM" }
        val am = evaluateSession(amInput)
        val pm = evaluateSession(pmInput)
        val best = if (pm.overallScore >= am.overallScore) HuntSession.PM else HuntSession.AM
        return DailyMovementForecast(
            amReport = am,
            pmReport = pm,
            recommendedSession = best,
            scoreDelta = abs(am.overallScore - pm.overallScore),
        )
    }

    private fun scoreBarometricPressure(input: SessionWeatherInput): FactorBreakdown {
        val p = input.barometricPressureInHg
        val trend = input.pressureTrend3hInHg

        val basePressureScore = when {
            p >= 30.25 -> 92.0
            p >= 30.10 -> 84.0
            p >= 29.95 -> 68.0
            p >= 29.80 -> 50.0
            p >= 29.65 -> 35.0
            else -> 22.0
        }

        val (trendBonus, reason) = when {
            trend >= 0.08 -> 14.0 to "Post-front high pressure surging rapidly"
            trend >= 0.04 -> 12.0 to "Post-front rising barometer boosts daytime feeding"
            trend <= -0.09 -> 24.0 to "Steep pre-front pressure plunge triggers heavy pre-storm movement"
            trend <= -0.05 -> 20.0 to "Rapid pre-front drop spurs deer onto their feet ahead of front"
            trend <= -0.02 -> 8.0 to "Mildly falling barometer supports moderate pre-front activity"
            trend >= 0.015 -> 5.0 to "Slowly stabilizing pressure"
            else -> 0.0 to if (p < 29.75) "Stagnant low pressure suppresses daylight travel" else "Steady barometer"
        }

        val raw = when {
            trend <= -0.05 -> maxOf(basePressureScore + trendBonus, 85.0)
            trend >= 0.04 -> maxOf(basePressureScore + trendBonus, if (p >= 30.00) 88.0 else 82.0)
            else -> basePressureScore + trendBonus
        }.coerceIn(0.0, 100.0)

        val trendSign = if (trend >= 0.0) "+" else ""
        val summary = String.format(Locale.US, "%.2f inHg (%s%.2f/3h)", p, trendSign, trend)
        return buildBreakdown(MovementFactor.BAROMETRIC_PRESSURE, raw, summary, reason)
    }

    private fun scoreTemperatureDelta(input: SessionWeatherInput): FactorBreakdown {
        val delta = input.tempDegF - input.sevenDayMeanTempDegF
        val temp = input.tempDegF

        val deltaScore = when {
            delta <= -15.0 -> 98.0
            delta <= -10.0 -> 92.0 + (-10.0 - delta) * 1.2
            delta <= -5.0 -> 78.0 + (-5.0 - delta) * 2.8
            delta <= 0.0 -> 55.0 + (-delta) * 4.6
            delta <= 5.0 -> 55.0 - delta * 4.5
            delta <= 12.0 -> 32.5 - (delta - 5.0) * 2.5
            else -> 15.0 - ((delta - 12.0) * 0.5).coerceAtMost(7.0)
        }

        val heatPenalty = when {
            temp >= 85.0 -> 30.0
            temp >= 75.0 -> 18.0 + (temp - 75.0) * 1.2
            temp > 68.0 -> (temp - 68.0) * 2.5
            else -> 0.0
        }

        val raw = (deltaScore - heatPenalty).coerceIn(0.0, 100.0)
        val deltaSign = if (delta >= 0.0) "+" else ""
        val summary = String.format(Locale.US, "%.0f°F (%s%.0f°F vs 7d mean)", temp, deltaSign, delta)
        val reason = when {
            heatPenalty >= 15.0 -> "Warm daytime temperatures limit movement to last light"
            delta <= -10.0 -> "Sharp cold front well below 7-day average drives heavy daylight travel"
            delta <= -4.0 -> "Cooler-than-average air improves thermal comfort for moving deer"
            delta >= 8.0 -> "Unseasonably warm surge slows daytime activity"
            else -> "Temperatures near 7-day seasonal norm"
        }
        return buildBreakdown(MovementFactor.TEMPERATURE_DELTA, raw, summary, reason)
    }

    private fun scoreRutPhase(input: SessionWeatherInput): FactorBreakdown {
        val rut = input.rutAssessment
        val raw = when (input.session) {
            HuntSession.AM -> rut.amBaselineScore
            HuntSession.PM -> rut.pmBaselineScore
        }.coerceIn(0.0, 100.0)

        val phase = rut.phase
        val summary = if (phase == null) {
            "Off-Season"
        } else {
            "Phase ${phase.number}: ${phase.phaseName} (${phase.biologicalName})"
        }
        val reason = phase?.tacticalFocus ?: "Outside active Midwest rut calendar window"
        return buildBreakdown(MovementFactor.RUT_PHASE, raw, summary, reason)
    }

    private fun scoreWind(input: SessionWeatherInput): FactorBreakdown {
        val speed = input.windSpeedMph
        val gust = maxOf(input.windGustMph ?: speed, speed)
        val gustSpread = gust - speed

        val baseScore = when {
            speed < 3.0 -> 72.0 + speed * 3.0
            speed <= 10.0 -> 96.0 - abs(speed - 6.5) * 1.2
            speed <= 16.0 -> 86.0 - (speed - 10.0) * 4.5
            speed <= 24.0 -> 59.0 - (speed - 16.0) * 4.2
            else -> (25.4 - (speed - 24.0) * 1.8).coerceAtLeast(5.0)
        }

        val gustPenalty = when {
            gustSpread > 14.0 -> 18.0
            gustSpread > 8.0 -> (gustSpread - 8.0) * 2.5
            else -> 0.0
        }

        val raw = (baseScore - gustPenalty).coerceIn(0.0, 100.0)
        val summary = if (input.windGustMph != null && gustSpread >= 3.0) {
            String.format(Locale.US, "%.0f mph (gusts %.0f mph)", speed, gust)
        } else {
            String.format(Locale.US, "%.0f mph", speed)
        }
        val reason = when {
            speed < 3.0 -> "Near-calm winds increase swirling risk in tight cover"
            speed <= 10.0 && gustPenalty == 0.0 -> "Steady light breeze gives clean, predictable scent control"
            speed <= 16.0 -> "Moderate wind keeps scent crisp but increases sensory caution"
            speed <= 24.0 -> "Strong wind pushes deer into sheltered leeward timber and bedding"
            else -> "High wind / gusts suppress open movement and bed deer tight"
        }
        return buildBreakdown(MovementFactor.WIND, raw, summary, reason)
    }

    private fun scorePrecipitation(input: SessionWeatherInput): FactorBreakdown {
        val rate = input.precipInchesPerHr
        val type = input.precipType
        val sinceEnded = input.hoursSincePrecipEnded

        val (raw, reason) = when {
            type == PrecipType.NONE || rate <= 0.001 -> {
                if (sinceEnded != null && sinceEnded <= 2.0) {
                    96.0 to "Post-rain clearing triggers immediate feeding and scent checking"
                } else {
                    85.0 to "Dry conditions support unobstructed movement"
                }
            }
            type == PrecipType.SNOW -> {
                if (rate <= 0.08) {
                    92.0 to "Light snow spurs active feeding and travel"
                } else {
                    52.0 to "Heavy snow slows long-distance travel"
                }
            }
            type == PrecipType.DRIZZLE || (type == PrecipType.LIGHT_RAIN && rate <= 0.05) -> {
                74.0 to "Light mist/drizzle maintains steady movement"
            }
            type == PrecipType.LIGHT_RAIN -> {
                44.0 to "Steady rain dampens movement to short food edges"
            }
            type == PrecipType.HEAVY_RAIN || rate > 0.20 -> {
                14.0 to "Heavy rain pins deer in dense cover"
            }
            type == PrecipType.SLEET_FREEZING_RAIN -> {
                12.0 to "Sleet and freezing rain severely suppress activity"
            }
            else -> 50.0 to "Mixed precipitation"
        }

        val summary = if (type == PrecipType.NONE || rate <= 0.001) {
            if (sinceEnded != null && sinceEnded <= 2.0) {
                String.format(Locale.US, "Cleared %.1fh ago", sinceEnded)
            } else {
                "Dry"
            }
        } else {
            String.format(Locale.US, "%s (%.2f in/hr)", type.name.lowercase().replace('_', ' '), rate)
        }
        return buildBreakdown(MovementFactor.PRECIPITATION, raw.coerceIn(0.0, 100.0), summary, reason)
    }

    private fun buildBreakdown(
        factor: MovementFactor,
        rawScore: Double,
        summary: String,
        reason: String,
    ): FactorBreakdown = FactorBreakdown(
        factor = factor,
        rawScore = rawScore,
        weight = factor.weight,
        weightedContribution = rawScore * factor.weight,
        summary = summary,
        reason = reason,
    )
}

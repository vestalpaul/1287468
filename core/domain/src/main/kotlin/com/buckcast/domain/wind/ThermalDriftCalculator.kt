package com.buckcast.domain.wind

import com.buckcast.domain.math.AngleMath
import com.buckcast.domain.math.Vector2D
import java.time.Duration
import java.time.ZonedDateTime
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.sin

enum class ThermalRegime(val displayName: String) {
    NEUTRAL_FLAT("Flat Terrain (Neutral)"),
    KATABATIC_DOWNHILL("Katabatic Cooling (Downhill)"),
    MORNING_TRANSITION("Morning Thermal Reversal"),
    ANABATIC_UPHILL("Anabatic Warming (Uphill)"),
    EVENING_TRANSITION("Evening Thermal Reversal"),
}

/**
 * Input parameters for terrain thermal drift and leeward ridge rotor analysis.
 *
 * @property slopeAspectDeg Compass bearing `[0, 360)` the terrain faces DOWNHILL (fall-line).
 * @property slopeGradePercent Terrain slope grade in percent (e.g. `20.0` = 20% grade).
 * @property ambientWindFromDeg Meteorological wind-FROM bearing in degrees.
 */
data class ThermalInput(
    val slopeAspectDeg: Double,
    val slopeGradePercent: Double,
    val currentTime: ZonedDateTime,
    val sunrise: ZonedDateTime,
    val sunset: ZonedDateTime,
    val cloudCoverPercent: Double,
    val ambientWindSpeedMph: Double,
    val ambientWindFromDeg: Double,
) {
    init {
        require(slopeAspectDeg.isFinite()) { "slopeAspectDeg must be finite: $slopeAspectDeg" }
        require(slopeGradePercent.isFinite() && slopeGradePercent >= 0.0) {
            "slopeGradePercent must be non-negative: $slopeGradePercent"
        }
        require(cloudCoverPercent.isFinite() && cloudCoverPercent in 0.0..100.0) {
            "cloudCoverPercent must be in [0, 100]: $cloudCoverPercent"
        }
        require(ambientWindSpeedMph.isFinite() && ambientWindSpeedMph >= 0.0) {
            "ambientWindSpeedMph must be non-negative: $ambientWindSpeedMph"
        }
        require(ambientWindFromDeg.isFinite()) { "ambientWindFromDeg must be finite: $ambientWindFromDeg" }
        require(sunset.isAfter(sunrise)) { "sunset must be after sunrise" }
    }
}

/**
 * Result of diurnal thermal slope drift and 2D vector fusion with ambient wind.
 * All bearings are strictly normalized to `[0.0, 360.0)`.
 */
data class ThermalDriftResult(
    val regime: ThermalRegime,
    val slopeAspectDeg: Double,
    val thermalDriftToDeg: Double,
    val thermalSpeedMph: Double,
    val ambientScentToDeg: Double,
    val ambientWindSpeedMph: Double,
    val effectiveScentToDeg: Double,
    val effectiveSpeedMph: Double,
    val thermalDominanceRatio: Double,
    val isTransitionSwirlRisk: Boolean,
    val isLeewardRotorRisk: Boolean,
)

/**
 * Pure-Kotlin diurnal thermal slope drift & leeward ridge rotor calculator.
 */
object ThermalDriftCalculator {

    private const val FLAT_GRADE_THRESHOLD_PERCENT = 2.0
    private const val ROTOR_GRADE_THRESHOLD_PERCENT = 8.0
    private const val ROTOR_WIND_THRESHOLD_MPH = 8.0
    private const val ROTOR_ALIGNMENT_TOLERANCE_DEG = 45.0
    private const val MAX_THERMAL_SPEED_MPH = 9.0
    private const val SYNOPTIC_SUPPRESSION_REF_MPH = 10.0
    private const val CLOUD_DAMPENING_COEFF = 0.65

    private const val MORNING_TRANSITION_START_MIN = 45L
    private const val MORNING_TRANSITION_END_MIN = 120L
    private const val EVENING_TRANSITION_BEFORE_SUNSET_MIN = 90L
    private const val EVENING_TRANSITION_AFTER_SUNSET_MIN = 60L

    fun calculate(input: ThermalInput): ThermalDriftResult {
        val normalizedAspect = AngleMath.normalizeDegrees(input.slopeAspectDeg)
        val uphillBearing = AngleMath.oppositeBearing(normalizedAspect)
        val ambientScentToDeg = AngleMath.oppositeBearing(input.ambientWindFromDeg)

        val regime = classifyRegime(input)
        val thermalDriftToDeg = when (regime) {
            ThermalRegime.KATABATIC_DOWNHILL,
            ThermalRegime.EVENING_TRANSITION,
            ThermalRegime.NEUTRAL_FLAT,
            -> normalizedAspect

            ThermalRegime.ANABATIC_UPHILL,
            ThermalRegime.MORNING_TRANSITION,
            -> uphillBearing
        }

        val regimeMultiplier = when (regime) {
            ThermalRegime.NEUTRAL_FLAT -> 0.0
            ThermalRegime.KATABATIC_DOWNHILL -> 1.0
            ThermalRegime.ANABATIC_UPHILL -> 0.85
            ThermalRegime.MORNING_TRANSITION,
            ThermalRegime.EVENING_TRANSITION,
            -> 0.25
        }

        val thermalSpeedMph = if (regime == ThermalRegime.NEUTRAL_FLAT) {
            0.0
        } else {
            val slopeRad = atan(input.slopeGradePercent / 100.0)
            val slopeFactor = sin(slopeRad)
            val skyFactor = 1.0 - CLOUD_DAMPENING_COEFF * (input.cloudCoverPercent / 100.0)
            val windRatio = input.ambientWindSpeedMph / SYNOPTIC_SUPPRESSION_REF_MPH
            val synopticSuppression = exp(-(windRatio * windRatio))
            (MAX_THERMAL_SPEED_MPH * slopeFactor * skyFactor * regimeMultiplier * synopticSuppression)
                .coerceAtLeast(0.0)
        }

        val ambientVec = Vector2D(bearingDeg = ambientScentToDeg, magnitude = input.ambientWindSpeedMph)
        val thermalVec = Vector2D(bearingDeg = thermalDriftToDeg, magnitude = thermalSpeedMph)
        val combinedVec = AngleMath.addVectors(ambientVec, thermalVec)

        val totalMagnitude = input.ambientWindSpeedMph + thermalSpeedMph
        val dominance = if (totalMagnitude <= 1e-6) {
            0.0
        } else {
            (thermalSpeedMph / totalMagnitude).coerceIn(0.0, 1.0)
        }

        val isTransitionSwirl = regime == ThermalRegime.MORNING_TRANSITION ||
            regime == ThermalRegime.EVENING_TRANSITION

        // Leeward ridge rotor occurs when > 8 mph ambient wind blows downslope over a ridge behind the stand.
        val isLeewardRotorRisk = input.ambientWindSpeedMph > ROTOR_WIND_THRESHOLD_MPH &&
            input.slopeGradePercent >= ROTOR_GRADE_THRESHOLD_PERCENT &&
            AngleMath.angularDistanceDegrees(ambientScentToDeg, normalizedAspect) <= ROTOR_ALIGNMENT_TOLERANCE_DEG

        return ThermalDriftResult(
            regime = regime,
            slopeAspectDeg = normalizedAspect,
            thermalDriftToDeg = thermalDriftToDeg,
            thermalSpeedMph = thermalSpeedMph,
            ambientScentToDeg = ambientScentToDeg,
            ambientWindSpeedMph = input.ambientWindSpeedMph,
            effectiveScentToDeg = combinedVec.bearingDeg,
            effectiveSpeedMph = combinedVec.magnitude,
            thermalDominanceRatio = dominance,
            isTransitionSwirlRisk = isTransitionSwirl,
            isLeewardRotorRisk = isLeewardRotorRisk,
        )
    }

    private fun classifyRegime(input: ThermalInput): ThermalRegime {
        if (input.slopeGradePercent < FLAT_GRADE_THRESHOLD_PERCENT) {
            return ThermalRegime.NEUTRAL_FLAT
        }

        val minAfterSunrise = Duration.between(input.sunrise, input.currentTime).toMinutes()
        val minBeforeSunset = Duration.between(input.currentTime, input.sunset).toMinutes()

        return when {
            minAfterSunrise < MORNING_TRANSITION_START_MIN -> ThermalRegime.KATABATIC_DOWNHILL
            minBeforeSunset < -EVENING_TRANSITION_AFTER_SUNSET_MIN -> ThermalRegime.KATABATIC_DOWNHILL
            minBeforeSunset <= EVENING_TRANSITION_BEFORE_SUNSET_MIN -> ThermalRegime.EVENING_TRANSITION
            minAfterSunrise < MORNING_TRANSITION_END_MIN -> ThermalRegime.MORNING_TRANSITION
            else -> ThermalRegime.ANABATIC_UPHILL
        }
    }
}

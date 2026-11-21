package com.buckcast.domain.wind

import com.buckcast.domain.math.AngleMath
import com.buckcast.domain.math.BearingSector

enum class StandWindVerdict {
    OPTIMAL,
    GOOD,
    CAUTION,
    UNSAFE,
}

enum class WindAlignmentBadge(val label: String) {
    WIND_IN_FACE("Wind in Face"),
    CROSSWIND("Crosswind"),
    WIND_AT_BACK("Wind at Back"),
}

/**
 * Stand wind configuration and deer-approach geometry.
 */
data class StandWindConfig(
    val standId: String,
    val standName: String,
    val preferredWindFromSectors: List<BearingSector>,
    val cautionMarginDeg: Double = 15.0,
    val deerZoneBearingsDeg: List<Double> = emptyList(),
    val scentConeHalfAngleDeg: Double = 22.5,
    val calmWindThresholdMph: Double = 3.0,
) {
    init {
        require(cautionMarginDeg.isFinite() && cautionMarginDeg in 0.0..90.0) {
            "cautionMarginDeg must be in [0, 90]: $cautionMarginDeg"
        }
        require(scentConeHalfAngleDeg.isFinite() && scentConeHalfAngleDeg in 1.0..90.0) {
            "scentConeHalfAngleDeg must be in [1, 90]: $scentConeHalfAngleDeg"
        }
        require(calmWindThresholdMph.isFinite() && calmWindThresholdMph >= 0.0) {
            "calmWindThresholdMph must be non-negative: $calmWindThresholdMph"
        }
    }
}

/**
 * Complete stand wind evaluation with normalized `[0, 360)` bearings and tactical risk flags.
 */
data class StandWindEvaluation(
    val standId: String,
    val standName: String,
    val verdict: StandWindVerdict,
    val score: Double,
    val effectiveWindFromDeg: Double,
    val effectiveScentToDeg: Double,
    val effectiveWindSpeedMph: Double,
    val scentConeMinDeg: Double,
    val scentConeMaxDeg: Double,
    val isSwirlingRisk: Boolean,
    val isLeewardRotorRisk: Boolean,
    val compromisedDeerZonesDeg: List<Double>,
    val explanation: String,
)

/**
 * Evaluates a hunting stand's wind compatibility incorporating preferred sectors,
 * expected deer-approach cones, calm-air swirling, thermal slope drift, and leeward rotors.
 */
object StandWindEvaluator {

    fun evaluate(
        config: StandWindConfig,
        ambientWindFromDeg: Double,
        ambientWindSpeedMph: Double,
        thermalDrift: ThermalDriftResult? = null,
    ): StandWindEvaluation {
        require(ambientWindFromDeg.isFinite()) { "ambientWindFromDeg must be finite: $ambientWindFromDeg" }
        require(ambientWindSpeedMph.isFinite() && ambientWindSpeedMph >= 0.0) {
            "ambientWindSpeedMph must be non-negative: $ambientWindSpeedMph"
        }

        val effectiveScentToDeg = thermalDrift?.effectiveScentToDeg
            ?: AngleMath.oppositeBearing(ambientWindFromDeg)
        val effectiveWindFromDeg = AngleMath.oppositeBearing(effectiveScentToDeg)
        val effectiveSpeedMph = thermalDrift?.effectiveSpeedMph ?: ambientWindSpeedMph

        val scentConeMinDeg = AngleMath.normalizeDegrees(effectiveScentToDeg - config.scentConeHalfAngleDeg)
        val scentConeMaxDeg = AngleMath.normalizeDegrees(effectiveScentToDeg + config.scentConeHalfAngleDeg)

        val compromisedZones = config.deerZoneBearingsDeg
            .map { AngleMath.normalizeDegrees(it) }
            .filter { zoneDeg ->
                AngleMath.angularDistanceDegrees(effectiveScentToDeg, zoneDeg) <= config.scentConeHalfAngleDeg
            }

        val isCalmSwirl = effectiveSpeedMph < config.calmWindThresholdMph
        val isThermalTransition = thermalDrift?.isTransitionSwirlRisk == true
        val isLeewardRotor = thermalDrift?.isLeewardRotorRisk == true
        val isSwirlingRisk = isCalmSwirl || isThermalTransition || isLeewardRotor

        val minDistanceOutside = if (config.preferredWindFromSectors.isEmpty()) {
            0.0
        } else {
            config.preferredWindFromSectors.minOf { it.distanceOutsideDegrees(effectiveWindFromDeg) }
        }
        val bestClearanceInside = if (config.preferredWindFromSectors.isEmpty()) {
            180.0
        } else {
            config.preferredWindFromSectors.maxOf { it.clearanceInsideDegrees(effectiveWindFromDeg) }
        }

        val (verdict, score, explanation) = when {
            compromisedZones.isNotEmpty() -> {
                val base = (25.0 - compromisedZones.size * 5.0).coerceAtLeast(5.0)
                Triple(
                    StandWindVerdict.UNSAFE,
                    base,
                    "Scent cone blows directly over ${compromisedZones.size} expected deer zone(s).",
                )
            }
            minDistanceOutside > config.cautionMarginDeg -> {
                val base = (35.0 - (minDistanceOutside - config.cautionMarginDeg) * 0.4).coerceAtLeast(0.0)
                Triple(
                    StandWindVerdict.UNSAFE,
                    base,
                    "Effective wind is ${minDistanceOutside.toInt()}° outside preferred stand sectors.",
                )
            }
            minDistanceOutside > 0.0 -> {
                val base = (65.0 - (minDistanceOutside / config.cautionMarginDeg.coerceAtLeast(1.0)) * 20.0)
                    .coerceIn(45.0, 65.0)
                Triple(
                    StandWindVerdict.CAUTION,
                    base,
                    "Marginal wind (${minDistanceOutside.toInt()}° outside preferred sector edge).",
                )
            }
            isLeewardRotor -> {
                Triple(
                    StandWindVerdict.CAUTION,
                    50.0,
                    "Leeward ridge rotor risk (> 8 mph downslope wind over ridge behind stand).",
                )
            }
            isSwirlingRisk -> {
                val reason = if (isCalmSwirl) {
                    "Near-calm wind (< ${config.calmWindThresholdMph} mph) creates swirling scent risk."
                } else {
                    "Diurnal thermal reversal window creates shifting scent currents."
                }
                Triple(StandWindVerdict.CAUTION, 55.0, reason)
            }
            bestClearanceInside >= config.cautionMarginDeg -> {
                val bonus = (bestClearanceInside / 45.0).coerceAtMost(1.0) * 12.0
                Triple(
                    StandWindVerdict.OPTIMAL,
                    (88.0 + bonus).coerceIn(88.0, 100.0),
                    "Wind and thermal drift center cleanly inside preferred stand window.",
                )
            }
            else -> {
                Triple(
                    StandWindVerdict.GOOD,
                    (74.0 + bestClearanceInside).coerceIn(72.0, 87.0),
                    "Wind fits inside preferred sector near edge margin.",
                )
            }
        }

        return StandWindEvaluation(
            standId = config.standId,
            standName = config.standName,
            verdict = verdict,
            score = score,
            effectiveWindFromDeg = effectiveWindFromDeg,
            effectiveScentToDeg = effectiveScentToDeg,
            effectiveWindSpeedMph = effectiveSpeedMph,
            scentConeMinDeg = scentConeMinDeg,
            scentConeMaxDeg = scentConeMaxDeg,
            isSwirlingRisk = isSwirlingRisk,
            isLeewardRotorRisk = isLeewardRotor,
            compromisedDeerZonesDeg = compromisedZones,
            explanation = explanation,
        )
    }
}

/**
 * Classifies wind-vs-facing compass alignment with 5° boundary hysteresis to prevent HUD badge flicker.
 */
object WindAlignmentClassifier {

    private const val FACE_THRESHOLD_DEG = 45.0
    private const val BACK_THRESHOLD_DEG = 135.0
    const val DEFAULT_HYSTERESIS_DEG = 5.0

    fun classify(
        facingHeadingDeg: Double,
        windFromDeg: Double,
        previousBadge: WindAlignmentBadge? = null,
        hysteresisDeg: Double = DEFAULT_HYSTERESIS_DEG,
    ): WindAlignmentBadge {
        require(hysteresisDeg.isFinite() && hysteresisDeg in 0.0..20.0) {
            "hysteresisDeg must be in [0, 20]: $hysteresisDeg"
        }
        val deltaFromWindSource = AngleMath.angularDistanceDegrees(facingHeadingDeg, windFromDeg)

        return when (previousBadge) {
            null -> when {
                deltaFromWindSource <= FACE_THRESHOLD_DEG -> WindAlignmentBadge.WIND_IN_FACE
                deltaFromWindSource >= BACK_THRESHOLD_DEG -> WindAlignmentBadge.WIND_AT_BACK
                else -> WindAlignmentBadge.CROSSWIND
            }
            WindAlignmentBadge.WIND_IN_FACE -> when {
                deltaFromWindSource > FACE_THRESHOLD_DEG + hysteresisDeg ->
                    if (deltaFromWindSource >= BACK_THRESHOLD_DEG) WindAlignmentBadge.WIND_AT_BACK else WindAlignmentBadge.CROSSWIND
                else -> WindAlignmentBadge.WIND_IN_FACE
            }
            WindAlignmentBadge.WIND_AT_BACK -> when {
                deltaFromWindSource < BACK_THRESHOLD_DEG - hysteresisDeg ->
                    if (deltaFromWindSource <= FACE_THRESHOLD_DEG) WindAlignmentBadge.WIND_IN_FACE else WindAlignmentBadge.CROSSWIND
                else -> WindAlignmentBadge.WIND_AT_BACK
            }
            WindAlignmentBadge.CROSSWIND -> when {
                deltaFromWindSource < FACE_THRESHOLD_DEG - hysteresisDeg -> WindAlignmentBadge.WIND_IN_FACE
                deltaFromWindSource > BACK_THRESHOLD_DEG + hysteresisDeg -> WindAlignmentBadge.WIND_AT_BACK
                else -> WindAlignmentBadge.CROSSWIND
            }
        }
    }
}

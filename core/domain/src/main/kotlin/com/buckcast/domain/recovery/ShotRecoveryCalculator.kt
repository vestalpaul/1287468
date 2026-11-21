package com.buckcast.domain.recovery

import com.buckcast.domain.math.AngleMath
import com.buckcast.domain.math.BearingSector
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToLong

enum class HitLocation(val displayName: String) {
    DOUBLE_LUNG("Double Lung"),
    HEART("Heart"),
    HIGH_LUNG_SINGLE("High / Single Lung"),
    LIVER("Liver"),
    GUT_PAUNCH("Gut / Paunch"),
    BRISKET_MUSCLE("Brisket / Muscle"),
    SHOULDER_BONE("Shoulder / Bone"),
    UNKNOWN("Uncertain Hit"),
}

enum class BloodSign(val displayName: String) {
    BRIGHT_RED_BUBBLY("Bright Red & Frothy/Bubbly"),
    DARK_RED_STEADY("Dark Red Steady Trail"),
    WATERY_GREEN_BROWN_DIGESTA("Watery / Green-Brown Digesta"),
    SPARSE_SPECKS("Sparse Specks / Drops"),
    PINK_FOAMY_OR_FAT("Light Pink / Fat Tallow"),
    NONE_FOUND("No Blood Found at Impact"),
}

enum class RecoveryWeaponType(val isArchery: Boolean) {
    COMPOUND_BOW(isArchery = true),
    CROSSBOW(isArchery = true),
    TRADITIONAL_BOW(isArchery = true),
    RIFLE(isArchery = false),
    MUZZLELOADER(isArchery = false),
    SHOTGUN_SLUG(isArchery = false),
}

enum class SpoilageRiskLevel(val displayName: String) {
    LOW("Low"),
    MODERATE("Moderate"),
    HIGH("High"),
    CRITICAL("Critical"),
}

data class ShotRecoveryInput(
    val hitLocation: HitLocation,
    val bloodSign: BloodSign,
    val weaponType: RecoveryWeaponType,
    val ambientTempDegF: Double,
    val shotTime: Instant,
    val observedEscapeBearingDeg: Double? = null,
    val windFromDeg: Double? = null,
) {
    init {
        require(ambientTempDegF.isFinite() && ambientTempDegF in -60.0..130.0) {
            "ambientTempDegF out of range: $ambientTempDegF"
        }
        if (observedEscapeBearingDeg != null) {
            require(observedEscapeBearingDeg.isFinite()) {
                "observedEscapeBearingDeg must be finite: $observedEscapeBearingDeg"
            }
        }
        if (windFromDeg != null) {
            require(windFromDeg.isFinite()) {
                "windFromDeg must be finite: $windFromDeg"
            }
        }
    }
}

/**
 * Pure-Kotlin recovery plan keeping biological wait hours intact even in warm temperatures,
 * while surfacing meat-spoilage risk and advisory separately so hunters do not bump wounded deer.
 */
data class ShotRecoveryPlan(
    val biologicalMinWaitHours: Double,
    val biologicalRecommendedWaitHours: Double,
    val earliestTrackTime: Instant,
    val recommendedTrackTime: Instant,
    val spoilageRisk: SpoilageRiskLevel,
    val spoilageSafeWindowHours: Double?,
    val spoilageAdvisory: String,
    val escapeCorridorCenterDeg: Double?,
    val escapeCorridorHalfAngleDeg: Double,
    val escapeCorridorSector: BearingSector?,
    val tactics: List<String>,
)

object ShotRecoveryCalculator {

    fun calculate(input: ShotRecoveryInput): ShotRecoveryPlan {
        val (hitMinHours, hitRecHours) = hitWaitWindow(input.hitLocation, input.weaponType)
        val (bloodMinHours, bloodRecHours) = bloodSignWaitWindow(input.bloodSign, input.weaponType)

        // When blood evidence is more severe (e.g., gut digesta or dark liver blood), adopt the conservative longer wait.
        val biologicalMinWaitHours = maxOf(hitMinHours, bloodMinHours)
        val biologicalRecommendedWaitHours = maxOf(hitRecHours, bloodRecHours)

        val earliestTrackTime = input.shotTime.plusSeconds((biologicalMinWaitHours * 3600.0).roundToLong())
        val recommendedTrackTime = input.shotTime.plusSeconds((biologicalRecommendedWaitHours * 3600.0).roundToLong())

        val (spoilageRisk, safeWindowHours, spoilageAdvisory) = evaluateSpoilage(
            ambientTempDegF = input.ambientTempDegF,
            biologicalRecommendedWaitHours = biologicalRecommendedWaitHours,
        )

        val centerDeg = input.observedEscapeBearingDeg?.let { AngleMath.normalizeDegrees(it) }
        val halfAngleDeg = when (input.hitLocation) {
            HitLocation.DOUBLE_LUNG, HitLocation.HEART -> 25.0
            else -> 35.0
        }
        val sector = centerDeg?.let {
            BearingSector(
                startDeg = AngleMath.normalizeDegrees(it - halfAngleDeg),
                endDeg = AngleMath.normalizeDegrees(it + halfAngleDeg),
            )
        }

        val tactics = buildTactics(input, biologicalRecommendedWaitHours, centerDeg)

        return ShotRecoveryPlan(
            biologicalMinWaitHours = biologicalMinWaitHours,
            biologicalRecommendedWaitHours = biologicalRecommendedWaitHours,
            earliestTrackTime = earliestTrackTime,
            recommendedTrackTime = recommendedTrackTime,
            spoilageRisk = spoilageRisk,
            spoilageSafeWindowHours = safeWindowHours,
            spoilageAdvisory = spoilageAdvisory,
            escapeCorridorCenterDeg = centerDeg,
            escapeCorridorHalfAngleDeg = halfAngleDeg,
            escapeCorridorSector = sector,
            tactics = tactics,
        )
    }

    private fun hitWaitWindow(hit: HitLocation, weapon: RecoveryWeaponType): Pair<Double, Double> = when (hit) {
        HitLocation.HEART, HitLocation.DOUBLE_LUNG ->
            if (weapon.isArchery) 0.5 to 1.0 else 0.25 to 0.5
        HitLocation.HIGH_LUNG_SINGLE ->
            if (weapon.isArchery) 3.0 to 4.5 else 1.5 to 2.5
        HitLocation.LIVER -> 4.0 to 6.0
        HitLocation.GUT_PAUNCH -> 8.0 to 12.0
        HitLocation.SHOULDER_BONE, HitLocation.BRISKET_MUSCLE, HitLocation.UNKNOWN -> 4.0 to 6.0
    }

    private fun bloodSignWaitWindow(blood: BloodSign, weapon: RecoveryWeaponType): Pair<Double, Double> = when (blood) {
        BloodSign.BRIGHT_RED_BUBBLY ->
            if (weapon.isArchery) 0.5 to 1.0 else 0.25 to 0.5
        BloodSign.DARK_RED_STEADY -> 4.0 to 6.0
        BloodSign.WATERY_GREEN_BROWN_DIGESTA -> 8.0 to 12.0
        BloodSign.SPARSE_SPECKS, BloodSign.PINK_FOAMY_OR_FAT, BloodSign.NONE_FOUND -> 3.0 to 5.0
    }

    private fun evaluateSpoilage(
        ambientTempDegF: Double,
        biologicalRecommendedWaitHours: Double,
    ): Triple<SpoilageRiskLevel, Double?, String> {
        val risk = when {
            ambientTempDegF <= 40.0 -> SpoilageRiskLevel.LOW
            ambientTempDegF <= 55.0 -> SpoilageRiskLevel.MODERATE
            ambientTempDegF <= 68.0 -> SpoilageRiskLevel.HIGH
            else -> SpoilageRiskLevel.CRITICAL
        }
        val safeHours: Double? = when (risk) {
            SpoilageRiskLevel.LOW -> null
            SpoilageRiskLevel.MODERATE -> 10.0
            SpoilageRiskLevel.HIGH -> 5.0
            SpoilageRiskLevel.CRITICAL -> 3.0
        }

        val tempLabel = String.format(Locale.US, "%.0f°F", ambientTempDegF)
        val advisory = when (risk) {
            SpoilageRiskLevel.LOW ->
                "Cool ambient temperature ($tempLabel) protects meat quality throughout the full biological wait."
            SpoilageRiskLevel.MODERATE ->
                "Moderate spoilage risk ($tempLabel, ~10h window). Prioritize skinning/cooling promptly once recovered."
            SpoilageRiskLevel.HIGH ->
                "High spoilage risk ($tempLabel, ~5h window). Do NOT rush a gut/liver wait; prepare ice/quarters immediately after recovery."
            SpoilageRiskLevel.CRITICAL ->
                if (biologicalRecommendedWaitHours > 4.0) {
                    "Critical heat spoilage warning ($tempLabel, ~3h cooling window). Biological wait remains ${biologicalRecommendedWaitHours.toInt()}h so the deer is not pushed; check nocturnal temperature drop and quarter/cool immediately upon recovery."
                } else {
                    "Critical heat spoilage risk ($tempLabel, ~3h window). Field dress and pack cavity with ice immediately upon recovery."
                }
        }
        return Triple(risk, safeHours, advisory)
    }

    private fun buildTactics(
        input: ShotRecoveryInput,
        waitHours: Double,
        escapeCenterDeg: Double?,
    ): List<String> = buildList {
        add("Mark exact shot site and last-seen spot before stepping down to check arrow/impact sign.")
        if (waitHours >= 6.0) {
            add("Back out quietly away from the escape trail and wait the full ${waitHours.toInt()}h biological window.")
        }
        if (escapeCenterDeg != null) {
            add("Sweep escape corridor centered on ${escapeCenterDeg.toInt()}° (±${if (input.hitLocation == HitLocation.DOUBLE_LUNG || input.hitLocation == HitLocation.HEART) 25 else 35}°).")
        }
        if (escapeCenterDeg != null && input.windFromDeg != null) {
            val scentToDeg = AngleMath.oppositeBearing(input.windFromDeg)
            val delta = AngleMath.angularDistanceDegrees(scentToDeg, escapeCenterDeg)
            if (delta <= 35.0) {
                add("Caution: current wind blows your scent directly toward the escape corridor (${escapeCenterDeg.toInt()}°); approach from a flanking crosswind if possible.")
            }
        }
        add("Log blood drops as breadcrumbs rather than walking directly on top of sign.")
    }
}

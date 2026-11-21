package com.buckcast.domain.recovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ShotRecoveryCalculatorTest {

    private val shotTime = Instant.parse("2026-11-21T22:30:00Z")

    @Test
    fun `double lung rifle vs archery returns rapid recovery windows and cool spoilage LOW`() {
        val archery = ShotRecoveryCalculator.calculate(
            ShotRecoveryInput(
                hitLocation = HitLocation.DOUBLE_LUNG,
                bloodSign = BloodSign.BRIGHT_RED_BUBBLY,
                weaponType = RecoveryWeaponType.COMPOUND_BOW,
                ambientTempDegF = 34.0,
                shotTime = shotTime,
                observedEscapeBearingDeg = 355.0,
            ),
        )
        assertEquals(0.5, archery.biologicalMinWaitHours, 1e-6)
        assertEquals(1.0, archery.biologicalRecommendedWaitHours, 1e-6)
        assertEquals(SpoilageRiskLevel.LOW, archery.spoilageRisk)
        assertNull(archery.spoilageSafeWindowHours)
        assertNotNull(archery.escapeCorridorSector)
        assertTrue(archery.escapeCorridorSector!!.contains(10.0))
        assertTrue(archery.escapeCorridorSector!!.contains(340.0))

        val rifle = ShotRecoveryCalculator.calculate(
            ShotRecoveryInput(
                hitLocation = HitLocation.DOUBLE_LUNG,
                bloodSign = BloodSign.BRIGHT_RED_BUBBLY,
                weaponType = RecoveryWeaponType.RIFLE,
                ambientTempDegF = 34.0,
                shotTime = shotTime,
            ),
        )
        assertEquals(0.25, rifle.biologicalMinWaitHours, 1e-6)
        assertEquals(0.5, rifle.biologicalRecommendedWaitHours, 1e-6)
    }

    @Test
    fun `gut paunch hit in 78 degF heat preserves full biological wait hours and reports CRITICAL spoilage separately`() {
        val plan = ShotRecoveryCalculator.calculate(
            ShotRecoveryInput(
                hitLocation = HitLocation.GUT_PAUNCH,
                bloodSign = BloodSign.WATERY_GREEN_BROWN_DIGESTA,
                weaponType = RecoveryWeaponType.COMPOUND_BOW,
                ambientTempDegF = 78.0,
                shotTime = shotTime,
                observedEscapeBearingDeg = 120.0,
                windFromDeg = 300.0, // blows directly toward 120° escape corridor
            ),
        )

        // Critical invariant: NEVER shorten biological wait hours due to hot ambient temperatures!
        assertEquals(8.0, plan.biologicalMinWaitHours, 1e-6)
        assertEquals(12.0, plan.biologicalRecommendedWaitHours, 1e-6)
        assertEquals(Duration.ofHours(8), Duration.between(shotTime, plan.earliestTrackTime))
        assertEquals(Duration.ofHours(12), Duration.between(shotTime, plan.recommendedTrackTime))

        // Spoilage advisory exposed separately
        assertEquals(SpoilageRiskLevel.CRITICAL, plan.spoilageRisk)
        assertEquals(3.0, plan.spoilageSafeWindowHours!!, 1e-6)
        assertTrue(plan.spoilageAdvisory.contains("78°F"))
        assertTrue(plan.spoilageAdvisory.contains("Biological wait remains 12h"))
        assertTrue(plan.tactics.any { it.contains("flanking crosswind") })
    }

    @Test
    fun `uncertain hit with dark red blood defaults to conservative liver wait window`() {
        val plan = ShotRecoveryCalculator.calculate(
            ShotRecoveryInput(
                hitLocation = HitLocation.UNKNOWN,
                bloodSign = BloodSign.DARK_RED_STEADY,
                weaponType = RecoveryWeaponType.CROSSBOW,
                ambientTempDegF = 50.0,
                shotTime = shotTime,
            ),
        )
        assertEquals(4.0, plan.biologicalMinWaitHours, 1e-6)
        assertEquals(6.0, plan.biologicalRecommendedWaitHours, 1e-6)
        assertEquals(SpoilageRiskLevel.MODERATE, plan.spoilageRisk)
    }
}

package com.buckcast.domain.entitlement

import com.buckcast.domain.model.AppThemePreset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EntitlementEvaluatorTest {

    private val t0 = 1_795_000_000_000L
    private val hourMs = 3_600_000L
    private val dayMs = 86_400_000L

    @Test
    fun `36h welcome preview grants ALPHA from t+0 through t+35h59m and locks back to SCOUT at t+36h00m`() {
        val snapshot = EntitlementSnapshot(
            localFirstLaunchEpochMs = t0,
            maxObservedEpochMs = t0,
            supportUserId = "dt_user_01",
        )

        val atInstall = EntitlementEvaluator.evaluate(snapshot, nowEpochMs = t0)
        assertEquals(SubscriptionTier.ALPHA, atInstall.tier)
        assertEquals(EntitlementSource.ALPHA_PREVIEW_36H, atInstall.source)
        assertTrue(atInstall.isPreviewActive)
        assertEquals(36 * hourMs, atInstall.remainingPreviewMs)
        assertTrue(atInstall.hasAccess(AlphaFeature.OFFLINE_MAP_PACKS))

        val at35h59m = EntitlementEvaluator.evaluate(snapshot, nowEpochMs = t0 + 36 * hourMs - 60_000L)
        assertEquals(SubscriptionTier.ALPHA, at35h59m.tier)
        assertEquals(60_000L, at35h59m.remainingPreviewMs)

        val at36h00m = EntitlementEvaluator.evaluate(snapshot, nowEpochMs = t0 + 36 * hourMs)
        assertEquals(SubscriptionTier.SCOUT, at36h00m.tier)
        assertEquals(EntitlementSource.NONE, at36h00m.source)
        assertFalse(at36h00m.isPreviewActive)
        assertEquals(0L, at36h00m.remainingPreviewMs)
        assertFalse(at36h00m.hasAccess(AlphaFeature.OFFLINE_MAP_PACKS))

        // Alpha-only themes revert gracefully to TIMBERLINE_DARK on Scout lockback
        assertEquals(
            AppThemePreset.TIMBERLINE_DARK,
            at36h00m.effectiveThemePreset(AppThemePreset.STEALTH_CRIMSON),
        )
    }

    @Test
    fun `anti-rollback clock guard blocks extending preview by rolling device clock backward`() {
        val expiredAt37h = EntitlementSnapshot(
            localFirstLaunchEpochMs = t0,
            maxObservedEpochMs = t0 + 37 * hourMs,
        )

        // User rolls clock back 30 hours -> evaluator pins to maxObservedEpochMs (37h), stays SCOUT
        val afterRollback = EntitlementEvaluator.evaluate(
            snapshot = expiredAt37h,
            nowEpochMs = t0 + 7 * hourMs,
        )
        assertEquals(SubscriptionTier.SCOUT, afterRollback.tier)
        assertEquals(0L, afterRollback.remainingPreviewMs)
    }

    @Test
    fun `serverFirstSeen older than localFirstLaunch prevents reinstall preview extension`() {
        val reinstalled = EntitlementSnapshot(
            localFirstLaunchEpochMs = t0 + 10 * dayMs,
            serverFirstSeenEpochMs = t0,
            maxObservedEpochMs = t0 + 10 * dayMs,
        )
        val state = EntitlementEvaluator.evaluate(reinstalled, nowEpochMs = t0 + 10 * dayMs)
        assertEquals(SubscriptionTier.SCOUT, state.tier)
    }

    @Test
    fun `promo comp and play subscription honor 14-day offline grace window and expire past 14 days`() {
        val comped = EntitlementSnapshot(
            localFirstLaunchEpochMs = t0,
            maxObservedEpochMs = t0 + 20 * dayMs,
            lastKnownTier = SubscriptionTier.ALPHA,
            lastKnownSource = EntitlementSource.PROMO_COMP,
            subscriptionExpiresEpochMs = t0 + 10 * dayMs,
            lastVerifiedEpochMs = t0 + 9 * dayMs,
        )

        // 5 days offline past expiry (within 14 days of lastVerified) -> ALPHA grace active
        val withinGrace = EntitlementEvaluator.evaluate(comped, nowEpochMs = t0 + 15 * dayMs)
        assertEquals(SubscriptionTier.ALPHA, withinGrace.tier)
        assertEquals(EntitlementSource.PROMO_COMP, withinGrace.source)
        assertTrue(withinGrace.isOfflineGraceActive)

        // 15 days since lastVerified (> 14 days) -> expires to SCOUT
        val pastGrace = EntitlementEvaluator.evaluate(comped, nowEpochMs = t0 + 24 * dayMs)
        assertEquals(SubscriptionTier.SCOUT, pastGrace.tier)
    }

    @Test
    fun `debug overrides work only when allowDebugOverride is true`() {
        val expired = EntitlementSnapshot(
            localFirstLaunchEpochMs = t0,
            maxObservedEpochMs = t0 + 40 * hourMs,
            debugOverride = DebugEntitlementOverride.FORCE_ALPHA,
        )
        assertEquals(
            SubscriptionTier.ALPHA,
            EntitlementEvaluator.evaluate(expired, t0 + 40 * hourMs, allowDebugOverride = true).tier,
        )
        assertEquals(
            SubscriptionTier.SCOUT,
            EntitlementEvaluator.evaluate(expired, t0 + 40 * hourMs, allowDebugOverride = false).tier,
        )
    }
}

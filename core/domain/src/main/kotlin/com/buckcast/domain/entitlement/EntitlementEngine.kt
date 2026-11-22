package com.buckcast.domain.entitlement

import com.buckcast.domain.model.AppThemePreset

enum class SubscriptionTier {
    SCOUT,
    ALPHA,
}

enum class EntitlementSource {
    NONE,
    ALPHA_PREVIEW_36H,
    PLAY_SUBSCRIPTION,
    PROMO_COMP,
    DEBUG_OVERRIDE,
}

enum class DebugEntitlementOverride {
    AUTO,
    FORCE_ALPHA,
    FORCE_SCOUT,
    EXPIRE_36H_PREVIEW,
}

enum class AlphaFeature(
    val title: String,
    val summary: String,
) {
    SEVEN_DAY_OUTLOOK("7-Day Movement Outlook", "Full 7-day AM/PM forecasts & movement timeline"),
    FIVE_FACTOR_INTEL("5-Factor Movement Intel", "Transparent scoring breakdown & 4 AM–9 PM hourly table"),
    PREMIUM_MAP_LAYERS("Satellite, Hybrid & Hillshade", "Aerial imagery & shaded relief map layers"),
    OFFLINE_MAP_PACKS("Offline Map Packs", "Download topographic & aerial regions for zero-cell hunts"),
    SCENT_THERMAL_CONES("300-yd Scent & Thermal Cones", "Live downwind plumes, diurnal thermals & range rings"),
    UNLIMITED_STANDS_WAYPOINTS("Unlimited Stands & Waypoints", "Save unlimited stands & sign beyond 1 stand / 3 pins"),
    STAND_WIND_FRESHNESS("Stand Wind-Match & Freshness", "Live wind verdicts, stand pressure tracking & sit logging"),
    ROUTE_AUDITOR("Entry/Exit Route Auditor", "Polyline walk-in audits against deer bedding"),
    BALLISTIC_YARDAGE("Cosine Ballistic Yardage", "Slope & stand-height compensated cut ranges"),
    BLOOD_TRAIL_TRACKER("Blood-Trail GPS Tracker", "Live trail recording, shot recovery & GPX export"),
    PREMIUM_THEMES("DropTine Custom Themes", "Topo Daylight, Stealth Crimson, Blaze Slate & Material You"),
    AI_BRIEFING("Alpha Field Briefings", "Full hunt briefing & pattern intelligence"),
}

data class EntitlementSnapshot(
    val localFirstLaunchEpochMs: Long,
    val serverFirstSeenEpochMs: Long? = null,
    val maxObservedEpochMs: Long = localFirstLaunchEpochMs,
    val lastKnownTier: SubscriptionTier = SubscriptionTier.SCOUT,
    val lastKnownSource: EntitlementSource = EntitlementSource.NONE,
    val subscriptionExpiresEpochMs: Long? = null,
    val lastVerifiedEpochMs: Long? = null,
    val debugOverride: DebugEntitlementOverride = DebugEntitlementOverride.AUTO,
    val supportUserId: String = "",
)

data class EntitlementState(
    val tier: SubscriptionTier,
    val source: EntitlementSource,
    val previewStartedEpochMs: Long,
    val previewExpiresEpochMs: Long,
    val subscriptionExpiresEpochMs: Long?,
    val lastVerifiedEpochMs: Long?,
    val isOfflineGraceActive: Boolean,
    val remainingPreviewMs: Long,
    val supportUserId: String = "",
    val debugOverride: DebugEntitlementOverride = DebugEntitlementOverride.AUTO,
) {
    val isAlpha: Boolean
        get() = tier == SubscriptionTier.ALPHA

    val isPreviewActive: Boolean
        get() = source == EntitlementSource.ALPHA_PREVIEW_36H && remainingPreviewMs > 0L

    fun hasAccess(feature: AlphaFeature): Boolean = tier == SubscriptionTier.ALPHA

    fun effectiveThemePreset(selected: AppThemePreset): AppThemePreset =
        if (!isAlpha && selected.isAlphaOnly) AppThemePreset.TIMBERLINE_DARK else selected
}

/**
 * Pure-Kotlin DropTine Alpha entitlement evaluator supporting:
 * - Immediate zero-friction 36-hour Alpha preview upon first install (`effectiveStart = min(localFirstLaunch, serverFirstSeen)`)
 * - Clock-rollback protection via monotonic `maxObservedEpochMs` with 5-minute tolerance
 * - 14-day offline grace window for verified Play subscriptions and RevenueCat Promo/VIP comps
 * - Debug-only overrides stripped cleanly in release builds (`allowDebugOverride = false`)
 */
object EntitlementEvaluator {

    const val PREVIEW_DURATION_MS = 36L * 3_600L * 1000L
    const val OFFLINE_GRACE_WINDOW_MS = 14L * 86_400L * 1000L
    const val CLOCK_ROLLBACK_TOLERANCE_MS = 5L * 60L * 1000L

    fun evaluate(
        snapshot: EntitlementSnapshot,
        nowEpochMs: Long,
        allowDebugOverride: Boolean = false,
    ): EntitlementState {
        // Anti-rollback guard: ignore device clock tampering backward > 5 minutes.
        val effectiveNowMs = if (nowEpochMs < snapshot.maxObservedEpochMs - CLOCK_ROLLBACK_TOLERANCE_MS) {
            snapshot.maxObservedEpochMs
        } else {
            nowEpochMs
        }

        val effectivePreviewStart = listOfNotNull(
            snapshot.localFirstLaunchEpochMs,
            snapshot.serverFirstSeenEpochMs,
        ).minOrNull() ?: snapshot.localFirstLaunchEpochMs
        val previewExpiresMs = effectivePreviewStart + PREVIEW_DURATION_MS

        if (allowDebugOverride && snapshot.debugOverride == DebugEntitlementOverride.FORCE_ALPHA) {
            return EntitlementState(
                tier = SubscriptionTier.ALPHA,
                source = EntitlementSource.DEBUG_OVERRIDE,
                previewStartedEpochMs = effectivePreviewStart,
                previewExpiresEpochMs = previewExpiresMs,
                subscriptionExpiresEpochMs = snapshot.subscriptionExpiresEpochMs,
                lastVerifiedEpochMs = snapshot.lastVerifiedEpochMs,
                isOfflineGraceActive = false,
                remainingPreviewMs = (previewExpiresMs - effectiveNowMs).coerceAtLeast(0L),
                supportUserId = snapshot.supportUserId,
                debugOverride = snapshot.debugOverride,
            )
        }

        if (allowDebugOverride && snapshot.debugOverride == DebugEntitlementOverride.FORCE_SCOUT) {
            return EntitlementState(
                tier = SubscriptionTier.SCOUT,
                source = EntitlementSource.DEBUG_OVERRIDE,
                previewStartedEpochMs = effectivePreviewStart,
                previewExpiresEpochMs = previewExpiresMs,
                subscriptionExpiresEpochMs = snapshot.subscriptionExpiresEpochMs,
                lastVerifiedEpochMs = snapshot.lastVerifiedEpochMs,
                isOfflineGraceActive = false,
                remainingPreviewMs = 0L,
                supportUserId = snapshot.supportUserId,
                debugOverride = snapshot.debugOverride,
            )
        }

        val isPaidSource = snapshot.lastKnownSource == EntitlementSource.PLAY_SUBSCRIPTION ||
            snapshot.lastKnownSource == EntitlementSource.PROMO_COMP

        if (snapshot.lastKnownTier == SubscriptionTier.ALPHA && isPaidSource) {
            val subExp = snapshot.subscriptionExpiresEpochMs
            val isDirectlyActive = subExp == null || effectiveNowMs < subExp
            val verifiedMs = snapshot.lastVerifiedEpochMs
            val isWithinOfflineGrace = verifiedMs != null &&
                (effectiveNowMs - verifiedMs) in 0L..OFFLINE_GRACE_WINDOW_MS

            if (isDirectlyActive || isWithinOfflineGrace) {
                return EntitlementState(
                    tier = SubscriptionTier.ALPHA,
                    source = snapshot.lastKnownSource,
                    previewStartedEpochMs = effectivePreviewStart,
                    previewExpiresEpochMs = previewExpiresMs,
                    subscriptionExpiresEpochMs = subExp,
                    lastVerifiedEpochMs = verifiedMs,
                    isOfflineGraceActive = !isDirectlyActive && isWithinOfflineGrace,
                    remainingPreviewMs = (previewExpiresMs - effectiveNowMs).coerceAtLeast(0L),
                    supportUserId = snapshot.supportUserId,
                    debugOverride = snapshot.debugOverride,
                )
            }
        }

        val previewBypassed = allowDebugOverride &&
            snapshot.debugOverride == DebugEntitlementOverride.EXPIRE_36H_PREVIEW

        val remainingPreviewMs = if (previewBypassed) {
            0L
        } else {
            (previewExpiresMs - effectiveNowMs).coerceAtLeast(0L)
        }

        return if (remainingPreviewMs > 0L) {
            EntitlementState(
                tier = SubscriptionTier.ALPHA,
                source = EntitlementSource.ALPHA_PREVIEW_36H,
                previewStartedEpochMs = effectivePreviewStart,
                previewExpiresEpochMs = previewExpiresMs,
                subscriptionExpiresEpochMs = snapshot.subscriptionExpiresEpochMs,
                lastVerifiedEpochMs = snapshot.lastVerifiedEpochMs,
                isOfflineGraceActive = false,
                remainingPreviewMs = remainingPreviewMs,
                supportUserId = snapshot.supportUserId,
                debugOverride = snapshot.debugOverride,
            )
        } else {
            EntitlementState(
                tier = SubscriptionTier.SCOUT,
                source = EntitlementSource.NONE,
                previewStartedEpochMs = effectivePreviewStart,
                previewExpiresEpochMs = previewExpiresMs,
                subscriptionExpiresEpochMs = snapshot.subscriptionExpiresEpochMs,
                lastVerifiedEpochMs = snapshot.lastVerifiedEpochMs,
                isOfflineGraceActive = false,
                remainingPreviewMs = 0L,
                supportUserId = snapshot.supportUserId,
                debugOverride = snapshot.debugOverride,
            )
        }
    }
}

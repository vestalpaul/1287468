package com.buckcast.feature.settings

import com.buckcast.domain.entitlement.DebugEntitlementOverride
import com.buckcast.domain.entitlement.EntitlementSource
import com.buckcast.domain.entitlement.EntitlementState
import com.buckcast.domain.entitlement.SubscriptionTier
import com.buckcast.domain.model.AppRuntimeConfig
import com.buckcast.domain.model.AppSettings
import com.buckcast.domain.model.AppThemePreset
import com.buckcast.domain.repository.EntitlementRepository
import com.buckcast.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `Alpha theme selection is gated on Scout and reverts effectiveThemePreset automatically`() = runTest(testDispatcher) {
        val fakeSettingsRepo = FakeSettingsRepo()
        val fakeEntitlementRepo = FakeEntitlementRepo(isAlpha = true)
        val vm = SettingsViewModel(
            settingsRepository = fakeSettingsRepo,
            entitlementRepository = fakeEntitlementRepo,
            runtimeConfig = AppRuntimeConfig(isDebugBuild = true, revenueCatPublicKey = "", mapTilerApiKey = ""),
        )
        val collectJob = launch { vm.uiState.collect() }
        advanceUntilIdle()

        // While Alpha preview is active, STEALTH_CRIMSON succeeds
        assertTrue(vm.selectThemePreset(AppThemePreset.STEALTH_CRIMSON))
        advanceUntilIdle()
        assertEquals(AppThemePreset.STEALTH_CRIMSON, vm.uiState.value.effectiveThemePreset)

        // When preview locks back to SCOUT, effective theme gracefully reverts to TIMBERLINE_DARK
        fakeEntitlementRepo.setTier(SubscriptionTier.SCOUT)
        advanceUntilIdle()
        assertEquals(AppThemePreset.TIMBERLINE_DARK, vm.uiState.value.effectiveThemePreset)

        // Selecting an Alpha theme while Scout returns false to open paywall
        assertFalse(vm.selectThemePreset(AppThemePreset.BLAZE_SLATE))

        collectJob.cancel()
    }

    @Test
    fun `rut calibration and legal light offsets persist cleanly`() = runTest(testDispatcher) {
        val fakeSettingsRepo = FakeSettingsRepo()
        val fakeEntitlementRepo = FakeEntitlementRepo(isAlpha = true)
        val vm = SettingsViewModel(
            settingsRepository = fakeSettingsRepo,
            entitlementRepository = fakeEntitlementRepo,
            runtimeConfig = AppRuntimeConfig(isDebugBuild = false, revenueCatPublicKey = "", mapTilerApiKey = ""),
        )
        val collectJob = launch { vm.uiState.collect() }
        advanceUntilIdle()

        vm.updatePeakRutDate(11, 20)
        vm.updateLegalLightOffsets(20L, 20L)
        advanceUntilIdle()

        assertEquals("11-20", vm.uiState.value.settings.peakRutMonthDay)
        assertEquals(20L, vm.uiState.value.settings.legalLightBeforeSunriseMinutes)
        assertEquals(20L, vm.uiState.value.settings.legalLightAfterSunsetMinutes)

        collectJob.cancel()
    }

    private class FakeSettingsRepo : SettingsRepository {
        private val flow = MutableStateFlow(AppSettings())
        override fun observeSettings(): Flow<AppSettings> = flow
        override suspend fun getSettings(): AppSettings = flow.value
        override suspend fun updateLastKnownLocation(latitude: Double, longitude: Double) {
            flow.value = flow.value.copy(lastKnownLat = latitude, lastKnownLon = longitude)
        }
        override suspend fun updateSettings(settings: AppSettings) {
            flow.value = settings
        }
    }

    private class FakeEntitlementRepo(isAlpha: Boolean) : EntitlementRepository {
        private val flow = MutableStateFlow(stateFor(if (isAlpha) SubscriptionTier.ALPHA else SubscriptionTier.SCOUT))
        fun setTier(tier: SubscriptionTier) {
            flow.value = stateFor(tier)
        }
        override fun observeEntitlement(): Flow<EntitlementState> = flow
        override suspend fun ensureInitialized() = Unit
        override suspend fun refreshEntitlements(): Result<EntitlementState> = Result.success(flow.value)
        override suspend fun restorePurchases(): Result<EntitlementState> = Result.success(flow.value)
        override suspend fun logInWithAccessCode(code: String): Result<EntitlementState> = Result.success(flow.value)
        override suspend fun purchaseAlpha(isAnnual: Boolean): Result<EntitlementState> = Result.success(flow.value)
        override suspend fun getSupportUserId(): String = "dt_test"
        override suspend fun setDebugOverride(override: DebugEntitlementOverride) = Unit
        override suspend fun reset36HourPreview() = Unit

        private fun stateFor(tier: SubscriptionTier) = EntitlementState(
            tier = tier,
            source = if (tier == SubscriptionTier.ALPHA) EntitlementSource.ALPHA_PREVIEW_36H else EntitlementSource.NONE,
            previewStartedEpochMs = 0L,
            previewExpiresEpochMs = 36L * 3_600_000L,
            subscriptionExpiresEpochMs = null,
            lastVerifiedEpochMs = null,
            isOfflineGraceActive = false,
            remainingPreviewMs = if (tier == SubscriptionTier.ALPHA) 12L * 3_600_000L else 0L,
            supportUserId = "dt_test",
        )
    }
}

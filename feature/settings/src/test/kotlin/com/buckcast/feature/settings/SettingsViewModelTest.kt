package com.buckcast.feature.settings

import com.buckcast.domain.entitlement.DebugEntitlementOverride
import com.buckcast.domain.entitlement.EntitlementEvaluator
import com.buckcast.domain.entitlement.EntitlementSnapshot
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

    @Test
    fun `debug entitlement switcher transitions cleanly between FORCE_ALPHA, FORCE_SCOUT, EXPIRE_36H_PREVIEW, RESET_36H_PREVIEW and AUTO`() =
        runTest(testDispatcher) {
            val fakeSettingsRepo = FakeSettingsRepo()
            val fakeEntitlementRepo = FakeEntitlementRepo(isAlpha = true)
            val vm = SettingsViewModel(
                settingsRepository = fakeSettingsRepo,
                entitlementRepository = fakeEntitlementRepo,
                runtimeConfig = AppRuntimeConfig(isDebugBuild = true, revenueCatPublicKey = "", mapTilerApiKey = ""),
            )
            val collectJob = launch { vm.uiState.collect() }
            advanceUntilIdle()

            assertEquals(DebugEntitlementOverride.AUTO, vm.uiState.value.entitlement.debugOverride)
            assertTrue(vm.uiState.value.entitlement.isAlpha)

            vm.setDebugOverride(DebugEntitlementOverride.FORCE_ALPHA)
            advanceUntilIdle()
            assertEquals(DebugEntitlementOverride.FORCE_ALPHA, vm.uiState.value.entitlement.debugOverride)
            assertTrue(vm.uiState.value.entitlement.isAlpha)

            vm.setDebugOverride(DebugEntitlementOverride.FORCE_SCOUT)
            advanceUntilIdle()
            assertEquals(DebugEntitlementOverride.FORCE_SCOUT, vm.uiState.value.entitlement.debugOverride)
            assertFalse(vm.uiState.value.entitlement.isAlpha)

            vm.setDebugOverride(DebugEntitlementOverride.EXPIRE_36H_PREVIEW)
            advanceUntilIdle()
            assertEquals(DebugEntitlementOverride.EXPIRE_36H_PREVIEW, vm.uiState.value.entitlement.debugOverride)
            assertFalse(vm.uiState.value.entitlement.isAlpha)

            vm.reset36HourPreview()
            advanceUntilIdle()
            assertEquals(DebugEntitlementOverride.AUTO, vm.uiState.value.entitlement.debugOverride)
            assertTrue(vm.uiState.value.entitlement.isAlpha)
            assertEquals(EntitlementSource.ALPHA_PREVIEW_36H, vm.uiState.value.entitlement.source)

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
        private var nowMs = if (isAlpha) 10_000L else 40L * 3_600_000L
        private var snapshot = EntitlementSnapshot(
            localFirstLaunchEpochMs = 0L,
            maxObservedEpochMs = nowMs,
            supportUserId = "dt_test",
        )
        private val flow = MutableStateFlow(evaluate())

        fun setTier(tier: SubscriptionTier) {
            nowMs = if (tier == SubscriptionTier.ALPHA) 10_000L else 40L * 3_600_000L
            snapshot = snapshot.copy(maxObservedEpochMs = nowMs)
            flow.value = evaluate()
        }

        override fun observeEntitlement(): Flow<EntitlementState> = flow
        override suspend fun ensureInitialized() = Unit
        override suspend fun refreshEntitlements(): Result<EntitlementState> = Result.success(flow.value)
        override suspend fun restorePurchases(): Result<EntitlementState> = Result.success(flow.value)
        override suspend fun logInWithAccessCode(code: String): Result<EntitlementState> = Result.success(flow.value)
        override suspend fun purchaseAlpha(isAnnual: Boolean): Result<EntitlementState> = Result.success(flow.value)
        override suspend fun getSupportUserId(): String = "dt_test"
        override suspend fun setDebugOverride(override: DebugEntitlementOverride) {
            snapshot = snapshot.copy(debugOverride = override)
            flow.value = evaluate()
        }
        override suspend fun reset36HourPreview() {
            snapshot = EntitlementSnapshot(
                localFirstLaunchEpochMs = nowMs,
                maxObservedEpochMs = nowMs,
                debugOverride = DebugEntitlementOverride.AUTO,
                supportUserId = "dt_test",
            )
            flow.value = evaluate()
        }

        private fun evaluate(): EntitlementState =
            EntitlementEvaluator.evaluate(snapshot, nowEpochMs = nowMs, allowDebugOverride = true)
    }
}

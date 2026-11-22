package com.buckcast.feature.settings

import com.buckcast.domain.entitlement.DebugEntitlementOverride
import com.buckcast.domain.entitlement.EntitlementEvaluator
import com.buckcast.domain.entitlement.EntitlementSnapshot
import com.buckcast.domain.entitlement.EntitlementSource
import com.buckcast.domain.entitlement.EntitlementState
import com.buckcast.domain.entitlement.SubscriptionTier
import com.buckcast.domain.math.GeoPoint
import com.buckcast.domain.model.AppRuntimeConfig
import com.buckcast.domain.model.AppSettings
import com.buckcast.domain.model.AppThemePreset
import com.buckcast.domain.repository.EntitlementRepository
import com.buckcast.domain.repository.SettingsRepository
import com.buckcast.sensors.CompassAccuracy
import com.buckcast.sensors.CompassPosture
import com.buckcast.sensors.CompassReading
import com.buckcast.sensors.CompassSensorSource
import com.buckcast.sensors.RotationVectorCompass
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
            compass = FakeCompass(),
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
            compass = FakeCompass(),
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
                compass = FakeCompass(),
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

    @Test
    fun `compass trim offset clamps to +-15 deg, persists to settings, syncs RotationVectorCompass, and recalibrate resets sensor fusion`() =
        runTest(testDispatcher) {
            val fakeSettingsRepo = FakeSettingsRepo(
                AppSettings(lastKnownLat = 41.5868, lastKnownLon = -93.6250),
            )
            val fakeEntitlementRepo = FakeEntitlementRepo(isAlpha = false)
            val fakeCompass = FakeCompass()
            val vm = SettingsViewModel(
                settingsRepository = fakeSettingsRepo,
                entitlementRepository = fakeEntitlementRepo,
                runtimeConfig = AppRuntimeConfig(isDebugBuild = true, revenueCatPublicKey = "", mapTilerApiKey = ""),
                compass = fakeCompass,
            )
            val collectJob = launch { vm.uiState.collect() }
            advanceUntilIdle()

            assertEquals(GeoPoint(41.5868, -93.6250), fakeCompass.seededLocation)

            // Live GPS fix takes precedence and is not overwritten by subsequent settings emissions
            val liveFix = GeoPoint(42.0000, -94.0000)
            fakeCompass.updateLocation(liveFix)
            vm.updateCompassOffsetDeg(4.2) // snaps to nearest 0.5 -> 4.0
            advanceUntilIdle()
            assertEquals(4.0, vm.uiState.value.settings.compassOffsetDeg, 1e-6)
            assertEquals(4.0, fakeCompass.getCompassOffsetDeg(), 1e-6)
            assertEquals(liveFix, fakeCompass.seededLocation)

            // Out of bounds clamps cleanly to +15.0 / -15.0
            vm.updateCompassOffsetDeg(28.0)
            advanceUntilIdle()
            assertEquals(15.0, vm.uiState.value.settings.compassOffsetDeg, 1e-6)
            assertEquals(15.0, fakeCompass.getCompassOffsetDeg(), 1e-6)

            vm.updateCompassOffsetDeg(-22.0)
            advanceUntilIdle()
            assertEquals(-15.0, vm.uiState.value.settings.compassOffsetDeg, 1e-6)

            vm.recalibrateCompass()
            advanceUntilIdle()
            assertEquals(1, fakeCompass.recalibrateCalls)
            assertEquals("Compass sensor fusion reset", vm.uiState.value.statusMessage)

            collectJob.cancel()
        }

    private class FakeCompass : RotationVectorCompass() {
        var recalibrateCalls = 0
            private set
        var seededLocation: GeoPoint? = null
            private set

        override fun updateLocation(point: GeoPoint?) {
            super.updateLocation(point)
            seededLocation = point
        }

        override fun updateLocationIfUnset(point: GeoPoint) {
            super.updateLocationIfUnset(point)
            if (seededLocation == null) {
                seededLocation = point
            }
        }

        override fun recalibrate() {
            recalibrateCalls++
        }

        override fun observeHeading(): Flow<CompassReading> = MutableStateFlow(
            CompassReading(
                trueHeadingDeg = 180.0,
                magneticHeadingDeg = 178.0,
                declinationDeg = 2.0,
                pitchDeg = 0.0,
                posture = CompassPosture.UPRIGHT_AIMING,
                accuracy = CompassAccuracy.HIGH,
                sensorSource = CompassSensorSource.ROTATION_VECTOR,
                isSensorAvailable = true,
                timestampEpochMs = 1_000L,
            ),
        )
    }

    private class FakeSettingsRepo(
        initial: AppSettings = AppSettings(),
    ) : SettingsRepository {
        private val flow = MutableStateFlow(initial)
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

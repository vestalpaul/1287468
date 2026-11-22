package com.buckcast.feature.forecast

import com.buckcast.domain.entitlement.DebugEntitlementOverride
import com.buckcast.domain.entitlement.EntitlementSource
import com.buckcast.domain.entitlement.EntitlementState
import com.buckcast.domain.entitlement.SubscriptionTier
import com.buckcast.domain.math.BearingSector
import com.buckcast.domain.math.GeoPoint
import com.buckcast.domain.model.AppSettings
import com.buckcast.domain.model.CachedWeatherForecast
import com.buckcast.domain.model.DailyWeatherSummary
import com.buckcast.domain.model.HourlyWeatherPoint
import com.buckcast.domain.model.Stand
import com.buckcast.domain.model.StandRoute
import com.buckcast.domain.model.StandSit
import com.buckcast.domain.model.StandType
import com.buckcast.domain.model.TerrainPosition
import com.buckcast.domain.repository.EntitlementRepository
import com.buckcast.domain.repository.SettingsRepository
import com.buckcast.domain.repository.StandRepository
import com.buckcast.domain.repository.WeatherRepository
import com.buckcast.domain.wind.StandWindVerdict
import com.buckcast.sensors.LocationPowerMode
import com.buckcast.sensors.LocationProvider
import com.buckcast.sensors.LocationState
import com.google.android.gms.location.FusedLocationProviderClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.UnknownHostException
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class ForecastViewModelTest {

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
    fun `ForecastViewModel builds 7-day outlook, ranks stands by live wind, and formats offline fallback cleanly`() =
        runTest(testDispatcher) {
            val today = LocalDate.now(ZoneId.systemDefault())
            val cached = CachedWeatherForecast(
                gridKey = "41.60,-93.65",
                latitude = 41.5868,
                longitude = -93.6250,
                elevationFeet = 950.0,
                timezoneId = ZoneId.systemDefault().id,
                fetchedAtEpochMs = System.currentTimeMillis() - 600_000L,
                isStale = false,
                hourly = listOf(
                    HourlyWeatherPoint(
                        epochSeconds = System.currentTimeMillis() / 1000L,
                        isoLocalTime = "${today}T07:00",
                        tempDegF = 34.0,
                        apparentTempDegF = 28.0,
                        barometricPressureInHg = 30.22,
                        pressureTrend3hInHg = 0.06,
                        windSpeedMph = 7.0,
                        windGustMph = 10.0,
                        windFromDeg = 0.0, // North wind
                        precipInchesPerHr = 0.0,
                        cloudCoverPercent = 15.0,
                        weatherCode = 1,
                    ),
                ),
                daily = listOf(
                    DailyWeatherSummary(
                        isoDate = today.toString(),
                        tempMaxDegF = 44.0,
                        tempMinDegF = 26.0,
                        precipSumInches = 0.0,
                        windSpeedMaxMph = 9.0,
                        dominantWindFromDeg = 0.0,
                        weatherCode = 1,
                    ),
                ),
            )

            val weatherRepo = object : WeatherRepository {
                private val flow = MutableStateFlow<CachedWeatherForecast?>(cached)
                override fun observeForecast(latitude: Double, longitude: Double): Flow<CachedWeatherForecast?> = flow
                override fun observeLatestCachedForecast(): Flow<CachedWeatherForecast?> = flow
                override suspend fun refreshForecast(latitude: Double, longitude: Double): Result<CachedWeatherForecast> =
                    Result.failure(UnknownHostException("Unable to resolve host"))
            }

            val standGood = Stand(
                id = "s-good",
                name = "North Wind Pinch",
                location = GeoPoint(41.58, -93.62),
                standType = StandType.HANG_ON,
                standHeightFeet = 20.0,
                terrainPosition = TerrainPosition.UPPER_THIRD,
                preferredWindSectors = listOf(BearingSector(315.0, 45.0)),
                createdAtEpochMs = 1L,
            )
            val standBad = Stand(
                id = "s-bad",
                name = "South Food Edge",
                location = GeoPoint(41.58, -93.62),
                standType = StandType.LADDER_STAND,
                standHeightFeet = 16.0,
                terrainPosition = TerrainPosition.FOOD_EDGE,
                preferredWindSectors = listOf(BearingSector(135.0, 225.0)),
                createdAtEpochMs = 2L,
            )

            val standRepo = object : StandRepository {
                override fun observeStands(): Flow<List<Stand>> = flowOf(listOf(standBad, standGood))
                override fun observeRoutesForStand(standId: String): Flow<List<StandRoute>> = flowOf(emptyList())
                override fun observeSitsForStand(standId: String): Flow<List<StandSit>> = flowOf(emptyList())
                override suspend fun saveStand(stand: Stand) = Unit
                override suspend fun deleteStand(standId: String) = Unit
                override suspend fun saveRoute(route: StandRoute) = Unit
                override suspend fun recordSit(sit: StandSit) = Unit
            }

            val settingsRepo = object : SettingsRepository {
                private val flow = MutableStateFlow(AppSettings())
                override fun observeSettings(): Flow<AppSettings> = flow
                override suspend fun getSettings(): AppSettings = flow.value
                override suspend fun updateLastKnownLocation(latitude: Double, longitude: Double) = Unit
                override suspend fun updateSettings(settings: AppSettings) {
                    flow.value = settings
                }
            }

            val entitlementRepo = object : EntitlementRepository {
                private val flow = MutableStateFlow(
                    EntitlementState(
                        tier = SubscriptionTier.ALPHA,
                        source = EntitlementSource.ALPHA_PREVIEW_36H,
                        previewStartedEpochMs = 0L,
                        previewExpiresEpochMs = 36L * 3_600_000L,
                        subscriptionExpiresEpochMs = null,
                        lastVerifiedEpochMs = null,
                        isOfflineGraceActive = false,
                        remainingPreviewMs = 20L * 3_600_000L,
                    ),
                )
                override fun observeEntitlement(): Flow<EntitlementState> = flow
                override suspend fun ensureInitialized() = Unit
                override suspend fun refreshEntitlements(): Result<EntitlementState> = Result.success(flow.value)
                override suspend fun restorePurchases(): Result<EntitlementState> = Result.success(flow.value)
                override suspend fun logInWithAccessCode(code: String): Result<EntitlementState> = Result.success(flow.value)
                override suspend fun purchaseAlpha(isAnnual: Boolean): Result<EntitlementState> = Result.success(flow.value)
                override suspend fun getSupportUserId(): String = "dt_fc"
                override suspend fun setDebugOverride(override: DebugEntitlementOverride) = Unit
                override suspend fun reset36HourPreview() = Unit
            }

            val fakeLocationProvider = object : LocationProvider(
                java.lang.reflect.Proxy.newProxyInstance(
                    FusedLocationProviderClient::class.java.classLoader,
                    arrayOf(FusedLocationProviderClient::class.java),
                ) { _, _, _ -> null } as FusedLocationProviderClient,
            ) {
                override fun observeLocation(mode: LocationPowerMode): Flow<LocationState> = flowOf(LocationState.Searching)
            }

            val vm = ForecastViewModel(
                weatherRepository = weatherRepo,
                settingsRepository = settingsRepo,
                standRepository = standRepo,
                entitlementRepository = entitlementRepo,
                locationProvider = fakeLocationProvider,
            )

            val job = launch { vm.uiState.collect() }
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(7, state.sevenDayOutlook.size)
            assertNotNull(state.todayOutlook)
            assertTrue(state.offlineBannerMessage.orEmpty().startsWith("Offline — showing cached forecast from "))
            assertEquals(2, state.rankedStands.size)
            assertEquals("s-good", state.rankedStands.first().stand.id)
            assertEquals(StandWindVerdict.OPTIMAL, state.rankedStands.first().evaluation.verdict)
            assertFalse(state.rankedStands.first().isLockedByScout)

            assertTrue(vm.openDayIntel(0))
            advanceUntilIdle()
            assertEquals(0, vm.uiState.value.selectedIntelDayIndex)

            job.cancel()
        }
}

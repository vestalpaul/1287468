package com.buckcast.feature.stands

import com.buckcast.domain.entitlement.DebugEntitlementOverride
import com.buckcast.domain.entitlement.EntitlementSource
import com.buckcast.domain.entitlement.EntitlementState
import com.buckcast.domain.entitlement.SubscriptionTier
import com.buckcast.domain.math.GeoPoint
import com.buckcast.domain.model.CachedWeatherForecast
import com.buckcast.domain.model.FieldLog
import com.buckcast.domain.model.Sighting
import com.buckcast.domain.model.Stand
import com.buckcast.domain.model.StandRoute
import com.buckcast.domain.model.StandSit
import com.buckcast.domain.model.StandType
import com.buckcast.domain.model.TerrainPosition
import com.buckcast.domain.model.TrackPoint
import com.buckcast.domain.model.TrackSession
import com.buckcast.domain.model.Waypoint
import com.buckcast.domain.model.WaypointCategory
import com.buckcast.domain.model.WeaponProfile
import com.buckcast.domain.repository.EntitlementRepository
import com.buckcast.domain.repository.FieldDataRepository
import com.buckcast.domain.repository.StandRepository
import com.buckcast.domain.repository.WeatherRepository
import com.buckcast.domain.stands.StandFreshnessLevel
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
import kotlinx.coroutines.flow.map
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
class StandsViewModelTest {

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
    fun `Scout enforces 1 stand and 3 waypoint limit while preserving extra stands read-only after preview expires`() =
        runTest(testDispatcher) {
            val standRepo = FakeStandRepo()
            val fieldRepo = FakeFieldRepo()
            val entitlementRepo = FakeEntitlementRepo(SubscriptionTier.ALPHA)
            val vm = StandsViewModel(
                standRepository = standRepo,
                fieldDataRepository = fieldRepo,
                weatherRepository = FakeWeatherRepo(),
                entitlementRepository = entitlementRepo,
                locationProvider = fakeLocationProvider(),
            )
            val job = launch { vm.uiState.collect() }
            advanceUntilIdle()

            // During Alpha preview, user creates 2 stands and 4 waypoints
            assertTrue(
                vm.saveStand(
                    existingId = null,
                    name = "Stand One",
                    location = GeoPoint(41.58, -93.62),
                    standType = StandType.HANG_ON,
                    standHeightFeet = 20.0,
                    terrainPosition = TerrainPosition.UPPER_THIRD,
                    selectedWindDirs = setOf(WindRoseDirection.N),
                    slopeAspectDeg = 180.0,
                    slopeGradePercent = 15.0,
                    notes = "",
                ),
            )
            advanceUntilIdle()
            assertTrue(
                vm.saveStand(
                    existingId = null,
                    name = "Stand Two",
                    location = GeoPoint(41.59, -93.62),
                    standType = StandType.SADDLE,
                    standHeightFeet = 18.0,
                    terrainPosition = TerrainPosition.CREEK_BOTTOM,
                    selectedWindDirs = setOf(WindRoseDirection.NW),
                    slopeAspectDeg = null,
                    slopeGradePercent = null,
                    notes = "",
                ),
            )
            repeat(4) { idx ->
                assertTrue(vm.saveWaypoint(WaypointCategory.SCRAPE, "Scrape $idx", GeoPoint(41.58 + idx * 0.001, -93.62)))
            }
            advanceUntilIdle()
            assertEquals(2, vm.uiState.value.stands.size)
            assertEquals(4, vm.uiState.value.waypoints.size)
            assertTrue(vm.uiState.value.stands.none { it.isReadOnlyLocked })

            // Lock back to SCOUT -> both stands preserved, 2nd stand read-only locked, 4th waypoint locked
            entitlementRepo.setTier(SubscriptionTier.SCOUT)
            advanceUntilIdle()

            assertEquals(2, vm.uiState.value.stands.size)
            assertFalse(vm.uiState.value.stands.first { it.stand.name == "Stand One" }.isReadOnlyLocked)
            assertTrue(vm.uiState.value.stands.first { it.stand.name == "Stand Two" }.isReadOnlyLocked)
            assertEquals(1, vm.uiState.value.lockedWaypointIds.size)

            // Attempting to add a new stand or waypoint on Scout returns false (triggers paywall)
            assertFalse(
                vm.saveStand(
                    existingId = null,
                    name = "Stand Three",
                    location = GeoPoint(41.60, -93.62),
                    standType = StandType.CLIMBER,
                    standHeightFeet = 18.0,
                    terrainPosition = TerrainPosition.MID_SLOPE,
                    selectedWindDirs = setOf(WindRoseDirection.W),
                    slopeAspectDeg = null,
                    slopeGradePercent = null,
                    notes = "",
                ),
            )
            assertFalse(vm.saveWaypoint(WaypointCategory.RUB, "Extra Rub", GeoPoint(41.58, -93.62)))

            job.cancel()
        }

    @Test
    fun `startSit and endSit with windedDeer persist sit record and update StandFreshness`() =
        runTest(testDispatcher) {
            val standRepo = FakeStandRepo()
            val entitlementRepo = FakeEntitlementRepo(SubscriptionTier.ALPHA)
            val vm = StandsViewModel(
                standRepository = standRepo,
                fieldDataRepository = FakeFieldRepo(),
                weatherRepository = FakeWeatherRepo(),
                entitlementRepository = entitlementRepo,
                locationProvider = fakeLocationProvider(),
            )
            val job = launch { vm.uiState.collect() }
            advanceUntilIdle()

            vm.saveStand(
                existingId = null,
                name = "Bottom Oak",
                location = GeoPoint(41.58, -93.62),
                standType = StandType.HANG_ON,
                standHeightFeet = 20.0,
                terrainPosition = TerrainPosition.CREEK_BOTTOM,
                selectedWindDirs = setOf(WindRoseDirection.N, WindRoseDirection.NE),
                slopeAspectDeg = null,
                slopeGradePercent = null,
                notes = "",
            )
            advanceUntilIdle()

            val standId = vm.uiState.value.stands.first().stand.id
            assertEquals(StandFreshnessLevel.FRESH, vm.uiState.value.stands.first().freshness.level)

            assertTrue(vm.startSit(standId))
            advanceUntilIdle()
            vm.endSit(standId, windedDeer = true)
            advanceUntilIdle()

            assertEquals(StandFreshnessLevel.BURNED, vm.uiState.value.stands.first().freshness.level)

            job.cancel()
        }

    private fun fakeLocationProvider(): LocationProvider = object : LocationProvider(
        java.lang.reflect.Proxy.newProxyInstance(
            FusedLocationProviderClient::class.java.classLoader,
            arrayOf(FusedLocationProviderClient::class.java),
        ) { _, _, _ -> null } as FusedLocationProviderClient,
    ) {
        override fun observeLocation(mode: LocationPowerMode): Flow<LocationState> = flowOf(LocationState.Searching)
    }

    private class FakeStandRepo : StandRepository {
        private val stands = MutableStateFlow<List<Stand>>(emptyList())
        private val routes = MutableStateFlow<List<StandRoute>>(emptyList())
        private val sits = MutableStateFlow<List<StandSit>>(emptyList())
        private var seq = 1L

        override fun observeStands(): Flow<List<Stand>> = stands
        override fun observeRoutesForStand(standId: String): Flow<List<StandRoute>> =
            routes.map { list -> list.filter { it.standId == standId } }
        override fun observeSitsForStand(standId: String): Flow<List<StandSit>> =
            sits.map { list -> list.filter { it.standId == standId } }
        override suspend fun saveStand(stand: Stand) {
            val stamped = if (stand.createdAtEpochMs <= 0L) stand.copy(createdAtEpochMs = seq++) else stand.copy(createdAtEpochMs = seq++)
            stands.value = stands.value.filterNot { it.id == stamped.id } + stamped
        }
        override suspend fun deleteStand(standId: String) {
            stands.value = stands.value.filterNot { it.id == standId }
        }
        override suspend fun saveRoute(route: StandRoute) {
            routes.value = routes.value.filterNot { it.id == route.id } + route
        }
        override suspend fun recordSit(sit: StandSit) {
            sits.value = sits.value + sit
        }
    }

    private class FakeFieldRepo : FieldDataRepository {
        private val waypoints = MutableStateFlow<List<Waypoint>>(emptyList())
        private var seq = 1L
        override fun observeWaypoints(): Flow<List<Waypoint>> = waypoints
        override fun observeTrackSessions(): Flow<List<TrackSession>> = flowOf(emptyList())
        override fun observeTrackPoints(sessionId: String): Flow<List<TrackPoint>> = flowOf(emptyList())
        override fun observeFieldLogs(): Flow<List<FieldLog>> = flowOf(emptyList())
        override fun observeSightings(): Flow<List<Sighting>> = flowOf(emptyList())
        override fun observeWeaponProfiles(): Flow<List<WeaponProfile>> = flowOf(emptyList())
        override suspend fun saveWaypoint(waypoint: Waypoint) {
            waypoints.value = waypoints.value + waypoint.copy(createdAtEpochMs = seq++)
        }
        override suspend fun deleteWaypoint(waypointId: String) = Unit
        override suspend fun startTrackSession(session: TrackSession) = Unit
        override suspend fun finishTrackSession(sessionId: String, endedAtEpochMs: Long) = Unit
        override suspend fun appendTrackPoint(point: TrackPoint) = Unit
        override suspend fun saveFieldLog(log: FieldLog) = Unit
        override suspend fun saveSighting(sighting: Sighting) = Unit
        override suspend fun saveWeaponProfile(profile: WeaponProfile) = Unit
    }

    private class FakeWeatherRepo : WeatherRepository {
        override fun observeForecast(latitude: Double, longitude: Double): Flow<CachedWeatherForecast?> = flowOf(null)
        override fun observeLatestCachedForecast(): Flow<CachedWeatherForecast?> = flowOf(null)
        override suspend fun refreshForecast(latitude: Double, longitude: Double): Result<CachedWeatherForecast> =
            Result.failure(IllegalStateException("unused"))
    }

    private class FakeEntitlementRepo(initial: SubscriptionTier) : EntitlementRepository {
        private val flow = MutableStateFlow(stateFor(initial))
        fun setTier(tier: SubscriptionTier) {
            flow.value = stateFor(tier)
        }
        override fun observeEntitlement(): Flow<EntitlementState> = flow
        override suspend fun ensureInitialized() = Unit
        override suspend fun refreshEntitlements(): Result<EntitlementState> = Result.success(flow.value)
        override suspend fun restorePurchases(): Result<EntitlementState> = Result.success(flow.value)
        override suspend fun logInWithAccessCode(code: String): Result<EntitlementState> = Result.success(flow.value)
        override suspend fun purchaseAlpha(isAnnual: Boolean): Result<EntitlementState> = Result.success(flow.value)
        override suspend fun getSupportUserId(): String = "dt_stands"
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
        )
    }
}

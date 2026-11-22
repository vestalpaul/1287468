package com.buckcast.feature.map

import com.buckcast.domain.entitlement.DebugEntitlementOverride
import com.buckcast.domain.entitlement.EntitlementSource
import com.buckcast.domain.entitlement.EntitlementState
import com.buckcast.domain.entitlement.SubscriptionTier
import com.buckcast.domain.math.BearingSector
import com.buckcast.domain.math.GeoPoint
import com.buckcast.domain.model.AppRuntimeConfig
import com.buckcast.domain.model.AppSettings
import com.buckcast.domain.model.CachedWeatherForecast
import com.buckcast.domain.model.HourlyWeatherPoint
import com.buckcast.domain.model.Stand
import com.buckcast.domain.model.StandRoute
import com.buckcast.domain.model.StandSit
import com.buckcast.domain.model.StandType
import com.buckcast.domain.model.TerrainPosition
import com.buckcast.domain.model.TrackPoint
import com.buckcast.domain.model.TrackSession
import com.buckcast.domain.model.Waypoint
import com.buckcast.domain.model.WeaponProfile
import com.buckcast.domain.model.FieldLog
import com.buckcast.domain.model.Sighting
import com.buckcast.domain.repository.EntitlementRepository
import com.buckcast.domain.repository.FieldDataRepository
import com.buckcast.domain.repository.SettingsRepository
import com.buckcast.domain.repository.StandRepository
import com.buckcast.domain.repository.WeatherRepository
import com.buckcast.domain.wind.StandWindVerdict
import com.buckcast.domain.wind.WindAlignmentBadge
import com.buckcast.sensors.CompassAccuracy
import com.buckcast.sensors.CompassPosture
import com.buckcast.sensors.CompassReading
import com.buckcast.sensors.CompassSensorSource
import com.buckcast.sensors.LocationPowerMode
import com.buckcast.sensors.LocationProvider
import com.buckcast.sensors.LocationState
import com.buckcast.sensors.RotationVectorCompass
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {

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
    fun `Scout tier blocks Satellite and Hillshade and auto-reverts active Alpha layers to Topo`() =
        runTest(testDispatcher) {
            val entitlementRepo = FakeEntitlementRepo(SubscriptionTier.ALPHA)
            val compassFlow = MutableStateFlow(compassReading(heading = 359.98))
            val vm = createViewModel(entitlementRepo = entitlementRepo, compassFlow = compassFlow)

            val job = launch { vm.uiState.collect() }
            advanceUntilIdle()

            assertTrue(vm.selectBaseLayer(MapBaseLayer.SATELLITE))
            assertTrue(vm.toggleHillshade())
            assertTrue(vm.toggleRangeRings())
            advanceUntilIdle()

            assertEquals(MapBaseLayer.SATELLITE, vm.uiState.value.baseLayer)
            assertTrue(vm.uiState.value.showHillshade)
            assertTrue(vm.uiState.value.showRangeRings)
            // Also verify 359.98 rounds to 0.0° rather than 360.0°
            assertEquals("0.0°", vm.uiState.value.formattedHeading)

            // Transition entitlement from Alpha preview -> Scout
            entitlementRepo.setTier(SubscriptionTier.SCOUT)
            advanceUntilIdle()

            assertEquals(MapBaseLayer.TOPO, vm.uiState.value.baseLayer)
            assertFalse(vm.uiState.value.showHillshade)
            assertFalse(vm.uiState.value.showRangeRings)
            assertNull(vm.uiState.value.windAlignment)
            assertFalse(vm.selectBaseLayer(MapBaseLayer.SATELLITE))
            assertFalse(vm.toggleHillshade())

            job.cancel()
        }

    @Test
    fun `WindAlignmentBadge applies 5-degree hysteresis across compass sector boundary`() =
        runTest(testDispatcher) {
            val compassFlow = MutableStateFlow(compassReading(heading = 30.0)) // Wind FROM 0° -> IN FACE
            val vm = createViewModel(
                entitlementRepo = FakeEntitlementRepo(SubscriptionTier.ALPHA),
                compassFlow = compassFlow,
                windFromDeg = 0.0,
            )
            val job = launch { vm.uiState.collect() }
            advanceUntilIdle()

            assertEquals(WindAlignmentBadge.WIND_IN_FACE, vm.uiState.value.windAlignment)

            // Cross 45° threshold slightly to 48° (< 50° hysteresis exit) -> stays WIND_IN_FACE
            compassFlow.value = compassReading(heading = 48.0)
            advanceUntilIdle()
            assertEquals(WindAlignmentBadge.WIND_IN_FACE, vm.uiState.value.windAlignment)

            // Move past 50° to 52° -> switches to CROSSWIND
            compassFlow.value = compassReading(heading = 52.0)
            advanceUntilIdle()
            assertEquals(WindAlignmentBadge.CROSSWIND, vm.uiState.value.windAlignment)

            // Drop back inside 45°..40° -> stays CROSSWIND at 42° until crossing 40°
            compassFlow.value = compassReading(heading = 42.0)
            advanceUntilIdle()
            assertEquals(WindAlignmentBadge.CROSSWIND, vm.uiState.value.windAlignment)

            compassFlow.value = compassReading(heading = 38.0)
            advanceUntilIdle()
            assertEquals(WindAlignmentBadge.WIND_IN_FACE, vm.uiState.value.windAlignment)

            job.cancel()
        }

    @Test
    fun `OfflineTileEstimator guardrail and TacticalGeoJsonBuilder produce valid geometry`() {
        val smallAreaTiles = OfflineTileEstimator.estimateTileCount(
            northLat = 41.60,
            southLat = 41.58,
            eastLon = -93.60,
            westLon = -93.64,
        )
        assertTrue(smallAreaTiles in 1L..OfflineTileEstimator.MAX_TILE_COUNT_GUARDRAIL)

        val hugeAreaTiles = OfflineTileEstimator.estimateTileCount(
            northLat = 43.5,
            southLat = 40.5,
            eastLon = -90.0,
            westLon = -96.5,
        )
        assertTrue(hugeAreaTiles > OfflineTileEstimator.MAX_TILE_COUNT_GUARDRAIL)

        val origin = GeoPoint(41.5868, -93.6250)
        val coneJson = TacticalGeoJsonBuilder.buildScentConeGeoJson(origin, scentToDeg = 135.0, lengthYards = 300.0)
        assertTrue(coneJson.contains("\"Polygon\""))
        assertTrue(coneJson.contains("\"scent_cone\""))

        val ringsJson = TacticalGeoJsonBuilder.buildRangeRingsGeoJson(origin)
        assertTrue(ringsJson.contains("\"50 yd\""))
        assertTrue(ringsJson.contains("\"100 yd\""))
        assertTrue(ringsJson.contains("\"200 yd\""))
        assertTrue(ringsJson.contains("\"300 yd\""))

        assertEquals("#4CAF50", TacticalGeoJsonBuilder.standPinColorHex(StandWindVerdict.GOOD, hasPreferredWind = true))
        assertEquals("#9E9E9E", TacticalGeoJsonBuilder.standPinColorHex(null, hasPreferredWind = false))

        val standsJson = TacticalGeoJsonBuilder.buildStandsGeoJson(
            listOf(
                StandMapPin(
                    id = "s-1",
                    name = "Ridge Stand",
                    location = origin,
                    verdict = StandWindVerdict.OPTIMAL,
                    colorHex = "#4CAF50",
                    isLocked = false,
                    isSelected = true,
                ),
            ),
        )
        assertTrue(standsJson.contains("\"Ridge Stand\""))
        assertTrue(standsJson.contains("\"#4CAF50\""))
    }

    @Test
    fun `MapTileConfig builds 256px Esri Satellite, multi-layer Hybrid, and Terrarium raster-dem Hillshade styles`() {
        assertEquals("topo.json", MapTileConfig.styleFileName(MapBaseLayer.TOPO, showHillshade = false))
        assertEquals("satellite.json", MapTileConfig.styleFileName(MapBaseLayer.SATELLITE, showHillshade = false))
        assertEquals("hybrid.json", MapTileConfig.styleFileName(MapBaseLayer.HYBRID, showHillshade = false))
        assertEquals("topo_hillshade.json", MapTileConfig.styleFileName(MapBaseLayer.TOPO, showHillshade = true))

        val usgsTopo = MapTileConfig.buildStyleJson(MapBaseLayer.TOPO, showHillshade = false, mapTilerApiKey = "")
        assertTrue(usgsTopo.contains("USGSTopo"))
        assertTrue(usgsTopo.contains("\"tileSize\": 256"))
        assertTrue(usgsTopo.contains("\"maxzoom\": 16"))
        assertFalse(usgsTopo.contains("raster-dem"))

        val esriSat = MapTileConfig.buildStyleJson(MapBaseLayer.SATELLITE, showHillshade = false, mapTilerApiKey = "")
        assertTrue(esriSat.contains(MapTileConfig.ESRI_WORLD_IMAGERY_URL))
        assertTrue(esriSat.contains("\"tileSize\": 256"))
        assertTrue(esriSat.contains("\"maxzoom\": 19"))

        val esriHybridWithHill = MapTileConfig.buildStyleJson(MapBaseLayer.HYBRID, showHillshade = true, mapTilerApiKey = "")
        assertTrue(esriHybridWithHill.contains(MapTileConfig.ESRI_WORLD_IMAGERY_URL))
        assertTrue(esriHybridWithHill.contains(MapTileConfig.ESRI_TRANSPORTATION_URL))
        assertTrue(esriHybridWithHill.contains(MapTileConfig.ESRI_BOUNDARIES_PLACES_URL))
        assertTrue(esriHybridWithHill.contains("\"type\": \"raster-dem\""))
        assertTrue(esriHybridWithHill.contains("\"encoding\": \"terrarium\""))
        assertTrue(esriHybridWithHill.contains("\"type\": \"hillshade\""))
        assertTrue(esriHybridWithHill.contains("\"hillshade-exaggeration\": 0.7"))

        val mapTilerSat = MapTileConfig.buildStyleJson(
            baseLayer = MapBaseLayer.SATELLITE,
            showHillshade = true,
            mapTilerApiKey = "mt_test_123",
        )
        assertTrue(mapTilerSat.contains("api.maptiler.com/tiles/satellite-v2"))
        assertTrue(mapTilerSat.contains("mt_test_123"))

        assertEquals("Open Sans Semibold", MapTileConfig.DEFAULT_GLYPH_FONT)
        for (layer in MapBaseLayer.entries) {
            for (hillshade in listOf(false, true)) {
                val styleJson = MapTileConfig.buildStyleJson(layer, hillshade, mapTilerApiKey = "")
                val sourcesSection = styleJson.substringAfter("\"sources\"").substringBefore("\"layers\"")
                val sourceBlockCount = Regex(""""type":\s*"raster(?:-dem)?"""").findAll(sourcesSection).count()
                val tileSizeCount = Regex(""""tileSize":\s*256""").findAll(sourcesSection).count()
                val maxZoomMatches = Regex(""""maxzoom":\s*(\d+)""").findAll(sourcesSection).toList()
                assertTrue("Expected at least 1 raster source for $layer/$hillshade", sourceBlockCount >= 1)
                assertEquals("Every source in $layer/$hillshade must declare tileSize 256", sourceBlockCount, tileSizeCount)
                assertEquals("Every source in $layer/$hillshade must declare maxzoom", sourceBlockCount, maxZoomMatches.size)
                maxZoomMatches.forEach { match ->
                    val z = match.groupValues[1].toInt()
                    assertTrue("Invalid maxzoom $z in $layer/$hillshade", z in 1..22)
                }
            }
        }
    }

    @Test
    fun `Scout mode banner clears when toggled off or leaving map and focusOnStand jumps camera`() =
        runTest(testDispatcher) {
            val entitlementRepo = FakeEntitlementRepo(SubscriptionTier.ALPHA)
            val compassFlow = MutableStateFlow(compassReading(heading = 180.0))
            val vm = createViewModel(entitlementRepo = entitlementRepo, compassFlow = compassFlow)
            val job = launch { vm.uiState.collect() }
            advanceUntilIdle()

            assertTrue(vm.toggleScoutMode())
            advanceUntilIdle()
            assertTrue(vm.uiState.value.scoutModeEnabled)
            assertTrue(vm.uiState.value.statusMessage.orEmpty().contains("Scout Mode active"))

            assertTrue(vm.toggleScoutMode())
            advanceUntilIdle()
            assertFalse(vm.uiState.value.scoutModeEnabled)
            assertNull(vm.uiState.value.statusMessage)

            assertTrue(vm.toggleScoutMode())
            advanceUntilIdle()
            vm.onLeaveMapScreen()
            advanceUntilIdle()
            assertNull(vm.uiState.value.statusMessage)

            val prevNonce = vm.uiState.value.camera.jumpNonce
            vm.focusOnStand("s-1")
            advanceUntilIdle()
            assertEquals(41.588, vm.uiState.value.camera.latitude, 1e-4)
            assertEquals(-93.624, vm.uiState.value.camera.longitude, 1e-4)
            assertTrue(vm.uiState.value.camera.jumpNonce > prevNonce)

            job.cancel()
        }

    @Test
    fun `focusOnStand and projectStandScent work before uiState is collected and recenter reports waiting when no GPS fix`() =
        runTest(testDispatcher) {
            val entitlementRepo = FakeEntitlementRepo(SubscriptionTier.ALPHA)
            val compassFlow = MutableStateFlow(compassReading(heading = 90.0))
            val vm = createViewModel(
                entitlementRepo = entitlementRepo,
                compassFlow = compassFlow,
                hasGpsFix = false,
            )
            // Allow SharingStarted.Eagerly flows (standsState / entitlementState) to receive initial emissions
            advanceUntilIdle()

            // Invoke cross-tab actions BEFORE any collector subscribes to vm.uiState
            vm.focusOnStand("s-1")
            assertTrue(vm.projectStandScent("s-1"))
            vm.recenterOnUserLocation()

            val job = launch { vm.uiState.collect() }
            advanceUntilIdle()

            assertEquals(41.588, vm.uiState.value.camera.latitude, 1e-4)
            assertEquals(-93.624, vm.uiState.value.camera.longitude, 1e-4)
            assertEquals("s-1", vm.uiState.value.projectedStandId)
            assertTrue(vm.uiState.value.camera.jumpNonce >= 2L)
            assertEquals("Waiting for GPS fix...", vm.uiState.value.statusMessage)

            job.cancel()
        }

    private fun compassReading(heading: Double): CompassReading = CompassReading(
        trueHeadingDeg = heading,
        magneticHeadingDeg = heading,
        declinationDeg = 0.0,
        pitchDeg = 10.0,
        posture = CompassPosture.FLAT_MAP,
        accuracy = CompassAccuracy.HIGH,
        sensorSource = CompassSensorSource.ROTATION_VECTOR,
        isSensorAvailable = true,
        timestampEpochMs = 1_000L,
    )

    private fun createViewModel(
        entitlementRepo: FakeEntitlementRepo,
        compassFlow: MutableStateFlow<CompassReading>,
        windFromDeg: Double = 0.0,
        hasGpsFix: Boolean = true,
    ): MapViewModel {
        val fakeCompass = object : RotationVectorCompass() {
            override fun observeHeading(): Flow<CompassReading> = compassFlow
        }
        val fakeClient = Proxy.newProxyInstance(
            FusedLocationProviderClient::class.java.classLoader,
            arrayOf(FusedLocationProviderClient::class.java),
        ) { _, _, _ -> null } as FusedLocationProviderClient
        val fakeLocationProvider = object : LocationProvider(fakeClient) {
            override fun observeLocation(mode: LocationPowerMode): Flow<LocationState> =
                if (hasGpsFix) {
                    flowOf(LocationState.Fix(GeoPoint(41.5868, -93.6250), 12.0, null, 1_000L))
                } else {
                    flowOf(LocationState.Searching)
                }
        }
        val stand = Stand(
            id = "s-1",
            name = "Ridge Stand",
            location = GeoPoint(41.588, -93.624),
            standType = StandType.HANG_ON,
            standHeightFeet = 20.0,
            terrainPosition = TerrainPosition.UPPER_THIRD,
            preferredWindSectors = listOf(BearingSector(315.0, 45.0)),
            createdAtEpochMs = 1L,
        )
        val forecast = CachedWeatherForecast(
            gridKey = "41.60,-93.60",
            latitude = 41.5868,
            longitude = -93.6250,
            elevationFeet = 950.0,
            timezoneId = ZoneId.systemDefault().id,
            fetchedAtEpochMs = System.currentTimeMillis(),
            isStale = false,
            hourly = listOf(
                HourlyWeatherPoint(
                    epochSeconds = System.currentTimeMillis() / 1000L,
                    isoLocalTime = "2026-11-11T07:00",
                    tempDegF = 34.0,
                    apparentTempDegF = 28.0,
                    barometricPressureInHg = 30.20,
                    pressureTrend3hInHg = 0.04,
                    windSpeedMph = 8.0,
                    windGustMph = 12.0,
                    windFromDeg = windFromDeg,
                    precipInchesPerHr = 0.0,
                    cloudCoverPercent = 10.0,
                    weatherCode = 0,
                ),
            ),
            daily = emptyList(),
        )
        return MapViewModel(
            standRepository = object : StandRepository {
                override fun observeStands(): Flow<List<Stand>> = flowOf(listOf(stand))
                override fun observeRoutesForStand(standId: String): Flow<List<StandRoute>> = flowOf(emptyList())
                override fun observeSitsForStand(standId: String): Flow<List<StandSit>> = flowOf(emptyList())
                override suspend fun saveStand(stand: Stand) = Unit
                override suspend fun deleteStand(standId: String) = Unit
                override suspend fun saveRoute(route: StandRoute) = Unit
                override suspend fun recordSit(sit: StandSit) = Unit
            },
            fieldDataRepository = object : FieldDataRepository {
                override fun observeWaypoints(): Flow<List<Waypoint>> = flowOf(emptyList())
                override fun observeTrackSessions(): Flow<List<TrackSession>> = flowOf(emptyList())
                override fun observeTrackPoints(sessionId: String): Flow<List<TrackPoint>> = flowOf(emptyList())
                override fun observeFieldLogs(): Flow<List<FieldLog>> = flowOf(emptyList())
                override fun observeSightings(): Flow<List<Sighting>> = flowOf(emptyList())
                override fun observeWeaponProfiles(): Flow<List<WeaponProfile>> = flowOf(emptyList())
                override suspend fun saveWaypoint(waypoint: Waypoint) = Unit
                override suspend fun deleteWaypoint(waypointId: String) = Unit
                override suspend fun startTrackSession(session: TrackSession) = Unit
                override suspend fun finishTrackSession(sessionId: String, endedAtEpochMs: Long) = Unit
                override suspend fun appendTrackPoint(point: TrackPoint) = Unit
                override suspend fun saveFieldLog(log: FieldLog) = Unit
                override suspend fun saveSighting(sighting: Sighting) = Unit
                override suspend fun saveWeaponProfile(profile: WeaponProfile) = Unit
            },
            weatherRepository = object : WeatherRepository {
                override fun observeForecast(latitude: Double, longitude: Double): Flow<CachedWeatherForecast?> =
                    flowOf(forecast)
                override fun observeLatestCachedForecast(): Flow<CachedWeatherForecast?> = flowOf(forecast)
                override suspend fun refreshForecast(latitude: Double, longitude: Double): Result<CachedWeatherForecast> =
                    Result.success(forecast)
            },
            settingsRepository = object : SettingsRepository {
                private val s = MutableStateFlow(AppSettings())
                override fun observeSettings(): Flow<AppSettings> = s
                override suspend fun getSettings(): AppSettings = s.value
                override suspend fun updateLastKnownLocation(latitude: Double, longitude: Double) = Unit
                override suspend fun updateSettings(settings: AppSettings) {
                    s.value = settings
                }
            },
            entitlementRepository = entitlementRepo,
            compass = fakeCompass,
            locationProvider = fakeLocationProvider,
            runtimeConfig = AppRuntimeConfig(isDebugBuild = true, revenueCatPublicKey = "", mapTilerApiKey = ""),
        )
    }

    private class FakeEntitlementRepo(initialTier: SubscriptionTier) : EntitlementRepository {
        private val flow = MutableStateFlow(stateFor(initialTier))
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
            previewExpiresEpochMs = if (tier == SubscriptionTier.ALPHA) 36L * 3_600_000L else 0L,
            subscriptionExpiresEpochMs = null,
            lastVerifiedEpochMs = null,
            isOfflineGraceActive = false,
            remainingPreviewMs = if (tier == SubscriptionTier.ALPHA) 36L * 3_600_000L else 0L,
        )
    }
}

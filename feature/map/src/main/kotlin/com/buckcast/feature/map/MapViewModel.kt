package com.buckcast.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buckcast.domain.astronomy.NoaaSolarCalculator
import com.buckcast.domain.entitlement.AlphaFeature
import com.buckcast.domain.entitlement.EntitlementSource
import com.buckcast.domain.entitlement.EntitlementState
import com.buckcast.domain.entitlement.SubscriptionTier
import com.buckcast.domain.math.AngleMath
import com.buckcast.domain.math.GeoMath
import com.buckcast.domain.math.GeoPoint
import com.buckcast.domain.model.AppRuntimeConfig
import com.buckcast.domain.model.CachedWeatherForecast
import com.buckcast.domain.model.RouteType
import com.buckcast.domain.model.Stand
import com.buckcast.domain.model.StandRoute
import com.buckcast.domain.model.Waypoint
import com.buckcast.domain.repository.EntitlementRepository
import com.buckcast.domain.repository.FieldDataRepository
import com.buckcast.domain.repository.SettingsRepository
import com.buckcast.domain.repository.StandRepository
import com.buckcast.domain.repository.WeatherRepository
import com.buckcast.domain.wind.StandWindConfig
import com.buckcast.domain.wind.StandWindEvaluator
import com.buckcast.domain.wind.LiveWindOverride
import com.buckcast.domain.wind.ThermalDriftCalculator
import com.buckcast.domain.wind.ThermalDriftResult
import com.buckcast.domain.wind.ThermalInput
import com.buckcast.domain.wind.WindAlignmentBadge
import com.buckcast.domain.wind.WindAlignmentClassifier
import com.buckcast.sensors.CompassFusionMath
import com.buckcast.sensors.CompassReading
import com.buckcast.sensors.LocationPowerMode
import com.buckcast.sensors.LocationProvider
import com.buckcast.sensors.LocationState
import com.buckcast.sensors.RotationVectorCompass
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs

enum class MapOrientationMode {
    NORTH_UP,
    HEADING_UP,
}

fun computeNextMapBearing(
    currentDeg: Double,
    targetDeg: Double,
    alpha: Double = 0.25,
    deadbandDeg: Double = 0.5,
): Double? {
    if (!currentDeg.isFinite() || !targetDeg.isFinite()) return null
    val current = AngleMath.normalizeDegrees(currentDeg)
    val target = AngleMath.normalizeDegrees(targetDeg)
    val delta = AngleMath.signedDeltaDegrees(current, target)
    if (abs(delta) < deadbandDeg) return null
    return AngleMath.normalizeDegrees(current + alpha * delta)
}

data class MapCameraState(
    val latitude: Double = DEFAULT_LAT,
    val longitude: Double = DEFAULT_LON,
    val zoom: Double = 14.5,
    val hasCenteredOnGps: Boolean = false,
    val jumpNonce: Long = 0L,
) {
    companion object {
        const val DEFAULT_LAT = 41.5868
        const val DEFAULT_LON = -93.6250
    }
}

data class RouteDrawingState(
    val standId: String,
    val standName: String,
    val routeType: RouteType,
    val points: List<GeoPoint> = emptyList(),
)

data class ScoutTargetResult(
    val targetPoint: GeoPoint,
    val distanceYards: Double?,
    val bearingFromUserDeg: Double?,
    val windFromDeg: Double,
    val scentToDeg: Double,
    val windSpeedMph: Double,
)

data class MapUiState(
    val camera: MapCameraState = MapCameraState(),
    val orientationMode: MapOrientationMode = MapOrientationMode.NORTH_UP,
    val snapNorthNonce: Long = 0L,
    val baseLayer: MapBaseLayer = MapBaseLayer.TOPO,
    val showHillshade: Boolean = false,
    val showRangeRings: Boolean = false,
    val scoutModeEnabled: Boolean = false,
    val scoutResult: ScoutTargetResult? = null,
    val projectedStandId: String? = null,
    val userLocation: GeoPoint? = null,
    val gpsAccuracyFeet: Double? = null,
    val isLocationSearching: Boolean = true,
    val isPermissionRequired: Boolean = false,
    val compassReading: CompassReading? = null,
    val formattedHeading: String = "---.-°",
    val windAlignment: WindAlignmentBadge? = null,
    val windFromDeg: Double = 315.0,
    val windSpeedMph: Double = 7.0,
    val forecastWindFromDeg: Double = 315.0,
    val forecastWindSpeedMph: Double = 7.0,
    val liveWindOverride: LiveWindOverride? = null,
    val scentOrigin: GeoPoint? = null,
    val scentToDeg: Double = 135.0,
    val thermalDrift: ThermalDriftResult? = null,
    val standPins: List<StandMapPin> = emptyList(),
    val waypoints: List<Waypoint> = emptyList(),
    val lockedWaypointIds: Set<String> = emptySet(),
    val routes: List<StandRoute> = emptyList(),
    val routeDrawing: RouteDrawingState? = null,
    val repositioningStandName: String? = null,
    val entitlement: EntitlementState = EntitlementState(
        tier = SubscriptionTier.SCOUT,
        source = EntitlementSource.NONE,
        previewStartedEpochMs = 0L,
        previewExpiresEpochMs = 0L,
        subscriptionExpiresEpochMs = null,
        lastVerifiedEpochMs = null,
        isOfflineGraceActive = false,
        remainingPreviewMs = 0L,
    ),
    val canUseAlphaLayers: Boolean = false,
    val canUseScentCones: Boolean = false,
    val canUseOfflinePacks: Boolean = false,
    val mapTilerApiKey: String = "",
    val statusMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MapViewModel @Inject constructor(
    private val standRepository: StandRepository,
    private val fieldDataRepository: FieldDataRepository,
    private val weatherRepository: WeatherRepository,
    private val settingsRepository: SettingsRepository,
    private val entitlementRepository: EntitlementRepository,
    private val compass: RotationVectorCompass,
    private val locationProvider: LocationProvider,
    private val runtimeConfig: AppRuntimeConfig,
) : ViewModel() {

    private val cameraFlow = MutableStateFlow(MapCameraState())
    private val orientationModeFlow = MutableStateFlow(MapOrientationMode.NORTH_UP)
    private val snapNorthNonceFlow = MutableStateFlow(0L)
    private val baseLayerFlow = MutableStateFlow(MapBaseLayer.TOPO)
    private val showHillshadeFlow = MutableStateFlow(false)
    private val showRangeRingsFlow = MutableStateFlow(false)
    private val scoutModeFlow = MutableStateFlow(false)
    private val scoutTargetFlow = MutableStateFlow<GeoPoint?>(null)
    private val projectedStandIdFlow = MutableStateFlow<String?>(null)
    private val routeDrawingFlow = MutableStateFlow<RouteDrawingState?>(null)
    private val repositioningStandNameFlow = MutableStateFlow<String?>(null)
    private val liveWindOverrideFlow = MutableStateFlow<LiveWindOverride?>(null)
    private val statusMessageFlow = MutableStateFlow<String?>(null)
    private val expiryReevalNonceFlow = MutableStateFlow(0L)

    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    private var previousWindAlignmentBadge: WindAlignmentBadge? = null

    private val standsState: StateFlow<List<Stand>> = standRepository.observeStands()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val entitlementState: StateFlow<EntitlementState> = entitlementRepository.observeEntitlement()
        .stateIn(viewModelScope, SharingStarted.Eagerly, MapUiState().entitlement)

    private val allRoutesFlow: Flow<List<StandRoute>> =
        standsState.flatMapLatest { stands ->
            if (stands.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(stands.map { standRepository.observeRoutesForStand(it.id) }) { lists ->
                    lists.flatMap { it }
                }
            }
        }

    private val layerAndToolFlow = combine(
        combine(cameraFlow, orientationModeFlow, snapNorthNonceFlow) { cam, orient, snapNonce ->
            Triple(cam, orient, snapNonce)
        },
        baseLayerFlow,
        showHillshadeFlow,
        showRangeRingsFlow,
        combine(
            scoutModeFlow,
            scoutTargetFlow,
            projectedStandIdFlow,
            combine(routeDrawingFlow, repositioningStandNameFlow) { drawing, repoName -> drawing to repoName },
            combine(liveWindOverrideFlow, statusMessageFlow, expiryReevalNonceFlow) { ovr, status, _ -> ovr to status },
        ) { scoutEnabled, scoutTarget, projectedStandId, drawAndRepo, windAndStatus ->
            ToolExtras(
                scoutEnabled = scoutEnabled,
                scoutTarget = scoutTarget,
                projectedStandId = projectedStandId,
                routeDrawing = drawAndRepo.first,
                repositioningStandName = drawAndRepo.second,
                liveWindOverride = windAndStatus.first,
                status = windAndStatus.second,
            )
        },
    ) { cameraAndOrient, baseLayer, showHillshade, showRangeRings, extras ->
        LayerToolState(
            camera = cameraAndOrient.first,
            orientationMode = cameraAndOrient.second,
            snapNorthNonce = cameraAndOrient.third,
            baseLayer = baseLayer,
            showHillshade = showHillshade,
            showRangeRings = showRangeRings,
            scoutModeEnabled = extras.scoutEnabled,
            scoutTarget = extras.scoutTarget,
            projectedStandId = extras.projectedStandId,
            routeDrawing = extras.routeDrawing,
            repositioningStandName = extras.repositioningStandName,
            liveWindOverride = extras.liveWindOverride,
            statusMessage = extras.status,
        )
    }

    private val repoDataFlow = combine(
        standsState,
        allRoutesFlow,
        fieldDataRepository.observeWaypoints(),
        weatherRepository.observeLatestCachedForecast(),
        settingsRepository.observeSettings(),
    ) { stands, routes, waypoints, weather, settings ->
        RepoData(stands, routes, waypoints, weather, settings)
    }

    val uiState: StateFlow<MapUiState> = combine(
        repoDataFlow,
        entitlementState,
        locationProvider.observeLocation(LocationPowerMode.HIGH_ACCURACY),
        compass.observeHeading(),
        layerAndToolFlow,
    ) { data, entitlement, locationState, compassReading, tools ->
        if (locationState is LocationState.Fix) {
            compass.updateLocation(locationState.point)
        }
        buildUiState(
            data = data,
            entitlement = entitlement,
            locationState = locationState,
            compassReading = compassReading,
            tools = tools,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = MapUiState(mapTilerApiKey = runtimeConfig.mapTilerApiKey),
    )

    init {
        viewModelScope.launch {
            entitlementRepository.ensureInitialized()
        }
        // Automatically revert Alpha-only layers/overlays back to Scout-safe state when preview expires.
        viewModelScope.launch {
            entitlementState.collect { entitlement ->
                if (!entitlement.hasAccess(AlphaFeature.PREMIUM_MAP_LAYERS)) {
                    if (baseLayerFlow.value != MapBaseLayer.TOPO) {
                        baseLayerFlow.value = MapBaseLayer.TOPO
                    }
                    if (showHillshadeFlow.value) {
                        showHillshadeFlow.value = false
                    }
                }
                if (!entitlement.hasAccess(AlphaFeature.SCENT_THERMAL_CONES)) {
                    showRangeRingsFlow.value = false
                    scoutModeFlow.value = false
                    scoutTargetFlow.value = null
                    projectedStandIdFlow.value = null
                }
            }
        }
    }

    fun updateCamera(
        latitude: Double,
        longitude: Double,
        zoom: Double,
        hasCenteredOnGps: Boolean = cameraFlow.value.hasCenteredOnGps,
    ) {
        if (!latitude.isFinite() || !longitude.isFinite() || !zoom.isFinite()) return
        cameraFlow.value = MapCameraState(
            latitude = latitude.coerceIn(-85.0, 85.0),
            longitude = longitude.coerceIn(-180.0, 180.0),
            zoom = zoom.coerceIn(2.0, 20.0),
            hasCenteredOnGps = hasCenteredOnGps,
            jumpNonce = cameraFlow.value.jumpNonce,
        )
    }

    fun recenterOnUserLocation() {
        val loc = uiState.value.userLocation
        if (loc == null) {
            statusMessageFlow.value = if (uiState.value.isPermissionRequired) {
                "Location permission required for GPS lock"
            } else {
                "Waiting for GPS fix..."
            }
            return
        }
        cameraFlow.value = cameraFlow.value.copy(
            latitude = loc.latitude,
            longitude = loc.longitude,
            zoom = maxOf(cameraFlow.value.zoom, 15.5),
            hasCenteredOnGps = true,
            jumpNonce = cameraFlow.value.jumpNonce + 1L,
        )
    }

    fun toggleOrientationMode() {
        val next = when (orientationModeFlow.value) {
            MapOrientationMode.NORTH_UP -> MapOrientationMode.HEADING_UP
            MapOrientationMode.HEADING_UP -> {
                snapNorthNonceFlow.value += 1L
                MapOrientationMode.NORTH_UP
            }
        }
        orientationModeFlow.value = next
    }

    fun onUserManualRotate() {
        if (orientationModeFlow.value == MapOrientationMode.HEADING_UP) {
            orientationModeFlow.value = MapOrientationMode.NORTH_UP
        }
    }

    fun recalibrateCompass() {
        compass.recalibrate()
    }

    fun setMilkweedFloatOverride(downwindHeadingDeg: Double, speedMph: Double? = null) {
        if (!downwindHeadingDeg.isFinite()) return
        val resolvedSpeed = (speedMph?.takeIf { it.isFinite() && it >= 0.0 }
            ?: uiState.value.forecastWindSpeedMph)
            .coerceIn(0.0, 80.0)
        val now = clockMs()
        liveWindOverrideFlow.value = LiveWindOverride.fromMilkweedFloat(
            downwindFloatHeadingDeg = downwindHeadingDeg,
            windSpeedMph = resolvedSpeed,
            setAtEpochMs = now,
        )
    }

    fun setManualWindOverride(windFromDeg: Double, speedMph: Double) {
        if (!windFromDeg.isFinite() || !speedMph.isFinite()) return
        val now = clockMs()
        liveWindOverrideFlow.value = LiveWindOverride(
            windFromDeg = AngleMath.normalizeDegrees(windFromDeg),
            windSpeedMph = speedMph.coerceIn(0.0, 80.0),
            setAtEpochMs = now,
        )
    }

    fun clearWindOverride() {
        liveWindOverrideFlow.value = null
    }

    fun reevaluateWindOverrideExpiry() {
        val current = liveWindOverrideFlow.value ?: return
        if (current.isExpired(clockMs())) {
            liveWindOverrideFlow.value = null
        } else {
            expiryReevalNonceFlow.value += 1L
        }
    }

    fun selectBaseLayer(layer: MapBaseLayer): Boolean {
        if (layer.isAlphaOnly && !entitlementState.value.hasAccess(AlphaFeature.PREMIUM_MAP_LAYERS)) {
            return false
        }
        baseLayerFlow.value = layer
        return true
    }

    fun toggleHillshade(): Boolean {
        if (!entitlementState.value.hasAccess(AlphaFeature.PREMIUM_MAP_LAYERS)) {
            return false
        }
        showHillshadeFlow.value = !showHillshadeFlow.value
        return true
    }

    fun toggleRangeRings(): Boolean {
        if (!entitlementState.value.hasAccess(AlphaFeature.SCENT_THERMAL_CONES)) {
            return false
        }
        showRangeRingsFlow.value = !showRangeRingsFlow.value
        return true
    }

    fun toggleScoutMode(): Boolean {
        if (!entitlementState.value.hasAccess(AlphaFeature.SCENT_THERMAL_CONES)) {
            return false
        }
        val next = !scoutModeFlow.value
        scoutModeFlow.value = next
        if (!next) {
            scoutTargetFlow.value = null
            statusMessageFlow.value = null
        } else {
            projectedStandIdFlow.value = null
            statusMessageFlow.value = "Scout Mode active — tap anywhere on map to project wind"
        }
        return true
    }

    fun focusOnStand(standId: String) {
        scoutModeFlow.value = false
        scoutTargetFlow.value = null
        statusMessageFlow.value = null
        val immediate = uiState.value.standPins.firstOrNull { it.id == standId }?.location
            ?: standsState.value.firstOrNull { it.id == standId }?.location
        if (immediate != null) {
            cameraFlow.value = cameraFlow.value.copy(
                latitude = immediate.latitude,
                longitude = immediate.longitude,
                zoom = maxOf(cameraFlow.value.zoom, 15.5),
                hasCenteredOnGps = true,
                jumpNonce = cameraFlow.value.jumpNonce + 1L,
            )
            return
        }
        viewModelScope.launch {
            val loaded = standRepository.observeStands()
                .firstOrNull { list -> list.any { it.id == standId } }
                ?.firstOrNull { it.id == standId }
                ?.location
                ?: return@launch
            cameraFlow.value = cameraFlow.value.copy(
                latitude = loaded.latitude,
                longitude = loaded.longitude,
                zoom = maxOf(cameraFlow.value.zoom, 15.5),
                hasCenteredOnGps = true,
                jumpNonce = cameraFlow.value.jumpNonce + 1L,
            )
        }
    }

    fun onLeaveMapScreen() {
        statusMessageFlow.value = null
    }

    fun projectStandScent(standId: String): Boolean {
        if (!entitlementState.value.hasAccess(AlphaFeature.SCENT_THERMAL_CONES)) {
            return false
        }
        val stand = uiState.value.standPins.firstOrNull { it.id == standId }?.location
            ?: standsState.value.firstOrNull { it.id == standId }?.location
            ?: return false
        scoutModeFlow.value = false
        scoutTargetFlow.value = null
        projectedStandIdFlow.value = standId
        cameraFlow.value = cameraFlow.value.copy(
            latitude = stand.latitude,
            longitude = stand.longitude,
            zoom = maxOf(cameraFlow.value.zoom, 15.0),
            hasCenteredOnGps = true,
            jumpNonce = cameraFlow.value.jumpNonce + 1L,
        )
        return true
    }

    fun onMapTap(point: GeoPoint) {
        val activeDrawing = routeDrawingFlow.value
        if (activeDrawing != null) {
            routeDrawingFlow.value = activeDrawing.copy(points = activeDrawing.points + point)
            return
        }
        if (scoutModeFlow.value && entitlementState.value.hasAccess(AlphaFeature.SCENT_THERMAL_CONES)) {
            projectedStandIdFlow.value = null
            scoutTargetFlow.value = point
            statusMessageFlow.value = null
        }
    }

    fun startRouteDrawing(standId: String, standName: String, routeType: RouteType): Boolean {
        if (!entitlementState.value.hasAccess(AlphaFeature.ROUTE_AUDITOR)) {
            return false
        }
        scoutModeFlow.value = false
        scoutTargetFlow.value = null
        routeDrawingFlow.value = RouteDrawingState(
            standId = standId,
            standName = standName,
            routeType = routeType,
        )
        statusMessageFlow.value = "Tap map points to draw ${routeType.name.lowercase()} route for $standName"
        return true
    }

    fun undoLastRoutePoint() {
        val current = routeDrawingFlow.value ?: return
        if (current.points.isNotEmpty()) {
            routeDrawingFlow.value = current.copy(points = current.points.dropLast(1))
        }
    }

    fun finishRouteDrawing() {
        val current = routeDrawingFlow.value ?: return
        if (current.points.size < 2) {
            statusMessageFlow.value = "Tap at least 2 points to save route"
            return
        }
        routeDrawingFlow.value = null
        viewModelScope.launch {
            standRepository.saveRoute(
                StandRoute(
                    id = UUID.randomUUID().toString(),
                    standId = current.standId,
                    routeType = current.routeType,
                    coordinates = current.points,
                ),
            )
            statusMessageFlow.value = "Saved ${current.routeType.name.lowercase()} route (${current.points.size} points)"
        }
    }

    fun cancelRouteDrawing() {
        routeDrawingFlow.value = null
        statusMessageFlow.value = null
    }

    fun startStandReposition(standName: String) {
        scoutModeFlow.value = false
        scoutTargetFlow.value = null
        routeDrawingFlow.value = null
        repositioningStandNameFlow.value = standName.trim().ifBlank { "Stand" }
    }

    fun cancelStandReposition() {
        repositioningStandNameFlow.value = null
    }

    fun clearMessage() {
        statusMessageFlow.value = null
    }

    private fun buildUiState(
        data: RepoData,
        entitlement: EntitlementState,
        locationState: LocationState,
        compassReading: CompassReading,
        tools: LayerToolState,
    ): MapUiState {
        val canUseAlphaLayers = entitlement.hasAccess(AlphaFeature.PREMIUM_MAP_LAYERS)
        val canUseScentCones = entitlement.hasAccess(AlphaFeature.SCENT_THERMAL_CONES)
        val canUseOfflinePacks = entitlement.hasAccess(AlphaFeature.OFFLINE_MAP_PACKS)

        val effectiveBaseLayer = if (!canUseAlphaLayers && tools.baseLayer.isAlphaOnly) {
            MapBaseLayer.TOPO
        } else {
            tools.baseLayer
        }
        val effectiveHillshade = canUseAlphaLayers && tools.showHillshade
        val effectiveRangeRings = canUseScentCones && tools.showRangeRings
        val effectiveScoutEnabled = canUseScentCones && tools.scoutModeEnabled
        val effectiveScoutTarget = if (effectiveScoutEnabled) tools.scoutTarget else null
        val effectiveProjectedStandId = if (canUseScentCones) tools.projectedStandId else null

        val fix = locationState as? LocationState.Fix
        val userLocation = fix?.point
        val initialLat = userLocation?.latitude
            ?: data.settings.lastKnownLat
            ?: data.weather?.latitude
            ?: MapCameraState.DEFAULT_LAT
        val initialLon = userLocation?.longitude
            ?: data.settings.lastKnownLon
            ?: data.weather?.longitude
            ?: MapCameraState.DEFAULT_LON

        val effectiveCamera = if (!tools.camera.hasCenteredOnGps) {
            tools.camera.copy(
                latitude = initialLat,
                longitude = initialLon,
                hasCenteredOnGps = userLocation != null,
            )
        } else {
            tools.camera
        }

        val nowEpochMs = clockMs()
        val nowInstant = Instant.ofEpochMilli(nowEpochMs)
        val nowSec = nowInstant.epochSecond
        val currentHour = data.weather?.hourly?.minByOrNull { abs(it.epochSeconds - nowSec) }
        val forecastWindFromDeg = AngleMath.normalizeDegrees(currentHour?.windFromDeg ?: 315.0)
        val forecastWindSpeedMph = currentHour?.windSpeedMph ?: 7.0
        val cloudPct = currentHour?.cloudCoverPercent ?: 25.0

        val activeOverride = tools.liveWindOverride?.takeIf { !it.isExpired(nowEpochMs) }
        val effectiveWindFromDeg = activeOverride?.windFromDeg ?: forecastWindFromDeg
        val effectiveWindSpeedMph = activeOverride?.windSpeedMph ?: forecastWindSpeedMph

        val zoneId = data.weather?.timezoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneId.systemDefault()
        val todaySolar = runCatching {
            NoaaSolarCalculator.calculateDay(LocalDate.now(zoneId), initialLat, initialLon, zoneId)
        }.getOrNull()

        val projectedStand = effectiveProjectedStandId?.let { targetId ->
            data.stands.firstOrNull { it.id == targetId }
        }
        val activeStandForThermal = projectedStand ?: userLocation?.let { loc ->
            data.stands.filter { it.slopeAspectDeg != null && it.slopeGradePercent != null }
                .minByOrNull { GeoMath.distanceYards(loc, it.location) }
        }

        val thermalDrift = if (
            activeOverride == null &&
            canUseScentCones &&
            activeStandForThermal?.slopeAspectDeg != null &&
            activeStandForThermal.slopeGradePercent != null &&
            todaySolar?.sunrise != null &&
            todaySolar.sunset != null
        ) {
            ThermalDriftCalculator.calculate(
                ThermalInput(
                    slopeAspectDeg = activeStandForThermal.slopeAspectDeg!!,
                    slopeGradePercent = activeStandForThermal.slopeGradePercent!!,
                    currentTime = ZonedDateTime.ofInstant(nowInstant, zoneId),
                    sunrise = todaySolar.sunrise!!,
                    sunset = todaySolar.sunset!!,
                    cloudCoverPercent = cloudPct,
                    ambientWindSpeedMph = effectiveWindSpeedMph,
                    ambientWindFromDeg = effectiveWindFromDeg,
                ),
            )
        } else {
            null
        }

        val effectiveScentToDeg = thermalDrift?.effectiveScentToDeg ?: AngleMath.oppositeBearing(effectiveWindFromDeg)

        compass.setCompassOffsetDeg(data.settings.compassOffsetDeg)

        val formattedHeading = if (compassReading.isSensorAvailable) {
            CompassFusionMath.formatHeadingTenths(compassReading.trueHeadingDeg)
        } else {
            "---.-°"
        }

        val windAlignment = if (canUseScentCones && compassReading.isSensorAvailable) {
            WindAlignmentClassifier.classify(
                facingHeadingDeg = compassReading.trueHeadingDeg,
                windFromDeg = effectiveWindFromDeg,
                previousBadge = previousWindAlignmentBadge,
                hysteresisDeg = WindAlignmentClassifier.DEFAULT_HYSTERESIS_DEG,
            ).also { previousWindAlignmentBadge = it }
        } else {
            null
        }

        val unlimited = entitlement.hasAccess(AlphaFeature.UNLIMITED_STANDS_WAYPOINTS)
        val unlockedStandIds = if (unlimited) {
            data.stands.map { it.id }.toSet()
        } else {
            data.stands.sortedBy { it.createdAtEpochMs }.take(1).map { it.id }.toSet()
        }
        val lockedWaypointIds = if (unlimited) {
            emptySet()
        } else {
            data.waypoints.sortedBy { it.createdAtEpochMs }.drop(3).map { it.id }.toSet()
        }

        val standPins = data.stands.map { stand ->
            val hasWind = stand.preferredWindSectors.isNotEmpty()
            val verdict = if (hasWind) {
                StandWindEvaluator.evaluate(
                    config = StandWindConfig(
                        standId = stand.id,
                        standName = stand.name,
                        preferredWindFromSectors = stand.preferredWindSectors,
                    ),
                    ambientWindFromDeg = effectiveWindFromDeg,
                    ambientWindSpeedMph = effectiveWindSpeedMph,
                    thermalDrift = thermalDrift.takeIf { stand.id == activeStandForThermal?.id },
                ).verdict
            } else {
                null
            }
            StandMapPin(
                id = stand.id,
                name = stand.name,
                location = stand.location,
                verdict = verdict,
                colorHex = TacticalGeoJsonBuilder.standPinColorHex(verdict, hasWind),
                isLocked = stand.id !in unlockedStandIds,
                isSelected = stand.id == effectiveProjectedStandId,
            )
        }

        val scentOrigin = when {
            effectiveScoutTarget != null -> effectiveScoutTarget
            projectedStand != null -> projectedStand.location
            else -> userLocation ?: GeoPoint(effectiveCamera.latitude, effectiveCamera.longitude)
        }

        val scoutResult = effectiveScoutTarget?.let { target ->
            ScoutTargetResult(
                targetPoint = target,
                distanceYards = userLocation?.let { GeoMath.distanceYards(it, target) },
                bearingFromUserDeg = userLocation?.let { GeoMath.initialBearingDegrees(it, target) },
                windFromDeg = effectiveWindFromDeg,
                scentToDeg = effectiveScentToDeg,
                windSpeedMph = effectiveWindSpeedMph,
            )
        }

        return MapUiState(
            camera = effectiveCamera,
            orientationMode = tools.orientationMode,
            snapNorthNonce = tools.snapNorthNonce,
            baseLayer = effectiveBaseLayer,
            showHillshade = effectiveHillshade,
            showRangeRings = effectiveRangeRings,
            scoutModeEnabled = effectiveScoutEnabled,
            scoutResult = scoutResult,
            projectedStandId = effectiveProjectedStandId,
            userLocation = userLocation,
            gpsAccuracyFeet = fix?.accuracyFeet,
            isLocationSearching = locationState is LocationState.Searching,
            isPermissionRequired = locationState is LocationState.PermissionRequired,
            compassReading = compassReading,
            formattedHeading = formattedHeading,
            windAlignment = windAlignment,
            windFromDeg = effectiveWindFromDeg,
            windSpeedMph = effectiveWindSpeedMph,
            forecastWindFromDeg = forecastWindFromDeg,
            forecastWindSpeedMph = forecastWindSpeedMph,
            liveWindOverride = activeOverride,
            scentOrigin = scentOrigin,
            scentToDeg = effectiveScentToDeg,
            thermalDrift = thermalDrift,
            standPins = standPins,
            waypoints = data.waypoints,
            lockedWaypointIds = lockedWaypointIds,
            routes = data.routes,
            routeDrawing = tools.routeDrawing,
            repositioningStandName = tools.repositioningStandName,
            entitlement = entitlement,
            canUseAlphaLayers = canUseAlphaLayers,
            canUseScentCones = canUseScentCones,
            canUseOfflinePacks = canUseOfflinePacks,
            mapTilerApiKey = runtimeConfig.mapTilerApiKey,
            statusMessage = tools.statusMessage,
        )
    }

    private data class ToolExtras(
        val scoutEnabled: Boolean,
        val scoutTarget: GeoPoint?,
        val projectedStandId: String?,
        val routeDrawing: RouteDrawingState?,
        val repositioningStandName: String?,
        val liveWindOverride: LiveWindOverride?,
        val status: String?,
    )

    private data class LayerToolState(
        val camera: MapCameraState,
        val orientationMode: MapOrientationMode,
        val snapNorthNonce: Long,
        val baseLayer: MapBaseLayer,
        val showHillshade: Boolean,
        val showRangeRings: Boolean,
        val scoutModeEnabled: Boolean,
        val scoutTarget: GeoPoint?,
        val projectedStandId: String?,
        val routeDrawing: RouteDrawingState?,
        val repositioningStandName: String?,
        val liveWindOverride: LiveWindOverride?,
        val statusMessage: String?,
    )

    private data class RepoData(
        val stands: List<Stand>,
        val routes: List<StandRoute>,
        val waypoints: List<Waypoint>,
        val weather: CachedWeatherForecast?,
        val settings: com.buckcast.domain.model.AppSettings,
    )
}

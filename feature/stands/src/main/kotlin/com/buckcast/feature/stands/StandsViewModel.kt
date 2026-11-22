package com.buckcast.feature.stands

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buckcast.domain.ballistics.BallisticRangeCalculator
import com.buckcast.domain.ballistics.BallisticRangeResult
import com.buckcast.domain.entitlement.AlphaFeature
import com.buckcast.domain.entitlement.EntitlementSource
import com.buckcast.domain.entitlement.EntitlementState
import com.buckcast.domain.entitlement.SubscriptionTier
import com.buckcast.domain.math.AngleMath
import com.buckcast.domain.math.BearingSector
import com.buckcast.domain.math.GeoMath
import com.buckcast.domain.math.GeoPoint
import com.buckcast.domain.model.CachedWeatherForecast
import com.buckcast.domain.model.RouteType
import com.buckcast.domain.model.Stand
import com.buckcast.domain.model.StandRoute
import com.buckcast.domain.model.StandSit
import com.buckcast.domain.model.StandType
import com.buckcast.domain.model.TerrainPosition
import com.buckcast.domain.model.Waypoint
import com.buckcast.domain.model.WaypointCategory
import com.buckcast.domain.repository.EntitlementRepository
import com.buckcast.domain.repository.FieldDataRepository
import com.buckcast.domain.repository.StandRepository
import com.buckcast.domain.repository.WeatherRepository
import com.buckcast.domain.stands.StandFreshnessCalculator
import com.buckcast.domain.stands.StandFreshnessResult
import com.buckcast.domain.stands.StandSitRecord
import com.buckcast.domain.wind.BeddingZone
import com.buckcast.domain.wind.EntryRoute
import com.buckcast.domain.wind.EntryRouteAuditResult
import com.buckcast.domain.wind.EntryRouteWindAuditor
import com.buckcast.domain.wind.StandWindConfig
import com.buckcast.domain.wind.StandWindEvaluation
import com.buckcast.domain.wind.StandWindEvaluator
import com.buckcast.sensors.LocationPowerMode
import com.buckcast.sensors.LocationProvider
import com.buckcast.sensors.LocationState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs

enum class WindRoseDirection(val label: String, val centerDeg: Double) {
    N("N", 0.0),
    NE("NE", 45.0),
    E("E", 90.0),
    SE("SE", 135.0),
    S("S", 180.0),
    SW("SW", 225.0),
    W("W", 270.0),
    NW("NW", 315.0);

    fun toSector(): BearingSector = BearingSector(
        startDeg = AngleMath.normalizeDegrees(centerDeg - 22.5),
        endDeg = AngleMath.normalizeDegrees(centerDeg + 22.5),
    )

    companion object {
        fun fromSectors(sectors: List<BearingSector>): Set<WindRoseDirection> =
            entries.filter { dir -> sectors.any { it.contains(dir.centerDeg) } }.toSet()
    }
}

data class StandDetailUi(
    val stand: Stand,
    val windEvaluation: StandWindEvaluation,
    val freshness: StandFreshnessResult,
    val routes: List<StandRoute>,
    val routeAudits: List<EntryRouteAuditResult>,
    val distanceYardsFromUser: Double?,
    val ballisticRangeFromUser: BallisticRangeResult?,
    val activeSitStartedEpochMs: Long?,
    val isReadOnlyLocked: Boolean,
)

data class StandsUiState(
    val stands: List<StandDetailUi> = emptyList(),
    val waypoints: List<Waypoint> = emptyList(),
    val lockedWaypointIds: Set<String> = emptySet(),
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
    val currentUserLocation: GeoPoint? = null,
    val statusMessage: String? = null,
) {
    val canCreateAnotherStand: Boolean
        get() = entitlement.hasAccess(AlphaFeature.UNLIMITED_STANDS_WAYPOINTS) || stands.size < MAX_SCOUT_STANDS

    val canCreateAnotherWaypoint: Boolean
        get() = entitlement.hasAccess(AlphaFeature.UNLIMITED_STANDS_WAYPOINTS) || waypoints.size < MAX_SCOUT_WAYPOINTS

    companion object {
        const val MAX_SCOUT_STANDS = 1
        const val MAX_SCOUT_WAYPOINTS = 3
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StandsViewModel @Inject constructor(
    private val standRepository: StandRepository,
    private val fieldDataRepository: FieldDataRepository,
    private val weatherRepository: WeatherRepository,
    private val entitlementRepository: EntitlementRepository,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val activeSitsFlow = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val messageFlow = MutableStateFlow<String?>(null)

    private data class StandBundle(
        val stand: Stand,
        val routes: List<StandRoute>,
        val sits: List<StandSit>,
    )

    private val bundlesFlow: Flow<List<StandBundle>> =
        standRepository.observeStands().flatMapLatest { stands ->
            if (stands.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    stands.map { stand ->
                        combine(
                            standRepository.observeRoutesForStand(stand.id),
                            standRepository.observeSitsForStand(stand.id),
                        ) { routes, sits ->
                            StandBundle(stand = stand, routes = routes, sits = sits)
                        }
                    },
                ) { it.toList() }
            }
        }

    val uiState: StateFlow<StandsUiState> = combine(
        bundlesFlow,
        fieldDataRepository.observeWaypoints(),
        weatherRepository.observeLatestCachedForecast(),
        entitlementRepository.observeEntitlement(),
        combine(
            locationProvider.observeLocation(LocationPowerMode.BATTERY_SAVER),
            activeSitsFlow,
            messageFlow,
        ) { loc, activeSits, msg -> Triple(loc, activeSits, msg) },
    ) { bundles, waypoints, weather, entitlement, controls ->
        buildState(
            bundles = bundles,
            waypoints = waypoints,
            weather = weather,
            entitlement = entitlement,
            locationState = controls.first,
            activeSits = controls.second,
            message = controls.third,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = StandsUiState(),
    )

    fun saveStand(
        existingId: String?,
        name: String,
        location: GeoPoint,
        standType: StandType,
        standHeightFeet: Double,
        terrainPosition: TerrainPosition,
        selectedWindDirs: Set<WindRoseDirection>,
        slopeAspectDeg: Double?,
        slopeGradePercent: Double?,
        notes: String,
    ): Boolean {
        val state = uiState.value
        val isNew = existingId == null
        if (isNew && !state.canCreateAnotherStand) {
            return false
        }
        val existingBundle = existingId?.let { id -> state.stands.firstOrNull { it.stand.id == id } }
        if (existingBundle?.isReadOnlyLocked == true) {
            return false
        }

        val stand = Stand(
            id = existingId ?: UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Stand ${state.stands.size + 1}" },
            location = location,
            standType = standType,
            standHeightFeet = standHeightFeet.coerceIn(0.0, 60.0),
            terrainPosition = terrainPosition,
            preferredWindSectors = selectedWindDirs.map { it.toSector() },
            elevationFeet = location.elevationFeet,
            slopeAspectDeg = slopeAspectDeg?.let(AngleMath::normalizeDegrees),
            slopeGradePercent = slopeGradePercent?.coerceIn(0.0, 100.0),
            notes = notes.trim(),
            createdAtEpochMs = existingBundle?.stand?.createdAtEpochMs ?: System.currentTimeMillis(),
        )
        viewModelScope.launch {
            standRepository.saveStand(stand)
            messageFlow.value = "Saved stand '${stand.name}'"
        }
        return true
    }

    fun deleteStand(standId: String) {
        viewModelScope.launch {
            standRepository.deleteStand(standId)
            activeSitsFlow.value = activeSitsFlow.value - standId
            messageFlow.value = "Stand deleted"
        }
    }

    fun saveStandRoute(standId: String, routeType: RouteType, points: List<GeoPoint>): Boolean {
        if (!uiState.value.entitlement.hasAccess(AlphaFeature.ROUTE_AUDITOR)) return false
        if (points.size < 2) return false
        viewModelScope.launch {
            standRepository.saveRoute(
                StandRoute(
                    id = "${standId}_${routeType.name}",
                    standId = standId,
                    routeType = routeType,
                    coordinates = points,
                ),
            )
            messageFlow.value = "Saved ${routeType.name.lowercase()} route (${points.size} points)"
        }
        return true
    }

    fun startSit(standId: String): Boolean {
        if (!uiState.value.entitlement.hasAccess(AlphaFeature.STAND_WIND_FRESHNESS)) return false
        activeSitsFlow.value = activeSitsFlow.value + (standId to System.currentTimeMillis())
        return true
    }

    fun endSit(standId: String, windedDeer: Boolean) {
        val startMs = activeSitsFlow.value[standId] ?: return
        val endMs = System.currentTimeMillis()
        val safeStartMs = minOf(startMs, endMs)
        activeSitsFlow.value = activeSitsFlow.value - standId
        viewModelScope.launch {
            standRepository.recordSit(
                StandSit(
                    id = UUID.randomUUID().toString(),
                    standId = standId,
                    startTimeEpochMs = safeStartMs,
                    endTimeEpochMs = endMs,
                    windedDeer = windedDeer,
                ),
            )
            messageFlow.value = if (windedDeer) {
                "Sit logged (winded deer noted in freshness)"
            } else {
                "Sit logged cleanly"
            }
        }
    }

    fun saveWaypoint(
        category: WaypointCategory,
        name: String,
        location: GeoPoint,
        notes: String = "",
    ): Boolean {
        val state = uiState.value
        if (!state.canCreateAnotherWaypoint) return false
        viewModelScope.launch {
            fieldDataRepository.saveWaypoint(
                Waypoint(
                    id = UUID.randomUUID().toString(),
                    category = category,
                    name = name.trim().ifBlank { category.name.lowercase().replace('_', ' ') },
                    location = location,
                    notes = notes.trim(),
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
            messageFlow.value = "Saved ${category.name} pin"
        }
        return true
    }

    fun clearMessage() {
        messageFlow.value = null
    }

    private fun buildState(
        bundles: List<StandBundle>,
        waypoints: List<Waypoint>,
        weather: CachedWeatherForecast?,
        entitlement: EntitlementState,
        locationState: LocationState,
        activeSits: Map<String, Long>,
        message: String?,
    ): StandsUiState {
        val userLoc = (locationState as? LocationState.Fix)?.point
        val nowInstant = Instant.now()
        val nowSec = nowInstant.epochSecond
        val currentHour = weather?.hourly?.minByOrNull { abs(it.epochSeconds - nowSec) }
        val windFromDeg = currentHour?.windFromDeg ?: 315.0
        val windSpeedMph = currentHour?.windSpeedMph ?: 7.0
        val effectiveScentToDeg = AngleMath.oppositeBearing(windFromDeg)

        val beddingZones = waypoints
            .filter { it.category == WaypointCategory.BEDDING }
            .map { wp ->
                BeddingZone(
                    id = wp.id,
                    name = wp.name,
                    center = wp.location,
                    radiusYards = DEFAULT_BEDDING_RADIUS_YARDS,
                )
            }

        val unlimited = entitlement.hasAccess(AlphaFeature.UNLIMITED_STANDS_WAYPOINTS)
        val sortedByCreated = bundles.sortedBy { it.stand.createdAtEpochMs }
        val unlockedStandIds = if (unlimited) {
            sortedByCreated.map { it.stand.id }.toSet()
        } else {
            sortedByCreated.take(StandsUiState.MAX_SCOUT_STANDS).map { it.stand.id }.toSet()
        }

        val sortedWps = waypoints.sortedBy { it.createdAtEpochMs }
        val lockedWaypointIds = if (unlimited) {
            emptySet()
        } else {
            sortedWps.drop(StandsUiState.MAX_SCOUT_WAYPOINTS).map { it.id }.toSet()
        }

        val details = bundles.map { bundle ->
            val stand = bundle.stand
            val eval = StandWindEvaluator.evaluate(
                config = StandWindConfig(
                    standId = stand.id,
                    standName = stand.name,
                    preferredWindFromSectors = stand.preferredWindSectors,
                ),
                ambientWindFromDeg = windFromDeg,
                ambientWindSpeedMph = windSpeedMph,
            )
            val freshness = StandFreshnessCalculator.evaluate(
                sits = bundle.sits.map {
                    StandSitRecord(
                        startTime = Instant.ofEpochMilli(it.startTimeEpochMs),
                        endTime = Instant.ofEpochMilli(it.endTimeEpochMs),
                        windedDeer = it.windedDeer,
                    )
                },
                now = nowInstant,
            )
            val routeAudits = bundle.routes.map { route ->
                EntryRouteWindAuditor.audit(
                    route = EntryRoute(
                        id = route.id,
                        name = "${stand.name} ${route.routeType.name}",
                        waypoints = route.coordinates,
                    ),
                    beddingZones = beddingZones,
                    effectiveScentToDeg = effectiveScentToDeg,
                )
            }
            val distYards = userLoc?.let { GeoMath.distanceYards(it, stand.location) }
            val ballistic = userLoc?.let {
                BallisticRangeCalculator.calculateFromCoordinates(
                    standGroundLocation = stand.location,
                    targetLocation = it,
                    standHeightFeet = stand.standHeightFeet,
                )
            }
            StandDetailUi(
                stand = stand,
                windEvaluation = eval,
                freshness = freshness,
                routes = bundle.routes,
                routeAudits = routeAudits,
                distanceYardsFromUser = distYards,
                ballisticRangeFromUser = ballistic,
                activeSitStartedEpochMs = activeSits[stand.id],
                isReadOnlyLocked = stand.id !in unlockedStandIds,
            )
        }

        return StandsUiState(
            stands = details,
            waypoints = waypoints,
            lockedWaypointIds = lockedWaypointIds,
            entitlement = entitlement,
            currentUserLocation = userLoc,
            statusMessage = message,
        )
    }

    companion object {
        private const val DEFAULT_BEDDING_RADIUS_YARDS = 50.0
    }
}

package com.buckcast.data.local.mapper

import com.buckcast.data.local.entities.AppSettingsEntity
import com.buckcast.data.local.entities.FieldLogEntity
import com.buckcast.data.local.entities.SightingEntity
import com.buckcast.data.local.entities.StandEntity
import com.buckcast.data.local.entities.StandRouteEntity
import com.buckcast.data.local.entities.StandSitEntity
import com.buckcast.data.local.entities.TrackPointEntity
import com.buckcast.data.local.entities.TrackSessionEntity
import com.buckcast.data.local.entities.WaypointEntity
import com.buckcast.data.local.entities.WeaponProfileEntity
import com.buckcast.domain.math.BearingSector
import com.buckcast.domain.math.GeoPoint
import com.buckcast.domain.model.AppSettings
import com.buckcast.domain.model.DeerClass
import com.buckcast.domain.model.FieldLog
import com.buckcast.domain.model.RouteType
import com.buckcast.domain.model.Sighting
import com.buckcast.domain.model.Stand
import com.buckcast.domain.model.StandRoute
import com.buckcast.domain.model.StandSit
import com.buckcast.domain.model.StandType
import com.buckcast.domain.model.TerrainPosition
import com.buckcast.domain.model.TrackPoint
import com.buckcast.domain.model.TrackSession
import com.buckcast.domain.model.TrackType
import com.buckcast.domain.model.Waypoint
import com.buckcast.domain.model.WaypointCategory
import com.buckcast.domain.model.WeaponDropEntry
import com.buckcast.domain.model.WeaponProfile
import com.buckcast.domain.model.WeaponType
import com.buckcast.domain.model.WeatherSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class BearingSectorWire(
    val startDeg: Double,
    val endDeg: Double,
)

@Serializable
private data class GeoPointWire(
    val latitude: Double,
    val longitude: Double,
    val elevationFeet: Double? = null,
)

@Serializable
private data class WeatherSnapshotWire(
    val tempDegF: Double,
    val apparentTempDegF: Double,
    val barometricPressureInHg: Double,
    val pressureTrend3hInHg: Double,
    val windSpeedMph: Double,
    val windGustMph: Double? = null,
    val windFromDeg: Double,
    val precipInchesPerHr: Double,
    val cloudCoverPercent: Double,
    val weatherCode: Int,
)

@Serializable
private data class WeaponDropEntryWire(
    val distanceYards: Double,
    val dropInches: Double,
)

class EntityMappers(private val json: Json) {

    fun toDomain(entity: StandEntity): Stand = Stand(
        id = entity.id,
        name = entity.name,
        location = GeoPoint(entity.latitude, entity.longitude, entity.elevationFeet),
        standType = StandType.valueOf(entity.standType),
        standHeightFeet = entity.standHeightFeet,
        terrainPosition = TerrainPosition.valueOf(entity.terrainPosition),
        preferredWindSectors = json.decodeFromString<List<BearingSectorWire>>(entity.preferredWindSectorsJson)
            .map { BearingSector(it.startDeg, it.endDeg) },
        elevationFeet = entity.elevationFeet,
        slopeAspectDeg = entity.slopeAspectDeg,
        slopeGradePercent = entity.slopeGradePercent,
        notes = entity.notes,
        createdAtEpochMs = entity.createdAtEpochMs,
    )

    fun toEntity(domain: Stand): StandEntity = StandEntity(
        id = domain.id,
        name = domain.name,
        latitude = domain.location.latitude,
        longitude = domain.location.longitude,
        elevationFeet = domain.elevationFeet ?: domain.location.elevationFeet,
        slopeAspectDeg = domain.slopeAspectDeg,
        slopeGradePercent = domain.slopeGradePercent,
        standType = domain.standType.name,
        standHeightFeet = domain.standHeightFeet,
        terrainPosition = domain.terrainPosition.name,
        preferredWindSectorsJson = json.encodeToString(
            domain.preferredWindSectors.map { BearingSectorWire(it.startDeg, it.endDeg) },
        ),
        notes = domain.notes,
        createdAtEpochMs = domain.createdAtEpochMs,
    )

    fun toDomain(entity: StandRouteEntity): StandRoute = StandRoute(
        id = entity.id,
        standId = entity.standId,
        routeType = RouteType.valueOf(entity.routeType),
        coordinates = json.decodeFromString<List<GeoPointWire>>(entity.polylineJson)
            .map { GeoPoint(it.latitude, it.longitude, it.elevationFeet) },
    )

    fun toEntity(domain: StandRoute): StandRouteEntity = StandRouteEntity(
        id = domain.id,
        standId = domain.standId,
        routeType = domain.routeType.name,
        polylineJson = json.encodeToString(
            domain.coordinates.map { GeoPointWire(it.latitude, it.longitude, it.elevationFeet) },
        ),
    )

    fun toDomain(entity: StandSitEntity): StandSit = StandSit(
        id = entity.id,
        standId = entity.standId,
        startTimeEpochMs = entity.startTimeEpochMs,
        endTimeEpochMs = entity.endTimeEpochMs,
        windedDeer = entity.windedDeer,
    )

    fun toEntity(domain: StandSit): StandSitEntity = StandSitEntity(
        id = domain.id,
        standId = domain.standId,
        startTimeEpochMs = domain.startTimeEpochMs,
        endTimeEpochMs = domain.endTimeEpochMs,
        windedDeer = domain.windedDeer,
    )

    fun toDomain(entity: WaypointEntity): Waypoint = Waypoint(
        id = entity.id,
        category = WaypointCategory.valueOf(entity.category),
        name = entity.name,
        location = GeoPoint(entity.latitude, entity.longitude, entity.elevationFeet),
        notes = entity.notes,
        createdAtEpochMs = entity.createdAtEpochMs,
    )

    fun toEntity(domain: Waypoint): WaypointEntity = WaypointEntity(
        id = domain.id,
        category = domain.category.name,
        name = domain.name,
        latitude = domain.location.latitude,
        longitude = domain.location.longitude,
        elevationFeet = domain.location.elevationFeet,
        notes = domain.notes,
        createdAtEpochMs = domain.createdAtEpochMs,
    )

    fun toDomain(entity: TrackSessionEntity): TrackSession = TrackSession(
        id = entity.id,
        name = entity.name,
        trackType = TrackType.valueOf(entity.trackType),
        startedAtEpochMs = entity.startedAtEpochMs,
        endedAtEpochMs = entity.endedAtEpochMs,
        notes = entity.notes,
    )

    fun toEntity(domain: TrackSession): TrackSessionEntity = TrackSessionEntity(
        id = domain.id,
        name = domain.name,
        trackType = domain.trackType.name,
        startedAtEpochMs = domain.startedAtEpochMs,
        endedAtEpochMs = domain.endedAtEpochMs,
        notes = domain.notes,
    )

    fun toDomain(entity: TrackPointEntity): TrackPoint = TrackPoint(
        id = entity.id,
        sessionId = entity.sessionId,
        location = GeoPoint(entity.latitude, entity.longitude, entity.elevationFeet),
        recordedAtEpochMs = entity.recordedAtEpochMs,
    )

    fun toEntity(domain: TrackPoint): TrackPointEntity = TrackPointEntity(
        id = domain.id,
        sessionId = domain.sessionId,
        latitude = domain.location.latitude,
        longitude = domain.location.longitude,
        elevationFeet = domain.location.elevationFeet,
        recordedAtEpochMs = domain.recordedAtEpochMs,
    )

    fun toDomain(entity: FieldLogEntity): FieldLog = FieldLog(
        id = entity.id,
        standId = entity.standId,
        notes = entity.notes,
        photoUris = json.decodeFromString(entity.photoUrisJson),
        location = GeoPoint(entity.latitude, entity.longitude, entity.elevationFeet),
        weatherSnapshot = entity.weatherSnapshotJson?.let { decodeSnapshot(it) },
        rutPhaseNumber = entity.rutPhaseNumber,
        createdAtEpochMs = entity.createdAtEpochMs,
    )

    fun toEntity(domain: FieldLog): FieldLogEntity = FieldLogEntity(
        id = domain.id,
        standId = domain.standId,
        notes = domain.notes,
        photoUrisJson = json.encodeToString(domain.photoUris),
        latitude = domain.location.latitude,
        longitude = domain.location.longitude,
        elevationFeet = domain.location.elevationFeet,
        weatherSnapshotJson = domain.weatherSnapshot?.let { encodeSnapshot(it) },
        rutPhaseNumber = domain.rutPhaseNumber,
        createdAtEpochMs = domain.createdAtEpochMs,
    )

    fun toDomain(entity: SightingEntity): Sighting = Sighting(
        id = entity.id,
        standId = entity.standId,
        deerClass = DeerClass.valueOf(entity.deerClass),
        count = entity.count,
        travelBearingDeg = entity.travelBearingDeg,
        distanceYards = entity.distanceYards,
        weatherSnapshot = entity.weatherSnapshotJson?.let { decodeSnapshot(it) },
        observedAtEpochMs = entity.observedAtEpochMs,
        notes = entity.notes,
    )

    fun toEntity(domain: Sighting): SightingEntity = SightingEntity(
        id = domain.id,
        standId = domain.standId,
        deerClass = domain.deerClass.name,
        count = domain.count,
        travelBearingDeg = domain.travelBearingDeg,
        distanceYards = domain.distanceYards,
        weatherSnapshotJson = domain.weatherSnapshot?.let { encodeSnapshot(it) },
        observedAtEpochMs = domain.observedAtEpochMs,
        notes = domain.notes,
    )

    fun toDomain(entity: WeaponProfileEntity): WeaponProfile = WeaponProfile(
        id = entity.id,
        name = entity.name,
        weaponType = WeaponType.valueOf(entity.weaponType),
        zeroYards = entity.zeroYards,
        dropTable = json.decodeFromString<List<WeaponDropEntryWire>>(entity.dropTableJson)
            .map { WeaponDropEntry(it.distanceYards, it.dropInches) },
    )

    fun toEntity(domain: WeaponProfile): WeaponProfileEntity = WeaponProfileEntity(
        id = domain.id,
        name = domain.name,
        weaponType = domain.weaponType.name,
        zeroYards = domain.zeroYards,
        dropTableJson = json.encodeToString(
            domain.dropTable.map { WeaponDropEntryWire(it.distanceYards, it.dropInches) },
        ),
    )

    fun toDomain(entity: AppSettingsEntity): AppSettings = AppSettings(
        lastKnownLat = entity.lastKnownLat,
        lastKnownLon = entity.lastKnownLon,
        peakRutMonthDay = entity.peakRutMonthDay,
        legalLightBeforeSunriseMinutes = entity.legalLightBeforeSunriseMinutes,
        legalLightAfterSunsetMinutes = entity.legalLightAfterSunsetMinutes,
        stealthModeEnabled = entity.stealthModeEnabled,
        customScorerWeights = entity.customScorerWeightsJson?.let { json.decodeFromString<Map<String, Double>>(it) },
        updatedAtEpochMs = entity.updatedAtEpochMs,
    )

    fun toEntity(domain: AppSettings): AppSettingsEntity = AppSettingsEntity(
        id = AppSettingsEntity.SINGLETON_ID,
        lastKnownLat = domain.lastKnownLat,
        lastKnownLon = domain.lastKnownLon,
        peakRutMonthDay = domain.peakRutMonthDay,
        legalLightBeforeSunriseMinutes = domain.legalLightBeforeSunriseMinutes,
        legalLightAfterSunsetMinutes = domain.legalLightAfterSunsetMinutes,
        stealthModeEnabled = domain.stealthModeEnabled,
        customScorerWeightsJson = domain.customScorerWeights?.let { json.encodeToString(it) },
        updatedAtEpochMs = domain.updatedAtEpochMs,
    )

    private fun encodeSnapshot(snapshot: WeatherSnapshot): String = json.encodeToString(
        WeatherSnapshotWire(
            tempDegF = snapshot.tempDegF,
            apparentTempDegF = snapshot.apparentTempDegF,
            barometricPressureInHg = snapshot.barometricPressureInHg,
            pressureTrend3hInHg = snapshot.pressureTrend3hInHg,
            windSpeedMph = snapshot.windSpeedMph,
            windGustMph = snapshot.windGustMph,
            windFromDeg = snapshot.windFromDeg,
            precipInchesPerHr = snapshot.precipInchesPerHr,
            cloudCoverPercent = snapshot.cloudCoverPercent,
            weatherCode = snapshot.weatherCode,
        ),
    )

    private fun decodeSnapshot(raw: String): WeatherSnapshot {
        val wire = json.decodeFromString<WeatherSnapshotWire>(raw)
        return WeatherSnapshot(
            tempDegF = wire.tempDegF,
            apparentTempDegF = wire.apparentTempDegF,
            barometricPressureInHg = wire.barometricPressureInHg,
            pressureTrend3hInHg = wire.pressureTrend3hInHg,
            windSpeedMph = wire.windSpeedMph,
            windGustMph = wire.windGustMph,
            windFromDeg = wire.windFromDeg,
            precipInchesPerHr = wire.precipInchesPerHr,
            cloudCoverPercent = wire.cloudCoverPercent,
            weatherCode = wire.weatherCode,
        )
    }
}

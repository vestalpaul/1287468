package com.buckcast.data.repository

import com.buckcast.data.local.dao.AppSettingsDao
import com.buckcast.data.local.dao.FieldLogDao
import com.buckcast.data.local.dao.SightingDao
import com.buckcast.data.local.dao.StandDao
import com.buckcast.data.local.dao.TrackDao
import com.buckcast.data.local.dao.WaypointDao
import com.buckcast.data.local.dao.WeaponProfileDao
import com.buckcast.data.local.dao.WeatherCacheDao
import com.buckcast.data.local.entities.AppSettingsEntity
import com.buckcast.data.local.entities.WeatherCacheEntity
import com.buckcast.data.local.mapper.EntityMappers
import com.buckcast.data.remote.OpenMeteoClient
import com.buckcast.data.remote.OpenMeteoMapper
import com.buckcast.data.remote.OpenMeteoResponse
import com.buckcast.domain.model.AppSettings
import com.buckcast.domain.model.CachedWeatherForecast
import com.buckcast.domain.model.FieldLog
import com.buckcast.domain.model.Sighting
import com.buckcast.domain.model.Stand
import com.buckcast.domain.model.StandRoute
import com.buckcast.domain.model.StandSit
import com.buckcast.domain.model.TrackPoint
import com.buckcast.domain.model.TrackSession
import com.buckcast.domain.model.Waypoint
import com.buckcast.domain.model.WeaponProfile
import com.buckcast.domain.repository.FieldDataRepository
import com.buckcast.domain.repository.SettingsRepository
import com.buckcast.domain.repository.StandRepository
import com.buckcast.domain.repository.WeatherRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

internal class StandRepositoryImpl(
    private val standDao: StandDao,
    private val mappers: EntityMappers,
) : StandRepository {

    override fun observeStands(): Flow<List<Stand>> =
        standDao.observeStands().map { list -> list.map(mappers::toDomain) }

    override fun observeRoutesForStand(standId: String): Flow<List<StandRoute>> =
        standDao.observeRoutesForStand(standId).map { list -> list.map(mappers::toDomain) }

    override fun observeSitsForStand(standId: String): Flow<List<StandSit>> =
        standDao.observeSitsForStand(standId).map { list -> list.map(mappers::toDomain) }

    override suspend fun saveStand(stand: Stand) {
        standDao.upsertStand(mappers.toEntity(stand))
    }

    override suspend fun updateStand(stand: Stand) {
        standDao.updateStand(mappers.toEntity(stand))
    }

    override suspend fun deleteStand(standId: String) {
        standDao.deleteStandById(standId)
    }

    override suspend fun saveRoute(route: StandRoute) {
        standDao.upsertRoute(mappers.toEntity(route))
    }

    override suspend fun recordSit(sit: StandSit) {
        standDao.upsertSit(mappers.toEntity(sit))
    }
}

internal class FieldDataRepositoryImpl(
    private val waypointDao: WaypointDao,
    private val trackDao: TrackDao,
    private val fieldLogDao: FieldLogDao,
    private val sightingDao: SightingDao,
    private val weaponProfileDao: WeaponProfileDao,
    private val mappers: EntityMappers,
) : FieldDataRepository {

    override fun observeWaypoints(): Flow<List<Waypoint>> =
        waypointDao.observeWaypoints().map { list -> list.map(mappers::toDomain) }

    override fun observeTrackSessions(): Flow<List<TrackSession>> =
        trackDao.observeSessions().map { list -> list.map(mappers::toDomain) }

    override fun observeTrackPoints(sessionId: String): Flow<List<TrackPoint>> =
        trackDao.observePointsForSession(sessionId).map { list -> list.map(mappers::toDomain) }

    override fun observeFieldLogs(): Flow<List<FieldLog>> =
        fieldLogDao.observeLogs().map { list -> list.map(mappers::toDomain) }

    override fun observeSightings(): Flow<List<Sighting>> =
        sightingDao.observeSightings().map { list -> list.map(mappers::toDomain) }

    override fun observeWeaponProfiles(): Flow<List<WeaponProfile>> =
        weaponProfileDao.observeProfiles().map { list -> list.map(mappers::toDomain) }

    override suspend fun saveWaypoint(waypoint: Waypoint) {
        waypointDao.upsert(mappers.toEntity(waypoint))
    }

    override suspend fun deleteWaypoint(waypointId: String) {
        waypointDao.deleteById(waypointId)
    }

    override suspend fun startTrackSession(session: TrackSession) {
        trackDao.upsertSession(mappers.toEntity(session))
    }

    override suspend fun finishTrackSession(sessionId: String, endedAtEpochMs: Long) {
        trackDao.finishSession(sessionId, endedAtEpochMs)
    }

    override suspend fun appendTrackPoint(point: TrackPoint) {
        trackDao.insertPoint(mappers.toEntity(point))
    }

    override suspend fun saveFieldLog(log: FieldLog) {
        fieldLogDao.upsert(mappers.toEntity(log))
    }

    override suspend fun saveSighting(sighting: Sighting) {
        sightingDao.upsert(mappers.toEntity(sighting))
    }

    override suspend fun saveWeaponProfile(profile: WeaponProfile) {
        weaponProfileDao.upsert(mappers.toEntity(profile))
    }
}

internal class SettingsRepositoryImpl(
    private val settingsDao: AppSettingsDao,
    private val mappers: EntityMappers,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : SettingsRepository {

    override fun observeSettings(): Flow<AppSettings> =
        settingsDao.observeSettings().map { entity ->
            entity?.let(mappers::toDomain) ?: AppSettings()
        }

    override suspend fun getSettings(): AppSettings =
        settingsDao.getSettingsOnce()?.let(mappers::toDomain) ?: AppSettings()

    override suspend fun updateLastKnownLocation(latitude: Double, longitude: Double) {
        val current = settingsDao.getSettingsOnce() ?: AppSettingsEntity()
        settingsDao.upsert(
            current.copy(
                id = AppSettingsEntity.SINGLETON_ID,
                lastKnownLat = latitude,
                lastKnownLon = longitude,
                updatedAtEpochMs = clockMs(),
            ),
        )
    }

    override suspend fun updateSettings(settings: AppSettings) {
        settingsDao.upsert(
            mappers.toEntity(settings.copy(updatedAtEpochMs = clockMs())),
        )
    }
}

internal class WeatherRepositoryImpl(
    private val weatherCacheDao: WeatherCacheDao,
    private val openMeteoClient: OpenMeteoClient,
    private val json: Json,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : WeatherRepository {

    override fun observeForecast(latitude: Double, longitude: Double): Flow<CachedWeatherForecast?> {
        val gridKey = OpenMeteoMapper.gridKeyFor(latitude, longitude)
        return weatherCacheDao.observeByGridKey(gridKey).map { entity ->
            entity?.let(::decodeCacheEntity)
        }
    }

    override fun observeLatestCachedForecast(): Flow<CachedWeatherForecast?> =
        weatherCacheDao.observeLatest().map { entity ->
            entity?.let(::decodeCacheEntity)
        }

    override suspend fun refreshForecast(
        latitude: Double,
        longitude: Double,
    ): Result<CachedWeatherForecast> {
        val gridKey = OpenMeteoMapper.gridKeyFor(latitude, longitude)
        return try {
            val result = openMeteoClient.fetchForecast(latitude, longitude)
            val nowMs = clockMs()
            val domain = OpenMeteoMapper.toDomain(
                gridKey = gridKey,
                response = result.response,
                fetchedAtEpochMs = nowMs,
                isStale = false,
            )
            weatherCacheDao.upsert(
                WeatherCacheEntity(
                    gridKey = gridKey,
                    latitude = result.response.latitude,
                    longitude = result.response.longitude,
                    elevationFeet = domain.elevationFeet,
                    timezoneId = result.response.timezone,
                    fetchedAtEpochMs = nowMs,
                    rawPayloadJson = result.rawPayloadJson,
                ),
            )
            Result.success(domain)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun decodeCacheEntity(entity: WeatherCacheEntity): CachedWeatherForecast {
        val parsed = json.decodeFromString<OpenMeteoResponse>(entity.rawPayloadJson)
        val isStale = (clockMs() - entity.fetchedAtEpochMs) > STALE_THRESHOLD_MS
        return OpenMeteoMapper.toDomain(
            gridKey = entity.gridKey,
            response = parsed,
            fetchedAtEpochMs = entity.fetchedAtEpochMs,
            isStale = isStale,
        )
    }

    companion object {
        const val STALE_THRESHOLD_MS = 3L * 60L * 60L * 1000L // 3 hours
    }
}

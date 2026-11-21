package com.buckcast.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
import com.buckcast.data.local.entities.WeatherCacheEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StandDao {
    @Query("SELECT * FROM stands ORDER BY createdAtEpochMs DESC")
    fun observeStands(): Flow<List<StandEntity>>

    @Query("SELECT * FROM stands")
    suspend fun getAllStandsOnce(): List<StandEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStand(stand: StandEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStands(stands: List<StandEntity>)

    @Query("DELETE FROM stands WHERE id = :standId")
    suspend fun deleteStandById(standId: String)

    @Query("DELETE FROM stands")
    suspend fun deleteAllStands()

    @Query("SELECT * FROM stand_routes WHERE standId = :standId")
    fun observeRoutesForStand(standId: String): Flow<List<StandRouteEntity>>

    @Query("SELECT * FROM stand_routes")
    suspend fun getAllRoutesOnce(): List<StandRouteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoute(route: StandRouteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoutes(routes: List<StandRouteEntity>)

    @Query("DELETE FROM stand_routes")
    suspend fun deleteAllRoutes()

    @Query("SELECT * FROM stand_sits WHERE standId = :standId ORDER BY endTimeEpochMs DESC")
    fun observeSitsForStand(standId: String): Flow<List<StandSitEntity>>

    @Query("SELECT * FROM stand_sits")
    suspend fun getAllSitsOnce(): List<StandSitEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSit(sit: StandSitEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSits(sits: List<StandSitEntity>)

    @Query("DELETE FROM stand_sits")
    suspend fun deleteAllSits()
}

@Dao
interface WaypointDao {
    @Query("SELECT * FROM waypoints ORDER BY createdAtEpochMs DESC")
    fun observeWaypoints(): Flow<List<WaypointEntity>>

    @Query("SELECT * FROM waypoints")
    suspend fun getAllOnce(): List<WaypointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(waypoint: WaypointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(waypoints: List<WaypointEntity>)

    @Query("DELETE FROM waypoints WHERE id = :waypointId")
    suspend fun deleteById(waypointId: String)

    @Query("DELETE FROM waypoints")
    suspend fun deleteAll()
}

@Dao
interface TrackDao {
    @Query("SELECT * FROM track_sessions ORDER BY startedAtEpochMs DESC")
    fun observeSessions(): Flow<List<TrackSessionEntity>>

    @Query("SELECT * FROM track_sessions")
    suspend fun getAllSessionsOnce(): List<TrackSessionEntity>

    @Query("SELECT * FROM track_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): TrackSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: TrackSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSessions(sessions: List<TrackSessionEntity>)

    @Query("UPDATE track_sessions SET endedAtEpochMs = :endedAtEpochMs WHERE id = :sessionId")
    suspend fun finishSession(sessionId: String, endedAtEpochMs: Long)

    @Query("DELETE FROM track_sessions")
    suspend fun deleteAllSessions()

    @Query("SELECT * FROM track_points WHERE sessionId = :sessionId ORDER BY recordedAtEpochMs ASC")
    fun observePointsForSession(sessionId: String): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM track_points WHERE sessionId = :sessionId ORDER BY recordedAtEpochMs ASC")
    suspend fun getPointsForSessionOnce(sessionId: String): List<TrackPointEntity>

    @Query("SELECT * FROM track_points ORDER BY recordedAtEpochMs ASC")
    suspend fun getAllPointsOnce(): List<TrackPointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoint(point: TrackPointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoints(points: List<TrackPointEntity>)

    @Query("DELETE FROM track_points")
    suspend fun deleteAllPoints()
}

@Dao
interface FieldLogDao {
    @Query("SELECT * FROM field_logs ORDER BY createdAtEpochMs DESC")
    fun observeLogs(): Flow<List<FieldLogEntity>>

    @Query("SELECT * FROM field_logs")
    suspend fun getAllOnce(): List<FieldLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(log: FieldLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(logs: List<FieldLogEntity>)

    @Query("DELETE FROM field_logs")
    suspend fun deleteAll()
}

@Dao
interface SightingDao {
    @Query("SELECT * FROM sightings ORDER BY observedAtEpochMs DESC")
    fun observeSightings(): Flow<List<SightingEntity>>

    @Query("SELECT * FROM sightings")
    suspend fun getAllOnce(): List<SightingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sighting: SightingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sightings: List<SightingEntity>)

    @Query("DELETE FROM sightings")
    suspend fun deleteAll()
}

@Dao
interface WeatherCacheDao {
    @Query("SELECT * FROM weather_cache WHERE gridKey = :gridKey LIMIT 1")
    fun observeByGridKey(gridKey: String): Flow<WeatherCacheEntity?>

    @Query("SELECT * FROM weather_cache ORDER BY fetchedAtEpochMs DESC LIMIT 1")
    fun observeLatest(): Flow<WeatherCacheEntity?>

    @Query("SELECT * FROM weather_cache WHERE gridKey = :gridKey LIMIT 1")
    suspend fun getByGridKeyOnce(gridKey: String): WeatherCacheEntity?

    @Query("SELECT * FROM weather_cache ORDER BY fetchedAtEpochMs DESC LIMIT 1")
    suspend fun getLatestOnce(): WeatherCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cache: WeatherCacheEntity)

    @Query("DELETE FROM weather_cache")
    suspend fun deleteAll()
}

@Dao
interface WeaponProfileDao {
    @Query("SELECT * FROM weapon_profiles ORDER BY name ASC")
    fun observeProfiles(): Flow<List<WeaponProfileEntity>>

    @Query("SELECT * FROM weapon_profiles")
    suspend fun getAllOnce(): List<WeaponProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: WeaponProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(profiles: List<WeaponProfileEntity>)

    @Query("DELETE FROM weapon_profiles")
    suspend fun deleteAll()
}

@Dao
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = ${AppSettingsEntity.SINGLETON_ID} LIMIT 1")
    fun observeSettings(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = ${AppSettingsEntity.SINGLETON_ID} LIMIT 1")
    suspend fun getSettingsOnce(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: AppSettingsEntity)
}

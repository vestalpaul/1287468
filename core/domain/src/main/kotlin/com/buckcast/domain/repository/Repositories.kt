package com.buckcast.domain.repository

import com.buckcast.domain.entitlement.DebugEntitlementOverride
import com.buckcast.domain.entitlement.EntitlementState
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
import kotlinx.coroutines.flow.Flow

interface StandRepository {
    fun observeStands(): Flow<List<Stand>>
    fun observeRoutesForStand(standId: String): Flow<List<StandRoute>>
    fun observeSitsForStand(standId: String): Flow<List<StandSit>>
    suspend fun saveStand(stand: Stand)
    suspend fun deleteStand(standId: String)
    suspend fun saveRoute(route: StandRoute)
    suspend fun recordSit(sit: StandSit)
}

interface FieldDataRepository {
    fun observeWaypoints(): Flow<List<Waypoint>>
    fun observeTrackSessions(): Flow<List<TrackSession>>
    fun observeTrackPoints(sessionId: String): Flow<List<TrackPoint>>
    fun observeFieldLogs(): Flow<List<FieldLog>>
    fun observeSightings(): Flow<List<Sighting>>
    fun observeWeaponProfiles(): Flow<List<WeaponProfile>>

    suspend fun saveWaypoint(waypoint: Waypoint)
    suspend fun deleteWaypoint(waypointId: String)
    suspend fun startTrackSession(session: TrackSession)
    suspend fun finishTrackSession(sessionId: String, endedAtEpochMs: Long)
    suspend fun appendTrackPoint(point: TrackPoint)
    suspend fun saveFieldLog(log: FieldLog)
    suspend fun saveSighting(sighting: Sighting)
    suspend fun saveWeaponProfile(profile: WeaponProfile)
}

interface WeatherRepository {
    fun observeForecast(latitude: Double, longitude: Double): Flow<CachedWeatherForecast?>
    fun observeLatestCachedForecast(): Flow<CachedWeatherForecast?>
    suspend fun refreshForecast(latitude: Double, longitude: Double): Result<CachedWeatherForecast>
}

interface SettingsRepository {
    fun observeSettings(): Flow<AppSettings>
    suspend fun getSettings(): AppSettings
    suspend fun updateLastKnownLocation(latitude: Double, longitude: Double)
    suspend fun updateSettings(settings: AppSettings)
}

interface BackupRepository {
    suspend fun exportBackupJson(): String
    suspend fun importBackupJson(json: String)
    suspend fun exportTrackGpx(sessionId: String): String
}

interface EntitlementRepository {
    fun observeEntitlement(): Flow<EntitlementState>
    suspend fun ensureInitialized()
    suspend fun refreshEntitlements(): Result<EntitlementState>
    suspend fun restorePurchases(): Result<EntitlementState>
    suspend fun logInWithAccessCode(code: String): Result<EntitlementState>
    suspend fun purchaseAlpha(isAnnual: Boolean): Result<EntitlementState>
    suspend fun getSupportUserId(): String
    suspend fun setDebugOverride(override: DebugEntitlementOverride)
    suspend fun reset36HourPreview()
}

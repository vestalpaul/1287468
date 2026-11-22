package com.buckcast.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "stands")
data class StandEntity(
    @PrimaryKey val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val elevationFeet: Double? = null,
    val slopeAspectDeg: Double? = null,
    val slopeGradePercent: Double? = null,
    val standType: String,
    val standHeightFeet: Double,
    val terrainPosition: String,
    val preferredWindSectorsJson: String,
    val notes: String,
    val createdAtEpochMs: Long,
)

@Serializable
@Entity(
    tableName = "stand_routes",
    foreignKeys = [
        ForeignKey(
            entity = StandEntity::class,
            parentColumns = ["id"],
            childColumns = ["standId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("standId")],
)
data class StandRouteEntity(
    @PrimaryKey val id: String,
    val standId: String,
    val routeType: String,
    val polylineJson: String,
)

@Serializable
@Entity(
    tableName = "stand_sits",
    foreignKeys = [
        ForeignKey(
            entity = StandEntity::class,
            parentColumns = ["id"],
            childColumns = ["standId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("standId")],
)
data class StandSitEntity(
    @PrimaryKey val id: String,
    val standId: String,
    val startTimeEpochMs: Long,
    val endTimeEpochMs: Long,
    val windedDeer: Boolean,
)

@Serializable
@Entity(tableName = "waypoints")
data class WaypointEntity(
    @PrimaryKey val id: String,
    val category: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val elevationFeet: Double? = null,
    val notes: String,
    val createdAtEpochMs: Long,
)

@Serializable
@Entity(tableName = "track_sessions")
data class TrackSessionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val trackType: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val notes: String,
)

@Serializable
@Entity(
    tableName = "track_points",
    foreignKeys = [
        ForeignKey(
            entity = TrackSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: String,
    val latitude: Double,
    val longitude: Double,
    val elevationFeet: Double? = null,
    val recordedAtEpochMs: Long,
)

@Serializable
@Entity(
    tableName = "field_logs",
    foreignKeys = [
        ForeignKey(
            entity = StandEntity::class,
            parentColumns = ["id"],
            childColumns = ["standId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("standId")],
)
data class FieldLogEntity(
    @PrimaryKey val id: String,
    val standId: String? = null,
    val notes: String,
    val photoUrisJson: String,
    val latitude: Double,
    val longitude: Double,
    val elevationFeet: Double? = null,
    val weatherSnapshotJson: String? = null,
    val rutPhaseNumber: Int? = null,
    val createdAtEpochMs: Long,
)

@Serializable
@Entity(
    tableName = "sightings",
    foreignKeys = [
        ForeignKey(
            entity = StandEntity::class,
            parentColumns = ["id"],
            childColumns = ["standId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("standId")],
)
data class SightingEntity(
    @PrimaryKey val id: String,
    val standId: String? = null,
    val deerClass: String,
    val count: Int,
    val travelBearingDeg: Double? = null,
    val distanceYards: Double? = null,
    val weatherSnapshotJson: String? = null,
    val observedAtEpochMs: Long,
    val notes: String,
)

@Entity(tableName = "weather_cache")
data class WeatherCacheEntity(
    @PrimaryKey val gridKey: String,
    val latitude: Double,
    val longitude: Double,
    val elevationFeet: Double,
    val timezoneId: String,
    val fetchedAtEpochMs: Long,
    val rawPayloadJson: String,
)

@Serializable
@Entity(tableName = "weapon_profiles")
data class WeaponProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val weaponType: String,
    val zeroYards: Double,
    val dropTableJson: String,
)

@Serializable
@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val lastKnownLat: Double? = null,
    val lastKnownLon: Double? = null,
    val peakRutMonthDay: String = "11-11",
    val legalLightBeforeSunriseMinutes: Long = 30L,
    val legalLightAfterSunsetMinutes: Long = 30L,
    val stealthModeEnabled: Boolean = false,
    val themePreset: String = "TIMBERLINE_DARK",
    val compassOffsetDeg: Double = 0.0,
    val customScorerWeightsJson: String? = null,
    val updatedAtEpochMs: Long = 0L,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Serializable
@Entity(tableName = "entitlement_cache")
data class EntitlementCacheEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val localFirstLaunchEpochMs: Long,
    val serverFirstSeenEpochMs: Long? = null,
    val maxObservedEpochMs: Long,
    val lastKnownTier: String = "SCOUT",
    val lastKnownSource: String = "NONE",
    val subscriptionExpiresEpochMs: Long? = null,
    val lastVerifiedEpochMs: Long? = null,
    val debugOverride: String = "AUTO",
    val supportUserId: String,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

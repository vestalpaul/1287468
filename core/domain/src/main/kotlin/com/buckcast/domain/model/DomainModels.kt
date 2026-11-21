package com.buckcast.domain.model

import com.buckcast.domain.math.BearingSector
import com.buckcast.domain.math.GeoPoint

enum class StandType {
    LADDER_STAND,
    HANG_ON,
    CLIMBER,
    SADDLE,
    GROUND_BLIND,
    BOX_BLIND,
}

enum class TerrainPosition {
    RIDGE_TOP,
    UPPER_THIRD,
    MID_SLOPE,
    BENCH_TERRACE,
    CREEK_BOTTOM,
    FOOD_EDGE,
    FLAT_TIMBER,
}

enum class RouteType {
    ENTRY,
    EXIT,
}

enum class WaypointCategory {
    SCRAPE,
    RUB,
    BEDDING,
    FOOD,
    TRAIL,
    HARVEST,
    SHOT_SITE,
    BLOOD_DROP,
    LAST_BLOOD,
}

enum class TrackType {
    WALK_IN,
    BLOOD_TRAIL,
    SCOUTING,
}

enum class DeerClass {
    MATURE_BUCK,
    YOUNG_BUCK,
    DOE,
    FAWN,
    UNKNOWN,
}

enum class WeaponType {
    COMPOUND_BOW,
    CROSSBOW,
    TRADITIONAL_BOW,
    RIFLE,
    MUZZLELOADER,
    SHOTGUN,
}

data class Stand(
    val id: String,
    val name: String,
    val location: GeoPoint,
    val standType: StandType,
    val standHeightFeet: Double,
    val terrainPosition: TerrainPosition,
    val preferredWindSectors: List<BearingSector>,
    val elevationFeet: Double? = location.elevationFeet,
    val slopeAspectDeg: Double? = null,
    val slopeGradePercent: Double? = null,
    val notes: String = "",
    val createdAtEpochMs: Long,
)

data class StandRoute(
    val id: String,
    val standId: String,
    val routeType: RouteType,
    val coordinates: List<GeoPoint>,
)

data class StandSit(
    val id: String,
    val standId: String,
    val startTimeEpochMs: Long,
    val endTimeEpochMs: Long,
    val windedDeer: Boolean,
)

data class Waypoint(
    val id: String,
    val category: WaypointCategory,
    val name: String,
    val location: GeoPoint,
    val notes: String = "",
    val createdAtEpochMs: Long,
)

data class TrackSession(
    val id: String,
    val name: String,
    val trackType: TrackType,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val notes: String = "",
)

data class TrackPoint(
    val id: Long = 0L,
    val sessionId: String,
    val location: GeoPoint,
    val recordedAtEpochMs: Long,
)

data class WeatherSnapshot(
    val tempDegF: Double,
    val apparentTempDegF: Double,
    val barometricPressureInHg: Double,
    val pressureTrend3hInHg: Double,
    val windSpeedMph: Double,
    val windGustMph: Double?,
    val windFromDeg: Double,
    val precipInchesPerHr: Double,
    val cloudCoverPercent: Double,
    val weatherCode: Int,
)

data class FieldLog(
    val id: String,
    val standId: String?,
    val notes: String,
    val photoUris: List<String>,
    val location: GeoPoint,
    val weatherSnapshot: WeatherSnapshot?,
    val rutPhaseNumber: Int?,
    val createdAtEpochMs: Long,
)

data class Sighting(
    val id: String,
    val standId: String?,
    val deerClass: DeerClass,
    val count: Int,
    val travelBearingDeg: Double?,
    val distanceYards: Double?,
    val weatherSnapshot: WeatherSnapshot?,
    val observedAtEpochMs: Long,
    val notes: String = "",
)

data class HourlyWeatherPoint(
    val epochSeconds: Long,
    val isoLocalTime: String,
    val tempDegF: Double,
    val apparentTempDegF: Double,
    val barometricPressureInHg: Double,
    val pressureTrend3hInHg: Double,
    val windSpeedMph: Double,
    val windGustMph: Double?,
    val windFromDeg: Double,
    val precipInchesPerHr: Double,
    val cloudCoverPercent: Double,
    val weatherCode: Int,
)

data class DailyWeatherSummary(
    val isoDate: String,
    val tempMaxDegF: Double,
    val tempMinDegF: Double,
    val precipSumInches: Double,
    val windSpeedMaxMph: Double,
    val dominantWindFromDeg: Double,
    val weatherCode: Int,
)

data class CachedWeatherForecast(
    val gridKey: String,
    val latitude: Double,
    val longitude: Double,
    val elevationFeet: Double,
    val timezoneId: String,
    val fetchedAtEpochMs: Long,
    val isStale: Boolean,
    val hourly: List<HourlyWeatherPoint>,
    val daily: List<DailyWeatherSummary>,
)

data class WeaponDropEntry(
    val distanceYards: Double,
    val dropInches: Double,
)

data class WeaponProfile(
    val id: String,
    val name: String,
    val weaponType: WeaponType,
    val zeroYards: Double,
    val dropTable: List<WeaponDropEntry>,
)

data class AppSettings(
    val lastKnownLat: Double? = null,
    val lastKnownLon: Double? = null,
    val peakRutMonthDay: String = "11-11",
    val legalLightBeforeSunriseMinutes: Long = 30L,
    val legalLightAfterSunsetMinutes: Long = 30L,
    val stealthModeEnabled: Boolean = false,
    val customScorerWeights: Map<String, Double>? = null,
    val updatedAtEpochMs: Long = 0L,
)

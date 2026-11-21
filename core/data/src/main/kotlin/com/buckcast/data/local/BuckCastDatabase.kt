package com.buckcast.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.buckcast.data.local.dao.AppSettingsDao
import com.buckcast.data.local.dao.FieldLogDao
import com.buckcast.data.local.dao.SightingDao
import com.buckcast.data.local.dao.StandDao
import com.buckcast.data.local.dao.TrackDao
import com.buckcast.data.local.dao.WaypointDao
import com.buckcast.data.local.dao.WeaponProfileDao
import com.buckcast.data.local.dao.WeatherCacheDao
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

@Database(
    entities = [
        StandEntity::class,
        StandRouteEntity::class,
        StandSitEntity::class,
        WaypointEntity::class,
        TrackSessionEntity::class,
        TrackPointEntity::class,
        FieldLogEntity::class,
        SightingEntity::class,
        WeatherCacheEntity::class,
        WeaponProfileEntity::class,
        AppSettingsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class BuckCastDatabase : RoomDatabase() {
    abstract fun standDao(): StandDao
    abstract fun waypointDao(): WaypointDao
    abstract fun trackDao(): TrackDao
    abstract fun fieldLogDao(): FieldLogDao
    abstract fun sightingDao(): SightingDao
    abstract fun weatherCacheDao(): WeatherCacheDao
    abstract fun weaponProfileDao(): WeaponProfileDao
    abstract fun appSettingsDao(): AppSettingsDao
}

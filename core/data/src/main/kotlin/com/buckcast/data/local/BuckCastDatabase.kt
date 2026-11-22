package com.buckcast.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.buckcast.data.local.dao.AppSettingsDao
import com.buckcast.data.local.dao.EntitlementCacheDao
import com.buckcast.data.local.dao.FieldLogDao
import com.buckcast.data.local.dao.SightingDao
import com.buckcast.data.local.dao.StandDao
import com.buckcast.data.local.dao.TrackDao
import com.buckcast.data.local.dao.WaypointDao
import com.buckcast.data.local.dao.WeaponProfileDao
import com.buckcast.data.local.dao.WeatherCacheDao
import com.buckcast.data.local.entities.AppSettingsEntity
import com.buckcast.data.local.entities.EntitlementCacheEntity
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
        EntitlementCacheEntity::class,
    ],
    version = 3,
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
    abstract fun entitlementCacheDao(): EntitlementCacheDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE app_settings ADD COLUMN themePreset TEXT NOT NULL DEFAULT 'TIMBERLINE_DARK'",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `entitlement_cache` (
                        `id` INTEGER NOT NULL,
                        `localFirstLaunchEpochMs` INTEGER NOT NULL,
                        `serverFirstSeenEpochMs` INTEGER,
                        `maxObservedEpochMs` INTEGER NOT NULL,
                        `lastKnownTier` TEXT NOT NULL,
                        `lastKnownSource` TEXT NOT NULL,
                        `subscriptionExpiresEpochMs` INTEGER,
                        `lastVerifiedEpochMs` INTEGER,
                        `debugOverride` TEXT NOT NULL,
                        `supportUserId` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE app_settings ADD COLUMN compassOffsetDeg REAL NOT NULL DEFAULT 0.0",
                )
            }
        }
    }
}

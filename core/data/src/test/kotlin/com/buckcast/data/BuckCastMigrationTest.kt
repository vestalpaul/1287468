package com.buckcast.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.buckcast.data.local.BuckCastDatabase
import com.buckcast.data.local.entities.EntitlementCacheEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BuckCastMigrationTest {

    private val dbName = "buckcast-migration-test.db"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration 1 to 2 preserves stands and settings, defaults themePreset, and initializes entitlement_cache`() =
        runTest {
            createV1DatabaseAndSeedData()

            val migratedDb = Room.databaseBuilder(context, BuckCastDatabase::class.java, dbName)
                .addMigrations(BuckCastDatabase.MIGRATION_1_2)
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .allowMainThreadQueries()
                .build()

            try {
                val stands = migratedDb.standDao().observeStands().first()
                assertEquals(1, stands.size)
                assertEquals("Legacy Oak Stand", stands.first().name)

                val settings = migratedDb.appSettingsDao().getSettingsOnce()
                assertNotNull(settings)
                assertEquals("TIMBERLINE_DARK", settings!!.themePreset)
                assertEquals("11-11", settings.peakRutMonthDay)

                migratedDb.entitlementCacheDao().upsert(
                    EntitlementCacheEntity(
                        localFirstLaunchEpochMs = 1_795_000_000_000L,
                        maxObservedEpochMs = 1_795_000_000_000L,
                        supportUserId = "dt_migrated_01",
                    ),
                )
                val cache = migratedDb.entitlementCacheDao().getCacheOnce()
                assertEquals("dt_migrated_01", cache?.supportUserId)
            } finally {
                migratedDb.close()
            }
        }

    private fun createV1DatabaseAndSeedData() {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `stands` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `elevationFeet` REAL, `slopeAspectDeg` REAL, `slopeGradePercent` REAL, `standType` TEXT NOT NULL, `standHeightFeet` REAL NOT NULL, `terrainPosition` TEXT NOT NULL, `preferredWindSectorsJson` TEXT NOT NULL, `notes` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `stand_routes` (`id` TEXT NOT NULL, `standId` TEXT NOT NULL, `routeType` TEXT NOT NULL, `polylineJson` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`standId`) REFERENCES `stands`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stand_routes_standId` ON `stand_routes` (`standId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `stand_sits` (`id` TEXT NOT NULL, `standId` TEXT NOT NULL, `startTimeEpochMs` INTEGER NOT NULL, `endTimeEpochMs` INTEGER NOT NULL, `windedDeer` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`standId`) REFERENCES `stands`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stand_sits_standId` ON `stand_sits` (`standId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `waypoints` (`id` TEXT NOT NULL, `category` TEXT NOT NULL, `name` TEXT NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `elevationFeet` REAL, `notes` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `track_sessions` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `trackType` TEXT NOT NULL, `startedAtEpochMs` INTEGER NOT NULL, `endedAtEpochMs` INTEGER, `notes` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `track_points` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` TEXT NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `elevationFeet` REAL, `recordedAtEpochMs` INTEGER NOT NULL, FOREIGN KEY(`sessionId`) REFERENCES `track_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_points_sessionId` ON `track_points` (`sessionId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `field_logs` (`id` TEXT NOT NULL, `standId` TEXT, `notes` TEXT NOT NULL, `photoUrisJson` TEXT NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `elevationFeet` REAL, `weatherSnapshotJson` TEXT, `rutPhaseNumber` INTEGER, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`standId`) REFERENCES `stands`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_field_logs_standId` ON `field_logs` (`standId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `sightings` (`id` TEXT NOT NULL, `standId` TEXT, `deerClass` TEXT NOT NULL, `count` INTEGER NOT NULL, `travelBearingDeg` REAL, `distanceYards` REAL, `weatherSnapshotJson` TEXT, `observedAtEpochMs` INTEGER NOT NULL, `notes` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`standId`) REFERENCES `stands`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sightings_standId` ON `sightings` (`standId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `weather_cache` (`gridKey` TEXT NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `elevationFeet` REAL NOT NULL, `timezoneId` TEXT NOT NULL, `fetchedAtEpochMs` INTEGER NOT NULL, `rawPayloadJson` TEXT NOT NULL, PRIMARY KEY(`gridKey`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `weapon_profiles` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `weaponType` TEXT NOT NULL, `zeroYards` REAL NOT NULL, `dropTableJson` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `app_settings` (`id` INTEGER NOT NULL, `lastKnownLat` REAL, `lastKnownLon` REAL, `peakRutMonthDay` TEXT NOT NULL, `legalLightBeforeSunriseMinutes` INTEGER NOT NULL, `legalLightAfterSunsetMinutes` INTEGER NOT NULL, `stealthModeEnabled` INTEGER NOT NULL, `customScorerWeightsJson` TEXT, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'dd3d3ed82687d7706b07071c63d5f2c2')")

                db.execSQL(
                    """
                    INSERT INTO stands (id, name, latitude, longitude, elevationFeet, slopeAspectDeg, slopeGradePercent, standType, standHeightFeet, terrainPosition, preferredWindSectorsJson, notes, createdAtEpochMs)
                    VALUES ('s-v1', 'Legacy Oak Stand', 41.58, -93.62, 940.0, 180.0, 15.0, 'HANG_ON', 20.0, 'UPPER_THIRD', '[]', 'v1 seed', 1795000000000)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO app_settings (id, lastKnownLat, lastKnownLon, peakRutMonthDay, legalLightBeforeSunriseMinutes, legalLightAfterSunsetMinutes, stealthModeEnabled, customScorerWeightsJson, updatedAtEpochMs)
                    VALUES (1, 41.58, -93.62, '11-11', 30, 30, 0, NULL, 1795000000000)
                    """.trimIndent(),
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        context.getDatabasePath(dbName).parentFile?.mkdirs()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(callback)
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper.writableDatabase.close()
        helper.close()
    }
}

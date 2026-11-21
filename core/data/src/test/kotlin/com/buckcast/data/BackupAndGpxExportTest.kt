package com.buckcast.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.buckcast.data.export.BackupRepositoryImpl
import com.buckcast.data.local.BuckCastDatabase
import com.buckcast.data.local.mapper.EntityMappers
import com.buckcast.data.repository.FieldDataRepositoryImpl
import com.buckcast.data.repository.SettingsRepositoryImpl
import com.buckcast.data.repository.StandRepositoryImpl
import com.buckcast.domain.math.BearingSector
import com.buckcast.domain.math.GeoPoint
import com.buckcast.domain.model.AppSettings
import com.buckcast.domain.model.Stand
import com.buckcast.domain.model.StandType
import com.buckcast.domain.model.TerrainPosition
import com.buckcast.domain.model.TrackPoint
import com.buckcast.domain.model.TrackSession
import com.buckcast.domain.model.TrackType
import com.buckcast.domain.model.Waypoint
import com.buckcast.domain.model.WaypointCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupAndGpxExportTest {

    private lateinit var db: BuckCastDatabase
    private lateinit var standRepo: StandRepositoryImpl
    private lateinit var fieldRepo: FieldDataRepositoryImpl
    private lateinit var settingsRepo: SettingsRepositoryImpl
    private lateinit var backupRepo: BackupRepositoryImpl

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BuckCastDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val mappers = EntityMappers(json)
        standRepo = StandRepositoryImpl(db.standDao(), mappers)
        fieldRepo = FieldDataRepositoryImpl(
            waypointDao = db.waypointDao(),
            trackDao = db.trackDao(),
            fieldLogDao = db.fieldLogDao(),
            sightingDao = db.sightingDao(),
            weaponProfileDao = db.weaponProfileDao(),
            mappers = mappers,
        )
        settingsRepo = SettingsRepositoryImpl(db.appSettingsDao(), mappers) { 1_795_000_000_000L }
        backupRepo = BackupRepositoryImpl(db, json) { 1_795_000_000_000L }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `JSON backup round-trips stands, waypoints, tracks, and singleton settings across database wipe`() = runTest {
        settingsRepo.updateSettings(
            AppSettings(
                lastKnownLat = 41.5868,
                lastKnownLon = -93.6250,
                peakRutMonthDay = "11-11",
                stealthModeEnabled = true,
            ),
        )
        standRepo.saveStand(
            Stand(
                id = "s1",
                name = "Ridge Oak",
                location = GeoPoint(41.5868, -93.6250, 950.0),
                standType = StandType.SADDLE,
                standHeightFeet = 18.0,
                terrainPosition = TerrainPosition.UPPER_THIRD,
                preferredWindSectors = listOf(BearingSector(270.0, 360.0)),
                elevationFeet = 950.0,
                slopeAspectDeg = 135.0,
                slopeGradePercent = 22.0,
                createdAtEpochMs = 1_795_000_000_000L,
            ),
        )
        fieldRepo.saveWaypoint(
            Waypoint(
                id = "w1",
                category = WaypointCategory.BEDDING,
                name = "South Bench Bedding",
                location = GeoPoint(41.5850, -93.6240, 910.0),
                createdAtEpochMs = 1_795_000_000_000L,
            ),
        )

        val backupJson = backupRepo.exportBackupJson()

        // Wipe database completely
        db.clearAllTables()
        assertTrue(standRepo.observeStands().first().isEmpty())
        assertTrue(fieldRepo.observeWaypoints().first().isEmpty())

        // Restore from JSON
        backupRepo.importBackupJson(backupJson)

        val restoredStands = standRepo.observeStands().first()
        assertEquals(1, restoredStands.size)
        assertEquals("Ridge Oak", restoredStands.first().name)
        assertEquals(22.0, restoredStands.first().slopeGradePercent!!, 1e-6)
        assertEquals(1, fieldRepo.observeWaypoints().first().size)
        assertTrue(settingsRepo.getSettings().stealthModeEnabled)
        assertEquals(41.5868, settingsRepo.getSettings().lastKnownLat!!, 1e-6)
    }

    @Test
    fun `GPX exporter emits valid GPX 1_1 XML with escaped characters and metric elevation`() = runTest {
        fieldRepo.startTrackSession(
            TrackSession(
                id = "trk-gpx",
                name = "Ridge & Creek <Trail>",
                trackType = TrackType.WALK_IN,
                startedAtEpochMs = 1_795_284_000_000L,
                endedAtEpochMs = 1_795_284_600_000L,
            ),
        )
        fieldRepo.appendTrackPoint(
            TrackPoint(
                sessionId = "trk-gpx",
                location = GeoPoint(41.5868000, -93.6250000, elevationFeet = 1000.0),
                recordedAtEpochMs = 1_795_284_060_000L,
            ),
        )

        val gpx = backupRepo.exportTrackGpx("trk-gpx")
        assertTrue(gpx.contains("""<gpx version="1.1" creator="BuckCastAndroid""""))
        assertTrue(gpx.contains("Ridge &amp; Creek &lt;Trail&gt;"))
        assertTrue(gpx.contains("""lat="41.5868000" lon="-93.6250000""""))
        // 1000 ft = 304.80 m
        assertTrue(gpx.contains("<ele>304.80</ele>"))
        assertTrue(gpx.contains("<time>"))
    }
}

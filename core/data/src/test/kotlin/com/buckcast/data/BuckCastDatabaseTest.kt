package com.buckcast.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.buckcast.data.local.BuckCastDatabase
import com.buckcast.data.local.mapper.EntityMappers
import com.buckcast.data.repository.FieldDataRepositoryImpl
import com.buckcast.data.repository.SettingsRepositoryImpl
import com.buckcast.data.repository.StandRepositoryImpl
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BuckCastDatabaseTest {

    private lateinit var db: BuckCastDatabase
    private lateinit var standRepo: StandRepositoryImpl
    private lateinit var fieldRepo: FieldDataRepositoryImpl
    private lateinit var settingsRepo: SettingsRepositoryImpl

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
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `stands with terrain attributes, routes, and sits persist and cascade delete cleanly`() = runTest {
        val stand = Stand(
            id = "stand-1",
            name = "North Creek Pinch",
            location = GeoPoint(41.5868, -93.6250, elevationFeet = 945.0),
            standType = StandType.HANG_ON,
            standHeightFeet = 21.0,
            terrainPosition = TerrainPosition.CREEK_BOTTOM,
            preferredWindSectors = listOf(BearingSector(315.0, 45.0)),
            elevationFeet = 945.0,
            slopeAspectDeg = 180.0,
            slopeGradePercent = 18.5,
            notes = "Big white oak on west bank",
            createdAtEpochMs = 1_795_000_000_000L,
        )
        standRepo.saveStand(stand)
        standRepo.saveRoute(
            StandRoute(
                id = "route-1",
                standId = "stand-1",
                routeType = RouteType.ENTRY,
                coordinates = listOf(GeoPoint(41.580, -93.625), GeoPoint(41.5868, -93.625)),
            ),
        )
        standRepo.recordSit(
            StandSit(
                id = "sit-1",
                standId = "stand-1",
                startTimeEpochMs = 1_794_900_000_000L,
                endTimeEpochMs = 1_794_914_400_000L,
                windedDeer = true,
            ),
        )

        val stands = standRepo.observeStands().first()
        assertEquals(1, stands.size)
        assertEquals(180.0, stands.first().slopeAspectDeg!!, 1e-6)
        assertEquals(18.5, stands.first().slopeGradePercent!!, 1e-6)
        assertEquals(1, standRepo.observeRoutesForStand("stand-1").first().size)
        assertEquals(true, standRepo.observeSitsForStand("stand-1").first().first().windedDeer)

        // Deleting stand cascades routes and sits
        standRepo.deleteStand("stand-1")
        assertTrue(standRepo.observeStands().first().isEmpty())
        assertTrue(standRepo.observeRoutesForStand("stand-1").first().isEmpty())
        assertTrue(standRepo.observeSitsForStand("stand-1").first().isEmpty())
    }

    @Test
    fun `waypoints, tracks, field logs, sightings, weapon profiles, and singleton settings persist offline`() = runTest {
        WaypointCategory.entries.forEachIndexed { idx, category ->
            fieldRepo.saveWaypoint(
                Waypoint(
                    id = "wp-$idx",
                    category = category,
                    name = "Point ${category.name}",
                    location = GeoPoint(41.58 + idx * 0.001, -93.62, elevationFeet = 920.0),
                    createdAtEpochMs = 1_795_000_000_000L + idx,
                ),
            )
        }
        assertEquals(WaypointCategory.entries.size, fieldRepo.observeWaypoints().first().size)

        fieldRepo.startTrackSession(
            TrackSession(
                id = "trk-1",
                name = "Doe Blood Trail",
                trackType = TrackType.BLOOD_TRAIL,
                startedAtEpochMs = 1_795_000_000_000L,
                endedAtEpochMs = null,
            ),
        )
        fieldRepo.appendTrackPoint(
            TrackPoint(
                sessionId = "trk-1",
                location = GeoPoint(41.5868, -93.6250, elevationFeet = 930.0),
                recordedAtEpochMs = 1_795_000_010_000L,
            ),
        )
        fieldRepo.finishTrackSession("trk-1", 1_795_000_600_000L)
        assertNotNull(fieldRepo.observeTrackSessions().first().first().endedAtEpochMs)
        assertEquals(1, fieldRepo.observeTrackPoints("trk-1").first().size)

        val weather = WeatherSnapshot(
            tempDegF = 38.0,
            apparentTempDegF = 32.0,
            barometricPressureInHg = 30.18,
            pressureTrend3hInHg = 0.05,
            windSpeedMph = 8.0,
            windGustMph = 12.0,
            windFromDeg = 315.0,
            precipInchesPerHr = 0.0,
            cloudCoverPercent = 25.0,
            weatherCode = 2,
        )
        fieldRepo.saveFieldLog(
            FieldLog(
                id = "log-1",
                standId = null,
                notes = "Fresh scrape on field edge",
                photoUris = listOf("content://media/1"),
                location = GeoPoint(41.5870, -93.6251, elevationFeet = 915.0),
                weatherSnapshot = weather,
                rutPhaseNumber = 9,
                createdAtEpochMs = 1_795_000_000_000L,
            ),
        )
        fieldRepo.saveSighting(
            Sighting(
                id = "sight-1",
                standId = null,
                deerClass = DeerClass.MATURE_BUCK,
                count = 1,
                travelBearingDeg = 210.0,
                distanceYards = 35.0,
                weatherSnapshot = weather,
                observedAtEpochMs = 1_795_000_000_000L,
            ),
        )
        fieldRepo.saveWeaponProfile(
            WeaponProfile(
                id = "wpn-1",
                name = "Mathews Phase4 29",
                weaponType = WeaponType.COMPOUND_BOW,
                zeroYards = 20.0,
                dropTable = listOf(WeaponDropEntry(30.0, -4.5), WeaponDropEntry(40.0, -11.2)),
            ),
        )
        assertEquals(1, fieldRepo.observeFieldLogs().first().size)
        assertEquals(1, fieldRepo.observeSightings().first().size)
        assertEquals(1, fieldRepo.observeWeaponProfiles().first().size)

        settingsRepo.updateSettings(
            AppSettings(
                lastKnownLat = 41.5868,
                lastKnownLon = -93.6250,
                peakRutMonthDay = "11-11",
                legalLightBeforeSunriseMinutes = 30L,
                legalLightAfterSunsetMinutes = 30L,
                stealthModeEnabled = true,
            ),
        )
        val loadedSettings = settingsRepo.getSettings()
        assertEquals(41.5868, loadedSettings.lastKnownLat!!, 1e-6)
        assertTrue(loadedSettings.stealthModeEnabled)
    }
}

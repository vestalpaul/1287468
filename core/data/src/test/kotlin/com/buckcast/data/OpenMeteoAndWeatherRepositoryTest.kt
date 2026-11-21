package com.buckcast.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.buckcast.data.local.BuckCastDatabase
import com.buckcast.data.remote.OpenMeteoClient
import com.buckcast.data.repository.WeatherRepositoryImpl
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenMeteoAndWeatherRepositoryTest {

    private lateinit var db: BuckCastDatabase
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BuckCastDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `OpenMeteoClient queries pressure_msl with past_days=1, converts to inHg and feet, and preserves Room fallback when offline`() =
        runTest {
            var shouldFail = false
            var capturedQuery = ""

            val mockEngine = MockEngine { request ->
                capturedQuery = request.url.encodedQuery
                if (shouldFail) {
                    throw IOException("Simulated backcountry offline state")
                }
                respond(
                    content = SAMPLE_OPEN_METEO_JSON,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

            var currentEpochMs = 1_795_284_000_000L
            val client = OpenMeteoClient(HttpClient(mockEngine), json)
            val repo = WeatherRepositoryImpl(
                weatherCacheDao = db.weatherCacheDao(),
                openMeteoClient = client,
                json = json,
                clockMs = { currentEpochMs },
            )

            val onlineResult = repo.refreshForecast(41.5868, -93.6250)
            assertTrue(onlineResult.isSuccess)
            assertTrue(capturedQuery.contains("pressure_msl"))
            assertFalse(capturedQuery.contains("surface_pressure"))
            assertTrue(capturedQuery.contains("past_days=1"))
            assertTrue(capturedQuery.contains("forecast_days=7"))

            val cached = repo.observeForecast(41.5868, -93.6250).first()
            assertNotNull(cached)
            assertFalse(cached!!.isStale)
            // 290 meters -> ~951.44 feet
            assertEquals(951.44, cached.elevationFeet, 1.0)
            // Hour index 3 rises from 1015.0 hPa (29.973 inHg) to 1018.4 hPa (30.073 inHg) -> +0.100 inHg trend
            val hour3 = cached.hourly[3]
            assertEquals(30.073, hour3.barometricPressureInHg, 0.01)
            assertEquals(0.100, hour3.pressureTrend3hInHg, 0.01)

            // Go offline and advance clock by 4 hours (> 3h stale threshold)
            shouldFail = true
            currentEpochMs += 4L * 3600L * 1000L
            val offlineResult = repo.refreshForecast(41.5868, -93.6250)
            assertTrue(offlineResult.isFailure)

            // Cached forecast remains available in Room and is tagged stale
            val fallback = repo.observeForecast(41.5868, -93.6250).first()
            assertNotNull(fallback)
            assertTrue(fallback!!.isStale)
            assertEquals(30.073, fallback.hourly[3].barometricPressureInHg, 0.01)
        }

    companion object {
        private const val SAMPLE_OPEN_METEO_JSON = """
        {
          "latitude": 41.5868,
          "longitude": -93.6250,
          "elevation": 290.0,
          "utc_offset_seconds": -21600,
          "timezone": "America/Chicago",
          "hourly": {
            "time": [
              "2026-11-20T21:00",
              "2026-11-20T22:00",
              "2026-11-20T23:00",
              "2026-11-21T00:00"
            ],
            "temperature_2m": [38.0, 36.5, 35.0, 33.8],
            "apparent_temperature": [31.0, 29.5, 28.0, 26.5],
            "pressure_msl": [1015.0, 1016.2, 1017.1, 1018.4],
            "wind_speed_10m": [8.0, 7.5, 6.0, 5.5],
            "wind_gusts_10m": [12.0, 11.0, 9.0, 8.0],
            "wind_direction_10m": [315.0, 320.0, 330.0, 340.0],
            "precipitation": [0.0, 0.0, 0.0, 0.0],
            "cloud_cover": [25.0, 15.0, 10.0, 5.0],
            "weather_code": [1, 1, 0, 0]
          },
          "daily": {
            "time": ["2026-11-20", "2026-11-21"],
            "temperature_2m_max": [44.0, 41.0],
            "temperature_2m_min": [28.0, 24.0],
            "precipitation_sum": [0.0, 0.0],
            "wind_speed_10m_max": [11.0, 9.5],
            "wind_direction_10m_dominant": [320.0, 340.0],
            "weather_code": [1, 0]
          }
        }
        """
    }
}

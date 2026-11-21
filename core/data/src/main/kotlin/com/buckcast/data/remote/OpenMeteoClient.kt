package com.buckcast.data.remote

import com.buckcast.domain.math.AngleMath
import com.buckcast.domain.math.GeoUnits
import com.buckcast.domain.model.CachedWeatherForecast
import com.buckcast.domain.model.DailyWeatherSummary
import com.buckcast.domain.model.HourlyWeatherPoint
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.round

data class OpenMeteoFetchResult(
    val rawPayloadJson: String,
    val response: OpenMeteoResponse,
)

/**
 * Ktor client fetching 7-day + 1-day-history Open-Meteo forecasts using sea-level pressure
 * (`pressure_msl`) and US imperial temperature/wind/precipitation units.
 */
class OpenMeteoClient(
    private val httpClient: HttpClient,
    private val json: Json,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    suspend fun fetchForecast(latitude: Double, longitude: Double): OpenMeteoFetchResult {
        val httpResponse = httpClient.get(baseUrl) {
            parameter("latitude", latitude)
            parameter("longitude", longitude)
            parameter("hourly", HOURLY_VARIABLES)
            parameter("daily", DAILY_VARIABLES)
            parameter("temperature_unit", "fahrenheit")
            parameter("wind_speed_unit", "mph")
            parameter("precipitation_unit", "inch")
            parameter("past_days", 1)
            parameter("forecast_days", 7)
            parameter("timezone", "auto")
        }
        if (!httpResponse.status.isSuccess()) {
            throw IllegalStateException("Open-Meteo HTTP ${httpResponse.status.value}")
        }
        val rawBody = httpResponse.bodyAsText()
        val parsed = json.decodeFromString<OpenMeteoResponse>(rawBody)
        return OpenMeteoFetchResult(rawPayloadJson = rawBody, response = parsed)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.open-meteo.com/v1/forecast"
        const val HOURLY_VARIABLES =
            "temperature_2m,apparent_temperature,pressure_msl,wind_speed_10m,wind_gusts_10m,wind_direction_10m,precipitation,cloud_cover,weather_code"
        const val DAILY_VARIABLES =
            "temperature_2m_max,temperature_2m_min,precipitation_sum,wind_speed_10m_max,wind_direction_10m_dominant,weather_code"
    }
}

/**
 * Maps raw Open-Meteo responses into `:core:domain` models strictly converting metric API fields
 * (`pressure_msl` hPa -> inHg, `elevation` m -> ft) at the repository boundary.
 */
object OpenMeteoMapper {

    fun gridKeyFor(latitude: Double, longitude: Double): String {
        val snappedLat = round(latitude * 20.0) / 20.0
        val snappedLon = round(longitude * 20.0) / 20.0
        return String.format(Locale.US, "%.2f,%.2f", snappedLat, snappedLon)
    }

    fun toDomain(
        gridKey: String,
        response: OpenMeteoResponse,
        fetchedAtEpochMs: Long,
        isStale: Boolean,
    ): CachedWeatherForecast {
        val zoneId = runCatching { ZoneId.of(response.timezone) }
            .getOrElse { ZoneOffset.ofTotalSeconds(response.utcOffsetSeconds) }

        val hourlyDto = response.hourly
        val hourlyCount = hourlyDto.time.size
        val pressuresInHg = List(hourlyCount) { i ->
            val hpa = hourlyDto.pressureMsl.getOrNull(i) ?: 1013.25
            GeoUnits.hpaToInHg(hpa)
        }

        val hourlyPoints = (0 until hourlyCount).map { i ->
            val isoTime = hourlyDto.time[i]
            val epochSec = runCatching {
                LocalDateTime.parse(isoTime).atZone(zoneId).toEpochSecond()
            }.getOrElse { 0L }

            val pressureInHg = pressuresInHg[i]
            val trend3h = if (i >= 3) pressureInHg - pressuresInHg[i - 3] else 0.0
            val windDir = AngleMath.normalizeDegrees(hourlyDto.windDirection10m.getOrNull(i) ?: 0.0)

            HourlyWeatherPoint(
                epochSeconds = epochSec,
                isoLocalTime = isoTime,
                tempDegF = hourlyDto.temperature2m.getOrNull(i) ?: 0.0,
                apparentTempDegF = hourlyDto.apparentTemperature.getOrNull(i)
                    ?: (hourlyDto.temperature2m.getOrNull(i) ?: 0.0),
                barometricPressureInHg = pressureInHg,
                pressureTrend3hInHg = trend3h,
                windSpeedMph = (hourlyDto.windSpeed10m.getOrNull(i) ?: 0.0).coerceAtLeast(0.0),
                windGustMph = hourlyDto.windGusts10m.getOrNull(i)?.coerceAtLeast(0.0),
                windFromDeg = windDir,
                precipInchesPerHr = (hourlyDto.precipitation.getOrNull(i) ?: 0.0).coerceAtLeast(0.0),
                cloudCoverPercent = (hourlyDto.cloudCover.getOrNull(i) ?: 0.0).coerceIn(0.0, 100.0),
                weatherCode = hourlyDto.weatherCode.getOrNull(i) ?: 0,
            )
        }

        val dailyDto = response.daily
        val dailySummaries = dailyDto.time.indices.map { i ->
            DailyWeatherSummary(
                isoDate = dailyDto.time[i],
                tempMaxDegF = dailyDto.temperature2mMax.getOrNull(i) ?: 0.0,
                tempMinDegF = dailyDto.temperature2mMin.getOrNull(i) ?: 0.0,
                precipSumInches = (dailyDto.precipitationSum.getOrNull(i) ?: 0.0).coerceAtLeast(0.0),
                windSpeedMaxMph = (dailyDto.windSpeed10mMax.getOrNull(i) ?: 0.0).coerceAtLeast(0.0),
                dominantWindFromDeg = AngleMath.normalizeDegrees(
                    dailyDto.windDirection10mDominant.getOrNull(i) ?: 0.0,
                ),
                weatherCode = dailyDto.weatherCode.getOrNull(i) ?: 0,
            )
        }

        return CachedWeatherForecast(
            gridKey = gridKey,
            latitude = response.latitude,
            longitude = response.longitude,
            elevationFeet = GeoUnits.metersToFeet(response.elevation),
            timezoneId = response.timezone,
            fetchedAtEpochMs = fetchedAtEpochMs,
            isStale = isStale,
            hourly = hourlyPoints,
            daily = dailySummaries,
        )
    }
}

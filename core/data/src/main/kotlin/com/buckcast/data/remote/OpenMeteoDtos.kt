package com.buckcast.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpenMeteoResponse(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double = 0.0,
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int = 0,
    val timezone: String = "UTC",
    val hourly: OpenMeteoHourlyDto,
    val daily: OpenMeteoDailyDto,
)

@Serializable
data class OpenMeteoHourlyDto(
    val time: List<String>,
    @SerialName("temperature_2m") val temperature2m: List<Double?>,
    @SerialName("apparent_temperature") val apparentTemperature: List<Double?>,
    @SerialName("pressure_msl") val pressureMsl: List<Double?>,
    @SerialName("wind_speed_10m") val windSpeed10m: List<Double?>,
    @SerialName("wind_gusts_10m") val windGusts10m: List<Double?> = emptyList(),
    @SerialName("wind_direction_10m") val windDirection10m: List<Double?>,
    val precipitation: List<Double?> = emptyList(),
    @SerialName("cloud_cover") val cloudCover: List<Double?> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
)

@Serializable
data class OpenMeteoDailyDto(
    val time: List<String>,
    @SerialName("temperature_2m_max") val temperature2mMax: List<Double?>,
    @SerialName("temperature_2m_min") val temperature2mMin: List<Double?>,
    @SerialName("precipitation_sum") val precipitationSum: List<Double?> = emptyList(),
    @SerialName("wind_speed_10m_max") val windSpeed10mMax: List<Double?>,
    @SerialName("wind_direction_10m_dominant") val windDirection10mDominant: List<Double?>,
    @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
)

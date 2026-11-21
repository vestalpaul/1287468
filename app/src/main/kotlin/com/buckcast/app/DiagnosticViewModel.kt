package com.buckcast.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buckcast.domain.model.CachedWeatherForecast
import com.buckcast.domain.repository.SettingsRepository
import com.buckcast.domain.repository.WeatherRepository
import com.buckcast.sensors.BarometerReading
import com.buckcast.sensors.BarometerSensor
import com.buckcast.sensors.CompassAccuracy
import com.buckcast.sensors.CompassPosture
import com.buckcast.sensors.CompassReading
import com.buckcast.sensors.CompassSensorSource
import com.buckcast.sensors.LocationPowerMode
import com.buckcast.sensors.LocationProvider
import com.buckcast.sensors.LocationState
import com.buckcast.sensors.RotationVectorCompass
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiagnosticUiState(
    val compass: CompassReading = CompassReading(
        trueHeadingDeg = 0.0,
        magneticHeadingDeg = 0.0,
        declinationDeg = 0.0,
        pitchDeg = 0.0,
        posture = CompassPosture.FLAT_MAP,
        accuracy = CompassAccuracy.MEDIUM,
        sensorSource = CompassSensorSource.UNAVAILABLE,
        isSensorAvailable = false,
        timestampEpochMs = 0L,
    ),
    val location: LocationState = LocationState.Searching,
    val powerMode: LocationPowerMode = LocationPowerMode.HIGH_ACCURACY,
    val barometer: BarometerReading = BarometerReading(
        stationPressureInHg = null,
        seaLevelPressureInHg = null,
        pressureTrendInHg = null,
        isSeaLevelCorrected = false,
        isAvailable = false,
        timestampEpochMs = 0L,
    ),
    val cachedWeather: CachedWeatherForecast? = null,
    val isRefreshingWeather: Boolean = false,
    val lastRefreshError: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DiagnosticViewModel @Inject constructor(
    private val compass: RotationVectorCompass,
    private val locationProvider: LocationProvider,
    private val barometerSensor: BarometerSensor,
    private val weatherRepository: WeatherRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val powerModeFlow = MutableStateFlow(LocationPowerMode.HIGH_ACCURACY)
    private val refreshTrigger = MutableStateFlow(0)
    private val refreshingFlow = MutableStateFlow(false)
    private val errorFlow = MutableStateFlow<String?>(null)

    private var lastSavedLat: Double? = null
    private var lastSavedLon: Double? = null

    private val locationFlow = combine(powerModeFlow, refreshTrigger) { mode, _ -> mode }
        .flatMapLatest { mode -> locationProvider.observeLocation(mode) }
        .onEach { state ->
            if (state is LocationState.Fix) {
                compass.updateLocation(state.point)
                barometerSensor.updateAltitudeFeet(state.point.elevationFeet)
                persistLocationIfMoved(state.point.latitude, state.point.longitude)
            }
        }

    val uiState: StateFlow<DiagnosticUiState> = combine(
        compass.observeHeading(),
        locationFlow,
        barometerSensor.observePressure(),
        weatherRepository.observeLatestCachedForecast(),
        combine(powerModeFlow, refreshingFlow, errorFlow) { mode, refreshing, error ->
            Triple(mode, refreshing, error)
        },
    ) { heading, location, baro, weather, meta ->
        DiagnosticUiState(
            compass = heading,
            location = location,
            powerMode = meta.first,
            barometer = baro,
            cachedWeather = weather,
            isRefreshingWeather = meta.second,
            lastRefreshError = meta.third,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = DiagnosticUiState(),
    )

    fun toggleLocationPowerMode() {
        powerModeFlow.value = when (powerModeFlow.value) {
            LocationPowerMode.HIGH_ACCURACY -> LocationPowerMode.BATTERY_SAVER
            LocationPowerMode.BATTERY_SAVER -> LocationPowerMode.HIGH_ACCURACY
        }
    }

    fun onLocationPermissionGranted() {
        refreshTrigger.value += 1
    }

    fun refreshWeatherNow() {
        viewModelScope.launch {
            refreshingFlow.value = true
            errorFlow.value = null
            val fix = uiState.value.location as? LocationState.Fix
            val settings = settingsRepository.getSettings()
            val lat = fix?.point?.latitude ?: settings.lastKnownLat ?: DEFAULT_MIDWEST_LAT
            val lon = fix?.point?.longitude ?: settings.lastKnownLon ?: DEFAULT_MIDWEST_LON

            settingsRepository.updateLastKnownLocation(lat, lon)
            val result = weatherRepository.refreshForecast(lat, lon)
            if (result.isFailure) {
                errorFlow.value = result.exceptionOrNull()?.message ?: "Offline — serving cached data"
            }
            refreshingFlow.value = false
        }
    }

    private fun persistLocationIfMoved(lat: Double, lon: Double) {
        val prevLat = lastSavedLat
        val prevLon = lastSavedLon
        if (prevLat == null || prevLon == null ||
            kotlin.math.abs(lat - prevLat) > 0.01 || kotlin.math.abs(lon - prevLon) > 0.01
        ) {
            lastSavedLat = lat
            lastSavedLon = lon
            viewModelScope.launch {
                settingsRepository.updateLastKnownLocation(lat, lon)
            }
        }
    }

    companion object {
        private const val DEFAULT_MIDWEST_LAT = 41.5868
        private const val DEFAULT_MIDWEST_LON = -93.6250
    }
}

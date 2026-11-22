package com.buckcast.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buckcast.domain.math.CompassDirection
import com.buckcast.sensors.CompassAccuracy
import com.buckcast.sensors.CompassFusionMath
import com.buckcast.sensors.LocationState
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = BuckCastDiagnosticColors) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing,
                ) { innerPadding ->
                    Surface(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        HardwareDiagnosticScreen()
                    }
                }
            }
        }
    }
}

private val BuckCastDiagnosticColors = darkColorScheme(
    primary = Color(0xFF8BC34A),
    secondary = Color(0xFFFFB74D),
    background = Color(0xFF0F1410),
    surface = Color(0xFF182019),
    error = Color(0xFFEF5350),
)

@Composable
private fun HardwareDiagnosticScreen(
    viewModel: DiagnosticViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            viewModel.onLocationPermissionGranted()
        }
    }

    LaunchedEffect(Unit) {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "BuckCast Phase 2 — Sensor & Cache Diagnostics",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        CompassDiagnosticCard(state = state)

        LocationAndBarometerCard(
            state = state,
            onTogglePowerMode = viewModel::toggleLocationPowerMode,
            onRequestPermission = {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            },
        )

        WeatherCacheDiagnosticCard(
            state = state,
            onRefreshNow = viewModel::refreshWeatherNow,
        )
    }
}

@Composable
private fun CompassDiagnosticCard(state: DiagnosticUiState) {
    val compass = state.compass
    val cardinal = CompassDirection.fromBearing(compass.trueHeadingDeg).abbreviation
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("True-North Compass Fusion", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (!compass.isSensorAvailable) {
                Text("Rotation vector sensors unavailable on this device.", color = MaterialTheme.colorScheme.error)
            } else {
                Text(
                    text = "${CompassFusionMath.formatHeadingTenths(compass.trueHeadingDeg)} $cardinal (True)",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "Magnetic: %.1f° | Declination: %+.2f° | Pitch: %.1f°",
                        compass.magneticHeadingDeg,
                        compass.declinationDeg,
                        compass.pitchDeg,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Posture: ${compass.posture.name} | Source: ${compass.sensorSource.name}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (compass.accuracy == CompassAccuracy.CALIBRATION_NEEDED) {
                    Text(
                        text = "CALIBRATION NEEDED — Move device in a figure-8 pattern.",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun LocationAndBarometerCard(
    state: DiagnosticUiState,
    onTogglePowerMode: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("GNSS & Barometer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            when (val loc = state.location) {
                LocationState.Searching -> Text("Searching for GNSS fix (${state.powerMode.name})...")
                LocationState.PermissionRequired -> {
                    Text("Location permission required for GNSS & magnetic declination.", color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRequestPermission) {
                        Text("Grant Location")
                    }
                }
                is LocationState.Fix -> {
                    Text(
                        text = String.format(
                            Locale.US,
                            "Lat %.5f, Lon %.5f | Elev: %s ft | ±%.0f ft",
                            loc.point.latitude,
                            loc.point.longitude,
                            loc.point.elevationFeet?.let { String.format(Locale.US, "%.0f", it) } ?: "--",
                            loc.accuracyFeet,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            OutlinedButton(onClick = onTogglePowerMode) {
                Text("GPS Mode: ${state.powerMode.name}")
            }

            Spacer(Modifier.height(4.dp))
            val baro = state.barometer
            if (!baro.isAvailable) {
                Text("Hardware Barometer: Not present on device", style = MaterialTheme.typography.bodySmall)
            } else {
                val mslLabel = baro.seaLevelPressureInHg?.let { String.format(Locale.US, "%.2f inHg", it) } ?: "--"
                val stnLabel = baro.stationPressureInHg?.let { String.format(Locale.US, "%.2f inHg", it) } ?: "--"
                val trendLabel = baro.pressureTrendInHg?.let { String.format(Locale.US, "%+.3f inHg", it) } ?: "Collecting..."
                Text(
                    text = "Barometer: $mslLabel (${if (baro.isSeaLevelCorrected) "MSL" else "Station"}) | Raw: $stnLabel | Trend: $trendLabel",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun WeatherCacheDiagnosticCard(
    state: DiagnosticUiState,
    onRefreshNow: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Offline Open-Meteo Cache", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val statusLabel = when {
                    state.cachedWeather == null -> "EMPTY"
                    state.cachedWeather.isStale -> "STALE"
                    else -> "FRESH"
                }
                Text(statusLabel, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
            }

            val cached = state.cachedWeather
            if (cached == null) {
                Text("No forecast cached yet. Tap Refresh to populate Room cache.")
            } else {
                val fetchedStr = DateTimeFormatter.ofPattern("MMM d HH:mm")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(cached.fetchedAtEpochMs))
                val nowSec = Instant.now().epochSecond
                val current = cached.hourly.minByOrNull { kotlin.math.abs(it.epochSeconds - nowSec) }
                Text("Cell: ${cached.gridKey} | Elev: ${cached.elevationFeet.toInt()} ft | Cached: $fetchedStr")
                if (current != null) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "%.0f°F | Wind %.0f mph @ %.0f° | %.2f inHg (%+.2f/3h)",
                            current.tempDegF,
                            current.windSpeedMph,
                            current.windFromDeg,
                            current.barometricPressureInHg,
                            current.pressureTrend3hInHg,
                        ),
                    )
                }
            }

            if (state.lastRefreshError != null) {
                Text("Offline fallback active: ${state.lastRefreshError}", color = MaterialTheme.colorScheme.error)
            }

            Button(onClick = onRefreshNow, enabled = !state.isRefreshingWeather) {
                Text(if (state.isRefreshingWeather) "Refreshing..." else "Refresh Weather")
            }
        }
    }
}

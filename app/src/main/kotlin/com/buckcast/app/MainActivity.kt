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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buckcast.app.ui.DropTineTheme
import com.buckcast.domain.math.GeoPoint
import com.buckcast.feature.forecast.ForecastScreen
import com.buckcast.feature.forecast.ForecastViewModel
import com.buckcast.feature.map.MapScreen
import com.buckcast.feature.map.MapViewModel
import com.buckcast.feature.settings.AlphaPaywallSheet
import com.buckcast.feature.settings.SettingsScreen
import com.buckcast.feature.settings.SettingsViewModel
import com.buckcast.feature.stands.StandDetailSheet
import com.buckcast.feature.stands.StandEditorSheet
import com.buckcast.feature.stands.StandsViewModel
import dagger.hilt.android.AndroidEntryPoint

private enum class DropTineTab(
    val label: String,
    val icon: ImageVector,
) {
    HOME(label = "Home", icon = Icons.Rounded.Home),
    MAP(label = "Map", icon = Icons.Rounded.Map),
    TRACK(label = "Track", icon = Icons.Rounded.Route),
    SETTINGS(label = "Settings", icon = Icons.Rounded.Settings),
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DropTineAppShell()
        }
    }
}

@Composable
private fun DropTineAppShell() {
    val context = LocalContext.current
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val forecastViewModel: ForecastViewModel = hiltViewModel()
    val mapViewModel: MapViewModel = hiltViewModel()
    val standsViewModel: StandsViewModel = hiltViewModel()

    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val standsState by standsViewModel.uiState.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableStateOf(DropTineTab.HOME) }
    var showPaywall by rememberSaveable { mutableStateOf(false) }
    var selectedStandId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingStandLat by rememberSaveable { mutableStateOf<Double?>(null) }
    var pendingStandLon by rememberSaveable { mutableStateOf<Double?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        forecastViewModel.refreshNow()
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

    DropTineTheme(preset = settingsState.effectiveThemePreset) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                NavigationBar {
                    DropTineTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            Surface(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    DropTineTab.HOME -> ForecastScreen(
                        viewModel = forecastViewModel,
                        contentPadding = innerPadding,
                        onOpenPaywall = { showPaywall = true },
                        onNavigateToMap = { selectedTab = DropTineTab.MAP },
                    )
                    DropTineTab.MAP -> MapScreen(
                        viewModel = mapViewModel,
                        contentPadding = innerPadding,
                        onStandSelected = { id -> selectedStandId = id },
                        onRequestAddStand = { point ->
                            if (!standsState.canCreateAnotherStand) {
                                showPaywall = true
                            } else {
                                pendingStandLat = point.latitude
                                pendingStandLon = point.longitude
                            }
                        },
                        onOpenPaywall = { showPaywall = true },
                    )
                    DropTineTab.TRACK -> TrackPlaceholderScreen(contentPadding = innerPadding)
                    DropTineTab.SETTINGS -> SettingsScreen(
                        viewModel = settingsViewModel,
                        contentPadding = innerPadding,
                        onOpenPaywall = { showPaywall = true },
                    )
                }
            }
        }

        val pendingLat = pendingStandLat
        val pendingLon = pendingStandLon
        if (pendingLat != null && pendingLon != null) {
            StandEditorSheet(
                initialLocation = GeoPoint(pendingLat, pendingLon),
                existingStand = null,
                isAlpha = settingsState.entitlement.isAlpha,
                onSave = { name, location, standType, heightFeet, terrainPos, windDirs, aspect, grade, notes ->
                    val saved = standsViewModel.saveStand(
                        existingId = null,
                        name = name,
                        location = location,
                        standType = standType,
                        standHeightFeet = heightFeet,
                        terrainPosition = terrainPos,
                        selectedWindDirs = windDirs,
                        slopeAspectDeg = aspect,
                        slopeGradePercent = grade,
                        notes = notes,
                    )
                    pendingStandLat = null
                    pendingStandLon = null
                    if (!saved) showPaywall = true
                },
                onOpenPaywall = { showPaywall = true },
                onDismiss = {
                    pendingStandLat = null
                    pendingStandLon = null
                },
            )
        }

        val activeDetail = selectedStandId?.let { targetId ->
            standsState.stands.firstOrNull { it.stand.id == targetId }
        }
        if (activeDetail != null) {
            StandDetailSheet(
                detail = activeDetail,
                entitlement = settingsState.entitlement,
                onStartSit = {
                    if (!standsViewModel.startSit(activeDetail.stand.id)) {
                        showPaywall = true
                    }
                },
                onEndSit = { winded ->
                    standsViewModel.endSit(activeDetail.stand.id, windedDeer = winded)
                },
                onDrawRoute = { stand, routeType ->
                    selectedStandId = null
                    if (mapViewModel.startRouteDrawing(stand.id, stand.name, routeType)) {
                        selectedTab = DropTineTab.MAP
                    } else {
                        showPaywall = true
                    }
                },
                onProjectScent = { stand ->
                    selectedStandId = null
                    if (mapViewModel.projectStandScent(stand.id)) {
                        selectedTab = DropTineTab.MAP
                    } else {
                        showPaywall = true
                    }
                },
                onDeleteStand = {
                    standsViewModel.deleteStand(activeDetail.stand.id)
                    selectedStandId = null
                },
                onOpenPaywall = { showPaywall = true },
                onDismiss = { selectedStandId = null },
            )
        }

        if (showPaywall) {
            AlphaPaywallSheet(
                entitlement = settingsState.entitlement,
                statusMessage = settingsState.statusMessage,
                isBusy = settingsState.isBusy,
                onPurchaseMonthly = { settingsViewModel.purchaseAlpha(isAnnual = false) },
                onPurchaseAnnual = { settingsViewModel.purchaseAlpha(isAnnual = true) },
                onRestorePurchases = settingsViewModel::restorePurchases,
                onDismiss = { showPaywall = false },
            )
        }
    }
}

@Composable
private fun TrackPlaceholderScreen(contentPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Route,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        text = "Blood-Trail & Field Log",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = "Coming in Phase 4",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Offline GNSS blood-trail breadcrumbs with ShotSite/LastBlood pins, shot-recovery countdowns with ambient meat-spoilage advisories, and whitetail sightings stamped with automatic weather & rut phase snapshots.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

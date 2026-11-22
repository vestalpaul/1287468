package com.buckcast.feature.map

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buckcast.domain.math.CompassDirection
import com.buckcast.domain.math.GeoPoint
import com.buckcast.domain.wind.WindAlignmentBadge
import com.buckcast.sensors.CompassAccuracy
import com.buckcast.sensors.CompassPosture
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.Locale

private const val SRC_ROUTES = "droptine-routes-src"
private const val SRC_RANGE_RINGS = "droptine-rings-src"
private const val SRC_SCENT_CONE = "droptine-scent-src"
private const val SRC_WIND_ARROW = "droptine-wind-src"
private const val SRC_THERMAL_ARROW = "droptine-thermal-src"
private const val SRC_WAYPOINTS = "droptine-waypoints-src"
private const val SRC_STANDS = "droptine-stands-src"
private const val SRC_USER_FOV = "droptine-user-fov-src"

private const val LAYER_ROUTES = "droptine-routes-layer"
private const val LAYER_DRAFT_VERTICES = "droptine-draft-vertices-layer"
private const val LAYER_RANGE_RINGS = "droptine-rings-layer"
private const val LAYER_RANGE_RING_LABELS = "droptine-rings-labels-layer"
private const val LAYER_SCENT_FILL = "droptine-scent-fill"
private const val LAYER_SCENT_OUTLINE = "droptine-scent-outline"
private const val LAYER_WIND_ARROW = "droptine-wind-layer"
private const val LAYER_THERMAL_ARROW = "droptine-thermal-layer"
private const val LAYER_WAYPOINTS = "droptine-waypoints-layer"
private const val LAYER_STANDS = "droptine-stands-layer"
private const val LAYER_STAND_LABELS = "droptine-stands-labels-layer"
private const val LAYER_USER_FOV = "droptine-user-fov-layer"
private const val LAYER_USER_PUCK = "droptine-user-puck-layer"

@Composable
fun MapScreen(
    viewModel: MapViewModel,
    contentPadding: PaddingValues,
    onStandSelected: (standId: String) -> Unit,
    onRequestAddStand: (location: GeoPoint) -> Unit,
    onOpenPaywall: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val latestState by rememberUpdatedState(state)
    val latestOnStandSelected by rememberUpdatedState(onStandSelected)
    val latestOnRequestAddStand by rememberUpdatedState(onRequestAddStand)

    val mapView = remember {
        MapView(context).apply { onCreate(null) }
    }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReadyVersion by remember { mutableIntStateOf(0) }
    var centeredOnGpsOnce by remember { mutableStateOf(false) }

    val offlinePackManager = remember { OfflineMapPackManager(context) }
    val packState by offlinePackManager.state.collectAsStateWithLifecycle()
    var showOfflineSheet by remember { mutableStateOf(false) }
    var capturedBounds by remember { mutableStateOf<ViewportBoundingBox?>(null) }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                mapView.onPause()
                mapView.onStop()
            }
            if (!mapView.isDestroyed) {
                mapView.onDestroy()
            }
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isRotateGesturesEnabled = true
            map.uiSettings.isAttributionEnabled = true
            map.uiSettings.isLogoEnabled = false

            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(latestState.camera.latitude, latestState.camera.longitude))
                .zoom(latestState.camera.zoom)
                .build()
            centeredOnGpsOnce = latestState.camera.hasCenteredOnGps

            map.addOnCameraIdleListener {
                val pos = map.cameraPosition
                pos.target?.let { target ->
                    viewModel.updateCamera(
                        latitude = target.latitude,
                        longitude = target.longitude,
                        zoom = pos.zoom,
                        hasCenteredOnGps = latestState.camera.hasCenteredOnGps,
                    )
                }
            }

            map.addOnMapClickListener { latLng ->
                val cur = latestState
                if (cur.routeDrawing == null && !cur.scoutModeEnabled) {
                    val screenPt = map.projection.toScreenLocation(latLng)
                    val hits = map.queryRenderedFeatures(screenPt, LAYER_STANDS)
                    val standId = hits.firstOrNull()?.getStringProperty("id")
                    if (!standId.isNullOrBlank()) {
                        latestOnStandSelected(standId)
                        return@addOnMapClickListener true
                    }
                }
                viewModel.onMapTap(GeoPoint(latLng.latitude, latLng.longitude))
                true
            }

            map.addOnMapLongClickListener { latLng ->
                if (latestState.routeDrawing == null) {
                    latestOnRequestAddStand(GeoPoint(latLng.latitude, latLng.longitude))
                    true
                } else {
                    false
                }
            }

            mapLibreMap = map
        }
    }

    // Center camera once automatically when first GPS fix arrives, or on programmatic jumps.
    LaunchedEffect(mapLibreMap, state.camera.hasCenteredOnGps, state.camera.jumpNonce) {
        val map = mapLibreMap ?: return@LaunchedEffect
        val shouldJumpToGps = state.camera.hasCenteredOnGps && !centeredOnGpsOnce
        if (shouldJumpToGps || state.camera.jumpNonce > 0L) {
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(state.camera.latitude, state.camera.longitude),
                    state.camera.zoom,
                ),
            )
            if (shouldJumpToGps) centeredOnGpsOnce = true
        }
    }

    val styleKey = Triple(state.baseLayer, state.showHillshade, state.mapTilerApiKey)
    LaunchedEffect(mapLibreMap, styleKey) {
        val map = mapLibreMap ?: return@LaunchedEffect
        val json = MapTileConfig.buildStyleJson(
            baseLayer = state.baseLayer,
            showHillshade = state.showHillshade,
            mapTilerApiKey = state.mapTilerApiKey,
        )
        map.setStyle(Style.Builder().fromJson(json)) { style ->
            ensureTacticalLayers(style)
            applyTacticalGeoJson(style, latestState)
            styleReadyVersion++
        }
    }

    LaunchedEffect(mapLibreMap, styleReadyVersion, state) {
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect
        applyTacticalGeoJson(style, state)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
        )

        // Top HUD Stack (Base layers + feature toggles + Calibration/Thermal banners)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(
                    top = contentPadding.calculateTopPadding() + 8.dp,
                    start = 12.dp,
                    end = 12.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                MapBaseLayer.entries.forEachIndexed { idx, layer ->
                    val locked = layer.isAlphaOnly && !state.canUseAlphaLayers
                    SegmentedButton(
                        selected = state.baseLayer == layer,
                        onClick = {
                            if (!viewModel.selectBaseLayer(layer)) onOpenPaywall()
                        },
                        shape = SegmentedButtonDefaults.itemShape(idx, MapBaseLayer.entries.size),
                        icon = {
                            if (locked) {
                                Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        },
                    ) {
                        Text(layer.label, maxLines = 1)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.showHillshade,
                    onClick = { if (!viewModel.toggleHillshade()) onOpenPaywall() },
                    label = { Text("Hillshade") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (state.canUseAlphaLayers) Icons.Rounded.Layers else Icons.Rounded.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
                FilterChip(
                    selected = state.showRangeRings,
                    onClick = { if (!viewModel.toggleRangeRings()) onOpenPaywall() },
                    label = { Text("Rings") },
                    leadingIcon = if (!state.canUseScentCones) {
                        { Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null,
                )
                FilterChip(
                    selected = state.scoutModeEnabled,
                    onClick = { if (!viewModel.toggleScoutMode()) onOpenPaywall() },
                    label = { Text("Scout") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (state.canUseScentCones) Icons.Rounded.Explore else Icons.Rounded.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
                AssistChip(
                    onClick = {
                        if (!state.canUseOfflinePacks) {
                            onOpenPaywall()
                        } else {
                            val bounds = mapLibreMap?.projection?.visibleRegion?.latLngBounds
                            capturedBounds = if (bounds != null) {
                                ViewportBoundingBox(
                                    northLat = bounds.latitudeNorth,
                                    southLat = bounds.latitudeSouth,
                                    eastLon = bounds.longitudeEast,
                                    westLon = bounds.longitudeWest,
                                )
                            } else {
                                ViewportBoundingBox(
                                    northLat = state.camera.latitude + 0.04,
                                    southLat = state.camera.latitude - 0.04,
                                    eastLon = state.camera.longitude + 0.05,
                                    westLon = state.camera.longitude - 0.05,
                                )
                            }
                            offlinePackManager.refreshPacks()
                            showOfflineSheet = true
                        }
                    },
                    label = { Text("Offline") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (state.canUseOfflinePacks) Icons.Rounded.DownloadForOffline else Icons.Rounded.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }

            if (state.compassReading?.accuracy == CompassAccuracy.CALIBRATION_NEEDED) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "Compass calibration needed — move phone in a gentle figure-8.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            state.thermalDrift?.let { thermal ->
                if (thermal.isLeewardRotorRisk || thermal.isTransitionSwirlRisk) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = if (thermal.isLeewardRotorRisk) {
                                "Leeward Rotor Turbulence Risk (${thermal.regime.displayName})"
                            } else {
                                "Thermal Reversal Swirl Window (${thermal.regime.displayName})"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        // Bottom HUD: Route drawing bar OR Compass / Wind Alignment / Scout Readout + FABs
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(
                    bottom = contentPadding.calculateBottomPadding() + 12.dp,
                    start = 12.dp,
                    end = 12.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.statusMessage?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::clearMessage) {
                            Text("OK")
                        }
                    }
                }
            }

            state.scoutResult?.let { scout ->
                ElevatedCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Scout Scent Projection",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            val distText = scout.distanceYards?.let { String.format(Locale.US, "%.0f yd", it) } ?: "Tap point"
                            val bearText = scout.bearingFromUserDeg?.let { CompassFusionMathLabel(it) } ?: ""
                            Text(
                                text = "$distText $bearText · Scent blows ${CompassDirection.fromBearing(scout.scentToDeg).abbreviation} @ ${scout.windSpeedMph.toInt()} mph",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { viewModel.toggleScoutMode() }) {
                            Text("Exit")
                        }
                    }
                }
            }

            state.routeDrawing?.let { drawing ->
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Drawing ${drawing.routeType.name} Route · ${drawing.standName} (${drawing.points.size} pts)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = viewModel::undoLastRoutePoint,
                                enabled = drawing.points.isNotEmpty(),
                            ) {
                                Icon(Icons.Rounded.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("Undo", modifier = Modifier.padding(start = 4.dp))
                            }
                            OutlinedButton(onClick = viewModel::cancelRouteDrawing) {
                                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("Cancel", modifier = Modifier.padding(start = 4.dp))
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            Button(
                                onClick = viewModel::finishRouteDrawing,
                                enabled = drawing.points.size >= 2,
                            ) {
                                Text("Save Route")
                            }
                        }
                    }
                }
            } ?: Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                ElevatedCard(modifier = Modifier.weight(1f, fill = false)) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = state.formattedHeading,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            val postureLabel = when (state.compassReading?.posture) {
                                CompassPosture.UPRIGHT_AIMING -> "Aiming"
                                else -> "Flat"
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.extraSmall,
                            ) {
                                Text(
                                    text = postureLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        val windLabel = CompassDirection.fromBearing(state.windFromDeg).abbreviation
                        Text(
                            text = "Wind $windLabel @ ${state.windSpeedMph.toInt()} mph",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.windAlignment?.let { badge ->
                            val badgeText = when (badge) {
                                WindAlignmentBadge.WIND_IN_FACE -> "IN YOUR FACE"
                                WindAlignmentBadge.CROSSWIND -> "CROSSWIND"
                                WindAlignmentBadge.WIND_AT_BACK -> "AT YOUR BACK"
                            }
                            val badgeColor = when (badge) {
                                WindAlignmentBadge.WIND_IN_FACE -> MaterialTheme.colorScheme.primary
                                WindAlignmentBadge.CROSSWIND -> MaterialTheme.colorScheme.tertiary
                                WindAlignmentBadge.WIND_AT_BACK -> MaterialTheme.colorScheme.error
                            }
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelMedium,
                                color = badgeColor,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            val loc = state.userLocation
                            if (loc != null) {
                                mapLibreMap?.animateCamera(
                                    CameraUpdateFactory.newLatLngZoom(
                                        LatLng(loc.latitude, loc.longitude),
                                        maxOf(state.camera.zoom, 15.5),
                                    ),
                                )
                                viewModel.recenterOnUserLocation()
                            }
                        },
                    ) {
                        Icon(Icons.Rounded.MyLocation, contentDescription = "Recenter on GPS")
                    }

                    FloatingActionButton(
                        onClick = {
                            val target = state.userLocation ?: GeoPoint(
                                latitude = state.camera.latitude,
                                longitude = state.camera.longitude,
                            )
                            onRequestAddStand(target)
                        },
                    ) {
                        Icon(Icons.Rounded.AddLocationAlt, contentDescription = "Tag Stand")
                    }
                }
            }
        }
    }

    if (showOfflineSheet) {
        OfflinePackSheet(
            viewportBox = capturedBounds,
            baseLayer = state.baseLayer,
            showHillshade = state.showHillshade,
            packState = packState,
            onDownloadViewport = { name ->
                val box = capturedBounds ?: return@OfflinePackSheet
                val styleJson = MapTileConfig.buildStyleJson(
                    baseLayer = state.baseLayer,
                    showHillshade = state.showHillshade,
                    mapTilerApiKey = state.mapTilerApiKey,
                )
                val key = "${state.baseLayer.name.lowercase(Locale.US)}_${if (state.showHillshade) "hill" else "base"}"
                offlinePackManager.startDownload(
                    packName = name,
                    northLat = box.northLat,
                    southLat = box.southLat,
                    eastLon = box.eastLon,
                    westLon = box.westLon,
                    styleJson = styleJson,
                    styleFileKey = key,
                )
            },
            onDeletePack = offlinePackManager::deletePack,
            onDismiss = { showOfflineSheet = false },
        )
    }
}

private fun CompassFusionMathLabel(deg: Double): String =
    String.format(Locale.US, "@ %.1f°", deg)

private fun ensureTacticalLayers(style: Style) {
    val empty = TacticalGeoJsonBuilder.emptyFeatureCollection()
    listOf(
        SRC_ROUTES,
        SRC_RANGE_RINGS,
        SRC_SCENT_CONE,
        SRC_WIND_ARROW,
        SRC_THERMAL_ARROW,
        SRC_WAYPOINTS,
        SRC_STANDS,
        SRC_USER_FOV,
    ).forEach { id ->
        if (style.getSource(id) == null) {
            style.addSource(GeoJsonSource(id, empty))
        }
    }

    if (style.getLayer(LAYER_ROUTES) == null) {
        style.addLayer(
            LineLayer(LAYER_ROUTES, SRC_ROUTES).withProperties(
                PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.lineWidth(3.5f),
            ),
        )
    }
    if (style.getLayer(LAYER_DRAFT_VERTICES) == null) {
        style.addLayer(
            CircleLayer(LAYER_DRAFT_VERTICES, SRC_ROUTES)
                .withFilter(Expression.eq(Expression.get("kind"), "draft_vertex"))
                .withProperties(
                    PropertyFactory.circleColor("#FFFFFF"),
                    PropertyFactory.circleRadius(5f),
                    PropertyFactory.circleStrokeColor("#4FC3F7"),
                    PropertyFactory.circleStrokeWidth(2f),
                ),
        )
    }
    if (style.getLayer(LAYER_RANGE_RINGS) == null) {
        style.addLayer(
            LineLayer(LAYER_RANGE_RINGS, SRC_RANGE_RINGS)
                .withFilter(Expression.eq(Expression.get("kind"), "ring"))
                .withProperties(
                    PropertyFactory.lineColor("#E0E0E0"),
                    PropertyFactory.lineOpacity(0.6f),
                    PropertyFactory.lineWidth(1.4f),
                    PropertyFactory.lineDasharray(arrayOf(3f, 2f)),
                ),
        )
    }
    if (style.getLayer(LAYER_RANGE_RING_LABELS) == null) {
        style.addLayer(
            SymbolLayer(LAYER_RANGE_RING_LABELS, SRC_RANGE_RINGS)
                .withFilter(Expression.eq(Expression.get("kind"), "ring_label"))
                .withProperties(
                    PropertyFactory.textField(Expression.get("label")),
                    PropertyFactory.textFont(arrayOf("Open Sans Regular")),
                    PropertyFactory.textSize(11f),
                    PropertyFactory.textColor("#FFFFFF"),
                    PropertyFactory.textHaloColor("#000000"),
                    PropertyFactory.textHaloWidth(1.5f),
                    PropertyFactory.textAllowOverlap(true),
                ),
        )
    }
    if (style.getLayer(LAYER_SCENT_FILL) == null) {
        style.addLayer(
            FillLayer(LAYER_SCENT_FILL, SRC_SCENT_CONE).withProperties(
                PropertyFactory.fillColor("#FF7043"),
                PropertyFactory.fillOpacity(0.28f),
            ),
        )
    }
    if (style.getLayer(LAYER_SCENT_OUTLINE) == null) {
        style.addLayer(
            LineLayer(LAYER_SCENT_OUTLINE, SRC_SCENT_CONE).withProperties(
                PropertyFactory.lineColor("#FF7043"),
                PropertyFactory.lineWidth(1.8f),
            ),
        )
    }
    if (style.getLayer(LAYER_WIND_ARROW) == null) {
        style.addLayer(
            LineLayer(LAYER_WIND_ARROW, SRC_WIND_ARROW).withProperties(
                PropertyFactory.lineColor("#29B6F6"),
                PropertyFactory.lineWidth(3.0f),
            ),
        )
    }
    if (style.getLayer(LAYER_THERMAL_ARROW) == null) {
        style.addLayer(
            LineLayer(LAYER_THERMAL_ARROW, SRC_THERMAL_ARROW).withProperties(
                PropertyFactory.lineColor("#FFA726"),
                PropertyFactory.lineWidth(2.6f),
                PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)),
            ),
        )
    }
    if (style.getLayer(LAYER_WAYPOINTS) == null) {
        style.addLayer(
            CircleLayer(LAYER_WAYPOINTS, SRC_WAYPOINTS).withProperties(
                PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.circleRadius(6.0f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(1.5f),
            ),
        )
    }
    if (style.getLayer(LAYER_STANDS) == null) {
        style.addLayer(
            CircleLayer(LAYER_STANDS, SRC_STANDS).withProperties(
                PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.circleRadius(9.5f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2.2f),
            ),
        )
    }
    if (style.getLayer(LAYER_STAND_LABELS) == null) {
        style.addLayer(
            SymbolLayer(LAYER_STAND_LABELS, SRC_STANDS).withProperties(
                PropertyFactory.textField(Expression.get("name")),
                PropertyFactory.textFont(arrayOf("Open Sans Regular")),
                PropertyFactory.textSize(12f),
                PropertyFactory.textOffset(arrayOf(0f, 1.5f)),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textColor("#FFFFFF"),
                PropertyFactory.textHaloColor("#000000"),
                PropertyFactory.textHaloWidth(1.5f),
                PropertyFactory.textAllowOverlap(true),
            ),
        )
    }
    if (style.getLayer(LAYER_USER_FOV) == null) {
        style.addLayer(
            FillLayer(LAYER_USER_FOV, SRC_USER_FOV)
                .withFilter(Expression.eq(Expression.get("kind"), "fov"))
                .withProperties(
                    PropertyFactory.fillColor("#4FC3F7"),
                    PropertyFactory.fillOpacity(0.24f),
                ),
        )
    }
    if (style.getLayer(LAYER_USER_PUCK) == null) {
        style.addLayer(
            CircleLayer(LAYER_USER_PUCK, SRC_USER_FOV)
                .withFilter(Expression.eq(Expression.get("kind"), "puck"))
                .withProperties(
                    PropertyFactory.circleColor("#03A9F4"),
                    PropertyFactory.circleRadius(7.0f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeWidth(2.5f),
                ),
        )
    }
}

private fun applyTacticalGeoJson(style: Style, state: MapUiState) {
    style.getSourceAs<GeoJsonSource>(SRC_ROUTES)?.setGeoJson(
        TacticalGeoJsonBuilder.buildRoutesGeoJson(
            savedRoutes = state.routes,
            draftPoints = state.routeDrawing?.points.orEmpty(),
            draftRouteType = state.routeDrawing?.routeType,
        ),
    )

    val ringOrigin = state.userLocation ?: GeoPoint(state.camera.latitude, state.camera.longitude)
    style.getSourceAs<GeoJsonSource>(SRC_RANGE_RINGS)?.setGeoJson(
        if (state.showRangeRings) {
            TacticalGeoJsonBuilder.buildRangeRingsGeoJson(ringOrigin)
        } else {
            TacticalGeoJsonBuilder.emptyFeatureCollection()
        },
    )

    style.getSourceAs<GeoJsonSource>(SRC_SCENT_CONE)?.setGeoJson(
        if (state.canUseScentCones) {
            TacticalGeoJsonBuilder.buildScentConeGeoJson(
                origin = state.scentOrigin,
                scentToDeg = state.scentToDeg,
            )
        } else {
            TacticalGeoJsonBuilder.emptyFeatureCollection()
        },
    )

    val arrowOrigin = state.scentOrigin ?: state.userLocation ?: GeoPoint(state.camera.latitude, state.camera.longitude)
    style.getSourceAs<GeoJsonSource>(SRC_WIND_ARROW)?.setGeoJson(
        TacticalGeoJsonBuilder.buildWindArrowGeoJson(
            origin = arrowOrigin,
            windFromDeg = state.windFromDeg,
        ),
    )

    style.getSourceAs<GeoJsonSource>(SRC_THERMAL_ARROW)?.setGeoJson(
        if (state.canUseScentCones && state.thermalDrift != null) {
            TacticalGeoJsonBuilder.buildThermalDriftArrowGeoJson(
                origin = arrowOrigin,
                thermalToDeg = state.thermalDrift.thermalDriftToDeg,
                thermalSpeedMph = state.thermalDrift.thermalSpeedMph,
            )
        } else {
            TacticalGeoJsonBuilder.emptyFeatureCollection()
        },
    )

    style.getSourceAs<GeoJsonSource>(SRC_WAYPOINTS)?.setGeoJson(
        TacticalGeoJsonBuilder.buildWaypointsGeoJson(state.waypoints, state.lockedWaypointIds),
    )

    style.getSourceAs<GeoJsonSource>(SRC_STANDS)?.setGeoJson(
        TacticalGeoJsonBuilder.buildStandsGeoJson(state.standPins),
    )

    val heading = state.compassReading?.trueHeadingDeg ?: 0.0
    style.getSourceAs<GeoJsonSource>(SRC_USER_FOV)?.setGeoJson(
        TacticalGeoJsonBuilder.buildUserPuckAndFovGeoJson(
            location = state.userLocation,
            trueHeadingDeg = heading,
        ),
    )
}

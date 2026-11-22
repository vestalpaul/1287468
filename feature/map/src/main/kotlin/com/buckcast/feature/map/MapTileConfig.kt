package com.buckcast.feature.map

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Available tactical base map layers for DropTine.
 */
enum class MapBaseLayer(
    val label: String,
    val isAlphaOnly: Boolean,
) {
    TOPO(label = "Topo", isAlphaOnly = false),
    SATELLITE(label = "Satellite", isAlphaOnly = true),
    HYBRID(label = "Hybrid", isAlphaOnly = true),
}

/**
 * Builds MapLibre v8 Style JSON files in `filesDir/map_styles/` so both live [MapView]
 * rendering and [OfflineManager] region downloads resolve the exact same local `file://` URI.
 */
object MapTileConfig {

    const val USGS_TOPO_URL =
        "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}"
    const val ESRI_WORLD_IMAGERY_URL =
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
    const val ESRI_TRANSPORTATION_URL =
        "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}"
    const val ESRI_BOUNDARIES_PLACES_URL =
        "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}"
    const val USGS_HYBRID_FALLBACK_URL =
        "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryTopo/MapServer/tile/{z}/{y}/{x}"
    const val AWS_TERRARIUM_DEM_URL =
        "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"

    private const val USGS_ATTRIBUTION = "USGS The National Map"
    private const val ESRI_ATTRIBUTION = "Esri, Maxar, Earthstar Geographics"
    private const val MAPTILER_ATTRIBUTION = "© MapTiler © OpenStreetMap contributors"
    private const val TERRARIUM_ATTRIBUTION = "Mapzen / AWS Open Data Terrarium"

    const val DEFAULT_GLYPH_FONT = "Open Sans Semibold"

    fun styleFileName(baseLayer: MapBaseLayer, showHillshade: Boolean): String {
        val baseName = baseLayer.name.lowercase(Locale.US)
        return if (showHillshade) "${baseName}_hillshade.json" else "$baseName.json"
    }

    fun writeStyleFile(
        context: Context,
        baseLayer: MapBaseLayer,
        showHillshade: Boolean,
        mapTilerApiKey: String,
    ): File {
        val stylesDir = File(context.applicationContext.filesDir, "map_styles").apply { mkdirs() }
        val file = File(stylesDir, styleFileName(baseLayer, showHillshade))
        val json = buildStyleJson(
            baseLayer = baseLayer,
            showHillshade = showHillshade,
            mapTilerApiKey = mapTilerApiKey,
        )
        file.writeText(json, StandardCharsets.UTF_8)
        return file
    }

    fun styleFileUri(
        context: Context,
        baseLayer: MapBaseLayer,
        showHillshade: Boolean,
        mapTilerApiKey: String,
    ): String = Uri.fromFile(writeStyleFile(context, baseLayer, showHillshade, mapTilerApiKey)).toString()

    fun baseTileUrl(layer: MapBaseLayer, mapTilerApiKey: String): String {
        val key = mapTilerApiKey.trim()
        return when (layer) {
            MapBaseLayer.TOPO -> USGS_TOPO_URL
            MapBaseLayer.SATELLITE ->
                if (key.isNotEmpty()) {
                    "https://api.maptiler.com/tiles/satellite-v2/{z}/{x}/{y}.jpg?key=$key"
                } else {
                    ESRI_WORLD_IMAGERY_URL
                }
            MapBaseLayer.HYBRID ->
                if (key.isNotEmpty()) {
                    "https://api.maptiler.com/maps/hybrid/256/{z}/{x}/{y}.jpg?key=$key"
                } else {
                    ESRI_WORLD_IMAGERY_URL
                }
        }
    }

    fun buildStyleJson(
        baseLayer: MapBaseLayer,
        showHillshade: Boolean,
        mapTilerApiKey: String,
    ): String {
        val trimmedKey = mapTilerApiKey.trim()
        val baseUrl = baseTileUrl(baseLayer, trimmedKey)
        val useMapTiler = baseLayer != MapBaseLayer.TOPO && trimmedKey.isNotEmpty()
        val useEsriHybridOverlay = baseLayer == MapBaseLayer.HYBRID && !useMapTiler

        val baseAttribution = when {
            baseLayer == MapBaseLayer.TOPO -> USGS_ATTRIBUTION
            useMapTiler -> MAPTILER_ATTRIBUTION
            else -> ESRI_ATTRIBUTION
        }
        val baseMaxZoom = if (baseLayer == MapBaseLayer.TOPO) 16 else 19

        val sourceEntries = mutableListOf<String>()
        sourceEntries.add(
            """
            "droptine-base": {
              "type": "raster",
              "tiles": ["$baseUrl"],
              "tileSize": 256,
              "maxzoom": $baseMaxZoom,
              "attribution": "$baseAttribution"
            }
            """.trimIndent(),
        )

        if (showHillshade) {
            sourceEntries.add(
                """
                "droptine-terrarium-dem": {
                  "type": "raster-dem",
                  "tiles": ["$AWS_TERRARIUM_DEM_URL"],
                  "encoding": "terrarium",
                  "tileSize": 256,
                  "maxzoom": 15,
                  "attribution": "$TERRARIUM_ATTRIBUTION"
                }
                """.trimIndent(),
            )
        }

        if (useEsriHybridOverlay) {
            sourceEntries.add(
                """
                "droptine-hybrid-transport": {
                  "type": "raster",
                  "tiles": ["$ESRI_TRANSPORTATION_URL"],
                  "tileSize": 256,
                  "maxzoom": 19,
                  "attribution": "$ESRI_ATTRIBUTION"
                }
                """.trimIndent(),
            )
            sourceEntries.add(
                """
                "droptine-hybrid-labels": {
                  "type": "raster",
                  "tiles": ["$ESRI_BOUNDARIES_PLACES_URL"],
                  "tileSize": 256,
                  "maxzoom": 19,
                  "attribution": "$ESRI_ATTRIBUTION"
                }
                """.trimIndent(),
            )
        }

        val layerEntries = mutableListOf<String>()
        layerEntries.add(
            """
            {
              "id": "droptine-base-layer",
              "type": "raster",
              "source": "droptine-base"
            }
            """.trimIndent(),
        )

        if (showHillshade) {
            layerEntries.add(
                """
                {
                  "id": "droptine-hillshade-layer",
                  "type": "hillshade",
                  "source": "droptine-terrarium-dem",
                  "paint": {
                    "hillshade-exaggeration": 0.7,
                    "hillshade-illumination-direction": 315,
                    "hillshade-shadow-color": "#0A1014",
                    "hillshade-highlight-color": "#FFF8E7",
                    "hillshade-accent-color": "#1E2D24"
                  }
                }
                """.trimIndent(),
            )
        }

        if (useEsriHybridOverlay) {
            layerEntries.add(
                """
                {
                  "id": "droptine-hybrid-transport-layer",
                  "type": "raster",
                  "source": "droptine-hybrid-transport",
                  "paint": {
                    "raster-opacity": 0.92
                  }
                }
                """.trimIndent(),
            )
            layerEntries.add(
                """
                {
                  "id": "droptine-hybrid-labels-layer",
                  "type": "raster",
                  "source": "droptine-hybrid-labels",
                  "paint": {
                    "raster-opacity": 1.0
                  }
                }
                """.trimIndent(),
            )
        }

        return """
            {
              "version": 8,
              "name": "DropTine ${baseLayer.label}",
              "glyphs": "https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf",
              "sources": {
                ${sourceEntries.joinToString(",\n                ")}
              },
              "layers": [
                ${layerEntries.joinToString(",\n                ")}
              ]
            }
        """.trimIndent()
    }
}

package com.buckcast.feature.map

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
 * Builds MapLibre v8 Style JSON definitions served via the virtual HTTPS host
 * `https://styles.droptine.app/{styleKey}.json` (intercepted in [OkHttpClient]) so both
 * live [MapView] rendering and [OfflineManager] region downloads resolve identical HTTPS URIs.
 */
object MapTileConfig {

    const val VIRTUAL_STYLE_HOST = "styles.droptine.app"
    const val VIRTUAL_STYLE_BASE_URL = "https://styles.droptine.app/"

    const val USGS_TOPO_URL =
        "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}"
    const val ESRI_CLARITY_IMAGERY_URL =
        "https://clarity.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
    const val ESRI_WORLD_IMAGERY_URL =
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
    const val ESRI_TRANSPORTATION_URL =
        "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}"
    const val ESRI_BOUNDARIES_PLACES_URL =
        "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}"
    const val USGS_HYDRO_URL =
        "https://basemap.nationalmap.gov/arcgis/rest/services/USGSHydroCached/MapServer/tile/{z}/{y}/{x}"
    const val USGS_CONTOURS_URL =
        "https://carto.nationalmap.gov/arcgis/rest/services/contours/MapServer/export?bbox={bbox-epsg-3857}&bboxSR=3857&imageSR=3857&size=256,256&dpi=96&format=png32&transparent=true&f=image"
    const val AWS_TERRARIUM_DEM_URL =
        "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"

    private const val USGS_ATTRIBUTION = "USGS The National Map"
    private const val ESRI_ATTRIBUTION = "Esri, Maxar, Earthstar Geographics"
    private const val MAPTILER_ATTRIBUTION = "© MapTiler © OpenStreetMap contributors"
    private const val TERRARIUM_ATTRIBUTION = "Mapzen / AWS Open Data Terrarium"

    const val DEFAULT_GLYPH_FONT = "Open Sans Semibold"

    fun styleKey(
        baseLayer: MapBaseLayer,
        showHillshade: Boolean,
        offlineSafe: Boolean = false,
    ): String {
        val baseName = baseLayer.name.lowercase(Locale.US)
        val hillPart = if (showHillshade) "_hillshade" else ""
        val offlinePart = if (offlineSafe) "_offline" else ""
        return "$baseName$hillPart$offlinePart"
    }

    fun styleFileName(
        baseLayer: MapBaseLayer,
        showHillshade: Boolean,
        offlineSafe: Boolean = false,
    ): String = "${styleKey(baseLayer, showHillshade, offlineSafe)}.json"

    fun styleUrl(
        baseLayer: MapBaseLayer,
        showHillshade: Boolean,
        offlineSafe: Boolean = false,
    ): String = "$VIRTUAL_STYLE_BASE_URL${styleFileName(baseLayer, showHillshade, offlineSafe)}"

    fun resolveStyleJsonForPath(path: String, mapTilerApiKey: String): String? {
        val clean = path.trimStart('/').trim()
        if (!clean.endsWith(".json", ignoreCase = true)) return null
        var stem = clean.dropLast(5).lowercase(Locale.US)
        val offlineSafe = stem.endsWith("_offline")
        if (offlineSafe) {
            stem = stem.removeSuffix("_offline")
        }
        val showHillshade = stem.endsWith("_hillshade")
        if (showHillshade) {
            stem = stem.removeSuffix("_hillshade")
        }
        val layer = MapBaseLayer.entries.firstOrNull {
            it.name.lowercase(Locale.US) == stem
        } ?: return null
        return buildStyleJson(
            baseLayer = layer,
            showHillshade = showHillshade,
            mapTilerApiKey = mapTilerApiKey,
            offlineSafe = offlineSafe,
        )
    }

    fun baseTileUrl(layer: MapBaseLayer, mapTilerApiKey: String): String {
        val key = mapTilerApiKey.trim()
        return when (layer) {
            MapBaseLayer.TOPO -> USGS_TOPO_URL
            MapBaseLayer.SATELLITE ->
                if (key.isNotEmpty()) {
                    "https://api.maptiler.com/tiles/satellite-v2/{z}/{x}/{y}.jpg?key=$key"
                } else {
                    ESRI_CLARITY_IMAGERY_URL
                }
            MapBaseLayer.HYBRID ->
                if (key.isNotEmpty()) {
                    "https://api.maptiler.com/maps/hybrid/256/{z}/{x}/{y}.jpg?key=$key"
                } else {
                    ESRI_CLARITY_IMAGERY_URL
                }
        }
    }

    fun buildStyleJson(
        baseLayer: MapBaseLayer,
        showHillshade: Boolean,
        mapTilerApiKey: String,
        offlineSafe: Boolean = false,
    ): String {
        val trimmedKey = mapTilerApiKey.trim()
        val baseUrl = baseTileUrl(baseLayer, trimmedKey)
        val useMapTiler = baseLayer != MapBaseLayer.TOPO && trimmedKey.isNotEmpty()
        val isHybrid = baseLayer == MapBaseLayer.HYBRID
        val includeTerrariumDem = showHillshade || isHybrid

        val baseAttribution = when {
            baseLayer == MapBaseLayer.TOPO -> USGS_ATTRIBUTION
            useMapTiler -> MAPTILER_ATTRIBUTION
            else -> ESRI_ATTRIBUTION
        }
        val baseMaxZoom = if (baseLayer == MapBaseLayer.TOPO) 16 else 19
        val baseTileSize = if (baseLayer != MapBaseLayer.TOPO && !useMapTiler) 128 else 256

        val sourceEntries = mutableListOf<String>()
        sourceEntries.add(
            """
            "droptine-base": {
              "type": "raster",
              "tiles": ["$baseUrl"],
              "tileSize": $baseTileSize,
              "maxzoom": $baseMaxZoom,
              "attribution": "$baseAttribution"
            }
            """.trimIndent(),
        )

        if (includeTerrariumDem) {
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

        if (isHybrid) {
            sourceEntries.add(
                """
                "droptine-hybrid-hydro": {
                  "type": "raster",
                  "tiles": ["$USGS_HYDRO_URL"],
                  "tileSize": 256,
                  "maxzoom": 16,
                  "attribution": "$USGS_ATTRIBUTION"
                }
                """.trimIndent(),
            )
            // Dynamic {bbox-epsg-3857} WMS export cannot be enumerated as z/x/y tiles by OfflineManager.
            if (!offlineSafe) {
                sourceEntries.add(
                    """
                    "droptine-hybrid-contours": {
                      "type": "raster",
                      "tiles": ["$USGS_CONTOURS_URL"],
                      "tileSize": 256,
                      "minzoom": 11,
                      "maxzoom": 16,
                      "attribution": "$USGS_ATTRIBUTION"
                    }
                    """.trimIndent(),
                )
            }
            if (!useMapTiler) {
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
        }

        val hillshadeExaggeration = when {
            isHybrid && showHillshade -> 0.75
            isHybrid -> 0.45
            else -> 0.7
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

        if (includeTerrariumDem) {
            layerEntries.add(
                """
                {
                  "id": "droptine-hillshade-layer",
                  "type": "hillshade",
                  "source": "droptine-terrarium-dem",
                  "paint": {
                    "hillshade-exaggeration": $hillshadeExaggeration,
                    "hillshade-illumination-direction": 315,
                    "hillshade-shadow-color": "#0A1014",
                    "hillshade-highlight-color": "#FFF8E7",
                    "hillshade-accent-color": "#1E2D24"
                  }
                }
                """.trimIndent(),
            )
        }

        if (isHybrid) {
            layerEntries.add(
                """
                {
                  "id": "droptine-hybrid-hydro-layer",
                  "type": "raster",
                  "source": "droptine-hybrid-hydro",
                  "paint": {
                    "raster-opacity": 0.72
                  }
                }
                """.trimIndent(),
            )
            if (!offlineSafe) {
                layerEntries.add(
                    """
                    {
                      "id": "droptine-hybrid-contours-layer",
                      "type": "raster",
                      "source": "droptine-hybrid-contours",
                      "minzoom": 11,
                      "paint": {
                        "raster-opacity": 0.80
                      }
                    }
                    """.trimIndent(),
                )
            }
            if (!useMapTiler) {
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
        }

        // MapLibre Native OfflineDownload hits std::regex_error (error_badbrace) when {fontstack}/{range}
        // appears in offline style JSON glyphs; offline styles contain only raster/DEM layers.
        val glyphsEntry = if (offlineSafe) {
            ""
        } else {
            "\n              \"glyphs\": \"https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf\","
        }

        return """
            {
              "version": 8,
              "name": "DropTine ${baseLayer.label}",$glyphsEntry
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

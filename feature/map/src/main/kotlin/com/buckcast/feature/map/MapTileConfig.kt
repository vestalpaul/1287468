package com.buckcast.feature.map

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
 * Builds self-contained MapLibre Style JSON documents with USGS National Map defaults
 * and optional MapTiler commercial aerial imagery when a key is supplied via BuildConfig.
 */
object MapTileConfig {

    private const val USGS_TOPO_URL =
        "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}"
    private const val USGS_SATELLITE_URL =
        "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer/tile/{z}/{y}/{x}"
    private const val USGS_HYBRID_URL =
        "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryTopo/MapServer/tile/{z}/{y}/{x}"
    private const val USGS_HILLSHADE_URL =
        "https://basemap.nationalmap.gov/arcgis/rest/services/USGSShadedReliefOnly/MapServer/tile/{z}/{y}/{x}"

    private const val USGS_ATTRIBUTION = "USGS The National Map"
    private const val MAPTILER_ATTRIBUTION = "© MapTiler © OpenStreetMap contributors"

    fun baseTileUrl(layer: MapBaseLayer, mapTilerApiKey: String): String {
        val key = mapTilerApiKey.trim()
        return when (layer) {
            MapBaseLayer.TOPO -> USGS_TOPO_URL
            MapBaseLayer.SATELLITE ->
                if (key.isNotEmpty()) {
                    "https://api.maptiler.com/tiles/satellite-v2/{z}/{x}/{y}.jpg?key=$key"
                } else {
                    USGS_SATELLITE_URL
                }
            MapBaseLayer.HYBRID ->
                if (key.isNotEmpty()) {
                    "https://api.maptiler.com/maps/hybrid/256/{z}/{x}/{y}.jpg?key=$key"
                } else {
                    USGS_HYBRID_URL
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
        val attribution = if (useMapTiler) MAPTILER_ATTRIBUTION else USGS_ATTRIBUTION
        val maxZoom = if (useMapTiler) 18 else 16

        val sourcesBlock = buildString {
            append(
                """
                "droptine-base": {
                  "type": "raster",
                  "tiles": ["$baseUrl"],
                  "tileSize": 256,
                  "maxzoom": $maxZoom,
                  "attribution": "$attribution"
                }
                """.trimIndent(),
            )
            if (showHillshade) {
                append(",\n")
                append(
                    """
                    "droptine-hillshade": {
                      "type": "raster",
                      "tiles": ["$USGS_HILLSHADE_URL"],
                      "tileSize": 256,
                      "maxzoom": 16,
                      "attribution": "$USGS_ATTRIBUTION"
                    }
                    """.trimIndent(),
                )
            }
        }

        val layersBlock = buildString {
            append(
                """
                {
                  "id": "droptine-base-layer",
                  "type": "raster",
                  "source": "droptine-base"
                }
                """.trimIndent(),
            )
            if (showHillshade) {
                append(",\n")
                append(
                    """
                    {
                      "id": "droptine-hillshade-layer",
                      "type": "raster",
                      "source": "droptine-hillshade",
                      "paint": {
                        "raster-opacity": 0.38
                      }
                    }
                    """.trimIndent(),
                )
            }
        }

        return """
            {
              "version": 8,
              "name": "DropTine ${baseLayer.label}",
              "glyphs": "https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf",
              "sources": {
                $sourcesBlock
              },
              "layers": [
                $layersBlock
              ]
            }
        """.trimIndent()
    }
}

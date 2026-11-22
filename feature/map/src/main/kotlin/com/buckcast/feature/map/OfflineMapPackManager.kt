package com.buckcast.feature.map

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/**
 * Pure-Kotlin Web Mercator tile count & storage estimator enforcing the 6,000-tile guardrail.
 */
object OfflineTileEstimator {

    const val DEFAULT_MIN_ZOOM = 10
    const val DEFAULT_MAX_ZOOM = 16
    const val MAX_TILE_COUNT_GUARDRAIL = 6_000L
    private const val ESTIMATED_KB_PER_TILE = 28.0

    fun estimateTileCount(
        northLat: Double,
        southLat: Double,
        eastLon: Double,
        westLon: Double,
        minZoom: Int = DEFAULT_MIN_ZOOM,
        maxZoom: Int = DEFAULT_MAX_ZOOM,
    ): Long {
        require(minZoom in 0..maxZoom && maxZoom <= 22) {
            "Invalid zoom range: $minZoom..$maxZoom"
        }
        val clampedNorth = northLat.coerceIn(-85.0511, 85.0511)
        val clampedSouth = southLat.coerceIn(-85.0511, 85.0511)
        val minLat = min(clampedNorth, clampedSouth)
        val maxLat = max(clampedNorth, clampedSouth)
        val minLon = min(westLon, eastLon).coerceIn(-180.0, 180.0)
        val maxLon = max(westLon, eastLon).coerceIn(-180.0, 180.0)

        var total = 0L
        for (z in minZoom..maxZoom) {
            val n = 2.0.pow(z.toDouble()).toLong().coerceAtLeast(1L)
            val xMin = lonToTileX(minLon, n)
            val xMax = lonToTileX(maxLon, n)
            val yMin = latToTileY(maxLat, n)
            val yMax = latToTileY(minLat, n)
            val dx = (xMax - xMin + 1L).coerceAtLeast(1L)
            val dy = (yMax - yMin + 1L).coerceAtLeast(1L)
            total += dx * dy
        }
        return total
    }

    fun estimateMegabytes(tileCount: Long): Double =
        (tileCount.coerceAtLeast(0L) * ESTIMATED_KB_PER_TILE) / 1024.0

    private fun lonToTileX(lonDeg: Double, n: Long): Long {
        val raw = floor(((lonDeg + 180.0) / 360.0) * n).toLong()
        return raw.coerceIn(0L, n - 1L)
    }

    private fun latToTileY(latDeg: Double, n: Long): Long {
        val rad = Math.toRadians(latDeg)
        val merc = ln(tan(rad) + 1.0 / cos(rad))
        val raw = floor(((1.0 - merc / PI) / 2.0) * n).toLong()
        return raw.coerceIn(0L, n - 1L)
    }
}

data class OfflinePackSummary(
    val id: Long,
    val name: String,
    val minZoom: Int,
    val maxZoom: Int,
    val completedResources: Long,
    val requiredResources: Long,
    val completedBytes: Long,
    val isComplete: Boolean,
)

data class OfflinePackManagerState(
    val isDownloading: Boolean = false,
    val activeRegionName: String? = null,
    val progressFraction: Float = 0f,
    val packs: List<OfflinePackSummary> = emptyList(),
    val errorMessage: String? = null,
    val statusMessage: String? = null,
)

/**
 * Coordinates MapLibre [OfflineManager] region downloads, progress observation, retries, and deletion.
 */
class OfflineMapPackManager(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val offlineManager = OfflineManager.getInstance(appContext)
    private val stateFlow = MutableStateFlow(OfflinePackManagerState())
    val state: StateFlow<OfflinePackManagerState> = stateFlow.asStateFlow()

    fun refreshPacks() {
        offlineManager.listOfflineRegions(
            object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    val regions = offlineRegions.orEmpty()
                    if (regions.isEmpty()) {
                        stateFlow.value = stateFlow.value.copy(packs = emptyList())
                        return
                    }
                    val collected = mutableListOf<OfflinePackSummary>()
                    var remaining = regions.size
                    regions.forEach { region ->
                        region.getStatus(
                            object : OfflineRegion.OfflineRegionStatusCallback {
                                override fun onStatus(status: OfflineRegionStatus?) {
                                    val def = region.definition as? OfflineTilePyramidRegionDefinition
                                    val name = region.metadata.toString(StandardCharsets.UTF_8)
                                        .ifBlank { "Offline Pack #${region.id}" }
                                    collected.add(
                                        OfflinePackSummary(
                                            id = region.id,
                                            name = name,
                                            minZoom = def?.minZoom?.toInt() ?: OfflineTileEstimator.DEFAULT_MIN_ZOOM,
                                            maxZoom = def?.maxZoom?.toInt() ?: OfflineTileEstimator.DEFAULT_MAX_ZOOM,
                                            completedResources = status?.completedResourceCount ?: 0L,
                                            requiredResources = status?.requiredResourceCount ?: 0L,
                                            completedBytes = status?.completedResourceSize ?: 0L,
                                            isComplete = status?.isComplete ?: false,
                                        ),
                                    )
                                    remaining--
                                    if (remaining <= 0) {
                                        stateFlow.value = stateFlow.value.copy(
                                            packs = collected.sortedByDescending { it.id },
                                        )
                                    }
                                }

                                override fun onError(error: String?) {
                                    remaining--
                                    if (remaining <= 0) {
                                        stateFlow.value = stateFlow.value.copy(
                                            packs = collected.sortedByDescending { it.id },
                                            errorMessage = error,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }

                override fun onError(error: String) {
                    stateFlow.value = stateFlow.value.copy(
                        errorMessage = error.ifBlank { "Unable to list offline packs" },
                    )
                }
            },
        )
    }

    fun startDownload(
        packName: String,
        northLat: Double,
        southLat: Double,
        eastLon: Double,
        westLon: Double,
        styleJson: String,
        styleFileKey: String,
        minZoom: Int = OfflineTileEstimator.DEFAULT_MIN_ZOOM,
        maxZoom: Int = OfflineTileEstimator.DEFAULT_MAX_ZOOM,
    ) {
        val estimatedTiles = OfflineTileEstimator.estimateTileCount(
            northLat = northLat,
            southLat = southLat,
            eastLon = eastLon,
            westLon = westLon,
            minZoom = minZoom,
            maxZoom = maxZoom,
        )
        if (estimatedTiles > OfflineTileEstimator.MAX_TILE_COUNT_GUARDRAIL) {
            stateFlow.value = stateFlow.value.copy(
                errorMessage = "Selected box needs $estimatedTiles tiles (limit ${OfflineTileEstimator.MAX_TILE_COUNT_GUARDRAIL}). Zoom in closer before downloading.",
                statusMessage = null,
            )
            return
        }

        val cleanName = packName.trim().ifBlank { "Hunt Area" }
        val stylesDir = File(appContext.filesDir, "offline-styles").apply { mkdirs() }
        val styleFile = File(stylesDir, "$styleFileKey.json")
        styleFile.writeText(styleJson, StandardCharsets.UTF_8)

        val bounds = LatLngBounds.from(
            max(northLat, southLat).coerceIn(-85.0, 85.0),
            max(eastLon, westLon).coerceIn(-180.0, 180.0),
            min(northLat, southLat).coerceIn(-85.0, 85.0),
            min(eastLon, westLon).coerceIn(-180.0, 180.0),
        )
        val definition = OfflineTilePyramidRegionDefinition(
            Uri.fromFile(styleFile).toString(),
            bounds,
            minZoom.toDouble(),
            maxZoom.toDouble(),
            appContext.resources.displayMetrics.density.coerceAtLeast(1f),
        )
        val metadata = cleanName.toByteArray(StandardCharsets.UTF_8)

        offlineManager.setOfflineMapboxTileCountLimit(OfflineTileEstimator.MAX_TILE_COUNT_GUARDRAIL)
        stateFlow.value = stateFlow.value.copy(
            isDownloading = true,
            activeRegionName = cleanName,
            progressFraction = 0f,
            errorMessage = null,
            statusMessage = "Initializing offline pack...",
        )

        offlineManager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    offlineRegion.setObserver(
                        object : OfflineRegion.OfflineRegionObserver {
                            override fun onStatusChanged(status: OfflineRegionStatus) {
                                val required = max(status.requiredResourceCount, 1L)
                                val fraction = (status.completedResourceCount.toFloat() / required.toFloat())
                                    .coerceIn(0f, 1f)
                                val complete = status.isComplete
                                stateFlow.value = stateFlow.value.copy(
                                    isDownloading = !complete,
                                    progressFraction = fraction,
                                    statusMessage = if (complete) {
                                        "Downloaded \"$cleanName\" (${status.completedTileCount} tiles)"
                                    } else {
                                        "Downloading \"$cleanName\" (${status.completedResourceCount}/${status.requiredResourceCount})"
                                    },
                                )
                                if (complete) {
                                    offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                    offlineRegion.setObserver(null)
                                    refreshPacks()
                                }
                            }

                            override fun onError(error: OfflineRegionError) {
                                stateFlow.value = stateFlow.value.copy(
                                    isDownloading = false,
                                    errorMessage = "${error.reason}: ${error.message}",
                                    statusMessage = null,
                                )
                            }

                            override fun mapboxTileCountLimitExceeded(limit: Long) {
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                stateFlow.value = stateFlow.value.copy(
                                    isDownloading = false,
                                    errorMessage = "Tile guardrail exceeded ($limit tiles). Zoom in and retry.",
                                    statusMessage = null,
                                )
                            }
                        },
                    )
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    stateFlow.value = stateFlow.value.copy(
                        isDownloading = false,
                        errorMessage = error.ifBlank { "Unable to create offline region" },
                        statusMessage = null,
                    )
                }
            },
        )
    }

    fun deletePack(packId: Long) {
        offlineManager.listOfflineRegions(
            object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    val target = offlineRegions.orEmpty().firstOrNull { it.id == packId } ?: return
                    target.delete(
                        object : OfflineRegion.OfflineRegionDeleteCallback {
                            override fun onDelete() {
                                stateFlow.value = stateFlow.value.copy(
                                    statusMessage = "Deleted offline map pack",
                                    errorMessage = null,
                                )
                                refreshPacks()
                            }

                            override fun onError(error: String) {
                                stateFlow.value = stateFlow.value.copy(
                                    errorMessage = "Delete failed: $error",
                                )
                            }
                        },
                    )
                }

                override fun onError(error: String) {
                    stateFlow.value = stateFlow.value.copy(errorMessage = error)
                }
            },
        )
    }
}

package com.buckcast.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

data class ViewportBoundingBox(
    val northLat: Double,
    val southLat: Double,
    val eastLon: Double,
    val westLon: Double,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflinePackSheet(
    viewportBox: ViewportBoundingBox?,
    baseLayer: MapBaseLayer,
    showHillshade: Boolean,
    packState: OfflinePackManagerState,
    onDownloadViewport: (packName: String) -> Unit,
    onCancelDownload: () -> Unit,
    onDeletePack: (packId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var packName by remember(baseLayer) { mutableStateOf("Hunt Area · ${baseLayer.label}") }

    val tileEstimate = viewportBox?.let {
        OfflineTileEstimator.estimateTileCount(
            northLat = it.northLat,
            southLat = it.southLat,
            eastLon = it.eastLon,
            westLon = it.westLon,
        )
    } ?: 0L
    val estimatedMb = OfflineTileEstimator.estimateMegabytes(tileEstimate)
    val overLimit = tileEstimate > OfflineTileEstimator.MAX_TILE_COUNT_GUARDRAIL

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Offline Map Packs (Zoom 10–16)",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Save the current viewport (${baseLayer.label}${if (showHillshade) " + Hillshade" else ""}) before entering zero-cell timber.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = packName,
                onValueChange = { packName = it },
                label = { Text("Pack Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (overLimit) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "Estimated area: %,d tiles (~%.1f MB)",
                            tileEstimate,
                            estimatedMb,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (overLimit) {
                        Text(
                            text = "Exceeds ${OfflineTileEstimator.MAX_TILE_COUNT_GUARDRAIL}-tile guardrail. Zoom in slightly before downloading.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            if (packState.isDownloading) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { packState.progressFraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = packState.statusMessage ?: "Downloading tiles...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else if (!packState.statusMessage.isNullOrBlank()) {
                Text(
                    text = packState.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (!packState.errorMessage.isNullOrBlank()) {
                Text(
                    text = packState.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (packState.isDownloading) {
                OutlinedButton(
                    onClick = onCancelDownload,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cancel Download", color = MaterialTheme.colorScheme.error)
                }
            } else {
                Button(
                    onClick = { onDownloadViewport(packName) },
                    enabled = viewportBox != null && !overLimit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null)
                    Text(
                        text = if (packState.errorMessage != null) "Retry Viewport Download" else "Download Current Area",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            Text(
                text = "Saved Offline Packs (${packState.packs.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            if (packState.packs.isEmpty()) {
                Text(
                    text = "No offline packs saved yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                packState.packs.forEach { pack ->
                    Card {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(pack.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                val sizeMb = pack.completedBytes / (1024.0 * 1024.0)
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "Z%d-%d · %d tiles (%d/%d res) · %.1f MB · %s",
                                        pack.minZoom,
                                        pack.maxZoom,
                                        pack.completedTiles,
                                        pack.completedResources,
                                        pack.requiredResources,
                                        sizeMb,
                                        if (pack.isComplete) "Ready offline" else "Partial",
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onDeletePack(pack.id) }) {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = "Delete pack",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Close")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

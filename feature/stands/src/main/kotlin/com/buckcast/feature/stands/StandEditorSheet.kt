package com.buckcast.feature.stands

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditLocationAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.buckcast.domain.math.GeoPoint
import com.buckcast.domain.model.Stand
import com.buckcast.domain.model.StandType
import com.buckcast.domain.model.TerrainPosition
import java.util.Locale

data class StandEditorDraft(
    val existingStand: Stand? = null,
    val location: GeoPoint,
    val name: String = existingStand?.name.orEmpty(),
    val standType: StandType = existingStand?.standType ?: StandType.HANG_ON,
    val standHeightFeet: Float = (existingStand?.standHeightFeet ?: 18.0).toFloat(),
    val terrainPosition: TerrainPosition = existingStand?.terrainPosition ?: TerrainPosition.UPPER_THIRD,
    val selectedWindDirs: Set<WindRoseDirection> = existingStand?.preferredWindSectors
        ?.let { WindRoseDirection.fromSectors(it) }
        ?: setOf(WindRoseDirection.N, WindRoseDirection.NW),
    val slopeAspectText: String = existingStand?.slopeAspectDeg?.toInt()?.toString().orEmpty(),
    val slopeGradeText: String = existingStand?.slopeGradePercent?.toInt()?.toString().orEmpty(),
    val notes: String = existingStand?.notes.orEmpty(),
) {
    companion object {
        fun fromStand(stand: Stand): StandEditorDraft = StandEditorDraft(
            existingStand = stand,
            location = stand.location,
        )

        fun forNewLocation(location: GeoPoint): StandEditorDraft = StandEditorDraft(
            existingStand = null,
            location = location,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StandEditorSheet(
    initialLocation: GeoPoint,
    existingStand: Stand? = null,
    draft: StandEditorDraft? = null,
    isAlpha: Boolean,
    onSave: (
        name: String,
        location: GeoPoint,
        standType: StandType,
        standHeightFeet: Double,
        terrainPosition: TerrainPosition,
        selectedWindDirs: Set<WindRoseDirection>,
        slopeAspectDeg: Double?,
        slopeGradePercent: Double?,
        notes: String,
    ) -> Unit,
    onRequestReposition: ((StandEditorDraft) -> Unit)? = null,
    onStartRouteDrawing: ((Stand) -> Unit)? = null,
    onOpenPaywall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val resolvedExisting = draft?.existingStand ?: existingStand
    val resolvedLocation = draft?.location ?: initialLocation
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val draftSeedKey = "${resolvedExisting?.id.orEmpty()}_${resolvedLocation.latitude}_${resolvedLocation.longitude}"
    var name by remember(draftSeedKey) {
        mutableStateOf(draft?.name ?: resolvedExisting?.name.orEmpty())
    }
    var standType by remember(draftSeedKey) {
        mutableStateOf(draft?.standType ?: resolvedExisting?.standType ?: StandType.HANG_ON)
    }
    var heightFeet by remember(draftSeedKey) {
        mutableFloatStateOf(draft?.standHeightFeet ?: (resolvedExisting?.standHeightFeet ?: 18.0).toFloat())
    }
    var terrainPos by remember(draftSeedKey) {
        mutableStateOf(draft?.terrainPosition ?: resolvedExisting?.terrainPosition ?: TerrainPosition.UPPER_THIRD)
    }
    var selectedDirs by remember(draftSeedKey) {
        mutableStateOf(
            draft?.selectedWindDirs
                ?: resolvedExisting?.preferredWindSectors?.let { WindRoseDirection.fromSectors(it) }
                ?: setOf(WindRoseDirection.N, WindRoseDirection.NW),
        )
    }
    var slopeAspectText by remember(draftSeedKey) {
        mutableStateOf(draft?.slopeAspectText ?: resolvedExisting?.slopeAspectDeg?.toInt()?.toString().orEmpty())
    }
    var slopeGradeText by remember(draftSeedKey) {
        mutableStateOf(draft?.slopeGradeText ?: resolvedExisting?.slopeGradePercent?.toInt()?.toString().orEmpty())
    }
    var notes by remember(draftSeedKey) {
        mutableStateOf(draft?.notes ?: resolvedExisting?.notes.orEmpty())
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (resolvedExisting == null) "Tag New Stand" else "Edit ${resolvedExisting.name}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = String.format(Locale.US, "GPS %.5f, %.5f", resolvedLocation.latitude, resolvedLocation.longitude),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (onRequestReposition != null) {
                OutlinedButton(
                    onClick = {
                        onRequestReposition(
                            StandEditorDraft(
                                existingStand = resolvedExisting,
                                location = resolvedLocation,
                                name = name,
                                standType = standType,
                                standHeightFeet = heightFeet,
                                terrainPosition = terrainPos,
                                selectedWindDirs = selectedDirs,
                                slopeAspectText = slopeAspectText,
                                slopeGradeText = slopeGradeText,
                                notes = notes,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.EditLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Reposition on Map")
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Stand Name") },
                placeholder = { Text("Oak Ridge Pinch") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Stand Type", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StandType.entries.forEach { type ->
                    FilterChip(
                        selected = standType == type,
                        onClick = { standType = type },
                        label = { Text(type.name.replace('_', ' ')) },
                    )
                }
            }

            Column {
                Text(
                    text = "Platform Height: ${heightFeet.toInt()} ft",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = heightFeet,
                    onValueChange = { heightFeet = it },
                    valueRange = 0f..32f,
                )
            }

            Text("Terrain Position", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TerrainPosition.entries.forEach { pos ->
                    FilterChip(
                        selected = terrainPos == pos,
                        onClick = { terrainPos = pos },
                        label = { Text(pos.name.replace('_', ' ')) },
                    )
                }
            }

            Text("Preferred Wind-FROM Rose (8-Point)", style = MaterialTheme.typography.labelLarge)
            WindRoseSelector(
                selected = selectedDirs,
                onToggle = { dir ->
                    selectedDirs = if (dir in selectedDirs) selectedDirs - dir else selectedDirs + dir
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = slopeAspectText,
                    onValueChange = { slopeAspectText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Downhill Aspect (°)") },
                    placeholder = { Text("0–359") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = slopeGradeText,
                    onValueChange = { slopeGradeText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Slope Grade (%)") },
                    placeholder = { Text("15") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Stand Notes") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )

            if (resolvedExisting != null && onStartRouteDrawing != null) {
                OutlinedButton(
                    onClick = {
                        if (isAlpha) {
                            onStartRouteDrawing(resolvedExisting)
                            onDismiss()
                        } else {
                            onOpenPaywall()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (isAlpha) "Draw Entry / Exit Route" else "Draw Entry / Exit Route (Alpha)")
                }
            }

            Button(
                onClick = {
                    onSave(
                        name,
                        resolvedLocation,
                        standType,
                        heightFeet.toDouble(),
                        terrainPos,
                        selectedDirs,
                        slopeAspectText.toDoubleOrNull(),
                        slopeGradeText.toDoubleOrNull(),
                        notes,
                    )
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save Stand")
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun WindRoseSelector(
    selected: Set<WindRoseDirection>,
    onToggle: (WindRoseDirection) -> Unit,
) {
    val rows = listOf(
        listOf(WindRoseDirection.NW, WindRoseDirection.N, WindRoseDirection.NE),
        listOf(WindRoseDirection.W, null, WindRoseDirection.E),
        listOf(WindRoseDirection.SW, WindRoseDirection.S, WindRoseDirection.SE),
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { dir ->
                    if (dir == null) {
                        Spacer(modifier = Modifier.weight(1f))
                    } else {
                        FilterChip(
                            selected = dir in selected,
                            onClick = { onToggle(dir) },
                            label = { Text(dir.label, fontWeight = FontWeight.Bold) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

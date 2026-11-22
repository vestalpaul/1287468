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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StandEditorSheet(
    initialLocation: GeoPoint,
    existingStand: Stand? = null,
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
    onStartRouteDrawing: ((Stand) -> Unit)? = null,
    onOpenPaywall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(existingStand?.name.orEmpty()) }
    var standType by remember { mutableStateOf(existingStand?.standType ?: StandType.HANG_ON) }
    var heightFeet by remember { mutableFloatStateOf((existingStand?.standHeightFeet ?: 18.0).toFloat()) }
    var terrainPos by remember { mutableStateOf(existingStand?.terrainPosition ?: TerrainPosition.UPPER_THIRD) }
    var selectedDirs by remember {
        mutableStateOf(
            existingStand?.preferredWindSectors?.let { WindRoseDirection.fromSectors(it) }
                ?: setOf(WindRoseDirection.N, WindRoseDirection.NW),
        )
    }
    var slopeAspectText by remember { mutableStateOf(existingStand?.slopeAspectDeg?.toInt()?.toString().orEmpty()) }
    var slopeGradeText by remember { mutableStateOf(existingStand?.slopeGradePercent?.toInt()?.toString().orEmpty()) }
    var notes by remember { mutableStateOf(existingStand?.notes.orEmpty()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (existingStand == null) "Tag New Stand" else "Edit ${existingStand.name}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = String.format(Locale.US, "GPS %.5f, %.5f", initialLocation.latitude, initialLocation.longitude),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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

            if (existingStand != null && onStartRouteDrawing != null) {
                OutlinedButton(
                    onClick = {
                        if (isAlpha) {
                            onStartRouteDrawing(existingStand)
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
                        initialLocation,
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

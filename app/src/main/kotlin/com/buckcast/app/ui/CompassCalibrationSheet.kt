package com.buckcast.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.buckcast.domain.math.AngleMath
import com.buckcast.sensors.CompassAccuracy
import com.buckcast.sensors.CompassPosture
import com.buckcast.sensors.CompassReading
import com.buckcast.sensors.CompassSensorSource
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompassCalibrationSheet(
    reading: CompassReading?,
    compassOffsetDeg: Double,
    onUpdateOffsetDeg: (Double) -> Unit,
    onRecalibrate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val accuracy = reading?.accuracy ?: CompassAccuracy.UNRELIABLE
    val statusColor = when (accuracy) {
        CompassAccuracy.HIGH -> Color(0xFF2E7D32)
        CompassAccuracy.MEDIUM -> MaterialTheme.colorScheme.tertiary
        CompassAccuracy.LOW, CompassAccuracy.UNRELIABLE -> MaterialTheme.colorScheme.error
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Explore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Compass Calibration",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "True-North sensor fusion & declination trim",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    color = statusColor.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, statusColor),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor),
                        )
                        Text(
                            text = accuracy.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColor,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // Live Sensor Telemetry Grid
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val trueStr = reading?.takeIf { it.isSensorAvailable }
                        ?.let { AngleMath.formatHeadingTenths(it.trueHeadingDeg) }
                        ?: "---.-°"
                    val magStr = reading?.takeIf { it.isSensorAvailable }
                        ?.let { AngleMath.formatHeadingTenths(it.magneticHeadingDeg) }
                        ?: "---.-°"
                    val declStr = reading?.takeIf { it.isSensorAvailable }
                        ?.let { String.format(Locale.US, "%+.1f°", it.declinationDeg) }
                        ?: "0.0°"
                    val postureStr = when (reading?.posture) {
                        CompassPosture.UPRIGHT_AIMING -> "Aim"
                        else -> "Flat"
                    }
                    val pitchStr = reading?.takeIf { it.isSensorAvailable }?.let {
                        val deg = if (it.posture == CompassPosture.UPRIGHT_AIMING) {
                            it.pitchDeg.roundToInt()
                        } else {
                            (90.0 + it.pitchDeg).roundToInt().coerceIn(0, 90)
                        }
                        String.format(Locale.US, "%+d°", deg)
                    } ?: "--°"
                    val sourceStr = when (reading?.sensorSource) {
                        CompassSensorSource.ROTATION_VECTOR -> "Rotation Vector (Gyro+Mag)"
                        CompassSensorSource.GEOMAGNETIC_ROTATION_VECTOR -> "Geomagnetic Vector"
                        CompassSensorSource.UNAVAILABLE, null -> "Sensor Unavailable"
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TelemetryCell(label = "True Heading", value = trueStr, modifier = Modifier.weight(1f))
                        TelemetryCell(label = "Magnetic", value = magStr, modifier = Modifier.weight(1f))
                        TelemetryCell(label = "Declination", value = declStr, modifier = Modifier.weight(1f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TelemetryCell(label = "Posture", value = postureStr, modifier = Modifier.weight(1f))
                        TelemetryCell(label = "Pitch / Tilt", value = pitchStr, modifier = Modifier.weight(1f))
                        TelemetryCell(label = "Trim Offset", value = String.format(Locale.US, "%+.1f°", compassOffsetDeg), modifier = Modifier.weight(1f))
                    }
                    Text(
                        text = "Sensor: $sourceStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Guided Figure-8 Instructions
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (accuracy == CompassAccuracy.HIGH) {
                        Color(0xFF2E7D32).copy(alpha = 0.14f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ),
                border = BorderStroke(1.dp, statusColor),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = if (accuracy == CompassAccuracy.HIGH) {
                            Icons.Rounded.CheckCircle
                        } else {
                            Icons.Rounded.WarningAmber
                        },
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(28.dp),
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (accuracy == CompassAccuracy.HIGH) {
                                "High Accuracy Lock Achieved"
                            } else {
                                "Wave Phone in a Smooth Figure-8"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                        )
                        Text(
                            text = "Rotate your wrist smoothly through three figure-8 loops away from treestand steel, buckles, or truck cabs until accuracy turns GREEN (HIGH).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onRecalibrate,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Reset Sensor Fusion")
            }

            // Manual Trim Offset Slider
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = String.format(Locale.US, "Manual Trim Offset: %+.1f°", compassOffsetDeg),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TextButton(
                        onClick = { onUpdateOffsetDeg(0.0) },
                        enabled = compassOffsetDeg != 0.0,
                    ) {
                        Text("Reset 0°")
                    }
                }
                Slider(
                    value = compassOffsetDeg.toFloat().coerceIn(-15f, 15f),
                    onValueChange = { raw ->
                        val snapped = ((raw * 2.0).roundToInt() / 2.0).coerceIn(-15.0, 15.0)
                        onUpdateOffsetDeg(snapped)
                    },
                    valueRange = -15f..15f,
                    steps = 59,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("-15°", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("0°", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("+15°", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Done")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TelemetryCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

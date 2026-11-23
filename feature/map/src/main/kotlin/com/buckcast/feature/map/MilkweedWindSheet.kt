package com.buckcast.feature.map

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.buckcast.domain.math.AngleMath
import com.buckcast.domain.math.CompassDirection
import com.buckcast.domain.wind.LiveWindOverride
import com.buckcast.domain.wind.windFromMilkweedFloatDeg
import java.util.Locale
import kotlin.math.roundToInt

private val QUICK_WIND_DIRECTIONS = listOf(
    "N" to 0.0,
    "NE" to 45.0,
    "E" to 90.0,
    "SE" to 135.0,
    "S" to 180.0,
    "SW" to 225.0,
    "W" to 270.0,
    "NW" to 315.0,
)

private val QUICK_WIND_SPEEDS = listOf(
    "Calm 2" to 2.0,
    "Light 5" to 5.0,
    "Breeze 10" to 10.0,
    "Steady 15" to 15.0,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MilkweedWindSheet(
    liveHeadingDeg: Double?,
    forecastWindFromDeg: Double,
    forecastWindSpeedMph: Double,
    activeOverride: LiveWindOverride?,
    onSnapMilkweedFloat: (downwindFloatHeadingDeg: Double, speedMph: Double?) -> Unit,
    onApplyManualWind: (windFromDeg: Double, speedMph: Double) -> Unit,
    onResetToForecast: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var manualWindFromDeg by remember(activeOverride?.windFromDeg, forecastWindFromDeg) {
        mutableFloatStateOf(((activeOverride?.windFromDeg ?: forecastWindFromDeg).toFloat()).coerceIn(0f, 355f))
    }
    var useForecastSpeed by remember(activeOverride?.windSpeedMph, forecastWindSpeedMph) {
        val matchesQuickPreset = activeOverride != null &&
            QUICK_WIND_SPEEDS.any { (_, mph) -> kotlin.math.abs(activeOverride.windSpeedMph - mph) < 0.5 }
        val matchesForecast = activeOverride != null &&
            kotlin.math.abs(activeOverride.windSpeedMph - forecastWindSpeedMph) < 0.5
        mutableStateOf(activeOverride == null || (matchesForecast && !matchesQuickPreset))
    }
    var manualSpeedMph by remember(activeOverride?.windSpeedMph, forecastWindSpeedMph) {
        mutableFloatStateOf((activeOverride?.windSpeedMph ?: forecastWindSpeedMph).toFloat().coerceIn(0f, 40f))
    }

    val effectiveSelectedSpeed = if (useForecastSpeed) forecastWindSpeedMph else manualSpeedMph.toDouble()
    val forecastDirLabel = CompassDirection.fromBearing(forecastWindFromDeg).abbreviation

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
                    imageVector = Icons.Rounded.Air,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Milkweed / Woods-Floor Wind",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Forecast Reference: $forecastDirLabel (${AngleMath.roundWholeDegrees(forecastWindFromDeg)}°) @ ${forecastWindSpeedMph.roundToInt()} mph",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Primary 1-Tap Milkweed Float Action
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Point phone where milkweed floats, then tap",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    val previewText = liveHeadingDeg?.let { heading ->
                        val floatDir = CompassDirection.fromBearing(heading).abbreviation
                        val fromDeg = windFromMilkweedFloatDeg(heading)
                        val fromDir = CompassDirection.fromBearing(fromDeg).abbreviation
                        String.format(
                            Locale.US,
                            "Float: %d° %s → Wind FROM %s (%d°)",
                            AngleMath.roundWholeDegrees(heading),
                            floatDir,
                            fromDir,
                            AngleMath.roundWholeDegrees(fromDeg),
                        )
                    } ?: "Waiting for compass heading..."
                    Text(
                        text = previewText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Button(
                        onClick = {
                            liveHeadingDeg?.let { heading ->
                                onSnapMilkweedFloat(heading, effectiveSelectedSpeed)
                                onDismiss()
                            }
                        },
                        enabled = liveHeadingDeg != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Snap to Milkweed Float")
                    }
                }
            }

            // Manual Wind-FROM Override
            Text(
                text = "Manual Wind-FROM Direction",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                QUICK_WIND_DIRECTIONS.forEach { (label, deg) ->
                    val isSelected = kotlin.math.abs(manualWindFromDeg - deg.toFloat()) < 5f ||
                        (deg == 0.0 && manualWindFromDeg >= 355f)
                    FilterChip(
                        selected = isSelected,
                        onClick = { manualWindFromDeg = deg.toFloat() },
                        label = { Text(label, maxLines = 1, softWrap = false) },
                    )
                }
            }

            val sliderDir = CompassDirection.fromBearing(manualWindFromDeg.toDouble()).abbreviation
            Text(
                text = String.format(
                    Locale.US,
                    "Fine Wind-FROM Bearing: %d° (%s)",
                    AngleMath.roundWholeDegrees(manualWindFromDeg.toDouble()),
                    sliderDir,
                ),
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = manualWindFromDeg,
                onValueChange = { raw ->
                    val snapped = ((raw / 5f).roundToInt() * 5).coerceIn(0, 355)
                    manualWindFromDeg = snapped.toFloat()
                },
                valueRange = 0f..355f,
                steps = 70,
            )

            Text(
                text = "Woods-Floor Wind Speed",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = useForecastSpeed,
                    onClick = {
                        useForecastSpeed = true
                        manualSpeedMph = forecastWindSpeedMph.toFloat()
                    },
                    label = { Text("Forecast (${forecastWindSpeedMph.roundToInt()} mph)", maxLines = 1, softWrap = false) },
                )
                QUICK_WIND_SPEEDS.forEach { (label, mph) ->
                    val selected = !useForecastSpeed && kotlin.math.abs(manualSpeedMph - mph.toFloat()) < 0.5f
                    FilterChip(
                        selected = selected,
                        onClick = {
                            useForecastSpeed = false
                            manualSpeedMph = mph.toFloat()
                        },
                        label = { Text(label, maxLines = 1, softWrap = false) },
                    )
                }
            }

            Button(
                onClick = {
                    onApplyManualWind(manualWindFromDeg.toDouble(), effectiveSelectedSpeed)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    String.format(
                        Locale.US,
                        "Apply Manual Wind (%s %d° @ %.0f mph)",
                        sliderDir,
                        AngleMath.roundWholeDegrees(manualWindFromDeg.toDouble()),
                        effectiveSelectedSpeed,
                    ),
                )
            }

            if (activeOverride != null) {
                OutlinedButton(
                    onClick = {
                        onResetToForecast()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Reset to Forecast ($forecastDirLabel @ ${forecastWindSpeedMph.roundToInt()} mph)")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

package com.buckcast.feature.forecast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
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
import com.buckcast.domain.math.CompassDirection
import com.buckcast.domain.scoring.HuntSession
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovementIntelSheet(
    day: DayOutlookUi,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedSession by remember { mutableStateOf(HuntSession.AM) }
    val activeReport = if (selectedSession == HuntSession.AM) day.amReport else day.pmReport
    val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "${day.dayLabel} Movement Intel",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = day.rut.phase?.let { "Phase ${it.number}: ${it.phaseName}" } ?: "Off-Season",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${activeReport.rating.label} · ${activeReport.overallScore.toInt()}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf(HuntSession.AM, HuntSession.PM).forEachIndexed { idx, session ->
                    val score = if (session == HuntSession.AM) day.amReport.overallScore else day.pmReport.overallScore
                    SegmentedButton(
                        selected = selectedSession == session,
                        onClick = { selectedSession = session },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = 2),
                    ) {
                        Text("${session.name} (${score.toInt()})")
                    }
                }
            }

            Text(
                text = "5-Factor Transparent Breakdown",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            activeReport.factors.forEach { factor ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${factor.factor.displayName} (${(factor.weight * 100).toInt()}%)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = String.format(Locale.US, "+%.1f pts", factor.weightedContribution),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (factor.rawScore / 100.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${factor.summary} — ${factor.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()

            Text(
                text = "Shooting Light & Lunar Schedule",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ScheduleLine("Civil Dawn (-6°)", day.solar.civilDawn?.let(timeFmt::format) ?: "--")
                ScheduleLine("Legal Shooting Start", day.solar.legalShootingStart?.let(timeFmt::format) ?: "--")
                ScheduleLine("Sunrise", day.solar.sunrise?.let(timeFmt::format) ?: "--")
                ScheduleLine("Solar Noon", timeFmt.format(day.solar.solarNoon))
                ScheduleLine("Sunset", day.solar.sunset?.let(timeFmt::format) ?: "--")
                ScheduleLine("Legal Shooting End", day.solar.legalShootingEnd?.let(timeFmt::format) ?: "--")
                ScheduleLine("Civil Dusk (-6°)", day.solar.civilDusk?.let(timeFmt::format) ?: "--")
                ScheduleLine(
                    "Moon (${day.lunar.phase.displayName} ${day.lunar.illuminationPercent.toInt()}%)",
                    "↑${day.lunar.moonrise?.let(timeFmt::format) ?: "--"} / ↓${day.lunar.moonset?.let(timeFmt::format) ?: "--"}",
                )
            }

            if (day.daytimeHours.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "Hourly Hunt Table (4 AM – 9 PM)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                day.daytimeHours.forEach { h ->
                    val hrLabel = runCatching {
                        LocalDateTime.parse(h.isoLocalTime).format(DateTimeFormatter.ofPattern("h a", Locale.US))
                    }.getOrDefault(h.isoLocalTime)
                    val windCard = CompassDirection.fromBearing(h.windFromDeg).abbreviation
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(hrLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.9f))
                        Text("${h.tempDegF.toInt()}°F", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.9f))
                        Text("$windCard ${h.windSpeedMph.toInt()}mph", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.4f))
                        Text(
                            String.format(Locale.US, "%.2f", h.barometricPressureInHg),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(0.9f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ScheduleLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

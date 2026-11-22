package com.buckcast.feature.forecast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buckcast.domain.math.CompassDirection
import com.buckcast.domain.scoring.MovementRating
import com.buckcast.domain.wind.StandWindVerdict
import java.util.Locale

@Composable
fun ForecastScreen(
    viewModel: ForecastViewModel,
    contentPadding: PaddingValues,
    onOpenPaywall: () -> Unit,
    onNavigateToMap: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            TopHeaderBar(
                locationLabel = state.locationLabel,
                isPreviewActive = state.entitlement.isPreviewActive,
                remainingPreviewMs = state.entitlement.remainingPreviewMs,
                isStale = state.isCacheStale,
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refreshNow,
                onPreviewClick = onOpenPaywall,
            )
        }

        if (!state.offlineBannerMessage.isNullOrBlank()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Text(
                        text = state.offlineBannerMessage.orEmpty(),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }

        state.todayOutlook?.let { today ->
            item {
                HeroTodayCard(
                    today = today,
                    currentHour = state.currentHour,
                    canOpenIntel = state.canViewFiveFactorIntel,
                    onClick = {
                        if (!viewModel.openDayIntel(0)) {
                            onOpenPaywall()
                        }
                    },
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "7-Day Whitetail Outlook",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                SingleChoiceSegmentedButtonRow {
                    OutlookViewMode.entries.forEachIndexed { idx, mode ->
                        SegmentedButton(
                            selected = state.outlookViewMode == mode,
                            onClick = { viewModel.setOutlookViewMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = idx, count = 2),
                        ) {
                            Text(if (mode == OutlookViewMode.CARDS) "Cards" else "Timeline")
                        }
                    }
                }
            }
        }

        item {
            if (state.outlookViewMode == OutlookViewMode.CARDS) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.sevenDayOutlook, key = { it.dayIndex }) { day ->
                        val locked = day.dayIndex > 0 && !state.canViewSevenDays
                        OutlookDayCard(
                            day = day,
                            isLocked = locked,
                            onClick = {
                                if (locked || !viewModel.openDayIntel(day.dayIndex)) {
                                    onOpenPaywall()
                                }
                            },
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.sevenDayOutlook.forEach { day ->
                        val locked = day.dayIndex > 0 && !state.canViewSevenDays
                        OutlookTimelineRow(
                            day = day,
                            isLocked = locked,
                            onClick = {
                                if (locked || !viewModel.openDayIntel(day.dayIndex)) {
                                    onOpenPaywall()
                                }
                            },
                        )
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "My Stands Wind Match",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(StandWindSlot.entries) { slot ->
                        FilterChip(
                            selected = state.selectedStandSlot == slot,
                            onClick = { viewModel.selectStandSlot(slot) },
                            label = { Text(slot.label) },
                        )
                    }
                }
                if (state.rankedStands.isEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "No stands saved yet. Open the Map tab to tag a stand and inspect live wind & thermal vectors.",
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.rankedStands.forEach { item ->
                            RankedStandRow(
                                item = item,
                                onClick = {
                                    if (item.isLockedByScout) onOpenPaywall() else onNavigateToMap()
                                },
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNavigateToMap),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Rounded.Map,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Tactical Topo & Scent Map",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = "Tap to view live compass FOV, 300-yd scent plumes, and offline map packs",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }

    val selectedDay = state.selectedIntelDayIndex?.let { idx -> state.sevenDayOutlook.getOrNull(idx) }
    if (selectedDay != null) {
        MovementIntelSheet(
            day = selectedDay,
            onDismiss = viewModel::closeDayIntel,
        )
    }
}

@Composable
private fun TopHeaderBar(
    locationLabel: String,
    isPreviewActive: Boolean,
    remainingPreviewMs: Long,
    isStale: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onPreviewClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = onRefresh,
            leadingIcon = { Icon(Icons.Rounded.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp)) },
            label = { Text(locationLabel, maxLines = 1) },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (isPreviewActive) {
                val hours = (remainingPreviewMs / 3_600_000L).coerceAtLeast(1L)
                AssistChip(
                    onClick = onPreviewClick,
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.WorkspacePremium,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = { Text("Alpha · ${hours}h left") },
                )
            } else if (isStale) {
                Text(
                    text = "CACHED",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Refresh Forecast")
            }
        }
    }
}

@Composable
private fun HeroTodayCard(
    today: DayOutlookUi,
    currentHour: com.buckcast.domain.model.HourlyWeatherPoint?,
    canOpenIntel: Boolean,
    onClick: () -> Unit,
) {
    val temp = currentHour?.tempDegF ?: today.tempMaxDegF
    val windMph = currentHour?.windSpeedMph ?: today.windSpeedMph
    val windDir = CompassDirection.fromBearing(currentHour?.windFromDeg ?: today.windFromDeg).abbreviation
    val pressure = currentHour?.barometricPressureInHg ?: today.pressureInHg
    val trend = currentHour?.pressureTrend3hInHg ?: today.pressureTrend3hInHg
    val trendGlyph = when {
        trend >= 0.03 -> "↑"
        trend <= -0.03 -> "↓"
        else -> "→"
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "${temp.toInt()}°F",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "Wind %s @ %.0f mph · %.2f inHg %s",
                            windDir,
                            windMph,
                            pressure,
                            trendGlyph,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RatingPill("AM", today.amReport.rating, today.amReport.overallScore.toInt())
                    RatingPill("PM", today.pmReport.rating, today.pmReport.overallScore.toInt())
                }
            }

            val phaseText = today.rut.phase?.let {
                "Phase ${it.number}: ${it.phaseName} — ${it.biologicalName}"
            } ?: "Off-Season (${today.rut.daysUntilNextPhase}d to Phase 1)"
            Text(
                text = phaseText,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )

            Text(
                text = "Moon: ${today.lunar.phase.displayName} (${today.lunar.illuminationPercent.toInt()}%)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            DualSunMoonArcCanvas(solar = today.solar, lunar = today.lunar)

            Text(
                text = if (canOpenIntel) "Tap for 5-Factor Movement Intel & Hourly Table" else "Unlock DropTine Alpha for 5-Factor Breakdown",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun RatingPill(session: String, rating: MovementRating, score: Int) {
    val bg = when (rating) {
        MovementRating.GREAT -> MaterialTheme.colorScheme.primaryContainer
        MovementRating.GOOD -> MaterialTheme.colorScheme.secondaryContainer
        MovementRating.OKAY -> MaterialTheme.colorScheme.surfaceVariant
        MovementRating.SLOW -> MaterialTheme.colorScheme.errorContainer
    }
    Surface(color = bg, shape = MaterialTheme.shapes.small) {
        Text(
            text = "$session ${rating.label} $score",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun OutlookDayCard(
    day: DayOutlookUi,
    isLocked: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .width(134.dp)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(day.dayLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (isLocked) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = "Alpha Locked",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            if (isLocked) {
                Text("Alpha 7-Day", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                Text("Tap to unlock", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    text = "${day.tempMaxDegF.toInt()}° / ${day.tempMinDegF.toInt()}°F",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${day.windDirectionLabel} ${day.windSpeedMph.toInt()} mph",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "AM ${day.amReport.rating.label} · PM ${day.pmReport.rating.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun OutlookTimelineRow(
    day: DayOutlookUi,
    isLocked: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(day.dayLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (isLocked) {
                    Text("Alpha 7-Day Outlook — Tap to Unlock", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                } else {
                    Text(
                        "${day.tempMaxDegF.toInt()}°/${day.tempMinDegF.toInt()}°F · ${day.windDirectionLabel} ${day.windSpeedMph.toInt()} mph",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (isLocked) {
                Icon(Icons.Rounded.Lock, contentDescription = "Locked", tint = MaterialTheme.colorScheme.tertiary)
            } else {
                Text(
                    text = "AM ${day.amReport.overallScore.toInt()} | PM ${day.pmReport.overallScore.toInt()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun RankedStandRow(
    item: RankedStandUi,
    onClick: () -> Unit,
) {
    val verdictColor = when (item.evaluation.verdict) {
        StandWindVerdict.OPTIMAL, StandWindVerdict.GOOD -> MaterialTheme.colorScheme.primary
        StandWindVerdict.CAUTION -> MaterialTheme.colorScheme.tertiary
        StandWindVerdict.UNSAFE -> MaterialTheme.colorScheme.error
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.stand.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (item.isLockedByScout) {
                    Text("Alpha Stand Intelligence — Tap to unlock", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                } else {
                    Text(
                        text = "${item.evaluation.verdict.name} (${item.evaluation.score.toInt()}) · ${item.freshness.level.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = verdictColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (item.isLockedByScout) {
                Icon(Icons.Rounded.Lock, contentDescription = "Alpha Locked", tint = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

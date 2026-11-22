package com.buckcast.feature.stands

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.buckcast.domain.entitlement.AlphaFeature
import com.buckcast.domain.entitlement.EntitlementState
import com.buckcast.domain.math.AngleMath
import com.buckcast.domain.model.RouteType
import com.buckcast.domain.model.Stand
import com.buckcast.domain.wind.StandWindVerdict
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandDetailSheet(
    detail: StandDetailUi,
    entitlement: EntitlementState,
    onStartSit: () -> Unit,
    onEndSit: (windedDeer: Boolean) -> Unit,
    onEditStand: (Stand) -> Unit = {},
    onDrawRoute: (Stand, RouteType) -> Unit,
    onProjectScent: (Stand) -> Unit,
    onDeleteStand: () -> Unit,
    onOpenPaywall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hasWindFreshness = entitlement.hasAccess(AlphaFeature.STAND_WIND_FRESHNESS)
    val hasBallistics = entitlement.hasAccess(AlphaFeature.BALLISTIC_YARDAGE)
    val hasRouteAuditor = entitlement.hasAccess(AlphaFeature.ROUTE_AUDITOR)
    val hasScentCones = entitlement.hasAccess(AlphaFeature.SCENT_THERMAL_CONES)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = detail.stand.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${detail.stand.standType.name.replace('_', ' ')} · ${detail.stand.standHeightFeet.toInt()} ft · ${detail.stand.terrainPosition.name.replace('_', ' ')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (detail.isReadOnlyLocked) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = "Scout Read-Only Locked",
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            if (detail.isReadOnlyLocked) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Scout Plan includes 1 active Stand. Your extra stands remain safely preserved in read-only mode.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = onOpenPaywall) {
                            Text("Unlock All Stands with Alpha")
                        }
                    }
                }
            }

            // Live Wind Verdict Card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val verdictColor = when (detail.windEvaluation.verdict) {
                        StandWindVerdict.OPTIMAL, StandWindVerdict.GOOD -> MaterialTheme.colorScheme.primary
                        StandWindVerdict.CAUTION -> MaterialTheme.colorScheme.tertiary
                        StandWindVerdict.UNSAFE -> MaterialTheme.colorScheme.error
                    }
                    Text(
                        text = "Wind Verdict: ${detail.windEvaluation.verdict.name} (${detail.windEvaluation.score.toInt()}/100)",
                        style = MaterialTheme.typography.titleMedium,
                        color = verdictColor,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Scent vector ${AngleMath.formatHeadingTenths(detail.windEvaluation.effectiveScentToDeg)} @ ${detail.windEvaluation.effectiveWindSpeedMph.toInt()} mph",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = detail.windEvaluation.explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Cosine-Compensated Ballistic Range Card (Alpha)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Distance & Ballistic Cut",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (!hasBallistics) {
                        Text(
                            text = detail.distanceYardsFromUser?.let { "Straight distance: ${it.toInt()} yd · Unlock Alpha for cosine-compensated ballistic cut" }
                                ?: "Unlock Alpha for cosine-compensated ballistic yardage",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    } else if (detail.ballisticRangeFromUser != null) {
                        val b = detail.ballisticRangeFromUser
                        Text(
                            text = String.format(
                                Locale.US,
                                "Horizontal Cut: %.1f yd (LOS: %.1f yd, Angle: %+.1f°)",
                                b.horizontalCutYards,
                                b.lineOfSightYards,
                                b.elevationAngleDeg,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        Text("Waiting for GPS fix to compute range.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Stand Freshness & Sit Logging (Alpha)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Stand Freshness: ${detail.freshness.level.displayName} (${detail.freshness.pressureScore.toInt()}/100)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = detail.freshness.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!hasWindFreshness) {
                        OutlinedButton(onClick = onOpenPaywall, modifier = Modifier.fillMaxWidth()) {
                            Text("Unlock Sit Logging & Freshness (Alpha)")
                        }
                    } else if (detail.activeSitStartedEpochMs == null) {
                        Button(onClick = onStartSit, modifier = Modifier.fillMaxWidth()) {
                            Text("Start Sit")
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onEndSit(false) }, modifier = Modifier.weight(1f)) {
                                Text("End Sit (Clean)")
                            }
                            OutlinedButton(onClick = { onEndSit(true) }, modifier = Modifier.weight(1f)) {
                                Text("End (Winded)")
                            }
                        }
                    }
                }
            }

            // Entry/Exit Route Audits (Alpha)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Entry / Exit Route Wind Audit",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (!hasRouteAuditor) {
                        Text("Unlock Alpha to draw entry/exit polylines and audit bedding scent crossings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    } else {
                        if (detail.routeAudits.isEmpty()) {
                            Text("No entry/exit routes saved for this stand yet.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            detail.routeAudits.forEach { audit ->
                                Text(
                                    text = "${audit.verdict.name}: ${audit.summary}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onDrawRoute(detail.stand, RouteType.ENTRY)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Draw Entry")
                            }
                            OutlinedButton(
                                onClick = {
                                    onDrawRoute(detail.stand, RouteType.EXIT)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Draw Exit")
                            }
                        }
                    }
                }
            }

            if (!detail.isReadOnlyLocked) {
                OutlinedButton(
                    onClick = { onEditStand(detail.stand) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Edit Stand")
                }
            }

            Button(
                onClick = {
                    if (hasScentCones) {
                        onProjectScent(detail.stand)
                        onDismiss()
                    } else {
                        onOpenPaywall()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (hasScentCones) "Project Scent Cone on Map" else "Project Scent (Alpha)")
            }

            if (!detail.isReadOnlyLocked) {
                TextButton(
                    onClick = {
                        onDeleteStand()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Delete Stand", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

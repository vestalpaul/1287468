package com.buckcast.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buckcast.domain.entitlement.DebugEntitlementOverride
import com.buckcast.domain.entitlement.EntitlementSource
import com.buckcast.domain.model.AppThemePreset

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    contentPadding: PaddingValues,
    onOpenPaywall: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var showAccessCodeDialog by remember { mutableStateOf(false) }
    var copiedNotice by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = "Settings & DropTine Alpha",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        if (!state.statusMessage.isNullOrBlank()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = state.statusMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::clearMessage) {
                            Text("OK")
                        }
                    }
                }
            }
        }

        item {
            SubscriptionCard(
                state = state,
                copiedNotice = copiedNotice,
                onCopySupportId = {
                    if (state.entitlement.supportUserId.isNotBlank()) {
                        clipboard.setText(AnnotatedString(state.entitlement.supportUserId))
                        copiedNotice = true
                    }
                },
                onOpenPaywall = onOpenPaywall,
                onOpenAccessCode = { showAccessCodeDialog = true },
            )
        }

        item {
            ThemePickerCard(
                selected = state.effectiveThemePreset,
                isAlpha = state.entitlement.isAlpha,
                onSelect = { preset ->
                    if (!viewModel.selectThemePreset(preset)) {
                        onOpenPaywall()
                    }
                },
            )
        }

        item {
            CalibrationCard(
                peakRutMonthDay = state.settings.peakRutMonthDay,
                beforeSunriseMin = state.settings.legalLightBeforeSunriseMinutes,
                afterSunsetMin = state.settings.legalLightAfterSunsetMinutes,
                onSelectPeakRut = viewModel::updatePeakRutDate,
                onSelectLegalOffsets = viewModel::updateLegalLightOffsets,
            )
        }

        if (state.isDebugBuild) {
            item {
                DebugEntitlementCard(
                    currentOverride = state.entitlement.debugOverride,
                    onSelectOverride = viewModel::setDebugOverride,
                    onResetPreview = viewModel::reset36HourPreview,
                )
            }
        }
    }

    if (showAccessCodeDialog) {
        AccessCodeDialog(
            onDismiss = { showAccessCodeDialog = false },
            onSubmit = { code ->
                showAccessCodeDialog = false
                viewModel.submitAccessCode(code)
            },
        )
    }
}

@Composable
private fun SubscriptionCard(
    state: SettingsUiState,
    copiedNotice: Boolean,
    onCopySupportId: () -> Unit,
    onOpenPaywall: () -> Unit,
    onOpenAccessCode: () -> Unit,
) {
    val ent = state.entitlement
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.WorkspacePremium,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (ent.isAlpha) "DropTine Alpha" else "DropTine Scout (Free)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            val subtitle = when {
                ent.isPreviewActive ->
                    "36-Hour Welcome Preview · ${formatPreviewCountdown(ent.remainingPreviewMs)} remaining"
                ent.source == EntitlementSource.PROMO_COMP ->
                    "VIP / Promotional Alpha Pass Active"
                ent.source == EntitlementSource.PLAY_SUBSCRIPTION ->
                    if (ent.isOfflineGraceActive) "Play Subscription (Offline Grace Active)" else "Google Play Subscription Active"
                ent.source == EntitlementSource.DEBUG_OVERRIDE ->
                    "Debug Build Override Active"
                else ->
                    "1 Stand, 3 Waypoints, Today Forecast & Topo Map active. Upgrade for 7-day intel, scent cones, offline packs & themes."
            }
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenPaywall) {
                    Text(if (ent.isAlpha && !ent.isPreviewActive) "Manage Alpha" else "Upgrade to Alpha")
                }
                OutlinedButton(onClick = onOpenAccessCode) {
                    Icon(Icons.Rounded.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Access Code")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "DropTine Support ID",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = ent.supportUserId.ifBlank { "Initializing..." },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    if (copiedNotice) {
                        Text(
                            text = "Copied to clipboard",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(onClick = onCopySupportId) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy Support ID")
                }
            }
        }
    }
}

@Composable
private fun ThemePickerCard(
    selected: AppThemePreset,
    isAlpha: Boolean,
    onSelect: (AppThemePreset) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Appearance & Night-Vision Themes",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            AppThemePreset.entries.forEach { preset ->
                val isSelected = preset == selected
                val locked = preset.isAlphaOnly && !isAlpha
                val (bgSwatch, accentSwatch) = themeSwatches(preset)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = MaterialTheme.shapes.medium,
                        )
                        .clickable { onSelect(preset) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(bgSwatch)
                                .border(1.dp, Color.Gray, CircleShape),
                        )
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(accentSwatch),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = preset.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = preset.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (locked) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = "Alpha Locked",
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    } else if (isSelected) {
                        Text(
                            text = "ACTIVE",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

private fun themeSwatches(preset: AppThemePreset): Pair<Color, Color> = when (preset) {
    AppThemePreset.TIMBERLINE_DARK -> Color(0xFF0D120F) to Color(0xFF7EA874)
    AppThemePreset.TOPO_DAYLIGHT -> Color(0xFFF3F2EC) to Color(0xFF2B4A35)
    AppThemePreset.STEALTH_CRIMSON -> Color(0xFF000000) to Color(0xFFEF5350)
    AppThemePreset.BLAZE_SLATE -> Color(0xFF111418) to Color(0xFFE65100)
    AppThemePreset.MATERIAL_YOU -> Color(0xFF2B332E) to Color(0xFFA8D5BA)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalibrationCard(
    peakRutMonthDay: String,
    beforeSunriseMin: Long,
    afterSunsetMin: Long,
    onSelectPeakRut: (Int, Int) -> Unit,
    onSelectLegalOffsets: (Long, Long) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Rut & Legal Light Calibration",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text("Peak Rut Lockdown Anchor", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple("Nov 4 (North)", 11, 4),
                    Triple("Nov 11 (Midwest)", 11, 11),
                    Triple("Nov 20 (South)", 11, 20),
                ).forEach { (label, m, d) ->
                    val tag = String.format("%02d-%02d", m, d)
                    FilterChip(
                        selected = peakRutMonthDay == tag,
                        onClick = { onSelectPeakRut(m, d) },
                        label = { Text(label) },
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text("Legal Shooting Light Window", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple("-30m / +30m (Standard)", 30L, 30L),
                    Triple("-20m / +20m", 20L, 20L),
                    Triple("Sunrise / Sunset (0m)", 0L, 0L),
                ).forEach { (label, pre, post) ->
                    FilterChip(
                        selected = beforeSunriseMin == pre && afterSunsetMin == post,
                        onClick = { onSelectLegalOffsets(pre, post) },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DebugEntitlementCard(
    currentOverride: DebugEntitlementOverride,
    onSelectOverride: (DebugEntitlementOverride) -> Unit,
    onResetPreview: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Debug Entitlement Switcher",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DebugEntitlementOverride.entries.forEach { mode ->
                    FilterChip(
                        selected = currentOverride == mode,
                        onClick = { onSelectOverride(mode) },
                        label = { Text(mode.name) },
                    )
                }
            }
            OutlinedButton(onClick = onResetPreview) {
                Text("RESET_36H_PREVIEW")
            }
        }
    }
}

@Composable
private fun AccessCodeDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter VIP / Reviewer Access Code") },
        text = {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Access Code") },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(code) },
                enabled = code.isNotBlank(),
            ) {
                Text("Activate")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

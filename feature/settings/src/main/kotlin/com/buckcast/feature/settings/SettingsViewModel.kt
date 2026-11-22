package com.buckcast.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buckcast.domain.entitlement.DebugEntitlementOverride
import com.buckcast.domain.entitlement.EntitlementSource
import com.buckcast.domain.entitlement.EntitlementState
import com.buckcast.domain.entitlement.SubscriptionTier
import com.buckcast.domain.model.AppRuntimeConfig
import com.buckcast.domain.model.AppSettings
import com.buckcast.domain.model.AppThemePreset
import com.buckcast.domain.repository.EntitlementRepository
import com.buckcast.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.MonthDay
import java.util.Locale
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val entitlement: EntitlementState = EntitlementState(
        tier = SubscriptionTier.SCOUT,
        source = EntitlementSource.NONE,
        previewStartedEpochMs = 0L,
        previewExpiresEpochMs = 0L,
        subscriptionExpiresEpochMs = null,
        lastVerifiedEpochMs = null,
        isOfflineGraceActive = false,
        remainingPreviewMs = 0L,
    ),
    val effectiveThemePreset: AppThemePreset = AppThemePreset.TIMBERLINE_DARK,
    val isDebugBuild: Boolean = false,
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val entitlementRepository: EntitlementRepository,
    private val runtimeConfig: AppRuntimeConfig,
) : ViewModel() {

    private val busyFlow = MutableStateFlow(false)
    private val messageFlow = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.observeSettings(),
        entitlementRepository.observeEntitlement(),
        busyFlow,
        messageFlow,
    ) { settings, entitlement, busy, message ->
        SettingsUiState(
            settings = settings,
            entitlement = entitlement,
            effectiveThemePreset = entitlement.effectiveThemePreset(settings.themePreset),
            isDebugBuild = runtimeConfig.isDebugBuild,
            isBusy = busy,
            statusMessage = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SettingsUiState(isDebugBuild = runtimeConfig.isDebugBuild),
    )

    init {
        viewModelScope.launch {
            entitlementRepository.ensureInitialized()
        }
    }

    /**
     * Applies [preset] if allowed by the current entitlement, or returns `false` so the UI
     * opens the Alpha Paywall sheet.
     */
    fun selectThemePreset(preset: AppThemePreset): Boolean {
        val entitlement = uiState.value.entitlement
        if (preset.isAlphaOnly && !entitlement.isAlpha) {
            return false
        }
        viewModelScope.launch {
            val current = settingsRepository.getSettings()
            settingsRepository.updateSettings(current.copy(themePreset = preset))
            messageFlow.value = "Theme updated to ${preset.displayName}"
        }
        return true
    }

    fun updatePeakRutDate(month: Int, dayOfMonth: Int) {
        val valid = runCatching { MonthDay.of(month, dayOfMonth) }.getOrNull() ?: return
        val formatted = String.format(Locale.US, "%02d-%02d", valid.monthValue, valid.dayOfMonth)
        viewModelScope.launch {
            val current = settingsRepository.getSettings()
            settingsRepository.updateSettings(current.copy(peakRutMonthDay = formatted))
            messageFlow.value = "Peak Rut lockdown anchor set to $formatted"
        }
    }

    fun updateLegalLightOffsets(beforeSunriseMinutes: Long, afterSunsetMinutes: Long) {
        viewModelScope.launch {
            val current = settingsRepository.getSettings()
            settingsRepository.updateSettings(
                current.copy(
                    legalLightBeforeSunriseMinutes = beforeSunriseMinutes.coerceIn(0L, 60L),
                    legalLightAfterSunsetMinutes = afterSunsetMinutes.coerceIn(0L, 60L),
                ),
            )
            messageFlow.value = "Legal shooting light updated to -$beforeSunriseMinutes / +$afterSunsetMinutes min"
        }
    }

    fun submitAccessCode(code: String) {
        viewModelScope.launch {
            busyFlow.value = true
            messageFlow.value = null
            val result = entitlementRepository.logInWithAccessCode(code)
            messageFlow.value = result.fold(
                onSuccess = { state ->
                    if (state.isAlpha) "DropTine Alpha unlocked via Access Code!" else "Code verified (Scout tier)."
                },
                onFailure = { err -> err.message ?: "Unable to verify access code" },
            )
            busyFlow.value = false
        }
    }

    fun restorePurchases() {
        viewModelScope.launch {
            busyFlow.value = true
            messageFlow.value = null
            val result = entitlementRepository.restorePurchases()
            messageFlow.value = result.fold(
                onSuccess = { state ->
                    if (state.isAlpha) "DropTine Alpha purchases restored!" else "No active Alpha subscription found."
                },
                onFailure = { err -> err.message ?: "Restore failed" },
            )
            busyFlow.value = false
        }
    }

    fun purchaseAlpha(isAnnual: Boolean) {
        viewModelScope.launch {
            busyFlow.value = true
            messageFlow.value = null
            val result = entitlementRepository.purchaseAlpha(isAnnual)
            messageFlow.value = result.fold(
                onSuccess = { "Welcome to DropTine Alpha!" },
                onFailure = { err -> err.message ?: "Purchase did not complete" },
            )
            busyFlow.value = false
        }
    }

    fun setDebugOverride(override: DebugEntitlementOverride) {
        if (!runtimeConfig.isDebugBuild) return
        viewModelScope.launch {
            entitlementRepository.setDebugOverride(override)
            messageFlow.value = "Debug entitlement override: ${override.name}"
        }
    }

    fun reset36HourPreview() {
        if (!runtimeConfig.isDebugBuild) return
        viewModelScope.launch {
            entitlementRepository.reset36HourPreview()
            messageFlow.value = "36-hour Alpha preview reset"
        }
    }

    fun clearMessage() {
        messageFlow.value = null
    }
}

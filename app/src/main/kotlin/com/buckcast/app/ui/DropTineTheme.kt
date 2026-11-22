package com.buckcast.app.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.buckcast.domain.model.AppThemePreset

private val TimberlineDarkScheme = darkColorScheme(
    primary = Color(0xFF7CC97E),
    onPrimary = Color(0xFF062109),
    primaryContainer = Color(0xFF193C22),
    onPrimaryContainer = Color(0xFFC5F2C6),
    secondary = Color(0xFFE5B567),
    onSecondary = Color(0xFF281900),
    secondaryContainer = Color(0xFF2E2A1A),
    onSecondaryContainer = Color(0xFFFBE0AF),
    tertiary = Color(0xFFE5B567),
    onTertiary = Color(0xFF281900),
    tertiaryContainer = Color(0xFF3E2D12),
    onTertiaryContainer = Color(0xFFFDE2B5),
    background = Color(0xFF08110C),
    onBackground = Color(0xFFEAF2ED),
    surface = Color(0xFF08110C),
    onSurface = Color(0xFFEAF2ED),
    surfaceVariant = Color(0xFF16251C),
    onSurfaceVariant = Color(0xFFAEC0B5),
    surfaceContainerLowest = Color(0xFF050B08),
    surfaceContainerLow = Color(0xFF0D1711),
    surfaceContainer = Color(0xFF111E16),
    surfaceContainerHigh = Color(0xFF17281E),
    surfaceContainerHighest = Color(0xFF1E3226),
    outline = Color(0xFF3B5746),
    outlineVariant = Color(0xFF23382B),
    error = Color(0xFFEF5350),
    onError = Color(0xFF2B0808),
    errorContainer = Color(0xFF3E1414),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val TopoDaylightScheme = lightColorScheme(
    primary = Color(0xFF1B4332),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD2E8DA),
    onPrimaryContainer = Color(0xFF0A261A),
    secondary = Color(0xFF4A6655),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1EAE3),
    onSecondaryContainer = Color(0xFF17261D),
    tertiary = Color(0xFFC85A32),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDD1),
    onTertiaryContainer = Color(0xFF3E1202),
    background = Color(0xFFF4F1E8),
    onBackground = Color(0xFF2B302C),
    surface = Color(0xFFF4F1E8),
    onSurface = Color(0xFF2B302C),
    surfaceVariant = Color(0xFFE8E4D8),
    onSurfaceVariant = Color(0xFF535B55),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAF8F2),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFECE8DC),
    surfaceContainerHighest = Color(0xFFE2DDD0),
    outline = Color(0xFF7D847E),
    outlineVariant = Color(0xFFCFCAC0),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDECEA),
    onErrorContainer = Color(0xFF5F1111),
)

private val StealthCrimsonScheme = darkColorScheme(
    primary = Color(0xFFE53935),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF2D0707),
    onPrimaryContainer = Color(0xFFFF8A80),
    secondary = Color(0xFFE57373),
    onSecondary = Color(0xFF1F0303),
    secondaryContainer = Color(0xFF210505),
    onSecondaryContainer = Color(0xFFFF8A80),
    tertiary = Color(0xFFFF6B6B),
    onTertiary = Color(0xFF240404),
    tertiaryContainer = Color(0xFF2B0707),
    onTertiaryContainer = Color(0xFFFF8F8F),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFF6B6B),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE57373),
    surfaceVariant = Color(0xFF120303),
    onSurfaceVariant = Color(0xFFB85454),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF070101),
    surfaceContainer = Color(0xFF0C0202),
    surfaceContainerHigh = Color(0xFF160404),
    surfaceContainerHighest = Color(0xFF220707),
    outline = Color(0xFF5E1717),
    outlineVariant = Color(0xFF3A0B0B),
    error = Color(0xFFFF5252),
    onError = Color(0xFF220000),
    errorContainer = Color(0xFF2D0606),
    onErrorContainer = Color(0xFFFF8A80),
)

private val BlazeSlateScheme = darkColorScheme(
    primary = Color(0xFFFF5722),
    onPrimary = Color(0xFF210700),
    primaryContainer = Color(0xFF471908),
    onPrimaryContainer = Color(0xFFFFD6C8),
    secondary = Color(0xFF64B5F6),
    onSecondary = Color(0xFF061E33),
    secondaryContainer = Color(0xFF1A2D40),
    onSecondaryContainer = Color(0xFFCCE7FF),
    tertiary = Color(0xFFFFB74D),
    onTertiary = Color(0xFF291700),
    tertiaryContainer = Color(0xFF3A280A),
    onTertiaryContainer = Color(0xFFFFE4BA),
    background = Color(0xFF0D131A),
    onBackground = Color(0xFFE7EEF5),
    surface = Color(0xFF0D131A),
    onSurface = Color(0xFFE7EEF5),
    surfaceVariant = Color(0xFF1C2733),
    onSurfaceVariant = Color(0xFFAAB8C7),
    surfaceContainerLowest = Color(0xFF080C11),
    surfaceContainerLow = Color(0xFF111922),
    surfaceContainer = Color(0xFF16202B),
    surfaceContainerHigh = Color(0xFF1E2B3A),
    surfaceContainerHighest = Color(0xFF263547),
    outline = Color(0xFF465C73),
    outlineVariant = Color(0xFF2A3B4C),
    error = Color(0xFFEF5350),
    onError = Color(0xFF2B0808),
    errorContainer = Color(0xFF3E1414),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun DropTineTheme(
    preset: AppThemePreset,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()

    val (colorScheme: ColorScheme, isLightSurface: Boolean) = when (preset) {
        AppThemePreset.TIMBERLINE_DARK -> TimberlineDarkScheme to false
        AppThemePreset.TOPO_DAYLIGHT -> TopoDaylightScheme to true
        AppThemePreset.STEALTH_CRIMSON -> StealthCrimsonScheme to false
        AppThemePreset.BLAZE_SLATE -> BlazeSlateScheme to false
        AppThemePreset.MATERIAL_YOU -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val dynamic = if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                dynamic to !systemDark
            } else {
                TimberlineDarkScheme to false
            }
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = isLightSurface
                    isAppearanceLightNavigationBars = isLightSurface
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}

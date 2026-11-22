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
    primary = Color(0xFF7EA874),
    onPrimary = Color(0xFF0E1D0C),
    primaryContainer = Color(0xFF233A22),
    onPrimaryContainer = Color(0xFFCFE8C8),
    secondary = Color(0xFF9DB8A0),
    onSecondary = Color(0xFF152418),
    secondaryContainer = Color(0xFF233026),
    onSecondaryContainer = Color(0xFFD5E6D7),
    tertiary = Color(0xFFD6A456),
    onTertiary = Color(0xFF241704),
    tertiaryContainer = Color(0xFF3E2D12),
    onTertiaryContainer = Color(0xFFF9E0B8),
    background = Color(0xFF0A0E0C),
    onBackground = Color(0xFFEAF0EC),
    surface = Color(0xFF0D120F),
    onSurface = Color(0xFFEAF0EC),
    surfaceVariant = Color(0xFF19221C),
    onSurfaceVariant = Color(0xFFB3C0B7),
    outline = Color(0xFF3C4B41),
    error = Color(0xFFEF5350),
    onError = Color(0xFF2B0808),
)

private val TopoDaylightScheme = lightColorScheme(
    primary = Color(0xFF2B4A35),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE5D5),
    onPrimaryContainer = Color(0xFF102819),
    secondary = Color(0xFF526658),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE3DC),
    onSecondaryContainer = Color(0xFF18241C),
    tertiary = Color(0xFFB8522A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDBD0),
    onTertiaryContainer = Color(0xFF3E1202),
    background = Color(0xFFECEAE1),
    onBackground = Color(0xFF1A1F1B),
    surface = Color(0xFFF3F2EC),
    onSurface = Color(0xFF1A1F1B),
    surfaceVariant = Color(0xFFE2DFD3),
    onSurfaceVariant = Color(0xFF475049),
    outline = Color(0xFF7B837C),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
)

private val StealthCrimsonScheme = darkColorScheme(
    primary = Color(0xFFEF5350),
    onPrimary = Color(0xFF260303),
    primaryContainer = Color(0xFF4A0E0E),
    onPrimaryContainer = Color(0xFFFFD6D6),
    secondary = Color(0xFFC62828),
    onSecondary = Color(0xFFFFEAEA),
    secondaryContainer = Color(0xFF2C0B0B),
    onSecondaryContainer = Color(0xFFF5B8B8),
    tertiary = Color(0xFFE57373),
    onTertiary = Color(0xFF240505),
    tertiaryContainer = Color(0xFF3D1010),
    onTertiaryContainer = Color(0xFFFFDCDC),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF5C6C6),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF5C6C6),
    surfaceVariant = Color(0xFF160808),
    onSurfaceVariant = Color(0xFFC48686),
    outline = Color(0xFF521E1E),
    error = Color(0xFFFF5252),
    onError = Color(0xFF260000),
)

private val BlazeSlateScheme = darkColorScheme(
    primary = Color(0xFFE65100),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF4F1D00),
    onPrimaryContainer = Color(0xFFFFDBC9),
    secondary = Color(0xFF5C85A6),
    onSecondary = Color(0xFF0B1C29),
    secondaryContainer = Color(0xFF1E3140),
    onSecondaryContainer = Color(0xFFCDE2F3),
    tertiary = Color(0xFFFFB74D),
    onTertiary = Color(0xFF261700),
    tertiaryContainer = Color(0xFF422C08),
    onTertiaryContainer = Color(0xFFFFE5BC),
    background = Color(0xFF0D1014),
    onBackground = Color(0xFFE8ECEF),
    surface = Color(0xFF111418),
    onSurface = Color(0xFFE8ECEF),
    surfaceVariant = Color(0xFF1D232B),
    onSurfaceVariant = Color(0xFFAEB8C2),
    outline = Color(0xFF3D4754),
    error = Color(0xFFEF5350),
    onError = Color(0xFF2B0808),
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

package com.flow.adhd.ui

import androidx.compose.material3.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Brand and zone accents are separate: orange is an action, zone color is context.
private val DarkPotik = darkColorScheme(
    primary = Color(0xFFFF8554), onPrimary = Color(0xFF301206),
    primaryContainer = Color(0xFF48271C), onPrimaryContainer = Color(0xFFFFD9C9),
    secondary = Color(0xFFB8C6DF), onSecondary = Color(0xFF172437),
    secondaryContainer = Color(0xFF263348), onSecondaryContainer = Color(0xFFDCE6F8),
    tertiary = Color(0xFFE9C66E), onTertiary = Color(0xFF302307),
    tertiaryContainer = Color(0xFF3A311D), onTertiaryContainer = Color(0xFFF5D993),
    background = Color(0xFF0D0F14), onBackground = Color(0xFFE8EAF2),
    surface = Color(0xFF151922), onSurface = Color(0xFFE8EAF2),
    surfaceVariant = Color(0xFF222938), onSurfaceVariant = Color(0xFFB3BCD0),
    outline = Color(0xFF69758C), outlineVariant = Color(0xFF30384A),
    surfaceDim = Color(0xFF0D0F14), surfaceBright = Color(0xFF303744),
    surfaceContainerLowest = Color(0xFF0A0C10), surfaceContainerLow = Color(0xFF12161F),
    surfaceContainer = Color(0xFF191E29), surfaceContainerHigh = Color(0xFF222938),
    surfaceContainerHighest = Color(0xFF2B3344), surfaceTint = Color(0xFFFF8554),
    inverseSurface = Color(0xFFE8EAF2), inverseOnSurface = Color(0xFF202531),
    inversePrimary = Color(0xFF9B3510), error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF4A2022), onErrorContainer = Color(0xFFFFDAD6),
)
private val LightPotik = lightColorScheme(
    primary = Color(0xFFA93610), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE2D5), onPrimaryContainer = Color(0xFF421705),
    secondary = Color(0xFF405A7F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDFE8F6), onSecondaryContainer = Color(0xFF203550),
    tertiary = Color(0xFF795900), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEBC0), onTertiaryContainer = Color(0xFF443000),
    background = Color(0xFFF2F4F8), onBackground = Color(0xFF171D29),
    surface = Color.White, onSurface = Color(0xFF171D29),
    surfaceVariant = Color(0xFFE9EDF4), onSurfaceVariant = Color(0xFF4F5C70),
    outline = Color(0xFF6C788C), outlineVariant = Color(0xFFD1D9E5),
    surfaceDim = Color(0xFFE1E6EF), surfaceBright = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8F9FC),
    surfaceContainer = Color(0xFFEFF2F7), surfaceContainerHigh = Color(0xFFE8ECF3),
    surfaceContainerHighest = Color(0xFFE0E6EF), surfaceTint = Color(0xFFA93610),
    inverseSurface = Color(0xFF252C39), inverseOnSurface = Color(0xFFF1F3F9),
    inversePrimary = Color(0xFFFFAC89), error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)
private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = FontFamily.SansSerif, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)
private val PotikTypography = Typography(
    headlineLarge = style(28, 34, FontWeight.Bold), headlineMedium = style(24, 30, FontWeight.Bold),
    headlineSmall = style(22, 28, FontWeight.Bold), titleLarge = style(20, 26, FontWeight.SemiBold),
    titleMedium = style(16, 23, FontWeight.SemiBold), titleSmall = style(14, 20, FontWeight.SemiBold),
    bodyLarge = style(16, 24), bodyMedium = style(14, 21), bodySmall = style(14, 20),
    labelLarge = style(14, 20, FontWeight.Medium), labelMedium = style(13, 18, FontWeight.Medium),
    labelSmall = style(12, 17, FontWeight.Medium),
)
@Composable
fun FlowTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = mode == "dark" || (mode != "light" && isSystemInDarkTheme())
    val colors = if (dark) DarkPotik else LightPotik
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? android.app.Activity)?.window?.let { window ->
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.surface.toArgb()
            var flags = window.decorView.systemUiVisibility
            flags = if (dark) flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() else flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (android.os.Build.VERSION.SDK_INT >= 26) flags = if (dark) flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() else flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = flags
        }
    }
    MaterialTheme(colorScheme = colors, typography = PotikTypography,
        shapes = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(26.dp)), content = content)
}

package com.flow.adhd.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PotikColors = darkColorScheme(
    primary = Color(0xFFFF6B35), onPrimary = Color(0xFF1A100C),
    background = Color(0xFF0D0F14), onBackground = Color(0xFFE7E8ED),
    surface = Color(0xFF161922), onSurface = Color(0xFFE7E8ED),
    surfaceVariant = Color(0xFF212331), onSurfaceVariant = Color(0xFFB7BBC9),
    outline = Color(0xFF454958),
)

@Composable
fun FlowTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = mode == "dark" || (mode != "light" && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) PotikColors else lightColorScheme(
        primary = Color(0xFFAF3B12), onPrimary = Color.White,
        background = Color(0xFFFFF9F5), surface = Color(0xFFFFF9F5),
        surfaceVariant = Color(0xFFF3E1D9), onSurfaceVariant = Color(0xFF54433C),
    ), content = content)
}

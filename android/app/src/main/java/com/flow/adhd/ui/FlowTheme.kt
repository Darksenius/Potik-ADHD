package com.flow.adhd.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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
fun FlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PotikColors, content = content)
}

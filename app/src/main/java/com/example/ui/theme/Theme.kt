package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VideoCallDarkColorScheme = darkColorScheme(
    primary = ElectricViolet,
    onPrimary = Color.White,
    primaryContainer = VioletContainer,
    onPrimaryContainer = Color(0xFFE9D5FF),
    secondary = VibrantCyan,
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = CyanContainer,
    onSecondaryContainer = Color(0xFFBAE6FD),
    tertiary = CyanAccent,
    onTertiary = Color.White,
    background = MidnightNavy,
    onBackground = TextPrimary,
    surface = SlateNavy,
    onSurface = TextPrimary,
    surfaceVariant = CardNavy,
    onSurfaceVariant = TextSecondary,
    outline = BorderNavy,
    error = CallRed,
    onError = Color.White,
    errorContainer = CallRedContainer,
    onErrorContainer = Color(0xFFFECACA)
)

@Composable
fun VideoCallTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = VideoCallDarkColorScheme,
        typography = Typography,
        content = content
    )
}

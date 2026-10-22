package com.thenoahnoah.podfs.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = PodAmber,
    onPrimary = PodInk,
    secondary = PodAmberBright,
    background = PodInk,
    surface = PodSurface,
    surfaceVariant = PodSurfaceRaised,
    outline = PodOutline,
    onSurfaceVariant = PodMuted,
    tertiary = Color(0xFF8FD4C8)
)

@Composable
fun PodFSTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
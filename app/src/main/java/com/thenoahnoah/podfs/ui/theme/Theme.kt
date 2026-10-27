package com.thenoahnoah.podfs.ui.theme

import android.content.Context
import androidx.core.content.edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

enum class AccentColor(val label: String, val primary: Color, val bright: Color) {
    Blue("Blue", Color(0xFF4DA3FF), Color(0xFF9CCBFF)),
    Amber("Amber", PodAmber, PodAmberBright),
    Teal("Teal", Color(0xFF4DD0C0), Color(0xFF9BE8DC)),
    Green("Green", Color(0xFF6BD968), Color(0xFFA6E89C)),
    Red("Red", Color(0xFFFF6B6B), Color(0xFFFFA3A3)),
    Purple("Purple", Color(0xFFB58CFF), Color(0xFFD2BDFF))
}

// Persisted in the "podfs" preferences alongside the selected volume.
object AccentSettings {
    private const val PREFS = "podfs"
    private const val KEY = "accent_color"

    var selected by mutableStateOf(AccentColor.Blue)
        private set

    fun load(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        selected = AccentColor.entries.firstOrNull { it.name == saved } ?: AccentColor.Blue
    }

    fun select(context: Context, color: AccentColor) {
        selected = color
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, color.name) }
    }
}

@Composable
fun PodFSTheme(content: @Composable () -> Unit) {
    val accent = AccentSettings.selected
    val colorScheme = darkColorScheme(
        primary = accent.primary,
        onPrimary = PodInk,
        secondary = accent.bright,
        background = PodInk,
        surface = PodSurface,
        surfaceVariant = PodSurfaceRaised,
        outline = PodOutline,
        onSurfaceVariant = PodMuted,
        tertiary = Color(0xFF8FD4C8)
    )
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
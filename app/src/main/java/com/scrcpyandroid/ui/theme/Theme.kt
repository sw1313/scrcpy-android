package com.scrcpyandroid.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6B4A),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF2F5D7A),
    background = Color(0xFFF3F6F4),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF14201A),
    onSurface = Color(0xFF14201A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FCB9E),
    onPrimary = Color(0xFF003822),
    secondary = Color(0xFF8EB6D0),
    background = Color(0xFF0E1411),
    surface = Color(0xFF17201B),
    onBackground = Color(0xFFE6EEE9),
    onSurface = Color(0xFFE6EEE9),
)

@Composable
fun ScrcpyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

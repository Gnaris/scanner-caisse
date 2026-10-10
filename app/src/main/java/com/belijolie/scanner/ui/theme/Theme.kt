package com.belijolie.scanner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Primary,
    primaryContainer = PrimaryContainer,
    secondary = Secondary,
    surface = Surface,
    surfaceVariant = SurfaceVariant,
    onSurface = OnSurface,
    error = Danger
)

private val DarkColors = darkColorScheme(
    primary = Primary,
    secondary = Secondary,
    error = Danger
)

@Composable
fun ScannerTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}

package com.sunodl.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Colores tomados del icono: dorado, azul eléctrico y violeta sobre fondo nocturno.
private val Gold = Color(0xFFFFB300)
private val Blue = Color(0xFF3D7BFF)
private val Violet = Color(0xFF9C4DFF)

private val Dark = darkColorScheme(
    primary = Gold, onPrimary = Color(0xFF1A1200),
    secondary = Blue, tertiary = Violet,
    background = Color(0xFF0B0B14), surface = Color(0xFF151524), surfaceVariant = Color(0xFF22223A),
    onBackground = Color(0xFFECECF5), onSurface = Color(0xFFECECF5), onSurfaceVariant = Color(0xFFBDBDD6),
    error = Color(0xFFFF6B6B), errorContainer = Color(0xFF4A1C1C), onErrorContainer = Color(0xFFFFDAD6),
)
private val Light = lightColorScheme(
    primary = Color(0xFF8A5A00), onPrimary = Color.White,
    secondary = Color(0xFF1F56D6), tertiary = Color(0xFF6C2BD9),
    background = Color(0xFFFAF8FF), surface = Color.White, surfaceVariant = Color(0xFFEDEAF6),
)

@Composable
fun SunoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

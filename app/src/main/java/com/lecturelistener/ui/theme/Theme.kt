package com.lecturelistener.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1668E3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E4FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF00696E),
    secondaryContainer = Color(0xFF9CF1F6),
    onSecondaryContainer = Color(0xFF002021),
    surface = Color(0xFFFAF9FF),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44464F),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF0A305F),
    primaryContainer = Color(0xFF284777),
    onPrimaryContainer = Color(0xFFD8E4FF),
    secondary = Color(0xFF80D4DB),
    secondaryContainer = Color(0xFF004F53),
    onSecondaryContainer = Color(0xFF9CF1F6),
    surface = Color(0xFF121318),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    error = Color(0xFFFFB4AB),
)

@Composable
fun LectureTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

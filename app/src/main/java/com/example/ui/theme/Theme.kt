package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = CyanElectric,
    onPrimary = Color(0xFF002029),
    primaryContainer = Color(0xFF004D61),
    onPrimaryContainer = Color(0xFFBBE9FF),
    secondary = BlueNeon,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF00388F),
    onSecondaryContainer = Color(0xFFD6E3FF),
    tertiary = PurpleAccent,
    onTertiary = Color(0xFFFFFFFF),
    background = DarkNavyBase,
    onBackground = TextWhite,
    surface = DarkNavySurface,
    onSurface = TextWhite,
    surfaceVariant = DarkNavySurfaceVariant,
    onSurfaceVariant = TextMuted,
    outline = DarkBorder,
    error = RedDanger,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = Color(0xFF001F2A),
    secondary = BlueDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E4FF),
    onSecondaryContainer = Color(0xFF001C3B),
    tertiary = PurpleAccent,
    onTertiary = Color.White,
    background = LightBg,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder,
    error = RedDanger,
    onError = Color.White
)

@Composable
fun InfiniteSpeedTheme(
    darkTheme: Boolean = true, // Default to sleek futuristic dark theme per brand requirements
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

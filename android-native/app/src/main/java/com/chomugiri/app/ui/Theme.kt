package com.chomugiri.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Accent = Color(0xFF7C5CFF)
val Accent2 = Color(0xFFFFB454)
val BgDark = Color(0xFF0C0C11)
val BgElevated = Color(0xFF15151D)
val BgElevated2 = Color(0xFF1F1F2A)
val BorderCol = Color(0xFF26262F)
val FgMuted = Color(0xFF8D8B9C)
val Success = Color(0xFF5FD9A4)
val Danger = Color(0xFFFF6B6B)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Accent2,
    onSecondary = Color(0xFF231400),
    background = BgDark,
    onBackground = Color(0xFFF1F0F7),
    surface = BgElevated,
    onSurface = Color(0xFFF1F0F7),
    surfaceVariant = BgElevated2,
    onSurfaceVariant = FgMuted,
    outline = BorderCol,
    error = Danger,
)

private val LightColors = lightColorScheme(
    primary = Accent,
    secondary = Accent2,
    error = Danger,
)

private val AppTypography = Typography(
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)

@Composable
fun ChomuGirITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        // The product is designed dark-first; the light scheme only exists as a fallback.
        colorScheme = if (darkTheme) DarkColors else DarkColors,
        typography = AppTypography,
        content = content,
    )
}

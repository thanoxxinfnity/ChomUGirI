package com.chomugiri.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** One full set of app colors — everything the UI needs besides the fixed dark code/terminal panels. */
data class AppColors(
    val accent: Color,
    val accent2: Color,
    val bg: Color,
    val bgElevated: Color,
    val bgElevated2: Color,
    val drawerBg: Color,
    val border: Color,
    val fgMuted: Color,
    val fgPrimary: Color,
    val success: Color,
    val danger: Color,
)

private val DarkPalette = AppColors(
    accent = Color(0xFF7C5CFF),
    accent2 = Color(0xFFFFB454),
    bg = Color(0xFF18181B),
    bgElevated = Color(0xFF212124),
    bgElevated2 = Color(0xFF28282E),
    drawerBg = Color(0xFF121214),
    border = Color(0xFF313136),
    fgMuted = Color(0xFF9C9AA6),
    fgPrimary = Color(0xFFF4F3F7),
    success = Color(0xFF5FD9A4),
    danger = Color(0xFFFF6B6B),
)

private val LightPalette = AppColors(
    accent = Color(0xFFC85A32),
    accent2 = Color(0xFFD9823E),
    bg = Color(0xFFFBF9F5),
    bgElevated = Color(0xFFFFFFFF),
    bgElevated2 = Color(0xFFF3F0E8),
    drawerBg = Color(0xFFF5F2EA),
    border = Color(0xFFE5E5E0),
    fgMuted = Color(0xFF8A8578),
    fgPrimary = Color(0xFF2B2A27),
    success = Color(0xFF3F9E6D),
    danger = Color(0xFFCE4B4B),
)

private val LocalAppColors = staticCompositionLocalOf { DarkPalette }

// Every existing call site (Text(color = FgMuted), Surface(color = BgElevated), etc.) keeps
// compiling unchanged — these read the palette the current theme installed via CompositionLocal.
val Accent: Color @Composable get() = LocalAppColors.current.accent
val Accent2: Color @Composable get() = LocalAppColors.current.accent2
val BgDark: Color @Composable get() = LocalAppColors.current.bg
val BgElevated: Color @Composable get() = LocalAppColors.current.bgElevated
val BgElevated2: Color @Composable get() = LocalAppColors.current.bgElevated2
val DrawerBg: Color @Composable get() = LocalAppColors.current.drawerBg
val BorderCol: Color @Composable get() = LocalAppColors.current.border
val FgMuted: Color @Composable get() = LocalAppColors.current.fgMuted
/** Primary readable text/icon color — use instead of a literal Color.White so light mode stays legible. */
val FgPrimary: Color @Composable get() = LocalAppColors.current.fgPrimary
val Success: Color @Composable get() = LocalAppColors.current.success
val Danger: Color @Composable get() = LocalAppColors.current.danger

private val AppTypography = Typography(
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 19.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)

@Composable
fun ChomuGirITheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.accent, onPrimary = Color.White,
            secondary = palette.accent2, onSecondary = Color(0xFF231400),
            background = palette.bg, onBackground = palette.fgPrimary,
            surface = palette.bgElevated, onSurface = palette.fgPrimary,
            surfaceVariant = palette.bgElevated2, onSurfaceVariant = palette.fgMuted,
            outline = palette.border, error = palette.danger,
        )
    } else {
        lightColorScheme(
            primary = palette.accent, onPrimary = Color.White,
            secondary = palette.accent2, onSecondary = Color.White,
            background = palette.bg, onBackground = palette.fgPrimary,
            surface = palette.bgElevated, onSurface = palette.fgPrimary,
            surfaceVariant = palette.bgElevated2, onSurfaceVariant = palette.fgMuted,
            outline = palette.border, error = palette.danger,
        )
    }
    CompositionLocalProvider(LocalAppColors provides palette) {
        MaterialTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
    }
}

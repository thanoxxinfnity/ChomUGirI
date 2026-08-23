package com.chomugiri.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
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
import androidx.compose.ui.unit.dp
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

/**
 * Cyber-black with neon cyan/purple. The layering is the part that makes it read as depth rather
 * than a flat dark theme: background, card and dialog step up in small increments, with a border
 * light enough to separate panels but not to draw the eye off the content.
 */
private val DarkPalette = AppColors(
    // Neon cyan leads and purple answers it: two accents far enough apart on the wheel to code
    // meaning (cyan = live/primary, purple = secondary) while both staying legible on near-black.
    accent = Color(0xFF00E5FF),
    accent2 = Color(0xFFB57BFF),
    // Deep cyber-black, very slightly blue rather than neutral gray, so the neon reads as glow
    // against it instead of sitting flat. Layers step up in tiny increments — on OLED a large
    // jump between panels looks like a rendering seam, not depth.
    bg = Color(0xFF080810),
    bgElevated = Color(0xFF10101C),
    bgElevated2 = Color(0xFF181828),
    drawerBg = Color(0xFF050509),
    border = Color(0xFF232338),
    fgMuted = Color(0xFF8E8CA8),
    fgPrimary = Color(0xFFF2F1FA),
    success = Color(0xFF3DF5A5),
    danger = Color(0xFFFF5C7A),
)

private val LightPalette = AppColors(
    accent = Color(0xFFB9502A),
    accent2 = Color(0xFFD9823E),
    bg = Color(0xFFFAF8F4),
    bgElevated = Color(0xFFFFFFFF),
    bgElevated2 = Color(0xFFF1EEE4),
    drawerBg = Color(0xFFF4F1E8),
    border = Color(0xFFE3E0D6),
    fgMuted = Color(0xFF847F72),
    fgPrimary = Color(0xFF29281F),
    success = Color(0xFF2F8F5B),
    danger = Color(0xFFC94444),
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
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

@Composable
fun ChomuGirITheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            // Dark ink on both accents, not white: neon cyan and light purple are high-luminance
            // colors, and white text on either fails contrast badly — the exact mistake that makes
            // a neon theme look striking in a mockup and unreadable on a real filled button.
            primary = palette.accent, onPrimary = Color(0xFF04141A),
            secondary = palette.accent2, onSecondary = Color(0xFF150A24),
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
        MaterialTheme(colorScheme = colorScheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

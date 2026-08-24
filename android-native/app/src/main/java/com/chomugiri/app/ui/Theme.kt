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
    /** A touch brighter than [border] — for the top edge of a raised card. See [hairline]. */
    val borderBright: Color,
    val fgMuted: Color,
    val fgPrimary: Color,
    val success: Color,
    val danger: Color,
)

/**
 * Restrained futurism rather than neon. The previous palette (#00E5FF on blue-black) read as
 * gaming/hacker; the same character executed with lower saturation and true-neutral surfaces
 * reads as a product. Three rules hold it together:
 *
 *  - Surfaces are neutral, not tinted. A blue-cast background makes a cyan accent look like it is
 *    bleeding into the page; a neutral one lets it sit on top as a deliberate highlight.
 *  - Steps between layers are small and even (bg -> card -> dialog). Large jumps look like seams
 *    on OLED, not depth.
 *  - Borders are quiet. Premium dark UI separates panels with a barely-there line plus a slightly
 *    brighter top edge (see [hairline]), not with contrast.
 */
private val DarkPalette = AppColors(
    // Cyan-400 rather than pure cyan: same identity, without the electric harshness that makes
    // long-form text next to it feel fatiguing. Violet-400 answers it for secondary emphasis.
    accent = Color(0xFF22D3EE),
    accent2 = Color(0xFFA78BFA),
    bg = Color(0xFF0B0B0F),
    bgElevated = Color(0xFF141419),
    bgElevated2 = Color(0xFF1D1D24),
    drawerBg = Color(0xFF08080B),
    border = Color(0xFF26262F),
    borderBright = Color(0xFF3A3A47),
    fgMuted = Color(0xFF9A9AAB),
    fgPrimary = Color(0xFFF4F4F7),
    success = Color(0xFF34D399),
    danger = Color(0xFFFB7185),
)

/**
 * The light theme used to be a warm cream-and-orange scheme, which shared nothing with the dark
 * one — switching themes looked like switching apps. Same accent family, same neutral discipline,
 * with the accents darkened to the shades that actually pass contrast on white.
 */
private val LightPalette = AppColors(
    accent = Color(0xFF0891B2),
    accent2 = Color(0xFF7C3AED),
    bg = Color(0xFFFBFBFD),
    bgElevated = Color(0xFFFFFFFF),
    bgElevated2 = Color(0xFFF3F3F7),
    drawerBg = Color(0xFFF7F7FA),
    border = Color(0xFFE4E4EC),
    borderBright = Color(0xFFD3D3DE),
    fgMuted = Color(0xFF6B6B7B),
    fgPrimary = Color(0xFF16161D),
    success = Color(0xFF059669),
    danger = Color(0xFFDC2626),
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
val BorderBright: Color @Composable get() = LocalAppColors.current.borderBright
val FgMuted: Color @Composable get() = LocalAppColors.current.fgMuted
/** Primary readable text/icon color — use instead of a literal Color.White so light mode stays legible. */
val FgPrimary: Color @Composable get() = LocalAppColors.current.fgPrimary
val Success: Color @Composable get() = LocalAppColors.current.success
val Danger: Color @Composable get() = LocalAppColors.current.danger

/**
 * Type scale with real hierarchy. The old scale ran 24/20/16/14 with almost no weight contrast, so
 * a heading and a label looked like the same text at different sizes. Headings now carry tighter
 * tracking (optical correction — large text looks loose at the same letter-spacing as body), body
 * keeps generous leading for readability, and labels are small, medium-weight and slightly tracked
 * out, which is what stops them reading as shrunken body text.
 */
private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.35).sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 19.sp, letterSpacing = 0.05.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.15.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(9.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun ChomuGirITheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            // Dark ink on both accents, not white: cyan and light violet are high-luminance
            // colors, and white text on either fails contrast badly — the exact mistake that makes
            // a neon theme look striking in a mockup and unreadable on a real filled button.
            primary = palette.accent, onPrimary = Color(0xFF04212A),
            secondary = palette.accent2, onSecondary = Color(0xFF1B1235),
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

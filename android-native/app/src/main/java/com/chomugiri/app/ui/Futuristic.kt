package com.chomugiri.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.sin

/**
 * A slow aurora drifting behind the whole app: three wide radial glows orbiting on separate
 * periods, so they cross and part without ever repeating in a way the eye can lock onto.
 *
 * Deliberately restrained. The glows sit at very low alpha over the near-black background, which
 * is what keeps this a lit room rather than a light show — heavy enough to feel alive behind the
 * content, faint enough that text on top never loses contrast. Their periods are long (18-31s)
 * and mutually prime-ish so the composite never visibly loops.
 *
 * Cost is three radial gradients per frame with no clipping or layers, which is cheap; the whole
 * thing is one Canvas that never invalidates anything above it.
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "aurora")
    // One shared 0..1 ramp per period, converted to an angle below — cheaper and smoother than
    // animating x/y separately, and it can never drift out of phase with itself.
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(23_000, easing = LinearEasing), RepeatMode.Restart), label = "a")
    val b by t.animateFloat(0f, 1f, infiniteRepeatable(tween(31_000, easing = LinearEasing), RepeatMode.Restart), label = "b")
    val c by t.animateFloat(0f, 1f, infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Restart), label = "c")

    val accent = Accent
    val accent2 = Accent2

    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        // Radii scale with the canvas so the look holds from a small phone to a tablet.
        val r = maxOf(w, h) * 0.75f

        glow(accent.copy(alpha = 0.16f), orbit(w * 0.30f, h * 0.24f, w * 0.26f, h * 0.10f, a), r)
        glow(accent2.copy(alpha = 0.12f), orbit(w * 0.76f, h * 0.34f, w * 0.20f, h * 0.13f, b), r * 0.9f)
        glow(accent.copy(alpha = 0.09f), orbit(w * 0.52f, h * 0.82f, w * 0.24f, h * 0.09f, c), r * 1.05f)
    }
}

/** Position on an ellipse, from a 0..1 phase. */
private fun orbit(cx: Float, cy: Float, rx: Float, ry: Float, phase: Float): Offset {
    val angle = phase * 2f * Math.PI.toFloat()
    return Offset(cx + rx * cos(angle), cy + ry * sin(angle))
}

private fun DrawScope.glow(color: Color, center: Offset, radius: Float) {
    drawCircle(
        // Fading to fully transparent at the edge is what makes these read as light rather than
        // as circles; a hard stop would show a visible rim against the background.
        brush = Brush.radialGradient(
            colors = listOf(color, Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

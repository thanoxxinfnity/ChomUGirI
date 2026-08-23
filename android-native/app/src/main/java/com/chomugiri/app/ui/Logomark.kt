package com.chomugiri.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chomugiri.app.R

/**
 * The one place the ChomuGiri brand mark is drawn. Every screen that shows "this is ChomuGiri"
 * calls this — never a generic Material icon — so the identity is consistent everywhere instead
 * of looking like an unbranded default AI icon.
 */
@Composable
fun Logomark(size: Dp = 40.dp, modifier: Modifier = Modifier, breathe: Boolean = false) {
    // Opt-in, and deliberately slow and shallow: this is for the idle empty state, where a mark
    // that is very subtly alive reads as a product waiting on you. Anywhere it sits next to text
    // in a bar it stays perfectly still, since motion there is just noise.
    val transition = rememberInfiniteTransition(label = "logo")
    val scale by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "logoScale",
    )
    Image(
        painter = painterResource(R.drawable.logo),
        contentDescription = "ChomuGiri",
        modifier = modifier
            .size(size)
            .scale(if (breathe) scale else 1f)
            .clip(RoundedCornerShape(size * 0.28f)),
    )
}

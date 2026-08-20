package com.chomugiri.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chomugiri.app.R

/**
 * The one place the ChomuGirI brand mark is drawn. Every screen that shows "this is ChomuGirI"
 * calls this — never a generic Material icon — so the identity is consistent everywhere instead
 * of looking like an unbranded default AI icon.
 */
@Composable
fun Logomark(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.logo),
        contentDescription = "ChomuGirI",
        modifier = modifier.size(size).clip(RoundedCornerShape(size * 0.28f)),
    )
}

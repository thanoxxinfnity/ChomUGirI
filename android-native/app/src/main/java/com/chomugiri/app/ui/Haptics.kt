package com.chomugiri.app.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Touch feedback, kept deliberately small and typed by intent rather than by waveform, so call
 * sites read as "this was a tap" / "this succeeded" instead of hard-coding a constant each time.
 *
 * Uses the platform's own haptic constants rather than driving the Vibrator directly: those route
 * through the user's system haptic settings, so someone who has turned touch feedback off in
 * Android actually gets silence instead of the app buzzing them anyway. The richer constants only
 * exist from API 30, so each one falls back to the closest older equivalent.
 */
class Haptics(private val view: View) {

    private fun perform(constant: Int) {
        view.performHapticFeedback(constant)
    }

    /** A normal button/row press. */
    fun tap() = perform(HapticFeedbackConstants.VIRTUAL_KEY)

    /** Selecting something from a set — tier chips, tabs, model picker. */
    fun select() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.KEYBOARD_TAP
    )

    /** Sending a message, starting a build — a committing action. */
    fun commit() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.LONG_PRESS
    )

    /** A run finished cleanly, files landed. */
    fun success() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.LONG_PRESS
    )

    /** Something failed — deliberately distinct from success so you can feel the difference. */
    fun failure() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT
        else HapticFeedbackConstants.LONG_PRESS
    )

    /** Expanding/collapsing a panel — lighter than a tap. */
    fun toggle() = perform(HapticFeedbackConstants.KEYBOARD_TAP)
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

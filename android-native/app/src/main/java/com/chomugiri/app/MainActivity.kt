package com.chomugiri.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import com.chomugiri.app.data.AppViewModel
import com.chomugiri.app.ui.AppRoot
import com.chomugiri.app.ui.BgDark
import com.chomugiri.app.ui.ChomuGirITheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by vm.settings.collectAsState()
            val darkTheme = settings.themeMode != "light"
            ChomuGirITheme(darkTheme = darkTheme) {
                // Status/nav bar icons are otherwise stuck light-on-dark from the static manifest
                // theme — that reads as invisible once the in-app toggle switches to the light
                // palette, so keep icon contrast in sync with the same setting that drives it.
                val view = LocalView.current
                SideEffect {
                    val controller = WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = !darkTheme
                    controller.isAppearanceLightNavigationBars = !darkTheme
                }
                // A plain color swap on theme toggle is jarring — cross-fade it instead.
                val bg by animateColorAsState(BgDark, tween(200), label = "bg")
                Surface(Modifier.fillMaxSize(), color = bg) {
                    AppRoot(vm)
                }
            }
        }
    }
}

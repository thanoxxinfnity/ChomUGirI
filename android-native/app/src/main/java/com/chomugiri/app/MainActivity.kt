package com.chomugiri.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.chomugiri.app.data.AppViewModel
import com.chomugiri.app.ui.AppRoot
import com.chomugiri.app.ui.BgDark
import com.chomugiri.app.ui.ChomuGirITheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    /**
     * Asked for once, on launch. It is not for marketing pings — from API 33 a foreground service
     * cannot show its required ongoing notification without it, and that notification is what
     * keeps a build alive while you are in another app. Declining only costs the progress
     * notification; nothing else in the app depends on it.
     */
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ensureNotificationPermission()
        setContent {
            val settings by vm.settings.collectAsState()
            ChomuGirITheme(darkTheme = settings.themeMode != "light") {
                // A plain color swap on theme toggle is jarring — cross-fade it instead.
                val bg by animateColorAsState(BgDark, tween(200), label = "bg")
                Surface(Modifier.fillMaxSize(), color = bg) {
                    AppRoot(vm)
                }
            }
        }
    }
}

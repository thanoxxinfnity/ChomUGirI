package com.chomugiri.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.chomugiri.app.data.AppViewModel
import com.chomugiri.app.ui.AppRoot
import com.chomugiri.app.ui.BgDark
import com.chomugiri.app.ui.ChomuGirITheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ChomuGirITheme {
                Surface(Modifier.fillMaxSize(), color = BgDark) {
                    AppRoot(vm)
                }
            }
        }
    }
}

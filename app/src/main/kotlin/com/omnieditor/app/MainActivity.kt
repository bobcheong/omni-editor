package com.omnieditor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omnieditor.design.OmniTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.hilt.navigation.compose.hiltViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val intentAction = IntentRouter.route(intent)

        setContent {
            val settingsVm: SettingsViewModel = hiltViewModel()
            val themePref by settingsVm.theme.collectAsStateWithLifecycle()
            val dynamicColor by settingsVm.dynamicColor.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themePref) {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }
            OmniTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                OmniNavGraph(initialAction = intentAction)
            }
        }
    }
}

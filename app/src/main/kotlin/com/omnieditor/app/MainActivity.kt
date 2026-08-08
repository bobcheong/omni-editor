package com.omnieditor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.omnieditor.design.OmniTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val intentAction = IntentRouter.route(intent)

        setContent {
            OmniTheme {
                OmniNavGraph(initialAction = intentAction)
            }
        }
    }
}

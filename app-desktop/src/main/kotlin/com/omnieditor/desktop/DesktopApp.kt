package com.omnieditor.desktop

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.omnieditor.design.OmniTheme

@Composable
fun DesktopApp(initialAction: StartAction = StartAction.None) {
    val navigator = remember { DesktopNavigator() }

    // Route initial action
    remember(initialAction) {
        when (initialAction) {
            is StartAction.OpenFile -> navigator.navigate(Screen.Editor(initialAction.path))
            is StartAction.Compare -> navigator.navigate(
                Screen.Compare(initialAction.left, initialAction.right)
            )
            StartAction.None -> {} // stay on home
        }
        true
    }

    OmniTheme {
        when (val screen = navigator.currentScreen) {
            is Screen.Home -> {
                // Placeholder: will wire feature screens in T-08+
                Text("Omni Editor \u2014 Desktop (Home)")
            }
            is Screen.Editor -> {
                // Placeholder: will wire EditorScreen from feature:editor
                Text("Editor: ${screen.filePath}")
            }
            is Screen.Compare -> {
                // Placeholder: will wire CompareScreen from feature:compare
                Text("Compare: ${screen.leftPath} vs ${screen.rightPath}")
            }
            is Screen.Setup -> {
                // Placeholder: will wire SourceSetupScreen from feature:setup
                Text("Compare Setup")
            }
        }
    }
}

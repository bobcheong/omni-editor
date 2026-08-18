package com.omnieditor.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed class Screen {
    data object Home : Screen()
    data class Editor(val filePath: String?) : Screen()
    data class Compare(val leftPath: String, val rightPath: String) : Screen()
    data class Setup(val prefillLeft: String? = null) : Screen()
}

class DesktopNavigator {
    var currentScreen by mutableStateOf<Screen>(Screen.Home)
        private set

    private val backStack = mutableListOf<Screen>()

    fun navigate(screen: Screen) {
        backStack.add(currentScreen)
        currentScreen = screen
    }

    fun back(): Boolean {
        if (backStack.isEmpty()) return false
        currentScreen = backStack.removeAt(backStack.lastIndex)
        return true
    }
}

package com.omnieditor.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val action = parseArgs(args)

    application {
        val settings = DesktopSettings.load()
        val state = rememberWindowState(
            width = settings.windowWidth.dp,
            height = settings.windowHeight.dp,
        )

        Window(
            onCloseRequest = ::exitApplication,
            title = "Omni Editor",
            state = state,
        ) {
            DesktopApp(initialAction = action)
        }
    }
}

sealed class StartAction {
    data object None : StartAction()
    data class OpenFile(val path: String) : StartAction()
    data class Compare(val left: String, val right: String) : StartAction()
}

private fun parseArgs(args: Array<String>): StartAction {
    if (args.isEmpty()) return StartAction.None

    return when (args[0]) {
        "--compare" -> {
            if (args.size < 3) {
                System.err.println("Usage: omnieditor --compare <left> <right>")
                exitProcess(1)
            }
            val left = args[1]
            val right = args[2]
            if (!File(left).exists()) {
                System.err.println("Error: file not found: $left")
                exitProcess(1)
            }
            if (!File(right).exists()) {
                System.err.println("Error: file not found: $right")
                exitProcess(1)
            }
            StartAction.Compare(left, right)
        }
        "--merge" -> {
            System.err.println("Error: --merge not yet implemented")
            exitProcess(1)
        }
        "--help", "-h" -> {
            println("Usage: omnieditor [file]")
            println("       omnieditor --compare <left> <right>")
            exitProcess(0)
        }
        else -> {
            val path = args[0]
            if (!File(path).exists()) {
                System.err.println("Error: file not found: $path")
                exitProcess(1)
            }
            StartAction.OpenFile(path)
        }
    }
}

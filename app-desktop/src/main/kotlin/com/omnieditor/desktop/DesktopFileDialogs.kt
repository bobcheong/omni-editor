package com.omnieditor.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser

object DesktopFileDialogs {
    suspend fun showOpenDialog(title: String = "Open File"): File? = withContext(Dispatchers.IO) {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.FILES_ONLY
        }
        val result = chooser.showOpenDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    suspend fun showSaveDialog(
        suggestedName: String = "document.txt",
        title: String = "Save As",
    ): File? = withContext(Dispatchers.IO) {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            selectedFile = File(suggestedName)
        }
        val result = chooser.showSaveDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }
}

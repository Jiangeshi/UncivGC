package com.unciv.app.desktop

import com.unciv.logic.files.ImagePacker
import com.unciv.ui.screens.modeditor.ModEditorPlatform
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

class DesktopModEditorPlatform : ModEditorPlatform {
    override fun chooseImageFile(): String? {
        val chooser = JFileChooser()
        chooser.dialogTitle = "选择单位图片"
        chooser.fileFilter = FileNameExtensionFilter("PNG 图片 (*.png)", "png")
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
            chooser.selectedFile.absolutePath
        else null
    }

    override fun packAtlases(modFolderPath: String): String? {
        return try {
            ImagePacker.packModAtlases(modFolderPath)
        } catch (e: Throwable) {
            "Pack failed: ${e.message}"
        }
    }
}

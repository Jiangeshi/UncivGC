package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 * 图片上传区块：Choose image… + Remove image + 状态提示。
 *
 * 用法：在表单里调用 [addImageSection]，图片复制到
 * `Images/<子目录>/<文件名>.png`（目录不存在会自动创建）。
 *
 * 各模块图片约定（游戏读取路径 = Images/ 下）：
 * - 单位图标:   UnitIcons/<name>.png
 * - 单位大图:   TileSets/<图集>/Units/<name>.png
 * - 建筑图标:   BuildingIcons/<name>.png
 * - 科技图标:   TechIcons/<name>.png
 * - 政策图标:   PolicyIcons/<name>.png
 * - 晋升图标:   UnitPromotionIcons/<name>.png
 * - 地形贴图:   TileSets/<图集>/Tiles/<name>.png
 * - 国家图标:   NationIcons/<name>.png
 */
class ModEditorImageSection(
    private val modFolder: FileHandle,
    /** 相对 Images/ 的子目录，如 "TechIcons"、"TileSets/FantasyHex/Tiles" */
    private val subDirectory: String,
    /** 文件名（不含扩展名，通常为条目 name） */
    private val fileName: () -> String,
    /** 选择图片前的前置检查；返回非空字符串表示错误信息（阻止选择） */
    private val preCheck: (() -> String?)? = null
) {
    private var statusLabel: Label = "".toLabel(fontSize = 13, fontColor = Color(1f, 1f, 1f, 0.55f))

    /** 当前目标文件（即使文件不存在也返回路径） */
    fun targetFile(): FileHandle =
        modFolder.child("Images/$subDirectory/${fileName()}.png")

    /** 在表单中渲染图片区（一行按钮 + 提示 + 状态） */
    fun addImageSection(table: Table) {
        val row = Table(BaseScreen.skin)
        val chooseButton = "Choose image…".toTextButton()
        chooseButton.onActivation { chooseImage() }
        row.add(chooseButton).pad(6f)
        val removeButton = "Remove image".toTextButton()
        removeButton.onActivation { removeImage() }
        row.add(removeButton).pad(6f)
        val hint = ("The image is copied to Images/".tr() + subDirectory + "/").toLabel(
            fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.5f))
        hint.wrap = true
        row.add(hint).growX().minWidth(180f).left().pad(6f)
        table.add(row).left().row()

        statusLabel = "".toLabel(fontSize = 13, fontColor = Color(1f, 1f, 1f, 0.55f))
        statusLabel.wrap = true
        table.add(statusLabel).growX().left().pad(2f, 8f, 6f, 8f).row()
    }

    private fun chooseImage() {
        val name = fileName()
        if (name.isBlank()) {
            statusLabel.setText("Enter a name first, then choose an image.")
            return
        }
        val check = preCheck?.invoke()
        if (check != null) {
            statusLabel.setText(check)
            return
        }
        val path = ModEditorPlatformHolder.impl?.chooseImageFile() ?: return
        try {
            val dest = targetFile()
            dest.parent().mkdirs()
            Gdx.files.absolute(path).copyTo(dest)
            statusLabel.setText("Image copied to".tr() + ": " + dest.path())
        } catch (e: Exception) {
            statusLabel.setText("Image copy failed:".tr() + " " + (e.message ?: ""))
        }
    }

    private fun removeImage() {
        val file = targetFile()
        if (file.exists()) file.delete()
        statusLabel.setText("Image removed".tr())
    }
}

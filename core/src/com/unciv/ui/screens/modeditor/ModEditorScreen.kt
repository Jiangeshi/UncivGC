package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.unciv.UncivGame
import com.unciv.models.translations.fillPlaceholders
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen

/** S1 模组工作台：选择/新建模组 */
class ModEditorScreen : BaseScreen() {

    private val listTable = Table(BaseScreen.skin)

    init {
        val root = Table(BaseScreen.skin)
        root.setFillParent(true)
        stage.addActor(root)

        val topBar = Table(BaseScreen.skin)
        val backButton = ("‹ " + "Back".tr()).toTextButton()
        backButton.onActivation { game.popScreen() }
        topBar.add(backButton).pad(8f)
        topBar.add("Mod Editor".toLabel(fontSize = 30)).padLeft(20f).expandX().left()
        val newModButton = "New mod".toTextButton()
        newModButton.onActivation { showNewModPopup() }
        topBar.add(newModButton).pad(8f)
        root.add(topBar).fillX().row()

        val scrollPane = AutoScrollPane(listTable)
        scrollPane.setScrollingDisabled(true, false)
        root.add(scrollPane).expand().grow()

        refreshList()
    }

    private fun refreshList() {
        listTable.clear()
        val modsFolder = UncivGame.Current.files.getModsFolder()
        val visibleFolder = UncivGame.Current.getVisibleModsFolder()
        println("[ModEditor] modsFolder=" + modsFolder.path() + " exists=" + modsFolder.exists()
                + " visible=" + (visibleFolder?.path() ?: "null"))

        // 内部 + 外部(可见) 两个目录的 mod 列出; 同名时只保留外部 (外部是源, 内部是自动同步的副本,
        // 编辑外部→保存→自动同步内部→游戏生效, 显示两条反而混淆)
        val entries = ArrayList<Pair<com.badlogic.gdx.files.FileHandle, Boolean>>()  // (modDir, isVisible)
        if (modsFolder.exists()) {
            for (mod in modsFolder.list().filter { it.isDirectory && !it.name().startsWith("temp-") }.sortedBy { it.name() })
                entries.add(mod to false)
        }
        if (visibleFolder != null && visibleFolder.exists() && visibleFolder.path() != modsFolder.path()) {
            for (mod in visibleFolder.list().filter { it.isDirectory && !it.name().startsWith("temp-") }.sortedBy { it.name() }) {
                val idx = entries.indexOfFirst { it.first.name() == mod.name() }
                if (idx >= 0) entries[idx] = mod to true   // 外部同名覆盖内部
                else entries.add(mod to true)
            }
        }
        println("[ModEditor] found mods: " + entries.map { it.first.name() })

        if (entries.isEmpty()) {
            listTable.add("No mods yet. Click \"New mod\" in the top right to get started!".toLabel())
                .pad(20f).row()
        }
        for ((mod, isVisible) in entries) {
            val row = Table(BaseScreen.skin)
            row.defaults().pad(8f)
            row.background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/ModRow", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                BaseScreen.skinStrings.skinConfig.baseColor)
            val nameLabel = mod.name().toLabel(fontSize = 24)
            val info = if (ModEditorData.readIsBaseRuleset(mod)) "Base ruleset mod" else "Extension mod"
            row.add(nameLabel).left().expandX()
            row.add(info.toLabel(fontSize = 16)).right().padRight(12f)
            if (isVisible) {
                row.add("External".toLabel(fontSize = 14,
                    fontColor = com.badlogic.gdx.graphics.Color(1f, 0.8f, 0.4f, 1f))).right().padRight(12f)
            }
            val openButton = "Open".toTextButton()
            openButton.onActivation { game.pushScreen(ModModulesScreen(mod)) }
            row.add(openButton)
            val deleteButton = "Delete".toTextButton()
            // allowEventPropagation=false: 阻止点击冒泡到行的"打开"动作 (否则点删除会先进入编辑界面)
            deleteButton.onActivation(com.unciv.ui.components.input.ActivationTypes.Tap, allowEventPropagation = false) {
                // fillPlaceholders 先填值再 tr — 直接 tr() 会把占位符值也翻译 ([name] 的 name → 名称, replace 失效)
                ConfirmPopup(this,
                    "Delete [name]?".fillPlaceholders(mod.name()).tr() + "\n" + "整个模组文件夹将被删除，无法恢复".tr(),
                    "Delete".tr()) {
                    try {
                        if (mod.exists()) mod.deleteDirectory()
                        refreshList()
                    } catch (e: Exception) {
                        ToastPopup("删除失败: " + (e.message ?: ""), this)
                    }
                }.open(force = true)
            }
            row.add(deleteButton).padLeft(6f)
            row.touchable = Touchable.enabled
            row.onActivation { game.pushScreen(ModModulesScreen(mod)) }
            listTable.add(row).fillX().pad(4f, 12f, 4f, 12f).row()
        }
    }

    private fun showNewModPopup() {
        val popup = Popup(this)
        popup.add("New mod".toLabel(fontSize = 26)).pad(10f).row()

        val nameField = UncivTextField("Mod name (Chinese OK, spaces become dashes)")
        popup.add("Name".toLabel()).left().pad(6f)
        popup.add(nameField).width(420f).row()
        val nameHint = "Chinese names work locally; use English when publishing".toLabel(
            fontSize = 13, fontColor = com.badlogic.gdx.graphics.Color(1f, 1f, 1f, 0.45f))
        popup.add(nameHint).colspan(2).left().pad(2f, 6f, 8f, 6f).row()

        val authorField = UncivTextField("Author (optional)")
        popup.add("Author".toLabel()).left().pad(6f)
        popup.add(authorField).width(420f).row()

        val baseRulesetBox = ModEditorSelectBox(ModEditorData.getBaseRulesetNames(), "Civ V - Gods & Kings", searchable = true)
        popup.add("Base ruleset".toLabel()).left().pad(6f)
        popup.add(baseRulesetBox).width(420f).row()

        val baseRulesetCheckbox = CheckBox(
            "Base ruleset mod (starts from scratch, not based on any ruleset)".tr(), BaseScreen.skin)
        popup.add(baseRulesetCheckbox).colspan(2).left().pad(6f).row()

        baseRulesetCheckbox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                baseRulesetBox.isDisabled = baseRulesetCheckbox.isChecked
            }
        })

        popup.addButton("Create") {
            val rawName = nameField.text.trim()
            val name = rawName.replace(Regex("[ /\\\\:]+|\\.\\."), "-")
            if (name.isEmpty() || name == "." || name == "..") {
                showErrorPopup("Please enter a mod name")
                return@addButton
            }
            println("[ModEditor] create clicked: name=$name isBase=${baseRulesetCheckbox.isChecked}")
            val folder = ModEditorData.getModFolderForEditor(name)
            if (folder.exists() && folder.list().size > 0) {
                showErrorPopup("A mod with this name already exists:".tr() + " " + name)
                return@addButton
            }
            try {
                val isBase = baseRulesetCheckbox.isChecked
                val baseRuleset = baseRulesetBox.selected.value
                ModEditorData.createNewMod(name, authorField.text.trim(), isBase, baseRuleset)
                println("[ModEditor] mod created at ${folder.path()}")
                popup.close()
                refreshList()
                game.pushScreen(ModModulesScreen(folder))
                println("[ModEditor] pushed ModModulesScreen")
            } catch (e: Exception) {
                println("[ModEditor] CREATE FAILED: ${e.stackTraceToString()}")
                showErrorPopup("Creation failed:".tr() + " " + (e.message ?: ""))
            }
        }
        popup.addCloseButton()
        popup.open()
        nameField.keyShortcuts.add(KeyCharAndCode.RETURN)
    }

    private fun showErrorPopup(message: String) {
        val popup = Popup(this)
        popup.add(message.toLabel(fontColor = com.badlogic.gdx.graphics.Color.RED)).pad(12f).row()
        popup.addCloseButton()
        popup.open()
    }
}

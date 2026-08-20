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
import com.unciv.utils.launchOnGLThread

/** S1 模组工作台：选择/新建模组 */
class ModEditorScreen : BaseScreen() {

    private val listTable = Table(BaseScreen.skin)
    // 上传审核状态 (异步加载, refreshList 行内显示标签) — 2026-08-21
    private var pendingStatus: Map<String, com.unciv.logic.lobby.LobbyApi.PendingModStatus> = emptyMap()
    private var mirrorNames: Set<String> = emptySet()

    init {
        loadModStatus()
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
        // UncivGC: 上传至国内镜像 (审核后上架) — 2026-08-21
        val uploadButton = "Upload to mirror".toTextButton()
        uploadButton.onActivation { showUploadPopup() }
        topBar.add(uploadButton).pad(8f)
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
            // 审核/上架状态标签 (2026-08-21)
            val norm = com.unciv.ui.screens.lobbyscreens.LobbyRoomScreen.normName(mod.name())
            when {
                norm in mirrorNames -> row.add("On mirror".toLabel(fontSize = 14,
                    fontColor = com.badlogic.gdx.graphics.Color(0.3f, 0.8f, 0.4f, 1f))).right().padRight(10f)
                pendingStatus[norm]?.status == "pending" -> row.add("Under review".toLabel(fontSize = 14,
                    fontColor = com.badlogic.gdx.graphics.Color(1f, 0.8f, 0.2f, 1f))).right().padRight(10f)
                pendingStatus[norm]?.status == "rejected" -> row.add(
                    ("Rejected" + (pendingStatus[norm]?.reason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
                        .toLabel(fontSize = 14, fontColor = com.badlogic.gdx.graphics.Color(1f, 0.4f, 0.4f, 1f)))
                        .right().padRight(10f)
            }
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

    /** 异步加载上传审核状态 + 镜像清单 (完成后重建列表显示标签) */
    private fun loadModStatus() {
        com.unciv.utils.Concurrency.run("ModStatusRefresh") {
            try {
                val pending = com.unciv.logic.lobby.LobbyApi.pendingModStatus()
                val mirror = com.unciv.logic.lobby.LobbyApi.modMirrorManifest()
                launchOnGLThread {
                    pendingStatus = pending
                    mirrorNames = mirror.map { com.unciv.ui.screens.lobbyscreens.LobbyRoomScreen.normName(it.name) }.toSet()
                    refreshList()
                }
            } catch (e: Exception) {
            }
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

    // ---------- 上传至国内镜像 (2026-08-21) ----------

    /** 列出本地模组供选择 → 令牌 → 确认 → 打包 zip → 流式上传 (进度) → 提示审核中 */
    private fun showUploadPopup() {
        val popup = Popup(this)
        popup.add("Choose a mod to upload".tr().toLabel(fontSize = 22)).pad(10f).row()
        // 上传令牌 (首次设置并记住; 同名更新需相同令牌) — 2026-08-21
        val tokenField = UncivTextField("Upload token (first time: set & remember it)".tr())
        popup.add(tokenField).growX().width(440f).pad(6f).row()
        val listTable = Table()
        val mods = listLocalModDirs()
        if (mods.isEmpty()) {
            popup.add("No mods found".tr().toLabel()).pad(12f).row()
        }
        for (modDir in mods) {
            val row = Table()
            row.defaults().pad(6f)
            row.background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/UploadRow", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                BaseScreen.skinStrings.skinConfig.baseColor)
            row.add(modDir.name().toLabel(fontSize = 18)).left().expandX()
            // GitHub 来源模组 (ModOptions.json 含 modUrl): 由镜像自动从 GitHub 更新, 不提供上传
            if (hasGithubUrl(modDir)) {
                row.add("GitHub auto-updated".tr().toLabel(fontSize = 13,
                    fontColor = com.badlogic.gdx.graphics.Color(0.5f, 0.7f, 1f, 1f))).right().padRight(8f)
            } else {
                val isUpdate = com.unciv.ui.screens.lobbyscreens.LobbyRoomScreen.normName(modDir.name()) in mirrorNames
                val savedToken = uploadTokens().getString(modDir.name(), "")
                if (savedToken.isNotEmpty()) tokenField.text = savedToken
                val btn = (if (isUpdate) "Update" else "Upload").tr().toTextButton()
                btn.onActivation {
                    val token = tokenField.text.trim()
                    if (token.isEmpty()) {
                        ToastPopup("Please set an upload token (remember it for updates)".tr(), this@ModEditorScreen)
                        return@onActivation
                    }
                    uploadTokens().putString(modDir.name(), token).flush()
                    popup.close()
                    startUploadMod(modDir, token)
                }
                row.add(btn)
            }
            row.touchable = Touchable.enabled
            listTable.add(row).fillX().pad(3f, 6f, 3f, 6f).row()
        }
        popup.add(com.unciv.ui.components.widgets.AutoScrollPane(listTable).apply {
            setScrollingDisabled(true, false)
            setHeight(300f)
        }).fillX().pad(6f).row()
        popup.addGoodSizedLabel("The mod will be uploaded for review; after approval it will be listed on the CN mirror".tr()).pad(8f).row()
        popup.addCloseButton()
        popup.open()
    }

    private fun uploadTokens(): com.badlogic.gdx.Preferences =
        com.badlogic.gdx.Gdx.app.getPreferences("uncivgc-upload-tokens")

    /** 模组是否 GitHub 来源 (ModOptions.json 含 modUrl) */
    private fun hasGithubUrl(modDir: com.badlogic.gdx.files.FileHandle): Boolean {
        try {
            val mo = modDir.child("jsons").child("ModOptions.json")
            return mo.exists() && mo.readString().contains("modUrl")
        } catch (e: Exception) {
            return false
        }
    }

    private fun listLocalModDirs(): List<com.badlogic.gdx.files.FileHandle> {
        val out = ArrayList<com.badlogic.gdx.files.FileHandle>()
        val modsFolder = UncivGame.Current.files.getModsFolder()
        val visibleFolder = UncivGame.Current.getVisibleModsFolder()
        if (modsFolder.exists())
            out.addAll(modsFolder.list().filter { it.isDirectory && !it.name().startsWith("temp-") }.sortedBy { it.name() })
        if (visibleFolder != null && visibleFolder.exists() && visibleFolder.path() != modsFolder.path()) {
            for (m in visibleFolder.list().filter { it.isDirectory && !it.name().startsWith("temp-") }) {
                if (out.none { it.name() == m.name() }) out.add(m)
            }
            out.sortBy { it.name() }
        }
        return out
    }

    private fun startUploadMod(modDir: com.badlogic.gdx.files.FileHandle, token: String) {
        val modName = modDir.name()
        // 打包到本地临时 zip (模组编辑器目录, 不占应用沙盒)
        val zipFile = com.badlogic.gdx.Gdx.files.local("temp-mod-upload.zip")
        val loading = Popup(this)
        loading.addGoodSizedLabel("Packing mod...".tr())
        loading.open()
        com.unciv.utils.Concurrency.run("ModUpload") {
            try {
                // 1. 打包
                java.util.zip.ZipOutputStream(zipFile.file().outputStream()).use { zos ->
                    fun addDir(dir: com.badlogic.gdx.files.FileHandle, base: String) {
                        for (child in dir.list().sortedBy { it.name() }) {
                            val entry = if (base.isEmpty()) child.name() else "$base/${child.name()}"
                            if (child.isDirectory) {
                                zos.putNextEntry(java.util.zip.ZipEntry("$entry/"))
                                zos.closeEntry()
                                addDir(child, entry)
                            } else {
                                zos.putNextEntry(java.util.zip.ZipEntry(entry))
                                child.read().use { ins -> ins.copyTo(zos) }
                                zos.closeEntry()
                            }
                        }
                    }
                    addDir(modDir, modDir.name())  // 顶层带模组目录名 (标准 zip 结构, 服务器校验/安装依赖)
                }
                val total = zipFile.file().length()
                if (total > 200L * 1024 * 1024) throw RuntimeException("Mod too large (max 200MB)")
                // 2. 上传 (进度) — 先 tr 再 fill (词条 key 带占位符)
                com.unciv.logic.lobby.LobbyApi.uploadModZipWithToken(modName, token, zipFile.file().absolutePath) { sent, tot ->
                    launchOnGLThread {
                        if (tot > 0) {
                            loading.reuseWith("Uploading: [sent] MB / [total] MB ([pct]%)".tr()
                                .fillPlaceholders(String.format("%.1f", sent / 1048576.0),
                                    String.format("%.1f", tot / 1048576.0),
                                    (sent * 100 / tot).toString()))
                        }
                    }
                }
                // 3. 完成
                launchOnGLThread {
                    loading.close()
                    ToastPopup("Uploaded, pending review".tr(), this@ModEditorScreen)
                    loadModStatus()  // 重新拉审核状态 (上传后立即显示“审核中”)
                }
            } catch (e: Exception) {
                launchOnGLThread {
                    loading.close()
                    ToastPopup("Upload failed: " + (e.message ?: ""), this@ModEditorScreen)
                }
            } finally {
                try {
                    zipFile.delete()
                } catch (ignored: Exception) {
                }
            }
        }
    }
}

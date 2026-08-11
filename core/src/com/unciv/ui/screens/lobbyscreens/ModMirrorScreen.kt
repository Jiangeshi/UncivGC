package com.unciv.ui.screens.lobbyscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.lobby.LobbyApi
import com.unciv.logic.lobby.ModMirrorEntry
import com.unciv.models.translations.tr
import com.unciv.models.ruleset.RulesetCache
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread

/**
 * UncivGC 国内模组镜像: 浏览镜像里的模组 (服务器托管, 国内下载快)
 * 支持: 未装 → 下载; 已装但有新版 → 更新; 已装最新 → 置灰
 * 下载带进度, md5 校验, 覆盖安装; 安装版本记录在 mirror_mod_state.json (进房时据此提示更新)
 */
class ModMirrorScreen : PickerScreen() {

    private var closed = false
    private val modRows = Table()
    private var allEntries: List<ModMirrorEntry> = emptyList()
    private val searchField = UncivTextField("Search mods".tr())

    init {
        setDefaultCloseAction()

        topTable.add(searchField).width(stage.width * 0.55f).padBottom(8f).row()
        searchField.onChange { applyFilter() }
        scrollPane.setScrollingDisabled(false, true)
        topTable.add(AutoScrollPane(modRows)).fill().row()

        rightSideButton.setText("Refresh".tr())
        rightSideButton.onActivation { refresh() }
        refresh()
    }

    private fun refresh() {
        modRows.clearChildren()
        modRows.add("Fetching mirror list...".toLabel()).pad(20f).row()
        Concurrency.run("MirrorRefresh") {
            try {
                val list = LobbyApi.modMirrorManifest()
                launchOnGLThread {
                    allEntries = list
                    applyFilter()
                }
            } catch (e: Exception) {
                launchOnGLThread {
                    if (closed) return@launchOnGLThread
                    modRows.clearChildren()
                    modRows.add("Failed to fetch mirror: [${e.message}]".toLabel()).pad(20f).row()
                }
            }
        }
    }

    /** 按搜索框过滤并渲染列表 (支持名称直接匹配 + 归一化匹配, 搜 "lm2" 也能命中) */
    private fun applyFilter() {
        if (closed) return
        val q = searchField.text.trim().lowercase()
        val normQ = q.filter { it.isLetterOrDigit() }
        val filtered = allEntries.filter { e ->
            q.isEmpty() || e.name.lowercase().contains(q) ||
                LobbyRoomScreen.normName(e.name).contains(normQ)
        }
        renderList(filtered)
    }

    private fun renderList(list: List<ModMirrorEntry>) {
        if (closed) return
        modRows.clearChildren()
        if (list.isEmpty()) {
            modRows.add((if (allEntries.isEmpty()) "The mirror has no mods yet" else "No matching mods").toLabel()).pad(20f).row()
            return
        }
        val installed = LobbyRoomScreen.installedMods().map { LobbyRoomScreen.normName(it) }.toSet()
        val state = LobbyRoomScreen.loadMirrorState()
        for (entry in list) {
            val row = Table()
            row.defaults().pad(5f)
            // 两行布局: 第一行显示名 (连字符→空格), 第二行灰色小字 (大小/版本/状态) — 避免名称与大小重叠
            val info = Table()
            info.defaults().pad(2f)
            info.add(displayName(entry.name).toLabel(fontSize = 20)).left().row()
            info.add(detailText(entry, state).toLabel(fontColor = com.badlogic.gdx.graphics.Color.GRAY, fontSize = 14)).left().row()
            row.add(info).expandX().left()
            val isInstalled = LobbyRoomScreen.normName(entry.name) in installed
            val hasUpdate = isInstalled && state[entry.name] != null && state[entry.name] != entry.version
            val button = when {
                !isInstalled -> "Download".toTextButton()
                hasUpdate -> "Update".toTextButton()
                else -> "Installed".toTextButton().apply { isDisabled = true }
            }
            button.onClick { downloadOrUpdate(entry, hasUpdate) }
            row.add(button).width(90f)
            modRows.add(row).fillX().row()
        }
    }

    /** 显示名: 连字符替换为空格 (Leader-Mission-2-Rising-Power → Leader Mission 2 Rising Power) */
    private fun displayName(name: String) = name.replace("-", " ")

    private fun detailText(entry: ModMirrorEntry, state: Map<String, String>): String {
        val size = if (entry.size > 0) String.format("%.1f MB", entry.size / 1048576.0) else ""
        val local = state[entry.name]
        val status = when {
            local == null -> "Not installed"
            local == entry.version -> "Up to date"
            else -> "Local [${local.take(10)}] -> new version available"
        }
        return listOf(size, status).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private fun downloadOrUpdate(entry: ModMirrorEntry, isUpdate: Boolean) {
        val loading = Popup(this)
        loading.addGoodSizedLabel(if (isUpdate) "Updating [${entry.name}]..." else "Downloading [${entry.name}]...")
        loading.open()
        Concurrency.runOnNonDaemonThreadPool("MirrorInstall") {
            val errs = LobbyRoomScreen.installFromMirror(listOf(entry), listOf(entry.name), this@ModMirrorScreen) { m, p ->
                launchOnGLThread { loading.reuseWith("[$m] $p%", false) }
            }
            launchOnGLThread {
                loading.close()
                if (closed) return@launchOnGLThread
                if (errs.isNotEmpty()) {
                    ToastPopup(errs.joinToString("\n"), this@ModMirrorScreen)
                } else {
                    ToastPopup((if (isUpdate) "[${entry.name}] update complete" else "[${entry.name}] installed").tr(), this@ModMirrorScreen)
                    // 立即重新加载规则集缓存, 游戏里马上能选到这个模组 (否则要重启 App)
                    RulesetCache.loadRulesets()
                }
                refresh()
            }
        }
    }

    override fun dispose() {
        closed = true
        super.dispose()
    }
}

package com.unciv.ui.screens.lobbyscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.lobby.LobbyApi
import com.unciv.logic.lobby.ModMirrorEntry
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
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

    init {
        setDefaultCloseAction()

        scrollPane.setScrollingDisabled(false, true)
        topTable.add(AutoScrollPane(modRows)).fill().row()

        rightSideButton.setText("刷新".tr())
        rightSideButton.onActivation { refresh() }
        refresh()
    }

    private fun refresh() {
        modRows.clearChildren()
        modRows.add("正在获取镜像列表...".toLabel()).pad(20f).row()
        Concurrency.run("MirrorRefresh") {
            try {
                val list = LobbyApi.modMirrorManifest()
                launchOnGLThread { updateList(list) }
            } catch (e: Exception) {
                launchOnGLThread {
                    if (closed) return@launchOnGLThread
                    modRows.clearChildren()
                    modRows.add("获取镜像失败: ${e.message}".toLabel()).pad(20f).row()
                }
            }
        }
    }

    private fun updateList(list: List<ModMirrorEntry>) {
        if (closed) return
        modRows.clearChildren()
        if (list.isEmpty()) {
            modRows.add("镜像里还没有模组".toLabel()).pad(20f).row()
            return
        }
        val installed = LobbyRoomScreen.installedMods().map { LobbyRoomScreen.normName(it) }.toSet()
        val state = LobbyRoomScreen.loadMirrorState()
        for (entry in list) {
            val row = Table()
            row.defaults().pad(5f)
            row.add(entry.name.toLabel(fontSize = 20)).width(280f).left()
            row.add(sizeText(entry).toLabel()).width(80f)
            row.add(versionText(entry, state).toLabel(fontSize = 14)).width(190f)
            val isInstalled = LobbyRoomScreen.normName(entry.name) in installed
            val hasUpdate = isInstalled && state[entry.name] != null && state[entry.name] != entry.version
            val button = when {
                !isInstalled -> "下载".toTextButton()
                hasUpdate -> "更新".toTextButton()
                else -> "已安装".toTextButton().apply { isDisabled = true }
            }
            button.onClick { downloadOrUpdate(entry, hasUpdate) }
            row.add(button).width(90f)
            modRows.add(row).fillX().row()
        }
    }

    private fun sizeText(entry: ModMirrorEntry): String =
        if (entry.size > 0) String.format("%.1f MB", entry.size / 1048576.0) else ""

    private fun versionText(entry: ModMirrorEntry, state: Map<String, String>): String {
        val local = state[entry.name]
        return when {
            local == null -> "未安装"
            local == entry.version -> "已是最新"
            else -> "本地 $local → 新版"
        }
    }

    private fun downloadOrUpdate(entry: ModMirrorEntry, isUpdate: Boolean) {
        val loading = Popup(this)
        loading.addGoodSizedLabel(if (isUpdate) "正在更新 ${entry.name}..." else "正在下载 ${entry.name}...")
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
                    ToastPopup("${entry.name} ${if (isUpdate) "更新" else "安装"}完成", this@ModMirrorScreen)
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

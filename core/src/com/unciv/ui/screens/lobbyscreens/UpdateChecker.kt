package com.unciv.ui.screens.lobbyscreens

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.lobby.LobbyApi
import com.unciv.logic.lobby.UpdateInfo
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread

/**
 * UncivGC 应用内更新检查: 启动进主菜单时后台检查服务器 version,
 * 有新版 → 弹窗下载 (进度) → md5 校验 → 系统安装 (FileProvider).
 * 每进程只提示一次, 避免反复弹窗.
 */
object UpdateChecker {

    private var checkedThisProcess = false

    fun checkAndPrompt(screen: BaseScreen) {
        if (checkedThisProcess) return
        checkedThisProcess = true
        Concurrency.run("UpdateCheck") {
            val info = LobbyApi.checkUpdate() ?: return@run
            val localVersion = com.unciv.UncivGame.UGC_VERSION
            if (info.version.isNotEmpty() && info.version == localVersion) return@run
            launchOnGLThread {
                if (screen.stage.root.isVisible) {
                    ConfirmPopup(
                        screen,
                        "发现新版本 ${info.version}\n\n${info.notes}\n\n是否下载更新？",
                        "下载更新",
                    ) { downloadAndInstall(info, screen) }.open()
                }
            }
        }
    }

    private fun downloadAndInstall(info: UpdateInfo, screen: BaseScreen) {
        val loading = Popup(screen)
        loading.addGoodSizedLabel("正在下载更新 (0 MB)...")
        loading.open()
        Concurrency.run("UpdateDownload") {
            val totalMb = if (info.apkSize > 0) String.format("%.0f", info.apkSize / 1048576.0) else "?"
            val path = LobbyApi.downloadApk { p ->
                launchOnGLThread {
                    val mb = String.format("%.1f", info.apkSize * p / 100.0 / 1048576.0)
                    loading.reuseWith("正在下载更新 ($mb MB / $totalMb MB)...", false)
                }
            }
            launchOnGLThread {
                loading.close()
                if (path == null) {
                    ToastPopup("下载失败，请稍后重试", screen)
                    return@launchOnGLThread
                }
                // md5 校验 (防传输损坏)
                val md5 = LobbyRoomScreen.md5Of(Gdx.files.local(path))
                if (info.apkMd5.isNotEmpty() && md5 != info.apkMd5) {
                    Gdx.files.local(path).delete()
                    ToastPopup("下载校验失败，请重试", screen)
                    return@launchOnGLThread
                }
                ToastPopup("下载完成，正在打开安装...", screen)
                UncivGame.Current.openApkForInstall(Gdx.files.local(path).file().absolutePath)
            }
        }
    }
}

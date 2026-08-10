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
 * 有新版 → 弹窗下载 (App 内 HttpURLConnection, 稳定) → md5 校验 → FileProvider 系统安装.
 * 任何失败都有明确提示, 不静默.
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
        // Android 8+: 先确保「安装未知应用」已授权 — 否则装不了 (华为等 ROM 默认拦截)
        if (!UncivGame.Current.canInstallPackages()) {
            ConfirmPopup(
                screen,
                "下载完成后需要安装更新\n\n请先在系统设置中允许本应用「安装未知应用」\n\n开启后请重新点击「下载更新」",
                "去设置",
            ) { UncivGame.Current.openInstallSettings() }.open()
            return
        }
        // App 内直接下载 (HttpURLConnection — 与 curl 同级, 慢速网络稳定) + FileProvider 安装
        val loading = Popup(screen)
        loading.addGoodSizedLabel("正在下载更新 (0 MB)...")
        loading.open()
        Concurrency.run("UpdateDownload") {
            val totalMb = if (info.apkSize > 0) String.format("%.0f", info.apkSize / 1048576.0) else "?"
            val path = try {
                LobbyApi.downloadApk { received, total ->
                    launchOnGLThread {
                        val mb = String.format("%.1f", received / 1048576.0)
                        val pct = if (total > 0) " (${(received * 100 / total)}%)" else ""
                        loading.reuseWith("正在下载更新 $mb MB / $totalMb MB$pct\n请保持 App 在前台...", false)
                    }
                }
            } catch (e: Exception) {
                null  // 兜底: 任何异常都按下载失败处理, 不崩溃
            }
            // md5 校验放后台线程 (27MB 哈希计算不卡 GL 线程)
            val md5 = if (path != null) LobbyRoomScreen.md5Of(Gdx.files.local(path)) else null
            launchOnGLThread {
                loading.close()
                if (path == null) {
                    ToastPopup("下载失败（网络中断或服务器繁忙），请稍后重试", screen)
                    return@launchOnGLThread
                }
                if (info.apkMd5.isNotEmpty() && md5 != info.apkMd5) {
                    Gdx.files.local(path).delete()
                    ToastPopup("下载校验失败，请重试", screen)
                    return@launchOnGLThread
                }
                ToastPopup("下载完成，正在打开安装...", screen)
                val opened = UncivGame.Current.openApkForInstall(Gdx.files.local(path).file().absolutePath)
                if (!opened) {
                    ToastPopup(
                        "无法自动打开安装界面\n请手动安装: ${Gdx.files.local(path).file().absolutePath}",
                        screen)
                }
            }
        }
    }
}

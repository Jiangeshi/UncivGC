package com.unciv.ui.screens.lobbyscreens

import com.unciv.models.translations.fillPlaceholders
import com.unciv.models.translations.tr
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
                        "New version [${info.version}] available\n\n${info.notes}\n\nDownload the update?".tr(),
                        "Download update".tr(),
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
                "The update needs to be installed after downloading\n\nPlease allow this app to \"Install unknown apps\" in system settings\n\nAfter enabling, tap \"Download update\" again".tr(),
                "Go to settings".tr(),
            ) { UncivGame.Current.openInstallSettings() }.open()
            return
        }
        // App 内直接下载 (HttpURLConnection — 与 curl 同级, 慢速网络稳定) + FileProvider 安装
        val loading = Popup(screen)
        loading.addGoodSizedLabel("Downloading update (0 MB)...".tr())
        loading.open()
        Concurrency.run("UpdateDownload") {
            val totalMb = if (info.apkSize > 0) String.format("%.0f", info.apkSize / 1048576.0) else "?"
            val path = try {
                LobbyApi.downloadApk { received, total ->
                    launchOnGLThread {
                        val mb = String.format("%.1f", received / 1048576.0)
                        val pct = if (total > 0) " (${(received * 100 / total)}%)" else ""
                        loading.reuseWith("Downloading update: [mb] MB / [total] MB[pct]\nKeep the app in the foreground...".fillPlaceholders(mb, totalMb, pct).tr(), false)
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
                    ToastPopup("Download failed (network or server issue), please retry later".tr(), screen)
                    return@launchOnGLThread
                }
                if (info.apkMd5.isNotEmpty() && md5 != info.apkMd5) {
                    Gdx.files.local(path).delete()
                    ToastPopup("Download checksum failed, please retry".tr(), screen)
                    return@launchOnGLThread
                }
                ToastPopup("Download complete, opening installer...".tr(), screen)
                val opened = UncivGame.Current.openApkForInstall(Gdx.files.local(path).file().absolutePath)
                if (!opened) {
                    ToastPopup(
                        "Could not open the installer automatically\nPlease install manually: [${Gdx.files.local(path).file().absolutePath}]".tr(),
                        screen)
                }
            }
        }
    }
}

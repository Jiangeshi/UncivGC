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
            // 1. 接续上次未完成的系统下载 (下载中退出 App / 没装完, 重开也能接上, 不重复下载)
            val pendingId = UncivGame.Current.settings.pendingApkDownloadId
            if (pendingId >= 0) {
                val status = UncivGame.Current.systemDownloadStatus(pendingId)
                when (status) {
                    1 -> {  // 下载完成 → 直接打开安装
                        val installed = com.unciv.utils.withGLContext { UncivGame.Current.openSystemDownload(pendingId) }
                        if (installed) {
                            UncivGame.Current.settings.pendingApkDownloadId = -1
                            UncivGame.Current.settings.save()
                        }
                        return@run
                    }
                    0 -> {  // 还在下载 → 提示查看通知栏, 不重复下载
                        launchOnGLThread { ToastPopup("更新正在下载中，请下拉通知栏查看进度", screen) }
                        return@run
                    }
                    else -> {  // 失败/不存在 → 清除, 走正常检查
                        UncivGame.Current.settings.pendingApkDownloadId = -1
                        UncivGame.Current.settings.save()
                    }
                }
            }
            // 2. 正常版本检查
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
        // Android 8+: 先确保「安装未知应用」已授权 — 否则下载完也弹不出安装界面 (华为等 ROM 默认拦截)
        if (!UncivGame.Current.canInstallPackages()) {
            ConfirmPopup(
                screen,
                "下载完成后需要安装更新\n\n请先在系统设置中允许本应用「安装未知应用」\n\n开启后请重新点击「下载更新」",
                "去设置",
            ) { UncivGame.Current.openInstallSettings() }.open()
            return
        }
        // 优先: Android 系统下载器 (通知栏进度, 断点续传, 慢速网络稳定); 返回 -1 的平台 (桌面) 回退到 Ktor 下载
        val downloadId = UncivGame.Current.enqueueSystemDownload(
            "${LobbyApi.SERVER_URL}/api/download/apk", "UncivGC-${info.version}.apk")
        if (downloadId >= 0) {
            // 记住下载任务 — 中途退出 App 重开后接续处理
            UncivGame.Current.settings.pendingApkDownloadId = downloadId
            UncivGame.Current.settings.save()
            val loading = Popup(screen)
            loading.addGoodSizedLabel("已开始下载更新\n请下拉通知栏查看下载进度\n下载完成后将自动打开安装...")
            loading.open()
            Concurrency.run("UpdateDownload") {
                val deadline = System.currentTimeMillis() + 10 * 60_000
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(2000)
                    val installed = com.unciv.utils.withGLContext { UncivGame.Current.openSystemDownload(downloadId) }
                    if (installed) {
                        UncivGame.Current.settings.pendingApkDownloadId = -1
                        UncivGame.Current.settings.save()
                        com.unciv.utils.withGLContext { loading.close() }
                        return@run
                    }
                }
                UncivGame.Current.settings.pendingApkDownloadId = -1
                UncivGame.Current.settings.save()
                com.unciv.utils.withGLContext {
                    loading.close()
                    ToastPopup("下载超时，请重试", screen)
                }
            }
            return
        }
        // 桌面端: Ktor 下载 + 进度弹窗
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
                        loading.reuseWith("正在下载更新 $mb MB / $totalMb MB$pct\n请保持网络连接...", false)
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
                    ToastPopup("下载失败，请稍后重试", screen)
                    return@launchOnGLThread
                }
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

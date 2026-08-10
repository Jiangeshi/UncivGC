package com.unciv.utils

import java.util.Locale

interface PlatformSpecific {

    /** Notifies player that his multiplayer turn started */
    fun notifyTurnStarted() {}

    /** Android 13+: 申请通知权限 (桌面端无操作) */
    fun requestNotificationPermission() {}

    /** Install system audio hooks */
    fun installAudioHooks() {}

    /** If not null, this is the path to the directory in which to store the local files - mods, saves, maps, etc */
    var customDataDirectory: String?

    /** If the OS localizes all error messages, this should provide a lookup */
    fun getSystemErrorMessage(errorCode: Int): String? = null

    fun getGcCount(): Int

    /** Get system locale, on Android 13+ app-specific locale */
    fun getDefaultLocale(): Locale = Locale.getDefault()

    /** 应用内更新: 打开系统安装界面安装 APK (Android 用 FileProvider; 桌面端无操作) */
    fun openApkForInstall(apkPath: String) {}

    /** 应用内更新: 用系统下载器下载 (Android DownloadManager, 通知栏进度+断点续传; 其他平台返回 -1 = 不支持) */
    fun enqueueSystemDownload(url: String, fileName: String): Long = -1

    /** 应用内更新: 检查系统下载状态; 下载完成 → 打开安装界面并返回 true; 未完成/失败 → false */
    fun openSystemDownload(downloadId: Long): Boolean = false
}

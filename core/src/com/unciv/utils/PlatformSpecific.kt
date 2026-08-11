package com.unciv.utils

import java.util.Locale

interface PlatformSpecific {

    /** Notifies player that his multiplayer turn started */
    fun notifyTurnStarted() {}

    /** Android 13+: 申请通知权限 (桌面端无操作) */
    fun requestNotificationPermission() {}

    /** 应用内更新: 打开系统安装界面安装 APK (Android 用 PackageInstaller; 桌面端返回 false) */
    fun openApkForInstall(apkPath: String): Boolean = false

    /** 应用内更新: 是否允许安装未知应用 (Android 8+; 其他平台默认允许) */
    fun canInstallPackages(): Boolean = true

    /** 应用内更新: 打开系统设置里的「安装未知应用」授权页 */
    fun openInstallSettings() {}

    /** App 从后台恢复时回调 (Android 从设置页返回; 用于权限开启后自动重试安装) */
    fun onAppResume() {}

    /** Android: 把用户可见目录 (外部存储) 的 mods 同步到应用内部目录 (App 实际读取处); 桌面端无操作 */
    fun syncModsFromVisibleToLocal() {}

    /** Android: 把打包生成的图集文件 (game*.png / game*.atlas / Atlases.json) 镜像回用户可见目录; 桌面端无操作 */
    fun mirrorAtlasToVisible(modFolderName: String) {}

    /** Install system audio hooks */
    fun installAudioHooks() {}

    /** If not null, this is the path to the directory in which to store the local files - mods, saves, maps, etc */
    var customDataDirectory: String?

    /** If the OS localizes all error messages, this should provide a lookup */
    fun getSystemErrorMessage(errorCode: Int): String? = null

    fun getGcCount(): Int

    /** Get system locale, on Android 13+ app-specific locale */
    fun getDefaultLocale(): Locale = Locale.getDefault()
}

package com.unciv.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.android.AndroidGraphics
import com.badlogic.gdx.math.Rectangle
import com.unciv.UncivGame
import com.unciv.logic.IdChecker
import com.unciv.logic.event.EventBus
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.utils.Concurrency
import com.unciv.utils.isUUID
import java.util.Locale

class AndroidGame(private val activity: Activity) : UncivGame() {

    /** Android 13+ (API 33): 运行时申请通知权限 — U 原本从不申请, 导致通知被系统静默屏蔽 */
    override fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return  // Android 12 及以下无需运行时权限
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) return
        try {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                9001,
            )
        } catch (e: Exception) {
        }
    }

    /** 用户可见目录 (外部存储 Android/data/包名/files/mods) → 应用内部目录 (App 实际读取处).
     *  必须在图集打包前完成 (同一线程), 避免「外部 mod 还没同步进来就打包」的时序竞态 */
    override fun syncModsFromVisibleToLocal() {
        try {
            val internalModsDir = java.io.File(activity.filesDir, "mods")
            val externalPath = activity.getExternalFilesDir(null) ?: return
            val externalModsDir = java.io.File(externalPath, "mods")
            if (!externalModsDir.exists()) externalModsDir.mkdirs()
            if (externalModsDir.exists()) externalModsDir.copyRecursively(internalModsDir, true)
        } catch (ignored: Exception) {
        }
    }

    /** 打包产物 (game*.png / game*.atlas / Atlases.json) 镜像回用户可见目录 —
     *  否则用户放 mod 的外部文件夹里永远看不到图集 (单向同步只进不出) */
    override fun mirrorAtlasToVisible(modFolderName: String) {
        try {
            val externalPath = activity.getExternalFilesDir(null) ?: return
            val externalModsDir = java.io.File(externalPath, "mods")
            val srcDir = java.io.File(activity.filesDir, "mods/$modFolderName")
            val dstDir = java.io.File(externalModsDir, modFolderName)
            if (!srcDir.isDirectory || !dstDir.isDirectory) return
            val artifacts = srcDir.listFiles()?.filter {
                it.isFile && (it.name.endsWith(".atlas") || it.name.endsWith(".png") || it.name == "Atlases.json")
            } ?: return
            for (f in artifacts) f.copyTo(java.io.File(dstDir, f.name), overwrite = true)
        } catch (ignored: Exception) {
        }
    }

    /** 主动申请「安装未知应用」— 已改为游戏内弹窗引导 (MainMenuScreen ConfirmPopup), 这里只提供判断和跳转 */
    override fun canInstallPackages(): Boolean = try {
        if (Build.VERSION.SDK_INT < 26) true
        else activity.packageManager.canRequestPackageInstalls()
    } catch (e: Exception) {
        true
    }

    /** 打开「安装未知应用」授权页 — 兼容链: 带包名 → 通用页 → 应用详情页 (华为对带包名跳转可能不响应) */
    override fun openInstallSettings() {
        try {
            if (Build.VERSION.SDK_INT < 26) return
            val base = android.content.Intent().addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                activity.startActivity(
                    base.setAction(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(android.net.Uri.parse("package:${activity.packageName}")))
            } catch (e: Exception) {
                try {
                    activity.startActivity(
                        base.setAction(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                } catch (e2: Exception) {
                    activity.startActivity(
                        base.setAction(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.parse("package:${activity.packageName}")))
                }
            }
        } catch (ignored: Exception) {
        }
    }

    /** 应用内更新: FileProvider 分享 APK → 系统安装器弹确认框 — 华为等 ROM 对 PackageInstaller 静默通道拦截,
     *  系统安装界面最稳 (用户手动装 APK 看到的同款界面) */
    override fun openApkForInstall(apkPath: String): Boolean = try {
        val apkFile = java.io.File(apkPath)
        if (!apkFile.exists() || apkFile.length() == 0L) return false
        val uri = androidx.core.content.FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apkFile)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    /** 从设置页返回后: 权限已开且上次安装失败 → 自动重试安装 */
    override fun onAppResume() {
        try {
            val prefs = activity.getSharedPreferences("uncivgc_update", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("install_failed", false) && canInstallPackages()) {
                prefs.edit().putBoolean("install_failed", false).apply()
                val path = prefs.getString("last_apk_path", null)
                if (path != null) openApkForInstall(path)
            }
        } catch (ignored: Exception) {
        }
    }

    private var lastOrientation = activity.resources.configuration.orientation

    fun addScreenObscuredListener() {
        val contentView = (Gdx.graphics as AndroidGraphics).view
        contentView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {

            /** [onGlobalLayout] gets triggered not only when the [View.getWindowVisibleDisplayFrame]
             * changes, but also on other things. So we need to check if that was actually
             * the thing that changed. */
            private var lastFrame: Rect? = null
            private var lastVisibleStage: Rectangle? = null

            override fun onGlobalLayout() {

                if (!isInitialized || screen == null)
                    return

                val currentFrame = Rect()
                contentView.getWindowVisibleDisplayFrame(currentFrame)

                val stage = (screen as BaseScreen).stage
                val horizontalRatio = stage.width / contentView.width
                val verticalRatio = stage.height / contentView.height

                // Android coordinate system has the origin in the top left,
                // while GDX uses bottom left.

                val visibleStage = Rectangle(
                    currentFrame.left * horizontalRatio,
                    (contentView.height - currentFrame.bottom)  * verticalRatio,
                    currentFrame.width() * horizontalRatio,
                    currentFrame.height() * verticalRatio
                )

                if (lastFrame == currentFrame && lastVisibleStage == visibleStage)
                    return
                lastFrame = currentFrame
                lastVisibleStage = visibleStage

                val currentOrientation = activity.resources.configuration.orientation
                if (lastOrientation != currentOrientation) {
                    lastOrientation = currentOrientation
                    return
                }

                Concurrency.runOnGLThread {
                    EventBus.send(UncivStage.VisibleAreaChanged(visibleStage))
                }
            }
        })
    }

    /** This is needed in onCreate _and_ onNewIntent to open links and notifications
     *  correctly even if the app was not running */
    fun setDeepLinkedGame(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) {
            deepLinkedMultiplayerGame = null
        }
        val uri: Uri? = intent.data
        val idParam = uri?.getQueryParameter("id") //legacy game url
        deepLinkedMultiplayerGame = 
            if (idParam != null && idParam.isUUID()) idParam
            else if (IdChecker.isGameDeepLink(uri.toString())) IdChecker.checkAndReturnUuiId(uri.toString())
            else null
    }

    fun isInitializedProxy() = super.isInitialized

    override fun getGcCount(): Int = 
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Debug.getRuntimeStat("art.gc.gc-count").toInt() else 0

    override fun getDefaultLocale(): Locale =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) super.getDefaultLocale()
        else activity.resources.configuration.locales.get(0)
}

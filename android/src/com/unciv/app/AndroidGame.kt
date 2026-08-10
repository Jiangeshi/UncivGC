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

    /** 应用内更新: 最近一次系统下载的目标文件 (FileProvider 分享安装用) */
    private var lastDownloadFile: java.io.File? = null

    /** 应用内更新: PackageInstaller 系统级安装 (应用商店同款 API, 国产 ROM 兼容最好); 记录 APK 路径供失败时兜底 */
    override fun openApkForInstall(apkPath: String): Boolean = try {
        val apkFile = java.io.File(apkPath)
        if (!apkFile.exists() || apkFile.length() == 0L) return false
        activity.getSharedPreferences("uncivgc_update", android.content.Context.MODE_PRIVATE)
            .edit().putString("last_apk_path", apkFile.absolutePath).apply()
        val installer = activity.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId: Int = installer.createSession(params)
        val session: android.content.pm.PackageInstaller.Session = installer.openSession(sessionId)
        val out: java.io.OutputStream = session.openWrite("uncivgc_update", 0, apkFile.length())
        val input = java.io.FileInputStream(apkFile)
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        input.close()
        session.fsync(out)
        out.close()
        // 结果回调 (InstallResultReceiver 静态注册)
        val resultIntent = android.app.PendingIntent.getBroadcast(
            activity, 0, android.content.Intent(activity, InstallResultReceiver::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        session.commit(resultIntent.intentSender)
        true
    } catch (e: Exception) {
        false
    }

    /** 应用内更新: 系统 DownloadManager 下载 (通知栏进度, 断点续传, 慢速网络稳定); 记住目标路径供 FileProvider 分享 */
    override fun enqueueSystemDownload(url: String, fileName: String): Long = try {
        val dm = activity.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val destDir = activity.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
        val destFile = java.io.File(destDir, fileName)
        lastDownloadFile = destFile
        val request = android.app.DownloadManager.Request(android.net.Uri.parse(url)).apply {
            setTitle("UncivGC 更新")
            setDescription(fileName)
            setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(android.net.Uri.fromFile(destFile))
        }
        dm.enqueue(request)
    } catch (e: Exception) {
        -1L
    }

    /** 应用内更新: 下载完成 → FileProvider 分享 APK → 系统安装界面 (比 DownloadManager 的 content uri 兼容性好, 国产 ROM 也弹) */
    override fun openSystemDownload(downloadId: Long): Boolean = try {
        val dm = activity.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val cursor = dm.query(android.app.DownloadManager.Query().setFilterById(downloadId))
        if (!cursor.moveToFirst()) {
            cursor.close()
            return false
        }
        val status = cursor.getInt(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS))
        cursor.close()
        if (status != android.app.DownloadManager.STATUS_SUCCESSFUL) return false
        // 优先用记录的路径; 进程重启后记录丢失 → 从 App 下载目录找最新的更新包
        var file = lastDownloadFile
        if (file == null || !file.exists() || file.length() == 0L) {
            val dir = activity.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            file = dir?.listFiles()?.filter { it.name.startsWith("UncivGC-") && it.extension.equals("apk", true) }
                ?.maxByOrNull { it.lastModified() }
        }
        if (file == null || !file.exists() || file.length() == 0L) return false
        val uri = androidx.core.content.FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
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

    /** 应用内更新: 查询系统下载状态: 1=成功, 0=下载中, 2=失败, 3=不存在 */
    override fun systemDownloadStatus(downloadId: Long): Int = try {
        val dm = activity.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val cursor = dm.query(android.app.DownloadManager.Query().setFilterById(downloadId))
        if (!cursor.moveToFirst()) {
            cursor.close()
            return 3
        }
        val status = cursor.getInt(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS))
        cursor.close()
        when (status) {
            android.app.DownloadManager.STATUS_SUCCESSFUL -> 1
            android.app.DownloadManager.STATUS_FAILED -> 2
            else -> 0
        }
    } catch (e: Exception) {
        3
    }

    /** 应用内更新: Android 8+ 检查「安装未知应用」授权 (华为等 ROM 默认拦截, 不授权则安装界面弹不出) */
    override fun canInstallPackages(): Boolean = try {
        if (Build.VERSION.SDK_INT < 26) true
        else activity.packageManager.canRequestPackageInstalls()
    } catch (e: Exception) {
        true
    }

    /** 应用内更新: 打开「安装未知应用」授权设置页 */
    override fun openInstallSettings() {
        try {
            if (Build.VERSION.SDK_INT < 26) return
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${activity.packageName}")
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        } catch (e: Exception) {
        }
    }

    /** 主动申请「安装未知应用」权限 — 跟通知权限一样, 进主菜单时每进程申请一次 (API 26+ 无运行时弹窗, 直接拉起系统授权页) */
    override fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT < 26) return
        if (canInstallPackages()) return  // 已授权不再打扰
        try {
            android.widget.Toast.makeText(
                activity, "为保证更新能自动安装，请允许本应用安装未知应用",
                android.widget.Toast.LENGTH_LONG).show()
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${activity.packageName}")
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        } catch (e: Exception) {
        }
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

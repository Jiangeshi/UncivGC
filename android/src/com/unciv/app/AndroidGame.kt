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

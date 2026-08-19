package com.unciv.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.unciv.UncivGame
import com.unciv.logic.files.ImagePacker
import com.unciv.ui.screens.modeditor.ModEditorPlatform
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Android 模组编辑器平台实现:
 *  - 图片上传: 系统文件选择器 (ACTION_GET_CONTENT, 图片类型) → 复制到应用缓存 → 返回绝对路径
 *  - 图集打包: core ImagePacker (Android 端自动走 SimpleImagePacker/PixmapPacker), 产物镜像回用户可见目录 */
class AndroidModEditorPlatform(private val activity: Activity) : ModEditorPlatform {

    companion object {
        const val REQUEST_CHOOSE_IMAGE = 48100
        @Volatile
        var pendingImageUri: ((Uri?) -> Unit)? = null

        /** AndroidLauncher.onActivityResult 转发入口 (UI 线程; 只存 uri, IO 由后台线程做) */
        fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode != REQUEST_CHOOSE_IMAGE) return
            val callback = pendingImageUri
            pendingImageUri = null
            try {
                callback?.invoke(if (resultCode == Activity.RESULT_OK) data?.data else null)
            } catch (ignored: Throwable) {
            }
        }
    }

    override fun chooseImageFile(): String? {
        logToFile("chooseImageFile start")
        val latch = CountDownLatch(1)
        val uriRef = AtomicReference<Uri?>()
        pendingImageUri = { uri ->
            logToFile("onActivityResult callback, uri=${uri}")
            uriRef.set(uri)
            latch.countDown()
        }
        try {
            activity.runOnUiThread {
                try {
                    // 用 ACTION_OPEN_DOCUMENT (与原版存档选择器 SaverLoader 一致, 华为上验证过稳定;
                    // ACTION_GET_CONTENT 在部分华为/HarmonyOS 机型返回时进程崩溃)
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        type = "image/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    activity.startActivityForResult(intent, REQUEST_CHOOSE_IMAGE)
                    logToFile("startActivityForResult sent")
                } catch (e: Throwable) {
                    logToFile("startActivityForResult failed: $e")
                    pendingImageUri = null
                    uriRef.set(null)
                    latch.countDown()
                }
            }
            if (!latch.await(120, TimeUnit.SECONDS)) {
                logToFile("chooseImageFile timeout")
                return null
            }
        } catch (e: Throwable) {
            logToFile("chooseImageFile await failed: $e")
            return null
        }
        val uri = uriRef.get() ?: run { logToFile("no uri selected"); return null }
        logToFile("copyUriToCache start, uri=$uri")
        val result = copyUriToCache(uri)
        logToFile("copyUriToCache done: $result")
        return result
    }

    /** 调试日志写到外部可见目录 (Android/data/包名/files/modeditor_debug.log), 闪退后可从文件管理器读取 */
    private fun logToFile(msg: String) {
        try {
            val dir = activity.getExternalFilesDir(null) ?: return
            val f = java.io.File(dir, "modeditor_debug.log")
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            f.appendText("$ts $msg\n")
        } catch (ignored: Throwable) {
        }
    }

    /** content:// URI → 应用缓存目录里的真实文件, 返回绝对路径 (模组编辑器按路径读取/复制) */
    private fun copyUriToCache(uri: Uri): String? {
        return try {
            val displayName = queryDisplayName(uri) ?: "imported.png"
            val dir = File(activity.cacheDir, "modeditor_import").apply { mkdirs() }
            val dest = File(dir, displayName)
            activity.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            dest.absolutePath
        } catch (e: Throwable) {
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (e: Throwable) {
            null
        }
    }

    override fun packAtlases(modFolderPath: String): String? {
        return try {
            val msg = ImagePacker.packModAtlases(modFolderPath)
            // 打包产物 (game*.png/atlas/Atlases.json) 镜像回用户可见目录, 否则用户看不到
            val modName = File(modFolderPath).name
            UncivGame.Current.mirrorAtlasToVisible(modName)
            msg
        } catch (e: Throwable) {
            "Pack failed: ${e.message}"
        }
    }
}

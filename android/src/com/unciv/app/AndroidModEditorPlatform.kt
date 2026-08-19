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
        val latch = CountDownLatch(1)
        val uriRef = AtomicReference<Uri?>()
        pendingImageUri = { uri ->
            uriRef.set(uri)
            latch.countDown()
        }
        try {
            activity.runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
                    activity.startActivityForResult(intent, REQUEST_CHOOSE_IMAGE)
                } catch (e: Throwable) {
                    pendingImageUri = null
                    uriRef.set(null)
                    latch.countDown()
                }
            }
            if (!latch.await(120, TimeUnit.SECONDS)) return null
        } catch (e: Throwable) {
            return null
        }
        // IO 在后台线程 (调用方线程), 不在 UI 线程
        val uri = uriRef.get() ?: return null
        return copyUriToCache(uri)
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

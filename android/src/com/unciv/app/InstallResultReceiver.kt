package com.unciv.app

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** 应用内更新: PackageInstaller 安装结果回调 — 失败时把 APK 复制到公共下载目录, 引导手动安装 (国产 ROM 兜底) */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -1)
        if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS) return
        try {
            val message = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)
            val copied = copyApkToDownloads(context)
            val extra = when {
                copied != null -> "\n已复制到: 文件管理→下载→$copied，点它手动安装"
                else -> "\n请检查: 设置→安全→外部来源应用→UncivGC→允许 后重试"
            }
            android.widget.Toast.makeText(
                context, "更新安装失败${if (message != null) ": $message" else ""}$extra",
                android.widget.Toast.LENGTH_LONG).show()
        } catch (ignored: Exception) {
        }
    }

    /** 把上次下载的 APK 复制到公共下载目录 (MediaStore, Android 10+ 免权限); 返回文件名或 null */
    private fun copyApkToDownloads(context: Context): String? {
        try {
            if (Build.VERSION.SDK_INT < 29) return null
            val prefs = context.getSharedPreferences("uncivgc_update", Context.MODE_PRIVATE)
            val srcPath = prefs.getString("last_apk_path", null) ?: return null
            val src = File(srcPath)
            if (!src.exists() || src.length() == 0L) return null
            val fileName = "UncivGC-更新.apk"
            val resolver = context.contentResolver
            // 删旧的同名文件
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf(fileName), null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${MediaStore.Downloads._ID}=?", arrayOf(id.toString()))
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
            return fileName
        } catch (e: Exception) {
            return null
        }
    }
}

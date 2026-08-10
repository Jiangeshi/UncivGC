package com.unciv.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 应用内更新: PackageInstaller 安装结果回调 (成功无操作; 失败弹系统 Toast 提示) */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -1)
        if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS) return
        try {
            val message = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)
                ?: "安装未完成 (代码 $status)"
            android.widget.Toast.makeText(context, "更新安装失败: $message", android.widget.Toast.LENGTH_LONG).show()
        } catch (ignored: Exception) {
        }
    }
}

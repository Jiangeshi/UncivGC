package com.unciv.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 帧同步对局系统通知栏 (2026-08-21 用户要求): 游戏在后台时的对局事件提醒
 * (暂停/恢复/新回合/解散/掉线/结束)。独立频道, 玩家可在系统设置里单独控制。
 */
object FsNotificationHelper {

    const val CHANNEL_ID = "UNCIV_FS_NOTIFICATION_CHANNEL"
    const val NOTIFICATION_ID = 3  // 与回合检查器 (1/2) 区分

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "帧同步对局", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "帧同步对局事件提醒 (暂停/恢复/新回合/解散/掉线/结束)"
        manager.createNotificationChannel(channel)
    }

    fun show(context: Context, title: String, text: String) {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.uncivnotification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (ignored: Exception) {
        }
    }

    fun cancel(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        } catch (ignored: Exception) {
        }
    }
}

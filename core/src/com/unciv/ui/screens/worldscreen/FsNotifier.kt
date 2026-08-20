package com.unciv.ui.screens.worldscreen

import java.util.concurrent.ConcurrentHashMap

/**
 * 帧同步手机系统通知栏桥 (2026-08-21 用户要求: 帧同步模式下游戏要能触发手机通知栏通知)。
 *
 * - [impl] 由 AndroidLauncher 注入 (桌面/其他平台为 null → 不发通知)
 * - [appInBackground] 由 AndroidLauncher onPause/onResume 维护: 只有游戏在后台时才发,
 *   前台不打扰 (游戏内已有提示/弹窗)
 * - [notify] 带 key 去重: 同一次对局会话里同 key 只发一次 (断线重连/重复广播不刷屏)
 */
object FsNotifier {

    /** Android 实现 (title, text) → 系统通知栏; 由 AndroidLauncher.onCreate 注入 */
    var impl: ((title: String, text: String) -> Unit)? = null

    /** 应用是否在后台 (AndroidLauncher onPause=true / onResume=false) */
    @Volatile
    var appInBackground = false

    private val sentKeys = ConcurrentHashMap.newKeySet<String>()

    /** 发通知: 前台不发; 同 key 只发一次 */
    fun notify(key: String, title: String, text: String) {
        if (!appInBackground) return
        if (!sentKeys.add(key)) return
        impl?.invoke(title, text)
    }

    /** 新对局开始/重连时清空去重集合 (通知 key 按局重置) */
    fun reset() {
        sentKeys.clear()
    }
}

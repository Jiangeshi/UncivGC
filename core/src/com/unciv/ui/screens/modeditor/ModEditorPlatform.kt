package com.unciv.ui.screens.modeditor

/** 平台相关能力（桌面/手机差异），由各平台启动器注册实现 */
interface ModEditorPlatform {
    /** 打开原生文件选择器，返回选中文件的绝对路径；取消返回 null */
    fun chooseImageFile(): String?

    /** 打包图集：把 mod 的 Images 目录打成 game.atlas 等文件，返回状态消息 */
    fun packAtlases(modFolderPath: String): String?
}

/** 后台线程选图 + GL 线程回调 — 直接调 [chooseImageFile] 会阻塞 GL 线程 (latch.await),
 *  安卓上长时间无响应 → ANR → 进程被杀闪退 (无崩溃界面)。所有 UI 调用点必须走这里。 */
fun ModEditorPlatform.chooseImageFileAsync(onResult: (String?) -> Unit) {
    com.unciv.utils.Concurrency.runOnNonDaemonThreadPool("ModEditorPickImage") {
        val path = try { chooseImageFile() } catch (e: Throwable) { null }
        com.badlogic.gdx.Gdx.app.postRunnable { onResult(path) }
    }
}

object ModEditorPlatformHolder {
    var impl: ModEditorPlatform? = null
}

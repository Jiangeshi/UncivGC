package com.unciv.ui.screens.modeditor

/** 平台相关能力（桌面/手机差异），由各平台启动器注册实现 */
interface ModEditorPlatform {
    /** 打开原生文件选择器，返回选中文件的绝对路径；取消返回 null */
    fun chooseImageFile(): String?

    /** 打包图集：把 mod 的 Images 目录打成 game.atlas 等文件，返回状态消息 */
    fun packAtlases(modFolderPath: String): String?
}

object ModEditorPlatformHolder {
    var impl: ModEditorPlatform? = null
}

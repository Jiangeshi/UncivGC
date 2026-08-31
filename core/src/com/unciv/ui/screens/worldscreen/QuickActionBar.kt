package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.screens.basescreen.BaseScreen

/** UncivGC 2026-08-31 用户要求: 顶栏下方一整行快捷工具栏
 *  形态: 与顶栏合并 (同底色, 无分隔), 左右撑满, 高度 = 科技按钮高度 (64)
 *  左组 = TechPolicyDiplomacyButtons (科技/政策/外交/间谍, 原组件, 独立 actor 对齐背景条顶部)
 *  右组 = 撤回/自动/周转/事件/待办/通知/状态 (WorldScreen 组装进 [rightGroup], 本类靠右排布)
 *
 *  ⚠️ 仅实验性 UI 启用 (事件/排行等均实验性功能); 非实验性 UI 保持原版布局 */
class QuickActionBar(private val worldScreen: WorldScreen) : Table() {
    val barHeight = 64f

    /** 右组按钮容器 (WorldScreen 组装按钮后调用) — 手动定位: 右对齐固定, 超宽向左溢出 */
    val rightGroup = Table()

    init {
        // 与顶栏完全同款背景 (WorldScreen/TopBar/StatsTable + 同 tint) — 2026-08-31 用户: 蓝色要和顶栏一致
        val backColor = BaseScreen.skinStrings.skinConfig.baseColor.darken(0.5f)
        background = BaseScreen.skinStrings.getUiBackground(
            "WorldScreen/TopBar/StatsTable", tintColor = backColor)
        touchable = Touchable.childrenOnly  // 背景条自身不响应点击, 但不挡子 actor
        // rightGroup 不参与 Table 布局 — updateLayout 手动定位 (右缘固定, 超宽向左溢出,
        // 状态按钮(最右)任何时候都在屏幕内 — 2026-08-31 用户反馈状态按钮消失)
        addActor(rightGroup)
    }

    fun updateLayout() {
        // 仅实验性 UI 显示工具栏 (非实验性 UI 保持原版布局: 完成回合回右下角 StatusButtons)
        isVisible = com.unciv.GUI.getSettings().experimentalUi
        if (!isVisible) return
        // 2026-08-31 修复: 实验性UI开关切换不重建 WorldScreen — 若状态按钮被 StatusButtons 抢走
        // (非实验性UI时 reparent), 这里强制挂回右组, 保证状态按钮始终在工具栏
        val nt = worldScreen.nextTurnButton
        if (nt.parent !== rightGroup) {
            if (nt.parent != null) nt.remove()
            rightGroup.add(nt).padLeft(4f).height(60f).fillY()
        }
        // 2026-09-01 自检: 撤回按钮同理 — 非实验性UI时被 StatusButtons 拿走 (右下角竖排),
        // 切回实验性UI时强制挂回右组, 避免孤儿
        val ub = worldScreen.undoButton
        if (ub.parent !== rightGroup) {
            if (ub.parent != null) ub.remove()
            rightGroup.add(ub).padLeft(4f).height(60f).align(com.badlogic.gdx.utils.Align.top)
        }
        setSize(worldScreen.stage.width, barHeight)
        // 紧贴顶栏底部, 无分隔 (2026-08-31 用户要求与顶栏合并)
        setPosition(0f, worldScreen.topBar.y - barHeight)
        // 右组: 右缘贴屏幕右缘, 超宽向左溢出; 垂直居中
        rightGroup.pack()
        rightGroup.setPosition(worldScreen.stage.width - rightGroup.width - 6f,
            (barHeight - rightGroup.height) / 2f)
    }
}

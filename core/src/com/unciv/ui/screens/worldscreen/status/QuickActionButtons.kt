package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.setSize
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.FrameSync
import com.unciv.ui.screens.worldscreen.WorldScreen

/**
 * UncivGC 2026-08-31 顶栏下快捷工具栏按钮 (右组):
 * - 待办: 当前待办动作, 点击弹下拉列表 (TodoMenu), 有角标
 * - 事件: 排队事件列表 (实验性UI), 点击弹 EventQueueMenu 下拉, 有角标
 * - 周转: 下一个单位 (原 NextUnit), 显示闲置数「周转 (n)」, 有角标
 * - 通知: 通知栏开关 (蓝=展开, 灰=收起), 复用 NotificationsScroll.isHidden
 * 所有按钮固定高度 60 (与科技按钮同高), 固定宽度按最长文本定
 */

/** 「待办」按钮: 点击弹待办下拉 (TodoMenu); 显示待办数量「待办 (n)」, 无待办置灰 (用户 2026-08-31) */
class TodoButton(private val worldScreen: WorldScreen) : TextButton("Todo".tr(), BaseScreen.skin) {

    init {
        setSize(100f, 60f)
        onActivation {
            TodoMenu(stage, this, worldScreen)
        }
    }

    fun update() {
        val count = NextTurnButton.todoActions.count { it.isChoice(worldScreen) }
        setText("Todo".tr() + if (count > 0) " ($count)" else "")
        isEnabled = count > 0
    }
}

/** 「事件」按钮: 排队事件列表 (实验性UI开启时显示); 有事件亮「事件 (n)」, 无事件置灰 */
class EventButton(private val worldScreen: WorldScreen) : TextButton("Events".tr(), BaseScreen.skin) {

    init {
        setSize(110f, 60f)
        onActivation {
            EventQueueMenu(stage, this, worldScreen)
        }
    }

    fun update() {
        if (!com.unciv.GUI.getSettings().experimentalUi) {
            isVisible = false
            return
        }
        isVisible = true
        val count = worldScreen.pendingQueueEventCount()
        setText("Events".tr() + if (count > 0) " ($count)" else "")
        isEnabled = count > 0
    }
}

/** 「单位」按钮: 下一个单位 — 有闲置单位时跳闲置 (NextUnit), 无闲置时按单位列表轮询;
 *  带闲置数「单位 (n)」; 数字清零仍可点 (用户 2026-08-31) */
class UnitButton(private val worldScreen: WorldScreen) : TextButton("Units".tr(), BaseScreen.skin) {

    init {
        setSize(130f, 60f)
        onActivation {
            if (NextTurnAction.NextUnit.isChoice(worldScreen)) {
                NextTurnAction.NextUnit.action(worldScreen)
            } else {
                cycleAllUnits()
            }
        }
    }

    /** 无闲置单位时: 按单位列表顺序轮询下一个单位 (不管是否闲置) */
    private fun cycleAllUnits() {
        val units = worldScreen.viewingCiv.units.getCivUnits()
            .filter { !it.isDestroyed && it.hasTile() }.toList()
        if (units.isEmpty()) return
        val current = worldScreen.bottomUnitTable.selectedUnit
        val idx = units.indexOfFirst { it === current }
        val next = units[(idx + 1) % units.size]
        worldScreen.mapHolder.setCenterPosition(next.getTile().position, immediately = false, selectUnit = false)
        worldScreen.bottomUnitTable.selectUnit(next)
        worldScreen.shouldUpdate = true  // 2026-08-31 修复: 不置 shouldUpdate 选中不刷新 (用户: 转完闲置后点周转没反应)
    }

    fun update() {
        // 闲置单位数 (复用 NextUnit 的 subText 口径: "[n] units idle" → 提取数字) — 2026-08-31 用户要求保留
        val idle = NextTurnAction.NextUnit.getSubText(worldScreen)
            ?.replace(Regex("\\D+"), "")
        setText("Units".tr() + (idle?.let { " ($it)" } ?: ""))
        // 无闲置也可点 (轮询所有单位) — 2026-08-31 用户要求
        isEnabled = worldScreen.viewingCiv.units.getCivUnits().any { !it.isDestroyed && it.hasTile() }
    }
}

/** 「通知」按钮: 通知栏开关 — 蓝=通知栏展开, 灰=收起 (复用 NotificationsScroll.isHidden) */
class NotifyButton(private val worldScreen: WorldScreen) : TextButton("Notification".tr(), BaseScreen.skin) {

    init {
        setSize(110f, 60f)
        onActivation {
            val scroll = worldScreen.notificationsScroll
            scroll.isHidden = !scroll.isHidden
            update()
        }
    }

    fun update() {
        val hidden = worldScreen.notificationsScroll.isHidden
        setText("Notification".tr())
        // 蓝=展开 (isHidden=false), 灰=收起 (isHidden=true)
        label.color = if (hidden) com.badlogic.gdx.graphics.Color.GRAY
            else com.badlogic.gdx.graphics.Color.SKY
        isEnabled = true
    }
}

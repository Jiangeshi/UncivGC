package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.models.translations.tr
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.popups.ScrollableAnimatedMenuPopup
import com.unciv.ui.screens.worldscreen.WorldScreen

/** UncivGC 2026-08-31: 「待办」按钮下拉列表 — 列出当前所有待办动作 (选择建造/科技/政策/万神殿/宗教/议会/自动化),
 *  点击直接执行对应动作 (处理完一个, 列表自动刷新显示剩余) */
class TodoMenu(
    stage: Stage,
    anchor: com.badlogic.gdx.scenes.scene2d.Actor,
    private val worldScreen: WorldScreen
) : ScrollableAnimatedMenuPopup(stage, anchor, com.badlogic.gdx.utils.Align.bottomLeft) {

    init {
        afterCloseCallback = { worldScreen.shouldUpdate = true }
    }

    override fun createScrollableContent(): Table? {
        val table = Table()
        table.defaults().pad(5f, 15f, 5f, 15f).growX()
        // 固定宽度让条目左右填满 (2026-08-31 用户: 下拉框元素应填满, 不居中)
        var any = false
        for (action in NextTurnButton.todoActions) {
            if (!action.isChoice(worldScreen)) continue
            table.add(com.badlogic.gdx.scenes.scene2d.ui.TextButton(
                action.getText(worldScreen).tr(), com.unciv.ui.screens.basescreen.BaseScreen.skin
            ).apply {
                onActivation {
                    action.action(worldScreen)
                    close()
                }
            }).width(260f).row()
            any = true
        }
        return if (any) table else null
    }

    override fun createFixedContent(): Table? = null
}

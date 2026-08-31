package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Disposable
import com.unciv.GUI

/** 右下角状态区按钮组。
 *  实验性 UI: 完成回合/待办/事件/周转/撤回 移入顶栏快捷工具栏 (QuickActionBar), 这里只剩
 *  小单位/自动回合/多人状态; 非实验性 UI: 原版布局 (含完成回合 + 撤回按钮) */
class StatusButtons(
    private val nextTurnButton: NextTurnButton,
    private val undoButton: UndoButton? = null
) : Table(), Disposable {
    var autoPlayStatusButton: AutoPlayStatusButton? = null
    var multiplayerStatusButton: MultiplayerStatusButton? = null
    var smallUnitButton: SmallUnitButton? = null
    private val padXSpace = 10f
    private val padYSpace = 5f

    init {
        add(nextTurnButton)
    }

    fun update(verticalWrap: Boolean) {
        clear()
        if(verticalWrap) {
            if (!GUI.getSettings().experimentalUi) add(nextTurnButton)
            smallUnitButton?.let {
                row()
                add(it).padTop(padYSpace).right()
            }
            autoPlayStatusButton?.let {
                row()
                add(it).padTop(padYSpace).right()
            }
            // 撤回按钮仅非实验性UI显示 (实验性UI在顶栏快捷工具栏) — 2026-09-01 自检
            if (!GUI.getSettings().experimentalUi) {
                undoButton?.let {
                    row()
                    add(it).padTop(padYSpace).right()
                }
            }
            multiplayerStatusButton?.let {
                row()
                add(it).padTop(padYSpace).right()
            }
        } else {
            if (!GUI.getSettings().experimentalUi) {
                multiplayerStatusButton?.let { add(it).padRight(padXSpace).top() }
                // 原版: 自动回合 + 撤回按钮竖排成一组 (2026-09-01 自检: 非实验性UI撤回按钮要恢复)
                if (autoPlayStatusButton != null || undoButton != null) {
                    val group = Table()
                    autoPlayStatusButton?.let {
                        group.add(it).row()
                    }
                    undoButton?.let {
                        group.add(it).padTop(padYSpace).row()
                    }
                    add(group).padRight(padXSpace).top()
                }
                smallUnitButton?.let { add(it).padRight(padXSpace).top() }
                add(nextTurnButton)
            } else {
                multiplayerStatusButton?.let { add(it).padRight(padXSpace).top() }
                autoPlayStatusButton?.let { add(it).padRight(padXSpace).top() }
                smallUnitButton?.let { add(it).padRight(padXSpace).top() }
            }
        }
        pack()
    }

    override fun dispose() {
        autoPlayStatusButton?.dispose()
        multiplayerStatusButton?.dispose()
    }
}

package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Disposable

class StatusButtons(
    val nextTurnButton: NextTurnButton
) : Table(), Disposable {
    var autoPlayStatusButton: AutoPlayStatusButton? = null
    var undoButton: UndoButton? = null
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
            add(nextTurnButton)
            smallUnitButton?.let {
                row()
                add(it).padTop(padYSpace).right()
            }
            autoPlayStatusButton?.let {
                row()
                add(it).padTop(padYSpace).right()
            }
            undoButton?.let {
                row()
                add(it).padTop(padYSpace).right()
            }
            multiplayerStatusButton?.let {
                row()
                add(it).padTop(padYSpace).right()
            }
        } else {
            multiplayerStatusButton?.let { add(it).padRight(padXSpace).top() }
            // 横排: 自动回合按钮 + 撤回按钮竖排成一组, 紧挨过回合按钮左边
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
        }
        pack()
    }

    fun updateUndoButton() {
        undoButton?.update()
    }

    override fun dispose() {
        autoPlayStatusButton?.dispose()
        multiplayerStatusButton?.dispose()
    }
}

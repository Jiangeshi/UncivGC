package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen

/** UncivGC 撤回按钮: 撤销上一步 (快照回退, 可连续), 只在自己回合内显示/可用 */
class UndoButton(private val worldScreen: WorldScreen) : Button(BaseScreen.skin) {

    init {
        add(Label("撤回", BaseScreen.skin)).pad(5f)
        onActivation {
            if (!worldScreen.undoManager.hasSnapshot) return@onActivation
            ConfirmPopup(
                worldScreen,
                "撤回上一步？可连续点击继续回退（仅影响本回合内未上传的操作）。",
                "撤回",
            ) {
                worldScreen.undoManager.undo()
            }.open()
        }
    }

    fun update() {
        // 联机局 (含大厅) 撤回会回退到旧快照, 与服务器回合状态可能错位 (出现"等待自己"卡死) — 暂只支持单机
        isVisible = worldScreen.isPlayersTurn && !worldScreen.gameInfo.gameParameters.isOnlineMultiplayer
        isDisabled = !worldScreen.undoManager.hasSnapshot
    }
}

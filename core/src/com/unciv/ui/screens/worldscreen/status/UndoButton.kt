package com.unciv.ui.screens.worldscreen.status

import com.unciv.models.translations.tr
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.FrameSync
import com.unciv.ui.screens.worldscreen.WorldScreen

/** UncivGC 撤回按钮: 撤销上一步 (快照回退, 可连续), 只在自己回合内显示/可用 */
class UndoButton(private val worldScreen: WorldScreen) : Button(BaseScreen.skin) {

    init {
        add(Label("Undo".tr(), BaseScreen.skin)).pad(5f)
        onActivation {
            if (!worldScreen.undoManager.hasSnapshot) return@onActivation
            ConfirmPopup(
                worldScreen,
                "Undo the last move? Tap repeatedly to keep undoing (only affects actions not yet uploaded this turn).".tr(),
                "Undo".tr(),
            ) {
                worldScreen.undoManager.undo()
            }.open()
        }
    }

    fun update() {
        // 撤回范围限制在本回合内 (快照栈在回合切换时清空) — 单机/联机都安全
        // UncivGC 帧同步 (同时回合): 服务器权威, 禁止撤回
        isVisible = worldScreen.isPlayersTurn && !FrameSync.isFsMode(worldScreen.gameInfo)
        isDisabled = !worldScreen.undoManager.hasSnapshot
    }
}

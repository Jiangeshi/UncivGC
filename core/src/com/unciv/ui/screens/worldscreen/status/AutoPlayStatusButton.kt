package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Disposable
import com.unciv.models.translations.tr
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onRightClick
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen

/** UncivGC 2026-08-31: 自动回合按钮改为文本按钮 (与其他工具栏按钮一致, 用户要求替代贴图) */
class AutoPlayStatusButton(
    val worldScreen: WorldScreen,
    nextTurnButton: NextTurnButton
) : TextButton("Auto".tr(), BaseScreen.skin), Disposable {

    init {
        setSize(90f, 60f)
        onActivation(binding = KeyboardBinding.AutoPlayMenu) {
            if (worldScreen.autoPlay.isAutoPlaying())
                worldScreen.autoPlay.stopAutoPlay()
            else if (worldScreen.isPlayersTurn)
                AutoPlayMenu(stage, this, nextTurnButton, worldScreen)
        }
        val directAutoPlay = {
            if (!worldScreen.gameInfo.gameParameters.isOnlineMultiplayer
                && worldScreen.viewingCiv == worldScreen.gameInfo.currentPlayerCiv) {
                worldScreen.autoPlay.startMultiturnAutoPlay()
                nextTurnButton.update()
            }
        }
        onRightClick(action = directAutoPlay)
        keyShortcuts.add(KeyboardBinding.AutoPlay, action = directAutoPlay)
    }

    override fun dispose() {
        if (isPressed && worldScreen.autoPlay.isAutoPlaying()) {
            worldScreen.autoPlay.stopAutoPlay()
        }
    }
}

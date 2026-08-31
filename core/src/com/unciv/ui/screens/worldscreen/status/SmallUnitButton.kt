package com.unciv.ui.screens.worldscreen.status

import com.unciv.models.translations.tr
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.setSize
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.images.IconTextButton
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.worldscreen.WorldScreen

const val nextLabel = "Cycle"
const val skipLabel = "Skip"

class SmallUnitButton(
    private val worldScreen: WorldScreen,
    private val statusButtons: StatusButtons
) : IconTextButton("", null, fontColor = NextTurnAction.NextUnit.color) {

    private var isSkip = worldScreen.game.settings.checkForDueUnitsCycles
    
    init {
        onActivation { 
            worldScreen.switchToNextUnit(resetDue = isSkip)
        }
    }

    fun update() {
        keyShortcuts.clear()
        isSkip = worldScreen.game.settings.checkForDueUnitsCycles // refresh value
        if(isSkip) {
            label.setText(skipLabel.tr())
            iconCell.setActor(ImageGetter.getImage("OtherIcons/Skip").apply { setSize(20f) })
            //keyShortcuts.add(KeyboardBinding.Skip) // don't double binding
            addTooltip(KeyboardBinding.Skip)
        } else {
            label.setText(nextLabel.tr())
            iconCell.setActor(ImageGetter.getImage("OtherIcons/Loading").apply { setSize(20f) })
            keyShortcuts.add(KeyboardBinding.Cycle)
            addTooltip(KeyboardBinding.Cycle)
        }
        // UncivGC 2026-08-31: 完成回合按钮不再显示 NextUnit — 改用「周转」按钮判断闲置单位;
        // 实验性UI下工具栏已有「周转」, 右下小单位按钮隐藏避免重复 (用户反馈)
        val unitButton = worldScreen.unitButton
        val visible = worldScreen.game.settings.smallUnitButton
            && !com.unciv.GUI.getSettings().experimentalUi
            && unitButton.isVisible
            && unitButton.isEnabled
            && worldScreen.bottomUnitTable.selectedUnit != null
        statusButtons.smallUnitButton = if (visible) this else null
        isEnabled = visible && unitButton.isEnabled
            && worldScreen.bottomUnitTable.selectedUnit?.run { due && isIdle() } == true
        pack()
    }

}

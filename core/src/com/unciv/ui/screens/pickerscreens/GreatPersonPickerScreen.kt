package com.unciv.ui.screens.pickerscreens

import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.translations.tr
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onDoubleClick
import com.unciv.ui.screens.worldscreen.WorldScreen

class GreatPersonPickerScreen(val worldScreen: WorldScreen, val civInfo: Civilization) : PickerScreen() {
    private var theChosenOne: BaseUnit? = null

    init {
        worldScreen.autoPlay.stopAutoPlay()
        closeButton.isVisible = false
        rightSideButton.setText("Choose a free great person".tr())

        val greatPersonUnits = civInfo.greatPeople.getGreatPeople()
        val useMayaLongCount = civInfo.greatPeople.mayaLimitedFreeGP > 0

        for (unit in greatPersonUnits) {
            val button =
                PickerPane.getPickerOptionButton(ImageGetter.getUnitIcon(unit), unit.name)
            button.pack()
            button.isEnabled = !useMayaLongCount || unit.name in civInfo.greatPeople.longCountGPPool
            if (button.isEnabled) {
                button.onClick {
                    theChosenOne = unit
                    pick("Get [${unit.name}]".tr())
                    descriptionLabel.setText(unit.getShortDescription())
                }

                button.onDoubleClick(UncivSound.Choir) { confirmAction(useMayaLongCount) }
            }
            topTable.add(button).pad(10f).row()
        }

        rightSideButton.onClick(UncivSound.Choir) {
            confirmAction(useMayaLongCount)
        }

    }

    private fun confirmAction(useMayaLongCount: Boolean) {
        // UncivGC 帧同步: 服务器权威 — 本地不执行 (原版 addUnit + 计数-- 只在本地,
        // 服务器不知情 → 下次全量同步删掉伟人/恢复计数 → 界面反复弹出)。发 op 等广播:
        // freeGreatPeople 归零同步后自动关页 (FrameSync 检测计数变化关 GreatPersonPickerScreen)
        if (com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(civInfo.gameInfo)) {
            val theChosen = theChosenOne ?: return
            com.unciv.ui.screens.worldscreen.FrameSync.sendOp(
                "greatPerson.pick", mapOf("unitName" to theChosen.name))
            return
        }
        civInfo.units.addUnit(theChosenOne!!, civInfo.getCapital())
        civInfo.greatPeople.freeGreatPeople--
        if (useMayaLongCount) {
            civInfo.greatPeople.mayaLimitedFreeGP--
            civInfo.greatPeople.longCountGPPool.remove(theChosenOne!!.name)
        }
        UncivGame.Current.popScreen()
    }
}

package com.unciv.ui.screens.pickerscreens

import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr

class PantheonPickerScreen(
    choosingCiv: Civilization
) : ReligionPickerScreenCommon(choosingCiv) {
    private var selectedPantheon: Belief? = null
    private val selection = Selection()

    init {
        topTable.defaults().pad(10f).fillX()

        for (belief in ruleset.beliefs.values) {
            if (belief.type != BeliefType.Pantheon) continue
            val beliefButton = getBeliefButton(belief, withTypeLabel = false)
            if (choosingCiv.religionManager.getReligionWithBelief(belief) == null && beliefIsAllowed(belief, choosingCiv)) {
                beliefButton.onClickSelect(selection, belief) {
                    selectedPantheon = belief
                    pick("Follow [${belief.name}]".tr())
                }
            } else {
                beliefButton.disable(redDisableColor)
            }
            topTable.add(beliefButton).row()
        }

        setOKAction("Choose a pantheon") {
            // UncivGC 帧同步: 服务器权威 (防重载回滚)
            if (com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(choosingCiv.gameInfo)) {
                com.unciv.ui.screens.worldscreen.FrameSync.sendOp("civ.chooseBeliefs", mapOf(
                    "beliefs" to listOf(selectedPantheon!!.name),
                    "free" to usingFreeBeliefs()))
            } else {
                chooseBeliefs(listOf(selectedPantheon!!), useFreeBeliefs = usingFreeBeliefs())
            }
        }
    }
    fun beliefIsAllowed(belief: Belief, choosingCiv: Civilization): Boolean {
        if (belief.getMatchingUniques(UniqueType.OnlyAvailable, GameContext.IgnoreConditionals)
                .any { !it.conditionalsApply(choosingCiv.state) })
            return false
        if (belief.getMatchingUniques(UniqueType.Unavailable, choosingCiv.state).any())
            return false
        return true
    }
}

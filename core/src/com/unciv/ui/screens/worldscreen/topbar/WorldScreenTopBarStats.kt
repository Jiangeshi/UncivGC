package com.unciv.ui.screens.worldscreen.topbar

import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.logic.civilization.Civilization
import com.unciv.models.stats.Stats
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.colorFromRGB
import com.unciv.ui.components.extensions.setFontColor
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toStringSigned
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.ScalingTableWrapper
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.victoryscreen.RankingType
import com.unciv.ui.screens.overviewscreen.EmpireOverviewCategories
import com.unciv.ui.screens.overviewscreen.EmpireOverviewScreen
import com.unciv.ui.screens.pickerscreens.PolicyPickerScreen
import com.unciv.ui.screens.pickerscreens.TechPickerScreen
import kotlin.math.ceil
import kotlin.math.roundToInt

internal class WorldScreenTopBarStats(topbar: WorldScreenTopBar) : ScalingTableWrapper() {

    private val goldLabel = "0".toLabel(colorFromRGB(225, 217, 71)) // #ed1947
    private val goldPerTurnLabel = "+0"
        .toLabel(colorFromRGB(225, 217, 71), 14)

    private val scienceLabel = "0".toLabel(colorFromRGB(78, 140, 151)) // #4e8c97
    private val happinessLabel = "0".toLabel()
    private val cultureLabel = "0".toLabel(colorFromRGB(210, 94, 210)) // #d25ed2

    private val faithLabel = "0".toLabel(colorFromRGB(168, 196, 241)) // #a8c4f1
    private val faithPerTurnLabel = "+0"
        .toLabel(colorFromRGB(168, 196, 241), 14)

    // UncivGC 实验性 UI (2026-08-22): 领土数量 + 人口数量 (信仰后; 人口用工作地块小人图标)
    private val territoryLabel = "0".toLabel(colorFromRGB(141, 178, 122)) // 绿
    private val populationLabel = "0".toLabel(colorFromRGB(230, 190, 120)) // 橙
    private val territoryIconCell: com.badlogic.gdx.scenes.scene2d.ui.Container<com.badlogic.gdx.scenes.scene2d.ui.Image> = com.badlogic.gdx.scenes.scene2d.ui.Container()
    private val populationIconCell: com.badlogic.gdx.scenes.scene2d.ui.Container<com.badlogic.gdx.scenes.scene2d.ui.Image> = com.badlogic.gdx.scenes.scene2d.ui.Container()

    private val happinessContainer = Group()

    // These are all to improve performance IE reduce update time (was 150 ms on my phone, which is a lot!)
    private val malcontentColor = colorFromRGB(239,83,80) // Color.valueOf("ef5350")
    private val happinessColor = colorFromRGB(92, 194, 77) // Color.valueOf("8cc24d")
    private val malcontentImage = ImageGetter.getStatIcon("Malcontent")
    private val happinessImage = ImageGetter.getStatIcon("Happiness")

    private val worldScreen = topbar.worldScreen


    companion object {
        const val defaultImageSize = 20f
        const val defaultHorizontalPad = 3f
        const val defaultTopPad = 8f
        const val defaultBottomPad = 3f
        const val defaultImageBottomPad = 6f
        const val padRightBetweenStats = 20f
    }

    init {
        isTransform = false

        defaults().pad(defaultTopPad, defaultHorizontalPad, defaultBottomPad, defaultHorizontalPad)

        fun addStat(
            icon: String,
            label: Label,
            noPad: Boolean = false,
            screenFactory: () -> BaseScreen?
        ) {
            val image = ImageGetter.getStatIcon(icon)
            val action = {
                val screen = screenFactory()
                if (screen != null) worldScreen.game.pushScreen(screen)
            }
            label.onClick(action)
            image.onClick(action)
            add(image).padBottom(defaultImageBottomPad).size(defaultImageSize)
            add(label).padRight(if (noPad) 0f else padRightBetweenStats)
        }

        fun addStat(
            icon: String,
            label: Label,
            overviewPage: EmpireOverviewCategories,
            noPad: Boolean = false
        ) = addStat(icon, label, noPad) {
            EmpireOverviewScreen(worldScreen.selectedCiv, overviewPage)
        }

        fun addPerTurnLabel(label: Label) {
            add(label).padRight(padRightBetweenStats)
                .height(Constants.defaultFontSize.toFloat()).top()
        }


        addStat("Gold", goldLabel, EmpireOverviewCategories.Stats, true)
        addPerTurnLabel(goldPerTurnLabel)

        addStat("Science", scienceLabel) { TechPickerScreen(worldScreen.selectedCiv) }

        val invokeResourcesPage = {            worldScreen.openEmpireOverview(EmpireOverviewCategories.Resources)
        }
        happinessContainer.onClick(invokeResourcesPage)
        happinessLabel.onClick(invokeResourcesPage)
        add(happinessContainer).padBottom(defaultImageBottomPad).size(defaultImageSize)
        add(happinessLabel).padRight(padRightBetweenStats)

        addStat("Culture", cultureLabel) {
            if (worldScreen.gameInfo.ruleset.policyBranches.isEmpty()) null
            else PolicyPickerScreen(worldScreen.selectedCiv, worldScreen.canChangeState)
        }

        if (worldScreen.gameInfo.isReligionEnabled()) {
            addStat("Faith", faithLabel, EmpireOverviewCategories.Religion, true)
            addPerTurnLabel(faithPerTurnLabel)
        } else add("Religion: Off".toLabel())

        // UncivGC 实验性 UI (2026-08-22): 领土 + 人口 (信仰后, 图标/数字, 开关控制显示)
        val invokeStatsPage = { worldScreen.openEmpireOverview(EmpireOverviewCategories.Stats) }
        val territoryIcon = ImageGetter.getImage("OtherIcons/Hexagon").apply { onClick(invokeStatsPage) }
        val populationIcon = ImageGetter.getImage("TileIcons/Worked").apply { onClick(invokeStatsPage) }
        territoryLabel.onClick(invokeStatsPage)
        populationLabel.onClick(invokeStatsPage)
        territoryIconCell.actor = territoryIcon
        populationIconCell.actor = populationIcon
        territoryLabel.isVisible = false
        populationLabel.isVisible = false
        add(territoryIconCell).padBottom(defaultImageBottomPad).size(defaultImageSize)
        add(territoryLabel).padRight(padRightBetweenStats)
        add(populationIconCell).padBottom(defaultImageBottomPad).size(defaultImageSize)
        add(populationLabel).padRight(padRightBetweenStats)
    }

    fun update(civInfo: Civilization) {
        resetScale()

        val nextTurnStats = civInfo.stats.statsForNextTurn

        goldLabel.setText(civInfo.gold.tr())
        goldPerTurnLabel.setText(rateLabel(nextTurnStats.gold))

        scienceLabel.setText(if (GUI.getSettings().experimentalUi)
            getScienceText(civInfo, nextTurnStats)  // UncivGC 实验性 UI: 科技显示 +x (剩余回合数), 与文化一致
        else rateLabel(nextTurnStats.science))

        happinessLabel.setText(getHappinessText(civInfo))

        if (civInfo.getHappiness() < 0) {
            happinessLabel.setFontColor(malcontentColor)
            happinessContainer.clearChildren()
            happinessContainer.addActor(malcontentImage)
        } else {
            happinessLabel.setFontColor(happinessColor)
            happinessContainer.clearChildren()
            happinessContainer.addActor(happinessImage)
        }

        cultureLabel.setText(getCultureText(civInfo, nextTurnStats))

        faithLabel.setText(civInfo.religionManager.storedFaith.tr())
        faithPerTurnLabel.setText(rateLabel(nextTurnStats.faith))

        // UncivGC 实验性 UI: 领土/人口 (信仰后) — 开关控制显示
        val expUi = GUI.getSettings().experimentalUi
        territoryLabel.setText(civInfo.getStatForRanking(RankingType.Territory).toString())
        populationLabel.setText(civInfo.getStatForRanking(RankingType.Population).toString())
        territoryLabel.isVisible = expUi
        populationLabel.isVisible = expUi
        territoryIconCell.isVisible = expUi
        populationIconCell.isVisible = expUi

        scaleTo(worldScreen.stage.width)
    }

    /** UncivGC 实验性 UI (2026-08-22): 科技显示 +x (剩余回合数), 格式与文化一致; 无当前科技时显示 +x (∞) */
    private fun getScienceText(civInfo: Civilization, nextTurnStats: Stats): String {
        val scienceString = rateLabel(nextTurnStats.science)
        val currentTech = civInfo.tech.currentTechnology() ?:
            return scienceString + "\u2004(" + Fonts.infinity + ")"
        val turnsToNextTech = civInfo.tech.turnsToTech(currentTech.name)
        return scienceString + "\u2004(" + Fonts.turn + "\u2009" + turnsToNextTech + ")"
    }

    private fun getCultureText(civInfo: Civilization, nextTurnStats: Stats): String {
        var cultureString = rateLabel(nextTurnStats.culture)
        // kotlin Float division by Zero produces `Float.POSITIVE_INFINITY`, not an exception
        val turnsToNextPolicy = (civInfo.policies.getCultureNeededForNextPolicy() - civInfo.policies.storedCulture) / nextTurnStats.culture
        cultureString += when {
            turnsToNextPolicy <= 0f -> "\u2004(!)" // Can choose policy right now
            nextTurnStats.culture <= 0 -> "\u2004(${Fonts.infinity})" // when you start the game, you're not producing any culture
            else -> "\u2004(" + Fonts.turn + "\u2009" + ceil(turnsToNextPolicy).toInt().tr() + ")" // U+2004: Three-Per-Em Space, U+2009: Thin Space
        }
        return cultureString
    }

    private fun getHappinessText(civInfo: Civilization): String {
        var happinessText = civInfo.getHappiness().tr()
        val goldenAges = civInfo.goldenAges
        happinessText +=
            if (goldenAges.isGoldenAge())
                "    {GOLDEN AGE}(${goldenAges.turnsLeftForCurrentGoldenAge})".tr()
            else
                " (${goldenAges.storedHappiness.tr()}/${goldenAges.happinessRequiredForNextGoldenAge().tr()})"
        return happinessText
    }

    private fun rateLabel(value: Float): String {
        return if (value.roundToInt() == 0) "±0" else value.roundToInt().toStringSigned()
    }
}

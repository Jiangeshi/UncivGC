package com.unciv.ui.screens.worldscreen.topbar

import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.MayaCalendar
import com.unciv.ui.components.YearTextUtil
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toStringSigned
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.ScalingTableWrapper
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.overviewscreen.EmpireOverviewCategories
import com.unciv.ui.screens.victoryscreen.VictoryScreen

internal class WorldScreenTopBarResources(topbar: WorldScreenTopBar) : ScalingTableWrapper() {
    private val turnsLabel = "Turns: 0/400".toLabel()
    private data class ResourceActors(val resource: TileResource, val label: Label, val icon: Group)
    private val resourceActors = ArrayList<ResourceActors>(12)
    private val resourcesWrapper = Table()
    val worldScreen = topbar.worldScreen

    // Note: For a proper functioning of the "shift floating buttons down when cramped" feature, it is
    // important that this entire Widget has only the bare minimum padding to its left and right.
    // #7193 did let the resources and turn label swap places, with the side effect of the "first resource getting extra left padding"
    // now being on the outside - the buttons moved out of the way despite there being visually ample space.

    private companion object {
        const val defaultPad = 5f
        const val extraPadBetweenLabelAndResources = 20f
        const val bottomPad = 10f
        const val extraPadBetweenResources = 5f
        const val outerHorizontalPad = 2f
        const val iconSize = 20f
        const val resourceAmountDescentTweak = 3f
    }

    init {
        defaults().space(extraPadBetweenLabelAndResources)
            .pad(defaultPad, outerHorizontalPad, bottomPad, outerHorizontalPad)

        resourcesWrapper.defaults().space(defaultPad)
        resourcesWrapper.touchable = Touchable.enabled

        turnsLabel.onClick {
            if (worldScreen.selectedCiv.isLongCountDisplay()) {
                val gameInfo = worldScreen.selectedCiv.gameInfo
                MayaCalendar.openPopup(worldScreen, worldScreen.selectedCiv, gameInfo.getYear())
            } else {
                worldScreen.game.pushScreen(VictoryScreen(worldScreen))
            }
        }
        resourcesWrapper.onClick {
            worldScreen.openEmpireOverview(EmpireOverviewCategories.Resources)
        }

        val strategicResources = worldScreen.gameInfo.ruleset.tileResources.values
            .filter { it.resourceType == ResourceType.Strategic && !it.isCityWide }
        for (resource in strategicResources) {
            val resourceImage = ImageGetter.getResourcePortrait(resource.name, iconSize)
            val resourceLabel = "0".toLabel()
            resourceActors += ResourceActors(resource, resourceLabel, resourceImage)
        }

        add(turnsLabel)

        // in case the icons are configured higher than a label, we add a dummy - height will be measured once before it's updated
        if (resourceActors.isNotEmpty()) {
            resourcesWrapper.add(resourceActors[0].icon)
            add(resourcesWrapper)
        }
    }

    /** 帧同步回合显示: 「回合 N ｜ 倒计时 mm:ss」— 每秒本地刷新 (deadline 为服务器 epoch 毫秒) */
    private fun updateFsTurnsLabel(gameInfo: com.unciv.logic.GameInfo) {
        // 暂停期间倒计时冻结 (服务器暂停不结算, deadline 恢复时才顺延 — 本地不再递减)
        if (com.unciv.ui.screens.worldscreen.FrameSync.isPaused()) return
        val deadline = com.unciv.ui.screens.worldscreen.FrameSync.turnDeadline
        val text = if (deadline <= 0) {
            // 未收到 turnStatus (连接初期): 先显示回合数, 收到广播后自动切换
            Fonts.turn + "\u2004" + gameInfo.turns.tr()
        } else {
            val remaining = ((deadline - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            val mm = remaining / 60
            val ss = remaining % 60
            Fonts.turn + "\u2004" + gameInfo.turns.tr() + "\u2004|\u2004" +
                String.format("%02d:%02d", mm, ss)
        }
        if (turnsLabel.text.toString() != text) turnsLabel.setText(text)
    }

    /** 帧同步: 倒计时每秒自刷 (TopBar 的 update 由 shouldUpdate 驱动, 不够频繁) */
    private fun startFsCountdown(worldScreen: com.unciv.ui.screens.worldscreen.WorldScreen) {
        if (turnsLabel.actions.size > 0) return
        turnsLabel.addAction(com.badlogic.gdx.scenes.scene2d.actions.Actions.forever(
            com.badlogic.gdx.scenes.scene2d.actions.Actions.sequence(
                com.badlogic.gdx.scenes.scene2d.actions.Actions.delay(1f),
                com.badlogic.gdx.scenes.scene2d.actions.Actions.run {
                    try {
                        if (com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(worldScreen.gameInfo))
                            updateFsTurnsLabel(worldScreen.gameInfo)
                    } catch (ignored: Exception) {
                    }
                }
            )
        ))
    }

    fun update(civInfo: Civilization) {
        resetScale()

        if (com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(civInfo.gameInfo)) {
            // UncivGC 帧同步: 回合显示 = 回合数 + 保底倒计时 (deadline 来自服务器 turnStatus 广播)
            startFsCountdown(worldScreen)
            updateFsTurnsLabel(civInfo.gameInfo)
        } else {
            val yearText = YearTextUtil.toYearText(
                civInfo.gameInfo.getYear(), civInfo.isLongCountDisplay()
            )
            turnsLabel.setText(Fonts.turn + "\u2004" + civInfo.gameInfo.turns.tr() + "\u2004|\u2004" + yearText) // U+2004: Three-Per-Em Space
        }

        resourcesWrapper.clearChildren()
        val civResources = civInfo.getCivResourcesByName()
        val civResourceSupply = civInfo.getCivResourceSupply()
        for ((index, resourceActors) in resourceActors.withIndex()) {
            val (resource, label, icon) = resourceActors

            if (resource.hasUnique(UniqueType.NotShownOnWorldScreen, civInfo.state)) continue

            val amount = civResources[resource.name] ?: 0

            if (!civInfo.canSeeResource(resource) && amount == 0) // You cannot trade for resources you cannot process yourself yet
                continue

            resourcesWrapper.add(icon).padLeft(if (index == 0) 0f else extraPadBetweenResources)

            if (!resource.isStockpiled)
                label.setText(amount.tr())
            else {
                val perTurn = civResourceSupply.firstOrNull { it.resource == resource }?.amount ?: 0
                if (perTurn == 0) label.setText(amount.tr())
                else label.setText("${amount.tr()} (${perTurn.toStringSigned()})")
            }
            resourcesWrapper.add(label).padTop(resourceAmountDescentTweak)  // digits don't have descenders, so push them down a little
        }

        scaleTo(worldScreen.stage.width)
    }
}

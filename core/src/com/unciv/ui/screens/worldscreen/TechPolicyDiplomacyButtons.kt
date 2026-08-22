package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Container
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.models.UncivSound
import com.unciv.models.translations.tr
import com.unciv.GUI
import com.unciv.ui.components.extensions.colorFromRGB
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.diplomacyscreen.DiplomacyScreen
import com.unciv.ui.screens.overviewscreen.EspionageOverviewScreen
import com.unciv.ui.screens.pickerscreens.PolicyPickerScreen
import com.unciv.ui.screens.pickerscreens.TechButton
import com.unciv.ui.screens.pickerscreens.TechPickerScreen
import com.unciv.ui.screens.worldscreen.UndoHandler.Companion.canUndo
import com.unciv.ui.screens.worldscreen.UndoHandler.Companion.restoreUndoCheckpoint


/** A holder for Tech, Policies and Diplomacy buttons going in the top left of the WorldScreen just under WorldScreenTopBar */
class TechPolicyDiplomacyButtons(val worldScreen: WorldScreen) : Table(BaseScreen.skin) {
    private val fogOfWarButtonHolder = Container<Button?>()
    private val fogOfWarButton = "Fog of War".toTextButton()

    private val techButtonHolder = Container<Table?>()
    private val pickTechButton = Table(skin)
    private val pickTechLabel = "".toLabel(Color.WHITE, 30)

    /** UncivGC 实验性 UI: 文明6 式实时排行面板 (科技按钮下方) */
    private val rankingPanel = RankingPanel(worldScreen)
    private val rankingPanelHolder = Container<Table?>()

    private val policyButtonHolder = Container<Button?>()
    private val policyScreenButton = Button(skin)
    private val diplomacyButtonHolder = Container<Button?>()
    private val diplomacyButton = Button(skin)
    /** 外交按钮 cell 引用 — 实验性 UI 下动态设置尺寸 (宽=科技1/2, 高相等; 2026-08-23) */
    private var diplomacyCell: com.badlogic.gdx.scenes.scene2d.ui.Cell<Container<Button?>>? = null
    private val undoButtonHolder = Container<Button?>()
    private val undoButton = Button(skin)
    private val espionageButtonHolder = Container<Button?>()
    private val espionageButton = Button(skin)

    private val viewingCiv = worldScreen.viewingCiv
    private val game = worldScreen.game


    init {
        defaults().left()
        add(fogOfWarButtonHolder).colspan(4).row()
        // UncivGC 2026-08-23 用户要求: 科技 + 外交 并排一行 (科技左, 外交右; 常开)
        add(techButtonHolder).padTop(10f).growX()
        diplomacyCell = add(diplomacyButtonHolder).padTop(10f).padRight(10f)
        row()
        add(rankingPanelHolder).colspan(4).padTop(10f).row()  // 排行面板下一行
        add(policyButtonHolder).padTop(10f).padRight(10f)
        // 注意: 外交按钮 holder 已在上方外交+科技行 add 过 — 不能重复 add (同一 actor 两次 add 布局错乱, 科技按钮消失根因 2026-08-23)
        add(espionageButtonHolder).padTop(10f).padRight(10f)
        add(undoButtonHolder).padTop(10f).padRight(10f)
        add().growX()  // Allows Policy and Diplo buttons to keep to the left

        fogOfWarButton.label.setFontSize(30)
        fogOfWarButton.labelCell.pad(10f)
        fogOfWarButton.pack()
        fogOfWarButtonHolder.onActivation(UncivSound.Paper, KeyboardBinding.TechnologyTree) {
            worldScreen.fogOfWar = !worldScreen.fogOfWar
            worldScreen.shouldUpdate = true
        }

        pickTechButton.background = BaseScreen.skinStrings.getUiBackground("WorldScreen/PickTechButton", BaseScreen.skinStrings.roundedEdgeRectangleShape, colorFromRGB(7, 46, 43))
        pickTechButton.defaults().pad(20f)
        pickTechButton.add(pickTechLabel)
        techButtonHolder.onActivation(UncivSound.Paper, KeyboardBinding.TechnologyTree) {
            game.pushScreen(TechPickerScreen(viewingCiv))
        }

        undoButton.add(ImageGetter.getImage("OtherIcons/Undo")).size(30f).pad(15f)
        undoButton.onActivation(binding = KeyboardBinding.Undo) {
            handleUndo()
        }

        policyScreenButton.add(ImageGetter.getImage("OtherIcons/Policies")).size(30f).pad(15f)
        policyButtonHolder.onActivation(binding = KeyboardBinding.SocialPolicies) {
            game.pushScreen(PolicyPickerScreen(worldScreen.selectedCiv, worldScreen.canChangeState))
        }

        diplomacyButton.add(ImageGetter.getImage("OtherIcons/DiplomacyW")).size(30f).pad(15f)
        diplomacyButtonHolder.onActivation(binding = KeyboardBinding.Diplomacy) {
            game.pushScreen(DiplomacyScreen(viewingCiv))
        }

        if (game.gameInfo!!.isEspionageEnabled()) {
            espionageButton.add(ImageGetter.getImage("OtherIcons/Espionage")).size(30f).pad(15f)
            espionageButtonHolder.onActivation(binding = KeyboardBinding.Espionage) {
                // We want to make sure to deselect a spy in the case that the player wants to cancel moving
                // the spy on the map screen by pressing this button
                if (worldScreen.bottomUnitTable.selectedSpy != null) {
                    worldScreen.bottomUnitTable.selectSpy(null)
                }
                game.pushScreen(EspionageOverviewScreen(worldScreen.selectedCiv, worldScreen))
            }
        }
    }

    fun update(): Boolean {
        updateFogOfWarButton()
        updateTechButton()
        updateUndoButton()
        updatePolicyButton()
        val result = updateDiplomacyButton()
        if (game.gameInfo!!.isEspionageEnabled())
            updateEspionageButton()
        updateRankingPanel()
        pack()
        setPosition(10f, worldScreen.topBar.y - height - 15f)
        return result
    }

    private fun updateFogOfWarButton() {
        if (viewingCiv.isSpectator()) {
            fogOfWarButtonHolder.actor = fogOfWarButton
            fogOfWarButtonHolder.touchable = Touchable.enabled
        } else {
            fogOfWarButtonHolder.touchable = Touchable.disabled
            fogOfWarButtonHolder.actor = null
        }
    }

    private fun updateTechButton() {
        techButtonHolder.touchable = Touchable.disabled
        techButtonHolder.actor = null
        if (worldScreen.gameInfo.ruleset.technologies.isEmpty() || viewingCiv.cities.isEmpty()) {
            // 调试: 科技按钮不显示排查 (2026-08-23 用户反馈玩家视角无科技按钮)
            try {
                com.unciv.ui.screens.worldscreen.FrameSync.log(
                    "updateTechButton: return early (techs=" + worldScreen.gameInfo.ruleset.technologies.size
                        + " cities=" + viewingCiv.cities.size + " civ=" + viewingCiv.civName + ")")
            } catch (ignored: Exception) {}
            return
        }
        techButtonHolder.touchable = Touchable.enabled

        if (viewingCiv.tech.currentTechnology() != null) {
            val currentTech = viewingCiv.tech.currentTechnologyName()!!
            val innerButton = TechButton(currentTech, viewingCiv.tech)
            innerButton.setButtonColor(colorFromRGB(7, 46, 43))
            techButtonHolder.actor = innerButton
            val turnsToTech = viewingCiv.tech.turnsToTech(currentTech)
            innerButton.text.setText(currentTech.tr(true))
            innerButton.turns.setText(turnsToTech + Fonts.turn)
            try {
                com.unciv.ui.screens.worldscreen.FrameSync.log("updateTechButton: show TechButton " + currentTech)
            } catch (ignored: Exception) {}
        } else {
            val canResearch = viewingCiv.tech.canResearchTech()
            if (canResearch || viewingCiv.tech.researchedTechnologies.size != 0) {
                val text = if (canResearch) "{Pick a tech}!" else "Technologies"
                pickTechLabel.setText(text.tr())
                techButtonHolder.actor = pickTechButton
                try {
                    com.unciv.ui.screens.worldscreen.FrameSync.log("updateTechButton: show pickTech (canResearch=" + canResearch
                        + " researched=" + viewingCiv.tech.researchedTechnologies.size + ")")
                } catch (ignored: Exception) {}
            } else {
                try {
                    com.unciv.ui.screens.worldscreen.FrameSync.log("updateTechButton: NO button (canResearch=" + canResearch
                        + " researched=" + viewingCiv.tech.researchedTechnologies.size + ")")
                } catch (ignored: Exception) {}
            }
        }
    }

    /** UncivGC 实验性 UI: 排行面板 (与外交按钮同行并列 — 科技按钮下方; 2026-08-22 用户要求)
     *  防御: 面板异常不得中断 WorldScreen.update (相遇弹窗/回合推进都在其后 — 2026-08-22) */
    private fun updateRankingPanel() {
        try {
            if (GUI.getSettings().experimentalUi) {
                // 科技按钮内容宽 (布局前用 prefWidth)
                val techWidth = techButtonHolder.actor?.prefWidth
                    ?: (if (techButtonHolder.width > 0f) techButtonHolder.width else 320f)
                // 外交按钮: 宽 = 科技按钮 1/2, 高相等 (用户 2026-08-23; cell 尺寸控制)
                val techHeight = techButtonHolder.actor?.height ?: 50f
                diplomacyCell?.width(techWidth / 2f)
                diplomacyCell?.height(techHeight)
                diplomacyButton.setSize(techWidth / 2f, techHeight)
                rankingPanel.update()
                rankingPanelHolder.actor = rankingPanel
                rankingPanelHolder.touchable = Touchable.enabled
            } else {
                rankingPanelHolder.actor = null
                rankingPanelHolder.touchable = Touchable.disabled
                diplomacyCell?.width(-1f)  // 恢复默认尺寸
                diplomacyCell?.height(-1f)
            }
        } catch (e: Exception) {
            rankingPanelHolder.actor = null
        }
    }

    private fun updateUndoButton() {
        // Don't show the undo button if there is no action to undo
        if (worldScreen.canUndo()) {
            undoButtonHolder.touchable = Touchable.enabled
            undoButtonHolder.actor = undoButton
        } else {
            undoButtonHolder.touchable = Touchable.disabled
            undoButtonHolder.actor = null
        }
    }

    private fun updatePolicyButton() {
        // Don't show policies until they become relevant
        if (viewingCiv.policies.adoptedPolicies.isNotEmpty() || viewingCiv.policies.canAdoptPolicy()) {
            policyButtonHolder.touchable = Touchable.enabled
            policyButtonHolder.actor = policyScreenButton
        } else {
            policyButtonHolder.touchable = Touchable.disabled
            policyButtonHolder.actor = null
        }
    }

    private fun updateDiplomacyButton(): Boolean {
        // UncivGC 实验性 UI: 外交按钮常开 (不建城/不认识文明也显示 — 2026-08-23 用户要求)
        if (GUI.getSettings().experimentalUi) {
            diplomacyButtonHolder.touchable = Touchable.enabled
            diplomacyButtonHolder.actor = diplomacyButton
            return true
        }
        return if (viewingCiv.isDefeated() || viewingCiv.isSpectator()
                || viewingCiv.getKnownCivs().filterNot { it == viewingCiv || it.isBarbarian }.none()
        ) {
            diplomacyButtonHolder.touchable = Touchable.disabled
            diplomacyButtonHolder.actor = null
            false
        } else {
            diplomacyButtonHolder.touchable = Touchable.enabled
            diplomacyButtonHolder.actor = diplomacyButton
            true
        }
    }

    private fun updateEspionageButton() {
        if (worldScreen.selectedCiv.espionageManager.spyList.isEmpty()) {
            espionageButtonHolder.touchable = Touchable.disabled
            espionageButtonHolder.actor = null
        } else {
            espionageButtonHolder.touchable = Touchable.enabled
            espionageButtonHolder.actor = espionageButton
        }
    }

    private fun handleUndo() {
        undoButton.disable()
        worldScreen.restoreUndoCheckpoint()
    }

    override fun act(delta: Float) = super.act(delta)
    override fun draw(batch: Batch?, parentAlpha: Float) = super.draw(batch, parentAlpha)
    override fun hit(x: Float, y: Float, touchable: Boolean): Actor? = super.hit(x, y, touchable)
}

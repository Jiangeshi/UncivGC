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
    /** 外交按钮 cell 引用 — 实验性 UI 下动态设置尺寸 (宽=科技1/3, 高50; 2026-08-23) */
    /** UncivGC 2026-08-31: 左组改右组同款风格 — 不再持有 cell 引用, 高度由 cell height(60f).fillY() 强制 */
    private val undoButtonHolder = Container<Button?>()
    private val undoButton = Button(skin)
    private val espionageButtonHolder = Container<Button?>()
    private val espionageButton = Button(skin)

    // ===== UncivGC 2026-08-31 重写: 实验性UI左组 政策/外交/间谍 = 右组同款 TextButton (用户要求) =====
    private val expPolicyButton = "政策".toTextButton().apply {
        onActivation(binding = KeyboardBinding.SocialPolicies) {
            game.pushScreen(PolicyPickerScreen(worldScreen.selectedCiv, worldScreen.canChangeState))
        }
    }
    private val expDiplomacyButton = "外交".toTextButton().apply {
        onActivation(binding = KeyboardBinding.Diplomacy) {
            game.pushScreen(DiplomacyScreen(viewingCiv))
        }
    }
    private val expEspionageButton = "间谍".toTextButton().apply {
        onActivation(binding = KeyboardBinding.Espionage) {
            if (worldScreen.bottomUnitTable.selectedSpy != null)
                worldScreen.bottomUnitTable.selectSpy(null)
            game.pushScreen(EspionageOverviewScreen(worldScreen.selectedCiv, worldScreen))
        }
    }

    private val viewingCiv = worldScreen.viewingCiv
    private val game = worldScreen.game


    init {
        defaults().left()
        add(fogOfWarButtonHolder).colspan(4).row()
        if (GUI.getSettings().experimentalUi) {
            // UncivGC 2026-08-23 用户要求: 按钮顺序 科技 政策 外交 间谍 (间谍有才显示); 撤销保留在最后
            // 2026-08-31 用户要求: 左组与右组同款写法 — 统一 height(60f).fillY(), 去掉 padTop hack
            // UncivGC 2026-08-31: 左组 = 右组同款 TextButton, 全部 60 高 (与右组一致 — 用户 2026-08-31)
            add(techButtonHolder).padRight(4f).height(60f).minHeight(60f).fillY()
            add(expPolicyButton).padLeft(8f).padRight(4f).height(60f).minHeight(60f).fillY()
            add(expDiplomacyButton).padLeft(4f).padRight(4f).height(60f).minHeight(60f).fillY()
            add(expEspionageButton).padRight(4f).height(60f).minHeight(60f).fillY()
            // UncivGC 2026-08-31: 撤销按钮移入顶栏快捷工具栏右组 (QuickActionBar), 左组不再显示
            // undoCell = add(undoButtonHolder).padRight(10f).top()
            add().growX()
            row()
            add(rankingPanelHolder).colspan(4).padTop(10f).row()  // 排行面板下一行
        } else {
            // 原版布局 (非实验性 UI): 科技单独一行, 政策/外交/间谍/撤销一行
            add(techButtonHolder).colspan(4).row()
            add(policyButtonHolder).padTop(10f).padRight(10f)
            add(diplomacyButtonHolder).padTop(10f).padRight(10f)
            add(espionageButtonHolder).padTop(10f).padRight(10f)
            add(undoButtonHolder).padTop(10f).padRight(10f)
            add().growX()  // Allows Policy and Diplo buttons to keep to the left
        }

        // 2026-08-23 用户反馈: 过回合后政策/外交稳定变矮 — 重建 WorldScreen 后新 init 的 cell 未固定尺寸
        // (固定尺寸只在 update() 里设, 重建后第一帧/首帧前渲染默认高度) → init 末尾直接固定
        // 2026-08-23 04:46 用户补充: 只要有操作就变矮 — init 时按钮本体还是 prefHeight(60, 图标30+pad15*2),
        // 操作触发 updateRankingPanel 后才被压到 60 → init 里把按钮本体尺寸+fill 全部固定, 高度恒定不再跳变
        if (GUI.getSettings().experimentalUi) {
            try {
                fixExpUiSizes()
            } catch (ignored: Exception) {}
        }
        fsLog("init 完成: expUi=" + GUI.getSettings().experimentalUi)


        fogOfWarButton.label.setFontSize(30)
        fogOfWarButton.labelCell.pad(10f)
        fogOfWarButton.pack()
        fogOfWarButtonHolder.onActivation(UncivSound.Paper, KeyboardBinding.TechnologyTree) {
            worldScreen.fogOfWar = !worldScreen.fogOfWar
            worldScreen.shouldUpdate = true
        }

        pickTechButton.background = BaseScreen.skinStrings.getUiBackground("WorldScreen/PickTechButton", BaseScreen.skinStrings.roundedEdgeRectangleShape, colorFromRGB(7, 46, 43))
        pickTechButton.defaults().pad(8f)  // pad 20→8: 高度接近 50, 与外交/科技同高 (2026-08-23)
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
        // 2026-08-31 修复: 图标 add 完成后**再**固定一次 — 之前 fixExpUiSizes 在 add 图标前执行,
        // 图标 add 改变按钮 prefHeight 后初始固定被冲掉 → 开局政策/外交变大 (用户反馈)
        if (GUI.getSettings().experimentalUi) {
            try {
                fixExpUiSizes()
                invalidateHierarchy()
            } catch (ignored: Exception) {}
        }
        fsLog("init 末尾再固定: policyBtnH=" + (policyScreenButton.height.toInt()) + " diploBtnH=" + (diplomacyButton.height.toInt()))
    }

    /** UncivGC 实验性 UI: 保证 holder 内按钮填满 (2026-08-31: cell 已由 height(60f).fillY() 强制高度,
     *  这里只处理 holder 内部填充, 不再持有 cell 引用) */
    private fun fixExpUiSizes() {
        techButtonHolder.fill()  // 科技内容动态, 填满 60
        // 政策/外交/间谍不 fill — 按钮本体由 setSize 强制 40 (2026-08-31 用户要求)
    }

    /** 左组贴顶栏: 实验性UI贴快捷工具栏 (无间距), 非实验性UI保持原版 15px 间距 (2026-09-01 自检修复) */
    private fun buttonsY(): Float =
        worldScreen.topBar.y - height - (if (GUI.getSettings().experimentalUi) 0f else 15f)

    fun update(): Boolean {
        // 观战者 (看海): 不显示科技/政策/外交/间谍/撤销 — 无操作权限, 只保留迷雾开关 (2026-08-23 用户反馈)
        // 排行面板保留 (2026-08-24 用户要求: 看海也要看排行; 仅实验性 UI 开启时显示)
        if (viewingCiv.isSpectator()) {
            updateFogOfWarButton()
            techButtonHolder.actor = null; techButtonHolder.touchable = Touchable.disabled
            policyButtonHolder.actor = null; policyButtonHolder.touchable = Touchable.disabled
            diplomacyButtonHolder.actor = null; diplomacyButtonHolder.touchable = Touchable.disabled
            espionageButtonHolder.actor = null; espionageButtonHolder.touchable = Touchable.disabled
            undoButtonHolder.actor = null; undoButtonHolder.touchable = Touchable.disabled
            // 2026-08-31: 实验性UI TextButton 同步隐藏
            expPolicyButton.isVisible = false
            expDiplomacyButton.isVisible = false
            expEspionageButton.isVisible = false
            updateRankingPanel()
            pack()
            setPosition(10f, buttonsY())
            return false
        }
        updateFogOfWarButton()
        updateTechButton()
        updateUndoButton()
        updatePolicyButton()
        val result = updateDiplomacyButton()
        if (game.gameInfo!!.isEspionageEnabled())
            updateEspionageButton()
        updateRankingPanel()
        pack()
        setPosition(10f, buttonsY())
        fsLog("update() pack后: techH=" + (techButtonHolder.height.toInt()) + " policyH=" + (policyButtonHolder.height.toInt())
            + " diploH=" + (diplomacyButtonHolder.height.toInt()) + " expUi=" + GUI.getSettings().experimentalUi)
        return result
    }

    companion object {
        /** 调试日志 (用户规则: 机制类修复加日志到 ~/fs_debug.log, 2026-08-22) */
        fun fsLog(msg: String) {
            try {
                val f = java.io.File(System.getProperty("user.home"), "fs_debug.log")
                f.appendText(java.time.LocalDateTime.now().toString().substring(11, 23) + " [Buttons] " + msg + "\n")
            } catch (ignored: Exception) {}
        }
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
        // UncivGC 实验性 UI: 科技按钮常开 (开局没建城也显示 — 2026-08-23 用户要求)
        if (GUI.getSettings().experimentalUi) {
            techButtonHolder.touchable = Touchable.enabled
            if (viewingCiv.tech.currentTechnology() != null) {
                val currentTech = viewingCiv.tech.currentTechnologyName()!!
                val innerButton = TechButton(currentTech, viewingCiv.tech)
                innerButton.setButtonColor(colorFromRGB(7, 46, 43))
                techButtonHolder.actor = innerButton
                val turnsToTech = viewingCiv.tech.turnsToTech(currentTech)
                innerButton.text.setText(currentTech.tr(true))
                innerButton.turns.setText(turnsToTech + Fonts.turn)
            } else {
                val canResearch = viewingCiv.tech.canResearchTech()
                if (canResearch || viewingCiv.tech.researchedTechnologies.size != 0) {
                    val text = if (canResearch) "{Pick a tech}!" else "Technologies"
                    pickTechLabel.setText(text.tr())
                    techButtonHolder.actor = pickTechButton
                } else {
                    pickTechLabel.setText("{Pick a tech}!".tr())
                    techButtonHolder.actor = pickTechButton
                }
            }
            try {
                val act = techButtonHolder.actor
                if (act != null) act.setSize(act.prefWidth, 60f)
            } catch (ignored: Exception) {}
            return
        }
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
        // UncivGC 实验性 UI: 科技按钮高度固定 60 (与外交同高 — 2026-08-23 用户要求; 过回合后外交变扁根因)
        if (GUI.getSettings().experimentalUi) {
            try {
                val act = techButtonHolder.actor
                if (act != null) act.setSize(act.prefWidth, 60f)
            } catch (ignored: Exception) {}
        }
    }

    /** UncivGC 实验性 UI: 排行面板 (与外交按钮同行并列 — 科技按钮下方; 2026-08-22 用户要求)
     *  防御: 面板异常不得中断 WorldScreen.update (相遇弹窗/回合推进都在其后 — 2026-08-22) */
    private fun updateRankingPanel() {
        // 2026-08-31 修复: 固定尺寸放 try 外 — 开局排行面板异常时不固定 → 外交/政策/间谍明显偏高
        // (用户反馈"刚开局按钮高, 点击后刷新变正常")
        if (GUI.getSettings().experimentalUi) fixExpUiSizes()
        try {
            if (GUI.getSettings().experimentalUi) {
                // 科技按钮内容宽 (布局前用 prefWidth)
                val techWidth = techButtonHolder.actor?.prefWidth
                    ?: (if (techButtonHolder.width > 0f) techButtonHolder.width else 320f)
                // ===== 高度统一 60 由 cell height(60f).fillY() 强制 (2026-08-31 右组同款) =====
                techButtonHolder.fill()
                policyButtonHolder.fill()
                diplomacyButtonHolder.fill()
                espionageButtonHolder.fill()
                undoButtonHolder.fill()
                rankingPanel.update()
                rankingPanelHolder.actor = rankingPanel
                rankingPanelHolder.touchable = Touchable.enabled
                fsLog("updateRankingPanel: 设后 techH=" + (techButtonHolder.height.toInt()) + " policyH=" + (policyButtonHolder.height.toInt())
                    + " diploH=" + (diplomacyButtonHolder.height.toInt()) + " panelH=" + (rankingPanel.height.toInt()))
            } else {
                rankingPanelHolder.actor = null
                rankingPanelHolder.touchable = Touchable.disabled
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
        // UncivGC 实验性 UI: 政策按钮常开 (F5 快捷键对应 — 2026-08-23 用户要求); 2026-08-31 改 TextButton
        if (GUI.getSettings().experimentalUi) {
            expPolicyButton.isVisible = true
            return
        }
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
        // UncivGC 实验性 UI: 外交按钮常开; 2026-08-31 改 TextButton
        if (GUI.getSettings().experimentalUi) {
            expDiplomacyButton.isVisible = true
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
            if (GUI.getSettings().experimentalUi) expEspionageButton.isVisible = false  // 2026-08-31 TextButton
            espionageButtonHolder.touchable = Touchable.disabled
            espionageButtonHolder.actor = null
        } else {
            if (GUI.getSettings().experimentalUi) expEspionageButton.isVisible = true  // 2026-08-31 TextButton
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

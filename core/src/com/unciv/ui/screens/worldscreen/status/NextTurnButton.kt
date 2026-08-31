package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.unciv.logic.civilization.managers.TurnManager
import com.unciv.models.translations.tr
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.UncivTooltip.Companion.removeTooltips
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.setSize
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.images.IconTextButton
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.AnimatedMenuPopup.Companion.addContextMenu
import com.unciv.ui.popups.hasOpenPopups
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.FrameSync
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.status.NextTurnAction.Default
import com.unciv.utils.Concurrency
import yairm210.purity.annotations.Readonly

class NextTurnButton(
    private val worldScreen: WorldScreen
) : IconTextButton("", null, 22) {
    private var nextTurnAction = Default
    private val unitsDueLabel = Label("", BaseScreen.skin)
    private val unitsDueCell: Cell<Label>

    init {
        pad(15f)
        onActivation {
            // 2026-08-30 调试: 完成回合按钮点击是否触发
            com.unciv.ui.screens.worldscreen.FrameSync.log(
                "NextTurnBtn clicked: action=$nextTurnAction myTurnFinished=" +
                com.unciv.ui.screens.worldscreen.FrameSync.myTurnFinished)
            nextTurnAction.action(worldScreen)
        }
        addContextMenu { NextTurnMenu(stage, this, worldScreen) }
        keyShortcuts.add(KeyboardBinding.NextTurn)
        keyShortcuts.add(KeyboardBinding.NextTurnAlternate)
        labelCell.row()
        unitsDueCell = add(unitsDueLabel).padTop(6f).colspan(2).center()
    }

    fun update() {
        nextTurnAction = getNextTurnAction(worldScreen)
        // UncivGC 2026-08-31: 有待办未清空时状态按钮显示「有待办未完成」(置灰提示, 不消失) —
        // 仅实验性UI (非实验性UI保持原版: 按钮直接显示待办动作)
        val fsFinished = FrameSync.isFsMode(worldScreen.gameInfo) && FrameSync.myTurnFinished
        val todoBlocks = com.unciv.GUI.getSettings().experimentalUi && !fsFinished
            && nextTurnAction == NextTurnAction.NextTurn
            && currentTodoAction(worldScreen) != null
        if (todoBlocks) {
            label.setText("有待办未完成".tr())
            label.color = com.badlogic.gdx.graphics.Color.GRAY
            iconCell.clearActor()
            unitsDueCell.clearActor()
            pack()
        } else {
            updateButton(nextTurnAction)
        }
        val autoPlay = worldScreen.autoPlay
        // UncivGC 帧同步: 禁 autoPlay — automateTurn 会在本地跑单位操作, 绕过服务器拦截
        val fsMode = FrameSync.isFsMode(worldScreen.gameInfo)
        if (!fsMode && autoPlay.shouldContinueAutoPlaying() && worldScreen.isPlayersTurn
            && !worldScreen.waitingForAutosave && !worldScreen.isNextTurnUpdateRunning()) {
            autoPlay.runAutoPlayJobInNewThread("MultiturnAutoPlay", worldScreen, false) {
                TurnManager(worldScreen.viewingCiv).automateTurn()
                Concurrency.runOnGLThread { worldScreen.nextTurn() }
                autoPlay.endTurnMultiturnAutoPlay()
            }
        }

        val baseEnabled = (worldScreen.isPlayersTurn || worldScreen.failedUpload)
            && !worldScreen.waitingForAutosave && !worldScreen.isNextTurnUpdateRunning()
        com.unciv.ui.screens.worldscreen.FrameSync.log(
            "NextTurnBtn update: action=$nextTurnAction base=$baseEnabled isPlayersTurn=${worldScreen.isPlayersTurn} " +
            "waitingAutosave=${worldScreen.waitingForAutosave} isNextTurnRunning=${worldScreen.isNextTurnUpdateRunning()} " +
            "myTurnFinished=" + com.unciv.ui.screens.worldscreen.FrameSync.myTurnFinished)
        // UncivGC 2026-08-31 一行按钮: 待办未清空 → 「下一个回合/完成回合」置灰 (不完成不能过回合;
        // 取消完成回合不挡; 单机「下一个回合」与 fs「完成回合」同规则 — 用户 2026-08-31)
        isEnabled = (nextTurnAction.getText(worldScreen) == "AutoPlay" || baseEnabled) && !todoBlocks
        // UncivGC 帧同步: 已点“完成回合”后按钮变「取消完成」仍可点 (点击取消)
        if (fsFinished && nextTurnAction != NextTurnAction.NextTurn) isEnabled = false
        // UncivGC 帧同步: 观战者不能点完成回合
        if (FrameSync.isFsMode(worldScreen.gameInfo) && worldScreen.viewingCiv.isSpectator()) isEnabled = false
        if (isEnabled) {
            addTooltip(KeyboardBinding.NextTurn)
        } else {
            removeTooltips()
        }

        worldScreen.smallUnitButton.update()
    }

    internal fun updateButton(nextTurnAction: NextTurnAction) {
        label.setText(nextTurnAction.getText(worldScreen).tr())
        label.color = nextTurnAction.color
        // UncivGC 帧同步: 等待剩余玩家状态不显示图标 (圆形回合图标会跑到按钮外)
        val fsWaiting = FrameSync.isFsMode(worldScreen.gameInfo) && FrameSync.myTurnFinished
        if (!fsWaiting && nextTurnAction.icon != null && ImageGetter.imageExists(nextTurnAction.icon!!))
            iconCell.setActor(ImageGetter.getImage(nextTurnAction.icon).apply {
                setSize(30f)
                color = nextTurnAction.color
            })
        else
            iconCell.clearActor()

        nextTurnAction.getSubText(worldScreen)?.let {
            unitsDueLabel.setText(it.tr())
            unitsDueCell.setActor(unitsDueLabel)
        } ?: unitsDueCell.clearActor()

        pack()
    }

    private fun getNextTurnAction(worldScreen: WorldScreen) =
        // UncivGC 帧同步 2026-08-30: 已完成回合 → 按钮只能是“取消完成回合”(可点反悔)
        // UncivGC 2026-08-31: 实验性UI下状态按钮只显示状态类动作 (待办/单位拆到独立按钮);
        // 非实验性UI保持原版 (待办动作优先显示, 如 选择建造/选择科技)
        if (FrameSync.isFsMode(worldScreen.gameInfo) && FrameSync.myTurnFinished) {
            NextTurnAction.NextTurn
        } else if (com.unciv.GUI.getSettings().experimentalUi) {
            // Guaranteed to return a non-null NextTurnAction because the last isChoice always returns true
            NextTurnAction.entries.first { it in statusActions && it.isChoice(worldScreen) }
        } else {
            // Guaranteed to return a non-null NextTurnAction because the last isChoice always returns true
            NextTurnAction.entries.first { it.isChoice(worldScreen) }
        }

    @Readonly fun isNextUnitAction(): Boolean = nextTurnAction == NextTurnAction.NextUnit

    companion object {
        /** 状态按钮动作集 (2026-08-31 一行按钮拆分: 状态显示+完成回合留在主按钮) */
        val statusActions = setOf(
            NextTurnAction.RetryUpload, NextTurnAction.AutoPlay, NextTurnAction.Working,
            NextTurnAction.Waiting, NextTurnAction.NextTurn
        )

        /** 待办动作集 (不完成不能过回合; 拆到「待办」按钮, 2026-08-31) */
        val todoActions = setOf(
            NextTurnAction.PickConstruction, NextTurnAction.PickTech, NextTurnAction.PickPolicy,
            NextTurnAction.MoveSpies, NextTurnAction.FoundPantheon, NextTurnAction.ExpandPantheon,
            NextTurnAction.FoundReligion, NextTurnAction.EnhanceReligion, NextTurnAction.ReformReligion,
            NextTurnAction.WorldCongressVote, NextTurnAction.MoveAutomatedUnits
        )

        /** 当前待办动作 (优先级最高的第一个满足条件项) */
        fun currentTodoAction(worldScreen: WorldScreen): NextTurnAction? =
            todoActions.firstOrNull { it.isChoice(worldScreen) }
    }
}

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
) : IconTextButton("", null, 30) {
    private var nextTurnAction = Default
    private val unitsDueLabel = Label("", BaseScreen.skin)
    private val unitsDueCell: Cell<Label>

    init {
        pad(15f)
        onActivation { nextTurnAction.action(worldScreen) }
        addContextMenu { NextTurnMenu(stage, this, worldScreen) }
        keyShortcuts.add(KeyboardBinding.NextTurn)
        keyShortcuts.add(KeyboardBinding.NextTurnAlternate)
        labelCell.row()
        unitsDueCell = add(unitsDueLabel).padTop(6f).colspan(2).center()
    }

    fun update() {
        nextTurnAction = getNextTurnAction(worldScreen)
        updateButton(nextTurnAction)
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

        isEnabled = nextTurnAction.getText(worldScreen) == "AutoPlay"
            || ((worldScreen.isPlayersTurn || worldScreen.failedUpload) && !worldScreen.waitingForAutosave && !worldScreen.isNextTurnUpdateRunning())
        // UncivGC 帧同步: 已点“完成回合”后按钮变「取消完成」仍可点 (点击取消); NextUnit 例外: 完成回合后仍可跳转/操作剩余闲置单位
        if (FrameSync.isFsMode(worldScreen.gameInfo) && FrameSync.myTurnFinished
            && nextTurnAction != NextTurnAction.NextUnit
            && nextTurnAction != NextTurnAction.NextTurn) isEnabled = false
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
        // UncivGC 帧同步 2026-08-30: 已完成回合 → 按钮只能是“取消完成回合”(可点反悔);
        // 点取消 → myTurnFinished=false → 走 else → 显示没做完的事 (选择建造/训练等待办, 可点);
        // “完成回合”只在所有待办处理完才出现 (待办动作永远优先)
        if (FrameSync.isFsMode(worldScreen.gameInfo) && FrameSync.myTurnFinished) {
            NextTurnAction.NextTurn
        } else
            // Guaranteed to return a non-null NextTurnAction because the last isChoice always returns true
            NextTurnAction.entries.first { it.isChoice(worldScreen) }

    @Readonly fun isNextUnitAction(): Boolean = nextTurnAction == NextTurnAction.NextUnit

}

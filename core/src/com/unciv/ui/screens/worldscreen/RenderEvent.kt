package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.Event
import com.unciv.models.ruleset.EventChoice
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.WrappableLabel
import com.unciv.ui.screens.civilopediascreen.FormattedLine
import com.unciv.ui.screens.civilopediascreen.MarkupRenderer

/** Renders an [Event] for [AlertPopup] or a floating tutorial task on [WorldScreen] */
class RenderEvent(
    val event: Event,
    val worldScreen: WorldScreen,
    val unit: MapUnit? = null,
    val onChoice: (EventChoice) -> Unit
) : Table() {
    private val gameInfo get() = worldScreen.gameInfo
    private val stageWidth get() = worldScreen.stage.width

    val isValid: Boolean

    //todo check generated translations

    init {
        defaults().fillX().center().pad(5f)

        // UncivGC 帧同步: 同时回合模式下 currentPlayerCiv 不一定是事件目标玩家
        // (服务器回合轮转的当前玩家 vs 弹窗所属文明) → 用 viewingCiv 计算选项条件,
        // 否则 "Only available <for [Human player] Civilizations>" 等条件全错 → 时代奖励选项缺失
        // 2026-08-26 再修: 单机热座同样问题 — 选项条件与触发 (triggerChoice → 嵌套事件加 popupAlerts)
        // 必须用事件所属文明 viewingCiv, 否则内层事件加到当前回合玩家身上, 弹窗玩家看不到 (嵌套事件不触发)
        val eventCiv = worldScreen.viewingCiv
        val gameContext = GameContext(eventCiv, unit = unit)
        val choices = event.getMatchingChoices(gameContext)
        isValid = choices != null
        if (isValid) {
            if (event.text.isNotEmpty()) {
                add(WrappableLabel(event.text, stageWidth * 0.5f).apply {
                    wrap = true
                    setAlignment(Align.center)
                    optimizePrefWidth()
                }).row()
            }
            if (event.civilopediaText.isNotEmpty()) {
                add(event.renderCivilopediaText(stageWidth * 0.5f, ::openCivilopedia)).row()
            }

            for (choice in choices!!) addChoice(choice)
        }
    }

    private fun addChoice(choice: EventChoice) {
        addSeparator()

        val button = choice.text.toTextButton()
        button.onActivation {
            if (com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(gameInfo)) {
                // UncivGC 帧同步: 选择回传服务器执行 (triggerChoice 服务器权威, 防重载回滚)
                val sent = com.unciv.ui.screens.worldscreen.FrameSync.sendOpChecked("civ.eventChoice",
                    mapOf("event" to event.name, "choice" to choice.text,
                          "unitId" to (unit?.id ?: -1)))
                if (!sent) {
                    // 2026-08-27 修复: 结算/暂停中 op 被吞 → 不关弹窗不标记已处理,
                    // 否则事件直接丢失 (服务器没执行, 本地已 resolved → 再也不会弹);
                    // 弹窗保留, 结算结束后玩家可再次选择
                    return@onActivation
                }
                // 已选择 → 不再重新挂起 (防存档重载后重复弹窗)
                com.unciv.ui.screens.worldscreen.FrameSync.markEventResolved(event.name)
                onChoice(choice)  // 关闭弹窗 + 移除本地 popupAlert
            } else {
                onChoice(choice)
                // 2026-08-26 修复: 用事件所属文明 viewingCiv (热座嵌套事件触发到正确文明)
                choice.triggerChoice(worldScreen.viewingCiv, unit)
            }
        }
        val key = KeyCharAndCode.parse(choice.keyShortcut)
        if (key != KeyCharAndCode.UNKNOWN) {
            button.keyShortcuts.add(key)
            button.addTooltip(key)
        }
        add(button).row()

        val lines = (
            choice.civilopediaText.asSequence()
                + choice.uniqueObjects.filter { it.isTriggerable || it.type == UniqueType.Comment }
                    .filterNot { it.isHiddenToUsers() }
                    .map { FormattedLine(it) }
            ).asIterable()
        add(MarkupRenderer.render(lines, stageWidth * 0.5f, linkAction = ::openCivilopedia)).row()
    }

    private fun openCivilopedia(link: String) = worldScreen.openCivilopedia(link)
}

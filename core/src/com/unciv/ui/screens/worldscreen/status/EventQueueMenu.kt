package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.popups.ScrollableAnimatedMenuPopup
import com.unciv.ui.screens.pickerscreens.GreatPersonPickerScreen
import com.unciv.ui.screens.worldscreen.AlertPopup
import com.unciv.ui.screens.worldscreen.TradePopup
import com.unciv.ui.screens.worldscreen.WorldScreen

/** UncivGC 待办事件列表 (实验性UI): 「事件」按钮点击弹出的下拉弹层 — 替代完成回合按钮位置
 *  - 滚动区: 排队事件 (popupAlerts 摘要 / 免费伟人 / 贸易请求), 点击弹对应弹窗
 *  - 固定区: 当前下一个动作 (完成回合/取消完成回合/选择建造等) — 事件按钮占了完成回合按钮位,
 *    结束回合入口放在这里, 不阻挡过回合 */
class EventQueueMenu(
    stage: Stage,
    nextTurnButton: NextTurnButton,
    private val worldScreen: WorldScreen
) : ScrollableAnimatedMenuPopup(stage, nextTurnButton) {

    private val viewingCiv get() = worldScreen.viewingCiv
    private val gameInfo get() = worldScreen.gameInfo

    init {
        // 菜单关闭后刷新按钮 (事件数可能已变化)
        afterCloseCallback = { worldScreen.shouldUpdate = true }
    }

    override fun createScrollableContent(): Table? {
        val table = Table()
        table.defaults().pad(5f, 15f, 5f, 15f).growX()
        var any = false

        // popupAlerts 排队事件 (通知/决策弹窗 — 下回合清空; 立即弹类型除外: 占领城市/外交联姻/游戏结束)
        for (alert in viewingCiv.popupAlerts.toList()) {
            if (alert.type in com.unciv.ui.screens.worldscreen.immediatePopupAlertTypes) continue
            table.add(menuButton(alertLabel(alert)) {
                AlertPopup(worldScreen, alert)
            }).row()
            any = true
        }

        // 免费伟人 (必选, 服务器计数驱动 — 不随下回合清空)
        if (viewingCiv.greatPeople.freeGreatPeople > 0) {
            table.add(menuButton("选择免费伟人".tr()) {
                worldScreen.game.pushScreen(GreatPersonPickerScreen(worldScreen, viewingCiv))
            }).row()
            any = true
        }

        // 贸易请求 (商路/贸易提议; 服务器存档驱动 — 回应前保留)
        for (req in viewingCiv.tradeRequests.toList()) {
            val civName = gameInfo.getCivilizationOrNull(req.requestingCiv)?.civName?.tr() ?: req.requestingCiv
            table.add(menuButton("贸易请求: [$civName]".tr()) {
                // TradePopup 固定取 first → 把点中的那条移到队首再开
                val list = viewingCiv.tradeRequests
                if (list.firstOrNull { it === req } != null) {
                    list.remove(req)
                    list.add(0, req)
                }
                TradePopup(worldScreen).open()
            }).row()
            any = true
        }

        return if (any) table else null
    }

    override fun createFixedContent(): Table? {
        val table = Table()
        table.defaults().pad(5f, 15f, 5f, 15f).growX()
        // 结束回合入口: 当前下一个动作 (与按钮原逻辑一致; 帧同步 = 完成回合/取消完成回合)
        val action = NextTurnAction.entries.first { it.isChoice(worldScreen) }
        table.add(getButton(action.getText(worldScreen).tr(), KeyboardBinding.NextTurnMenuNextTurn) {
            action.action(worldScreen)
        }).row()
        return table
    }

    /** 事件项按钮 (无键盘绑定 — 动态数量, 同绑定会全部触发) */
    private fun menuButton(text: String, action: () -> Unit): Actor =
        text.toTextButton().apply {
            onActivation {
                action()
                close()
            }
        }

    /** 弹窗摘要文案 (按 AlertType 映射) */
    private fun alertLabel(alert: PopupAlert): String {
        val value = alert.value
        return when (alert.type) {
            AlertType.Event -> {
                val name = value.split(Constants.stringSplitCharacter)[0]
                gameInfo.ruleset.events[name]?.name ?: name
            }
            AlertType.WonderBuilt -> "奇观建成: " + (gameInfo.ruleset.buildings[value]?.name ?: value)
            AlertType.TechResearched -> "科技研究完成: " + (gameInfo.ruleset.technologies[value]?.name ?: value)
            AlertType.WarDeclaration -> "宣战: " + civLabel(value)
            AlertType.FirstContact -> "相遇: " + civLabel(value)
            AlertType.CityConquered -> "占领城市: " + cityLabel(value)
            AlertType.CityTraded -> "城市交易: " + cityLabel(value)
            AlertType.BorderConflict -> "边境冲突: " + civLabel(value)
            AlertType.TilesStolen -> "土地被占: " + civLabel(value)
            AlertType.DemandToStopSettlingCitiesNear -> "要求停止扩张: " + civLabel(value)
            AlertType.CitySettledNearOtherCivDespiteOurPromise -> "违反承诺建城: " + civLabel(value)
            AlertType.DemandToStopSpreadingReligion -> "要求停止传教: " + civLabel(value)
            AlertType.ReligionSpreadDespiteOurPromise -> "违反承诺传教: " + civLabel(value)
            AlertType.DemandToStopSpyingOnUs -> "要求停止间谍: " + civLabel(value)
            AlertType.SpyingOnUsDespiteOurPromise -> "违反承诺间谍: " + civLabel(value)
            AlertType.DemandToNotAttackUs -> "要求停止攻击: " + civLabel(value)
            AlertType.AttackedUsDespitePromise -> "违反承诺攻击: " + civLabel(value)
            AlertType.AcceptingDemand -> "接受要求: " + civLabel(value)
            AlertType.RejectingDemand -> "拒绝要求: " + civLabel(value)
            AlertType.GoldenAge -> "黄金时代"
            AlertType.DeclarationOfFriendship -> "友谊宣言: " + civLabel(value)
            AlertType.StartIntro -> "开场"
            AlertType.DiplomaticMarriage -> "外交联姻: " + cityLabel(value)
            AlertType.BulliedProtectedMinor, AlertType.AttackedProtectedMinor, AlertType.AttackedAllyMinor ->
                "城邦事件: " + (value.split('@').firstOrNull()?.let { civLabel(it) } ?: "")
            AlertType.RecapturedCivilian -> "夺回平民"
            AlertType.GameHasBeenWon -> "游戏结束"
            AlertType.Defeated -> "文明灭亡: " + civLabel(value)
            AlertType.Denounced -> "谴责: " + civLabel(value)
            AlertType.TradeRouteOffer -> {
                val parts = value.split("|")
                if (parts.size >= 2) "贸易邀请: " + cityLabel(parts[0]) + " → " + cityLabel(parts[1])
                else "贸易邀请"
            }
            AlertType.AllianceOffer -> "同盟提议: " + civLabel(value)
            AlertType.AllianceRenew -> "续约同盟: " + civLabel(value)
            AlertType.AllianceFollowUp -> "盟友战争跟进: " + civLabel(value)
        }
    }

    private fun civLabel(idOrName: String): String =
        gameInfo.getCivilizationOrNull(idOrName)?.civName?.tr() ?: idOrName

    private fun cityLabel(id: String): String =
        gameInfo.getCities().firstOrNull { it.id == id }?.name?.tr() ?: id
}

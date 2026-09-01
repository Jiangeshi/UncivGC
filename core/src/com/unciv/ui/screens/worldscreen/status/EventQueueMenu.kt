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
    anchor: com.badlogic.gdx.scenes.scene2d.Actor,
    private val worldScreen: WorldScreen
) : ScrollableAnimatedMenuPopup(stage, anchor, com.badlogic.gdx.utils.Align.bottomLeft) {

    private val viewingCiv get() = worldScreen.viewingCiv
    private val gameInfo get() = worldScreen.gameInfo

    init {
        // 菜单关闭后刷新按钮 (事件数可能已变化)
        afterCloseCallback = { worldScreen.shouldUpdate = true }
    }

    override fun createScrollableContent(): Table? {
        val table = Table()
        table.defaults().pad(5f, 15f, 5f, 15f).growX()
        // 固定宽度让条目左右填满 (2026-08-31 用户: 下拉框元素应填满, 不居中)
        var any = false

        // popupAlerts 排队事件 (通知/决策弹窗 — 下回合清空; 立即弹类型除外: 占领城市/外交联姻/游戏结束)
        for (alert in viewingCiv.popupAlerts.toList()) {
            if (alert.type in com.unciv.ui.screens.worldscreen.immediatePopupAlertTypes) continue
            table.add(menuButton(alertLabel(alert)) {
                AlertPopup(worldScreen, alert)
            }).width(260f).row()
            any = true
        }

        // 免费伟人 (必选, 服务器计数驱动 — 不随下回合清空)
        if (viewingCiv.greatPeople.freeGreatPeople > 0) {
            table.add(menuButton("Choose a free great person".tr()) {
                worldScreen.game.pushScreen(GreatPersonPickerScreen(worldScreen, viewingCiv))
            }).width(260f).row()
            any = true
        }

        // 贸易请求 (商路/贸易提议; 服务器存档驱动 — 回应前保留)
        for (req in viewingCiv.tradeRequests.toList()) {
            val civName = gameInfo.getCivilizationOrNull(req.requestingCiv)?.civName?.tr() ?: req.requestingCiv
            table.add(menuButton("Trade request: [$civName]".tr()) {
                // TradePopup 固定取 first → 把点中的那条移到队首再开
                val list = viewingCiv.tradeRequests
                if (list.firstOrNull { it === req } != null) {
                    list.remove(req)
                    list.add(0, req)
                }
                TradePopup(worldScreen).open()
            }).width(260f).row()
            any = true
        }

        return if (any) table else null
    }

    override fun createFixedContent(): Table? = null

    /** 事件项按钮 (无键盘绑定 — 动态数量, 同绑定会全部触发) */
    private fun menuButton(text: String, action: () -> Unit): Actor =
        text.toTextButton().apply {
            onActivation {
                action()
                close()
            }
        }

    /** 弹窗摘要文案 (按 AlertType 映射) — 全部走 tr() 英文源串, 动态名用 [x] 占位符由 tr() 替换 */
    private fun alertLabel(alert: PopupAlert): String {
        val value = alert.value
        return when (alert.type) {
            AlertType.Event -> {
                val name = value.split(Constants.stringSplitCharacter)[0]
                gameInfo.ruleset.events[name]?.name ?: name
            }
            AlertType.WonderBuilt -> "Wonder built: [${gameInfo.ruleset.buildings[value]?.name ?: value}]".tr()
            AlertType.TechResearched -> "Technology researched: [${gameInfo.ruleset.technologies[value]?.name ?: value}]".tr()
            AlertType.WarDeclaration -> "Declared war on: [${civLabel(value)}]".tr()
            AlertType.FirstContact -> "Met: [${civLabel(value)}]".tr()
            AlertType.CityConquered -> "City conquered: [${cityLabel(value)}]".tr()
            AlertType.CityTraded -> "City traded: [${cityLabel(value)}]".tr()
            AlertType.BorderConflict -> "Border conflict: [${civLabel(value)}]".tr()
            AlertType.TilesStolen -> "Tiles stolen: [${civLabel(value)}]".tr()
            AlertType.DemandToStopSettlingCitiesNear -> "Demand to stop settling: [${civLabel(value)}]".tr()
            AlertType.CitySettledNearOtherCivDespiteOurPromise -> "Settled despite promise: [${civLabel(value)}]".tr()
            AlertType.DemandToStopSpreadingReligion -> "Demand to stop spreading religion: [${civLabel(value)}]".tr()
            AlertType.ReligionSpreadDespiteOurPromise -> "Spread religion despite promise: [${civLabel(value)}]".tr()
            AlertType.DemandToStopSpyingOnUs -> "Demand to stop spying: [${civLabel(value)}]".tr()
            AlertType.SpyingOnUsDespiteOurPromise -> "Spied despite promise: [${civLabel(value)}]".tr()
            AlertType.DemandToNotAttackUs -> "Demand to not attack us: [${civLabel(value)}]".tr()
            AlertType.AttackedUsDespitePromise -> "Attacked despite promise: [${civLabel(value)}]".tr()
            AlertType.AcceptingDemand -> "Accepting demand: [${civLabel(value)}]".tr()
            AlertType.RejectingDemand -> "Rejecting demand: [${civLabel(value)}]".tr()
            AlertType.GoldenAge -> "Golden Age".tr()
            AlertType.DeclarationOfFriendship -> "Declaration of Friendship: [${civLabel(value)}]".tr()
            AlertType.StartIntro -> "Opening".tr()
            AlertType.DiplomaticMarriage -> "Diplomatic marriage: [${cityLabel(value)}]".tr()
            AlertType.BulliedProtectedMinor, AlertType.AttackedProtectedMinor, AlertType.AttackedAllyMinor ->
                "City-state event: [${value.split('@').firstOrNull()?.let { civLabel(it) } ?: ""}]".tr()
            AlertType.RecapturedCivilian -> "Recaptured civilian".tr()
            AlertType.GameHasBeenWon -> "Game has been won".tr()
            AlertType.Defeated -> "Civilization defeated: [${civLabel(value)}]".tr()
            AlertType.Denounced -> "Denounced: [${civLabel(value)}]".tr()
            AlertType.TradeRouteOffer -> {
                val parts = value.split("|")
                if (parts.size >= 2)
                    "Trade invitation: [${cityLabel(parts[0])}] to [${cityLabel(parts[1])}]".tr()
                else "Trade invitation".tr()
            }
            AlertType.AllianceOffer -> "Alliance offer: [${civLabel(value)}]".tr()
            AlertType.AllianceRenew -> "Alliance renewal: [${civLabel(value)}]".tr()
            AlertType.AllianceFollowUp -> "Ally war follow-up: [${civLabel(value)}]".tr()
        }
    }

    private fun civLabel(idOrName: String): String =
        gameInfo.getCivilizationOrNull(idOrName)?.civName?.tr() ?: idOrName

    private fun cityLabel(id: String): String =
        gameInfo.getCities().firstOrNull { it.id == id }?.name?.tr() ?: id
}

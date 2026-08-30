package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.UncivGame
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.pad
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.diplomacyscreen.DiplomacyScreen
import com.unciv.ui.screens.diplomacyscreen.LeaderIntroTable
import yairm210.purity.annotations.Readonly
import kotlin.math.max
import kotlin.math.min
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane

/* TODO:
    different Notification wording for peace treaties?
    Let Notification jump to Diplomacy.trade with empty offers (accepted)
        or a clone of the rejected offer (denied) ...?
*/

/**
 * [Popup] communicating trade offers of others to the player.
 *
 * Called in [WorldScreen].update, which checks if there are any in viewingCiv.tradeRequests.
 *
 * @param worldScreen The parent screen
 */
class TradePopup(worldScreen: WorldScreen) : Popup(worldScreen) {
    val viewingCiv = worldScreen.viewingCiv
    val tradeRequest = viewingCiv.tradeRequests.first()

    init {
        val requestingCiv = worldScreen.gameInfo.getCivilization(tradeRequest.requestingCiv)
        val nation = requestingCiv.nation
        val trade = tradeRequest.trade


        val ourResources = viewingCiv.getCivResourcesByName()

        val leaderIntroTable = LeaderIntroTable(requestingCiv)
        add(leaderIntroTable)
        addSeparator()

        val tradeOffersTable = Table().apply { defaults().pad(10f) }
        tradeOffersTable.add("[${nation.name}]'s trade offer".toLabel())
        // empty column to separate offers columns better
        tradeOffersTable.add().pad(0f, 15f)
        tradeOffersTable.add("Our trade offer".toLabel())
        tradeOffersTable.row()

        
        @Readonly
        fun getOfferText(offer:TradeOffer): String {
            var tradeText = offer.getOfferText()
            if (offer.type == TradeOfferType.Luxury_Resource || offer.type == TradeOfferType.Strategic_Resource)
                tradeText += "\n" + "Owned by you: [${ourResources[offer.name]}]".tr()
            return tradeText
        }

        for (i in 0..max(trade.theirOffers.lastIndex, trade.ourOffers.lastIndex)) {
            if (trade.theirOffers.lastIndex < i) tradeOffersTable.add()
            else tradeOffersTable.add(getOfferText(trade.theirOffers[i]).toLabel())
            tradeOffersTable.add()
            if (trade.ourOffers.lastIndex < i) tradeOffersTable.add()
            else tradeOffersTable.add(getOfferText(trade.ourOffers[i]).toLabel())
            tradeOffersTable.row()
        }
        tradeOffersTable.pack()

        val scrollHeight = min(tradeOffersTable.height, worldScreen.stage.height/2)
        add(ScrollPane(tradeOffersTable)).height(scrollHeight).row()

        addSeparator(Color.DARK_GRAY, height = 1f)

        // Starting playback here assumes the TradePopup is shown immediately
        UncivGame.Current.musicController.playVoice("${requestingCiv.civName}.tradeRequest")
        addGoodSizedLabel(nation.tradeRequest).pad(15f).row()

        addButton("Sounds good!", 'y') {
            if (FrameSync.isFsMode(viewingCiv.gameInfo)) {
                // 帧同步: 服务器权威接受 (转移/条约在服务器执行, 状态广播同步)
                FrameSync.sendTradeAccept(tradeRequest.requestingCiv)
                // 2026-08-31 修复: 接受方本地应用对方提供的 embassy — tradeAccepted 广播只给发起方,
                // 接受方侧 modifier 缺失 (互卖大使馆"自己没卖出去/还是对面的"根因; 服务器已应用, 本地补齐显示)
                if (trade.theirOffers.any { it.type == TradeOfferType.Embassy }) {
                    try {
                        val theirDm = requestingCiv.getDiplomacyManager(viewingCiv)
                        if (theirDm != null) {
                            if (theirDm.hasModifier(com.unciv.logic.civilization.diplomacy.DiplomaticModifiers.EstablishedEmbassy)) {
                                theirDm.replaceModifier(com.unciv.logic.civilization.diplomacy.DiplomaticModifiers.EstablishedEmbassy,
                                    com.unciv.logic.civilization.diplomacy.DiplomaticModifiers.SharedEmbassies, 3f)
                                theirDm.otherCivDiplomacy().replaceModifier(com.unciv.logic.civilization.diplomacy.DiplomaticModifiers.ReceivedEmbassy,
                                    com.unciv.logic.civilization.diplomacy.DiplomaticModifiers.SharedEmbassies, 3f)
                            } else {
                                theirDm.addModifier(com.unciv.logic.civilization.diplomacy.DiplomaticModifiers.ReceivedEmbassy, 1f)
                                theirDm.otherCivDiplomacy().addModifier(com.unciv.logic.civilization.diplomacy.DiplomaticModifiers.EstablishedEmbassy, 2f)
                            }
                        }
                    } catch (ignored: Exception) {
                    }
                }
                viewingCiv.tradeRequests.remove(tradeRequest)
                close()
            } else {
                val tradeLogic = TradeLogic(viewingCiv, requestingCiv)
                tradeLogic.currentTrade.set(trade)
                tradeLogic.acceptTrade()
                close()
                TradeThanksPopup(leaderIntroTable, worldScreen)
                requestingCiv.addNotification("[${viewingCiv.civName}] has accepted your trade request", NotificationCategory.Trade, viewingCiv.civName, NotificationIcon.Trade)
            }
        }.row()

        addButton("Not this time.", 'n') {
            if (FrameSync.isFsMode(viewingCiv.gameInfo)) {
                // 帧同步: 服务器移除挂起提议
                FrameSync.sendTradeReject(tradeRequest.requestingCiv)
                viewingCiv.tradeRequests.remove(tradeRequest)
                close()
            } else {
                tradeRequest.decline(viewingCiv)
                close()
                requestingCiv.addNotification("[${viewingCiv.civName}] has denied your trade request", NotificationCategory.Trade, viewingCiv.civName, NotificationIcon.Trade)
                worldScreen.shouldUpdate = true
            }
        }.row()

        addButton("How about something else...", 'e') {
            close()
            worldScreen.game.pushScreen(DiplomacyScreen(viewingCiv, requestingCiv, trade))
            worldScreen.shouldUpdate = true
        }.row()
    }

    override fun close() {
        // 清掉该发起方的所有请求 (含广播重复/补推替换后的新引用) — 防“关闭后残留再弹”
        viewingCiv.tradeRequests.removeAll { it.requestingCiv == tradeRequest.requestingCiv }
        super.close()
    }

    class TradeThanksPopup(leaderIntroTable: LeaderIntroTable, worldScreen: WorldScreen) : Popup(worldScreen) {
        init {
            add(leaderIntroTable)
            addSeparator().padBottom(15f)
            addGoodSizedLabel("Excellent!").row()
            addCloseButton("Farewell.", KeyCharAndCode.SPACE) {
                worldScreen.shouldUpdate = true
                // in all cases, worldScreen.shouldUpdate should be set to true when we remove the last of the popups
                // in order for the next trade to appear immediately
            }
            open()
        }
    }
}

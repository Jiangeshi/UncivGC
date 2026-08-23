package com.unciv.ui.screens.cityscreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.city.City
import com.unciv.logic.trade.TradeRouteNetwork
import com.unciv.logic.trade.TradeRoutes
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.packIfNeeded
import com.unciv.ui.components.extensions.pad
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.diplomacyscreen.DiplomacyScreen
import com.unciv.ui.screens.worldscreen.FrameSync
import java.text.DecimalFormat

/** UncivGC 2026-08-24: 商路详情弹窗 (设计稿 §四) — 城市界面统计按钮旁「商路」按钮打开 */
class TradeRoutesPopup(private val cityScreen: CityScreen) : Popup(cityScreen, Scrollability.None) {
    private val city: City = cityScreen.cityView.city
    private val decimal = DecimalFormat("0.#")

    init {
        val header = "商路详情 · ${city.name}".toLabel()
        header.setAlignment(Align.center)
        add(header).pad(5f).growX().row()

        val scrollPane = AutoScrollPane(innerTable)
        scrollPane.setOverscroll(false, false)
        val scrollPaneCell = add(scrollPane).padTop(0f)
        scrollPaneCell.maxHeight(cityScreen.stage.height * 3 / 4)

        row()
        addCloseButton(additionalKey = KeyCharAndCode.SPACE)

        update()
    }

    private fun update() {
        innerTable.clear()
        val gameInfo = city.civ.gameInfo
        val network = gameInfo.getTradeRouteNetwork()
        val routes = network.getRoutes(city)
        if (routes.isEmpty()) {
            innerTable.add("该城市当前没有商路连接".toLabel()).pad(10f).row()
            innerTable.packIfNeeded()
            return
        }
        val domestic = routes.filter { it.otherCity.civ == city.civ }
            .sortedByDescending { TradeRoutes.baseFor(city, it) }
        val foreign = routes.filter { it.otherCity.civ != city.civ }
            .sortedByDescending { TradeRoutes.baseFor(city, it) }

        addGroup("国内商路", domestic, canBlock = false)
        addGroup("国外商路", foreign, canBlock = true)
        innerTable.packIfNeeded()
    }

    private fun addGroup(title: String, routes: List<TradeRouteNetwork.Route>, canBlock: Boolean) {
        if (routes.isEmpty()) return
        var totalGold = 0f
        for (route in routes) {
            val rank = TradeRoutes.rankOf(city, route)
            totalGold += TradeRoutes.actualStats(city, route, rank).gold
        }
        innerTable.add("$title（总计收益：${decimal.format(totalGold)} 金币/回合）".toLabel())
            .colspan(7).padTop(8f).padBottom(2f).row()
        innerTable.addSeparator(colSpan = 7)
        // 表头
        for (head in listOf("#", "联通城市", "方式", "距离", "贸易收益", "额外收益", "操作"))
            innerTable.add(head.toLabel()).pad(2f, 4f)
        innerTable.row()
        innerTable.addSeparator(colSpan = 7)

        for ((index, route) in routes.withIndex()) {
            val rank = TradeRoutes.rankOf(city, route)
            val stats = TradeRoutes.actualStats(city, route, rank)
            val other = route.otherCity
            innerTable.add((index + 1).toString().toLabel()).pad(2f, 4f)
            innerTable.add(other.name.toLabel()).pad(2f, 4f)
            innerTable.add((if (route.isSea) "海路" else "陆路").toLabel()).pad(2f, 4f)
            innerTable.add(route.distance.toString().toLabel()).pad(2f, 4f)
            innerTable.add("${decimal.format(stats.gold)} 金币".toLabel()).pad(2f, 4f)
            innerTable.add(formatExtra(stats).toLabel()).pad(2f, 4f)
            innerTable.add(buildActionButtons(route, other, canBlock)).pad(2f, 4f)
            innerTable.row()
        }
        innerTable.addSeparator(colSpan = 7)
    }

    private fun formatExtra(stats: Stats): String {
        val parts = mutableListOf<String>()
        for (stat in Stat.entries) {
            if (stat == Stat.Gold) continue
            val value = stats[stat]
            if (value != 0f) parts.add("${decimal.format(value)} ${stat.name.tr()}")
        }
        return if (parts.isEmpty()) "—" else parts.joinToString("、")
    }

    private fun buildActionButtons(route: TradeRouteNetwork.Route, other: City, canBlock: Boolean): Table {
        val buttons = Table()
        val gameInfo = city.civ.gameInfo
        val pair = TradeRouteNetwork.pairKey(city, other)
        val blocked = pair in gameInfo.tradeRouteBlocked
        val myCivId = city.civ.civID
        val myRequested = gameInfo.tradeRouteRestoreRequests[pair]?.contains(myCivId) == true
        val otherRequested = gameInfo.tradeRouteRestoreRequests[pair]?.contains(other.civ.civID) == true

        if (canBlock) {
            if (blocked && myRequested) {
                buttons.add(if (otherRequested) "对方已请求".toLabel() else "等待对方…".toLabel())
                    .pad(2f)
            } else {
                val label = when {
                    !blocked -> "取消"
                    else -> if (otherRequested) "确认恢复" else "恢复"
                }
                buttons.add(label.toTextButton().apply {
                    onClick {
                        // 乐观更新本地状态 (服务器广播回来会再次对齐)
                        if (!blocked) {
                            gameInfo.tradeRouteBlocked.add(pair)
                            gameInfo.tradeRouteRestoreRequests.remove(pair)
                        } else {
                            gameInfo.tradeRouteRestoreRequests.getOrPut(pair) { HashSet() }.add(myCivId)
                            // 城邦无玩家操作: 单方请求即恢复 (与服务器逻辑一致)
                            if (other.civ.isCityState || otherRequested) {
                                gameInfo.tradeRouteBlocked.remove(pair)
                                gameInfo.tradeRouteRestoreRequests.remove(pair)
                            }
                        }
                        gameInfo.invalidateTradeRoutes()
                        // 仅帧同步发 op (单机直接本地生效, 不发网络消息)
                        if (com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(gameInfo))
                            FrameSync.sendOp("city.toggleTradeRouteBlocked", mapOf(
                                "cityId" to city.id,
                                "otherCityId" to other.id
                            ))
                        update()
                    }
                }).pad(2f)
            }
        }
        if (other.civ != city.civ) {
            buttons.add("交易".toTextButton().apply {
                onClick {
                    close()
                    cityScreen.game.pushScreen(DiplomacyScreen(city.civ, other.civ))
                }
            }).pad(2f)
        }
        return buttons
    }
}

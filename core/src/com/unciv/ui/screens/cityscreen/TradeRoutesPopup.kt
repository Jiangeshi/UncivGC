package com.unciv.ui.screens.cityscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.city.City
import com.unciv.logic.trade.TradeRouteNetwork
import com.unciv.logic.trade.TradeRoutes
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.brighten
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.packIfNeeded
import com.unciv.ui.components.extensions.pad
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.diplomacyscreen.DiplomacyScreen
import com.unciv.ui.screens.worldscreen.FrameSync
import java.text.DecimalFormat

/** UncivGC 2026-08-24: 商路详情弹窗 (设计稿 §四) — 城市界面统计按钮旁「商路」按钮打开
 *  2026-08-24 15:09 用户反馈: 参考统计 (DetailedStatsPopup) 表格样式, 加大尺寸, 空状态也显示表头 */
class TradeRoutesPopup(private val cityScreen: CityScreen) : Popup(cityScreen, Scrollability.None) {
    private val city: City = cityScreen.cityView.city
    private val decimal = DecimalFormat("0.#")
    // 滚动内容用独立 Table — 不能用 Popup.innerTable: AutoScrollPane(innerTable) 后再 add(scrollPane)
    // 会循环包含 (innerTable→scrollPane→innerTable) → setStage StackOverflow (2026-08-24 用户点击商路按钮崩溃)
    private val contentTable = Table()

    private val colorHeaderBg: Color = Color.valueOf("4a4a5a").darken(0.2f)
    private val colorGroupBg: Color = Color.valueOf("3a3a4a").darken(0.2f)

    init {
        val header = ("商路详情".tr() + " · " + city.name.tr()).toLabel()
        header.setAlignment(Align.center)
        add(header).pad(8f).growX().row()

        val scrollPane = AutoScrollPane(contentTable)
        scrollPane.setOverscroll(false, false)
        val scrollPaneCell = add(scrollPane).padTop(0f)
        scrollPaneCell.maxHeight(cityScreen.stage.height * 3 / 4)
        scrollPaneCell.minWidth(cityScreen.stage.width * 3 / 5)

        row()
        addCloseButton(additionalKey = KeyCharAndCode.SPACE)

        update()
    }

    private fun update() {
        contentTable.clear()
        val gameInfo = city.civ.gameInfo
        val network = gameInfo.getTradeRouteNetwork()
        val routes = network.getRoutes(city)
        val domestic = routes.filter { it.otherCity.civ == city.civ }
            .sortedByDescending { TradeRoutes.baseFor(city, it) }
        val foreign = routes.filter { it.otherCity.civ != city.civ }
            .sortedByDescending { TradeRoutes.baseFor(city, it) }

        addGroup("国内商路", domestic, canBlock = false, showEmptyHeader = true)
        addGroup("国外商路", foreign, canBlock = true, showEmptyHeader = true)
        contentTable.packIfNeeded()
    }

    private fun addGroup(title: String, routes: List<TradeRouteNetwork.Route>, canBlock: Boolean, showEmptyHeader: Boolean) {
        var totalGold = 0f
        for (route in routes) {
            val rank = TradeRoutes.rankOf(city, route)
            totalGold += TradeRoutes.actualStats(city, route, rank).gold
        }
        // 分组标题: 带背景 + 总计收益 (即使无商路也显示表头 — 2026-08-24 用户要求参考统计格式)
        val groupHeader = Table()
        groupHeader.background = BaseScreen.skinStrings.getUiBackground("General/Border", tintColor = colorGroupBg)
        groupHeader.add("$title（总计收益：${decimal.format(totalGold)} 金币/回合）".toLabel())
            .pad(8f, 12f).growX()
        contentTable.add(groupHeader).colspan(7).growX().padTop(10f).row()

        addHeaderRow()

        if (routes.isEmpty()) {
            if (showEmptyHeader) {
                val empty = "暂无商路连接".toLabel()
                empty.setAlignment(Align.center)
                contentTable.add(empty).colspan(7).pad(10f).growX().row()
            }
            return
        }

        for ((index, route) in routes.withIndex()) {
            val rank = TradeRoutes.rankOf(city, route)
            val stats = TradeRoutes.actualStats(city, route, rank)
            val other = route.otherCity
            contentTable.add((index + 1).toString().toLabel()).minWidth(columnWidths[0]).pad(6f, 10f)
            contentTable.add(other.name.tr().toLabel()).minWidth(columnWidths[1]).pad(6f, 10f)
            contentTable.add((if (route.isSea) "海路" else "陆路").toLabel()).minWidth(columnWidths[2]).pad(6f, 10f)
            contentTable.add(route.distance.toString().toLabel()).minWidth(columnWidths[3]).pad(6f, 10f)
            contentTable.add("${decimal.format(stats.gold)} 金币".toLabel()).minWidth(columnWidths[4]).pad(6f, 10f)
            contentTable.add(formatExtra(stats).toLabel()).minWidth(columnWidths[5]).pad(6f, 10f)
            contentTable.add(buildActionButtons(route, other, canBlock)).minWidth(columnWidths[6]).pad(4f, 6f)
            contentTable.row()
        }
        contentTable.addSeparator(colSpan = 7).padTop(4f)
    }

    /** 表头行 (统计同款: 带背景 + 居中对齐) — 无商路也显示
     *  列宽与内容行统一 (width/minWidth 同值), 否则表头独立 Table 与内容行不对齐 (2026-08-24 用户反馈) */
    private val columnWidths = listOf(50f, 120f, 80f, 80f, 130f, 160f, 190f)

    private fun addHeaderRow() {
        val headerRow = Table()
        headerRow.background = BaseScreen.skinStrings.getUiBackground("General/Border", tintColor = colorHeaderBg)
        val heads = listOf("#", "联通城市", "方式", "距离", "贸易收益", "额外收益", "操作")
        for ((i, head) in heads.withIndex()) {
            val label = head.toLabel().apply { setAlignment(Align.center) }
            headerRow.add(label).width(columnWidths[i]).pad(8f, 6f)
        }
        contentTable.add(headerRow).colspan(7).growX().row()
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

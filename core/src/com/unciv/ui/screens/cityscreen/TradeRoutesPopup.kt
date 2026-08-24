package com.unciv.ui.screens.cityscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.city.City
import com.unciv.logic.trade.TradeRouteNetwork
import com.unciv.logic.trade.TradeRoutes
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.models.ruleset.unique.UniqueType
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
    // 列宽 (表头与内容行 minWidth 同值) — 必须声明在 init 之前: Kotlin 属性按声明顺序初始化 (2026-08-24 NPE)
    private val columnWidths = listOf(80f, 120f, 80f, 80f, 130f, 100f, 130f)
    // 滚动内容用独立 Table — 不能用 Popup.innerTable: AutoScrollPane(innerTable) 后再 add(scrollPane)
    // 会循环包含 (innerTable→scrollPane→innerTable) → setStage StackOverflow (2026-08-24 用户点击商路按钮崩溃)
    private val contentTable = Table()

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
        // 分组标题: 居中文本 + 分隔线 (参考统计 "Base values" 行排版, 无背景 — 2026-08-24 用户要求)
        val groupLabel = "$title（总计收益：${decimal.format(totalGold)} 金币/回合）".toLabel()
        groupLabel.setAlignment(Align.center)
        contentTable.add(groupLabel).colspan(7).growX().padTop(10f).padBottom(2f).row()
        contentTable.addSeparator(colSpan = 7)

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
            val other = route.otherCity
            val seaFactor = if (route.isSea) 0.7f else 1f  // 与 TradeRoutes.actualStats 一致 (2026-08-25)
            val resBonus = TradeRoutes.resourceBonus(city, other)
            val resBonusDecayed = resBonus * seaFactor
            // 贸易收益列 = 纯人口距离基础 (吃排名衰减 + 海商修正)
            val popGold = (route.otherCity.population.population * 0.3f + city.population.population * 0.3f) *
                TradeRoutes.distFactor(route.distance)
            val baseGold = popGold / rank * seaFactor
            // 额外收益列 = unique 加成 (含金币, 吃 1/(rank+1) 衰减 + 海商修正)
            val uniqueStats = Stats()
            for (unique in city.getMatchingUniques(UniqueType.StatsFromTradeRoute))
                uniqueStats.add(unique.stats * (1f / (rank + 1)) * seaFactor)
            addRouteRow(index + 1, route, other, baseGold, resBonusDecayed, uniqueStats)
        }
        contentTable.addSeparator(colSpan = 7).padTop(4f)
    }

    /** 表头行: 直接排在 contentTable 里 (与内容行同一列体系 → 天然对齐; 参考统计排版, 无背景) — 2026-08-24
     *  之前用独立 Table + colspan 导致两套列宽 → 内容列被撑大时表头错位 */
    private fun addRouteRow(index: Int, route: TradeRouteNetwork.Route, other: City,
                             baseGold: Float, resBonusDecayed: Float, stats: Stats) {
        fun makeLabel(text: String) = text.toLabel().apply { setAlignment(Align.center) }
        contentTable.add(makeLabel(index.toString())).minWidth(columnWidths[0]).pad(6f, 10f)
        contentTable.add(makeLabel(other.name.tr())).minWidth(columnWidths[1]).pad(6f, 10f)
        contentTable.add(makeLabel(if (route.isSea) "海路" else "陆路")).minWidth(columnWidths[2]).pad(6f, 10f)
        contentTable.add(makeLabel(route.distance.toString())).minWidth(columnWidths[3]).pad(6f, 10f)
        contentTable.add(makeLabel("${decimal.format(baseGold)} 金币")).minWidth(columnWidths[4]).pad(6f, 10f)
        contentTable.add(makeLabel(if (resBonusDecayed > 0f) "${decimal.format(resBonusDecayed)} 金币" else "—")).minWidth(columnWidths[5]).pad(6f, 10f)
        contentTable.add(makeLabel(formatExtra(stats))).minWidth(columnWidths[6]).pad(6f, 10f)
        contentTable.row()
    }

    private fun addHeaderRow() {
        val heads = listOf("序号", "联通城市", "方式", "距离", "贸易收益", "资源收益", "额外收益")
        for ((i, head) in heads.withIndex()) {
            val label = head.toLabel().apply { setAlignment(Align.center) }
            contentTable.add(label).minWidth(columnWidths[i]).pad(6f, 10f)
        }
        contentTable.row()
        contentTable.addSeparator(colSpan = 7)
    }

    private fun formatExtra(stats: Stats): String {
        val parts = mutableListOf<String>()
        for (stat in Stat.entries) {
            val value = stats[stat]
            if (value != 0f) parts.add("${decimal.format(value)} ${stat.name.tr()}")
        }
        return if (parts.isEmpty()) "—" else parts.joinToString("、")
    }

    private fun buildActionButtons(route: TradeRouteNetwork.Route, other: City, canBlock: Boolean, isBlocked: Boolean): Table {
        val buttons = Table()
        val gameInfo = city.civ.gameInfo
        val pair = TradeRouteNetwork.pairKey(city, other)
        val myCivId = city.civ.civID
        val myRequested = gameInfo.tradeRouteRestoreRequests[pair]?.contains(myCivId) == true
        val otherRequested = gameInfo.tradeRouteRestoreRequests[pair]?.contains(other.civ.civID) == true

        if (canBlock && isBlocked) {
            if (myRequested) {
                buttons.add(if (otherRequested) "对方已请求".toLabel() else "等待对方…".toLabel()).pad(2f)
            } else {
                buttons.add("恢复".toTextButton().apply {
                    onClick {
                        gameInfo.tradeRouteRestoreRequests.getOrPut(pair) { HashSet() }.add(myCivId)
                        if (other.civ.isCityState || otherRequested) {
                            gameInfo.tradeRouteBlocked.remove(pair)
                            gameInfo.tradeRouteRestoreRequests.remove(pair)
                        }
                        gameInfo.invalidateTradeRoutes()
                        if (FrameSync.isFsMode(gameInfo))
                            FrameSync.sendOp("city.toggleTradeRouteBlocked", mapOf("cityId" to city.id, "otherCityId" to other.id))
                        update()
                    }
                }).pad(2f)
            }
        }
        buttons.add("交易".toTextButton().apply {
            isDisabled = true  // 装饰按钮，暂不启用
            color = Color.GRAY
        }).pad(2f)
        return buttons
    }
}

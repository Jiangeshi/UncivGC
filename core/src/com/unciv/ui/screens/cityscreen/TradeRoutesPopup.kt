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
import com.unciv.ui.components.extensions.pad
import com.unciv.ui.components.extensions.packIfNeeded
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.worldscreen.FrameSync
import java.text.DecimalFormat

/** 商路管理弹窗 (UncivGC 2026-08-26 设计稿 v2): 每城管理自己的 — 本城发起/本城接收/可建立。
 *  表格列: 类型(内商/外商) | 目的地 | 距离 | 方式 | 收益(stat) | 操作
 *  纯拦截: 操作发 op, 等服务器广播后刷新 */
class TradeRoutesPopup(screen: com.unciv.ui.screens.basescreen.BaseScreen, private val city: City) :
    Popup(screen, Scrollability.None) {
    private val decimal = DecimalFormat("0.#")
    private val columnWidths = listOf(60f, 120f, 60f, 60f, 110f, 110f, 110f)
    private val contentTable = Table()
    /** 广播到达后自动刷新 (纯拦截: 建立/断开后等服务器 state 同步) */
    private var lastRoutesSig = ""

    init {
        val header = ("贸易路线".tr() + " · " + city.name.tr()).toLabel()
        header.setAlignment(Align.center)
        add(header).pad(8f).growX().row()

        val scrollPane = AutoScrollPane(contentTable)
        scrollPane.setOverscroll(false, false)
        val scrollPaneCell = add(scrollPane).padTop(0f)
        scrollPaneCell.maxHeight(screen.stage.height * 3 / 4)
        scrollPaneCell.minWidth(screen.stage.width * 3 / 5)

        row()
        addCloseButton(additionalKey = KeyCharAndCode.SPACE)

        update()
    }

    override fun act(delta: Float) {
        super.act(delta)
        val sig = city.civ.gameInfo.tradeRoutes.toString() + "|" + city.civ.gameInfo.tradeRouteOffers.toString() +
                "|" + city.civ.gameInfo.tradeRouteCooldowns.toString()
        if (sig != lastRoutesSig) {
            lastRoutesSig = sig
            update()
        }
    }

    private fun update() {
        contentTable.clear()
        val gameInfo = city.civ.gameInfo
        val network = gameInfo.getTradeRouteNetwork()
        val myCiv = city.civ
        val cap = TradeRoutes.capacity(myCiv)
        val used = TradeRoutes.usedByCiv(myCiv)

        val capLabel = "容量：$used / $cap".toLabel()
        capLabel.setAlignment(Align.center)
        contentTable.add(capLabel).colspan(6).growX().padTop(6f).padBottom(2f).center().row()
        contentTable.addSeparator(colSpan = 6)

        // 我的路线 (本城发起)
        val initiatorRoutes = network.getEstablishedRoutes(city).filter { network.isInitiator(city, it.otherCity) }
            .sortedByDescending { TradeRoutes.initiatorStats(city, it).gold }
        addGroup("我的路线", initiatorRoutes, isInitiatorGroup = true, showEmpty = true)

        // 我的接收 (本城接收)
        val receiverRoutes = network.getEstablishedRoutes(city).filter { !network.isInitiator(city, it.otherCity) }
        addGroup("我的接收", receiverRoutes, isInitiatorGroup = false, showEmpty = true)

        // 可建立 (可达且未连接, 容量未满)
        if (used < cap) {
            val existing = network.getEstablishedRoutes(city).map { it.otherCity.id }.toSet()
            val offerTargets = gameInfo.tradeRouteOffers.values.flatten().toSet()
            val candidates = network.getReachable(city)
                .filter { it.otherCity.id !in existing && it.otherCity.id !in offerTargets }
                .sortedByDescending { TradeRoutes.initiatorStats(city, it).gold }
            addCandidates(candidates)
        } else {
            val full = "容量已满（断开后可建立新路线）".toLabel()
            full.setAlignment(Align.center)
            full.color = Color.GRAY
            contentTable.add(full).colspan(6).pad(8f).growX().row()
        }
        contentTable.packIfNeeded()
    }

    private fun addGroup(title: String, routes: List<TradeRouteNetwork.Route>, isInitiatorGroup: Boolean, showEmpty: Boolean) {
        val gameInfo = city.civ.gameInfo
        val groupLabel = title.toLabel()
        groupLabel.setAlignment(Align.center)
        contentTable.add(groupLabel).colspan(6).growX().padTop(10f).padBottom(2f).row()
        contentTable.addSeparator(colSpan = 6)
        addHeaderRow()

        if (routes.isEmpty()) {
            if (showEmpty) {
                val empty = "暂无".toLabel()
                empty.setAlignment(Align.center)
                empty.color = Color.GRAY
                contentTable.add(empty).colspan(6).pad(8f).growX().row()
            }
            return
        }

        for (route in routes) {
            val other = route.otherCity
            val type = if (other.civ == city.civ) "内商" else "外商"
            val way = when {
                route.isSea -> "海路"
                route.hasRailroad -> "铁路"
                else -> "道路"
            }
            // 收益函数第一参数必须是发起方城市: 本城发起 → city; 本城接收 → route.otherCity (2026-08-26 修复)
            val fromCity = if (isInitiatorGroup) city else route.otherCity
            val myStats = if (isInitiatorGroup) TradeRoutes.initiatorStats(fromCity, route)
                          else TradeRoutes.receiverStats(fromCity, route)
            val theirStats = if (isInitiatorGroup) TradeRoutes.receiverStats(fromCity, route)
                             else TradeRoutes.initiatorStats(fromCity, route)
            contentTable.add(makeLabel(type)).minWidth(columnWidths[0]).pad(6f, 8f)
            contentTable.add(makeLabel(other.name.tr())).minWidth(columnWidths[1]).pad(6f, 8f)
            contentTable.add(makeLabel(route.distance.toString())).minWidth(columnWidths[2]).pad(6f, 8f)
            contentTable.add(makeLabel(way)).minWidth(columnWidths[3]).pad(6f, 8f)
            contentTable.add(makeLabel(formatStats(myStats))).minWidth(columnWidths[4]).pad(6f, 8f)
            contentTable.add(makeLabel(formatStats(theirStats))).minWidth(columnWidths[5]).pad(6f, 8f)
            val opCell = Table()
            opCell.add("断开".toTextButton().apply {
                onClick {
                    // 确认弹窗 (2026-08-26 用户要求): 防误触; 确认前先禁用按钮防重复弹窗
                    isDisabled = true
                    ConfirmPopup(stageToShowOn, "确定断开与 ${other.name.tr()} 的商路？\n断开后 3 回合内本城不能发起新商路", "断开", isConfirmPositive = false, restoreDefault = { isDisabled = false }) {
                        if (FrameSync.isFsMode(gameInfo)) {
                            FrameSync.sendTradeRouteDisconnect(city.id, other.id)
                        } else {
                            // 单机: 本地直接执行 (任一方断开)
                            val fromList = gameInfo.tradeRoutes[city.id]
                            if (fromList != null && fromList.remove(other.id) && fromList.isEmpty())
                                gameInfo.tradeRoutes.remove(city.id)
                            val revList = gameInfo.tradeRoutes[other.id]
                            if (revList != null && revList.remove(city.id) && revList.isEmpty())
                                gameInfo.tradeRoutes.remove(other.id)
                            gameInfo.invalidateTradeRoutes()
                            // 断开冷却 (2026-08-26 用户要求): 本城 3 回合内不能发起新商路
                            gameInfo.tradeRouteCooldowns[city.id] = gameInfo.turns
                            try { city.cityStats.update() } catch (ignored: Exception) {}
                        }
                    }.open(force = true)
                }
            }).pad(2f)
            contentTable.add(opCell).minWidth(columnWidths[6]).pad(6f, 8f)
            contentTable.row()
        }
        contentTable.addSeparator(colSpan = 6).padTop(4f)
    }

    private fun addCandidates(candidates: List<TradeRouteNetwork.Route>) {
        val gameInfo = city.civ.gameInfo
        val groupLabel = "可建立商路".toLabel()
        groupLabel.setAlignment(Align.center)
        contentTable.add(groupLabel).colspan(6).growX().padTop(10f).padBottom(2f).row()
        contentTable.addSeparator(colSpan = 6)

        // 发起条件 (2026-08-26 用户要求): 无已开发地块资源的城市不能发起商路
        if (!TradeRoutes.canInitiate(city)) {
            val notice = "需要已开发的地块资源（改良后的资源地块）才能发起商路".toLabel()
            notice.setAlignment(Align.center)
            notice.color = Color.GRAY
            contentTable.add(notice).colspan(6).pad(8f).growX().row()
            return
        }
        addHeaderRow()

        if (candidates.isEmpty()) {
            val empty = "无可建立目标（需要道路/港口连接且在距离上限内）".toLabel()
            empty.setAlignment(Align.center)
            empty.color = Color.GRAY
            contentTable.add(empty).colspan(6).pad(8f).growX().row()
            return
        }

        for (route in candidates) {
            val other = route.otherCity
            val myStats = TradeRoutes.initiatorStats(city, route)
            val theirStats = if (other.civ == city.civ) TradeRoutes.receiverStats(city, route)
                             else TradeRoutes.receiverStats(city, route)
            val type = if (other.civ == city.civ) "内商" else "外商"
            val way = when {
                route.isSea -> "海路"
                route.hasRailroad -> "铁路"
                else -> "道路"
            }
            contentTable.add(makeLabel(type)).minWidth(columnWidths[0]).pad(6f, 8f)
            contentTable.add(makeLabel(other.name.tr())).minWidth(columnWidths[1]).pad(6f, 8f)
            contentTable.add(makeLabel(route.distance.toString())).minWidth(columnWidths[2]).pad(6f, 8f)
            contentTable.add(makeLabel(way)).minWidth(columnWidths[3]).pad(6f, 8f)
            contentTable.add(makeLabel(formatStats(myStats))).minWidth(columnWidths[4]).pad(6f, 8f)
            contentTable.add(makeLabel(formatStats(theirStats))).minWidth(columnWidths[5]).pad(6f, 8f)
            val opCell = Table()
            // 断开冷却 (2026-08-26 用户要求): 冷却中按钮变红 + ⏳图标 + 剩余回合, 不可发起
            val cdTurn = gameInfo.tradeRouteCooldowns[city.id]
            val cdRemaining = if (cdTurn != null) 3 - (gameInfo.turns - cdTurn) else 0
            if (cdRemaining > 0) {
                val cdBtn = (Fonts.turn.toString() + cdRemaining).toTextButton()
                cdBtn.label.color = Color.RED
                opCell.add(cdBtn).pad(2f)
            } else {
                opCell.add("建立".toTextButton().apply {
                    onClick {
                        // 单机冷却检查 (联机由服务器判) — 冷却中直接不响应
                        if (!FrameSync.isFsMode(gameInfo)) {
                            val cd = gameInfo.tradeRouteCooldowns[city.id]
                            if (cd != null && gameInfo.turns - cd < 3) return@onClick
                        }
                        // 发起条件 (2026-08-26 用户要求): 无已开发地块资源不能发起
                        if (!TradeRoutes.canInitiate(city)) return@onClick
                        // 确认弹窗 (2026-08-26 用户要求): 防误触; 确认前先禁用按钮防重复弹窗
                        isDisabled = true
                        ConfirmPopup(stageToShowOn, "确定与 ${other.name.tr()} 建立商路？", "建立", isConfirmPositive = true, restoreDefault = { isDisabled = false }) {
                            if (FrameSync.isFsMode(gameInfo)) {
                                FrameSync.sendTradeRouteOffer(city.id, other.id)
                            } else {
                                // 单机: 本地直接执行 (内商/外商/城邦都直接建立)
                                val list = gameInfo.tradeRoutes.getOrPut(city.id) { ArrayList() }
                                if (!list.contains(other.id)) list.add(other.id)
                                gameInfo.invalidateTradeRoutes()
                                try { city.cityStats.update() } catch (ignored: Exception) {}
                            }
                        }.open(force = true)
                    }
                }).pad(2f)
            }
            contentTable.add(opCell).minWidth(columnWidths[6]).pad(6f, 8f)
            contentTable.row()
        }
        contentTable.addSeparator(colSpan = 6).padTop(4f)
    }

    private fun addHeaderRow() {
        val heads = listOf("类型", "目的地", "距离", "方式", "我方收益", "对方收益", "操作")
        for ((i, head) in heads.withIndex()) {
            contentTable.add(makeLabel(head)).minWidth(columnWidths[i]).pad(6f, 8f)
        }
        contentTable.row()
        contentTable.addSeparator(colSpan = 6)
    }

    private fun makeLabel(text: String) = text.toLabel().apply { setAlignment(Align.center) }

    private fun formatStats(stats: Stats): String {
        val parts = mutableListOf<String>()
        for (stat in Stat.entries) {
            val value = stats[stat]
            if (value != 0f) parts.add("${decimal.format(value)} ${stat.name.tr()}")
        }
        return if (parts.isEmpty()) "—" else parts.joinToString(" ")
    }
}

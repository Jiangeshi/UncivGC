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
import kotlin.math.min

/** 商路管理弹窗 (UncivGC 2026-08-26 设计稿 v2): 每城管理自己的 — 本城发起/本城接收/可建立。
 *  表格列: 类型(内商/外商) | 目的地 | 距离 | 方式 | 收益(stat) | 操作
 *  纯拦截: 操作发 op, 等服务器广播后刷新 */
class TradeRoutesPopup(private val screen: com.unciv.ui.screens.basescreen.BaseScreen, private val city: City) :
    Popup(screen, Scrollability.None) {
    private val decimal = DecimalFormat("0.#")
    private val columnWidths = listOf(60f, 120f, 60f, 60f, 190f, 190f, 110f)
    private val contentTable = Table()
    /** 广播到达后自动刷新 (纯拦截: 建立/断开后等服务器 state 同步) */
    private var lastRoutesSig = ""

    init {
        val header = ("贸易路线".tr() + " · " + city.name.tr()).toLabel()
        header.setAlignment(Align.center)
        add(header).pad(8f).growX().row()

        val scrollPane = AutoScrollPane(contentTable)
        scrollPane.setOverscroll(false, false)
        scrollPane.setScrollingDisabled(true, false)  // 2026-08-26 用户要求: 禁横向滚动, 只竖向
        scrollPane.fadeScrollBars = false
        val scrollPaneCell = add(scrollPane).padTop(0f)
        // 固定视口 (模组编辑器/游戏设置同款): 宽度自适应内容 (不横向溢出), 高度固定留竖向滚动
        scrollPaneCell.minWidth(min(screen.stage.width * 0.9f, 742f))
        scrollPaneCell.maxWidth(min(screen.stage.width * 0.98f, 900f))
        scrollPaneCell.height(min(screen.stage.height * 0.7f, 520f))

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

        // 我的路线 (本城发起) — 2026-08-26 修复: 按 tradeRoutes 精确方向分组, 不用 isInitiator 过滤
        // (双向时 A→B 的接收方向条目也会被 isInitiator 误判 → 两条都进「我的路线」)
        val initiatorCities = (gameInfo.tradeRoutes[city.id] ?: emptyList())
            .mapNotNull { id -> gameInfo.getCities().firstOrNull { c -> c.id == id } }
        addGroup("我的路线", initiatorCities, isInitiatorGroup = true, showEmpty = true)

        // 我的接收 (对方发起 → 本城)
        val receiverCities = gameInfo.tradeRoutes.entries
            .filter { (fromId, toIds) -> fromId != city.id && city.id in toIds }
            .mapNotNull { (fromId, _) -> gameInfo.getCities().firstOrNull { c -> c.id == fromId } }
        addGroup("我的接收", receiverCities, isInitiatorGroup = false, showEmpty = true)

        // 可建立 (可达且未连接, 容量未满)
        // 2026-08-26 用户要求: 允许双向 — A→B 已存在时 B 也能连 A (各自方向一次);
        // 只排除自己已发起的连接 (已发出的邀请目标仍显示, 按钮变「等待」)
        if (used < cap) {
            val existing = gameInfo.tradeRoutes[city.id]?.toSet() ?: emptySet()
            val candidates = network.getReachable(city)
                .filter { it.otherCity.id !in existing }
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

    private fun addGroup(title: String, otherCities: List<City>, isInitiatorGroup: Boolean, showEmpty: Boolean) {
        val gameInfo = city.civ.gameInfo
        val network = gameInfo.getTradeRouteNetwork()
        val groupLabel = title.toLabel()
        groupLabel.setAlignment(Align.center)
        contentTable.add(groupLabel).colspan(6).growX().padTop(10f).padBottom(2f).row()
        contentTable.addSeparator(colSpan = 6)
        addHeaderRow()

        if (otherCities.isEmpty()) {
            if (showEmpty) {
                val empty = "暂无".toLabel()
                empty.setAlignment(Align.center)
                empty.color = Color.GRAY
                contentTable.add(empty).colspan(6).pad(8f).growX().row()
            }
            return
        }

        val reachable = network.getReachable(city)
        val routes = otherCities.mapNotNull { other -> reachable.firstOrNull { it.otherCity.id == other.id } }
        val ordered = if (isInitiatorGroup) routes.sortedByDescending { TradeRoutes.initiatorStats(city, it).gold } else routes
        for (route in ordered) {
            val other = route.otherCity
            val type = if (other.civ == city.civ) "内商" else "外商"
            val way = when {
                route.isSea -> "海路"
                route.hasRailroad -> "铁路"
                else -> "道路"
            }
            // 收益函数第一参数必须是发起方城市: 本城发起 → city; 本城接收 → route.otherCity (2026-08-26 修复)
            // 2026-08-27: 显示总收益 (基础 + 词条固定 + 百分比加成, 按收益归属城市算)
            // 我的收益: 受益者=本城; 对方收益: 受益者=对方城市
            val myStats = if (isInitiatorGroup) TradeRoutes.totalStats(city, city, route, true)
                          else TradeRoutes.totalStats(route.otherCity, city, route, false)
            val theirStats = if (isInitiatorGroup) TradeRoutes.totalStats(city, route.otherCity, route, false)
                             else TradeRoutes.totalStats(route.otherCity, route.otherCity, route, true)
            contentTable.add(makeLabel(type)).minWidth(columnWidths[0]).pad(6f, 8f)
            contentTable.add(makeCityLabel(other.name.tr())).minWidth(columnWidths[1]).maxWidth(columnWidths[1]).pad(6f, 8f)
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
                            // 2026-08-26 用户要求: 只断当前方向 (A→B 断开不影响 B→A)
                            if (isInitiatorGroup) FrameSync.sendTradeRouteDisconnect(city.id, other.id)
                            else FrameSync.sendTradeRouteDisconnect(other.id, city.id)
                        } else {
                            // 单机: 只删当前方向 (本城发起 → city→other; 本城接收 → other→city)
                            if (isInitiatorGroup) {
                                val fromList = gameInfo.tradeRoutes[city.id]
                                if (fromList != null && fromList.remove(other.id) && fromList.isEmpty())
                                    gameInfo.tradeRoutes.remove(city.id)
                            } else {
                                val revList = gameInfo.tradeRoutes[other.id]
                                if (revList != null && revList.remove(city.id) && revList.isEmpty())
                                    gameInfo.tradeRoutes.remove(other.id)
                            }
                            gameInfo.invalidateTradeRoutes()
                            // 断开冷却 (2026-08-26 用户要求): 本城 3 回合内不能发起新商路
                            gameInfo.tradeRouteCooldowns[city.id] = gameInfo.turns
                            try { city.cityStats.update() } catch (ignored: Exception) {}
                            // 2026-08-26 用户要求: 断开通知对方 (热座/单机; 内商同文明不通知)
                            val otherCiv = other.civ
                            if (otherCiv != city.civ) {
                                val fromName = if (isInitiatorGroup) city.name.tr() else other.name.tr()
                                val toName = if (isInitiatorGroup) other.name.tr() else city.name.tr()
                                otherCiv.addNotification(
                                    "${city.civ.civName.tr()} 断开了从 $fromName 到 $toName 的贸易路线",
                                    com.unciv.logic.civilization.NotificationCategory.Trade,
                                    com.unciv.logic.civilization.NotificationIcon.Trade)
                            }
                        }
                        // 2026-08-26 用户要求: 商路变化后实时刷新城市页面 (金钱等立即更新)
                        refreshCityScreen()
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
            // 2026-08-27: 可建立候选也显示总收益 (本城词条 + 百分比加成)
            val myStats = TradeRoutes.totalStats(city, city, route, true)
            val theirStats = TradeRoutes.totalStats(city, route.otherCity, route, false)
            val type = if (other.civ == city.civ) "内商" else "外商"
            val way = when {
                route.isSea -> "海路"
                route.hasRailroad -> "铁路"
                else -> "道路"
            }
            contentTable.add(makeLabel(type)).minWidth(columnWidths[0]).pad(6f, 8f)
            contentTable.add(makeCityLabel(other.name.tr())).minWidth(columnWidths[1]).maxWidth(columnWidths[1]).pad(6f, 8f)
            contentTable.add(makeLabel(route.distance.toString())).minWidth(columnWidths[2]).pad(6f, 8f)
            contentTable.add(makeLabel(way)).minWidth(columnWidths[3]).pad(6f, 8f)
            contentTable.add(makeLabel(formatStats(myStats))).minWidth(columnWidths[4]).pad(6f, 8f)
            contentTable.add(makeLabel(formatStats(theirStats))).minWidth(columnWidths[5]).pad(6f, 8f)
            val opCell = Table()
            // 断开冷却 (2026-08-26 用户要求): 冷却中按钮变红 + ⏳图标 + 剩余回合, 不可发起
            val cdTurn = gameInfo.tradeRouteCooldowns[city.id]
            val cdRemaining = if (cdTurn != null) 3 - (gameInfo.turns - cdTurn) else 0
            // 已发出邀请 (2026-08-26 用户要求): 按钮变「等待」, 行不消失
            val pendingOffer = gameInfo.tradeRouteOffers[city.id]?.contains(other.id) == true
            // 玩家之间外商 → 「申请」; 内商/城邦/AI接收方 → 「建立」
            val isForeignPlayer = other.civ != city.civ && other.civ.isHuman()
            if (cdRemaining > 0) {
                val cdBtn = (Fonts.turn.toString() + cdRemaining).toTextButton()
                cdBtn.label.color = Color.RED
                opCell.add(cdBtn).pad(2f)
            } else if (pendingOffer) {
                opCell.add("等待".toTextButton().apply { isDisabled = true }).pad(2f)
            } else {
                val btnText = if (isForeignPlayer) "申请" else "建立"
                val question = if (isForeignPlayer) "确定向 ${other.name.tr()} 申请建立商路？" else "确定与 ${other.name.tr()} 建立商路？"
                opCell.add(btnText.toTextButton().apply {
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
                        ConfirmPopup(stageToShowOn, question, btnText, isConfirmPositive = true, restoreDefault = { isDisabled = false }) {
                            if (FrameSync.isFsMode(gameInfo)) {
                                FrameSync.sendTradeRouteOffer(city.id, other.id)
                            } else if (isForeignPlayer) {
                                // 单机热座双玩家之间: 需要请求 (2026-08-26 用户要求) — 写邀请 + 接收方弹窗接受/拒绝
                                val off = gameInfo.tradeRouteOffers.getOrPut(city.id) { ArrayList() }
                                if (!off.contains(other.id)) off.add(other.id)
                                other.civ.popupAlerts.add(com.unciv.logic.civilization.PopupAlert(
                                    com.unciv.logic.civilization.AlertType.TradeRouteOffer, "${city.id}|${other.id}"))
                                gameInfo.invalidateTradeRoutes()
                                try { city.cityStats.update() } catch (ignored: Exception) {}
                            } else {
                                // 单机: 内商 / AI接收方(默认接受) / 城邦: 直接建立
                                val list = gameInfo.tradeRoutes.getOrPut(city.id) { ArrayList() }
                                if (!list.contains(other.id)) list.add(other.id)
                                gameInfo.invalidateTradeRoutes()
                                try { city.cityStats.update() } catch (ignored: Exception) {}
                            }
                            // 2026-08-26 用户要求: 商路变化后实时刷新城市页面 (金钱等立即更新)
                            refreshCityScreen()
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

    private fun makeLabel(text: String) = text.toLabel().apply {
        setAlignment(Align.center)
        wrap = true  // 2026-08-27: 收益多 stat 换行 (每行 3 个), 防超宽溢出
    }

    /** 城市名: 超长用省略号截断 (2026-08-26 用户要求) */
    private fun makeCityLabel(text: String) = text.toLabel().apply {
        setAlignment(Align.center)
        setEllipsis(true)
    }

    /** 2026-08-26 用户要求: 商路变化后实时刷新底层城市页面 (金钱等立即更新, 不用退出重进) */
    private fun refreshCityScreen() {
        if (screen is CityScreen) screen.update()
    }

    /** 收益格式化: 每行 3 个 stat, 图标+数字+名称, 多行显示 — 2026-08-27 用户要求
     *  (5 种收益同行会超列宽溢出盖住操作列, 每行 3 个 + wrap 后任意数量都不撑爆) */
    private fun formatStats(stats: Stats): String {
        val parts = mutableListOf<String>()
        for (stat in Stat.entries) {
            val value = stats[stat]
            if (value != 0f) parts.add("${stat.character}${decimal.format(value)} ${stat.name.tr()}")
        }
        if (parts.isEmpty()) return "—"
        return parts.chunked(3).joinToString("\n") { it.joinToString("  ") }
    }
}

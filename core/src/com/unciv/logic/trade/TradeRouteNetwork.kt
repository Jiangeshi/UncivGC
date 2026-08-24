package com.unciv.logic.trade

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stats
import kotlin.math.min

/**
 * UncivGC 2026-08-24: 商路系统重写 (城市-城市等价连接)。
 *
 * 设计稿: 文档/商路系统-设计稿.md
 * - 等价连接: 城市对连通后双方各自计算收益 (各自视角公式), 无发起方/接收方
 * - 距离上限: 国内 道路12/铁路20/海洋25, 国外 +5 (17/25/30)
 * - 衰减: 基础收益 1/n、unique 每路加成 1/(n+1), 双方各自独立排名 (陆/海分开)
 * - 海商 ×0.9 独立排名
 * - 屏蔽: 仅外商, 单方取消双方断, 恢复需双方确认
 *
 * 帧同步: 商路状态纯推导不广播 (输入全是已广播状态), 客户端/服务器同 jar 共享计算。
 * 缓存: GameInfo.getTradeRouteNetwork() 惰性计算, 回合结算/状态变化时 invalidate。
 */
class TradeRouteNetwork(val gameInfo: GameInfo) {

    data class Route(
        val otherCity: City,
        val distance: Int,       // 每格 = 1 (本城视角 BFS 路径长度)
        val isSea: Boolean,      // 海路 (双方港口 + 水域)
        val hasRailroad: Boolean // 陆路路径含铁路段
    )

    private data class RouteInfo(val distance: Int, val isSea: Boolean, val hasRailroad: Boolean)

    private var connectionsCache: Map<City, List<Route>>? = null

    fun getRoutes(city: City): List<Route> {
        if (connectionsCache == null) compute()
        return connectionsCache!![city] ?: emptyList()
    }

    fun invalidate() {
        connectionsCache = null
    }

    private fun compute() {
        val result = HashMap<City, MutableList<Route>>()
        val allCities = gameInfo.getCities()
            .filter { !it.civ.isBarbarian && it.getCenterTileOrNull() != null }
            .toList()
        if (System.getProperty("fs.trade.debug") != null)
            System.err.println("[TradeRoute] cities=" + allCities.map { it.name + "(" + it.civ.civName + ")" })
        val debugLog = java.io.File(System.getProperty("user.home"), "fs_debug.log")
        debugLog.appendFsDebug("[TradeRoute] compute start, allCities=${allCities.map { it.name + "(" + it.civ.civName + ")" }}\n")
        if (allCities.size < 2) {
            connectionsCache = result
            return
        }
        // 阶段1: 每个城市各自视角 BFS (陆 + 海), 得可达城市集合
        val forward = HashMap<City, Map<City, RouteInfo>>()
        for (city in allCities) {
            val reachable = HashMap<City, RouteInfo>()
            landBfs(city, reachable)
            seaBfs(city, reachable)
            forward[city] = reachable
        }
        // 阶段2: 对称验证 (双方互相可达) + 距离上限 (按媒介/国内外) + 屏蔽过滤
        val blocked = gameInfo.tradeRouteBlocked
        val debug = System.getProperty("fs.trade.debug") != null
        for (city in allCities) {
            val list = result.getOrPut(city) { mutableListOf() }
            val reach = forward[city]!!
            if (debug) System.err.println("[TradeRoute] " + city.name + " reachable: " +
                reach.map { (k, v) -> k.name + "(dist=" + v.distance + ",sea=" + v.isSea + ",rail=" + v.hasRailroad + ")" })
            for ((other, info) in reach) {
                if (other == city) continue
                val isForeign = other.civ != city.civ
                val limit = limitFor(info, isForeign)
                if (info.distance > limit) {
                    debugLog.appendFsDebug("[TradeRoute] SKIP ${city.name}→${other.name} dist=${info.distance}>limit=$limit sea=${info.isSea} rail=${info.hasRailroad}\n")
                    continue
                }
                if (pairKey(city, other) in blocked) {
                    debugLog.appendFsDebug("[TradeRoute] SKIP ${city.name}→${other.name} BLOCKED\n")
                    continue
                }
                val back = forward[other]?.get(city)
                if (back == null) {
                    debugLog.appendFsDebug("[TradeRoute] SKIP ${city.name}→${other.name} NO_BACK_PATH (other can't reach city)\n")
                    continue
                }
                if (back.distance > limitFor(back, isForeign)) {
                    debugLog.appendFsDebug("[TradeRoute] SKIP ${city.name}→${other.name} BACK_DIST=${back.distance}>limit=${limitFor(back, isForeign)}\n")
                    continue
                }
                debugLog.appendFsDebug("[TradeRoute] OK ${city.name}→${other.name} dist=${info.distance} sea=${info.isSea}\n")
                list.add(Route(other, info.distance, info.isSea, info.hasRailroad))
            }
            if (debug) System.err.println("[TradeRoute] " + city.name + " routes: " + list.map { it.otherCity.name + "(" + it.distance + ")" })
        }
        connectionsCache = result
    }

    // 距离上限: 道路10/铁路15/海洋15 (国外 +5) — 2026-08-25 用户调整
    private fun limitFor(info: RouteInfo, isForeign: Boolean): Int = when {
        info.isSea -> if (isForeign) 20 else 15
        info.hasRailroad -> if (isForeign) 20 else 15
        else -> if (isForeign) 15 else 10
    }

    /** 陆路 BFS: 沿道路/铁路/森林丛林格, 深度 ≤ 25 (最大陆路上限), 记录 (距离, 是否含铁路) */
    private fun landBfs(city: City, reachable: MutableMap<City, RouteInfo>) {
        val start = city.getCenterTile()
        val debug = System.getProperty("fs.trade.debug") != null
        if (debug) System.err.println("[TradeRoute] landBfs start=" + city.name + " tile=" + start.position +
            " road=" + start.getUnpillagedRoad())
        val visited = HashSet<Tile>()
        val queue = ArrayDeque<Triple<Tile, Int, Boolean>>()
        visited.add(start)
        queue.add(Triple(start, 0, false))
        var expanded = 0
        while (queue.isNotEmpty()) {
            val (tile, dist, hasRail) = queue.removeFirst()
            if (dist > 0 && tile.isCityCenter()) {
                val otherCity = tile.getCity()
                if (otherCity != null && otherCity != city && !otherCity.civ.isBarbarian
                    && !reachable.containsKey(otherCity)
                ) {
                    reachable[otherCity] = RouteInfo(dist, false, hasRail)
                }
            }
            if (dist >= MAX_LAND_DEPTH) continue
            for (neighbor in tile.neighbors) {
                if (visited.contains(neighbor)) continue
                val isCityCenter = neighbor.isCityCenter()
                if (!isCityCenter && !neighbor.hasConnection(city.civ)) continue
                val owner = neighbor.getOwner()
                if (owner != null && !canEnterBordersOf(city.civ, owner)) continue
                visited.add(neighbor)
                val newHasRail = hasRail || neighbor.getUnpillagedRoad() == RoadStatus.Railroad
                queue.add(Triple(neighbor, dist + 1, newHasRail))
                expanded++
            }
        }
        val debugLog = java.io.File(System.getProperty("user.home"), "fs_debug.log")
        debugLog.appendFsDebug("[TradeRoute] landBfs ${city.name} expanded=$expanded visited=${visited.size} reachable=${reachable.keys.map { it.name }}\n")
    }

    /** 海路 BFS: 需本城有港口且未被封锁, 沿水域到其他有港口城市, 深度 ≤ 30 (海洋上限) */
    private fun seaBfs(city: City, reachable: MutableMap<City, RouteInfo>) {
        if (!city.containsBuildingUnique(UniqueType.ConnectTradeRoutes) || city.isBlockaded()) return
        val start = city.getCenterTile()
        val visited = HashSet<Tile>()
        val queue = ArrayDeque<Pair<Tile, Int>>()
        visited.add(start)
        queue.add(start to 0)
        while (queue.isNotEmpty()) {
            val (tile, dist) = queue.removeFirst()
            if (dist > 0 && tile.isCityCenter()) {
                val otherCity = tile.getCity()
                if (otherCity != null && otherCity != city && !otherCity.civ.isBarbarian
                    && otherCity.containsBuildingUnique(UniqueType.ConnectTradeRoutes)
                    && !otherCity.isBlockaded()
                    && !reachable.containsKey(otherCity)
                ) {
                    reachable[otherCity] = RouteInfo(dist, true, false)
                }
            }
            if (dist >= MAX_SEA_DEPTH) continue
            for (neighbor in tile.neighbors) {
                if (visited.contains(neighbor)) continue
                val isWater = neighbor.isWater
                val isHarborCity = neighbor.isCityCenter()
                    && neighbor.getCity()?.containsBuildingUnique(UniqueType.ConnectTradeRoutes) == true
                    && neighbor.getCity()?.isBlockaded() != true
                if (!isWater && !isHarborCity) continue
                visited.add(neighbor)
                queue.add(neighbor to dist + 1)
            }
        }
        val debugLog = java.io.File(System.getProperty("user.home"), "fs_debug.log")
        debugLog.appendFsDebug("[TradeRoute] seaBfs ${city.name} visited=${visited.size} reachable=${reachable.keys.map { it.name }}\n")
    }

    private fun canEnterBordersOf(civ: Civilization, otherCiv: Civilization): Boolean {
        if (otherCiv == civ) return true
        if (otherCiv.isBarbarian || civ.isBarbarian) return false
        val diplomacyManager = civ.getDiplomacyManager(otherCiv) ?: return false
        if (diplomacyManager.diplomaticStatus == DiplomaticStatus.War) return false
        if (civ.isCityState || otherCiv.isCityState) return true  // 城邦不签开边, 默认通行
        return diplomacyManager.hasOpenBorders
    }

    companion object {
        const val MAX_LAND_DEPTH = 25
        const val MAX_SEA_DEPTH = 30

        /** 城市对无方向 key (排序拼接) — 屏蔽/恢复请求共用 */
        fun pairKey(cityA: City, cityB: City): String {
            val a = cityA.id
            val b = cityB.id
            return if (a <= b) "$a|$b" else "$b|$a"
        }
    }
}

/** 调试日志写入 (桌面用户目录 fs_debug.log); Android 根目录只读 (EROFS) 时静默忽略 — 2026-08-24 手机建城崩溃 */
private fun java.io.File.appendFsDebug(msg: String) {
    try {
        appendText(msg)
    } catch (ignored: Exception) {
    }
}

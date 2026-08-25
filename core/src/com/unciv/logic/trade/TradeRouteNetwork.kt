package com.unciv.logic.trade

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.min

/**
 * 商路网络 (UncivGC 2026-08-26 设计稿 v2 改造): 从"全自动连接"改为"可达性查询 + 已建立连接"。
 * - getReachable(city): 可达城市列表 (道路/铁路/海路 BFS, 距离 = min(道路路径, 地形BFS+1)), 含上限过滤 — 供"可建立"列表
 * - getEstablishedRoutes(city): 已建立的商路 (gameInfo.tradeRoutes 过滤, 本城为发起方或接收方) — 供收益/UI/AI
 * - 收益距离防刷: min(道路路径, 地形BFS步数+1) — 玩家铺路形状不能改变地形步数 (设计稿 v2 §1.2)
 */
class TradeRouteNetwork(val gameInfo: GameInfo) {

    data class Route(
        val otherCity: City,
        val distance: Int,       // 收益距离 = min(道路路径, 地形BFS+1)
        val isSea: Boolean,      // 海路 (双方港口 + 水域)
        val hasRailroad: Boolean // 陆路路径含铁路段
    )

    private data class RouteInfo(val distance: Int, val isSea: Boolean, val hasRailroad: Boolean)

    // city -> (otherCity -> 道路路径距离)
    private var reachCache: Map<City, Map<City, RouteInfo>>? = null
    // city -> (otherCity -> 地形BFS步数)
    private var terrainCache: Map<City, Map<City, Int>>? = null

    fun invalidate() {
        reachCache = null
        terrainCache = null
    }

    private fun ensureComputed() {
        if (reachCache == null) compute()
    }

    /** 可达城市列表 (供"可建立"列表 / 距离查询); 含距离上限过滤 + 对称可达验证 */
    fun getReachable(city: City): List<Route> {
        ensureComputed()
        val reach = reachCache!![city] ?: return emptyList()
        val list = ArrayList<Route>()
        for ((other, info) in reach) {
            if (other == city) continue
            val terrain = terrainCache!![city]?.get(other) ?: info.distance
            val dist = min(info.distance, terrain + 1)
            list.add(Route(other, dist, info.isSea, info.hasRailroad))
        }
        return list
    }

    /** 已建立的商路 (本城为发起方或接收方) — 收益/UI/AI 用; 连接不在可达表 (失效) 时剔除 */
    fun getEstablishedRoutes(city: City): List<Route> {
        ensureComputed()
        val result = ArrayList<Route>()
        val myId = city.id
        for ((fromId, toIds) in gameInfo.tradeRoutes) {
            val otherIds = when {
                fromId == myId -> toIds.filter { it != myId }
                myId in toIds -> listOf(fromId)
                else -> continue
            }
            for (otherId in otherIds) {
                val other = gameInfo.getCities().firstOrNull { it.id == otherId } ?: continue
                val reach = reachCache!![city]?.get(other) ?: continue
                val terrain = terrainCache!![city]?.get(other) ?: reach.distance
                result.add(Route(other, min(reach.distance, terrain + 1), reach.isSea, reach.hasRailroad))
            }
        }
        return result
    }

    /** 本城是否是某连接的发起方 */
    fun isInitiator(city: City, otherCity: City): Boolean {
        return (gameInfo.tradeRoutes[city.id] ?: emptyList()).contains(otherCity.id)
    }

    /** 收益距离 (min(道路路径, 地形BFS+1)); 不可达/超上限 → null */
    fun routeDistance(city: City, other: City): Int? {
        ensureComputed()
        val reach = reachCache!![city]?.get(other) ?: return null
        val terrain = terrainCache!![city]?.get(other) ?: reach.distance
        return min(reach.distance, terrain + 1)
    }

    /** 地形BFS步数 (可通行格, 每格=1, 不含道路/移动成本) */
    fun terrainDistance(city: City, other: City): Int? {
        ensureComputed()
        return terrainCache!![city]?.get(other)
    }

    private fun compute() {
        val forward = HashMap<City, Map<City, RouteInfo>>()
        val terrain = HashMap<City, Map<City, Int>>()
        val allCities = gameInfo.getCities()
            .filter { !it.civ.isBarbarian && it.getCenterTileOrNull() != null }
            .toList()
        if (allCities.size < 2) {
            reachCache = forward
            terrainCache = terrain
            return
        }
        // 阶段1: 每个城市各自视角 BFS (陆 + 海) + 地形步数 BFS
        for (city in allCities) {
            val reachable = HashMap<City, RouteInfo>()
            landBfs(city, reachable)
            seaBfs(city, reachable)
            forward[city] = reachable
            terrain[city] = terrainBfs(city)
        }
        // 阶段2: 对称验证 (双方互相可达) + 距离上限过滤 (设计稿 v2: 12/20/25 统一, 不分国内外)
        val finalForward = HashMap<City, Map<City, RouteInfo>>()
        for (city in allCities) {
            val m = HashMap<City, RouteInfo>()
            val reach = forward[city]!!
            for ((other, info) in reach) {
                if (other == city) continue
                if (info.distance > limitFor(info)) continue
                val back = forward[other]?.get(city) ?: continue
                if (back.distance > limitFor(back)) continue
                m[other] = info
            }
            finalForward[city] = m
        }
        reachCache = finalForward
        terrainCache = terrain
    }

    // 距离上限 (2026-08-26 用户确认): 道路12/铁路20/海洋25, 统一不分国内外
    private fun limitFor(info: RouteInfo): Int = when {
        info.isSea -> 25
        info.hasRailroad -> 20
        else -> 12
    }

    /** 陆路 BFS: 沿道路/铁路/森林丛林格, 深度 ≤ 25 (最大陆路上限), 记录 (距离, 是否含铁路) */
    private fun landBfs(city: City, reachable: MutableMap<City, RouteInfo>) {
        val start = city.getCenterTile()
        val visited = HashSet<Tile>()
        val queue = ArrayDeque<Triple<Tile, Int, Boolean>>()
        visited.add(start)
        queue.add(Triple(start, 0, false))
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
            }
        }
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
                    && canEnterBordersOf(city.civ, otherCity.civ)
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
    }

    /** 地形步数 BFS (2026-08-26 设计稿 v2 §1.2 防刷距离): 只考虑可通行地块, 每格=1,
     *  不含道路/移动成本 (丘陵森林河流不增加成本), 不可通行 (山脉/水域) 必须绕行;
     *  服务器全图视角, 不限制国界/探索 */
    private fun terrainBfs(city: City): Map<City, Int> {
        val result = HashMap<City, Int>()
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
                    && !result.containsKey(otherCity)
                ) {
                    result[otherCity] = dist
                }
            }
            if (dist >= MAX_LAND_DEPTH) continue
            for (neighbor in tile.neighbors) {
                if (visited.contains(neighbor)) continue
                if (neighbor.isWater || neighbor.isImpassible()) continue
                visited.add(neighbor)
                queue.add(neighbor to dist + 1)
            }
        }
        return result
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

        /** 城市对无方向 key (排序拼接) — 兼容旧屏蔽字段 */
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

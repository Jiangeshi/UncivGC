package com.unciv.logic.trade

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.ui.components.extensions.toPercent

/**
 * 商路收益计算 (UncivGC 2026-08-26 设计稿 v2 重写): 单向商路, 双方不同类型收益。
 *
 * 发起方 (卖货): 金币 = 已改良地块资源数 + 0.5 × 收益距离
 * 接收方 (进货): 文化 +1×奢侈数, 产能 +1×战略数, 食物 +0.5×奖金数 (发起方已改良地块资源)
 * 容量: 时代序号 (远古1...未来9) + Provides [X] Trade Routes unique
 * 城邦: 只接收; 发起方连城邦 影响力 +10/条 (服务器结算)
 * 传教: 每回合接收方城市 + 基础压力一半 (最低1) 发起方主流宗教压力 (服务器结算)
 */
object TradeRoutes {

    /** 发起方收益: 金币 = 已改良地块资源数 + 距离×系数 (2026-08-26 用户调整: 陆商 1.5/格, 海商 0.5/格)
     *  同盟 Lv1+: 同盟间商路收益 +50% (2026-08-26 同盟设计稿 v1.0) */
    fun initiatorStats(city: City, route: TradeRouteNetwork.Route): Stats {
        val stats = Stats()
        val distFactor = if (route.isSea) 0.5f else 1.5f  // 2026-08-26 用户调整: 陆商 1.5/格, 海商 0.5/格
        stats.gold = (improvedResourceCount(city) + distFactor * route.distance) * allianceBonus(city, route.otherCity)
        return stats
    }

    /** 接收方收益: 奢侈×1文化 + 战略×1产能 + 奖金×0.5食物 (发起方已改良地块资源); 同盟间 +50% */
    fun receiverStats(city: City, route: TradeRouteNetwork.Route): Stats {
        val stats = Stats()
        val resources = improvedResourceTypes(city)
        val mult = allianceBonus(city, route.otherCity)
        stats.culture = (resources[ResourceType.Luxury]?.toFloat() ?: 0f) * mult
        stats.production = (resources[ResourceType.Strategic]?.toFloat() ?: 0f) * mult
        stats.food = (resources[ResourceType.Bonus] ?: 0) * 0.5f * mult
        return stats
    }

    /** 同盟商路加成 (2026-08-26): 两城市文明间存在同盟 → 1.5 (Lv1 起生效) */
    private fun allianceBonus(city: City, otherCity: City): Float {
        return if (city.civ.gameInfo.alliances.any {
                it.contains(city.civ.civID) && it.contains(otherCity.civ.civID)
            }) 1.5f else 1f
    }

    /** 单条商路某方总收益 = (基础收益 + 每条商路词条 StatsFromTradeRoute) × 百分比加成 StatPercentFromTradeRoutes
     *  — 2026-08-27 用户要求: 商路页面显示总收益 (词条/百分比按收益归属城市的 uniques 算)
     *  fromCity = 发起方城市 (基础收益函数第一参数, 资源来自发起方); beneficiary = 收益归属城市 */
    fun totalStats(fromCity: City, beneficiary: City, route: TradeRouteNetwork.Route, isInitiator: Boolean): Stats {
        val base = if (isInitiator) initiatorStats(fromCity, route) else receiverStats(fromCity, route)
        // 每条商路固定词条 (如「每条商路 +2 金币」) — 收益归属城市
        val perRoute = Stats()
        for (unique in beneficiary.getMatchingUniques(UniqueType.StatsFromTradeRoute)) perRoute.add(unique.stats)
        base.add(perRoute)
        // 百分比加成 (如「+25% 金币 from Trade Routes」) — 收益归属城市
        val percentageStats = Stats()
        for (unique in beneficiary.getMatchingUniques(UniqueType.StatPercentFromTradeRoutes))
            percentageStats[Stat.valueOf(unique.params[1])] += unique.params[0].toFloat()
        for ((stat) in base) base[stat] *= percentageStats[stat].toPercent()
        return base
    }

    /** 已改良的地块资源数 (归属本城地块且已改良, 或城市中心自动供应; 排除建筑/文明级全局资源)
     *  — 设计稿 v2: 必须是"地块资源且被改良" */
    fun improvedResourceCount(city: City): Int {
        return improvedResourceTypes(city).values.sum()
    }

    /** 已改良的地块资源分类统计 (ResourceType -> 数量) */
    fun improvedResourceTypes(city: City): Map<ResourceType, Int> {
        val result = HashMap<ResourceType, Int>()
        for (tile in city.getTiles()) {
            if (tile.getCity() != city) continue
            val res = tile.tileResource ?: continue
            if (tile.getUnpillagedImprovement() == null && tile != city.getCenterTileOrNull()) continue
            result[res.resourceType] = (result[res.resourceType] ?: 0) + 1
        }
        return result
    }

    /** 文明商路容量: 时代序号 (远古=1, 古典=2, ...) + Provides [X] Trade Routes unique 加成 */
    fun capacity(civ: Civilization): Int {
        var cap = civ.getEraNumber() + 1
        for (unique in civ.getMatchingUniques(UniqueType.ProvidesTradeRoutes)) {
            cap += unique.params[0].toInt()
        }
        return cap
    }

    /** 文明已用商路数 (发起方视角: 本文明城市发起的连接数) */
    fun usedByCiv(civ: Civilization): Int {
        val myCityIds = civ.cities.map { it.id }.toSet()
        return civ.gameInfo.tradeRoutes.entries
            .filter { (fromId, _) -> fromId in myCityIds }
            .sumOf { (_, toIds) -> toIds.size }
    }

    /** 发起条件 (2026-08-26 用户要求): 发起城市必须有已开发的地块资源 (改良后的资源地块) */
    fun canInitiate(city: City): Boolean = improvedResourceCount(city) > 0

    /** 本城商路收益汇总 (发起方金币 + 接收方文产食) — CityStats 用
     *  receiverStats/initiatorStats 第一参数必须是发起方城市 — 接收方分支传 route.otherCity (2026-08-26 修复)
     *  2026-08-26 再修复: 按 tradeRoutes 精确方向遍历 — isInitiator 在双向时会把接收方向误判为发起 (收益重复计算) */
    fun cityTradeRouteStats(city: City): Stats {
        val stats = Stats()
        val gameInfo = city.civ.gameInfo
        val network = gameInfo.getTradeRouteNetwork()
        val reachable = network.getReachable(city)
        // StatsFromTradeRoute (2026-08-26 用户确认补上): 每条商路加固定 stats (发起+接收都算, 词条如「每条商路 +2 金币」)
        val perRouteStats = Stats()
        for (unique in city.getMatchingUniques(UniqueType.StatsFromTradeRoute)) {
            perRouteStats.add(unique.stats)
        }
        // 本城发起 (我的路线)
        for (otherId in gameInfo.tradeRoutes[city.id] ?: emptyList()) {
            val route = reachable.firstOrNull { it.otherCity.id == otherId } ?: continue
            stats.add(initiatorStats(city, route))
            stats.add(perRouteStats)
        }
        // 本城接收 (对方发起 → 本城)
        for ((fromId, toIds) in gameInfo.tradeRoutes) {
            if (fromId == city.id || city.id !in toIds) continue
            val otherCity = gameInfo.getCities().firstOrNull { it.id == fromId } ?: continue
            val route = reachable.firstOrNull { it.otherCity.id == otherCity.id } ?: continue
            stats.add(receiverStats(otherCity, route))
            stats.add(perRouteStats)
        }
        return stats
    }
}

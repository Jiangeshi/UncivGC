package com.unciv.logic.trade

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stats

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

    /** 发起方收益: 金币 = 已改良地块资源数 + 距离×系数 (2026-08-26 用户调整: 陆商 1/格, 海商 0.5/格) */
    fun initiatorStats(city: City, route: TradeRouteNetwork.Route): Stats {
        val stats = Stats()
        val distFactor = if (route.isSea) 0.5f else 1f
        stats.gold = improvedResourceCount(city) + distFactor * route.distance
        return stats
    }

    /** 接收方收益: 奢侈×1文化 + 战略×1产能 + 奖金×0.5食物 (发起方已改良地块资源) */
    fun receiverStats(city: City, route: TradeRouteNetwork.Route): Stats {
        val stats = Stats()
        val resources = improvedResourceTypes(city)
        stats.culture = resources[ResourceType.Luxury]?.toFloat() ?: 0f
        stats.production = resources[ResourceType.Strategic]?.toFloat() ?: 0f
        stats.food = (resources[ResourceType.Bonus] ?: 0) * 0.5f
        return stats
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

    /** 本城商路收益汇总 (发起方金币 + 接收方文产食) — CityStats 用 */
    fun cityTradeRouteStats(city: City): Stats {
        val stats = Stats()
        val network = city.civ.gameInfo.getTradeRouteNetwork()
        for (route in network.getEstablishedRoutes(city)) {
            if (network.isInitiator(city, route.otherCity)) stats.add(initiatorStats(city, route))
            else stats.add(receiverStats(city, route))
        }
        return stats
    }
}

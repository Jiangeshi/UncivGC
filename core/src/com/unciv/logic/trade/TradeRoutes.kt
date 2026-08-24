package com.unciv.logic.trade

import com.unciv.logic.city.City
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stats
import kotlin.math.min

/**
 * UncivGC 2026-08-24: 商路收益计算 (设计稿 §1)。
 *
 * base = (对方人口×0.4 + 本城人口×0.3) × min(1.5, 0.9 + 距离×0.05) + 资源差
 * 资源差: 我方有对方没有 — 奢侈 +1 / 战略 +0.5 / 奖金 +0.5 (每种)
 * 衰减: 基础收益 1/rank (陆/海分开排名), unique 每路加成 1/(rank+1), 海商 ×0.9
 * 城邦影响力 +5/条 (不吃衰减) — 服务器结算
 */
object TradeRoutes {

    fun distFactor(distance: Int): Float = min(1.5f, 0.9f + distance * 0.05f)

    fun baseFor(city: City, route: TradeRouteNetwork.Route): Float {
        val otherPop = route.otherCity.population.population
        val ownPop = city.population.population
        // 人口系数 0.3/0.3 (2026-08-25 用户调整)
        var gold = (otherPop * 0.3f + ownPop * 0.3f) * distFactor(route.distance)
        gold += resourceBonus(city, route.otherCity)
        return gold
    }

    /** 我方城市有、对方城市没有的资源差奖励 (奢侈+1/战略+0.5/奖金+0.5, 每种资源).
     *  只算归属本城的地块资源 (tile.getCity()==city — 重叠地块只算归属城市, 2026-08-24 用户要求)
     *  且已改良 (或城市中心自动供应); 排除建筑/文明级 uniques 全局资源 */
    fun resourceBonus(city: City, otherCity: City): Float {
        var bonus = 0f
        val ruleset = city.civ.gameInfo.ruleset
        fun mapResources(c: City): Set<TileResource> {
            val set = HashSet<TileResource>()
            for (tile in c.getTiles()) {
                if (tile.getCity() != c) continue
                val res = tile.tileResource ?: continue
                if (tile.getUnpillagedImprovement() == null && tile != c.getCenterTileOrNull()) continue
                set.add(res)
            }
            return set
        }
        val myResources = mapResources(city)
        val otherResources = mapResources(otherCity)
        for (resource in myResources) {
            if (resource in otherResources) continue
            bonus += when (resource.resourceType) {
                ResourceType.Luxury -> 0.5f
                ResourceType.Strategic -> 0.25f
                ResourceType.Bonus -> 0.25f
            }
        }
        return bonus
    }

    /** 本城商路集合内排名 (陆/海分开, 按 base 从高到低; 第 1 名 = 1) */
    fun rankOf(city: City, route: TradeRouteNetwork.Route): Int {
        val routes = city.civ.gameInfo.getTradeRouteNetwork().getRoutes(city)
        val peers = routes.filter { it.isSea == route.isSea }
            .sortedByDescending { baseFor(city, it) }
        val idx = peers.indexOfFirst { it.otherCity == route.otherCity && it.distance == route.distance }
        return if (idx < 0) 1 else idx + 1
    }

    /** 单条商路实际收益:
     *  [ (对方人口×0.5 + 本城人口×0.3) × 距离系数 ÷ rank + 资源差 ] × 海商系数(海路0.9)
     *  资源差不吃排名衰减, 吃海商系数 (2026-08-24 用户确认) */
    fun actualStats(city: City, route: TradeRouteNetwork.Route, rank: Int): Stats {
        val stats = Stats()
        val seaFactor = if (route.isSea) 0.7f else 1f  // 海商系数 0.7 (2026-08-25 用户调整)
        val popGold = ((route.otherCity.population.population * 0.3f + city.population.population * 0.3f)
                * distFactor(route.distance))
        stats.gold = (popGold / rank + resourceBonus(city, route.otherCity)) * seaFactor
        for (unique in city.getMatchingUniques(UniqueType.StatsFromTradeRoute))
            stats.add(unique.stats * (1f / (rank + 1)))
        return stats
    }
}

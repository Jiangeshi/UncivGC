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
 * 发起方 (卖货): 金币 = 已改良地块资源数×0.5 + 距离×系数 (2026-08-28: 资源 1→0.5)
 * 接收方 (进货): 文化 +1×奢侈数, 产能 +1×战略数, 食物 +0.5×奖金数 (发起方已改良地块资源)
 * 人口 (2026-08-28 方案A): 发起方金币 ×(1+接收方人口/20); 接收方文产食 ×(1+发起方人口/20)
 * 同盟 (2026-08-28): 同盟间商路收益 +25% (原 +50%, 用户调低)
 * 词条 (2026-08-28 晚): 词条固定加成并入基础收益 — 吃同盟/人口/rank 系数; 只对发起方生效
 * 容量: 时代序号 (远古1...未来9) + Provides [X] Trade Routes unique
 * 城邦: 只接收; 发起方连城邦 影响力 +10/条 (服务器结算)
 * 传教: 每回合接收方城市 + 基础压力一半 (最低1) 发起方主流宗教压力 (服务器结算)
 * 2026-08-28: 每条商路按 1/rank 衰减 (第 1 条全额, 第 2 条 ×1/2...), rank 按发起方先后, 双向独立
 * 完整公式: (基础 + 词条固定) × 同盟 × 人口系数 × (1/rank) × 百分比加成
 */
object TradeRoutes {

    /** 商路 rank (1-based): 发起方城市第几条连接 — 按 tradeRoutes[city.id] 列表顺序 (连接先后)
     *  2026-08-28 用户确认: rank 由发起方决定, 双向各自独立 */
    fun routeRank(fromCity: City, otherCityId: String): Int {
        val list = fromCity.civ.gameInfo.tradeRoutes[fromCity.id] ?: return 1
        val idx = list.indexOf(otherCityId)
        return if (idx >= 0) idx + 1 else 1
    }

    /** 衰减系数 = 1/rank (第 1 条 ×1, 第 2 条 ×0.5, 第 3 条 ×0.333...) */
    fun decayFactor(fromCity: City, otherCityId: String): Float = 1f / routeRank(fromCity, otherCityId)

    /** 发起方收益: 金币 = (已改良资源数×0.5 + 距离×系数 + 词条固定金币) × 同盟 × 人口系数 × 1/rank
     *  2026-08-28: 资源 1→0.5 削基础; 人口系数 ×(1+接收方人口/20) 方案A; 词条并入基础吃全系数 (晚) */
    fun initiatorStats(city: City, route: TradeRouteNetwork.Route, expectedRank: Int? = null): Stats {
        val stats = Stats()
        val distFactor = if (route.isSea) 0.3f else 1.2f  // 2026-08-31 用户调整: 陆商 1.2/格, 海商 0.3/格 (原 1.5/0.5)
        val rank = expectedRank ?: routeRank(city, route.otherCity.id)
        // 2026-09-01 用户: 人口系数分档 (原 x/20 后期增长太高): 1~10: x/20, 11~20: 0.5+(x-10)/40,
        // 21~30: 0.75+(x-20)/60, 31+: 0.917+(x-30)/80 (每档起点=上档终点, 分母 20/40/60/80 递增)
        val popFactor = 1f + populationFactor(route.otherCity.population.population)  // 方案A: 接收方人口
        stats.gold = improvedResourceCount(city) * 0.5f + distFactor * route.distance
        // 词条固定加成并入基础 (2026-08-28 晚): 吃同盟/人口/rank 系数; 只发起方有
        // 2026-08-28 修复: 全 stat 并入 — 原只取 gold, 产能/粮食/科学/文化等词条全部不生效 (用户 LM2ugc 商路词条)
        for (unique in city.getMatchingUniques(UniqueType.StatsFromTradeRoute))
            stats.add(unique.stats)
        val mult = allianceBonus(city, route.otherCity) * popFactor * (1f / rank)
        return stats.times(mult)
    }

    /** 接收方收益: 奢侈×1文化 + 战略×1产能 + 奖金×0.5食物 (发起方已改良地块资源)
     *  2026-08-28: 人口系数 ×(1 + 发起方人口/20) 方案A; 按发起方 rank 衰减; 无词条固定加成 (只发起方有) */
    fun receiverStats(city: City, route: TradeRouteNetwork.Route, expectedRank: Int? = null): Stats {
        val stats = Stats()
        val resources = improvedResourceTypes(city)
        val rank = expectedRank ?: routeRank(city, route.otherCity.id)
        val popFactor = 1f + populationFactor(city.population.population)  // 方案A: 发起方人口 (city=发起方)
        val mult = allianceBonus(city, route.otherCity) * popFactor * (1f / rank)
        stats.culture = (resources[ResourceType.Luxury]?.toFloat() ?: 0f) * mult
        stats.production = (resources[ResourceType.Strategic]?.toFloat() ?: 0f) * mult
        stats.food = (resources[ResourceType.Bonus] ?: 0) * 0.5f * mult
        return stats
    }

    /** 同盟商路加成 (2026-08-26 同盟设计稿 v1.0): 两城市文明间存在同盟 → 1.25 (Lv1 起生效)
     *  2026-08-28 用户调整: 1.5 (+50%) → 1.25 (+25%) */
    /** 2026-09-01 用户: 人口系数分档 (原 x/20 后期增长太高): 每 10 人口一档,
     *  每档起点=上一档终点, 分母 20/40/60/80 递增, 31+ 锁分母 80 — 曲线连续、后期增长平缓
     *  1~10: x/20; 11~20: 0.5+(x-10)/40; 21~30: 0.75+(x-20)/60; 31+: 0.75+10/60+(x-30)/80 */
    private fun populationFactor(pop: Int): Float = when {
        pop <= 10 -> pop / 20f
        pop <= 20 -> 0.5f + (pop - 10) / 40f
        pop <= 30 -> 0.75f + (pop - 20) / 60f
        else -> 0.75f + 10f / 60f + (pop - 30) / 80f
    }

    private fun allianceBonus(city: City, otherCity: City): Float {
        return if (city.civ.gameInfo.alliances.any {
                it.contains(city.civ.civID) && it.contains(otherCity.civ.civID)
            }) 1.25f else 1f
    }

    /** 单条商路某方总收益: (基础 + 词条固定) × 同盟 × 人口 × 1/rank × 百分比加成
     *  — 2026-08-27: 商路页面显示总收益 (词条/百分比按收益归属城市的 uniques 算)
     *  2026-08-28 晚重构: 词条固定已并入 initiatorStats/receiverStats 基础内 (吃同盟/人口/rank);
     *  这里只乘百分比加成 (StatPercentFromTradeRoutes) — 不再重复乘 rank 衰减 */
    fun totalStats(fromCity: City, beneficiary: City, route: TradeRouteNetwork.Route, isInitiator: Boolean, expectedRank: Int? = null): Stats {
        val base = if (isInitiator) initiatorStats(fromCity, route, expectedRank) else receiverStats(fromCity, route, expectedRank)
        // 百分比加成 (如「+25% 金币 from Trade Routes」) — 收益归属城市; 乘在总收益上 (含全部系数)
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
            // 2026-09-01 修复: ①战略资源有前置科技, 未解锁不可见 → 不参与商路判定 (canSeeResource)
            // ②「开发资源」= 对应的改良 (羊盖农场不算开发羊, 只有牧场才算; 城市中心坐资源保留原版自动开发)
            if (!city.civ.canSeeResource(res)) continue
            val improvement = tile.getUnpillagedImprovement()
            if (improvement != null) {
                if (improvement !in res.getImprovements()) continue
            } else if (tile != city.getCenterTileOrNull()) {
                continue
            }
            result[res.resourceType] = (result[res.resourceType] ?: 0) + 1
        }
        return result
    }

    /** 文明商路容量: 时代序号 (远古=1, 古典=2, ...) + Provides [X] Trade Routes unique 加成
     *  2026-08-31 修复: Market 等建筑提供 2 条商路而非 1 条 — 建筑 uniques 被计两次: ①civ.getMatchingUniques
     *  (城市→建筑, 无条件词条能通过 civ 上下文) ②城市级循环。修复: 城市级循环先计并收集文本,
     *  文明级循环跳过建筑来源 (港口那种带城市条件的词条仍由城市级以城市上下文评估, 8-28 行为不变) */
    fun capacity(civ: Civilization): Int {
        var cap = civ.getEraNumber() + 1
        // 建筑级 ProvidesTradeRoutes (Harbor 等): 城市条件 (<in cities without a [Market]>) 由 city.state context 正确评估
        val buildingRouteUniqueTexts = HashSet<String>()
        for (city in civ.cities) {
            for (unique in city.getMatchingUniques(UniqueType.ProvidesTradeRoutes, includeCivUniques = false)) {
                buildingRouteUniqueTexts.add(unique.text)
                cap += unique.params[0].toInt()
            }
        }
        // 文明级: 政策/文明特性/全局 uniques — 排除建筑来源 (否则 Market 计两次, 用户反馈原版/LM 都是)
        for (unique in civ.getMatchingUniques(UniqueType.ProvidesTradeRoutes)) {
            if (unique.text in buildingRouteUniqueTexts) continue
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
     *  2026-08-26 再修复: 按 tradeRoutes 精确方向遍历 — isInitiator 在双向时会把接收方向误判为发起 (收益重复计算)
     *  2026-08-28 晚重构: 词条固定已并入 initiatorStats (吃同盟/人口/rank); 这里按 totalStats 语义补百分比加成 */
    fun cityTradeRouteStats(city: City): Stats {
        val stats = Stats()
        val gameInfo = city.civ.gameInfo
        val network = gameInfo.getTradeRouteNetwork()
        val reachable = network.getReachable(city)
        // 本城发起 (我的路线): 基础(含词条固定) × 同盟 × 人口 × 1/rank × 百分比加成
        for (otherId in gameInfo.tradeRoutes[city.id] ?: emptyList()) {
            val route = reachable.firstOrNull { it.otherCity.id == otherId } ?: continue
            stats.add(totalStats(city, city, route, true))
        }
        // 本城接收 (对方发起 → 本城): 基础文产食 × 同盟 × 人口 × 1/rank × 接收方百分比加成
        // (无词条固定 — 只发起方有; 百分比按收益归属城市算)
        for ((fromId, toIds) in gameInfo.tradeRoutes) {
            if (fromId == city.id || city.id !in toIds) continue
            val otherCity = gameInfo.getCities().firstOrNull { it.id == fromId } ?: continue
            val route = reachable.firstOrNull { it.otherCity.id == otherCity.id } ?: continue
            stats.add(totalStats(otherCity, city, route, false))
        }
        return stats
    }
}

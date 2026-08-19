package com.unciv.view

import com.unciv.ui.screens.worldscreen.FrameSync

import com.unciv.logic.city.City
import com.unciv.logic.city.CityFlags
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.city.StatTreeNode
import com.unciv.logic.city.CityFocus
import com.unciv.logic.city.CityResources
import com.unciv.logic.city.GreatPersonPointsBreakdown
import com.unciv.logic.city.managers.CityReligionManager
import com.unciv.logic.map.tile.Tile
import com.unciv.models.Religion
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.Counter
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.tile.ResourceSupplyList
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import yairm210.purity.annotations.Readonly

/** View of a [City] from the perspective of [civView]. UI should use this and not city directly.
 * This should only be for cities we can see as if we own them - our cities, spied cities, or if we're spectator */
class CityView(city: City,
               viewer: Civilization,
               spectatorMode: Boolean = false,
               override val civView: CivView) : ForeignCityView(city, viewer, spectatorMode, civView) {
    /** The viewing player's full CivView (always a self-view). For the city's owning civ, use [owningCiv]. */
    @Readonly fun viewingCiv(): CivView = civView

    /** Cities the viewer can page through in CityScreen: own cities normally, or spy-visited cities when spying. */
    @Readonly fun getViewableCities(): List<CityView> {
        val isSpying = city.civ !== viewer && viewer.gameInfo.isEspionageEnabled() && !viewer.isSpectator()
        return if (isSpying) viewer.espionageManager.getCitiesWithOurSpies()
            .filter { it.civ != viewer }
            .map { civView.getCity(it) }
        else city.civ.cities.map { civView.getCity(it) }
    }

    val tilesInRange: Set<Tile> get() = city.tilesInRange

    @Readonly fun centerTile(): TileView = civView.gameView.tileMapView.getTile(city.getCenterTile())
    @Readonly fun getTiles(): Sequence<TileView> = city.getTiles().map { civView.gameView.tileMapView.getTile(it) }
    @Readonly fun tileView(tile: Tile): TileView = civView.gameView.tileMapView.getTile(tile)

    @Readonly fun getWorkRange(): Int = city.getWorkRange()
    @Readonly fun isWorked(tileView: TileView): Boolean = city.isWorked(getTile(tileView))
    @Readonly fun canBuyTile(tileView: TileView): Boolean = city.expansion.canBuyTile(getTile(tileView))
    @Readonly fun getGoldCostOfTile(tileView: TileView, extraTiles: Int = 0): Int =
        city.expansion.getGoldCostOfTile(getTile(tileView), extraTiles)
    // Population
    @Readonly fun getFreePopulation(): Int = city.population.getFreePopulation()
    @Readonly fun getPopulationCount(): Int = city.population.population
    @Readonly fun getFoodStored(): Int = city.population.foodStored
    @Readonly fun getFoodToNextPopulation(): Int = city.population.getFoodToNextPopulation()
    @Readonly fun getMaxSpecialists(): Counter<String> = city.population.getMaxSpecialists()
    @Readonly fun getNewSpecialists(): Counter<String> = city.population.getNewSpecialists()
    val manualSpecialists: Boolean get() = city.manualSpecialists
    @Readonly fun getNumTurnsToStarvation(): Int? = city.population.getNumTurnsToStarvation()
    @Readonly fun getNumTurnsToNewPopulation(): Int? = city.population.getNumTurnsToNewPopulation()
    @Readonly fun getStatsOfSpecialist(specialistName: String): Stats = city.cityStats.getStatsOfSpecialist(specialistName)

    // City state
    @Readonly fun getNumberOfFollowers(): Counter<String> = city.religion.getNumberOfFollowers()
    @Readonly fun religion(): CityReligionManager = city.religion
    @Readonly fun isStarving(): Boolean = city.isStarving()
    @Readonly fun isGrowing(): Boolean = city.isGrowing()
    @Readonly fun isInResistance(): Boolean = city.isInResistance()
    @Readonly fun isWeLoveTheKingDayActive(): Boolean = city.isWeLoveTheKingDayActive()
    val demandedResource: String get() = city.demandedResource
    @Readonly fun getFlag(flag: CityFlags): Int = city.getFlag(flag)
    @Readonly fun getCityFocus(): CityFocus = city.getCityFocus()
    val avoidGrowth: Boolean get() = city.avoidGrowth
    @Readonly fun getState(): GameContext = city.state

    // Stats
    @Readonly fun getCurrentCityStats(): Stats = city.cityStats.currentCityStats
    @Readonly fun getHappinessList(): Map<String, Float> = city.cityStats.happinessList
    @Readonly fun getBaseStatTree(): StatTreeNode = city.cityStats.baseStatTree
    @Readonly fun getStatPercentBonusTree(): StatTreeNode = city.cityStats.statPercentBonusTree
    @Readonly fun getFinalStatList(): Map<String, Stats> = city.cityStats.finalStatList

    // Expansion
    @Readonly fun hasChoosableTiles(): Boolean = city.expansion.getChoosableTiles().any()
    @Readonly fun getCultureToNextTile(): Int = city.expansion.getCultureToNextTile()
    @Readonly fun getCultureStored(): Int = city.expansion.cultureStored

    // Constructions
    val constructions: CityConstructionsView get() = CityConstructionsView(city.cityConstructions)
    @Readonly fun currentConstructionName(): String = city.cityConstructions.currentConstructionName()
    @Readonly fun getBuiltBuildings(): Sequence<Building> = city.cityConstructions.getBuiltBuildings()
    @Readonly fun isPuppet(): Boolean = city.isPuppet
    @Readonly fun hasMatchingUnique(uniqueType: UniqueType): Boolean = city.getMatchingUniques(uniqueType).any()
    @Readonly fun getDisabledConstructions(): Set<String> = city.disabledConstructions
    @Readonly fun isStatRelated(stat: Stat, building: Building): Boolean = building.isStatRelated(stat, city)
    @Readonly fun getProductionTooltip(construction: PerpetualConstruction): String = construction.getProductionTooltip(city)
    @Readonly fun getResourceRequirementsPerTurn(construction: IConstruction): Counter<String> =
        if (construction is BaseUnit) construction.getResourceRequirementsPerTurn(city.civ.state)
        else construction.getResourceRequirementsPerTurn(city.state)
    @Readonly fun getStockpiledResourceRequirements(construction: IConstruction): Counter<String> =
        construction.getStockpiledResourceRequirements(city.state)
    @Readonly fun getConstructionProductionCost(construction: INonPerpetualConstruction): Int =
        construction.getProductionCost(city.civ, city)
    @Readonly fun getUnitDescription(unit: BaseUnit): String = unit.getDescription(city)
    @Readonly fun getBuildingDescription(building: Building): String = building.getDescription(city, true)
    @Readonly fun getConversionRate(statConversion: PerpetualConstruction.StatConversion): Int = statConversion.getConversionRate(city)
    @Readonly fun getGoldForSellingBuilding(buildingName: String): Int = city.getGoldForSellingBuilding(buildingName)
    @Readonly fun hasSoldBuildingThisTurn(): Boolean = city.hasSoldBuildingThisTurn
    @Readonly fun isGodModeEnabled(): Boolean = city.civ.gameInfo.gameParameters.godMode
    @Readonly fun getUnitShouldUseSavedPromotion(baseUnit: String): Boolean? = city.unitShouldUseSavedPromotion[baseUnit]
    @Readonly fun getCityAmbienceSound(): String = city.civ.getEra().citySound
    @Readonly fun isBeingRazed(): Boolean = city.isBeingRazed
    @Readonly fun isCapital(): Boolean = city.isCapital()
    @Readonly fun getGarrison(): MapUnitView? = city.getGarrison()?.let { MapUnitView(it, civView) }
    @Readonly fun canBeDestroyed(): Boolean = city.canBeDestroyed()
    @Readonly fun getExpandRange(): Int = city.getExpandRange()
    @Readonly fun chooseNewTileToOwn(): Tile? = city.expansion.chooseNewTileToOwn()
    @Readonly fun getImprovementToCreate(construction: Building): TileImprovement? =
        construction.getImprovementToCreate(city.getRuleset(), city.civ)
    @Readonly fun hasFreeBuilding(building: Building): Boolean =
        city.civ.civConstructions.hasFreeBuilding(city, building)

    // Resources/misc
    @Readonly fun getResourceStockpiles(): Counter<String> = city.resourceStockpiles
    @Readonly fun getCityResourcesAvailableToCity(): ResourceSupplyList = CityResources.getCityResourcesAvailableToCity(city)
    @Readonly fun getGreatPersonPointsBreakdown(): GreatPersonPointsBreakdown = GreatPersonPointsBreakdown(city)
    @Readonly fun getRuleset(): Ruleset = city.getRuleset()
    @Readonly fun getBuildingStats(building: Building): Stats = building.getStats(city)

    @Readonly fun getStatReserve(stat: Stat): Int = city.getStatReserve(stat)
    @Readonly fun getMajorityReligion(): Religion? = city.religion.getMajorityReligion()
    @Readonly fun getYourReligion(): Religion? = viewer.religionManager.religion
    @Readonly fun canBePurchasedWithAnyStat(construction: INonPerpetualConstruction): Boolean =
        construction.canBePurchasedWithAnyStat(city)
    @Readonly fun canBePurchasedWithStat(construction: INonPerpetualConstruction, stat: Stat): Boolean =
        construction.canBePurchasedWithStat(city, stat)

    @Readonly fun isOwnedByViewer(): Boolean = city.civ === viewer
    @Readonly fun isOwnedTile(tile: Tile): Boolean = tile.getCity() === city
    @Readonly private fun getTile(tileView: TileView) = tileView.getTile()

    // ACTIONS
    // 帧同步: 完成回合后 (myTurnFinished) 禁止城市配置操作 — 结算已按旧配置入账,
    // 再改配置 → 服务器状态变但本回合产出已入账 → 显示与入账不符 (用户反馈: 粮取max产取max)
    private fun canChangeState() = city.civ === viewer &&
            (viewer.isCurrentPlayer() || FrameSync.isFsMode(viewer.gameInfo)) &&
            !(FrameSync.isFsMode(viewer.gameInfo) && FrameSync.myTurnFinished)

    fun tryLockTile(tileView: TileView): Boolean {
        if (!canChangeState()) return false
        if (!isWorked(tileView)) return false
        if (FrameSync.isFsMode(city.civ.gameInfo)) {
            // 帧同步: 服务器权威 (锁定状态由广播同步)
            val pos = getTile(tileView).position
            FrameSync.sendOp("city.lockTile", mapOf("cityId" to city.id, "tileX" to pos.x!!, "tileY" to pos.y!!))
            return true
        }
        return city.lockTile(getTile(tileView))
    }
    fun tryUnlockTile(tileView: TileView): Boolean {
        if (!canChangeState()) return false
        if (FrameSync.isFsMode(city.civ.gameInfo)) {
            val pos = getTile(tileView).position
            FrameSync.sendOp("city.unlockTile", mapOf("cityId" to city.id, "tileX" to pos.x!!, "tileY" to pos.y!!))
            return true
        }
        return city.unlockTile(getTile(tileView))
    }
    fun tryBuyTile(tileView: TileView): Boolean {
        if (!canChangeState()) return false
        if (!city.expansion.canBuyTile(getTile(tileView))) return false
        city.expansion.buyTile(getTile(tileView))
        return true
    }
    fun tryWorkTile(tileView: TileView): Boolean {
        if (!canChangeState()) return false
        if (FrameSync.isFsMode(city.civ.gameInfo)) {
            // 帧同步: 服务器权威 (工作格由广播同步)
            val pos = getTile(tileView).position
            FrameSync.sendOp("city.workTile", mapOf("cityId" to city.id, "tileX" to pos.x!!, "tileY" to pos.y!!))
            return true
        }
        return city.workTile(getTile(tileView))
    }
    fun tryStopWorkingTile(tileView: TileView): Boolean {
        if (!canChangeState()) return false
        if (FrameSync.isFsMode(city.civ.gameInfo)) {
            val pos = getTile(tileView).position
            FrameSync.sendOp("city.stopWorkTile", mapOf("cityId" to city.id, "tileX" to pos.x!!, "tileY" to pos.y!!))
            return true
        }
        return city.stopWorkingTile(getTile(tileView))
    }
    fun tryAddToQueue(name: String): Boolean {
        if (!canChangeState()) return false
        if (FrameSync.isFsMode(city.civ.gameInfo)) {
            // 帧同步: 服务器权威 (队列由广播/回合末存档同步)
            FrameSync.sendOp("city.setProduction", mapOf("cityId" to city.id, "item" to name))
            return true
        }
        city.cityConstructions.addToQueue(name)
        return true
    }
    fun tryRemoveFromQueue(index: Int, automatic: Boolean): Boolean {
        if (!canChangeState()) return false
        if (FrameSync.isFsMode(city.civ.gameInfo)) {
            FrameSync.sendOp("city.removeFromQueue", mapOf("cityId" to city.id, "index" to index))
            return true
        }
        city.cityConstructions.removeFromQueue(index, automatic)
        return true
    }
    fun tryRaisePriority(index: Int): Int? {
        if (!canChangeState()) return null
        if (FrameSync.isFsMode(city.civ.gameInfo)) {
            FrameSync.sendOp("city.raisePriority", mapOf("cityId" to city.id, "index" to index))
            return null
        }
        return city.cityConstructions.raisePriority(index)
    }
    fun tryLowerPriority(index: Int): Int? {
        if (!canChangeState()) return null
        if (FrameSync.isFsMode(city.civ.gameInfo)) {
            FrameSync.sendOp("city.lowerPriority", mapOf("cityId" to city.id, "index" to index))
            return null
        }
        return city.cityConstructions.lowerPriority(index)
    }
    fun updateTileStats() = city.cityStats.updateTileStats()

    fun updateCityStats() = city.cityStats.update()
    fun tryRenameCity(name: String): Boolean {
        if (!canChangeState()) return false
        if (com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(city.civ.gameInfo)) {
            // UncivGC 帧同步: 服务器权威 (原名本地执行会重载回滚); 结果由城市名广播同步
            com.unciv.ui.screens.worldscreen.FrameSync.sendOp(
                "city.rename", mapOf("cityId" to city.id, "name" to name))
            return true
        }
        city.name = name
        return true
    }
    fun tryAnnexCity(): Boolean {
        if (!canChangeState()) return false
        if (FrameSync.isFsMode(city.civ.gameInfo)) {
            // 帧同步: 服务器权威 (annexCity 本地执行会被同步回滚)
            FrameSync.sendOp("city.annex", mapOf("cityId" to city.id))
            return true
        }
        city.annexCity()
        return true
    }
    fun trySetRazing(raze: Boolean): Boolean {
        if (!canChangeState()) return false
        if (FrameSync.isFsMode(city.civ.gameInfo)) {
            // 帧同步: 服务器权威 (isBeingRazed 本地改会被同步回滚 → “无法停止拆除”)
            FrameSync.sendOp("city.setRazing", mapOf("cityId" to city.id, "raze" to raze))
            return true
        }
        city.isBeingRazed = raze
        return true
    }
    fun tryAddToQueueWithTile(construction: IConstruction, tile: Tile): Boolean {
        if (!canChangeState()) return false
        if (FrameSync.isFsMode(city.civ.gameInfo)) {
            FrameSync.sendOp("city.addToQueueWithTile", mapOf(
                "cityId" to city.id, "item" to construction.name,
                "tileX" to tile.position.x!!, "tileY" to tile.position.y!!))
            return true
        }
        city.cityConstructions.addToQueue(construction, tile = tile)
        return true
    }
    fun trySetUnitShouldUseSavedPromotion(baseUnit: String, value: Boolean): Boolean {
        if (!canChangeState()) return false
        if (FrameSync.isFsMode(city.civ.gameInfo)) {
            FrameSync.sendCitySetUnitSavedPromotion(city.id, baseUnit, value)
            return true
        }
        city.unitShouldUseSavedPromotion[baseUnit] = value
        return true
    }
    fun trySellBuilding(construction: Building): Boolean {
        if (!canChangeState()) return false
        city.sellBuilding(construction)
        return true
    }
    fun tryMoveEntryToTop(index: Int) {
        if (!canChangeState()) return
        if (FrameSync.isFsMode(city.civ.gameInfo)) {
            FrameSync.sendOp("city.moveQueueEntry", mapOf("cityId" to city.id, "index" to index))
            return
        }
        city.cityConstructions.moveEntryToTop(index)
    }
    fun tryMoveEntryToEnd(index: Int) {
        if (!canChangeState()) return
        if (FrameSync.isFsMode(city.civ.gameInfo)) {
            FrameSync.sendCityMoveEntryToEnd(city.id, index)
            return
        }
        city.cityConstructions.moveEntryToEnd(index)
    }
    fun tryAddToQueueConstruction(construction: IConstruction, addToTop: Boolean = false) {
        if (!canChangeState()) return
        if (FrameSync.isFsMode(city.civ.gameInfo)) {
            FrameSync.sendCityAddToQueue(city.id, construction.name, addToTop)
            return
        }
        city.cityConstructions.addToQueue(construction, addToTop = addToTop)
    }
    fun tryRemoveAllByName(name: String) {
        if (!canChangeState()) return
        if (FrameSync.isFsMode(city.civ.gameInfo)) {
            FrameSync.sendCityRemoveFromQueueByName(city.id, name)
            return
        }
        city.cityConstructions.removeAllByName(name)
    }
    fun tryDisableConstruction(name: String) {
        if (!canChangeState()) return
        if (FrameSync.isFsMode(city.civ.gameInfo)) {
            FrameSync.sendCityDisableConstruction(city.id, name, disable = true)
            return
        }
        city.disabledConstructions.add(name)
    }
    fun tryEnableConstruction(name: String) {
        if (!canChangeState()) return
        if (FrameSync.isFsMode(city.civ.gameInfo)) {
            FrameSync.sendCityDisableConstruction(city.id, name, disable = false)
            return
        }
        city.disabledConstructions.remove(name)
    }
    fun tryReassignPopulation(resetLocked: Boolean = false): Boolean {
        if (!canChangeState()) return false
        // UncivGC 帧同步: 服务器权威 (纯拦截 — 本地执行会被状态广播回滚)
        if (com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(city.civ.gameInfo)) {
            com.unciv.ui.screens.worldscreen.FrameSync.sendOp("city.reassignPopulation", mapOf(
                "cityId" to city.id,
                "resetLocked" to resetLocked))
            return true
        }
        city.reassignPopulation(resetLocked)
        return true
    }
    fun tryToggleAvoidGrowth(): Boolean {
        if (!canChangeState()) return false
        // UncivGC 帧同步: 服务器权威 (纯拦截 — 本地执行会被状态广播回滚)
        if (com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(city.civ.gameInfo)) {
            com.unciv.ui.screens.worldscreen.FrameSync.sendOp("city.toggleAvoidGrowth", mapOf(
                "cityId" to city.id))
            return true
        }
        city.avoidGrowth = !city.avoidGrowth
        city.reassignPopulation()
        return true
    }
    fun tryEnableManualSpecialists(): Boolean {
        if (!canChangeState()) return false
        // UncivGC 帧同步: 服务器权威 (纯拦截 — 本地执行会被状态广播回滚)
        if (com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(city.civ.gameInfo)) {
            com.unciv.ui.screens.worldscreen.FrameSync.sendOp("city.enableManualSpecialists", mapOf(
                "cityId" to city.id))
            return true
        }
        city.manualSpecialists = true
        return true
    }
    fun tryDisableManualSpecialists(): Boolean {
        if (!canChangeState()) return false
        if (FrameSync.isFsMode(city.civ.gameInfo)) {
            // 服务器权威 (manual=false; 原 enable op 默认 true, 兼容)
            FrameSync.sendCityDisableManualSpecialists(city.id)
            return true
        }
        city.manualSpecialists = false
        city.reassignPopulation()
        return true
    }
    fun tryAssignSpecialist(specialistName: String): Boolean {
        if (!canChangeState()) return false
        if (FrameSync.isFsMode(city.civ.gameInfo)) {
            FrameSync.sendCityAssignSpecialist(city.id, specialistName, delta = 1)
            return true
        }
        city.population.specialistAllocations.add(specialistName, 1)
        city.manualSpecialists = true
        city.cityStats.update()
        return true
    }
    fun tryUnassignSpecialist(specialistName: String): Boolean {
        if (!canChangeState()) return false
        if (FrameSync.isFsMode(city.civ.gameInfo)) {
            FrameSync.sendCityAssignSpecialist(city.id, specialistName, delta = -1)
            return true
        }
        city.population.specialistAllocations.add(specialistName, -1)
        city.manualSpecialists = true
        city.cityStats.update()
        return true
    }
    fun trySetCityFocus(focus: CityFocus): Boolean {
        if (!canChangeState()) return false
        // UncivGC 帧同步: 服务器权威 (纯拦截 — 本地执行则服务器不知道, 下次状态广播回滚)
        val city = this.city
        val gameInfo = city.civ.gameInfo
        if (com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(gameInfo)) {
            com.unciv.ui.screens.worldscreen.FrameSync.sendOp("city.setFocus", mapOf(
                "cityId" to city.id,
                "focus" to focus.name))
            return true
        }
        city.setCityFocus(focus)
        city.reassignPopulation()
        return true
    }

}

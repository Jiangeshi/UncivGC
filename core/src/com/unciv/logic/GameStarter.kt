package com.unciv.logic

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.files.MapSaver
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.MirroringType
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stats
import com.unciv.models.translations.equalsPlaceholderText
import com.unciv.models.translations.getPlaceholderParameters
import com.unciv.utils.debug
import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Readonly

/**
 * Starts a new game
 *
 * Map terrain is determinisic, but game setup, including start locations, resources, and other
 * details, are random, based on [GameContext.stateBasedRandom], which is based on the gameId, which
 *  is fully random per game.
 */
class GameStarter private constructor(
    private val gameSetupInfo: GameSetupInfo
) {
    companion object {
        // temporary instrumentation while tuning/debugging
        private const val consoleTimings = false

        fun startNewGame(gameSetupInfo: GameSetupInfo): GameInfo =
            GameStarter(gameSetupInfo).gameInfo
    }

    private val gameInfo = GameInfo()
    private val rng = GameContext(gameInfo = gameInfo).stateBasedRandom("GameStarter")
    private val ruleset: Ruleset
    private lateinit var tileMap: TileMap

    init {
        if (consoleTimings)
            debug("\nGameStarter run with parameters %s, map %s", gameSetupInfo.gameParameters, gameSetupInfo.mapParameters)

        // In the case where we used to have an extension mod, and now we don't, we cannot "unselect" it in the UI.
        // We need to remove the dead mods so there aren't problems later.
        gameSetupInfo.gameParameters.mods.removeAll { !RulesetCache.containsKey(it) }

        // [TEMPORARY] If we have a base ruleset in the mod list, we make that our base ruleset
        val baseRulesetInMods = gameSetupInfo.gameParameters.mods.firstOrNull { RulesetCache[it]!!.modOptions.isBaseRuleset }
        if (baseRulesetInMods != null)
            gameSetupInfo.gameParameters.baseRuleset = baseRulesetInMods

        if (!RulesetCache.containsKey(gameSetupInfo.gameParameters.baseRuleset))
            gameSetupInfo.gameParameters.baseRuleset = RulesetCache.getVanillaRuleset().name

        gameInfo.gameParameters = gameSetupInfo.gameParameters
        ruleset = RulesetCache.getComplexRuleset(gameInfo.gameParameters)
        val mapGen = MapGenerator(ruleset)

        // Make sure that a valid game speed is loaded (catches a base ruleset not using the default game speed)
        if (!ruleset.speeds.containsKey(gameSetupInfo.gameParameters.speed)) {
            gameSetupInfo.gameParameters.speed = ruleset.speeds.keys.first()
        }

        var phaseOneChosenCivs: List<Player> = emptyList() // Never used, but the compiler needs it due to runAndMeasure capturing the var
        if (gameSetupInfo.mapParameters.name != "") runAndMeasure("loadMap") {
            tileMap = MapSaver.loadMap(gameSetupInfo.mapFile!!)
            // Don't override the map parameters - this can include if we world wrap or not!
            phaseOneChosenCivs = chooseCivilizations(existingMap = true)
        } else runAndMeasure("generateMap") {
            // The MapGen needs to know what civs are in the game to generate regions, starts and resources
            phaseOneChosenCivs = chooseCivilizations(existingMap = false)
            addCivilizations(phaseOneChosenCivs)
            tileMap = mapGen.generateMap(gameSetupInfo.mapParameters, gameSetupInfo.gameParameters, gameInfo)
            tileMap.mapParameters = gameSetupInfo.mapParameters
            // Now forget them for a moment! MapGen can silently fail to place some city states, so then we'll use the old fallback method to place those.
            gameInfo.civilizations.clear()
        }

        runAndMeasure("addCivilizations") {
            gameInfo.tileMap = tileMap
            tileMap.gameInfo = gameInfo // need to set this transient before placing units in the map
            addCivilizations(phaseOneChosenCivs) // this is before gameInfo.setTransients, so gameInfo doesn't yet have the gameBasics
        }

        runAndMeasure("Remove units") {
            // Remove units for civs that aren't in this game
            for (tile in tileMap.values)
                for (unit in tile.getUnits())
                    if (gameInfo.civilizations.none { it.civID == unit.owner }) {
                        unit.currentTile = tile
                        unit.setTransients(ruleset)
                        unit.removeFromTile()
                    }
        }

        if (tileMap.continentSizes.isEmpty())   // Probably saved map without continent data
            runAndMeasure("assignContinents") {
                tileMap.assignContinents(TileMap.AssignContinentsMode.Ensure)
            }

        runAndMeasure("setTransients") {
            // mark as no migrateToTileHistory necessary
            gameInfo.historyStartTurn = 0
            tileMap.setTransients(ruleset) // if we're starting from a map with pre-placed units, they need the civs to exist first
            tileMap.setStartingLocationsTransients()

            gameInfo.difficulty = gameSetupInfo.gameParameters.difficulty

            gameInfo.setTransients() // needs to be before placeBarbarianUnit because it depends on the tilemap having its gameInfo set
        }

        runAndMeasure("addCivStartingUnits") {
            addCivStartingUnits()
        }

        runAndMeasure("Policies") {
            addCivPolicies()
        }

        runAndMeasure("Techs and Stats") {
            addCivTechs()
        }

        runAndMeasure("Starting stats") {
            addCivStats()
        }

        // remove starting locations once we're done
        tileMap.clearStartingLocations()

        // set max starting movement for units loaded from map
        for (tile in tileMap.values) {
            for (unit in tile.getUnits()) unit.currentMovement = unit.getMaxMovement().toFloat()
        }

        // This triggers the one-time greeting from Nation.startIntroPart1/2
        addPlayerIntros()

        UncivGame.Current.settings.apply {
            lastGameSetup = gameSetupInfo
            save()
        }
    }

    private fun runAndMeasure(text: String, action: ()->Unit) {
        if (!consoleTimings) return action()
        val startNanos = System.nanoTime()
        action()
        val delta = System.nanoTime() - startNanos
        debug("GameStarter.%s took %s.%sms", text, delta/1000000L, (delta/10000L).rem(100))
    }

    private fun addPlayerIntros() {
        gameInfo.civilizations.filter {
            // isNotEmpty should also exclude a spectator
            it.playerType == PlayerType.Human && it.nation.startIntroPart1.isNotEmpty()
        }.forEach {
            it.popupAlerts.add(PopupAlert(AlertType.StartIntro, ""))
        }
    }

    private fun addCivTechs() {
        fun Civilization.addTechSilently(name: String) {
            // check if the technology is in the ruleset and not already researched
            if (!ruleset.technologies.containsKey(name)) return
            if (tech.isResearched(name)) return
            tech.addTechnology(name, false)
        }

        for (civInfo in gameInfo.civilizations) {
            if (civInfo.isBarbarian) continue

            for (tech in ruleset.technologies.values.filter { it.hasUnique(UniqueType.StartingTech) })
                civInfo.addTechSilently(tech.name)

            if (!civInfo.isHuman())
                for (tech in gameInfo.getDifficulty().aiFreeTechs)
                    civInfo.addTechSilently(tech)

            // generic start with technology unique
            civInfo.forEachMatchingUnique(UniqueType.StartsWithTech) { unique ->
                civInfo.addTechSilently(unique.params[0])
            }

            // add all techs to spectators
            if (civInfo.isSpectator())
                for (tech in ruleset.technologies.values)
                    civInfo.addTechSilently(tech.name)

            // Add techs for advanced starting era
            val startingEraNumber = ruleset.eras[gameSetupInfo.gameParameters.startingEra]!!.eraNumber
            for (tech in ruleset.technologies.values) {
                if (ruleset.eras[tech.era()]!!.eraNumber >= startingEraNumber) continue
                if (civInfo.tech.isUnresearchable(tech)) continue
                civInfo.addTechSilently(tech.name)
            }

            // Since adding technologies generates popups (addTechnology parameter showNotification only suppresses Notifications)
            civInfo.popupAlerts.clear()
        }
    }

    private fun addCivPolicies() {
        for (civInfo in gameInfo.civilizations.filter { !it.isBarbarian }) {

            // generic start with policy unique
            civInfo.forEachMatchingUnique(UniqueType.StartsWithPolicy) { unique ->
                // get the parameter from the unique
                val policyName = unique.params[0]

                // check if the policy is in the ruleset and not already adopted
                if (!ruleset.policies.containsKey(policyName) || civInfo.policies.isAdopted(policyName))
                    return@forEachMatchingUnique

                val policyToAdopt = ruleset.policies[policyName]!!
                civInfo.policies.run {
                    freePolicies++
                    adopt(policyToAdopt)
                }
            }
        }
    }

    private fun addCivStats() {
        val ruleSet = gameInfo.ruleset
        val startingEra = gameInfo.gameParameters.startingEra
        val era = ruleSet.eras[startingEra]!!
        for (civInfo in gameInfo.civilizations.filter { !it.isBarbarian && !it.isSpectator() }) {
            civInfo.addGold((era.startingGold * gameInfo.speed.goldCostModifier).toInt())
            civInfo.policies.addCulture((era.startingCulture * gameInfo.speed.cultureCostModifier).toInt())
        }
    }

    @Readonly
    private fun chooseCivilizations(existingMap: Boolean): List<Player> {
        val newGameParameters = gameSetupInfo.gameParameters
        val selectedPlayerNames = newGameParameters.players
            .map { it.chosenCiv }.toSet()
        @LocalState val randomNationsPool = (
            if (gameSetupInfo.gameParameters.enableRandomNationsPool)
                ruleset.nations.asSequence().map { it.value }
                    .filter { it.name in gameSetupInfo.gameParameters.randomNationsPool }
            else
                ruleset.nations.asSequence().map { it.value }
                    .filter { it.isMajorCiv && !it.hasUnique(UniqueType.WillNotBeChosenForNewGames) }
            ).filter { it.name !in selectedPlayerNames }
            .shuffled().let { ArrayDeque(it.toList()) }

        val civNamesWithStartingLocations =
            if (existingMap) gameInfo.tileMap.startingLocationsByNation.keys
            else emptySet()
        @LocalState val presetRandomNationsPool = randomNationsPool
            .filter { it.name in civNamesWithStartingLocations }
            .shuffled().let { ArrayDeque(it) }
        randomNationsPool.removeAll(presetRandomNationsPool)

        // At this point the civ names in newGameParameters.players, randomNationsPool and presetRandomNationsPool
        // are mutually exclusive. Random should **not** exist in the two random pools, but we have not explicitly guarded
        // here against the UI leaving one in gameParameters.randomNationsPool or map editor in tileMap.startingLocationsByNation.

        var extraRandomAIPlayers = 0
        var selectedAIToSkip = emptyList<Player>()
        if (newGameParameters.randomNumberOfPlayers) {
            // This swaps min and max if the user accidentally swapped min and max
            val min = newGameParameters.minNumberOfPlayers.coerceAtMost(newGameParameters.maxNumberOfPlayers)
            val max = newGameParameters.maxNumberOfPlayers.coerceAtLeast(newGameParameters.minNumberOfPlayers)
            val nonAICount = newGameParameters.players.count {
                it.playerType === PlayerType.Human || it.chosenCiv === Constants.spectator
            }
            val desiredNumberOfPlayers = (min.coerceAtLeast(nonAICount)..max.coerceAtLeast(nonAICount)).random(rng)

            if (desiredNumberOfPlayers > newGameParameters.players.size) {
                extraRandomAIPlayers = desiredNumberOfPlayers - newGameParameters.players.size
            } else if (desiredNumberOfPlayers < newGameParameters.players.size) {
                val extraPlayers = newGameParameters.players.size - desiredNumberOfPlayers
                selectedAIToSkip = newGameParameters.players
                    .filter { it.playerType === PlayerType.AI }
                    .shuffled()
                    .sortedByDescending { it.chosenCiv == Constants.random }
                    .subList(0, extraPlayers)
            }
        }

        // Add player entries to the result

        val chosenPlayers = (
            // Join two Sequences, one the explicitly chosen players...
            newGameParameters.players.asSequence()
                .filterNot { it in selectedAIToSkip }
                .sortedWith(compareBy<Player> { it.chosenCiv == Constants.random } // Nonrandom before random
                    .thenBy { it.playerType == PlayerType.AI }) // Human before AI
                // ...another for the extra random ones
                + (0 until extraRandomAIPlayers).asSequence().map { Player() }
            ).mapNotNull {
                // Resolve random players
                when {
                    it.chosenCiv != Constants.random -> Player(ruleset.nations[it.chosenCiv]!!, it.playerType, it.playerId)
                    presetRandomNationsPool.isNotEmpty() -> Player(presetRandomNationsPool.removeLast(), it.playerType, it.playerId)
                    randomNationsPool.isNotEmpty() -> Player(randomNationsPool.removeLast(), it.playerType, it.playerId)
                    else -> null
                }
            }.toMutableList()

        // ensure Spectators always first players
        val spectators = chosenPlayers.filter { it.chosenCiv == Constants.spectator }
        val otherPlayers = chosenPlayers.filterNot { it.chosenCiv == Constants.spectator }.toMutableList()

        // Shuffle Major Civs
        if (newGameParameters.shufflePlayerOrder) otherPlayers.shuffle()

        chosenPlayers.clear()
        chosenPlayers.addAll(spectators)
        chosenPlayers.addAll(otherPlayers)

        // Add CityStates to result - disguised as normal AI, but addCivilizations will detect them
        val numberOfCityStates = if (newGameParameters.randomNumberOfCityStates) {
            // This swaps min and max if the user accidentally swapped min and max
            val min = newGameParameters.minNumberOfCityStates.coerceAtMost(newGameParameters.maxNumberOfCityStates)
            val max = newGameParameters.maxNumberOfCityStates.coerceAtLeast(newGameParameters.minNumberOfCityStates)
            (min..max).random(rng)
        } else {
            newGameParameters.numberOfCityStates
        }

        chosenPlayers += ruleset.nations.asSequence()
            .filter {
                it.value.isCityState &&
                    !it.value.hasUnique(UniqueType.WillNotBeChosenForNewGames)
            }.map { it.value }
            .shuffled()
            .sortedByDescending { it.name in civNamesWithStartingLocations }  // please those with location first
            .take(numberOfCityStates)
            .map { Player(it) }

        return chosenPlayers
    }

    private fun addCivilizations(chosenPlayers: List<Player>) {
        val newGameParameters = gameSetupInfo.gameParameters
        if (!newGameParameters.noBarbarians) {
            val barbs = ruleset.nations[Constants.barbarians]
            if (barbs != null) {
                val barbarianCivilization = Civilization(barbs)
                gameInfo.civilizations.add(barbarianCivilization)
            }
        }

        val usedCivNations = chosenPlayers.map { it.chosenNation }.toSet()
        val usedMajorCivs = ruleset.nations.asSequence()
            .map { it.value }
            .filter { it.isMajorCiv && it in usedCivNations }

        for (player in chosenPlayers) {
            val civ = Civilization(player.chosenNation)
            if (civ.isMajorCiv() || civ.isSpectator()) {
                civ.playerType = player.playerType
                civ.playerId = player.playerId
                civ.playerMinutesBeforeForceResign = newGameParameters.minutesUntilForceResign
            }
            else if (!civ.cityStateFunctions.initCityState(ruleset, newGameParameters.startingEra, usedMajorCivs, rng))
                continue
            gameInfo.civilizations.add(civ)
        }
    }

    private fun addCivStartingUnits() {

        val ruleSet = gameInfo.ruleset
        val tileMap = gameInfo.tileMap

        val cityCenterMinStats = sequenceOf(ruleSet.tileImprovements[Constants.cityCenter])
            .filterNotNull()
            .flatMap { it.getMatchingUniques(UniqueType.EnsureMinimumStats, GameContext.IgnoreConditionals) }
            .firstOrNull()
            ?.stats ?: Stats.DefaultCityCenterMinimum

        val startScores = HashMap<Tile, Float>(tileMap.values.size)
        for (tile in tileMap.values) {
            startScores[tile] = tile.stats.getTileStartScore(cityCenterMinStats)
        }
        val allCivs = gameInfo.civilizations.filter { !it.isBarbarian }
        val landTilesInBigEnoughGroup = getCandidateLand(allCivs.size, startScores)

        // First we get start locations for the major civs, on the second pass the city states (without predetermined starts) can squeeze in wherever
        val civNamesWithStartingLocations = tileMap.startingLocationsByNation.keys
        val bestCivs = allCivs.filter { (!it.isCityState || it.civID in civNamesWithStartingLocations)
            && !it.isSpectator()}
        val bestLocations = getStartingLocations(bestCivs, landTilesInBigEnoughGroup, startScores)
        // UncivGC: 镜像出生点 — 地图镜像时, 把后放置文明的起点改为先前起点的镜像位置 (地形已镜像, 位置等效)
        val bestLocationsToUse = mirrorStartingLocations(bestLocations)
        for ((civ, tile) in bestLocationsToUse) {
            // A nation can have multiple marked starting locations, of which the first pass may have chosen one
            tileMap.removeStartingLocations(civ.civID)
            // Mark the best start locations so we remember them for the second pass
            tileMap.addStartingLocation(civ.civID, tile)
        }

        val startingLocations = getStartingLocations(allCivs, landTilesInBigEnoughGroup, startScores)

        // no starting units for Barbarians and Spectators
        determineStartingUnitsAndLocations(gameInfo, startingLocations, ruleSet)
    }

    /** UncivGC: 镜像出生点 — 只镜像主要文明 (城邦保持随机/原位置), 按地图镜像类型把镜像位置分配给后续文明 (四向=4人完美对称).
     *  位置无效(海洋/界外)时保留随机起点, 不强制 */
    private fun mirrorStartingLocations(locations: HashMap<Civilization, Tile>): HashMap<Civilization, Tile> {
        val mirroring = tileMap.mapParameters.mirroring
        if (mirroring == MirroringType.none) return locations
        val result = HashMap(locations)
        val majors = locations.entries.filter { !it.key.isCityState }.sortedBy { it.key.civID }
        fun mirrorPositions(position: HexCoord): List<HexCoord> = when (mirroring) {
            MirroringType.leftright -> listOf(HexCoord.of(position.y, position.x))
            MirroringType.topbottom -> listOf(HexCoord.of(-position.y, -position.x))
            MirroringType.aroundCenterTile -> listOf(HexCoord.of(-position.x, -position.y))
            MirroringType.fourway -> listOf(
                HexCoord.of(position.y, position.x),
                HexCoord.of(-position.y, -position.x),
                HexCoord.of(-position.x, -position.y),
            )
            else -> emptyList()
        }
        var index = 0
        while (index < majors.size) {
            val (civ, tile) = majors[index]
            result[civ] = tile
            val mirrors = mirrorPositions(tile.position)
                .mapNotNull { tileMap.getIfTileExistsOrNull(it.x, it.y) }
                .filter { it.isLand }
            for (mirrorTile in mirrors) {
                index++
                if (index >= majors.size) break
                result[majors[index].key] = mirrorTile
            }
            index++
        }
        return result
    }

    private fun removeAncientRuinsNearStartingLocation(startingLocation: Tile) {
        startingLocation.forEachTileAtDistance(3) { tile ->
            if (tile.tileImprovement != null && tile.tileImprovement!!.isAncientRuinsEquivalent()) {
                tile.removeImprovement() // Remove ancient ruins in immediate vicinity
            }
        }
    }

    private fun determineStartingUnitsAndLocations(
        gameInfo: GameInfo,
        startingLocations: HashMap<Civilization, Tile>,
        ruleset: Ruleset
    ) {
        val startingEra = gameInfo.gameParameters.startingEra
        val settlerLikeUnits = ruleset.units.filter { it.value.isCityFounder() }

        for (civ in gameInfo.civilizations.filter { !it.isBarbarian && !it.isSpectator() }) {
            val startingLocation = startingLocations[civ]!!

            removeAncientRuinsNearStartingLocation(startingLocation)
            val startingUnits = getStartingUnitsForEraAndDifficulty(civ, startingEra)
            adjustStartingUnitsForCityStatesAndOneCityChallenge(civ, startingUnits, settlerLikeUnits)
            placeStartingUnits(civ, startingLocation, startingUnits, ruleset.eras[startingEra]!!.startingMilitaryUnit, settlerLikeUnits)

            // Trigger any global or nation uniques that should be triggered.
            // We may need the starting location for some uniques, which is why we're doing it now
            // This relies on gameInfo.ruleset already being initialized
            val startingTriggers = gameInfo.getGlobalUniques().uniqueObjects + civ.nation.uniqueObjects
            for (unique in startingTriggers) {
                if (unique.hasTriggerConditional() || !unique.conditionalsApply(civ.state)) continue
                repeat(unique.getUniqueMultiplier(civ.state)) {
                    UniqueTriggerActivation.triggerUnique(unique, civ, tile = startingLocation)
                }
            }
        }
    }

    @Readonly
    private fun getStartingUnitsForEraAndDifficulty(civ: Civilization, startingEra: String): MutableList<String> {
        @LocalState val startingUnits = ruleset.eras[startingEra]?.getStartingUnits(ruleset)
            ?: throw Exception("Era $startingEra does not exist in the ruleset!")

        // Add extra units granted by difficulty
        startingUnits.addAll(when {
            civ.isHuman() -> gameInfo.getDifficulty().playerBonusStartingUnits
            civ.isMajorCiv() -> gameInfo.getDifficulty().aiMajorCivBonusStartingUnits
            else -> gameInfo.getDifficulty().aiCityStateBonusStartingUnits
        })

        return startingUnits
    }

    @Readonly
    private fun getEquivalentUnit(
        civ: Civilization,
        unitParam: String,
        eraUnitReplacement: String,
        settlerLikeUnits: Map<String, BaseUnit>
    ): BaseUnit? {
        var unit = unitParam // We want to change it and this is the easiest way to do so
        if (unit == Constants.eraSpecificUnit) unit = eraUnitReplacement
        if (unit == Constants.settler && Constants.settler !in ruleset.units) {
            val buildableSettlerLikeUnits =
                settlerLikeUnits.filter {
                    it.value.isBuildable(civ)
                        && it.value.isCivilian()
                }
            if (buildableSettlerLikeUnits.isEmpty()) return null // No settlers in this mod
            return civ.getEquivalentUnit(buildableSettlerLikeUnits.keys.random(rng))
        }
        if (unit == "Worker" && "Worker" !in ruleset.units) {
            val buildableWorkerLikeUnits = ruleset.units.filter {
                it.value.hasUnique(UniqueType.BuildImprovements) &&
                    it.value.isBuildable(civ) && it.value.isCivilian()
            }
            if (buildableWorkerLikeUnits.isEmpty()) return null // No workers in this mod
            return civ.getEquivalentUnit(buildableWorkerLikeUnits.keys.random(rng))
        }
        return civ.getEquivalentUnit(unit)
    }

    private fun adjustStartingUnitsForCityStatesAndOneCityChallenge(
        civ: Civilization,
        startingUnits: MutableList<String>,
        settlerLikeUnits: Map<String, BaseUnit>
    ) {
        // Adjust starting units for city states
        if (civ.isCityState && !gameInfo.ruleset.modOptions.hasUnique(UniqueType.AllowCityStatesSpawnUnits)) {
            val startingSettlers = startingUnits.filter { settlerLikeUnits.contains(it) }

            startingUnits.clear()
            startingUnits.add(startingSettlers.random(rng))
        }

        // Adjust starting units for one city challenge
        if (civ.playerType == PlayerType.Human && gameInfo.gameParameters.oneCityChallenge) {
            val startingSettlers = startingUnits.filter { settlerLikeUnits.contains(it) }

            startingUnits.removeAll(startingSettlers)
            startingUnits.add(startingSettlers.random(rng))
        }
    }

    private fun placeStartingUnits(civ: Civilization, startingLocation: Tile, startingUnits: MutableList<String>, eraUnitReplacement: String, settlerLikeUnits: Map<String, BaseUnit>) {
        for (unit in startingUnits) {
            val unitToAdd = getEquivalentUnit(civ, unit, eraUnitReplacement, settlerLikeUnits)
            if (unitToAdd != null) civ.units.placeUnitNearTile(startingLocation.position, unitToAdd)
        }
    }

    private fun getCandidateLand(
        civCount: Int,
        startScores: HashMap<Tile, Float>
    ): Map<Tile, Float> {
        tileMap.assignContinents(TileMap.AssignContinentsMode.Ensure)

        // We want to  distribute starting locations fairly, and thus not place anybody on a small island
        // - unless necessary. Old code would only consider landmasses >= 20 tiles.
        // Instead, take continents until >=90% total area or everybody can get their own island
        val totalArea = tileMap.continentSizes.values.sum()
        var candidateArea = 0
        val candidateContinents = HashSet<Int>()
        for ((index, continentId) in tileMap.continentsSortedBySize.withIndex()) {
            candidateArea += tileMap.continentSizes[continentId]!!
            candidateContinents.add(continentId)
            if (candidateArea >= totalArea * 0.9f) break
            if (index >= civCount) break
        }

        return startScores.filter { it.key.getContinent() in candidateContinents }
    }

    private fun getStartingLocations(
        civs: List<Civilization>,
        landTilesInBigEnoughGroup: Map<Tile, Float>,
        startScores: HashMap<Tile, Float>
    ): HashMap<Civilization, Tile> {

        val civsOrderedByAvailableLocations = getCivsOrderedByAvailableLocations(civs)
        // UncivGC 组队 (2026-08-23): 同队出生在同一半图 — 按候选陆地 x 坐标均分队伍数段, 每队只用自己段
        val teamAllowedTiles = computeTeamAllowedTiles(civs, landTilesInBigEnoughGroup.keys)

        for (minimumDistanceBetweenStartingLocations in tileMap.tileMatrix.size / 6 downTo 0) {
            val freeTiles = getFreeTiles(landTilesInBigEnoughGroup, minimumDistanceBetweenStartingLocations)

            val startingLocations = getStartingLocationsForCivs(civsOrderedByAvailableLocations, freeTiles, startScores, minimumDistanceBetweenStartingLocations, teamAllowedTiles, allowFallbackGlobal = minimumDistanceBetweenStartingLocations == 0)
            if (startingLocations != null) return startingLocations
        }
        throw Exception("Didn't manage to get starting tiles even with distance of 1?")
    }

    /** UncivGC 组队 (2026-08-23): 每队的允许出生半区 (civ -> 候选格集合; 不在 map = 不限)。
     *  fsTeams 按 playerId 分组; AI/无队真人轮流分配段 (各段均衡); 未组队/候选格空 → 不限 (原逻辑)。 */
    private fun computeTeamAllowedTiles(civs: List<Civilization>, candidateTiles: Collection<Tile>): Map<Civilization, Set<Tile>> {
        val fsTeams = gameSetupInfo.gameParameters.fsTeams
        val teamCount = fsTeams.count { it.isNotEmpty() }
        if (teamCount <= 1 || candidateTiles.isEmpty()) return emptyMap()
        val civTeam = HashMap<Civilization, Int>()
        var next = 0
        for (civ in civs) {
            val pid = civ.playerId
            val idx = if (pid.isNotEmpty()) fsTeams.indexOfFirst { pid in it } else -1
            civTeam[civ] = if (idx >= 0) idx else (next++ % teamCount)
        }
        // 按候选陆地格数量均分 (不是 x 宽度): 陆地分布不均时每队仍拿到等量陆地, 防某段全是海
        val sortedTiles = candidateTiles.sortedBy { it.position.x }
        val perTeam = (sortedTiles.size + teamCount - 1) / teamCount
        val allowed = HashMap<Civilization, Set<Tile>>()
        for ((civ, idx) in civTeam) {
            val from = (idx * perTeam).coerceAtMost(sortedTiles.size)
            val to = ((idx + 1) * perTeam).coerceAtMost(sortedTiles.size)
            if (from < to) allowed[civ] = sortedTiles.subList(from, to).toSet()
        }
        return allowed
    }

    @Readonly
    private fun getCivsOrderedByAvailableLocations(civs: List<Civilization>): List<Civilization> {
        return civs.shuffled()   // Order should be random since it determines who gets best start
            .sortedBy { civ ->
                val startBias = civ.nation.getStartBias(ruleset, civ.getGameContextForStartBias())
                when {
                    civ.civID in tileMap.startingLocationsByNation -> 1 // harshest requirements
                    startBias.any { it in tileMap.naturalWonders } && !gameSetupInfo.gameParameters.noStartBias -> 2
                    startBias.contains(Constants.tundra) && !gameSetupInfo.gameParameters.noStartBias -> 3    // Tundra starts are hard to find, so let's do them first
                    startBias.isNotEmpty() && !gameSetupInfo.gameParameters.noStartBias -> 4 // less harsh
                    else -> 5  // no requirements
                }
            }.sortedByDescending { it.isHuman() } // More important for humans to get their start biases!
    }

    @Readonly
    private fun getFreeTiles(landTilesInBigEnoughGroup: Map<Tile, Float>, minimumDistanceBetweenStartingLocations: Int): MutableList<Tile> {
        return landTilesInBigEnoughGroup.asSequence()
            .filter {
                HexMath.getDistanceFromEdge(it.key.position, tileMap.mapParameters) >=
                    (minimumDistanceBetweenStartingLocations * 2) / 3
            }.sortedBy { it.value }
            .map { it.key }
            .toMutableList()
    }

    // Mutating - updates freeTiles
    private fun getStartingLocationsForCivs(
        civsOrderedByAvailableLocations: List<Civilization>,
        freeTiles: MutableList<Tile>,
        startScores: HashMap<Tile, Float>,
        minimumDistanceBetweenStartingLocations: Int,
        teamAllowedTiles: Map<Civilization, Set<Tile>> = emptyMap(),
        allowFallbackGlobal: Boolean = false
    ): HashMap<Civilization, Tile>? {
        val startingLocations = HashMap<Civilization, Tile>()
        for (civ in civsOrderedByAvailableLocations) {

            val startingLocation = getCivStartingLocation(civ, freeTiles, startScores, teamAllowedTiles[civ], allowFallbackGlobal)
            startingLocation ?: break

            startingLocations[civ] = startingLocation

            val distanceToNext = minimumDistanceBetweenStartingLocations /
                (if (civ.isCityState) 2 else 1) // We allow city states to squeeze in tighter
            @Suppress("DEPRECATION")
            freeTiles.removeAll(tileMap.getTilesInDistance(startingLocation.position, distanceToNext)
                .toSet())
        }
        return if (startingLocations.size < civsOrderedByAvailableLocations.size) null else startingLocations
    }

    private fun getCivStartingLocation(
        civ: Civilization,
        freeTiles: MutableList<Tile>,
        startScores: HashMap<Tile, Float>,
        allowedTiles: Set<Tile>? = null,
        allowFallbackGlobal: Boolean = false,
    ): Tile? {
        var startingLocation = tileMap.startingLocationsByNation[civ.civID]?.randomOrNull(rng)
        if (startingLocation == null) {
            startingLocation = tileMap.startingLocationsByNation[Constants.spectator]?.randomOrNull(rng)
            if (startingLocation != null) {
                tileMap.startingLocationsByNation[Constants.spectator]?.remove(startingLocation)
            }
        }
        // UncivGC 组队: 预设起点 (MapRegions 随机分的 region.startPosition) 不在本队半区 → 忽略 (2026-08-23)
        if (startingLocation != null && allowedTiles != null && startingLocation !in allowedTiles)
            startingLocation = null
        if (startingLocation == null && freeTiles.isNotEmpty())
            // UncivGC 组队: 候选格限制在本队半区; 半区不够时返回 null 让外层减小间距重试 (保持同队同侧),
            // 仅间距=0 允许全局兜底 (防极端地图生成失败)
            if (allowedTiles != null) {
                val candidates = freeTiles.filter { it in allowedTiles }.toMutableList()
                startingLocation = when {
                    candidates.isNotEmpty() -> getOneStartingLocation(civ, candidates, startScores)
                    allowFallbackGlobal -> getOneStartingLocation(civ, freeTiles, startScores)
                    else -> null
                }
            } else
                startingLocation = getOneStartingLocation(civ, freeTiles, startScores)
        // If startingLocation is null we failed to get all the starting tiles with this minimum distance
        return startingLocation
    }

    @Readonly
    private fun getOneStartingLocation(
        civ: Civilization,
        freeTiles: MutableList<Tile>,
        startScores: HashMap<Tile, Float>
    ): Tile {
        if (gameSetupInfo.gameParameters.noStartBias) {
            return freeTiles.random(rng)
        }
        val startBiases = civ.nation.getStartBias(ruleset, civ.getGameContextForStartBias())
        if (startBiases.any { it in tileMap.naturalWonders }) {
            // startPref wants Natural wonder neighbor: Rare and very likely to be outside getDistanceFromEdge
            val wonderNeighbor = tileMap.values.asSequence()
                .filter { it.isNaturalWonder() && it.naturalWonder!! in startBiases }
                .sortedByDescending { startScores[it] }
                .firstOrNull()
            if (wonderNeighbor != null) return wonderNeighbor
        }

        var preferredTiles = freeTiles.toList()
        for (startBias in startBiases) {
            preferredTiles = when {
                startBias.equalsPlaceholderText("Avoid []") -> {
                    val tileToAvoid = startBias.getPlaceholderParameters()[0]
                    preferredTiles.filter { tile ->
                        @Suppress("DEPRECATION")
                        !tile.getTilesInDistance(1).any {
                            it.matchesTerrainFilter(tileToAvoid, null)
                        }
                    }
                }
                startBias in tileMap.naturalWonders -> preferredTiles  // passthrough: already failed
                else -> preferredTiles.filter { tile ->
                    @Suppress("DEPRECATION")
                    tile.getTilesInDistance(1).any {
                        it.matchesTerrainFilter(startBias, null)
                    }
                }
            }
        }
        return preferredTiles.randomOrNull(rng) ?: freeTiles.random(rng)
    }
}

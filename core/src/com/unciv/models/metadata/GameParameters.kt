package com.unciv.models.metadata

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.ruleset.Speed

class GameParameters : IsPartOfGameInfoSerialization { // Default values are the default new game
    var difficulty = "Prince"
    var speed: String = Speed.DEFAULT // Not an instance of class Speed

    var randomNumberOfPlayers = false
    var minNumberOfPlayers = 3
    var maxNumberOfPlayers = 3
    var players = ArrayList<Player>().apply {
        add(Player(playerType = PlayerType.Human))
        repeat(3) { add(Player()) }
    }
    var randomNumberOfCityStates = false
    var minNumberOfCityStates = 0
    var maxNumberOfCityStates = 6
    var numberOfCityStates = 0  // UncivGC: 默认 0 城邦 (用户 2026-08-21 要求; 原版 6)

    var enableRandomNationsPool = false
    var randomNationsPool = arrayListOf<String>()

    var noCityRazing = false
    var noBarbarians = false
    var ragingBarbarians = false
    var oneCityChallenge = false
    // UncivGC: 随机数可变 (SL) — 开启后随机事件结果可因读档重试而改变; 默认关 = 确定性随机 (联机公平)
    var reRollableRandom = false
    var godMode = false
    var nuclearWeaponsEnabled = true
    var espionageEnabled = false
    /** UncivGC 帧同步: 同时回合 (实时联机, Civ6 模式) — 房间设置开关, 服务器权威 */
    var simultaneousTurns = false
    /** UncivGC 帧同步: 每段回合保底时长 (分钟) — 5 段 [0-25, 26-50, 51-75, 76-100, 100+]; 0=无限制 (该段不设倒计时, 全员完成才过回合); null=服务器默认 */
    var fsTurnTimes: Array<Float>? = null
    /** UncivGC 帧同步: 回合结算后强制停留/锁定秒数 (客户端结算锁定+提示条时长; 0=不锁定) — 2026-08-22 用户要求可设置, 默认 3; 2026-08-27 用户调整默认 3→1 */
    var fsSettleLockSeconds: Int = 1
    /** UncivGC 组队 (2026-08-23): 队伍数 (1=不组队; 2/3=组队) — 大厅设置, 开局后固定 */
    var fsTeamCount: Int = 1
    /** UncivGC 组队: 队伍分组, 按队伍索引 (队1/队2/队3), 元素是 playerId — 生成器写入存档, 服务器广播权威 */
    var fsTeams: Array<Array<String>> = arrayOf()
    var noStartBias = false
    var shufflePlayerOrder = false

    var victoryTypes: ArrayList<String> = arrayListOf()
    var startingEra = "Ancient era"

    @Deprecated("Since 4.21.0, use showCivilizationStats")
    var showVictoryStats = true
    // TODO: remove nullable after migration
    var showCivilizationStats: Boolean? = null
        get() = field ?: showVictoryStats
        set(value) {
            field = value
            if (value != null)
                showVictoryStats = value
        }
    var showDemographics = false
    var showRankings = true
    var showCharts = true
    var hideOtherCivilizationStats = false

    // Multiplayer parameters
    var isOnlineMultiplayer = false
    var multiplayerServerUrl: String? = null
    var anyoneCanSpectate = true
    /** After this amount of minutes, anyone can choose to 'skip turn' of the current player to keep the game going */
    var minutesUntilSkipTurn = 60 * 24
    /** Initial players' timer to play before they can be forced to resign permanently*/
    var minutesUntilForceResign = 3 * 24 * 60
    /** Time a player recover on their timer before they can be forced to resign. Time isn't added if the player get their turn skipped*/
    var minutesRecoveredPerTurn = 60 * 24

    var baseRuleset: String = BaseRuleset.Civ_V_GnK.fullName
    var mods = LinkedHashSet<String>()

    var maxTurns = 500

    var acceptedModCheckErrors = ""

    fun clone(): GameParameters {
        val parameters = GameParameters()
        parameters.difficulty = difficulty
        parameters.speed = speed
        parameters.randomNumberOfPlayers = randomNumberOfPlayers
        parameters.minNumberOfPlayers = minNumberOfPlayers
        parameters.maxNumberOfPlayers = maxNumberOfPlayers
        parameters.players = ArrayList(players)
        parameters.randomNumberOfCityStates = randomNumberOfCityStates
        parameters.minNumberOfCityStates = minNumberOfCityStates
        parameters.maxNumberOfCityStates = maxNumberOfCityStates
        parameters.numberOfCityStates = numberOfCityStates
        parameters.enableRandomNationsPool = enableRandomNationsPool
        parameters.randomNationsPool = ArrayList(randomNationsPool)
        parameters.noCityRazing = noCityRazing
        parameters.noBarbarians = noBarbarians
        parameters.ragingBarbarians = ragingBarbarians
        parameters.oneCityChallenge = oneCityChallenge
        // godMode intentionally reset on clone
        parameters.nuclearWeaponsEnabled = nuclearWeaponsEnabled
        parameters.espionageEnabled = espionageEnabled
        parameters.simultaneousTurns = simultaneousTurns
        parameters.fsSettleLockSeconds = fsSettleLockSeconds
        parameters.fsTeamCount = fsTeamCount
        parameters.fsTeams = fsTeams.map { it.clone() }.toTypedArray()
        parameters.noStartBias = noStartBias
        parameters.shufflePlayerOrder = shufflePlayerOrder
        parameters.victoryTypes = ArrayList(victoryTypes)
        parameters.startingEra = startingEra
        parameters.showCivilizationStats = showCivilizationStats
        parameters.showDemographics = showDemographics
        parameters.showRankings = showRankings
        parameters.showCharts = showCharts
        parameters.hideOtherCivilizationStats = hideOtherCivilizationStats
        parameters.isOnlineMultiplayer = isOnlineMultiplayer
        parameters.multiplayerServerUrl = multiplayerServerUrl
        parameters.anyoneCanSpectate = anyoneCanSpectate
        parameters.baseRuleset = baseRuleset
        parameters.mods = LinkedHashSet(mods)
        parameters.maxTurns = maxTurns
        parameters.acceptedModCheckErrors = acceptedModCheckErrors
        return parameters
    }

    // For debugging and GameStarter console output
    override fun toString() = sequence {
            yield("$difficulty $speed $startingEra")
            yield("${players.count { it.playerType == PlayerType.Human }} ${PlayerType.Human}")
            yield("${players.count { it.playerType == PlayerType.AI }} ${PlayerType.AI}")
            if (randomNumberOfPlayers) yield("Random number of Players: $minNumberOfPlayers..$maxNumberOfPlayers")
            if (randomNumberOfCityStates) yield("Random number of City-States: $minNumberOfCityStates..$maxNumberOfCityStates")
            else yield("$numberOfCityStates CS")
            if (isOnlineMultiplayer) yield("Online Multiplayer")
            if (noBarbarians) yield("No barbs")
            if (ragingBarbarians) yield("Raging barbs")
            if (oneCityChallenge) yield("OCC")
            if (simultaneousTurns) yield("Simultaneous Turns")
            if (!nuclearWeaponsEnabled) yield("No nukes")
            if (godMode) yield("God mode")
            yield("Enabled Victories: " + victoryTypes.joinToString())
            yield(baseRuleset)
            yield(if (mods.isEmpty()) "no mods" else mods.joinToString(",", "mods=(", ")", 6) )
        }.joinToString(prefix = "(", postfix = ")")

    /** Get all mods including base
     *
     *  The returned Set is ordered base first, then in the order they are stored in a save.
     *  This creates a fresh instance, and the caller is allowed to mutate it.
     */
    fun getModsAndBaseRuleset() =
        LinkedHashSet<String>(mods.size + 1).apply {
            add(baseRuleset)
            addAll(mods)
        }
}

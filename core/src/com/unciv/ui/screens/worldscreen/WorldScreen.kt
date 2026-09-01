package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.view.CivView
import com.unciv.view.GameView
import com.unciv.logic.UncivShowableException
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.event.EventBus
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.MapVisualization
import com.unciv.logic.multiplayer.MultiplayerGameUpdated
import com.unciv.logic.multiplayer.storage.FileStorageRateLimitReached
import com.unciv.logic.multiplayer.storage.MultiplayerAuthException
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.models.TutorialTrigger
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.ruleset.Event
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.centerX
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.input.KeyShortcutDispatcherVeto
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.KeyboardPanningListener
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.AuthPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.popups.hasOpenPopups
import com.unciv.ui.popups.options.OptionsPopupPages
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.cityscreen.CityScreen
import com.unciv.ui.screens.devconsole.DevConsolePopup
import com.unciv.ui.screens.mainmenuscreen.MainMenuScreen
import com.unciv.ui.screens.newgamescreen.NewGameScreen
import com.unciv.ui.screens.overviewscreen.EmpireOverviewCategories
import com.unciv.ui.screens.overviewscreen.EmpireOverviewScreen
import com.unciv.ui.screens.pickerscreens.DiplomaticVoteResultScreen
import com.unciv.ui.screens.pickerscreens.GreatPersonPickerScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.ui.screens.savescreens.QuickSave
import com.unciv.ui.screens.savescreens.SaveGameScreen
import com.unciv.ui.screens.victoryscreen.VictoryScreen
import com.unciv.ui.screens.worldscreen.bottombar.BattleTable
import com.unciv.ui.screens.worldscreen.bottombar.TileInfoTable
import com.unciv.ui.screens.worldscreen.chat.ChatButton
import com.unciv.ui.screens.worldscreen.mainmenu.WorldScreenMusicPopup
import com.unciv.ui.screens.worldscreen.minimap.MinimapHolder
import com.unciv.ui.screens.worldscreen.status.AutoPlayStatusButton
import com.unciv.ui.screens.worldscreen.status.MultiplayerStatusButton
import com.unciv.ui.screens.worldscreen.status.NextTurnButton
import com.unciv.ui.screens.worldscreen.status.UndoButton
import com.unciv.ui.screens.worldscreen.status.NextTurnProgress
import com.unciv.ui.screens.worldscreen.status.SmallUnitButton
import com.unciv.ui.screens.worldscreen.status.StatusButtons
import com.unciv.ui.screens.worldscreen.topbar.WorldScreenTopBar
import com.unciv.ui.screens.worldscreen.unit.AutoPlay
import com.unciv.ui.screens.worldscreen.unit.UnitTable
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsTable
import com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder
import com.unciv.ui.screens.worldscreen.worldmap.WorldMapTileUpdater.updateTiles
import com.unciv.utils.Concurrency
import com.unciv.utils.debug
import com.unciv.utils.launchOnGLThread
import com.unciv.utils.launchOnThreadPool
import com.unciv.utils.withGLContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import yairm210.purity.annotations.Readonly
import java.util.Timer
import kotlin.concurrent.timer

/** UncivGC 待办事件 (实验性UI): 必须立即弹出的弹窗类型 — 城市决策(占领/外交联姻)/终局(游戏结束)/宣战(用户 2026-08-31: 不然都不知道自己被打了), 不进事件队列 */
internal val immediatePopupAlertTypes = setOf(
    com.unciv.logic.civilization.AlertType.CityConquered,
    com.unciv.logic.civilization.AlertType.DiplomaticMarriage,
    com.unciv.logic.civilization.AlertType.GameHasBeenWon,
    com.unciv.logic.civilization.AlertType.WarDeclaration
)

/**
 * Do not create this screen without seriously thinking about the implications: this is the single most memory-intensive class in the application.
 * There really should ever be only one in memory at the same time, likely managed by [UncivGame].
 *
 * @param gameInfo The game state the screen should represent
 * @param viewingCiv The currently active [civilization][Civilization]
 * @param restoreState
 */
class WorldScreen(
    val gameInfo: GameInfo,
    val autoPlay: AutoPlay,
    val viewingCiv: Civilization,
    restoreState: RestoreState? = null
) : BaseScreen() {
    /** When set, causes the screen to update in the next [render][render] event */
    var shouldUpdate = false

    /** Indicates it's the player's ([viewingCiv]) turn */
    var isPlayersTurn = viewingCiv.isCurrentPlayer()
        internal set     // only this class is allowed to make changes
    
    /** Indicates that a game failed to upload, and needs to be uploaded */
    var failedUpload = false
        private set

    /** Selected civilization, used in spectator and replay mode, equals viewingCiv in ordinary games */
    var selectedCiv = viewingCiv
        internal set
    /** This is the *base view* from which all other views are derived */
    var gameView = GameView(gameInfo, selectedCiv, spectatorMode = viewingCiv.isSpectator())
        internal set

    var fogOfWar = true

    /** `true` when it's the player's turn unless he is a spectator */
    val canChangeState
        get() = isPlayersTurn && !viewingCiv.isSpectator()

    val mapHolder = WorldMapHolder(this, gameInfo.tileMap)

    internal var waitingForAutosave = false
    private val mapVisualization = MapVisualization(gameInfo, viewingCiv)

    // Floating Widgets going counter-clockwise
    internal val topBar = WorldScreenTopBar(this)
    internal val techPolicyAndDiplomacy = TechPolicyDiplomacyButtons(this)
    internal val chatButton = ChatButton(this)
    private val unitActionsTable = UnitActionsTable(this)
    /** Bottom left widget holding information about a selected unit or city */
    internal val bottomUnitTable = UnitTable(this)
    private val battleTable = BattleTable(this)
    private val zoomController = ZoomButtonPair(mapHolder)
    internal val minimapWrapper = MinimapHolder(mapHolder)
    private val bottomTileInfoTable = TileInfoTable(this)
    internal val notificationsScroll = NotificationsScroll(this)
    internal val nextTurnButton = NextTurnButton(this)
    /** UncivGC 2026-08-31 顶栏快捷工具栏: 待办/事件/周转/通知/撤回/自动/状态 右组按钮 */
    internal val autoPlayButton = com.unciv.ui.screens.worldscreen.status.AutoPlayStatusButton(this, nextTurnButton)
    internal val todoButton = com.unciv.ui.screens.worldscreen.status.TodoButton(this)
    internal val eventButton = com.unciv.ui.screens.worldscreen.status.EventButton(this)
    internal val unitButton = com.unciv.ui.screens.worldscreen.status.UnitButton(this)
    internal val notifyButton = com.unciv.ui.screens.worldscreen.status.NotifyButton(this)
    internal val undoButton = com.unciv.ui.screens.worldscreen.status.UndoButton(this)
    // ⚠️ statusButtons 必须在 quickActionBar 之前声明: 它的 init add(nextTurnButton),
    // 若在 quickActionBar 组装之后, 会把状态按钮从 rightGroup 抢走 parent,
    // 随后 statusButtons.update() 的 clear() 使其变孤儿 → 状态按钮消失 (2026-08-31 根因)
    // 2026-09-01 自检: undoButton 传入 — 非实验性UI右下角恢复原版「自动+撤回竖排一组」
    private val statusButtons = StatusButtons(nextTurnButton, undoButton)
    internal val quickActionBar = com.unciv.ui.screens.worldscreen.QuickActionBar(this).also { bar ->
        // 顺序: 撤回 自动 周转 事件 待办 通知 状态 (状态最右 — 用户 2026-08-31)
        bar.rightGroup.add(undoButton).padLeft(4f).height(60f).align(com.badlogic.gdx.utils.Align.top)
        bar.rightGroup.add(autoPlayButton).padLeft(4f).height(60f).fillY()
        bar.rightGroup.add(unitButton).padLeft(4f).height(60f).fillY()
        bar.rightGroup.add(eventButton).padLeft(4f).height(60f).fillY()
        bar.rightGroup.add(todoButton).padLeft(4f).height(60f).fillY()
        bar.rightGroup.add(notifyButton).padLeft(4f).height(60f).fillY()
        bar.rightGroup.add(nextTurnButton).padLeft(4f).height(60f).fillY()
    }
    /** UncivGC 撤回: 快照管理器 (自己回合内后台存快照, 点撤回回退) */
    val undoManager = UndoManager(this).also { it.start() }
    internal val smallUnitButton = SmallUnitButton(this, statusButtons)
    private val tutorialTaskTable = Table().apply {
        background = skinStrings.getUiBackground("WorldScreen/TutorialTaskTable", tintColor = skinStrings.skinConfig.baseColor.darken(0.5f))
    }
    private var tutorialTaskTableHash = 0

    private var nextTurnUpdateJob: Job? = null

    private val events = EventBus.EventReceiver()

    private var uiEnabled = true

    internal val undoHandler = UndoHandler(this)


    init {
        // UncivGC 帧同步: 进局强制刷新翻译 (mod 翻译/新键立即生效 — 镜像下载/重载后 translations 缓存可能旧)
        try {
            com.unciv.UncivGame.Current.translations.tryReadTranslationForCurrentLanguage()
        } catch (ignored: Exception) {
        }
        // notifications are right-aligned, they take up only as much space as necessary.
        notificationsScroll.width = stage.width / 2

        minimapWrapper.x = stage.width - minimapWrapper.width

        // This is the most memory-intensive operation we have currently, most OutOfMemory errors will occur here
        mapHolder.addTiles()
        mapHolder.reloadMaxZoom()

        // resume music (in case choices from the menu lead to instantiation of a new WorldScreen)
        UncivGame.Current.musicController.resume()

        stage.addActor(mapHolder)
        stage.scrollFocus = mapHolder
        stage.addActor(notificationsScroll)  // very low in z-order, so we're free to let it extend _below_ tile info and minimap if we want
        stage.addActor(tutorialTaskTable)    // behind topBar!
        stage.addActor(topBar)
        stage.addActor(statusButtons)
        // UncivGC 2026-08-31 快捷工具栏: 背景条先加(底层), 科技按钮组后加(上层覆盖背景条)
        stage.addActor(quickActionBar)
        stage.addActor(techPolicyAndDiplomacy)
        // UncivGC: 帧同步模式聊天按钮移到顶栏 (与 状态/暂停/概览 并列) — 2026-08-22; 原版模式照旧挂 stage
        // 帧同步也显示聊天按钮 (新版私聊弹窗, 2026-08-25 用户要求)
        stage.addActor(chatButton)

        stage.addActor(zoomController)
        zoomController.isVisible = UncivGame.Current.settings.showZoomButtons

        stage.addActor(bottomUnitTable)
        stage.addActor(unitActionsTable)
        stage.addActor(bottomTileInfoTable)
        stage.addActor(minimapWrapper)
        battleTable.width = stage.width / 3
        battleTable.x = stage.width / 3
        stage.addActor(battleTable)

        val tileToCenterOn: HexCoord =
                when {
                    viewingCiv.getCapital() != null -> viewingCiv.getCapital()!!.location.toHexCoord()
                    viewingCiv.units.getCivUnits().any() -> viewingCiv.units.getCivUnits().first().getTile().position
                    else -> HexCoord.Zero
                }

        mapHolder.isAutoScrollEnabled = Gdx.app.type == Application.ApplicationType.Desktop && game.settings.mapAutoScroll
        mapHolder.mapPanningSpeed = game.settings.mapPanningSpeed

        // Don't select unit and change selectedCiv when centering as spectator
        mapHolder.setCenterPosition(tileToCenterOn, immediately = true, selectUnit = !viewingCiv.isSpectator())

        tutorialController.allTutorialsShowedCallback = { shouldUpdate = true }

        addKeyboardListener() // for map panning by W,S,A,D
        addKeyboardPresses()  // shortcut keys like F1


        if (gameInfo.gameParameters.isOnlineMultiplayer && !gameInfo.isUpToDate)
            isPlayersTurn = false // until we're up to date, don't let the player do anything

        if (gameInfo.gameParameters.isOnlineMultiplayer) {
            val gameId = gameInfo.gameId
            events.receive(MultiplayerGameUpdated::class, { it.preview.gameId == gameId }) {
                // UncivGC 帧同步: 存档更新由 FrameSync 统一处理, 不走 preview 事件重载 (防双刷)
                if (FrameSync.isFsMode(gameInfo)) return@receive
                if (isNextTurnUpdateRunning() || game.onlineMultiplayer.hasLatestGameState(gameInfo, it.preview)) {
                    return@receive
                }
                Concurrency.run("Load latest multiplayer state") {
                    loadLatestMultiplayerState()
                }
            }
        }

        if (restoreState != null) restore(restoreState)

        // UncivGC 帧同步 (同时回合): 启动 ws 操作通道; 全员始终可操作 (回合由服务器权威推进)
        if (FrameSync.isFsMode(gameInfo)) {
            isPlayersTurn = true
            FrameSync.startIfEnabled(this)
        }

        // don't run update() directly, because the UncivGame.worldScreen should be set so that the city buttons and tile groups
        //  know what the viewing civ is.
        shouldUpdate = true
    }

    /** 从子屏 (城市/科技等) 返回时 libGDX 会重新调用 show(): 若处于暂停, 补弹全局暂停弹窗 */
    override fun show() {
        super.show()
        if (FrameSync.isFsMode(gameInfo)) {
            FrameSync.ensurePausePopup()
        }
    }

    override fun dispose() {
        FrameSync.stop()
        resizeDeferTimer?.cancel()
        undoManager.stop()
        events.stopReceiving()
        statusButtons.dispose()
        autoPlayButton.dispose()  // UncivGC 2026-08-31: 自动回合按钮移入工具栏, 单独释放
        super.dispose()
    }

    override fun getCivilopediaRuleset() = gameInfo.ruleset

    // Handle disabling and re-enabling WASD listener while Options are open
    override fun openOptionsPopup(startingPage: OptionsPopupPages, withDebug: Boolean, onClose: () -> Unit) {
        val oldListener = stage.root.listeners.filterIsInstance<KeyboardPanningListener>().firstOrNull()
        if (oldListener != null) {
            stage.removeListener(oldListener)
            oldListener.dispose()
        }
        super.openOptionsPopup(startingPage, withDebug) {
            addKeyboardListener()
            onClose()
        }
    }

    fun openEmpireOverview(category: EmpireOverviewCategories? = null, selection: String = "") {
        game.pushScreen(EmpireOverviewScreen(selectedCiv, category, selection))
    }

    fun openNewGameScreen() {
        val newGameSetupInfo = GameSetupInfo(gameInfo)
        newGameSetupInfo.mapParameters.reseed()
        val newGameScreen = NewGameScreen(newGameSetupInfo)
        game.pushScreen(newGameScreen)
    }

    fun openSaveGameScreen() {
        // See #10353 - we don't support locally saving an online multiplayer game
        if (gameInfo.gameParameters.isOnlineMultiplayer) return
        game.pushScreen(SaveGameScreen(gameInfo))
    }

    private fun addKeyboardPresses() {
        globalShortcuts.add(KeyboardBinding.DeselectOrQuit) { backButtonAndESCHandler() }

        // Space and N are assigned in NextTurnButton constructor
        // Functions that have a big button are assigned there (WorldScreenTopBar, TechPolicyDiplomacyButtons..)
        globalShortcuts.add(KeyboardBinding.Civilopedia) { openCivilopedia() }
        globalShortcuts.add(KeyboardBinding.EmpireOverviewTrades) { openEmpireOverview(EmpireOverviewCategories.Trades) }
        globalShortcuts.add(KeyboardBinding.EmpireOverviewUnits) { openEmpireOverview(EmpireOverviewCategories.Units) }
        globalShortcuts.add(KeyboardBinding.EmpireOverviewPolitics) { openEmpireOverview(EmpireOverviewCategories.Politics) }
        globalShortcuts.add(KeyboardBinding.EmpireOverviewNotifications) { openEmpireOverview(EmpireOverviewCategories.Notifications) }
        globalShortcuts.add(KeyboardBinding.VictoryScreen) { game.pushScreen(VictoryScreen(this)) }
        globalShortcuts.add(KeyboardBinding.EmpireOverviewStats) { openEmpireOverview(EmpireOverviewCategories.Stats) }
        globalShortcuts.add(KeyboardBinding.EmpireOverviewResources) { openEmpireOverview(EmpireOverviewCategories.Resources) }
        globalShortcuts.add(KeyboardBinding.QuickSave) { QuickSave.save(gameInfo, this) }
        globalShortcuts.add(KeyboardBinding.QuickLoad) { QuickSave.load(this) }
        globalShortcuts.add(KeyboardBinding.ViewCapitalCity) {
            // UncivGC 帧同步: 同时回合下 currentPlayer 不一定是自己 → 用观看文明 (否则跳到别人首都)
            val capital = viewingCiv.getCapital()
            if (capital != null && !mapHolder.setCenterPosition(capital.location.toHexCoord()))
                game.pushScreen(CityScreen(gameView.getCityView(capital)))
        }
        globalShortcuts.add(KeyboardBinding.Options) { // Game Options
            openOptionsPopup { nextTurnButton.update() }
        }
        globalShortcuts.add(KeyboardBinding.SaveGame) { openSaveGameScreen() }    //   Save
        globalShortcuts.add(KeyboardBinding.LoadGame) { game.pushScreen(LoadGameScreen()) }    //   Load
        globalShortcuts.add(KeyboardBinding.QuitGame) { game.popScreen() }    //   WorldScreen is the last screen, so this quits
        globalShortcuts.add(KeyboardBinding.NewGame) { openNewGameScreen() }
        globalShortcuts.add(KeyboardBinding.MusicPlayer) {
            WorldScreenMusicPopup(this).open(force = true)
        }
        globalShortcuts.add(Input.Keys.NUMPAD_ADD) { this.mapHolder.zoomIn() }    //   '+' Zoom
        globalShortcuts.add(Input.Keys.NUMPAD_SUBTRACT) { this.mapHolder.zoomOut() }    //   '-' Zoom
        globalShortcuts.add(KeyboardBinding.ToggleUI) { toggleUI() }
        globalShortcuts.add(KeyboardBinding.ToggleYieldDisplay) { minimapWrapper.yieldImageButton.toggle() }
        globalShortcuts.add(KeyboardBinding.ToggleWorkedTilesDisplay) { minimapWrapper.populationImageButton.toggle() }
        globalShortcuts.add(KeyboardBinding.ToggleMovementDisplay) { minimapWrapper.movementsImageButton.toggle() }
        globalShortcuts.add(KeyboardBinding.ToggleResourceDisplay) { minimapWrapper.resourceImageButton.toggle() }
        globalShortcuts.add(KeyboardBinding.ToggleImprovementDisplay) { minimapWrapper.improvementsImageButton.toggle() }

        globalShortcuts.add(KeyboardBinding.DeveloperConsole, action = ::openDeveloperConsole)
    }

    @Readonly
    fun openDeveloperConsole() {
        // No cheating unless you're by yourself, ignoring a possible spectator
        if (gameInfo.civilizations.count { it.isHuman() && !it.isSpectator() } > 1) return
        DevConsolePopup(this)
    }

    private fun toggleUI() {
        uiEnabled = !uiEnabled
        topBar.isVisible = uiEnabled
        statusButtons.isVisible = uiEnabled
        techPolicyAndDiplomacy.isVisible = uiEnabled
        tutorialTaskTable.isVisible = uiEnabled
        bottomTileInfoTable.isVisible = uiEnabled
        unitActionsTable.isVisible = uiEnabled
        notificationsScroll.isVisible = uiEnabled
        minimapWrapper.isVisible = uiEnabled
        bottomUnitTable.isVisible = uiEnabled
        if (uiEnabled) battleTable.update() else battleTable.isVisible = false
    }

    private fun addKeyboardListener() {
        stage.addListener(KeyboardPanningListener(mapHolder, allowWASD = true))
    }

    // We contain a map...
    override fun getShortcutDispatcherVetoer() = KeyShortcutDispatcherVeto.createTileGroupMapDispatcherVetoer()

    private suspend fun loadLatestMultiplayerState(): Unit = coroutineScope {
        if (game.screen != this@WorldScreen) return@coroutineScope // User already went somewhere else

        val loadingGamePopup = Popup(this@WorldScreen)
        launchOnGLThread {
            loadingGamePopup.addGoodSizedLabel("Loading latest game state...")
            loadingGamePopup.open()
        }

        try {
            debug("loadLatestMultiplayerState current game: gameId: %s, turn: %s, curCiv: %s",
                gameInfo.gameId, gameInfo.turns, gameInfo.currentPlayer)
            val latestGame = game.onlineMultiplayer.multiplayerServer.downloadGame(gameInfo.gameId)
            debug("loadLatestMultiplayerState downloaded game: gameId: %s, turn: %s, curCiv: %s",
                latestGame.gameId, latestGame.turns, latestGame.currentPlayer)
            if (viewingCiv.civID == latestGame.currentPlayer || viewingCiv.civID == Constants.spectator) {
                game.notifyTurnStarted()
            }
            launchOnGLThread {
                loadingGamePopup.close()
            }
            startNewScreenJob(latestGame, autoPlay)
        } catch (ex: Throwable) {
            launchOnGLThread {
                val (message) = LoadGameScreen.getLoadExceptionMessage(ex, "Couldn't download the latest game state!")
                loadingGamePopup.clear()
                loadingGamePopup.addGoodSizedLabel(message).colspan(2).row()
                loadingGamePopup.addButton("Retry") {
                    launchOnThreadPool("Load latest multiplayer state after error") {
                        loadLatestMultiplayerState()
                    }
                }.right()
                loadingGamePopup.addButton("Main menu") {
                    game.pushScreen(MainMenuScreen())
                }.left()
            }
        }
    }

    // This is private so that we will set the shouldUpdate to true instead.
    // That way, not only do we save a lot of unnecessary updates, we also ensure that all updates are called from the main GL thread
    // and we don't get any silly concurrency problems!
    private fun update() {

        if (uiEnabled) {
            displayTutorialsOnUpdate()

            bottomUnitTable.update()

            updateSelectedCiv()

            if (fogOfWar) minimapWrapper.update(selectedCiv)
            else minimapWrapper.update(viewingCiv)

            if (fogOfWar) bottomTileInfoTable.civView = gameView.civView
            else bottomTileInfoTable.civView = gameView.civView
            bottomTileInfoTable.updateTileTable(mapHolder.selectedTile)
            bottomTileInfoTable.x = stage.width - bottomTileInfoTable.width
            bottomTileInfoTable.y = if (game.settings.showMinimap) minimapWrapper.height + 5f else 0f

            battleTable.update()

            displayTutorialTaskOnUpdate()
        }

        mapHolder.resetArrows()
        if (UncivGame.Current.settings.showUnitMovements) {
            val allUnits = gameInfo.civilizations.asSequence().flatMap { it.units.getCivUnits() }
            val allAttacks = allUnits.map { unit -> unit.attacksSinceTurnStart.asSequence().map { attacked -> Triple(unit.civ, unit.getTile().position, attacked.toHexCoord()) } }.flatten() +
                gameInfo.civilizations.asSequence().flatMap { civInfo -> civInfo.attacksSinceTurnStart.asSequence().map { Triple(civInfo, it.source, it.target) } }
            mapHolder.updateMovementOverlay(
                allUnits.filter(mapVisualization::isUnitPastVisible),
                allUnits.filter(mapVisualization::isUnitFutureVisible),
                allAttacks.filter { (attacker, source, target) -> mapVisualization.isAttackVisible(attacker, source, target) }
                        .map { (_, source, target) -> source to target }
            )
        }

        zoomController.isVisible = UncivGame.Current.settings.showZoomButtons

        // if we use the clone, then when we update viewable tiles
        // it doesn't update the explored tiles of the civ... need to think about that harder
        // it causes a bug when we move a unit to an unexplored tile (for instance a cavalry unit which can move far)

        // UncivGC: 观战者战争迷雾按钮 — fogOfWar=true 按所选文明视野(迷雾), false 用 spectator 视角全图显示
        // (原版两个分支相同, 按钮只切 minimap 视角, 主视图永远跟随 selectedCiv → 观战者点按钮无任何变化)
        if (fogOfWar) mapHolder.updateTiles(gameView.civView)
        else mapHolder.updateTiles(CivView(viewingCiv, viewingCiv, true, gameView))

        topBar.update(selectedCiv)
        if (tutorialTaskTable.isVisible)
            tutorialTaskTable.y = topBar.getYForTutorialTask() - tutorialTaskTable.height

        if (techPolicyAndDiplomacy.update())
            displayTutorial(TutorialTrigger.OtherCivEncountered)

        if (uiEnabled) {
            // UnitActionsTable measures geometry (its own y, techPolicyAndDiplomacy and fogOfWarButton), so call update this late
            unitActionsTable.y = bottomUnitTable.height
            unitActionsTable.update(bottomUnitTable.selectedUnit)
        }

        // If the game has ended, lets stop AutoPlay
        if (autoPlay.isAutoPlaying() && !gameInfo.oneMoreTurnMode && (viewingCiv.isDefeated() || gameInfo.checkForVictory())) {
            autoPlay.stopAutoPlay()
        }

        if (!hasOpenPopups() && !autoPlay.isAutoPlaying() && isPlayersTurn) {
            when {
                viewingCiv.shouldShowDiplomaticVotingResults() ->
                    UncivGame.Current.pushScreen(DiplomaticVoteResultScreen(gameInfo.diplomaticVictoryVotesCast, viewingCiv))
                !gameInfo.oneMoreTurnMode && (viewingCiv.isDefeated() || gameInfo.checkForVictory()) &&
                    // 帧同步: 观战者不弹胜负界面 (战败重进=观战者, 弹失败界面会卡住; 2026-08-21)
                    !(com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(gameInfo) && viewingCiv.isSpectator()) -> {
                    // 帧同步: 胜利判定详细日志 — 排查"一进去就显示印尼获胜"误判 (2026-08-22)
                    if (com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(gameInfo)) {
                        try {
                            val sb = StringBuilder("胜利判定触发! viewingCiv=" + viewingCiv.civName
                                + " defeated=" + viewingCiv.isDefeated()
                                + " victoryData=" + gameInfo.victoryData
                                + " oneMoreTurnMode=" + gameInfo.oneMoreTurnMode)
                            for (c in gameInfo.civilizations) {
                                if (c.isBarbarian || c.isSpectator()) continue
                                sb.append("\n  ").append(c.civName)
                                    .append(" cities=").append(c.cities.size)
                                    .append(" units=").append(c.units.getCivUnitsSize())
                                    .append(" defeated=").append(c.isDefeated())
                                    .append(" victoryType=").append(try {
                                        c.victoryManager.getVictoryTypeAchieved()
                                    } catch (e: Exception) { "ERR" })
                            }
                            com.unciv.ui.screens.worldscreen.FrameSync.log(sb.toString())
                        } catch (e: Exception) {
                        }
                    }
                    // 帧同步: 对局结束提示只弹一次 — 否则关闭胜利屏后 update 再次检测到胜利 → 死循环
                    // (观战/成员进打完的局: 胜利屏关闭 → 回 WorldScreen → 又弹 → 困住)
                    if (com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(gameInfo)) {
                        if (!com.unciv.ui.screens.worldscreen.FrameSync.victoryShownForFsGame) {
                            com.unciv.ui.screens.worldscreen.FrameSync.victoryShownForFsGame = true
                            // 手机通知栏: 后台时告知对局结束 (2026-08-21)
                            com.unciv.ui.screens.worldscreen.FsNotifier.notify(
                                "gameOver",
                                "Game over".tr(),
                                if (viewingCiv.isDefeated()) "You have been defeated".tr() else "You have won".tr())
                            game.pushScreen(VictoryScreen(this))
                        }
                    } else {
                        game.pushScreen(VictoryScreen(this))
                    }
                }
                // 必须立即弹出的弹窗 (用户 2026-08-31): 占领城市/外交联姻(城市决策)/游戏结束(终局) — 不进队列
                viewingCiv.popupAlerts.any { it.type in immediatePopupAlertTypes } ->
                    AlertPopup(this, viewingCiv.popupAlerts.first { it.type in immediatePopupAlertTypes })
                // UncivGC 待办事件 (实验性UI): 事件不立即弹, 进队列由「事件」按钮查看 (下回合清空)
                !com.unciv.GUI.getSettings().experimentalUi && viewingCiv.greatPeople.freeGreatPeople > 0 ->
                    game.pushScreen(GreatPersonPickerScreen(this, viewingCiv))
                !com.unciv.GUI.getSettings().experimentalUi && viewingCiv.popupAlerts.any() ->
                    AlertPopup(this, viewingCiv.popupAlerts.first())
                !com.unciv.GUI.getSettings().experimentalUi && viewingCiv.tradeRequests.isNotEmpty() -> {
                    // In the meantime this became invalid, perhaps because we accepted previous trades
                    for (tradeRequest in viewingCiv.tradeRequests.toList())
                        if (!TradeEvaluation().isTradeValid(tradeRequest.trade, viewingCiv,
                                gameInfo.getCivilization(tradeRequest.requestingCiv)))
                            viewingCiv.tradeRequests.remove(tradeRequest)

                    if (viewingCiv.tradeRequests.isNotEmpty()) // if a valid one still exists
                        TradePopup(this).open()
                }
            }
        }

        updateGameplayButtons()

        val coveredNotificationsTop = stage.height - statusButtons.y
        val coveredNotificationsBottom = (bottomTileInfoTable.height + bottomTileInfoTable.y)
//                (if (game.settings.showMinimap) minimapWrapper.height else 0f)
        notificationsScroll.update(viewingCiv.notifications, coveredNotificationsTop, coveredNotificationsBottom)

        val posZoomFromRight = if (game.settings.showMinimap) minimapWrapper.width
        else bottomTileInfoTable.width
        zoomController.setPosition(stage.width - posZoomFromRight - 10f, 10f, Align.bottomRight)
    }

    private fun getCurrentTutorialTask(): Event? {
        if (!game.settings.tutorialTasksCompleted.contains("Create a trade route")) {
            if (viewingCiv.cache.citiesConnectedToCapitalToMediums.any { it.key.civ == viewingCiv })
                game.settings.addCompletedTutorialTask("Create a trade route")
        }
        val stateForConditionals = viewingCiv.state
        return gameInfo.ruleset.events.values.firstOrNull {
            it.presentation == Event.Presentation.Floating &&
                it.isAvailable(stateForConditionals)
        }
    }

    private fun displayTutorialsOnUpdate() {

        displayTutorial(TutorialTrigger.Introduction)

        displayTutorial(TutorialTrigger.EnemyCityNeedsConqueringWithMeleeUnit) {
            viewingCiv.diplomacy.values.asSequence()
                    .filter { it.diplomaticStatus == DiplomaticStatus.War }
                    .map { it.otherCiv } // we're now lazily enumerating over CivilizationInfo's we're at war with
                    .flatMap { it.cities.asSequence() } // ... all *their* cities
                    .filter { it.health == 1 } // ... those ripe for conquering
                    .flatMap { it.getCenterTile().getTilesInDistance(2) }
                    // ... all tiles around those in range of an average melee unit
                    // -> and now we look for a unit that could do the conquering because it's ours
                    //    no matter whether civilian, air or ranged, tell user he needs melee
                    .any { it.getUnits().any { unit -> unit.civ == viewingCiv } }
        }
        displayTutorial(TutorialTrigger.AfterConquering) { viewingCiv.cities.any { it.hasJustBeenConquered } }

        displayTutorial(TutorialTrigger.InjuredUnits) { gameInfo.getCurrentPlayerCivilization().units.getCivUnits().any { it.health < it.maxHealth } }

        displayTutorial(TutorialTrigger.Workers) {
            gameInfo.getCurrentPlayerCivilization().units.getCivUnits().any {
                it.cache.hasUniqueToBuildImprovements && it.isCivilian() && !it.isGreatPerson()
            }
        }
    }

    private fun displayTutorialTaskOnUpdate() {
        fun setInvisible() {
            tutorialTaskTable.isVisible = false
            tutorialTaskTable.clear()
            tutorialTaskTableHash = 0
        }
        if (!game.settings.showTutorials || viewingCiv.isDefeated()) return setInvisible()
        val tutorialTask = getCurrentTutorialTask() ?: return setInvisible()

        if (!UncivGame.Current.isTutorialTaskCollapsed) {
            val hash = tutorialTask.hashCode()  // Default implementation is OK - we see the same instance or not
            if (hash != tutorialTaskTableHash) {
                val renderEvent = RenderEvent(tutorialTask, this) {
                    shouldUpdate = true
                }
                if (!renderEvent.isValid) return setInvisible()
                tutorialTaskTable.clear()
                tutorialTaskTable.add(renderEvent).pad(10f)
                tutorialTaskTableHash = hash
            }
        } else {
            tutorialTaskTable.clear()
            tutorialTaskTable.add(ImageGetter.getImage("OtherIcons/HiddenTutorialTask").apply { setSize(30f,30f) }).pad(5f)
            tutorialTaskTableHash = 0
        }
        tutorialTaskTable.pack()
        tutorialTaskTable.centerX(stage)
        tutorialTaskTable.y = topBar.getYForTutorialTask() - tutorialTaskTable.height
        tutorialTaskTable.onClick {
            UncivGame.Current.isTutorialTaskCollapsed = !UncivGame.Current.isTutorialTaskCollapsed
            displayTutorialTaskOnUpdate()
        }
        tutorialTaskTable.isVisible = true
    }

    fun setSelectedCiv(civ: Civilization) {
        selectedCiv = civ
        gameView = GameView(gameInfo, civ, viewingCiv.isSpectator())
    }

    private fun updateSelectedCiv() {
        setSelectedCiv(when {
            bottomUnitTable.selectedUnit != null -> bottomUnitTable.selectedUnit!!.civ
            bottomUnitTable.selectedCity != null -> bottomUnitTable.selectedCity!!.owningCiv().getCiv()
            else -> viewingCiv
        })
    }

    class RestoreState(
        mapHolder: WorldMapHolder,
        val selectedCivName: String,
        val viewingCivName: String,
        val fogOfWar: Boolean
    ) {
        val zoom = mapHolder.scaleX
        val scrollX = mapHolder.scrollX
        val scrollY = mapHolder.scrollY
    }
    
    @Readonly
    fun getRestoreState(): RestoreState {
        return RestoreState(mapHolder, selectedCiv.civID, viewingCiv.civID, fogOfWar)
    }

    private fun restore(restoreState: RestoreState) {

        // This is not the case if you have a multiplayer game where you play as 2 civs
        if (viewingCiv.civID == restoreState.viewingCivName) {
            mapHolder.zoom(restoreState.zoom)
            mapHolder.scrollX = restoreState.scrollX
            mapHolder.scrollY = restoreState.scrollY
            mapHolder.updateVisualScroll()
        }

        setSelectedCiv(gameInfo.getCivilization(restoreState.selectedCivName))
        fogOfWar = restoreState.fogOfWar
    }

    fun nextTurn() {
        // UncivGC 帧同步: 结束回合 = 通知服务器 (服务器收集全员结束信号后统一推进)
        if (FrameSync.isFsMode(gameInfo)) {
            FrameSync.sendNextTurn()
            return
        }
        isPlayersTurn = false
        shouldUpdate = true
        // UncivGC 待办事件 (实验性UI): 单机过回合清空未查看事件 (帧同步由结算重载清空)
        if (com.unciv.GUI.getSettings().experimentalUi) {
            viewingCiv.popupAlerts.clear()
            viewingCiv.tradeRequests.clear()
        }
        undoManager.clear()  // UncivGC: 过回合清空撤回快照
        val progressBar = NextTurnProgress(nextTurnButton)
        progressBar.start(this)

        // on a separate thread so the user can explore their world while we're passing the turn
        nextTurnUpdateJob = Concurrency.runOnNonDaemonThreadPool("NextTurn") {
            debug("Next turn starting")
            val startTime = System.currentTimeMillis()
            val originalGameInfo = gameInfo
            val gameInfoClone = originalGameInfo.clone()
            gameInfoClone.setTransients()  // this can get expensive on large games, not the clone itself

            progressBar.increment()

            gameInfoClone.nextTurn(progressBar, true)

            if (originalGameInfo.gameParameters.isOnlineMultiplayer) {
                // outer try-catch for non-auth exceptions
                try {
                    // keep retrying if upload fails AND reauthentication succeeds
                    var retryUpload: Boolean
                    do {
                        try {
                            game.onlineMultiplayer.updateGame(gameInfoClone)
                            // upload succeeded
                            retryUpload = false
                        } catch (_: MultiplayerAuthException) {
                            // true only if authentication succeeds (the popup permits retries)
                            // false only if user closes the auth popup or the popup init crashes
                            val authResult = CompletableDeferred<Boolean>()
                            launchOnGLThread {
                                try {
                                    AuthPopup(this@WorldScreen, authResult::complete).open(true)
                                } catch (ex: Exception) {
                                    // GL thread crashed during AuthPopup init, let's wrap up
                                    authResult.complete(false)
                                    // ensure exception is passed to crash handler
                                    throw ex
                                }
                            }
                            retryUpload = authResult.await()
                        }
                    } while (retryUpload)
                } catch (ex: Exception) { // non-auth exceptions
                    when (ex) {
                        is FileStorageRateLimitReached -> {
                            val message = "Server limit reached! Please wait for [${ex.limitRemainingSeconds}] seconds"
                            launchOnGLThread {
                                val cantUploadNewGamePopup = Popup(this@WorldScreen)
                                cantUploadNewGamePopup.addGoodSizedLabel(message).row()
                                cantUploadNewGamePopup.addCloseButton()
                                cantUploadNewGamePopup.open()
                            }
                        }
                        else -> {
                            val message = "Could not upload game! Reason: [${ex.message ?: "Unknown"}]"
                            launchOnGLThread {
                                val cantUploadNewGamePopup = Popup(this@WorldScreen)
                                cantUploadNewGamePopup.addGoodSizedLabel(message).row()
                                cantUploadNewGamePopup.addButton("Copy to clipboard") {
                                    Gdx.app.clipboard.contents = ex.stackTraceToString()
                                }
                                cantUploadNewGamePopup.addCloseButton()
                                cantUploadNewGamePopup.open()
                            }
                        }
                    }

                    this@WorldScreen.failedUpload = true // Since we couldn't push the new game clone, then we need to try again
                    this@WorldScreen.shouldUpdate = true
                    return@runOnNonDaemonThreadPool
                }
            }

            if (game.gameInfo != originalGameInfo) // while this was turning we loaded another game
                return@runOnNonDaemonThreadPool

            debug("Next turn took %sms", System.currentTimeMillis() - startTime)

            // Special case: when you are the only alive human player, the game will always be up to date
            if (gameInfo.gameParameters.isOnlineMultiplayer
                    && gameInfoClone.civilizations.count { it.isAlive() && it.playerType == PlayerType.Human } == 1) {
                gameInfoClone.isUpToDate = true
            }

            progressBar.increment()

            startNewScreenJob(gameInfoClone, autoPlay)
        }
    }

    fun switchToNextUnit(resetDue: Boolean = true) {
        // Try to select something new if we already have the next pending unit selected.
        if (bottomUnitTable.selectedUnit != null && resetDue) {
            // UncivGC 帧同步: due=false 是本地“已查看”标记, 会被广播回滚 (stateJson 带 due) →
            // 记入本地集合, applyState 后重新应用, 否则“下一个单位”永远循环同一个单位
            if (FrameSync.isFsMode(gameInfo)) FrameSync.markDueSeen(bottomUnitTable.selectedUnit!!.id)
            bottomUnitTable.selectedUnit!!.due = false
        }
        val nextDueUnit = viewingCiv.units.cycleThroughDueUnits(bottomUnitTable.selectedUnit)
        if (nextDueUnit != null) {
            mapHolder.setCenterPosition(
                nextDueUnit.currentTile.position,
                immediately = false,
                selectUnit = false
            )
            bottomUnitTable.selectUnit(nextDueUnit)
        } else {
            mapHolder.removeAction(mapHolder.blinkAction)
            mapHolder.selectedTile = null
            bottomUnitTable.selectUnit()
        }
        shouldUpdate = true
    }
    
    @Readonly
    internal fun isNextTurnUpdateRunning(): Boolean {
        val job = nextTurnUpdateJob
        return job != null && job.isActive
    }

    /** UncivGC 待办事件队列 (实验性UI): 是否有排队事件 — 有则「完成回合」按钮变「事件 (n)」 */
    internal fun hasPendingQueueEvents(): Boolean = pendingQueueEventCount() > 0

    /** UncivGC 待办事件队列计数: popupAlerts 弹窗 (立即弹类型除外) + 免费伟人(1) + 贸易请求 (实验性UI外恒 0) */
    internal fun pendingQueueEventCount(): Int {
        if (!com.unciv.GUI.getSettings().experimentalUi) return 0
        var count = viewingCiv.popupAlerts.count { it.type !in immediatePopupAlertTypes }
        if (viewingCiv.greatPeople.freeGreatPeople > 0) count++
        count += viewingCiv.tradeRequests.size
        return count
    }

    private fun updateGameplayButtons() {
        nextTurnButton.update()
        // UncivGC 2026-08-31 快捷工具栏: 同步刷新右组按钮状态 + 背景条定位
        todoButton.update()
        eventButton.update()
        unitButton.update()
        notifyButton.update()
        quickActionBar.updateLayout()

        updateAutoPlayStatusButton()
        updateUndoStatusButton()
        updateMultiplayerStatusButton()

        statusButtons.update(false)
        val maxWidth = stage.width - techPolicyAndDiplomacy.width - 25f
        if(statusButtons.width > maxWidth) {
            statusButtons.update(true)
        }
        statusButtons.setPosition(stage.width - statusButtons.width - 10f, topBar.y - statusButtons.height - 10f)

        // Update chat button position to always be below techPolicyAndDiplomacy (帧同步模式在顶栏, 跳过)
        if (!FrameSync.isFsMode(gameInfo)) chatButton.updatePosition()
    }

    private fun updateAutoPlayStatusButton() {
        // UncivGC 帧同步: 隐藏 AutoPlay — 客户端本地自动化会被服务器广播回滚 (单位乱跳)
        val fsMode = com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(gameInfo)
        if (com.unciv.GUI.getSettings().experimentalUi) {
            // UncivGC 2026-08-31: 自动回合按钮移入顶栏快捷工具栏 (撤回右边)
            val shouldShow = !fsMode && game.settings.autoPlay.showAutoPlayButton
            if (!shouldShow && autoPlayButton.isVisible) autoPlay.stopAutoPlay()
            autoPlayButton.isVisible = shouldShow
            // 从非实验性UI切回时清掉右下角旧实例 (2026-09-01 自检)
            if (statusButtons.autoPlayStatusButton != null) {
                statusButtons.autoPlayStatusButton = null
                autoPlay.stopAutoPlay()
            }
        } else {
            // 非实验性UI: 保持原版右下角「自动回合」按钮 (与撤回竖排一组 — 2026-09-01 自检修复)
            autoPlayButton.isVisible = false
            if (statusButtons.autoPlayStatusButton == null) {
                if (!fsMode && game.settings.autoPlay.showAutoPlayButton)
                    statusButtons.autoPlayStatusButton = AutoPlayStatusButton(this, nextTurnButton)
            } else {
                if (!game.settings.autoPlay.showAutoPlayButton || fsMode) {
                    statusButtons.autoPlayStatusButton = null
                    autoPlay.stopAutoPlay()
                }
            }
        }
    }

    /** UncivGC 撤回按钮: 只在自己回合内创建/显示 */
    private fun updateUndoStatusButton() {
        undoButton.update()
    }

    /** 快照变化后刷新撤回按钮状态 (由 UndoManager 调用) */
    fun refreshUndoButton() {
        undoButton.update()
    }

    private fun updateMultiplayerStatusButton() {
        // UncivGC 联机大厅: 大厅局不显示多人状态按钮 (等待/回合提示由大厅自己的一套逻辑负责)
        // 2026-09-01: 大厅局识别改用 viaLobby 标志 (原按存档服务器地址, 误伤用 30126 开的原版多人局)
        val isLobbyGame = gameInfo.gameParameters.viaLobby
        val shouldShow = !isLobbyGame &&
            (gameInfo.gameParameters.isOnlineMultiplayer || game.settings.multiplayer.statusButtonInSinglePlayer)
        if (shouldShow) {
            if (statusButtons.multiplayerStatusButton != null) return
            statusButtons.multiplayerStatusButton = MultiplayerStatusButton(this,
                game.onlineMultiplayer.multiplayerFiles.getGameByGameId(gameInfo.gameId))
        } else {
            if (statusButtons.multiplayerStatusButton == null) return
            statusButtons.multiplayerStatusButton = null
        }
    }


    private var resizeDeferTimer: Timer? = null

    override fun resize(width: Int, height: Int) {
        resizeDeferTimer?.cancel()
        if (resizeDeferTimer == null && stage.viewport.screenWidth == width && stage.viewport.screenHeight == height) return
        resizeDeferTimer = timer("Resize", daemon = true, 500L, Long.MAX_VALUE) {
            resizeDeferTimer?.cancel()
            resizeDeferTimer = null
            startNewScreenJob(gameInfo, autoPlay, true) // start over
        }
    }

    override fun render(delta: Float) {
        //  This is so that updates happen in the MAIN THREAD, where there is a GL Context,
        //    otherwise images will not load properly!
        if (shouldUpdate && resizeDeferTimer == null) {
            shouldUpdate = false

            // Since updating the worldscreen can take a long time, *especially* the first time, we disable input processing to avoid ANRs
            Gdx.input.inputProcessor = null
            update()
            showTutorialsOnNextTurn()
            if (Gdx.input.inputProcessor == null) // Update may have replaced the worldscreen with a GreatPersonPickerScreen etc, so the input would already be set
                Gdx.input.inputProcessor = stage
        }

        super.render(delta)
    }


    private fun showTutorialsOnNextTurn() {
        if (!game.settings.showTutorials || autoPlay.isAutoPlaying()) return
        displayTutorial(TutorialTrigger.SlowStart)
        displayTutorial(TutorialTrigger.CityExpansion) { viewingCiv.cities.any { it.expansion.tilesClaimed() > 0 } }
        displayTutorial(TutorialTrigger.BarbarianEncountered) { viewingCiv.viewableTiles.any { it.getUnits().any { unit -> unit.civ.isBarbarian } } }
        displayTutorial(TutorialTrigger.RoadsAndRailroads) { viewingCiv.cities.size > 2 }
        displayTutorial(TutorialTrigger.Happiness) { viewingCiv.getHappiness() < 5 }
        displayTutorial(TutorialTrigger.Unhappiness) { viewingCiv.getHappiness() < 0 }
        displayTutorial(TutorialTrigger.GoldenAge) { viewingCiv.goldenAges.isGoldenAge() }
        displayTutorial(TutorialTrigger.IdleUnits) { gameInfo.turns >= 50 && game.settings.checkForDueUnits }
        displayTutorial(TutorialTrigger.ContactMe) { gameInfo.turns >= 100 }
        val resources = viewingCiv.detailedCivResources.asSequence().filter { it.origin == "All" }  // Avoid full list copy
        displayTutorial(TutorialTrigger.LuxuryResource) { resources.any { it.resource.resourceType == ResourceType.Luxury } }
        displayTutorial(TutorialTrigger.StrategicResource) { resources.any { it.resource.resourceType == ResourceType.Strategic } }
        displayTutorial(TutorialTrigger.EnemyCity) {
            viewingCiv.getKnownCivs().filter { viewingCiv.isAtWarWith(it) }
                    .flatMap { it.cities.asSequence() }.any { viewingCiv.hasExplored(it.getCenterTile()) }
        }
        displayTutorial(TutorialTrigger.Embarking) { viewingCiv.hasUnique(UniqueType.LandUnitEmbarkation) }
        displayTutorial(TutorialTrigger.NaturalWonders) { viewingCiv.naturalWonders.size > 0 }
        displayTutorial(TutorialTrigger.WeLoveTheKingDay) { viewingCiv.cities.any { it.demandedResource != "" } }
    }

    private fun backButtonAndESCHandler() {

        // Deselect Unit
        if (bottomUnitTable.selectedUnit != null) {
            bottomUnitTable.selectUnit()
            shouldUpdate = true
            return
        }

        // Deselect city
        if (bottomUnitTable.selectedCity != null) {
            bottomUnitTable.selectUnit()
            shouldUpdate = true
            return
        }

        if (bottomUnitTable.selectedSpy != null) {
            bottomUnitTable.selectSpy(null)
            shouldUpdate = true
            return
        }

        game.popScreen()
    }

    fun autoSave() {
        waitingForAutosave = true
        shouldUpdate = true
        UncivGame.Current.files.autosaves.requestAutoSave(gameInfo, true).invokeOnCompletion {
            // only enable the user to next turn once we've saved the current one
            waitingForAutosave = false
            shouldUpdate = true
        }
    }
}

/** This exists so that no reference to the current world screen remains, so the old world screen can get garbage collected during [UncivGame.loadGame]. */
private fun startNewScreenJob(gameInfo: GameInfo, autoPlay: AutoPlay, autosaveDisabled: Boolean = false) {
    Concurrency.run {
        val newWorldScreen = try {
            UncivGame.Current.loadGame(gameInfo, autoPlay)
        } catch (notAPlayer: UncivShowableException) {
            withGLContext {
                val (message) = LoadGameScreen.getLoadExceptionMessage(notAPlayer)
                val mainMenu = UncivGame.Current.goToMainMenu()
                ToastPopup(message, mainMenu)
            }
            return@run
        } catch (_: OutOfMemoryError) {
            withGLContext {
                val mainMenu = UncivGame.Current.goToMainMenu()
                ToastPopup("Not enough memory on phone to load game!", mainMenu)
            }
            return@run
        }

        val shouldAutoSave = !autosaveDisabled
                && gameInfo.turns % UncivGame.Current.settings.turnsBetweenAutosaves == 0
        if (shouldAutoSave) {
            newWorldScreen.autoSave()
        }
    }
}

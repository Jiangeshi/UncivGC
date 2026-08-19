package com.unciv.ui.screens.worldscreen.mainmenu

import com.unciv.models.translations.tr
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle
import com.unciv.logic.lobby.LobbyApi
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.onLongPress
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.lobbyscreens.LobbyRoomScreen
import com.unciv.ui.screens.lobbyscreens.LobbyScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.ui.screens.victoryscreen.VictoryScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread

/** The in-game menu called from the "Hamburger" button top-left
 *
 *  Popup automatically opens as soon as it's initialized
 */
class WorldScreenMenuPopup(
    val worldScreen: WorldScreen,
    expertMode: Boolean = false
) : Popup(worldScreen, scrollable = Scrollability.All) {
    private val singleColumn = false  // UncivGC: 游戏菜单永远两列 (官方会按屏幕自动收成一列)
    private fun <T: Actor?> Cell<T>.nextColumn() {
        if (!singleColumn && column == 0) return
        row()
    }

    init {
        worldScreen.autoPlay.stopAutoPlay()
        defaults().fillX()

        // UncivGC 联机大厅开局: 隐藏单机选项, 增加「退出房间」(AI托管)
        val isLobbyGame = worldScreen.gameInfo.gameParameters.multiplayerServerUrl == LobbyRoomScreen.SP_SERVER_URL

        val showSave = !worldScreen.gameInfo.gameParameters.isOnlineMultiplayer
        val showMusic = worldScreen.game.musicController.isMusicAvailable()
        val showConsole = showSave && expertMode
        val buttonCount = 8 - (if (isLobbyGame) 2 else 0) + (if (isLobbyGame) 1 else 0)
                + (if (isLobbyGame && LobbyRoomScreen.activeAmOwner) 2 else 0)
                + (if (showSave) 1 else 0) + (if (showMusic) 1 else 0) + (if (showConsole) 1 else 0)

        val emptyPrefHeight = this.prefHeight
        val firstCell = addButton("Main menu") {
            worldScreen.game.goToMainMenu()
        }
        firstCell.nextColumn()

        addButton("Civilopedia", KeyboardBinding.Civilopedia) {
            close()
            worldScreen.openCivilopedia()
        }.nextColumn()
        if (showSave)
            addButton("Save game", KeyboardBinding.SaveGame) {
                close()
                worldScreen.openSaveGameScreen()
            }.nextColumn()
        if (!isLobbyGame)
            addButton("Load game", KeyboardBinding.LoadGame) {
                close()
                worldScreen.game.pushScreen(LoadGameScreen())
            }.nextColumn()
        if (!isLobbyGame)
            addButton("Start new game", KeyboardBinding.NewGame) {
                close()
                worldScreen.openNewGameScreen()
            }.nextColumn()
        addButton("Victory status", KeyboardBinding.VictoryScreen) {
            close()
            worldScreen.game.pushScreen(VictoryScreen(worldScreen))
        }.nextColumn()
        val optionsCell = addButton("Options", KeyboardBinding.Options) {
            close()
            worldScreen.openOptionsPopup()
        }
        optionsCell.actor.onLongPress {
            close()
            worldScreen.openOptionsPopup(withDebug = true)
        }
        optionsCell.nextColumn()
        if (showMusic)
            addButton("Music", KeyboardBinding.MusicPlayer) {
                close()
                WorldScreenMusicPopup(worldScreen).open(force = true)
            }.nextColumn()

        if (showConsole)
            addButton("Developer Console", KeyboardBinding.DeveloperConsole) {
                close()
                worldScreen.openDeveloperConsole()
            }.nextColumn()

        if (isLobbyGame)
            addButton("Leave room".tr()) {
                close()
                confirmLeaveLobbyGame()
            }.nextColumn()

        // UncivGC 联机大厅: 跳海 (房主) — 保存本局全部设置直接开新图
        if (isLobbyGame && LobbyRoomScreen.activeAmOwner)
            addButton("Restart to new map".tr()) {
                close()
                confirmRestartLobbyGame()
            }.nextColumn()

        // UncivGC 联机大厅: 重新开始 (房主) — 重置房间, 全员自动准备, 回房间再点开始
        if (isLobbyGame && LobbyRoomScreen.activeAmOwner)
            addButton("Reset room".tr()) {
                close()
                confirmRestartToRoom()
            }.nextColumn()
        
        addButton("Exit") {
            close()
            Gdx.app.exit()
        }.apply { actor.style = BaseScreen.skin.get("negative", TextButtonStyle::class.java) }
            .nextColumn()

        addCloseButton().run { colspan(if (singleColumn || column == 1) 1 else 2) }
        pack()

        open(force = true)
    }

    /** UncivGC 联机大厅: 重新开始 = 删旧存档, 重置房间 (保留设置/文明, 全员自动准备), 全员回房间界面等房主再点开始 */
    private fun confirmRestartToRoom() {
        val roomId = LobbyRoomScreen.activeRoomId ?: return
        ConfirmPopup(worldScreen, "Reset the room (keep settings and civs), everyone auto-readies, back to the room to start again?".tr(), "Reset room".tr()) {
            Concurrency.run("LobbyRestartToRoom") {
                try {
                    val res = LobbyApi.restartRoom(roomId, LobbyRoomScreen.currentNickname(), LobbyRoomScreen.currentPlayerId())
                    if (!res.ok) throw Exception(res.msg)
                    // 重置进房标记: 房间已重置, 再次开始能正常进游戏 (否则 LobbyRoomPoll 的
                    // enteredGameForRoom != room.id 不成立 → 房主点开始后卡在房间界面)
                    LobbyRoomScreen.enteredGameForRoom = null
                    // 全员(含自己)由房间监视器自动带回房间界面
                    launchOnGLThread { ToastPopup("Room reset, waiting for everyone to return...".tr(), worldScreen) }
                } catch (e: Exception) {
                    launchOnGLThread { ToastPopup("Reset room failed: [${e.message}]".tr(), worldScreen) }
                }
            }
        }.open(force = true)
    }

    /** UncivGC 联机大厅: 跳海 = 删旧存档, 重置房间(全员自动准备), 直接开新图 */
    private fun confirmRestartLobbyGame() {
        val roomId = LobbyRoomScreen.activeRoomId ?: return
        ConfirmPopup(worldScreen, "Keep all settings and start a new map directly? The old save will be deleted.".tr(), "Restart to new map".tr()) {
            Concurrency.run("LobbyRestartGame") {
                try {
                    // 1. 重置房间: 删旧存档, 保留成员/文明/设置, 全员自动准备; 跳海要随机新图 → 重置种子
                    val res = LobbyApi.restartRoom(roomId, LobbyRoomScreen.currentNickname(), LobbyRoomScreen.currentPlayerId(), randomizeSeed = true)
                    if (!res.ok) throw Exception(res.msg)
                    // 重置进房标记 (同 confirmRestartToRoom: 防止再次进入同房间时轮询不触发进游戏)
                    LobbyRoomScreen.enteredGameForRoom = null
                    // 2. 直接开始新图 (全员已自动准备)
                    val start = LobbyApi.startGame(roomId, LobbyRoomScreen.currentNickname(), LobbyRoomScreen.currentPlayerId())
                    if (!start.ok) throw Exception(start.msg)
                    // 3. 自己的房间监视器会检测到新 gameId 自动切图
                    launchOnGLThread { ToastPopup("New map started, entering...".tr(), worldScreen) }
                } catch (e: Exception) {
                    launchOnGLThread { ToastPopup("Restart to new map failed: [${e.message}]".tr(), worldScreen) }
                }
            }
        }.open(force = true)
    }

    /** UncivGC 联机大厅: 退出房间 = 玩家时文明交给 AI 托管 + 离开房间; 观战者直接离开 */
    private fun confirmLeaveLobbyGame() {
        val roomId = LobbyRoomScreen.activeRoomId ?: return
        val myId = worldScreen.game.settings.multiplayer.getUserId()
        val myCivName = worldScreen.gameInfo.civilizations
            .firstOrNull { it.playerId == myId }?.civName
        // 观战者: 存档无匹配文明, 或匹配到 Spectator (战败转观战后) — 都不是真玩家
        val isSpectator = myCivName == null || myCivName == "Spectator"
        val isFrameSync = com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(worldScreen.gameInfo)
        val confirmText = if (isSpectator)
            "Leave the room?"
        else
            "Leave the room? Your civilization will be handed to AI and the game continues.".tr()
        ConfirmPopup(worldScreen, confirmText, "Leave room".tr()) {
            LobbyRoomScreen.leavingGame = true
            Concurrency.run("LobbyLeaveGame") {
                var leaveError: String? = null
                try {
                    // 帧同步: 不需要 resign/AI 托管 — fs_server 把退出/掉线玩家视为已结束, 不阻塞全员结算;
                    // resign 走官方多人存档流程, 帧同步存档在 fs 服务器, 可能失败 → 卡在房间退不出去
                    if (!isSpectator && !isFrameSync) {
                        val onlineMultiplayer = worldScreen.game.onlineMultiplayer
                        val preview = onlineMultiplayer.multiplayerFiles.getGameByGameId(worldScreen.gameInfo.gameId)
                        if (preview == null) {
                            throw Exception("Could not find the game record")
                        }
                        // 1. 玩家: 文明转 AI (resign + 上传存档)
                        onlineMultiplayer.resignPlayer(preview, myCivName!!, myCivName)
                    }
                    // 2. 离开大厅房间 (尽力而为, 失败不阻断返回); 观战者/战败转观战者也必须调用 —
                    //    战败玩家仍是房间成员, 不 leaveRoom → 服务器成员列表残留 → 大厅 LobbyPoll
                    //    检测到“我还在房间里”自动拉回 → 退出死循环 (卡在多人模式出不去的根因)
                    try {
                        LobbyApi.leaveRoom(roomId, LobbyRoomScreen.currentNickname(), LobbyRoomScreen.currentPlayerId())
                    } catch (e: Exception) {
                        leaveError = "Leave room failed: [${e.message}]".tr()
                    }
                } catch (e: Exception) {
                    // 任何异常都不能卡住玩家 — 记录错误并继续回大厅 (resign/leaveRoom 失败不应困住玩家)
                    leaveError = "Leave room failed: [${e.message}]".tr()
                }
                // 3. 替换掉 WorldScreen (避免背后残留观战视图), 回到联机大厅; 恢复原多人服务器
                //    帧同步: 先停连接 (WorldScreen 即将销毁, 不依赖 dispose 时机)
                try { com.unciv.ui.screens.worldscreen.FrameSync.stop() } catch (ignored: Exception) {}
                launchOnGLThread {
                    if (leaveError != null) ToastPopup(leaveError, worldScreen)
                    LobbyRoomScreen.restoreMultiplayerServer()
                    worldScreen.game.replaceCurrentScreen(LobbyScreen())
                }
            }
        }.open(force = true)
    }
}

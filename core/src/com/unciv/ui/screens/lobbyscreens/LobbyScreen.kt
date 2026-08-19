package com.unciv.ui.screens.lobbyscreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.UncivGame
import com.unciv.logic.lobby.LobbyApi
import com.unciv.logic.lobby.LobbyRoom
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread

/** 联机大厅: 房间列表 */
class LobbyScreen : PickerScreen() {

    private var closed = false
    private val roomRows = Table()
    private val nicknameLabel = "".toLabel()
    private var lastListFingerprint = ""
    private var autoJoinDone = false
    /** 自动回房/回游戏节流 (防止每 3 秒重复导航) */
    private var lastAutoRejoinMs = 0L

    private val nickname: String
        get() = UncivGame.Current.settings.lobbyNickname.ifBlank { "Player" }

    private val playerId: String
        get() = UncivGame.Current.settings.multiplayer.getUserId()

    init {
        // 回到大厅: 允许重新进入房间 (跨实例防重标志重置)
        LobbyRoomScreen.enteredGameForRoom = null
        // 大厅关闭按钮: 正常 pop 回上一屏 (主菜单); 若栈里没有主菜单 (对局结束/异常路径 replace 进来,
        // 栈深 1 时 popScreen 会弹"退出游戏"确认框 — 注意该确认框挂在 stage 上, 不是 screen,
        // 无法从 screen 层关掉 → 必须从源头避免触发 (栈深 1 直接 replace 回主菜单)
        setDefaultCloseAction()
        // 关键: onActivation 是追加语义 — setDefaultCloseAction 已注册 Tap→popScreen, 不先清除则点击时
        // 旧 popScreen 先执行 (栈深 1 弹"退出游戏"确认框!) 再执行下面新逻辑 → 弹窗残留。
        // 与 LobbyRoomScreen 的 Leave room 按钮同款处理
        com.unciv.ui.components.input.ActorAttachments.get(pickerPane.closeButton)
            .clearActivationActions(com.unciv.ui.components.input.ActivationTypes.Tap)
        pickerPane.closeButton.onActivation {
            // 栈里有主菜单 → 逐个 pop 到主菜单上方 (栈深 >1, popScreen 正常返回, 不会触发退出确认)
            // 栈里没有主菜单 (对局结束/异常路径 replace 进来, 栈深 1) → 绝不 popScreen, 直接回主菜单
            val mm = game.getScreensOfType(com.unciv.ui.screens.mainmenuscreen.MainMenuScreen::class).firstOrNull()
            if (mm == null) {
                // 清理房间残留 (当前大厅将被 replace 替换)
                try {
                    game.removeScreensOfType(com.unciv.ui.screens.lobbyscreens.LobbyRoomScreen::class)
                } catch (ignored: Exception) {
                }
                game.replaceCurrentScreen(com.unciv.ui.screens.mainmenuscreen.MainMenuScreen())
            } else {
                var s = game.popScreen()
                while (s != null && s !is com.unciv.ui.screens.mainmenuscreen.MainMenuScreen) {
                    s = game.popScreen()
                }
            }
        }
        pickerPane.closeButton.keyShortcuts.clear()
        pickerPane.closeButton.keyShortcuts.add(com.unciv.ui.components.input.KeyCharAndCode.BACK)

        // UncivGC: 进大厅时申请通知权限 (Android 13+, 每进程一次; 回合提醒用)
        if (!notificationPermissionAsked) {
            notificationPermissionAsked = true
            com.unciv.UncivGame.Current.requestNotificationPermission()
        }

        val refreshButton = "Refresh".toTextButton()
        refreshButton.onClick { refresh() }
        val nicknameButton = "Change nickname".toTextButton()
        nicknameButton.onClick {
            showNicknamePopup()
        }
        updateNicknameLabel()
        val topButtons = Table()
        topButtons.add(nicknameLabel).padRight(10f)
        topButtons.add(refreshButton).padRight(10f)
        topButtons.add(nicknameButton)
        topTable.add(topButtons).padBottom(10f).row()

        scrollPane.setScrollingDisabled(false, true)
        topTable.add(AutoScrollPane(roomRows)).fill().row()

        rightSideButton.setText("Create room".tr())
        // 自动进房检测完成前不可创建房间 (否则可能绕过唯一房间限制)
        rightSideButton.disable()
        rightSideButton.keyShortcuts.add(KeyCharAndCode.RETURN)
        rightSideButton.onActivation { showCreateRoomPopup() }

        roomRows.add("Checking if you are already in a room...".toLabel()).pad(20f).row()

        // 如果自己已经在某个房间里, 直接进入该房间/游戏 (不用先看大厅列表)
        Concurrency.run("LobbyAutoJoin") {
            try {
                val rooms = LobbyApi.listRooms()
                val myRoom = rooms.firstOrNull { room -> room.memberIds.any { it == playerId } }
                launchOnGLThread {
                    autoJoinDone = true
                    rightSideButton.enable()
                    if (!closed && myRoom != null) {
                        if (myRoom.status == "playing" && !myRoom.gameId.isNullOrEmpty()
                            && LobbyRoomScreen.enteredGameForRoom != myRoom.id) {
                            // 游戏已开始 → 直接进入游戏 (未进入过该房间才进, 防与 LobbyPoll 双触发)
                            LobbyRoomScreen.enterLobbyGame(myRoom.gameId!!, myRoom, this@LobbyScreen)
                        } else {
                            // 在房间里 → 直接进入房间
                            game.pushScreen(LobbyRoomScreen(myRoom.id, myRoom.name))
                        }
                    }
                }
            } catch (e: Exception) {
                launchOnGLThread {
                    autoJoinDone = true
                    rightSideButton.enable()
                }
            }
        }

        Concurrency.run("LobbyPoll") {
            while (!closed) {
                try {
                    val rooms = LobbyApi.listRooms()
                    // 已在房间/游戏但落在列表页 (初始 auto-join 失败/网络抖动/其他设备) → 自动拉回 (带节流, 避免反复导航)
                    val myRoom = rooms.firstOrNull { room -> room.memberIds.any { it == playerId } }
                    if (myRoom != null && game.screen == this@LobbyScreen) {
                        val now = System.currentTimeMillis()
                        if (now - lastAutoRejoinMs > 8000) {
                            lastAutoRejoinMs = now
                            launchOnGLThread {
                                if (!closed && game.screen == this@LobbyScreen) {
                                    if (myRoom.status == "playing" && !myRoom.gameId.isNullOrEmpty()
                                        && LobbyRoomScreen.enteredGameForRoom != myRoom.id) {
                                        LobbyRoomScreen.enterLobbyGame(myRoom.gameId!!, myRoom, this@LobbyScreen)
                                    } else {
                                        game.pushScreen(LobbyRoomScreen(myRoom.id, myRoom.name))
                                    }
                                }
                            }
                        }
                    } else {
                        launchOnGLThread { updateList(rooms) }
                    }
                } catch (e: Exception) {
                    // 网络失败静默, 下一轮再试
                }
                Thread.sleep(3000)
            }
        }
    }

    private fun updateNicknameLabel() {
        nicknameLabel.setText("Current nickname: [${nickname}]".tr())
    }

    private fun showNicknamePopup() {
        val current = nickname
        val popup = Popup(this)
        popup.addGoodSizedLabel("Set lobby nickname (shown to other players)".tr()).colspan(2).row()
        val textField = UncivTextField(current)
        popup.add(textField).width(stage.width / 2).row()
        val okButton = "OK".toTextButton()
        okButton.onActivation {
            val name = textField.text.trim()
            if (name.isNotEmpty()) {
                UncivGame.Current.settings.lobbyNickname = name
                updateNicknameLabel()
            }
            popup.close()
        }
        okButton.keyShortcuts.add(KeyCharAndCode.RETURN)
        popup.add(okButton)
        popup.open()
    }

    private fun showCreateRoomPopup() {
        val popup = Popup(this)
        popup.addGoodSizedLabel("Create room".tr()).colspan(2).row()
        val nameField = UncivTextField("My room".tr())
        popup.add(nameField).width(stage.width / 2).row()
        val createButton = "Create".toTextButton()
        createButton.onActivation {
            val name = nameField.text.trim().ifEmpty { "My room".tr() }
            popup.close()
            val loading = Popup(this)
            loading.addGoodSizedLabel("Creating...".tr())
            loading.open()
            Concurrency.run("LobbyCreate") {
                try {
                    val room = LobbyApi.createRoom(name, nickname, playerId, null)
                    launchOnGLThread {
                        loading.close()
                        game.pushScreen(LobbyRoomScreen(room.id, room.name))
                    }
                } catch (e: Exception) {
                    launchOnGLThread {
                        loading.reuseWith("Create failed: [${e.message}]".tr(), true)
                    }
                }
            }
        }
        createButton.keyShortcuts.add(KeyCharAndCode.RETURN)
        // 取消按钮: 不想创建可直接关弹窗 (之前只有 Create, 误点后无法反悔; BACK 键也可关)
        val cancelButton = "Cancel".toTextButton()
        cancelButton.onActivation { popup.close() }
        cancelButton.keyShortcuts.add(KeyCharAndCode.BACK)
        val buttonRow = com.badlogic.gdx.scenes.scene2d.ui.Table()
        buttonRow.add(cancelButton).padRight(10f)
        buttonRow.add(createButton)
        popup.add(buttonRow)
        popup.open()
    }

    private fun updateList(rooms: List<LobbyRoom>) {
        if (closed) return
        // 房间列表没变化就不重建 (version 含成员变化), 避免每 5 秒全量刷新卡顿
        val fp = rooms.joinToString("|") { "${it.id}:${it.version}:${it.status}:${it.playerCount}" }
        if (fp == lastListFingerprint) return
        lastListFingerprint = fp
        roomRows.clearChildren()
        if (rooms.isEmpty()) {
            roomRows.add("No rooms yet - tap 'Create room' to start one".toLabel()).pad(20f).row()
            return
        }
        for (room in rooms) {
            val block = Table()
            val row = Table()
            row.defaults().pad(5f)
            row.add(room.name.toLabel(fontSize = 22)).width(200f)
            row.add("Host: [${room.owner ?: "-"}]".toLabel()).width(140f)
            row.add("[${room.playerCount}] players".toLabel()).width(60f)
            val statusText = when (room.status) {
                "playing" -> "In game"
                "starting" -> "Generating map..."
                else -> "Waiting"
            }
            row.add(statusText.toLabel()).width(120f)
            if (room.status == "waiting") {
                val joinButton = "Join".toTextButton()
                joinButton.onClick { joinRoom(room) }
                row.add(joinButton)
            } else if (room.status == "playing") {
                val spectateButton = "Spectate".toTextButton()
                spectateButton.onClick { spectateRoom(room) }
                row.add(spectateButton)
            } else {
                row.add("".toLabel())
            }
            row.add("".toLabel()).expandX()
            block.add(row).fillX().row()
            // 规则集/模组行
            val modsText = lobbyModsText(room)
            if (modsText.isNotEmpty()) {
                block.add(modsText.toLabel(fontSize = 14)).padLeft(10f).padBottom(4f).row()
            }
            roomRows.add(block).fillX().row()
        }
    }

    /** 房间的规则集+模组显示文本 (列表摘要字段) */
    private fun lobbyModsText(room: LobbyRoom): String {
        val base = room.baseRuleset ?: return ""
        // 模组型基础规则集 (如 LM2) 已在 "Ruleset:" 里显示, mods 里再列就重复 → 过滤掉
        val mods = room.mods.filter { it != base }
        return if (mods.isEmpty()) "Ruleset: [$base]".tr()
        else "Ruleset: [$base]  Mods: [${mods.joinToString(", ")}]".tr()
    }

    private fun joinRoom(room: LobbyRoom) {
        val loading = Popup(this)
        loading.addGoodSizedLabel("Joining...".tr())
        loading.open()
        Concurrency.run("LobbyJoin") {
            try {
                val result = LobbyApi.joinRoom(room.id, nickname, playerId, null)
                launchOnGLThread {
                    loading.close()
                    if (result.ok) {
                        game.pushScreen(LobbyRoomScreen(room.id, room.name))
                    } else {
                        ToastPopup(result.msg.tr().ifEmpty { "Join failed".tr() }, this@LobbyScreen)
                    }
                }
            } catch (e: Exception) {
                launchOnGLThread {
                    loading.reuseWith("Join failed: [${e.message}]".tr(), true)
                }
            }
        }
    }

    /** 观战: 加入进行中的房间, 直接以观战者身份进入游戏 (不进成员列表) */
    private fun spectateRoom(room: LobbyRoom) {
        val loading = Popup(this)
        loading.addGoodSizedLabel("Entering spectate...".tr())
        loading.open()
        Concurrency.run("LobbySpectate") {
            try {
                val result = LobbyApi.spectateRoom(room.id, nickname, playerId)
                launchOnGLThread {
                    loading.close()
                    if (result.ok && !result.gameId.isNullOrEmpty()) {
                        enterGameAsSpectator(result.gameId!!, room)
                    } else {
                        ToastPopup(result.msg.tr().ifEmpty { "Spectate failed".tr() }, this@LobbyScreen)
                    }
                }
            } catch (e: Exception) {
                launchOnGLThread { loading.reuseWith("Spectate failed: [${e.message}]".tr(), true) }
            }
        }
    }

    private fun enterGameAsSpectator(gameId: String, room: LobbyRoom) {
        // 下载存档需要几秒 (服务器 5Mbps 带宽) — 给持续提示, 避免看起来像卡住
        val downloading = Popup(this)
        downloading.addGoodSizedLabel("Downloading save, entering spectate...".tr())
        downloading.open()
        Concurrency.run("LobbyEnterSpectator") {
            try {
                // 记录房间 ID 和原多人服务器 (观战者退出/恢复也要用)
                LobbyRoomScreen.activeRoomId = room.id
                // 房主也可能因 auto-join 失效走观战入口 — 要确认房主身份, 不能一律 false (否则跳海/重新开始按钮消失)
                LobbyRoomScreen.activeAmOwner = LobbyRoomScreen.resolveAmOwner(room)
                val settings = UncivGame.Current.settings.multiplayer
                if (settings.getServer() != LobbyRoomScreen.SP_SERVER_URL) {
                    settings.lobbyPreviousServer = settings.getServer()
                }
                settings.setServer(LobbyRoomScreen.SP_SERVER_URL)
                LobbyRoomScreen.ensureSaveServerRegistered()
                game.onlineMultiplayer.downloadGame(gameId)
                // 屏幕已切换到观战 (loadGame), popup 随旧屏幕销毁 — 这里 close 仅为兜底
                launchOnGLThread { try { downloading.close() } catch (ignored: Exception) {} }
                // 观战者也盯房间: 跳海开新局时自动跟进 (非成员, 不检查成员状态)
                LobbyRoomScreen.startGameWatcher(room.id, gameId, isMember = false)
            } catch (e: Exception) {
                launchOnGLThread {
                    try { downloading.close() } catch (ignored: Exception) {}
                    ToastPopup("Failed to enter spectate: [${e.message}]".tr(), this@LobbyScreen)
                }
            }
        }
    }

    private fun refresh() {
        Concurrency.run("LobbyRefresh") {
            try {
                val rooms = LobbyApi.listRooms()
                // 已在房间但落在列表页 (如返回键退房失败/其他设备建房) → 自动拉回房间/游戏
                val myRoom = rooms.firstOrNull { room -> room.memberIds.any { it == playerId } }
                launchOnGLThread {
                    if (!closed && myRoom != null && game.screen !is LobbyRoomScreen) {
                        if (myRoom.status == "playing" && !myRoom.gameId.isNullOrEmpty()
                            && LobbyRoomScreen.enteredGameForRoom != myRoom.id) {
                            LobbyRoomScreen.enterLobbyGame(myRoom.gameId!!, myRoom, this@LobbyScreen)
                        } else {
                            game.pushScreen(LobbyRoomScreen(myRoom.id, myRoom.name))
                        }
                        return@launchOnGLThread
                    }
                    updateList(rooms)
                }
            } catch (e: Exception) {
                launchOnGLThread { ToastPopup("Refresh failed: [${e.message}]".tr(), this@LobbyScreen) }
            }
        }
    }

    override fun dispose() {
        closed = true
        super.dispose()
    }

    companion object {
        /** 通知权限是否已申请过 (每进程一次, 避免每次进大厅都弹) */
        private var notificationPermissionAsked = false
    }
}

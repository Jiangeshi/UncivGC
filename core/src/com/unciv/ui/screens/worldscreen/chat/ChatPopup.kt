package com.unciv.ui.screens.worldscreen.chat

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.ImageButton
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.multiplayer.chat.Chat
import com.unciv.logic.multiplayer.chat.ChatStore
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.brighten
import com.unciv.ui.components.extensions.coerceLightnessAtLeast
import com.unciv.ui.components.extensions.getContrastRatio
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.ColorMarkupLabel
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.utils.Concurrency
import com.unciv.ui.screens.worldscreen.WorldScreen


private val civChatColorsMap = mapOf(
    "System" to Color.WHITE,
    "Server" to Color.LIGHT_GRAY,
)

/** Approximate Popup InnerTable tone — for nation-name contrast checks. */
private val chatPanelBackground = Color.DARK_GRAY

private const val NAME_MIN_CONTRAST = 3.0
private const val NAME_LIGHTEN = 0.18f
private const val NAME_MIN_LIGHTNESS = 0.55f

private fun isTooCloseToWhite(color: Color): Boolean =
    color.r > 0.82f && color.g > 0.82f && color.b > 0.82f

private fun fallbackColorForName(name: String): Color {
    val hue = ((name.hashCode() % 360) + 360) % 360
    return Color().fromHsv(hue.toFloat(), 0.70f, 0.95f)
}

/**
 * Readable nation color on the dark chat panel.
 * Prefer outer, then inner; skip near-white (body is white); else a stable hash hue.
 */
private fun pickNationNameColor(nation: Nation?, senderName: String): Color {
    val candidates = buildList {
        if (nation != null) {
            add(nation.getOuterColor())
            add(nation.getInnerColor())
        }
    }

    for (color in candidates) {
        if (isTooCloseToWhite(color)) continue
        if (getContrastRatio(chatPanelBackground, color) < NAME_MIN_CONTRAST) continue
        return color.brighten(NAME_LIGHTEN).apply { a = 1f }
    }
    for (color in candidates) {
        if (isTooCloseToWhite(color)) continue
        return color.coerceLightnessAtLeast(NAME_MIN_LIGHTNESS).apply { a = 1f }
    }
    return fallbackColorForName(senderName)
}

private fun resolveNation(gameInfo: GameInfo, senderCivName: String): Nation? =
    gameInfo.getCivilizationOrNull(senderCivName)?.nation
        ?: gameInfo.ruleset.nations[senderCivName]

/** One chat line: no bubble — nation-colored name + white body. */
fun createChatMessageLine(
    gameInfo: GameInfo,
    senderCivName: String,
    message: String,
    suffix: String? = null,
): Table {
    val row = Table()

    if (senderCivName in civChatColorsMap) {
        val line = Label(
            "${senderCivName.tr()}${if (suffix != null) " [${suffix.tr()}]" else ""}: ${message.tr()}",
            BaseScreen.skin
        ).apply {
            wrap = true
            setAlignment(Align.left)
            color = civChatColorsMap.getValue(senderCivName)
        }
        row.add(line).growX().left()
        return row
    }

    val nameColor = pickNationNameColor(resolveNation(gameInfo, senderCivName), senderCivName)
    val suffixPart = if (suffix != null) " ({$suffix})" else ""
    val nameMarkup = "#" + nameColor.toString().substring(0, 6)
    val line = ColorMarkupLabel(
        "«$nameMarkup»{$senderCivName}$suffixPart:«WHITE» $message",
        defaultColor = Color.WHITE
    ).apply {
        wrap = true
        setAlignment(Align.left)
    }
    row.add(line).growX().left()
    return row
}

class ChatPopup(
    val chat: Chat,
    private val worldScreen: WorldScreen,
) : Popup(screen = worldScreen, scrollable = Scrollability.None) {
    // ==================== UncivGC 帧同步新版聊天 (私聊) — 必须在 init 之前声明 (Kotlin 属性初始化顺序) ====================
    companion object {
        /** 每频道已读 seq (静态: 弹窗关闭重开不重置) — 2026-08-25 */
        val fsChannelReadSeqStatic = HashMap<String, Int>()
    }

    private var fsChannel = "world"
    private val fsChannels = LinkedHashMap<String, String>()
    private val fsUnread = HashMap<String, Int>()
    private val fsMessages = ArrayList<com.unciv.logic.lobby.LobbyChatMessage>()
    private var fsLastSeq = 0
    private var fsPolling = false
    /** playerId -> civName (消息显示文明用) — 2026-08-25 */
    private val fsMemberCivs = HashMap<String, String>()
    /** 每频道已读 seq: 切换频道时记录, 切回不重计未读 — 2026-08-25 */
    private val fsChannelReadSeq = fsChannelReadSeqStatic

    private val chatTable = Table(skin)
    private val scrollPane = ScrollPane(chatTable, skin)
    private val messageField = UncivTextField(hint = "Type something...")

    init {
        // UncivGC 帧同步: 使用新版私聊弹窗 (lobby 房间聊天, 频道列表/高亮/未读) — 2026-08-25 用户要求
        if (com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(worldScreen.gameInfo)) {
            buildFsChat()
        } else {
            buildOriginalChat()
        }
    }

    private fun buildOriginalChat() {
        ChatStore.chatPopup = this
        chatTable.defaults().growX().pad(5f).left()

        /**
         * Layout:
         * |  ChatHeader | CloseButton |
         * |  ChatTable (colSpan = 2)  |
         * | MessageField | SendButton |
         */

        val chatHeader = Table(skin)
        val chatLabel = "Chat".toLabel(fontSize = 30, alignment = Align.center)
        val chatIcon = ImageGetter.getImage("OtherIcons/Chat")

        chatHeader.add(chatIcon).size(chatLabel.height * 1.6f)
            .padRight(chatLabel.height / 3).padBottom(chatLabel.height / 4)
        chatHeader.add(chatLabel).expandX()

        add(chatHeader).left().pad(5f).expandX()
        add(
            ImageButton(ImageGetter.getImage("OtherIcons/Close").drawable)
                .onClick {
                    ChatStore.chatPopup = null
                    close()
                }
        ).size(chatLabel.height * 1.3f).right().row()

        scrollPane.setFadeScrollBars(false)
        scrollPane.setScrollingDisabled(true, false)
        add(scrollPane).colspan(2)
            .size(0.5f * worldScreen.stage.width, 0.5f * worldScreen.stage.height)
            .expand().fill().row()

        add(messageField).expandX().fillX()
        val sendButton = Button(skin)
        sendButton.add(ImageGetter.getImage("OtherIcons/Send"))
        add(sendButton).size(messageField.height * 1.2f, messageField.height).padLeft(1f).row()

        populateChat()

        sendButton.onClick { sendMessage() }

        messageField.addListener(object : InputListener() {
            override fun keyDown(event: InputEvent?, keycode: Int): Boolean {
                if (keycode == Input.Keys.ENTER || keycode == Input.Keys.NUMPAD_ENTER) {
                    sendMessage()
                }
                return true
            }
        })
    }

    private fun buildFsChat() {
        val roomId = com.unciv.ui.screens.lobbyscreens.LobbyRoomScreen.activeRoomId ?: return
        val myId = com.unciv.ui.screens.lobbyscreens.LobbyRoomScreen.currentPlayerId()
        val myNick = com.unciv.ui.screens.lobbyscreens.LobbyRoomScreen.currentNickname()

        val header = Table(skin)
        header.add("Chat".toLabel(fontSize = 30, alignment = Align.left)).left().expandX()
        header.add(
            ImageButton(ImageGetter.getImage("OtherIcons/Close").drawable).onClick { close() }
        ).size(30f, 30f).right().padLeft(8f)
        add(header).growX().top().pad(5f).row()

        val mainRow = Table()
        val channelTable = Table()
        channelTable.defaults().pad(3f)
        val channelScroll = ScrollPane(channelTable, skin)
        channelScroll.setFadeScrollBars(false)
        mainRow.add(channelScroll).width(140f).height(0.42f * worldScreen.stage.height).padRight(6f)

        val msgTable = Table()
        msgTable.defaults().growX().pad(3f).left()
        val msgScroll = ScrollPane(msgTable, skin)
        msgScroll.setFadeScrollBars(false)
        msgScroll.setScrollingDisabled(true, false)
        mainRow.add(msgScroll).width(0.36f * worldScreen.stage.width).height(0.42f * worldScreen.stage.height)
        add(mainRow).padBottom(6f).row()

        val inputRow = Table()
        val inputField = UncivTextField(hint = "Type something...")
        val sendButton = Button(skin)
        sendButton.add(ImageGetter.getImage("OtherIcons/Send"))
        inputRow.add(inputField).expandX().fillX()
        inputRow.add(sendButton).size(inputField.height * 1.2f, inputField.height).padLeft(4f)
        add(inputRow).growX().row()

        fun refreshMessages() {
            msgTable.clearChildren()
            for (m in fsMessages) {
                val visible = when {
                    fsChannel == "world" -> m.to == "world" || m.to.isEmpty()
                    fsChannel == "team" -> m.to == "team"
                    fsChannel.startsWith("player:") -> {
                        val target = fsChannel.removePrefix("player:")
                        (m.to == "player:$target" && m.playerId == myId) ||
                        (m.to == "player:$myId" && m.playerId == target)
                    }
                    else -> false
                }
                if (!visible) continue
                val civName = fsMemberCivs[m.playerId]
                    ?: worldScreen.gameInfo.civilizations.firstOrNull { it.playerId == m.playerId }?.civName
                val namePart = if (civName.isNullOrEmpty()) m.nickname else "${m.nickname}（${civName.tr()}）"
                val label = "[$namePart]: ${m.text}".toLabel(fontSize = 18)
                label.color = if (m.playerId == myId) Color.GREEN else Color.WHITE
                label.setAlignment(Align.left)
                // wrap 需要固定宽度才生效 (ScrollPane 内容默认无限宽 → 长消息不换行被截断, 2026-08-25 用户反馈)
                label.wrap = true
                val msgWidth = 0.36f * worldScreen.stage.width - 20f  // 视口宽 - 滚动条/padding 余量
                msgTable.add(label).growX().left().pad(2f).width(msgWidth).row()
            }
            msgTable.pack()
            msgScroll.layout()
            msgScroll.scrollY = msgScroll.maxY
        }

        fun refreshChannels() {
            channelTable.clearChildren()
            for ((label, key) in fsChannels) {
                val unread = fsUnread[key] ?: 0
                val text = if (unread > 0) "$label ($unread)" else label
                // 矩形列表行: Button.draw() 会用 style 的 drawable 覆盖 setBackground (libGDX 1.14)
                // → 背景/高亮必须写进 ButtonStyle (up/over/down/checked), 否则是皮肤默认圆角按钮 (2026-08-25 用户反馈)
                val selected = key == fsChannel
                val bg = com.unciv.ui.screens.basescreen.BaseScreen.skinStrings.getUiBackground(
                    "General/Border",
                    tintColor = if (selected) Color.valueOf("3a7d44") else Color.valueOf("4a4a5a"))
                val style = com.badlogic.gdx.scenes.scene2d.ui.Button.ButtonStyle()
                style.up = bg
                style.over = bg
                style.down = bg
                style.checked = bg
                val row = Button(style)
                row.add(text.toLabel().apply { color = Color.WHITE }).growX().pad(6f, 10f, 6f, 10f)
                row.onClick {
                    fsChannel = key
                    fsUnread[key] = 0
                    fsChannelReadSeq[key] = fsLastSeq  // 切到该频道 = 已读到当前 (2026-08-25)
                    refreshChannels()
                    refreshMessages()
                }
                channelTable.add(row).growX().pad(2f).row()
            }
            channelTable.pack()
        }

        fun sendText() {
            val text = inputField.text.trim()
            if (text.isEmpty()) return
            inputField.setText("")
            Concurrency.run("FsChatSend") {
                try {
                    com.unciv.logic.lobby.LobbyApi.sendChat(roomId, myNick, myId, text, fsChannel)
                } catch (e: Exception) {
                }
            }
        }
        sendButton.onClick { sendText() }
        inputField.addListener(object : InputListener() {
            override fun keyDown(event: InputEvent?, keycode: Int): Boolean {
                if (keycode == Input.Keys.ENTER || keycode == Input.Keys.NUMPAD_ENTER) sendText()
                return true
            }
        })

        refreshChannels()
        refreshMessages()

        // 轮询房间聊天 (getRoom 每 2 秒短轮询 — waitRoom 长轮询在弹窗环境可能不返回, 用户反馈收不到)
        fsPolling = true
        var channelsBuilt = false
        Concurrency.run("FsChatPoll") {
            while (fsPolling) {
                try {
                    val room = com.unciv.logic.lobby.LobbyApi.getRoom(roomId, myId)
                    if (!channelsBuilt) {
                        fsChannels.clear()
                        fsChannels["世界"] = "world"
                        val myTeam = room.members.firstOrNull { it.playerId == myId }?.team ?: 0
                        if (myTeam > 0) fsChannels["队伍"] = "team"
                        for (m in room.members) {
                            if (m.playerId == myId || m.playerId.isEmpty()) continue
                            fsChannels[m.nickname] = "player:" + m.playerId
                            m.civ?.let { fsMemberCivs[m.playerId] = it }
                        }
                        // 弹窗打开 = 已读: 按钮未读清零 (但 fsLastSeq 保持 0, 历史消息也要显示 — 2026-08-25)
                        val maxSeq = room.chat.maxOfOrNull { it.seq } ?: 0
                        com.unciv.ui.screens.worldscreen.chat.ChatButton.fsReadSeq = maxSeq
                        com.unciv.ui.screens.worldscreen.chat.ChatButton.updateFsUnread(0)
                        channelsBuilt = true
                        com.unciv.utils.Concurrency.runOnGLThread { refreshChannels() }
                    }
                    val newMsgs = room.chat.filter { it.seq > fsLastSeq }
                    if (newMsgs.isNotEmpty()) {
                        fsLastSeq = newMsgs.last().seq
                        // 弹窗开着: 消息即已读 (按钮未读清零) — 2026-08-25
                        com.unciv.ui.screens.worldscreen.chat.ChatButton.fsReadSeq = fsLastSeq
                        com.unciv.ui.screens.worldscreen.chat.ChatButton.updateFsUnread(0)
                        fsMessages.addAll(newMsgs)
                        for (m in newMsgs) {
                            val chanKey = when {
                                m.to == "world" || m.to.isEmpty() -> "world"
                                m.to == "team" -> "team"
                                m.to.startsWith("player:") -> m.to
                                else -> null
                            }
                            if (chanKey == null) continue
                            if (chanKey == fsChannel) continue
                            if (chanKey.startsWith("player:") && m.playerId != myId && m.to != "player:$myId") continue
                            // 该频道已读进度: 读过的消息不重计 (2026-08-25)
                            val readSeq = fsChannelReadSeq[chanKey] ?: 0
                            if (m.seq <= readSeq) continue
                            fsUnread[chanKey] = (fsUnread[chanKey] ?: 0) + 1
                        }
                        val totalUnread = fsUnread.values.sum()
                        com.unciv.ui.screens.worldscreen.chat.ChatButton.updateFsUnread(totalUnread)
                        com.unciv.utils.Concurrency.runOnGLThread {
                            refreshChannels()
                            refreshMessages()
                        }
                    }
                } catch (e: Exception) {
                }
                try { Thread.sleep(2000) } catch (e: InterruptedException) { break }
            }
        }
        closeListeners.add { fsPolling = false }
    }

    fun sendMessage() {
        val message = messageField.text.trim()

        val userId = UncivGame.Current.settings.multiplayer.getUserId()
        val currentPlayerCiv = worldScreen.gameInfo.currentPlayerCiv
        val civName = if (currentPlayerCiv.playerId == userId) {
            currentPlayerCiv.civID
        } else {
            // what do I do if someone is a spectator?
            worldScreen.gameInfo.civilizations.firstOrNull { civ -> civ.playerId == userId }?.civID
                ?: "Unknown"
        }

        if (message.isNotEmpty()) {
            chat.requestMessageSend(civName, message)
            messageField.setText("")
        }
    }

    fun addMessage(
        senderCivName: String,
        message: String,
        suffix: String? = null,
        scroll: Boolean = true
    ) {
        // wrap 需要固定宽度才生效: 原版聊天 scrollPane 宽 = 0.5*stage.width (2026-08-25 修复: 长消息不换行)
        val msgWidth = 0.5f * worldScreen.stage.width - 20f
        chatTable.add(
            createChatMessageLine(worldScreen.gameInfo, senderCivName, message, suffix)
        ).growX().left().width(msgWidth).row()
        if (scroll) scrollToBottom()
    }

    private fun populateChat() {
        chatTable.clearChildren()
        chat.forEachMessage { civName, message ->
            addMessage(civName, message)
        }
        ChatStore.pollGlobalMessages { civName, message ->
            addMessage(civName, message, suffix = "one time")
        }
        scrollToBottom()
    }

    private fun scrollToBottom() {
        chatTable.invalidate()
        scrollPane.layout()
        scrollPane.scrollY = 0f
        scrollPane.scrollPercentY = 1f
    }
}

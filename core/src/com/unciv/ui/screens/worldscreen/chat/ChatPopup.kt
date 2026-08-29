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
        /** 弹窗是否打开 (静态: ChatButton 轮询据此暂停 — 弹窗开着时 Popup 轮询已处理未读, 避免双轮询 2026-08-28) */
        @Volatile var fsPopupOpen = false
    }

    // 2026-08-29: 帧同步聊天弹窗改由共享组件 FsChatPanel 实现 (与房间界面同一份 UI/数据),
    // 旧字段 (fsChannel/fsChannels/fsMessages/fsLastSeq/fsUnread/fsMemberCivs/fsChannelReadSeq 等)
    // 已迁移进 FsChatPanel, 此处不再需要

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
        // 2026-08-29: 复用共享组件 FsChatPanel — 与房间界面聊天同一份 UI/数据 (用户要求"一模一样")
        // 弹窗打开 → ChatButton 轮询暂停 (避免双轮询打 lobby); 关闭时复位
        fsPopupOpen = true
        val roomId = com.unciv.ui.screens.lobbyscreens.LobbyRoomScreen.activeRoomId ?: return
        val myId = com.unciv.ui.screens.lobbyscreens.LobbyRoomScreen.currentPlayerId()
        val myNick = com.unciv.ui.screens.lobbyscreens.LobbyRoomScreen.currentNickname()

        val panel = com.unciv.ui.components.widgets.FsChatPanel(
            roomId = roomId,
            myId = myId,
            myNick = myNick,
            memberCivOf = { pid ->
                worldScreen.gameInfo.civilizations.firstOrNull { it.playerId == pid }?.civName
            },
            stageWidth = worldScreen.stage.width,
            stageHeight = worldScreen.stage.height,
        )
        add(panel).grow()
        panel.closeRequested = { close() }
        // 弹窗开着时其他频道来消息 → 游戏内 ChatButton 角标实时更新 (2026-08-29)
        panel.onUnreadChange = { total ->
            com.unciv.ui.screens.worldscreen.chat.ChatButton.updateFsUnread(total)
        }
        closeListeners.add { fsPopupOpen = false; panel.disposePolling() }
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

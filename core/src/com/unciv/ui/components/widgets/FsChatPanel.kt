package com.unciv.ui.components.widgets

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
import com.unciv.logic.lobby.LobbyApi
import com.unciv.logic.lobby.LobbyChatMessage
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.utils.Concurrency

/**
 * UncivGC 2026-08-29: 帧同步聊天弹窗共享组件 (房间界面 + 游戏内 同一份 UI/数据)
 *
 * 数据源: lobby 房间聊天 (room["chat"], 单份记录两界面共用)
 * UI: 频道列表 (世界/队伍/私聊) + 消息区 + 输入框, 与游戏内帧同步聊天完全一致
 *
 * @param roomId 房间 ID (聊天数据源)
 * @param myId 当前玩家 playerId
 * @param myNick 当前玩家昵称
 * @param memberCivOf playerId -> 文明名 映射 (房间界面用 room.members, 游戏内用 gameInfo.civilizations)
 * @param stageWidth / stageHeight 视口尺寸 (消息宽度/弹窗高度计算)
 */
class FsChatPanel(
    private val roomId: String,
    private val myId: String,
    private val myNick: String,
    private val memberCivOf: (String) -> String?,
    stageWidth: Float,
    stageHeight: Float,
) : Table() {

    companion object {
        /** 每频道已读 seq (静态: 弹窗关闭重开不重置, 重开不重计历史未读) — 2026-08-29 */
        private val fsChannelReadSeqStatic = HashMap<String, Int>()
    }

    private val skin = com.unciv.ui.screens.basescreen.BaseScreen.skin

    // ---- 频道状态 (弹窗生命周期内) ----
    private var fsChannel = "world"
    private val fsChannels = LinkedHashMap<String, String>()
    private val fsUnread = HashMap<String, Int>()
    private val fsMessages = ArrayList<LobbyChatMessage>()
    private var fsLastSeq = 0
    private var fsPolling = false
    private var fsRenderedSeq = 0
    private val fsChannelLabels = HashMap<String, Label>()
    private val fsMemberCivs = HashMap<String, String>()
    // 每频道已读 seq: 静态跨弹窗保留 (重开不重计历史未读, 同游戏内原实现) — 2026-08-29
    private val fsChannelReadSeq = fsChannelReadSeqStatic

    private val msgWidth = 0.36f * stageWidth - 20f
    private val panelWidth = 140f + 0.36f * stageWidth + 6f

    init {
        // 头部: Chat 标题 + 关闭按钮 (关闭由外部 Popup 处理, 这里只布局)
        val header = Table(skin)
        header.add("Chat".toLabel(fontSize = 30, alignment = Align.left)).left().expandX()
        header.add(
            ImageButton(ImageGetter.getImage("OtherIcons/Close").drawable).onClick { closeRequested?.invoke() }
        ).size(30f, 30f).right().padLeft(8f)
        add(header).growX().top().pad(5f).row()

        val mainRow = Table()
        val channelTable = Table()
        channelTable.defaults().pad(3f)
        val channelScroll = ScrollPane(channelTable, skin)
        channelScroll.setFadeScrollBars(false)
        mainRow.add(channelScroll).width(140f).height(0.42f * stageHeight).padRight(6f)

        val msgTable = Table()
        msgTable.defaults().growX().pad(3f).left()
        val msgScroll = ScrollPane(msgTable, skin)
        msgScroll.setFadeScrollBars(false)
        msgScroll.setScrollingDisabled(true, false)
        mainRow.add(msgScroll).width(0.36f * stageWidth).height(0.42f * stageHeight)
        add(mainRow).padBottom(6f).row()

        val inputRow = Table()
        val inputField = UncivTextField(hint = "Type something...")
        val sendButton = Button(skin)
        sendButton.add(ImageGetter.getImage("OtherIcons/Send"))
        inputRow.add(inputField).expandX().fillX()
        inputRow.add(sendButton).size(inputField.height * 1.2f, inputField.height).padLeft(4f)
        add(inputRow).growX().row()

        fun refreshMessages(appendOnly: Boolean = false) {
            if (!appendOnly) {
                msgTable.clearChildren()
                fsRenderedSeq = 0
            }
            for (m in fsMessages) {
                if (m.seq <= fsRenderedSeq) continue
                fsRenderedSeq = m.seq
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
                    ?: memberCivOf(m.playerId)
                    ?.also { fsMemberCivs[m.playerId] = it }
                val namePart = if (civName.isNullOrEmpty()) m.nickname else "${m.nickname}（${civName.tr()}）"
                val label = "[$namePart]: ${m.text}".toLabel(fontSize = 18)
                label.color = if (m.playerId == myId) Color.GREEN else Color.WHITE
                label.setAlignment(Align.left)
                label.wrap = true
                msgTable.add(label).growX().left().pad(2f).width(msgWidth).row()
            }
            msgTable.pack()
            msgScroll.layout()
            msgScroll.scrollY = msgScroll.maxY
        }

        fun rebuildChannels() {
            channelTable.clearChildren()
            fsChannelLabels.clear()
            for ((label, key) in fsChannels) {
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
                val textLabel = label.toLabel().apply { color = Color.WHITE }
                fsChannelLabels[key] = textLabel
                row.add(textLabel).growX().pad(6f, 10f, 6f, 10f)
                row.onClick {
                    fsChannel = key
                    fsUnread[key] = 0
                    fsChannelReadSeq[key] = fsLastSeq
                    rebuildChannels()
                    refreshMessages()
                }
                channelTable.add(row).growX().pad(2f).row()
            }
            channelTable.pack()
        }

        fun updateChannelUnread() {
            for ((key, textLabel) in fsChannelLabels) {
                val label = fsChannels.entries.firstOrNull { it.value == key }?.key ?: continue
                val unread = fsUnread[key] ?: 0
                textLabel.setText(if (unread > 0) "$label ($unread)" else label)
            }
        }

        fun sendText() {
            val text = inputField.text.trim()
            if (text.isEmpty()) return
            inputField.setText("")
            Concurrency.run("FsChatSend") {
                try {
                    LobbyApi.sendChat(roomId, myNick, myId, text, fsChannel)
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

        // 轮询房间聊天
        fsPolling = true
        var channelsBuilt = false
        Concurrency.run("FsChatPoll") {
            while (fsPolling) {
                try {
                    val room = LobbyApi.getRoom(roomId, myId)
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
                        val maxSeq = room.chat.maxOfOrNull { it.seq } ?: 0
                        // 弹窗打开 = 全部已读: 清频道未读 + 记录各频道已读基线 — 2026-08-29
                        fsUnread.clear()
                        fsChannelReadSeq.clear()
                        // 2026-08-30 修复: fsLastSeq=0 → 打开时显示全部历史 (重开聊天能看到之前消息,
                        // 用户反馈"退出去就没了"); 未读基线=maxSeq → 打开前的消息不计未读, 新消息才计
                        fsLastSeq = 0
                        val readBaseChans = buildList {
                            add("world")
                            add("team")
                            for (m in room.members) {
                                if (m.playerId == myId || m.playerId.isEmpty()) continue
                                add("player:" + m.playerId)
                            }
                        }
                        for (chan in readBaseChans) fsChannelReadSeq[chan] = maxSeq
                        // 2026-08-30 修复: 同步更新 ChatButton 轮询的已读进度 (弹窗读完 → 关闭后
                        // 按钮轮询不再把刚读过的消息算未读 — 用户反馈“读完退出过一秒又跳出来”)
                        com.unciv.ui.screens.worldscreen.chat.ChatButton.fsReadSeq = maxSeq
                        channelsBuilt = true
                        com.unciv.utils.Concurrency.runOnGLThread { rebuildChannels() }
                    }
                    val newMsgs = room.chat.filter { it.seq > fsLastSeq }
                    if (newMsgs.isNotEmpty()) {
                        fsLastSeq = newMsgs.last().seq
                        fsMessages.addAll(newMsgs)
                        if (fsMessages.size > 200)
                            fsMessages.subList(0, fsMessages.size - 200).clear()
                        for (m in newMsgs) {
                            if (m.playerId == myId) continue  // 自己发的消息不计未读 (2026-08-29)
                            val chanKey = when {
                                m.to == "world" || m.to.isEmpty() -> "world"
                                m.to == "team" -> "team"
                                m.to.startsWith("player:") -> m.to
                                else -> null
                            }
                            if (chanKey == null) continue
                            if (chanKey == fsChannel) continue
                            if (chanKey.startsWith("player:") && m.playerId != myId && m.to != "player:$myId") continue
                            val readSeq = fsChannelReadSeq[chanKey] ?: 0
                            if (m.seq <= readSeq) continue
                            fsUnread[chanKey] = (fsUnread[chanKey] ?: 0) + 1
                        }
                        // 2026-08-29: 未读总数回调 (房间按钮/游戏内 ChatButton 各自显示 "Chat (n)")
                        onUnreadChange?.invoke(fsUnread.values.sum())
                        com.unciv.utils.Concurrency.runOnGLThread {
                            updateChannelUnread()
                            refreshMessages(appendOnly = true)
                        }
                    }
                } catch (e: Exception) {
                }
                try { Thread.sleep(2000) } catch (e: InterruptedException) { break }
            }
        }
    }

    /** 关闭回调 (Popup 关闭 + 停轮询) */
    var closeRequested: (() -> Unit)? = null

    /** 未读总数变化回调 (弹窗开着时其他频道来消息 → 使用方按钮更新 "Chat (n)") — 2026-08-29 */
    var onUnreadChange: ((Int) -> Unit)? = null

    /** 当前已拉取到的最大消息 seq (弹窗打开时作为已读基线) — 2026-08-29 */
    fun lastMessageSeq(): Int = fsLastSeq

    /** 停止轮询 (弹窗关闭时调用) */
    fun disposePolling() {
        fsPolling = false
    }
}

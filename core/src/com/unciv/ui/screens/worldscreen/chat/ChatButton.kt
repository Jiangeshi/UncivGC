package com.unciv.ui.screens.worldscreen.chat

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.utils.Align
import com.unciv.UncivGame
import com.unciv.logic.AlternatingStateManager
import com.unciv.logic.multiplayer.chat.ChatStore
import com.unciv.logic.multiplayer.chat.ChatWebSocket
import com.unciv.logic.multiplayer.chat.Message
import com.unciv.models.translations.tr
import com.unciv.ui.components.SmallButtonStyle
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.IconTextButton
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.worldscreen.WorldScreen

private val smallButtonStyle = SmallButtonStyle()

/** 帧同步聊天未读总数 (新版聊天按钮角标) — 2026-08-25 */
private var fsUnreadCount = 0

class ChatButton(val worldScreen: WorldScreen) : IconTextButton(
    "Chat", ImageGetter.getImage("OtherIcons/Chat"), 23
) {
    private val chat = ChatStore.getChatByGameId(worldScreen.gameInfo.gameId)

    companion object {
        /** 帧同步已读进度 (弹窗读过的消息 seq; 按钮轮询按此计未读) — 2026-08-25 */
        @Volatile var fsReadSeq = 0

        /** 帧同步新版聊天未读总数 → 更新所有 ChatButton 角标 (2026-08-25) */
        fun updateFsUnread(count: Int) {
            fsUnreadCount = count
            com.unciv.UncivGame.Current.let { game ->
                val ws = game.screen as? com.unciv.ui.screens.worldscreen.WorldScreen
                ws?.chatButton?.updateBadge()
            }
            // 顶栏帧同步聊天按钮也更新 (用户说的"聊天按钮" — 2026-08-25)
            com.unciv.ui.screens.worldscreen.topbar.WorldScreenTopBar.updateFsChatUnread(count)
        }
    }

    init {
        // 帧同步: 全局聊天未读轮询 (弹窗关闭时也更新按钮 "Chat (n)") — 2026-08-25
        if (com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(worldScreen.gameInfo)) {
            val roomId = com.unciv.ui.screens.lobbyscreens.LobbyRoomScreen.activeRoomId
            val myId = com.unciv.ui.screens.lobbyscreens.LobbyRoomScreen.currentPlayerId()
            if (roomId != null) {
                com.unciv.utils.Concurrency.run("FsChatButtonPoll") {
                    while (true) {
                        // 2026-08-28: 聊天弹窗开着 → 暂停拉取 (Popup 轮询已处理未读/显示, 避免双轮询重复打 lobby)
                        if (!com.unciv.ui.screens.worldscreen.chat.ChatPopup.fsPopupOpen) {
                            try {
                                val room = com.unciv.logic.lobby.LobbyApi.getRoom(roomId, myId)
                                // 2026-08-29: 过滤自己发的消息 (自己发言不弹自己提醒)
                                val unread = room.chat.count { it.seq > fsReadSeq && it.playerId != myId &&
                                    (it.to == "world" || it.to.isEmpty() || it.to == "team" ||
                                     (it.to.startsWith("player:") && it.to == "player:$myId")) }
                                updateFsUnread(unread)
                            } catch (e: Exception) {
                            }
                        }
                        try { Thread.sleep(3000) } catch (e: InterruptedException) { break }
                    }
                }
            }
        }
    }

    private val badge = "".toTextButton(smallButtonStyle).apply {
        disable()
        label.setColor(Color.WHITE)
        label.setAlignment(Align.center)
        label.setFontScale(0.2f)
    }

    private val flash = AlternatingStateManager(
        name = "ChatButton color flash",
        onOriginalState = {
            icon?.color = fontColor
            label.color = fontColor
        }, onAlternateState = {
            icon?.color = Color.ORANGE
            label.color = Color.ORANGE
        }
    )

        private fun updateBadge() {
        val count = if (com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(worldScreen.gameInfo))
            fsUnreadCount else chat.unreadCount
        // 帧同步: 未读显示在按钮文字上 "Chat (n)" (用户要求, 与房间聊天一致 — 2026-08-25)
        // 2026-08-29: 翻译修复 — 整串 "Chat (n)" 无翻译条目, 应 "Chat".tr() + 数字拼接
        if (com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(worldScreen.gameInfo)) {
            label.setText(if (count > 0) "Chat".tr() + " ($count)" else "Chat".tr())
            badge.setText("")
            badge.isVisible = false
            return
        }
        badge.setText(if (count > 0) count.toString() else "")
        badge.height = height / 3
        badge.setPosition(
            width - badge.width / 1.5f,
            height - badge.height / 1.5f
        )

        badge.isVisible = count > 0 || (!com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(worldScreen.gameInfo) && ChatStore.hasGlobalMessage)

        if (badge.isVisible) {
            var text = chat.unreadCount.toString()
            if (ChatStore.hasGlobalMessage) {
                text += '+'
            }
            badge.setText(text)
        } else flash.stop()
    }

    fun triggerChatIndication() {
        updateBadge()
        flash.start()
    }

    init {
        width = 95f
        iconCell.pad(3f).center()
        addActor(badge)
        updateBadge()

        onClick {
            chat.unreadCount = 0
            ChatStore.hasGlobalMessage = false
            updateBadge()

            ChatPopup(chat, worldScreen).open()
        }

        refreshVisibility()
    }

    /**
     * Toggles [ChatButton] if needed and also starts or stops [ChatWebSocket] as required.
     */
    fun refreshVisibility() {
        isVisible = if (
            worldScreen.gameInfo.gameParameters.isOnlineMultiplayer &&
            UncivGame.Current.onlineMultiplayer.multiplayerServer.getFeatureSet().chatVersion > 0
        ) {
            ChatWebSocket.requestMessageSend(
                Message.Join(listOf(worldScreen.gameInfo.gameId)),
            )
            updatePosition()
            updateBadge()
            true
        } else {
            ChatWebSocket.stop()
            false
        }
    }

    fun updatePosition() = setPosition(
        worldScreen.techPolicyAndDiplomacy.x.coerceAtLeast(1f),
        worldScreen.techPolicyAndDiplomacy.y - height - 1f
    )
}

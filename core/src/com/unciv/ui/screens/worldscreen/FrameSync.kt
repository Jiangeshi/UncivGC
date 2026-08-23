package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.city.City
import com.unciv.logic.city.CityStats
import com.unciv.logic.lobby.LobbyApi
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeRequest
import com.unciv.models.translations.fillPlaceholders
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.worldscreen.bottombar.BattleTableHelpers.battleAnimationDeferred
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * UncivGC 帧同步 (同时回合实时联机) 客户端 — 服务器权威。
 *
 * 职责:
 * - 连接 fs_server 的 ws 操作通道 (每局一个连接)
 * - 玩家操作 (移动) 通过本通道发给服务器执行, 本地不直接改状态
 * - 接收服务器广播的状态快照 → 驱动本地显示 (单位位置/移动力/回合)
 *
 * 协议见 帧同步设计.md §4 与 ugc-server-dev/fs_server.py。
 */
object FrameSync {

    /** 帧同步调试日志 (用户目录 fs_debug.log — 桌面版 .app 无控制台, 必须落文件; 2026-08-22 排查"结算停留不生效"用) */
    private fun dbg(msg: String) {
        try {
            val f = java.io.File(System.getProperty("user.home"), "fs_debug.log")
            java.io.FileWriter(f, true).use { it.write(java.text.SimpleDateFormat("HH:mm:ss.SSS").format(java.util.Date()) + " " + msg + "\n") }
        } catch (ignored: Exception) {
        }
    }

    /** 供其他类写入帧同步调试日志 (WorldScreen 胜利判定等; 2026-08-22) */
    fun log(msg: String) = dbg(msg)

    /** fs_server 端口 (生产 30125; 测试服 30127; 本地联调 -Duncivgc.fsPort=30125) */
    private val FS_PORT: Int
        get() = System.getProperty("uncivgc.fsPort")?.toIntOrNull() ?: 30127
    private const val RECONNECT_BASE_MS = 2000L
    private const val RECONNECT_MAX_MS = 15000L
    /** 心跳间隔: 5s 一次 (断线检测提速 — 网络切换/服务器关闭时收不到 pong 快速判死) */
    private const val PING_INTERVAL_MS = 5_000L
    /** pong 超时: 超过 3 个心跳周期没收到 pong → 连接判死, 立即重连 */
    private const val PONG_TIMEOUT_MS = 15_000L

    private val client = HttpClient(CIO) {
        // 不启用 ktor 协议层 ping — 自研服务器不回复协议 ping (默认即 null)
        install(WebSockets)
    }

    @Volatile private var running = false
    private var job: Job? = null
    private var session: WebSocketSession? = null
    private var gameId = ""
    private var playerId = ""
    private var nickname = ""

    /** UncivGC 组队 (2026-08-23): 我的队伍成员 playerId 集合 (含自己; 服务器 state.teams 权威同步) */
    private val myTeamPlayerIds = HashSet<String>()
    /** 组队探索历史是否已一次性合并 (首次合并队友 exploredBy 到我的显示, 之后持续跟随实时视野) */
    private var teamExploredMerged = false

    /** 玩家昵称映射 (playerId -> nickname): 服务器 state 附加 nicknames 同步;
     *  供概览/政治学等界面显示 "文明名 (昵称)" */
    val playerNicknames = HashMap<String, String>()
    private var worldScreenRef: java.lang.ref.WeakReference<WorldScreen>? = null
    /** 暂停按钮 (由 WorldScreenTopBar 创建并注册 — 生命周期随顶栏, 避免重载竞态) */
    @Volatile private var fsPauseButton: TextButton? = null
    /** 暂停全局弹窗 (防止重复弹) */
    private var pauseBar: Table? = null  // 非模态暂停提示条 (2026-08-21: 不再用模态 Popup, 顶部按钮保持可点)
    /** 暂停输入拦截: 顶栏以下全屏挡点击 (只有顶栏按钮 + 提示条 Resume 可点) — 2026-08-21 用户要求 */
    private var pauseBlocker: com.badlogic.gdx.scenes.scene2d.Actor? = null
    /** 回合结算提示条 (整个 Table — 存 Label 会导致移除时只删文字、背景框残留, 2026-08-21 观战者反馈) */
    private var settlingHint: com.badlogic.gdx.scenes.scene2d.ui.Table? = null  // 回合结算提示 (2 秒)
    @Volatile private var serverSettling = false  // 服务器结算中 (turnStatus settling): 全程锁定 — 切换前停留由服务器端延迟广播实现 (2026-08-22)
    /** 暂停发起者昵称 (弹窗被盖住后返回世界屏时补弹用) */
    @Volatile private var pauseNickname: String? = null
    private var lastErrorShown = ""

    /** 最近一次服务器状态 (GL 线程外只读) */
    @Volatile private var lastTurn = -1
    @Volatile private var lastPaused = false
    @Volatile private var connected = false
    /** 观战者 (存档无我的 playerId) — 连接时告知服务器, 不参与“全员完成”判定 */
    @Volatile private var isSpectating = false

    /** 帧同步: 对局结束 (胜利) 提示已弹出标记 — 防关闭胜利屏后 update 反复弹 (死循环); start() 时重置 */
    @Volatile var victoryShownForFsGame = false

    /** 回合倒计时 (服务器 turnStatus 广播的 deadline, epoch 毫秒; 0=未知) + 已完成回合的玩家 */
    @Volatile var turnDeadline: Long = 0
    @Volatile var turnReadyPlayers: List<String> = emptyList()

    /** 当前在线玩家 (playerId 列表): fs_server turnStatus 附带; 对局内玩家状态面板用 */
    @Volatile var onlinePlayers: List<String> = emptyList()

    /** 已广播但未选择的事件弹窗 (eventName): 存档重载会触发 start() 清空 popupAlerts →
     *  重新挂起未解决的事件, 防止"时代奖励闪现一下就没了" */
    private val pendingEvents = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** 我是否已点“完成回合” (发送后本地立即置 true, 结算后 turnStatus 广播复位) */
    @Volatile var myTurnFinished = false

    /** 存档重载进行中 (防重入) */
    @Volatile private var reloading = false
    /** 已重载到的服务器回合 (saveUpdated 幂等: 只重载更新的存档) */
    @Volatile private var lastReloadedTurn = -1
    /** 本次状态广播中城市状态是否有变化 (hp/人口/地块) → 打开的城市界面需要刷新 */
    private var cityStateChanged = false
    /** 同局内已弹过相遇通知的文明 (双刷/重建不重复弹) */
    private val shownMeets = HashSet<String>()
    private var shownMeetsGameId = ""
    /** 帧同步本地“已查看”单位 (点过“下一个单位”): due=false 会被广播回滚, 本地记 id 集合在 applyState 后重新应用 */
    private val localDueSeen = HashSet<Int>()
    private var localDueSeenGameId = ""

    // ---------- 对外判定 ----------

    /** 帧同步模式: 存档开启同时回合 + 大厅多人局 */
    @yairm210.purity.annotations.Readonly
    fun isFsMode(gameInfo: GameInfo?): Boolean =
        gameInfo?.gameParameters?.simultaneousTurns == true
            && gameInfo.gameParameters.isOnlineMultiplayer

    fun isConnected() = connected

    /** 帧同步模式下拦截操作: 发 op 给服务器, 本地不执行; 返回 true=已拦截 (worldScreen 为 null 时放行本地执行) */
    fun tryInterceptOp(worldScreen: WorldScreen?, op: String, data: Map<String, Any?>): Boolean {
        if (worldScreen == null) return false
        if (!isFsMode(worldScreen.gameInfo)) return false
        sendOp(op, data)
        return true
    }

    /** 安全获取当前世界屏幕: headless (服务器模拟器 AI 自动化) 或未在世界屏幕时为 null */
    fun currentWorldScreenOrNull(): WorldScreen? = try {
        com.unciv.GUI.getWorldScreen()
    } catch (e: Exception) {
        null
    }

    // ---------- 生命周期 ----------

    /** WorldScreen init 完成后调用 (tileGroupMap 已就绪); 失败不影响游戏 */
    fun startIfEnabled(worldScreen: WorldScreen) {
        val gameInfo = worldScreen.gameInfo ?: return
        if (!isFsMode(gameInfo)) return
        try {
            start(worldScreen)
        } catch (e: Exception) {
            println("FrameSync start failed: " + e)
        }
    }

    private fun start(worldScreen: WorldScreen) {
        if (running) return
        FsNotifier.reset()  // 新对局: 通知去重按局重置 (2026-08-21)
        val gameInfo = worldScreen.gameInfo ?: return
        running = true
        connected = false
        worldScreenRef = java.lang.ref.WeakReference(worldScreen)
        val newGameId = gameInfo.gameId
        val isNewGame = gameId != newGameId  // 跨局 (跳海/新房间) 判定: 同 gameId 的 start = 回合重载
        gameId = newGameId
        playerId = UncivGameHelper.getUserId()
        nickname = UncivGameHelper.getNickname()
        // 跨局状态重置 (跳海/换房后新局: 完成回合/倒计时/重载幂等必须清零, 否则新局失效)
        lastReloadedTurn = -1
        myTurnFinished = false
        turnDeadline = 0
        turnReadyPlayers = emptyList()
        lastTurn = gameInfo.turns
        // 跨局重置: 对局结束 (胜利/失败) 提示标记 — 新局重新允许弹一次
        victoryShownForFsGame = false
        // 观战者判定: viewingCiv 是 Spectator 文明, 或存档里没有任何存活文明匹配我的 playerId
        // (玩家文明被消灭后 playerId 仍留在存档 civilizations → 不排除已败文明会被误判成该玩家,
        //  死后退出房间再观战时被拉回已死文明 (如阿兹特克))
        isSpectating = worldScreen.viewingCiv.isSpectator()
                || gameInfo.civilizations.none { it.playerId == playerId && !it.isDefeated() }
        // 重载后抑制存档残留弹窗: 文明介绍(StartIntro)等只在开局出现一次, 重载重放很烦;
        // 相遇通知(FirstContact): 普通玩家本次保留弹出, 连接建立后告知服务器删除 (弹一次);
        // 观战者 (存档里没有自己的 playerId) 直接屏蔽, 不弹
        try {
            // 相遇通知: 完全由客户端主动检测驱动, 不再保留服务器存档的通知
            // (存档通知是"双弹"来源之一; 主动检测 + shownMeets 防重 + 已认识没弹过也补弹)
            if (shownMeetsGameId != gameId) {
                shownMeets.clear()
                shownMeetsGameId = gameId
            }
            // 已查看标记必须每次加载都清空: 回合结算 reload 后服务器补推 state 的 turn 与 lastTurn 相等,
            // handleSimMessage 的 newTurn>lastTurn 清理不触发 → 残留标记把新回合 due=true 误设回 false → 闲置循环漏单位 (2026-08-21)
            localDueSeen.clear()
            localDueSeenGameId = gameId
            worldScreen.viewingCiv.popupAlerts.clear()
            // 事件弹窗 (时代奖励等): 服务器广播后挂起未选择, 存档重载会清空 → 重新挂起, 弹窗不消失
            if (pendingEvents.isNotEmpty()) {
                for (ev in pendingEvents) {
                    // 完整 value 可能带 "|unitId=N" 后缀 → 取名字段比较 (否则 unitId 事件永远判重失败 → 重复添加)
                    val evName = ev.split(com.unciv.Constants.stringSplitCharacter)[0]
                    val exists = worldScreen.viewingCiv.popupAlerts.any {
                        it.type == com.unciv.logic.civilization.AlertType.Event
                                && it.value.split(com.unciv.Constants.stringSplitCharacter)[0] == evName
                    }
                    if (!exists) {
                        worldScreen.viewingCiv.popupAlerts.add(com.unciv.logic.civilization.PopupAlert(
                            com.unciv.logic.civilization.AlertType.Event, ev))
                    }
                }
            }
        } catch (e: Exception) {
        }
        updateStatusLabel()
        connectLoop()
    }

    /** WorldScreen dispose 时调用 */
    fun stop() {
        running = false
        connected = false
        try {
            job?.cancel(FrameSyncStopException())
        } catch (ignored: Exception) {
        }
        job = null
        session = null
        pauseNickname = null
        // 重置暂停/结算锁定 — 否则断线重连后 lastPaused/serverSettling 卡旧值,
        // 所有 sendOp 被静默吞掉 → “操作没反应” (2026-08-21 用户反馈; 服务器重连补推会重新同步状态)
        lastPaused = false
        serverSettling = false
        Concurrency.runOnGLThread {
            // fsPauseButton 不置 null — 重载时新顶栏会重新注册覆盖; 置 null 会和注册产生竞态 (按钮文本不更新)
            hidePauseBar()
            try {
                settlingHint?.remove()
            } catch (ignored: Exception) {
            }
            settlingHint = null
        }
        worldScreenRef = null
    }

    private class FrameSyncStopException : CancellationException("FrameSync stop requested")

    // ---------- 操作发送 ----------

    /** 服务器存档已更新 (回合结算后): 下载重载, 城市/科技/经济等全状态对齐.
     *  重载会重建 WorldScreen → 旧连接自动停, 新 WorldScreen init 自动重连. */
    private fun reloadGame(turn: Int = -1) {
        if (reloading || !running) return
        // 幂等: 已重载过该回合 (或更新的) 则跳过 — 防延迟/重复广播导致刷新两遍
        if (turn >= 0 && turn <= lastReloadedTurn) return
        reloading = true
        val oldWorldScreen = worldScreenRef?.get()
        Concurrency.run("FrameSyncReload") {
            try {
                // 先停旧连接/UI — 否则 loadGame 后新 WorldScreen init 的 start 被 running=true 挡住
                // (statusLabel/暂停按钮不重建, 旧连接残留) — 新 start 会重建全部
                stop()
                // 不走 onlineMultiplayer.downloadGame: 它会更新 preview 触发 MultiplayerGameUpdated
                // 事件 → WorldScreen 处理器再触发一次重载 (双刷根因)。直接下载+loadGame。
                val gi = com.unciv.UncivGame.Current.onlineMultiplayer.multiplayerServer.downloadGame(gameId)
                com.unciv.UncivGame.Current.loadGame(gi)
                if (turn >= 0) lastReloadedTurn = turn  // 成功才记录
            } catch (e: Exception) {
                println("FrameSync reload failed: " + e)
                // 下载/重载失败: 旧屏幕可能还在 → 恢复连接 (否则连接/UI 全丢)
                if (oldWorldScreen != null && !running) startIfEnabled(oldWorldScreen)
            } finally {
                reloading = false
            }
        }
    }

    /** 发送单位操作 (如 unit.move); 断线时提示 (限频 5s), 避免操作静默丢失 */
    private var lastDisconnectToastAt = 0L
    /** 完成回合后锁定的 op (结算已入账, 再改城市配置/科技/政策 → 显示与入账不符);
     *  单位操作 (move/attack 等) 保留 — NextUnit 例外 (完成回合后仍可操作闲置单位) */
    private val TURN_LOCKED_OPS = setOf(
        "city.setProduction", "city.addToQueue", "city.addToQueueWithTile",
        "city.removeFromQueue", "city.removeFromQueueByName", "city.moveQueueEntry",
        "city.raisePriority", "city.lowerPriority", "city.disableConstruction",
        "city.workTile", "city.stopWorkTile", "city.lockTile", "city.unlockTile",
        "city.assignSpecialist", "city.reassignPopulation", "city.setFocus",
        "city.enableManualSpecialists", "city.disableManualSpecialists", "city.toggleAvoidGrowth",
        "city.annex", "city.setRazing", "city.buyTile", "city.sellBuilding", "city.rename",
        "city.setUnitSavedPromotion", "city.saveUnitPromotions", "civ.setConstructionDisabled",
        "civ.chooseTech", "civ.choosePolicy", "civ.chooseBeliefs", "civ.eventChoice"
    )

    fun sendOp(op: String, data: Map<String, Any?>) {
        // 暂停期间: 游戏内操作全部静默无效 (不报错; 顶栏按钮仍可点) — 2026-08-21 用户要求
        if (lastPaused) {
            return
        }
        // 回合结算中 (服务器 settling=true): 全程锁定 — 切换前停留由服务器延迟广播实现 (2026-08-22)
        if (serverSettling) {
            dbg("sendOp 被结算锁定吞掉: op=$op")
            return
        }
        // 完成回合后 (myTurnFinished) 锁定城市配置/科技/政策/信仰类 op —
        // 结算已按旧配置入账, 再改 → 服务器状态变但本回合产出已入账 → 显示与入账不符;
        // 单位操作 (move/attack 等) 保留 — 完成回合后仍可操作闲置单位 (NextUnit 例外)
        if (myTurnFinished && op in TURN_LOCKED_OPS) {
            showToast("Turn finished - city changes apply next turn".tr())
            return
        }
        if (!connected) {
            val now = System.currentTimeMillis()
            if (now - lastDisconnectToastAt > 5000) {
                lastDisconnectToastAt = now
                worldScreenRef?.get()?.let { ws ->
                    try {
                        ToastPopup("Connection lost - action not sent".tr(), ws)
                    } catch (ignored: Exception) {
                    }
                }
            }
            return
        }
        sendJson(buildJson {
            put("type", "op")
            put("playerId", playerId)
            put("op", op)
            put("data", data)
        })
    }

    // ---- 外交 ----
    fun sendDeclareWar(otherCivId: String) {
        sendOp("civ.declareWar", mapOf("otherCivId" to otherCivId))
    }

    fun sendMakePeace(otherCivId: String) {
        sendOp("civ.makePeace", mapOf("otherCivId" to otherCivId))
    }

    // ---- 贸易 ----
    fun sendTradeOffer(toCivId: String, trade: Trade) {
        sendOp("trade.offer", mapOf(
            "to" to toCivId,
            "ourOffers" to trade.ourOffers.map {
                mapOf("name" to it.name, "type" to it.type.name, "amount" to it.amount)
            },
            "theirOffers" to trade.theirOffers.map {
                mapOf("name" to it.name, "type" to it.type.name, "amount" to it.amount)
            }
        ))
    }

    fun sendTradeRetract(toCivId: String) {
        sendOp("trade.retract", mapOf("to" to toCivId))
    }

    fun sendTradeAccept(requestingCivId: String) {
        sendOp("trade.accept", mapOf("requestingCiv" to requestingCivId))
    }

    fun sendTradeReject(requestingCivId: String) {
        sendOp("trade.reject", mapOf("requestingCiv" to requestingCivId))
    }

    fun sendDenounce(otherCivId: String) {
        sendOp("civ.denounce", mapOf("otherCivId" to otherCivId))
    }

    fun sendFriendshipOffer(toCivId: String) {
        sendOp("civ.friendshipOffer", mapOf("to" to toCivId))
    }

    fun sendFriendshipAccept(requestingCivId: String) {
        sendOp("civ.friendshipAccept", mapOf("requestingCiv" to requestingCivId))
    }

    fun sendFriendshipDecline(requestingCivId: String) {
        sendOp("civ.friendshipDecline", mapOf("requestingCiv" to requestingCivId))
    }

    fun sendDemand(toCivId: String, demandName: String) {
        sendOp("civ.demand", mapOf("to" to toCivId, "demand" to demandName))
    }

    fun sendDemandAccept(requestingCivId: String, demandName: String) {
        sendOp("civ.demandAccept", mapOf("requestingCiv" to requestingCivId, "demand" to demandName))
    }

    fun sendDemandRefuse(requestingCivId: String, demandName: String) {
        sendOp("civ.demandRefuse", mapOf("requestingCiv" to requestingCivId, "demand" to demandName))
    }

    fun sendBuyTile(cityId: String, tileX: Int, tileY: Int) {
        sendOp("city.buyTile", mapOf("cityId" to cityId, "tileX" to tileX, "tileY" to tileY))
    }

    fun sendSellBuilding(cityId: String, buildingName: String) {
        sendOp("city.sellBuilding", mapOf("cityId" to cityId, "building" to buildingName))
    }

    fun sendTribute(cityStateName: String, worker: Boolean) {
        sendOp("civ.tribute", mapOf("cityState" to cityStateName, "worker" to worker))
    }

    fun sendUnitRename(unitId: Int, name: String) {
        sendOp("unit.rename", mapOf("unitId" to unitId, "name" to name))
    }

    fun sendDiplomaticVote(chosenCiv: String?) {
        // 弃权用空串 (null 值在 buildJson 里可能有序列化差异)
        sendOp("civ.diplomaticVote", mapOf("chosenCiv" to (chosenCiv ?: "")))
    }

    fun sendVoteResultSeen() {
        sendOp("civ.voteResultSeen", emptyMap())
    }

    fun sendCityConquerChoice(cityId: String, action: String) {
        sendOp("civ.cityConquerChoice", mapOf("cityId" to cityId, "action" to action))
    }

    fun sendCityMoveEntryToEnd(cityId: String, index: Int) {
        sendOp("city.moveEntryToEnd", mapOf("cityId" to cityId, "index" to index))
    }

    fun sendCityAddToQueue(cityId: String, item: String, toTop: Boolean) {
        sendOp("city.addToQueue", mapOf("cityId" to cityId, "item" to item, "toTop" to toTop))
    }

    fun sendCityRemoveFromQueueByName(cityId: String, name: String) {
        sendOp("city.removeFromQueueByName", mapOf("cityId" to cityId, "name" to name))
    }

    fun sendCityDisableConstruction(cityId: String, name: String, disable: Boolean) {
        sendOp("city.disableConstruction", mapOf("cityId" to cityId, "name" to name, "disable" to disable))
    }

    fun sendCivSetConstructionDisabled(name: String, disable: Boolean) {
        sendOp("civ.setConstructionDisabled", mapOf("name" to name, "disable" to disable))
    }

    fun sendCitySetUnitSavedPromotion(cityId: String, baseUnit: String, value: Boolean) {
        sendOp("city.setUnitSavedPromotion", mapOf("cityId" to cityId, "baseUnit" to baseUnit, "value" to value))
    }

    fun sendCitySaveUnitPromotions(cityId: String, baseUnit: String, promotions: Collection<String>) {
        sendOp("city.saveUnitPromotions", mapOf(
            "cityId" to cityId, "baseUnit" to baseUnit, "promotions" to ArrayList(promotions)))
    }

    fun sendCityAssignSpecialist(cityId: String, specialist: String, delta: Int) {
        sendOp("city.assignSpecialist", mapOf("cityId" to cityId, "specialist" to specialist, "delta" to delta))
    }

    fun sendCityDisableManualSpecialists(cityId: String) {
        sendOp("city.enableManualSpecialists", mapOf("cityId" to cityId, "manual" to false))
    }

    /**
     * 通用单位 action 拦截 (伟人加速/商队/维修/宗教等未单独拦截的 action)。
     * 已单独拦截的类型 (move/attack/fortify/sleep 等) 不在列表, 返回 false 走本地 lambda (lambda 内自行拦截)。
     */
    fun tryInterceptGenericAction(worldScreen: WorldScreen?, unit: MapUnit, action: com.unciv.models.UnitAction): Boolean {
        val gameInfo = worldScreen?.gameInfo ?: return false
        if (!isFsMode(gameInfo)) return false
        val op = when (action.type) {
            com.unciv.models.UnitActionType.HurryResearch -> "unit.hurryResearch"
            com.unciv.models.UnitActionType.HurryPolicy -> "unit.hurryPolicy"
            com.unciv.models.UnitActionType.HurryWonder -> "unit.hurryWonder"
            com.unciv.models.UnitActionType.HurryBuilding -> "unit.hurryBuilding"
            com.unciv.models.UnitActionType.ConductTradeMission -> "unit.tradeMission"
            com.unciv.models.UnitActionType.SetUp -> "unit.setUp"
            com.unciv.models.UnitActionType.Guard -> "unit.guard"
            com.unciv.models.UnitActionType.Repair -> "unit.repair"
            com.unciv.models.UnitActionType.FoundReligion -> "unit.foundReligion"
            com.unciv.models.UnitActionType.EnhanceReligion -> "unit.enhanceReligion"
            com.unciv.models.UnitActionType.SpreadReligion -> "unit.spreadReligion"
            com.unciv.models.UnitActionType.CreateImprovement -> "unit.createImprovement"
            com.unciv.models.UnitActionType.Transform -> "unit.transform"
            else -> return false
        }
        val payload = mapOf("unitId" to unit.id)
        if (op == "unit.createImprovement") {
            // 大先知/伟人瞬间建改良 (圣地/工厂等): 本地不执行, 服务器权威
            val tile = unit.getTile()
            val uniques = unit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.ConstructImprovementInstantly)
            val imp = uniques.firstNotNullOfOrNull { u ->
                tile?.ruleset?.tileImprovements?.values?.firstOrNull {
                    it.matchesFilter(u.params[0], com.unciv.models.ruleset.unique.GameContext(
                        civInfo = unit.civ, unit = unit, tile = tile))
                }
            } ?: return false
            sendOp(op, mapOf(
                "unitId" to unit.id,
                "tileX" to (tile?.position?.x ?: -9999),
                "tileY" to (tile?.position?.y ?: -9999),
                "improvement" to imp.name))
            return true
        }
        if (op == "unit.transform") {
            // 单位转化 (波斯不死军互转/军事单位转伟人): 本地不执行, 服务器权威
            val tile = unit.getTile()
            val stateForConditionals = unit.cache.state
            val target = unit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.CanTransform, stateForConditionals)
                .firstNotNullOfOrNull { u ->
                    val t = unit.civ.getEquivalentUnit(u.params[0])
                    if (t != null && t.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.OnlyAvailable,
                            com.unciv.models.ruleset.unique.GameContext.IgnoreConditionals)
                            .none { !it.conditionalsApply(stateForConditionals) }
                        && t.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.Unavailable, stateForConditionals).none())
                        t else null
                } ?: return false
            sendOp(op, mapOf("unitId" to unit.id, "target" to target.name))
            return true
        }
        sendOp(op, mapOf("unitId" to unit.id))
        return true
    }

    /** 结束回合: 服务器收集所有在线玩家的结束信号后才推进 (掉线视为已结束).
     *  本地立即置 myTurnFinished → 按钮变“等待剩余玩家” (不等广播, 点击即反馈). */
    fun sendNextTurn() {
        sendJson("""{"type":"nextTurn"}""")
        myTurnFinished = true
        worldScreenRef?.get()?.let { ws ->
            try {
                ws.nextTurnButton.update()
            } catch (ignored: Exception) {
            }
        }
    }

    /** 取消完成回合: 服务器从 ended 集合移除, 按钮恢复可操作 (2026-08-22 用户反馈"跳过回合无法取消") */
    fun sendUncompleteTurn() {
        sendJson("""{"type":"uncompleteTurn"}""")
        myTurnFinished = false
        worldScreenRef?.get()?.let { ws ->
            try {
                ws.nextTurnButton.update()
            } catch (ignored: Exception) {
            }
        }
    }

    fun sendPause() {
        sendJson("""{"type":"pause"}""")
    }

    fun sendResume() {
        sendJson("""{"type":"resume"}""")
    }

    /** 发送串行化: 连续操作 (移动→攻击) 必须按序到达服务器, 否则可能乱序执行 */
    private val sendMutex = kotlinx.coroutines.sync.Mutex()

    /** 上次收到 pong 的时间 (心跳判死用); 0 = 尚未收到过 */
    @Volatile private var lastPongAt = 0L

    /** 每回合贸易申请计数缓存 (key = 发起civID|目标civID → 次数; 服务器 stateJson tradeSent 同步; 驱动贸易按钮灰显) */
    private val tradeSentCache = HashMap<String, Int>()

    /** 查询本回合已向 [targetCivId] 发出的贸易申请次数 (TradeTable 灰显用) */
    fun tradeSentCount(civId: String, targetCivId: String): Int =
        tradeSentCache[civId + "|" + targetCivId] ?: 0

    /** 断线提示限频 (统一入口: sendJson 失败 / 心跳判死 / 重连失败都走这里) */
    private var lastDisconnectNoticeAt = 0L

    private fun notifyDisconnected() {
        val now = System.currentTimeMillis()
        if (now - lastDisconnectNoticeAt < 5000) return
        lastDisconnectNoticeAt = now
        showToast("Connection lost - reconnecting...".tr())
        // 手机通知栏: 后台时告知掉线 (对局在继续, 回合倒计时不等人) — 同局只发一次
        FsNotifier.notify("connLost", "Connection lost".tr(), "Connection lost - reconnecting...".tr())
    }

    private fun sendJson(json: String) {
        val s = session
        if (s == null || !connected) {
            // 回合结算重载期间 (reloadGame: stop → 下载存档 → loadGame) 不提示断线 —
            // 连接是主动停的, 新 WorldScreen 会自动重连; 玩家点击只丢一次, 结算后自然恢复
            if (!reloading) notifyDisconnected()
            updateStatusLabel()
            return
        }
        Concurrency.run("FrameSyncSend") {
            try {
                sendMutex.withLock {
                    s.send(json)
                }
            } catch (e: Exception) {
                // 发送失败 = 连接已死 (网络切换/服务器关闭): 不再静默丢弃 —
                // 标记断线 + 关闭会话 (触发 receive 退出 → connectLoop 立即重连) + 提示
                connected = false
                try {
                    GlobalScope.launch { s.close() }
                } catch (ignored: Exception) {
                }
                notifyDisconnected()
            }
        }
    }

    // ---------- 连接 ----------

    private fun fsUrl(): String {
        val base = LobbyApi.SERVER_URL
        val host = base.substringAfter("://").substringBefore(':')
        val spec = if (isSpectating) "&spectator=true" else ""
        // v=2: 声明支持状态广播 gzip 压缩 (fs_server 按连接能力分发, 旧客户端不受影响)
        return "ws://$host:$FS_PORT/ws?gameId=$gameId&playerId=$playerId&nickname=${java.net.URLEncoder.encode(nickname, "UTF-8")}$spec&v=2"
    }

    private fun connectLoop() {
        job?.cancel()
        job = Concurrency.run("FrameSync") {
            var attempt = 0
            while (running) {
                try {
                    connectOnce()
                    if (!running) break
                    // 连接正常结束 (服务器关闭等) → 重连
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    if (!running) break
                    println("FrameSync connect error: " + e.message)
                }
                connected = false
                updateStatusLabel()
                if (!running) break
                // 断线立即重连 (第一次不等待), 失败后按退避递增 — 网络切换/抖动快速恢复
                val backoff = if (attempt == 0) 0L
                else (RECONNECT_BASE_MS shl attempt.coerceAtMost(3)).coerceAtMost(RECONNECT_MAX_MS)
                attempt++
                if (backoff > 0) {
                    notifyDisconnected()
                    try {
                        Thread.sleep(backoff)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    private suspend fun connectOnce() {
        session?.close()
        val ws = client.webSocketSession { url(fsUrl()) }
        session = ws
        connected = true
        lastErrorShown = ""
        lastPongAt = System.currentTimeMillis()
        updateStatusLabel()
        // 连接后立即向模拟器要一次全量状态 (服务器 join 已自动发, 这里兜底)
        // 心跳: 5s 一次; 超过 15s (3 周期) 无 pong → 连接判死, 主动断开触发重连
        val pingJob = GlobalScope.launch {
            while (running && ws.isActive) {
                delay(PING_INTERVAL_MS)
                try {
                    ws.send("""{"type":"ping"}""")
                } catch (ignored: Exception) {
                }
                if (lastPongAt != 0L && System.currentTimeMillis() - lastPongAt > PONG_TIMEOUT_MS) {
                    try {
                        ws.close()
                    } catch (ignored: Exception) {
                    }
                    break
                }
            }
        }
        try {
            while (running && ws.isActive) {
                val frame = ws.incoming.receive()
                if (frame is Frame.Text) {
                    handleMessage(frame.readText())
                } else if (frame is Frame.Binary) {
                    // 状态广播 gzip 压缩 (v=2): 解压后与原文一致, 走同一处理路径 — 只改传输编码, 不改数据语义
                    try {
                        val text = java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(frame.readBytes()))
                            .bufferedReader(java.nio.charset.StandardCharsets.UTF_8).use { it.readText() }
                        handleMessage(text)
                    } catch (ignored: Exception) {
                        // 解压失败: 忽略本条, 下一条广播/回合重载兜底
                    }
                } else if (frame is Frame.Close) {
                    break
                }
            }
        } finally {
            pingJob.cancel()
            if (session === ws) {
                connected = false
                session = null
            }
            try {
                ws.close()
            } catch (ignored: Exception) {
            }
        }
    }

    // ---------- 消息处理 ----------

    private fun handleMessage(text: String) {
        val msg = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return
        }
        val type = msg["type"]?.jsonPrimitive?.contentOrNull ?: "sim"
        when (type) {
            "sim" -> handleSimMessage(msg)
            "battle" -> handleBattleMessage(msg)
            "campCleared" -> handleCampCleared(msg)
            "saveUpdated" -> reloadGame(msg["turn"]?.jsonPrimitive?.intOrNull ?: -1)
            "tradeRequest" -> handleTradeRequest(msg)
            "tradeRetracted" -> handleTradeRetracted(msg)
            "tradeAccepted" -> handleTradeAccepted(msg)
            "friendshipOffer" -> handleFriendshipOffer(msg)
            "demandOffer" -> handleDemandOffer(msg)
            "denounced" -> handleDenounced(msg)
            "declaredWar" -> handleDeclaredWar(msg)
            "wonderLost" -> handleWonderLost(msg)
            "ruinReward" -> handleRuinReward(msg)
            "notification" -> handleNotification(msg)
            "eventPopup" -> handleEventPopup(msg)
            "cityConquered" -> handleCityConquered(msg)
            "turnStatus" -> handleTurnStatus(msg)
            "pauseNotice" -> handlePauseNotice(msg)
            "resumeNotice" -> handleResumeNotice(msg)
            "pong" -> lastPongAt = System.currentTimeMillis()
            "closed" -> {
                val reason = msg["reason"]?.jsonPrimitive?.contentOrNull ?: ""
                // 对局已死 (模拟器退出): 停止重连并自动回大厅 — 继续重连只会无限失败
                FsNotifier.notify("closed", "Game closed".tr(), "Real-time game closed: [$reason]".tr())
                handleFatalError("Real-time game closed: [$reason]".tr())
            }
            "error" -> {
                val reason = msg["reason"]?.jsonPrimitive?.contentOrNull ?: ""
                // 致命错误 (game not running / gameId required): 对局在服务器已不存在,
                // 停止重连并自动回大厅; 其余 error 仅提示
                if (reason == "game not running" || reason == "gameId required") {
                    FsNotifier.notify("error", "Game error".tr(), "Real-time error: [$reason]".tr())
                    handleFatalError("Real-time error: [$reason]".tr())
                } else {
                    showToast("Real-time error: [$reason]".tr())
                }
            }
        }
    }

    /** 致命错误: 停止帧同步重连, 提示后自动回联机大厅 (对局已不存在时继续重连只会无限失败) */
    private fun handleFatalError(message: String) {
        showToast(message)
        val ws = worldScreenRef?.get()
        stop()
        Concurrency.runOnGLThread {
            try {
                if (ws != null && com.unciv.UncivGame.Current.screen === ws) {
                    com.unciv.ui.screens.lobbyscreens.LobbyRoomScreen.restoreMultiplayerServer()
                    ws.game.replaceCurrentScreen(com.unciv.ui.screens.lobbyscreens.LobbyScreen())
                }
            } catch (ignored: Exception) {
            }
        }
    }

    /** 回合状态广播 (服务器保底时长 + 完成回合): deadline (epoch 秒) + 已完成玩家列表.
     *  驱动: ①顶栏倒计时显示 ②“完成回合”按钮状态 (我完成→等剩余玩家; 结算后 ready 清空→恢复).
     *  防跳变: 过期广播 (回合号落后) 忽略; 同回合内本地已声明 (myTurnFinished) 不被不含我的广播覆盖;
     *  只有回合号前进时才重置完成状态. */
    private fun handleTurnStatus(msg: JsonObject) {
        val tsTurn = msg["turn"]?.jsonPrimitive?.intOrNull ?: return
        if (tsTurn < lastTurn) return  // 过期广播 (网络延迟/乱序) 忽略
        // 结算状态: settling=true → 全程锁定 (提示条常驻); 结算结束 → 解锁 + 移除提示条
        // (切换前停留由服务器延迟广播实现, 2026-08-22; 这里不再做切换后锁定)
        val settling = msg["settling"]?.jsonPrimitive?.contentOrNull == "true"
        val wasSettling = serverSettling
        dbg("turnStatus turn=$tsTurn lastTurn=$lastTurn settling=$settling wasSettling=$wasSettling settleLockSec=" + (worldScreenRef?.get()?.gameInfo?.gameParameters?.fsSettleLockSeconds ?: -1))
        serverSettling = settling
        if (settling) {
            dbg("settling=true → 锁定 + 提示条")
            showSettlingHint()  // 显示提示条 + 全程锁定 (serverSettling 控制实际锁定)
        } else {
            // 结算结束 (延迟到期, 服务器已广播新回合): 解锁 + 移除提示条
            // 无条件 hide (不要求 wasSettling) — 防 applyState 曾显示/状态错位导致提示条残留 (2026-08-22)
            dbg("settling=false → 解锁, 移除提示条")
            hideSettlingHint()
        }
        val deadlineSec = msg["deadline"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
        // deadline=0 的广播不覆盖已有倒计时 (连接补推竞态); deadline<0 = 无限制段 (无限, 不设倒计时)
        if (deadlineSec > 0 || turnDeadline == 0L) {
            turnDeadline = (deadlineSec * 1000).toLong()
        } else if (deadlineSec < 0) {
            turnDeadline = -1L
        }
        turnReadyPlayers = msg["readyPlayers"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        onlinePlayers = msg["onlinePlayers"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: onlinePlayers
        if (tsTurn > lastTurn) {
            // 新回合: 完成状态重置 (按钮恢复“完成回合”)
            myTurnFinished = false
            // 手机通知栏: 后台时告知新回合 (每回合一次; key 带回合号)
            FsNotifier.notify(
                "turn-$tsTurn",
                "New turn".tr(),
                "Turn [turnNumber] has started".tr().fillPlaceholders(tsTurn.toString()))
        } else if (playerId in turnReadyPlayers) {
            myTurnFinished = true
        }
        // 同回合且广播不含我: 保持本地状态 (已声明的不被旧广播覆盖)
        val worldScreen = worldScreenRef?.get() ?: return
        Concurrency.runOnGLThread {
            try {
                worldScreen.nextTurnButton.update()
                worldScreen.topBar.update(worldScreen.selectedCiv)
            } catch (ignored: Exception) {
            }
        }
    }

    /** 暂停按钮注册 (WorldScreenTopBar 创建时调用; 顶栏销毁时传 null 解除) */
    fun registerFsPauseButton(button: TextButton?) {
        fsPauseButton = button
        updateStatusLabel()
    }

    /** 暂停/继续 (顶栏按钮点击): 发命令 + 本地立即切换文本 (服务器广播 lastPaused 会确认/纠正) */
    fun togglePause() {
        if (lastPaused) {
            sendResume()
            hidePauseBar()
        } else {
            sendPause()
        }
        lastPaused = !lastPaused
        // 直接更新按钮文本 — 不依赖 statusLabel (可能为空导致跳过)
        Concurrency.runOnGLThread {
            fsPauseButton?.setText(if (lastPaused) "Resume".tr() else "Pause".tr())
        }
    }

    /** 服务器是否已暂停 (顶栏倒计时冻结用) */
    fun isPaused(): Boolean = lastPaused

    /** 有人暂停: 全局弹窗 (模态 — 暂停期间不能行动) + 倒计时冻结; 弹窗里有「继续」按钮 (谁都能点) */
    private fun handlePauseNotice(msg: JsonObject) {
        val nickname = msg["nickname"]?.jsonPrimitive?.contentOrNull ?: return
        pauseNickname = nickname
        FsNotifier.notify("pause", "Game paused".tr(), "[$nickname] has paused the game".tr())
        Concurrency.runOnGLThread {
            showPausePopup(nickname)
        }
    }

    /** 暂停提示条 (非模态): 顶部按钮 (暂停/概览/菜单) 保持可点 — 2026-08-21 用户要求 */
    private fun showPausePopup(nickname: String) {
        if (pauseBar != null) return  // 防重复
        val worldScreen = currentWorldScreenOrNull() ?: return
        val gameInfo = worldScreen.gameInfo ?: return
        if (gameInfo.gameId != gameId) return
        lastPaused = true
        updateStatusLabel()
        try {
            // 输入拦截: 顶栏以下的整个区域挡掉所有点击/滚轮 (只有顶栏按钮能点)
            // 纯 Actor + touchable.enabled 即可拦截 — stage hit() 只命中顶层 actor, 下面的地图/按钮收不到事件
            val blocker = com.badlogic.gdx.scenes.scene2d.Actor()
            blocker.touchable = com.badlogic.gdx.scenes.scene2d.Touchable.enabled
            val topBarHeight = worldScreen.topBar.height
            val blockerHeight = (worldScreen.stage.height - if (topBarHeight > 0f) topBarHeight else 50f).coerceAtLeast(0f)
            blocker.setBounds(0f, 0f, worldScreen.stage.width, blockerHeight)
            worldScreen.stage.addActor(blocker)
            pauseBlocker = blocker

            val bar = Table()
            // 单层圆角背景 (模组编辑器同款样式, 不再矩形套内层) — 2026-08-21
            bar.background = com.unciv.ui.screens.basescreen.BaseScreen.skinStrings.getUiBackground(
                "PauseBar",
                com.unciv.ui.screens.basescreen.BaseScreen.skinStrings.roundedEdgeRectangleShape,
                com.unciv.ui.screens.basescreen.BaseScreen.skinStrings.skinConfig.baseColor.darken(0.5f))
            bar.add("[$nickname] has paused the game".tr().toLabel(fontSize = 26)).pad(18f, 24f, 18f, 14f)
            // 观战者: 只显示提示, 不显示 Resume 按钮 (观战者无权恢复游戏, 2026-08-21)
            if (!worldScreen.viewingCiv.isSpectator()) {
                val resumeBtn = TextButton("Resume".tr(), com.unciv.ui.screens.basescreen.BaseScreen.skin)
                resumeBtn.onClick {
                    sendResume()
                    hidePauseBar()
                }
                bar.add(resumeBtn).pad(12f, 6f, 12f, 18f)
            }
            bar.pack()
            // 居中放大
            bar.setPosition((worldScreen.stage.width - bar.width) / 2f, (worldScreen.stage.height - bar.height) / 2f)
            worldScreen.stage.addActor(bar)
            pauseBar = bar
        } catch (e: Exception) {
        }
    }

    private fun hidePauseBar() {
        try {
            pauseBar?.remove()
        } catch (ignored: Exception) {
        }
        pauseBar = null
        try {
            pauseBlocker?.remove()
        } catch (ignored: Exception) {
        }
        pauseBlocker = null
    }

    /** 回合结算提示条: settling=true 期间常驻 (全程锁定), 结算结束 (settling=false) 由 hideSettlingHint 移除 — 2026-08-22 */
    private fun showSettlingHint() {
        val worldScreen = currentWorldScreenOrNull() ?: return
        val gameInfo = worldScreen.gameInfo ?: return
        if (gameInfo.gameId != gameId) return
        Concurrency.runOnGLThread {
            try {
                if (settlingHint != null) return@runOnGLThread  // 已显示
                val hint = "Settling turn...".tr().toLabel(fontSize = 22)
                hint.setColor(com.badlogic.gdx.graphics.Color.WHITE)
                val table = Table()
                table.background = com.unciv.ui.screens.basescreen.BaseScreen.skinStrings.getUiBackground(
                    "SettlingHint",
                    com.unciv.ui.screens.basescreen.BaseScreen.skinStrings.roundedEdgeRectangleShape,
                    com.badlogic.gdx.graphics.Color(0f, 0f, 0f, 0.6f))
                table.add(hint).pad(12f, 20f, 12f, 20f)
                table.pack()
                table.setPosition((worldScreen.stage.width - table.width) / 2f, worldScreen.stage.height * 0.42f)
                worldScreen.stage.addActor(table)
                settlingHint = table  // 存整个框 — remove() 时背景和文字一起移除
            } catch (e: Exception) {
            }
        }
    }

    /** 结算结束: 移除提示条 (GL 线程) */
    private fun hideSettlingHint() {
        Concurrency.runOnGLThread {
            try {
                settlingHint?.remove()
            } catch (ignored: Exception) {
            }
            settlingHint = null
        }
    }

    /** 从子屏 (城市/科技等) 返回世界屏时调用: 非模态条挂在 stage 上自动可见, 无需补弹; 保留防丢兜底 */
    fun ensurePausePopup() {
        val nick = pauseNickname ?: return
        if (pauseBar != null) return
        if (!lastPaused) return
        Concurrency.runOnGLThread {
            showPausePopup(nick)
        }
    }

    /** 有人恢复: 关闭暂停提示条 + 倒计时恢复 */
    private fun handleResumeNotice(msg: JsonObject) {
        FsNotifier.notify("resume", "Game resumed".tr(), "The game has resumed".tr())
        Concurrency.runOnGLThread {
            lastPaused = false
            pauseNickname = null
            updateStatusLabel()
            hidePauseBar()
        }
    }

    /** 暂停按钮位置: 跟随左侧“外交/聊天”按钮列 (聊天按钮正下方); WorldScreen.update 时调用 */

    /** 战斗反馈: 服务器攻击结果广播 → 游戏原生通知栏 (右上角), 不是弹窗 */
    /** 奇观被抢提示: 服务器定向广播 → 游戏原生通知栏 (回合内实时, 不等重载) */
    private fun handleWonderLost(msg: JsonObject) {
        val wonder = msg["wonder"]?.jsonPrimitive?.contentOrNull ?: return
        val cityName = msg["city"]?.jsonPrimitive?.contentOrNull ?: ""
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            val myCiv = worldScreen.viewingCiv
            if (myCiv == null || myCiv.isSpectator()) return@runOnGLThread
            val text = if (cityName.isNotEmpty())
                "[${wonder}] has been built by another civilization — [${cityName}] production canceled"
            else
                "[${wonder}] has been built by another civilization"
            myCiv.addNotification(
                text.tr(), null,
                com.unciv.logic.civilization.NotificationCategory.General,
                com.unciv.logic.civilization.NotificationIcon.Production)
        }
    }

    /** 遗迹奖励提示: 服务器定向广播 → 游戏原生通知栏 (回合内实时, 不等重载) */
    private fun handleRuinReward(msg: JsonObject) {
        val text = msg["text"]?.jsonPrimitive?.contentOrNull ?: return
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            val myCiv = worldScreen.viewingCiv
            if (myCiv == null || myCiv.isSpectator()) return@runOnGLThread
            myCiv.addNotification(
                text, null,
                com.unciv.logic.civilization.NotificationCategory.General,
                com.unciv.logic.civilization.NotificationIcon.Ruins)
        }
    }

    /** 回合内新增通知广播 (厌战度/资源增减等): 定向转发后本地加通知 → 通知面板显示 (不等回合重载) */
    private fun handleNotification(msg: JsonObject) {
        val text = msg["text"]?.jsonPrimitive?.contentOrNull ?: return
        // 定向: 服务器带目标 playerId, 只处理发给自己的
        val targetPlayerId = msg["playerId"]?.jsonPrimitive?.contentOrNull
        if (targetPlayerId != null && targetPlayerId != playerId) return
        val iconName = msg["icon"]?.jsonPrimitive?.contentOrNull
        val categoryName = msg["category"]?.jsonPrimitive?.contentOrNull
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            val myCiv = worldScreen.viewingCiv
            if (myCiv == null || myCiv.isSpectator()) return@runOnGLThread
            try {
                // 不加图标: 服务器广播的 icon 可能是建筑/单位名, 客户端 getImage 解析不了会黑块
                // (原版图标只支持 单位/科技/资源/晋升/直路径; 建筑名等无法解析)
                val cat = try {
                    categoryName?.let { com.unciv.logic.civilization.Notification.NotificationCategory.valueOf(it) }
                        ?: com.unciv.logic.civilization.Notification.NotificationCategory.General
                } catch (e: Exception) {
                    com.unciv.logic.civilization.Notification.NotificationCategory.General
                }
                // 去重: 同文本通知已存在 (如 handleBattleMessage 已加的"我方攻击"通知) 不重复添加
                // 注意: addNotification 存的是翻译后文本 — 比较也必须用翻译后, 否则同文本第二次必重复
                val displayText = text.tr()
                if (myCiv.notifications.any { it.text == displayText }) return@runOnGLThread
                myCiv.addNotification(displayText, null, cat)
                worldScreen.shouldUpdate = true
            } catch (e: Exception) {
            }
        }
    }

    /** 事件弹窗回合内广播 (mod 事件如时代奖励): 本地加 popupAlert → 世界屏自然弹 AlertPopup (不等重载) */
    private fun handleEventPopup(msg: JsonObject) {
        // 完整 value (可含 "|unitId=N" 后缀): 单位上下文决定选项 (单位限定事件)
        val eventValue = msg["event"]?.jsonPrimitive?.contentOrNull ?: return
        val eventName = eventValue.split(com.unciv.Constants.stringSplitCharacter)[0]
        // 定向消息: 服务器带目标 playerId, 只处理发给自己的 (防串弹到其他玩家)
        val targetPlayerId = msg["playerId"]?.jsonPrimitive?.contentOrNull
        if (targetPlayerId != null && targetPlayerId != playerId) return
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            val myCiv = worldScreen.viewingCiv ?: return@runOnGLThread
            if (myCiv.isSpectator()) return@runOnGLThread
            // 去重: 同事件弹窗已存在不重复加 (存档广播也会带 popupAlerts, 回合末重载兜底)
            if (myCiv.popupAlerts.any { it.type == com.unciv.logic.civilization.AlertType.Event
                    && it.value.split(com.unciv.Constants.stringSplitCharacter)[0] == eventName }) return@runOnGLThread
            myCiv.popupAlerts.add(com.unciv.logic.civilization.PopupAlert(
                com.unciv.logic.civilization.AlertType.Event, eventValue))
            // 记录挂起事件: 存档重载 (start() 清空 popupAlerts) 后重新挂起, 弹窗不消失
            pendingEvents.add(eventValue)
            worldScreen.shouldUpdate = true
        }
    }

    /** 事件已选择 (RenderEvent 发 op 后调用): 从挂起列表移除, 不再重弹 */
    fun markEventResolved(eventName: String) {
        // 按名字段匹配移除 — 挂起列表存完整 value (可能带 "|unitId=N" 后缀), 精确 remove 会漏 (孙武事件每回合重弹根因)
        pendingEvents.removeIf { it.split(com.unciv.Constants.stringSplitCharacter)[0] == eventName }
    }

    /** 建筑被建造 → 刷新打开的事件弹窗 (互斥选项如特殊伟人项目: 别人占了选项后立即灰掉/消失) */
    private var lastEventPopupRefreshAt = 0L
    private fun refreshOpenEventPopups(worldScreen: WorldScreen) {
        // 节流: 回合结算后大量建筑完工会连续广播 built → 不节流弹窗会被反复关/重开 (闪烁)
        val now = System.currentTimeMillis()
        if (now - lastEventPopupRefreshAt < 1500) return
        lastEventPopupRefreshAt = now
        try {
            for (actor in worldScreen.stage.actors) {
                if (actor is com.unciv.ui.screens.worldscreen.AlertPopup && actor.isVisible) {
                    actor.refreshForFsSync()
                }
            }
        } catch (e: Exception) {
        }
    }

    /** 贸易提议广播 (服务器挂起+转发): 本地加 TradeRequest → WorldScreen.update 自然弹 TradePopup */
    /** 我方发起的报价被接受 (贸易成交): 清除等待/报价状态 + 退出贸易界面 —
     *  发起人正停留在外交界面的贸易页时, 应回到外交菜单 (友谊宣言/宣战/谴责等) (2026-08-22 用户反馈) */
    private fun handleTradeAccepted(msg: JsonObject) {
        val acceptingCivName = msg["requestingCiv"]?.jsonPrimitive?.contentOrNull ?: return
        val targetPlayerId = msg["playerId"]?.jsonPrimitive?.contentOrNull
        if (targetPlayerId != null && targetPlayerId != playerId) return
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            val myCiv = worldScreen.viewingCiv
            if (myCiv != null && !myCiv.isSpectator()) {
                // 接受方 (服务器广播的是 civName; 本地 tradeRequests 存 civID)
                val acceptingCiv = gameInfo.civilizations.firstOrNull { it.civName == acceptingCivName }
                // 清除已成交的挂起报价: 接受方侧 (发起方乐观加的报价 key=发起方 civID) + 我方侧 (反向)
                if (acceptingCiv != null) {
                    acceptingCiv.tradeRequests.removeAll { it.requestingCiv == myCiv.civID }
                    myCiv.tradeRequests.removeAll { it.requestingCiv == acceptingCiv.civID }
                } else {
                    myCiv.tradeRequests.removeAll { it.requestingCiv == acceptingCivName }
                }
                try {
                    val cur = com.unciv.UncivGame.Current.screen
                    if (cur is com.unciv.ui.screens.worldscreen.TradePopup) {
                        com.unciv.UncivGame.Current.popScreen()
                    } else if (cur is com.unciv.ui.screens.diplomacyscreen.DiplomacyScreen && acceptingCiv != null) {
                        // 发起人: 贸易已成交 → 退出贸易页, 回到外交菜单 (updateRightSide 同时复位贸易状态标记)
                        cur.updateRightSide(acceptingCiv)
                    }
                } catch (ignored: Exception) {
                }
            }
            worldScreen.shouldUpdate = true
        }
    }

    private fun handleTradeRequest(msg: JsonObject) {
        val requestingCiv = msg["requestingCiv"]?.jsonPrimitive?.contentOrNull ?: return
        val tradeJson = msg["trade"]?.jsonObject ?: return
        // 定向消息: 服务器带目标 playerId, 只处理发给自己的 (防贸易请求串到其他玩家)
        val targetPlayerId = msg["playerId"]?.jsonPrimitive?.contentOrNull
        if (targetPlayerId != null && targetPlayerId != playerId) return
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            val trade = parseTrade(tradeJson, gameInfo) ?: return@runOnGLThread
            try {
                // 重复广播/重连补推同一内容 → 跳过 (保持原请求引用, 防弹窗关闭后残留再弹/再生效)
                val existing = worldScreen.viewingCiv.tradeRequests.firstOrNull { it.requestingCiv == requestingCiv }
                if (existing != null && existing.trade.equalTrade(trade)) return@runOnGLThread
                worldScreen.viewingCiv.tradeRequests.removeAll { it.requestingCiv == requestingCiv }
                worldScreen.viewingCiv.tradeRequests.add(TradeRequest(requestingCiv, trade))
                worldScreen.shouldUpdate = true
            } catch (e: Exception) {
            }
        }
    }

    /** 对方撤回贸易提议: 本地移除对应请求 (弹窗开着则关闭) */
    private fun handleTradeRetracted(msg: JsonObject) {
        val requestingCiv = msg["requestingCiv"]?.jsonPrimitive?.contentOrNull ?: return
        // 定向消息: 服务器带目标 playerId, 只处理发给自己的 (防撤回消息串到其他玩家)
        val targetPlayerId = msg["playerId"]?.jsonPrimitive?.contentOrNull
        if (targetPlayerId != null && targetPlayerId != playerId) return
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            worldScreen.viewingCiv.tradeRequests.removeAll { it.requestingCiv == requestingCiv }
            worldScreen.shouldUpdate = true
            // 对方撤回时, 开着的 TradePopup 残留 → 关闭 (防点了报"无挂起提议"拒绝; 选词条页面同款问题)
            try {
                val cur = com.unciv.UncivGame.Current.screen
                if (cur is com.unciv.ui.screens.worldscreen.TradePopup) {
                    com.unciv.UncivGame.Current.popScreen()
                }
            } catch (e: Exception) {
            }
        }
    }

    /** 占领城市弹窗 (服务器权威): 本地加 popupAlert → AlertPopup 自然弹出 (选择按钮发 op) */
    private fun handleCityConquered(msg: JsonObject) {
        val cityId = msg["cityId"]?.jsonPrimitive?.contentOrNull ?: return
        // 定向消息: 服务器带目标 playerId, 只处理发给自己的 (防串弹到其他玩家)
        val targetPlayerId = msg["playerId"]?.jsonPrimitive?.contentOrNull
        if (targetPlayerId != null && targetPlayerId != playerId) return
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            try {
                val alerts = worldScreen.viewingCiv.popupAlerts
                if (alerts.any { it.type == com.unciv.logic.civilization.AlertType.CityConquered && it.value == cityId })
                    return@runOnGLThread  // 去重 (存档广播/重连可能重复推送)
                alerts.add(com.unciv.logic.civilization.PopupAlert(
                    com.unciv.logic.civilization.AlertType.CityConquered, cityId))
                worldScreen.shouldUpdate = true
            } catch (e: Exception) {
            }
        }
    }

    /** 友谊宣言提议: 本地加 popupAlerts → AlertPopup 自然弹出 (接受/拒绝发 op) */
    private fun handleFriendshipOffer(msg: JsonObject) {
        val requestingCiv = msg["requestingCiv"]?.jsonPrimitive?.contentOrNull ?: return
        // 定向消息: 服务器带目标 playerId, 只处理发给自己的 (防串弹到其他玩家)
        val targetPlayerId = msg["playerId"]?.jsonPrimitive?.contentOrNull
        if (targetPlayerId != null && targetPlayerId != playerId) return
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            try {
                val alerts = worldScreen.viewingCiv.popupAlerts
                alerts.removeAll {
                    it.type == com.unciv.logic.civilization.AlertType.DeclarationOfFriendship && it.value == requestingCiv
                }
                alerts.add(com.unciv.logic.civilization.PopupAlert(
                    com.unciv.logic.civilization.AlertType.DeclarationOfFriendship, requestingCiv))
                worldScreen.shouldUpdate = true
            } catch (e: Exception) {
            }
        }
    }

    /** 被宣战: 本地加 popupAlerts → AlertPopup 自然弹出 (原版战争宣言弹窗) */
    private fun handleDeclaredWar(msg: JsonObject) {
        val declarer = msg["declarer"]?.jsonPrimitive?.contentOrNull ?: return
        val declaredBy = msg["declaredBy"]?.jsonPrimitive?.contentOrNull ?: declarer
        val warTarget = msg["warTarget"]?.jsonPrimitive?.contentOrNull ?: ""
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            try {
                val viewingCiv = worldScreen.viewingCiv
                // 开战: 原版行为 — 所有正在进行的贸易 + 挂起的贸易申请立即取消 (存档由服务器清, 本地同步清)
                val warCivId = gameInfo.civilizations.firstOrNull { it.civName == warTarget }?.civID
                    ?: gameInfo.civilizations.firstOrNull { it.civName == declaredBy }?.civID
                if (warCivId != null) {
                    for (civ in gameInfo.civilizations) {
                        civ.tradeRequests.removeAll { it.requestingCiv == warCivId }
                        civ.getDiplomacyManager(warCivId)?.trades?.clear()
                    }
                    // 对方视角的 tradeRequests 也要清 (发起方在本地方向)
                    viewingCiv.tradeRequests.removeAll { req ->
                        val other = gameInfo.civilizations.firstOrNull { it.civID == req.requestingCiv }
                        other?.civName == warTarget || other?.civName == declaredBy
                    }
                }
                // 关闭开着的 TradePopup (贸易已取消, 残留弹窗操作会报无挂起提议)
                try {
                    val cur = com.unciv.UncivGame.Current.screen
                    if (cur is WorldScreen) {
                        cur.stage.actors.filterIsInstance<TradePopup>().forEach { it.close() }
                    }
                } catch (e: Exception) {
                }
                // 双方都回主界面 (关掉城市/科技/外交等子屏) — 宣战是重大事件, 打断当前操作
                try {
                    com.unciv.UncivGame.Current.resetToWorldScreen()
                } catch (e: Exception) {
                }
                // 弹窗: 主动方 Toast 确认, 被动方原版 WarDeclaration 弹窗 (含 leader 台词)
                val amIDeclarer = viewingCiv.civName == declaredBy
                if (amIDeclarer) {
                    showToast("[${declaredBy}] has declared war on [${warTarget}]!".tr())
                } else {
                    val alerts = viewingCiv.popupAlerts
                    alerts.removeAll {
                        it.type == com.unciv.logic.civilization.AlertType.WarDeclaration && it.value == declaredBy
                    }
                    alerts.add(com.unciv.logic.civilization.PopupAlert(
                        com.unciv.logic.civilization.AlertType.WarDeclaration, declaredBy))
                }
                worldScreen.shouldUpdate = true
            } catch (e: Exception) {
            }
        }
    }

    /** 被谴责: 本地加 popupAlerts → AlertPopup 自然弹出 (含"THIS MEANS WAR!"宣战按钮) */
    private fun handleDenounced(msg: JsonObject) {
        val denouncer = msg["denouncer"]?.jsonPrimitive?.contentOrNull ?: return
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            try {
                val alerts = worldScreen.viewingCiv.popupAlerts
                alerts.removeAll {
                    it.type == com.unciv.logic.civilization.AlertType.Denounced && it.value == denouncer
                }
                alerts.add(com.unciv.logic.civilization.PopupAlert(
                    com.unciv.logic.civilization.AlertType.Denounced, denouncer))
                worldScreen.shouldUpdate = true
            } catch (e: Exception) {
            }
        }
    }

    /** 要求提议: 本地加 popupAlerts → AlertPopup 自然弹出 (接受/拒绝发 op) */
    private fun handleDemandOffer(msg: JsonObject) {
        val requestingCiv = msg["requestingCiv"]?.jsonPrimitive?.contentOrNull ?: return
        val demandName = msg["demand"]?.jsonPrimitive?.contentOrNull ?: return
        // 定向消息: 服务器带目标 playerId, 只处理发给自己的 (防串弹到其他玩家)
        val targetPlayerId = msg["playerId"]?.jsonPrimitive?.contentOrNull
        if (targetPlayerId != null && targetPlayerId != playerId) return
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            try {
                val demand = com.unciv.logic.civilization.diplomacy.Demand.valueOf(demandName)
                val alerts = worldScreen.viewingCiv.popupAlerts
                alerts.removeAll {
                    it.type == demand.demandAlert && it.value == requestingCiv
                }
                alerts.add(com.unciv.logic.civilization.PopupAlert(demand.demandAlert, requestingCiv))
                worldScreen.shouldUpdate = true
            } catch (e: Exception) {
            }
        }
    }

    private fun parseTrade(tradeJson: JsonObject, gameInfo: GameInfo): Trade? {
        val trade = Trade()
        for (listName in listOf("ourOffers", "theirOffers")) {
            val list = tradeJson[listName]?.jsonArray ?: continue
            val target = if (listName == "ourOffers") trade.ourOffers else trade.theirOffers
            for (offerJson in list) {
                val obj = offerJson.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                val typeName = obj["type"]?.jsonPrimitive?.contentOrNull ?: continue
                val amount = obj["amount"]?.jsonPrimitive?.intOrNull ?: 1
                val type = try {
                    TradeOfferType.valueOf(typeName)
                } catch (e: Exception) {
                    continue
                }
                target.add(TradeOffer(name, type, amount, gameInfo.speed))
            }
        }
        if (trade.ourOffers.isEmpty() && trade.theirOffers.isEmpty()) return null
        return trade
    }

    private fun handleBattleMessage(msg: JsonObject) {
        val attackerCiv = msg["attackerCiv"]?.jsonPrimitive?.contentOrNull ?: return
        val attackerId = msg["attackerId"]?.jsonPrimitive?.intOrNull ?: return
        val damage = msg["damage"]?.jsonPrimitive?.intOrNull ?: 0          // 攻击者对防守者
        val defenderDamage = msg["defenderDamage"]?.jsonPrimitive?.intOrNull ?: 0  // 防守者对攻击者
        val x = msg["x"]?.jsonPrimitive?.intOrNull ?: return
        val y = msg["y"]?.jsonPrimitive?.intOrNull ?: return
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            try {
                // 战斗动画/伤害飘字 (原版 battleAnimationDeferred): 服务器广播 battle 后播放 —
                // 帧同步本地不执行 Battle, 无动画 → "打人不显示伤害" (2026-08-21)
                // 2026-08-22: ①只播与自己相关的战斗 (自己打/被打; 观战者播全部) ②城市攻击 (attackerId=-1)
                val myCiv = worldScreen.viewingCiv
                val spectator = myCiv == null || myCiv.isSpectator()
                val targetTile = gameInfo.tileMap.get(x, y) ?: return@runOnGLThread
                val targetId = msg["targetId"]?.jsonPrimitive?.intOrNull ?: -1
                val attacker: com.unciv.logic.battle.ICombatant? =
                    if (attackerId >= 0) {
                        findUnit(gameInfo, attackerId)?.let { com.unciv.logic.battle.MapUnitCombatant(it) }
                    } else {
                        // 城市攻击: attackerName 定位攻击城市 (城市轰炸无突进动画, 只需飘字/闪烁)
                        val cname = msg["attackerName"]?.jsonPrimitive?.contentOrNull
                        gameInfo.civilizations.firstOrNull { it.civName == attackerCiv }
                            ?.cities?.firstOrNull { it.name == cname }
                            ?.let { com.unciv.logic.battle.CityCombatant(it) }
                    }
                val defender: com.unciv.logic.battle.ICombatant? =
                    if (targetId >= 0) {
                        findUnit(gameInfo, targetId)?.let { com.unciv.logic.battle.MapUnitCombatant(it) }
                    } else {
                        // 城市目标 (targetId=-1): 用坐标找城市
                        targetTile.getCity()?.let { com.unciv.logic.battle.CityCombatant(it) }
                    }
                if (!spectator) {
                    // 非观战者: 只播与自己相关的战斗 (攻击者或防守者是自己文明)
                    val attackerIsMine = attackerCiv == myCiv!!.civName
                    val defenderIsMine = defender?.getCivInfo()?.civName == myCiv.civName
                    if (!attackerIsMine && !defenderIsMine) return@runOnGLThread
                    // 攻击者不可见 (对方打我, 攻击者在我视野外) → attacker 为 null, 只播防守者飘字
                }
                if (defender == null) return@runOnGLThread
                worldScreen.battleAnimationDeferred(
                    attacker,        // 可空: 攻击者不可见时只播防守者飘字/闪烁
                    defenderDamage,  // 攻击者受到的伤害
                    defender,
                    damage)          // 防守者受到的伤害
            } catch (ignored: Exception) {
            }
            // 战斗通知统一走服务器广播 (checkRuinRewards 全量转发, 含攻击方/被攻击方/厌战度等),
            // 这里不再本地 addNotification — 否则与广播重复 (用户实测"通知两次"的根因之一)
            worldScreen.shouldUpdate = true
        }
    }

    /** 剿灭蛮族营地通知 (原版: 只通知自己文明的行动) */
    private fun handleCampCleared(msg: JsonObject) {
        val civ = msg["civ"]?.jsonPrimitive?.contentOrNull ?: return
        val unitName = msg["unitName"]?.jsonPrimitive?.contentOrNull ?: "?"
        val gold = msg["gold"]?.jsonPrimitive?.intOrNull ?: 0
        val x = msg["x"]?.jsonPrimitive?.intOrNull ?: return
        val y = msg["y"]?.jsonPrimitive?.intOrNull ?: return
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            val myCiv = worldScreen.viewingCiv
            if (myCiv == null || myCiv.isSpectator()) return@runOnGLThread
            if (civ != myCiv.civName) return@runOnGLThread  // 跟原版一样, 只通知自己的
            myCiv.addNotification(
                "Our [${unitName}] cleared a Barbarian camp for [$gold] gold".tr(),
                com.unciv.logic.map.HexCoord(x, y),
                com.unciv.logic.civilization.NotificationCategory.War,
                com.unciv.logic.civilization.NotificationIcon.War
            )
            worldScreen.shouldUpdate = true
        }
    }

    private fun handleSimMessage(msg: JsonObject) {
        val ok = msg["ok"]?.jsonPrimitive?.contentOrNull == "true"
        val reason = msg["reason"]?.jsonPrimitive?.contentOrNull ?: ""
        val state = msg["state"] as? JsonObject
        if (state == null) {
            // op 结果失败 (如移动被拒)
            if (!ok && reason.isNotEmpty() && reason != "state") {
                // 结算窗口拒绝 (全员完成→模拟器结算中): 友好提示而非“Operation rejected”, 玩家稍候重试即可
                if (reason.contains("turn settling")) {
                    showToast("Turn settling, please wait a moment".tr())
                } else {
                    showToast("Operation rejected: [$reason]".tr())
                }
            }
            return
        }
        val worldScreen = worldScreenRef?.get() ?: return
        val newTurn = state["turn"]?.jsonPrimitive?.intOrNull
        if (newTurn != null && newTurn > lastTurn) {
            // 新回合: “已查看”闲置单位标记重置 — 上回合点过“下一个单位”的单位本回合重新参与循环
            // (不重置 → reapplyLocalDueSeen 把本回合 due=true 的单位设回 false → 闲置循环漏单位)
            localDueSeen.clear()
            // 新回合 state 到达 = 结算完成 (settle_finish 广播): 移除提示条/解锁 — 2026-08-22 修复“回合正在结算”残留
            // (旧逻辑这里无条件 showSettlingHint, 而 turnStatus 隐藏需 wasSettling → 状态对不上时提示条永不消失)
            serverSettling = false
            hideSettlingHint()
        }
        lastTurn = newTurn ?: lastTurn
        lastPaused = state["paused"]?.jsonPrimitive?.contentOrNull == "true"
        val units = state["units"]?.jsonArray ?: emptyList()
        val cities = state["cities"]?.jsonArray ?: emptyList()
        val civs = state["civs"]?.jsonArray ?: emptyList()
        val encampments = state["encampments"]?.jsonArray ?: emptyList()
        val improvements = state["improvements"]?.jsonArray ?: emptyList()
        val improvementsDone = state["improvementsDone"]?.jsonArray ?: emptyList()
        val roads = state["roads"]?.jsonArray ?: emptyList()
        val religions = state["religions"]?.jsonArray ?: emptyList()
        // 地形变化同步 (OneTimeChangeTerrain): 被改地块 [x,y,baseTerrain,features,naturalWonder,improvement]
        val terrainChanges = state["terrainChanges"]?.jsonArray ?: emptyList()
        // 增量广播: full 标记 + removed 列表 (服务器总是输出; 缺省视为全量)
        val isFull = state["full"]?.jsonPrimitive?.contentOrNull == "true"
        val removedUnits = state["removedUnits"]?.jsonArray ?: emptyList()
        val removedCities = state["removedCities"]?.jsonArray ?: emptyList()
        // UncivGC 组队: 队伍分组 (playerId 列表, 按队号索引) — 服务器 state 顶层 teams 段 (2026-08-23)
        try {
            val teams = state["teams"]?.jsonArray
            if (teams != null) {
                val newTeam = HashSet<String>()
                for (teamArr in teams) {
                    val arr = teamArr.jsonArray ?: continue
                    for (pid in arr) pid.jsonPrimitive.contentOrNull?.let { newTeam.add(it) }
                }
                if (newTeam != myTeamPlayerIds) {
                    myTeamPlayerIds.clear()
                    myTeamPlayerIds.addAll(newTeam)
                    teamExploredMerged = false  // 队伍变化 → 重新一次性合并探索历史
                }
            }
        } catch (e: Exception) {}
        // 防御: 增量状态但回合已变 → 丢弃并请求全量 (回合变化应全量, 此为网络乱序/服务器防御漏网)
        if (!isFull && newTurn != null && newTurn > lastTurn) {
            try {
                sendJson(buildJson {
                    put("cmd", "state")
                    put("full", true)
                })
            } catch (_: Exception) {}
            return
        }
        // 外交胜利投票记录同步 [投票文明, 投给谁|null] (投票后 mayVoteForDiplomaticVictory 立即变 false; 服务器权威)
        state["diplomaticVotes"]?.jsonArray?.let { dvArr ->
            try {
                val server = HashMap<String, String?>()
                for (dv in dvArr) {
                    val a = dv.jsonArray ?: continue
                    if (a.size < 2) continue
                    val civId = a[0].jsonPrimitive.contentOrNull ?: continue
                    val voted = if (a[1].jsonPrimitive.contentOrNull == null) null else a[1].jsonPrimitive.contentOrNull
                    server[civId] = voted
                }
                val local = worldScreen.gameInfo.diplomaticVictoryVotesCast
                if (local.toMap() != server) {
                    local.clear()
                    local.putAll(server)
                    worldScreen.shouldUpdate = true
                }
            } catch (e: Exception) {
            }
        }
        // 昵称映射同步 (playerId -> nickname): 服务器在 state 消息顶层附加 (obj["nicknames"]), 概览/政治学显示用
        // 注意: 在 msg 顶层找, 不在 state 子对象里 — 2026-08-20 修复 (此前全房间昵称显示 "-")
        msg["nicknames"]?.jsonObject?.let { nk ->
            playerNicknames.clear()
            for ((k, v) in nk) playerNicknames[k] = v.jsonPrimitive.contentOrNull ?: k
        }
        Concurrency.runOnGLThread {
            if (!running) return@runOnGLThread
            try {
                applyState(worldScreen, units, cities, civs, encampments, improvements, improvementsDone, roads, religions, terrainChanges, isFull = isFull, removedUnits = removedUnits, removedCities = removedCities)
            } catch (e: Exception) {
                // 状态应用绝不能崩溃 — 失败项由下一条广播/回合重载兜底
            }
            updateStatusLabel()
            // 战败检测: 弹提示 → 确认后切观战 (看海); 每局只弹一次
            checkDefeatedAndOfferSpectate()
        }
    }

    /** UncivGC 组队 (2026-08-23): 我的队友 civ 列表 (不含自己; 观战/无队伍 → 空)
     *  AI 无 playerId 不在 teams 里, 永不成为队友 */
    private fun getMyTeammates(gameInfo: GameInfo): List<Civilization> {
        if (myTeamPlayerIds.isEmpty() || isSpectating) return emptyList()
        return gameInfo.civilizations.filter {
            it.playerId in myTeamPlayerIds && it.playerId != playerId
                && !it.isDefeated() && !it.isSpectator()
        }
    }

    /** 视野刷新: 我方全部单位调 updateVisibleTiles() — 游戏原生逻辑,
     *  按单位真实视野范围计算 viewableTiles 并永久探索新格子 (与单机完全一致)。
     *  敌方单位不做任何探索 — 只有进入我方视野才可见。
     *  主动相遇检测: 我方视野内出现未认识的文明 → 双向相遇 (双方客户端各自触发, 弹窗对称 —
     *  不依赖"自己视野变化"才检查, 对方走进我方已见区域也能触发)。
     *  组队 (2026-08-23): 队友单位/城市视野并入自己的 viewableTiles + 永久探索, 实现视野共享。 */
    private fun refreshMyCivVisibility(worldScreen: WorldScreen, gameInfo: GameInfo) {
        val civ = worldScreen.viewingCiv
        if (civ.isSpectator()) return
        try {
            // 城市视野: 原版 viewableTiles = 领土+邻居(ourTilesAndNeighboringTiles) ∪ 单位视野;
            // 帧同步客户端从不跑 updateOurTiles → 占领/新建城市后城市中心视野缺失 → 迷雾不揭/城内单位看不见
            // 先重建 ourTilesAndNeighboringTiles (城市拥有的格+邻居, 原版语义), 再刷单位视野 (全量重算 viewableTiles)
            try { civ.cache.updateOurTiles() } catch (e0: Exception) {}
            for (unit in civ.units.getCivUnits()) {
                if (unit.isDestroyed || !unit.hasTile()) continue
                unit.updateVisibleTiles()
            }
            // ---- 组队: 队友视野共享 ----
            val teammates = getMyTeammates(gameInfo)
            if (teammates.isNotEmpty()) {
                for (tciv in teammates) {
                    try { tciv.cache.updateOurTiles() } catch (e1: Exception) {}
                    for (unit in tciv.units.getCivUnits()) {
                        if (unit.isDestroyed || !unit.hasTile()) continue
                        try { unit.updateVisibleTiles() } catch (e2: Exception) {}
                    }
                }
                // 队友正在看的格 → 并入我的实时视野 (单位视野 + 城市视野)
                val merged = LinkedHashSet(civ.viewableTiles)
                for (tciv in teammates) {
                    merged.addAll(tciv.viewableTiles)
                    try { merged.addAll(tciv.cache.ourTilesAndNeighboringTiles) } catch (e3: Exception) {}
                }
                civ.viewableTiles = merged
                // 队友当前视野的格 → 我也永久探索 (持续跟随)
                for (tciv in teammates) {
                    for (tile in tciv.viewableTiles) {
                        try { tile.setExplored(civ, true) } catch (e4: Exception) {}
                    }
                }
                // 首次: 一次性合并队友全部探索历史 (join 时队友 exploredBy 来自存档; 之后本地持续维护)
                if (!teamExploredMerged) {
                    try {
                        for (tile in gameInfo.tileMap.values) {
                            for (tciv in teammates) {
                                if (tile.isExplored(tciv)) tile.setExplored(civ, true)
                            }
                        }
                    } catch (e5: Exception) {}
                    teamExploredMerged = true
                }
            }
            // 主动相遇检测: 每个文明只弹一次 (shownMeets 防重, 跨重载/重建有效)
            // 帧同步: 相遇的权威执行在服务器 (civ.meet op → makeCivilizationsMeet: 城邦见面给金币/双向建外交/通知),
            // 客户端只负责弹窗 UI — 本地调 makeCivilizationsMeet 会本地加金币, 被广播回滚 (见面金币丢失 bug 根因)
            // 仅未认识的文明才发 op+弹窗; 已认识的不再补弹 (重载/闪退后 shownMeets 清空 → 补弹会“所有人又认识一遍”)
            // 组队: civ.viewableTiles 已含队友视野 → 队友见到的新文明我也自动遇见 (2026-08-23)
            for (tile in civ.viewableTiles) {
                val tileUnit = tile.getFirstUnit()
                if (tileUnit != null && tileUnit.civ != civ && !tileUnit.civ.isBarbarian
                    && !tileUnit.civ.isSpectator()
                    && tileUnit.civ.civID !in shownMeets) {
                    if (!civ.diplomacy.containsKey(tileUnit.civ.civID)) {
                        sendOp("civ.meet", mapOf("civ" to tileUnit.civ.civID))
                        try {
                            civ.popupAlerts.add(com.unciv.logic.civilization.PopupAlert(
                                com.unciv.logic.civilization.AlertType.FirstContact, tileUnit.civ.civID))
                        } catch (e3: Exception) {}
                    }
                    shownMeets.add(tileUnit.civ.civID)
                }
                val tileCity = tile.getCity()
                if (tileCity != null && tileCity.civ != civ && !tileCity.civ.isBarbarian
                    && !tileCity.civ.isSpectator()
                    && tileCity.civ.civID !in shownMeets) {
                    if (!civ.diplomacy.containsKey(tileCity.civ.civID)) {
                        sendOp("civ.meet", mapOf("civ" to tileCity.civ.civID))
                        try {
                            civ.popupAlerts.add(com.unciv.logic.civilization.PopupAlert(
                                com.unciv.logic.civilization.AlertType.FirstContact, tileCity.civ.civID))
                        } catch (e3: Exception) {}
                    }
                    shownMeets.add(tileCity.civ.civID)
                }
            }
            worldScreen.shouldUpdate = true
        } catch (e: Exception) {
            // 视野刷新失败不影响游戏
        }
    }

    /** 帧同步: 点过“下一个单位”的单位 id 记入本地集合 (due 广播回滚后重新应用) */
    fun markDueSeen(unitId: Int) {
        localDueSeen.add(unitId)
    }

    /** 帧同步: 单位“跳过回合”切换后调用 — 从“已查看”集合移除该单位.
     *  否则取消跳过时 (服务器 due 已改回 true) 广播应用后又被本地标记打回 false → “跳过回合无法取消” (2026-08-22 用户反馈) */
    fun onUnitSkipToggle(unitId: Int) {
        localDueSeen.remove(unitId)
    }

    /** 帧同步: 广播应用后重新应用本地“已查看”标记 (否则 due 被广播覆盖回 true, 下一个单位循环卡住).
     *  [serverDueChanged]: 本次广播中服务器驱动了 due 变化的单位 (跳过回合切换) — 不应用本地标记,
     *  否则“取消跳过” (服务器已改回 true) 又被本地打回 false → 无法取消 (2026-08-22) */
    private fun reapplyLocalDueSeen(gameInfo: GameInfo, serverDueChanged: Set<Int> = emptySet()) {
        try {
            for (id in localDueSeen) {
                if (id in serverDueChanged) continue
                findUnit(gameInfo, id)?.due = false
            }
        } catch (ignored: Exception) {}
    }

    /** 服务器出现但本地没有的单位 (购买/生产完成等) → 按状态创建, 实时显示 (不等回合末重载) */
    private fun createUnitFromState(gameInfo: GameInfo, obj: JsonObject): MapUnit? {
        try {
            val civName = obj["civ"]?.jsonPrimitive?.contentOrNull ?: return null
            val civ = gameInfo.civilizations.firstOrNull { it.civName == civName } ?: return null
            val unitName = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
            val baseUnit = gameInfo.ruleset.units[unitName] ?: return null
            val x = obj["x"]?.jsonPrimitive?.intOrNull ?: return null
            val y = obj["y"]?.jsonPrimitive?.intOrNull ?: return null
            val tile = gameInfo.tileMap.get(x, y) ?: return null
            val unit = MapUnit()
            unit.baseUnit = baseUnit
            unit.civ = civ
            unit.owner = civName  // 序列化字段 (lateinit) — 漏了会在渲染时 UninitializedPropertyAccessException 崩溃
            unit.name = unitName  // 名称键 (lateinit) — 同上, 漏了渲染时 getName 崩
            unit.setTransients(gameInfo.ruleset)  // 补全 transient 链: promotions.unit/statusMap/updateUniques
            // (漏了 promotions.unit → 晋升按钮生成时 UninitializedPropertyAccessException: lateinit property unit)
            unit.id = obj["id"]?.jsonPrimitive?.intOrNull ?: return null
            obj["hp"]?.jsonPrimitive?.intOrNull?.let { unit.health = it }
            obj["actions"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()?.let { unit.currentMovement = it }
            obj["due"]?.jsonPrimitive?.contentOrNull?.let { unit.due = it == "true" }
            // 编队形态同步 (军团/集团军; 可选字段 — 缺失保持默认 Single)
            obj["formation"]?.jsonPrimitive?.contentOrNull?.let { fName ->
                try {
                    unit.formation = com.unciv.logic.map.mapunit.UnitFormation.valueOf(fName)
                } catch (ignored: Exception) {
                }
            }
            unit.putInTile(tile)  // 可能 throw (格被占) → 返回 null, 回合末重载兜底
            civ.units.addUnit(unit, false)
            return unit
        } catch (e: Exception) {
            return null
        }
    }

    private fun applyState(worldScreen: WorldScreen, units: List<JsonElement>, cities: List<JsonElement> = emptyList(), civs: List<JsonElement> = emptyList(), encampments: List<JsonElement> = emptyList(), improvements: List<JsonElement> = emptyList(), improvementsDone: List<JsonElement> = emptyList(), roads: List<JsonElement> = emptyList(), religions: List<JsonElement> = emptyList(), terrainChanges: List<JsonElement> = emptyList(), isFull: Boolean = true, removedUnits: List<JsonElement> = emptyList(), removedCities: List<JsonElement> = emptyList()) {
        val gameInfo = worldScreen.gameInfo ?: return
        // 地形变化同步 (OneTimeChangeTerrain "Turn this tile into"): 服务器权威改地形, 本地应用 + 刷新视野/单位通行
        syncTerrainChanges(gameInfo, terrainChanges, worldScreen)
        // 回合数显示同步 (顶栏)
        if (gameInfo.turns != lastTurn) {
            gameInfo.turns = lastTurn
        }
        cityStateChanged = false
        syncCities(worldScreen, cities, isFull, removedCities)
        val civChanged = syncCivInfo(gameInfo, civs, worldScreen.viewingCiv.civID)
        var unitsChanged = false
        syncEncampments(gameInfo, encampments)
        syncImprovements(gameInfo, improvements)
        syncImprovementsDone(gameInfo, improvementsDone)
        syncRoads(gameInfo, roads)
        syncReligions(gameInfo, religions, worldScreen)
        val stateIds = HashSet<Int>()
        // 本次广播中服务器驱动了 due 变化的单位 (跳过回合切换) — 重应用“已查看”标记时跳过, 防止取消跳过被回滚
        val serverDueChanged = HashSet<Int>()
        for (unitJson in units) {
            try {
                val obj = unitJson.jsonObject
                val id = obj["id"]?.jsonPrimitive?.intOrNull ?: continue
                stateIds.add(id)
                val x = obj["x"]?.jsonPrimitive?.intOrNull ?: continue
                val y = obj["y"]?.jsonPrimitive?.intOrNull ?: continue
                val unit = findUnit(gameInfo, id)
                    ?: createUnitFromState(gameInfo, obj)?.also { unitsChanged = true }
                    ?: continue
                if (!unit.hasTile()) continue
                val target = gameInfo.tileMap.get(x, y) ?: continue
                if (unit.getTile() != target) {
                    worldScreen.mapHolder.animateServerUnitMove(unit, target)
                    // 服务器移动后刷新单位 uniques 缓存 — 否则地块条件类 uniques
                    // (行商 "in [{improved} {resource}] tiles" 进货 / 城市中心售货) 仍按旧地块判断 → 当回合不可用
                    try { unit.updateUniques() } catch (ignored: Exception) {}
                }
                obj["actions"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()?.let {
                    if (unit.currentMovement != it) unit.currentMovement = it
                }
                // 单位升级同步 (废墟升级/金币升级/免费升级 — 同 id 新单位): 图标/属性/战力立即变化
                obj["baseUnit"]?.jsonPrimitive?.contentOrNull?.let { baseName ->
                    if (unit.baseUnit.name != baseName) {
                        val newBase = gameInfo.ruleset.units[baseName]
                        if (newBase != null && newBase != unit.baseUnit) {
                            unit.baseUnit = newBase
                            if (unit.name != baseName) unit.name = baseName
                            try {
                                unit.updateUniques()
                            } catch (ignored: Exception) {
                            }
                            unitsChanged = true
                            worldScreen.shouldUpdate = true
                        }
                    }
                }
                obj["hp"]?.jsonPrimitive?.intOrNull?.let {
                    if (unit.health != it) {
                        unit.health = it
                        unitsChanged = true
                    }
                }
                obj["due"]?.jsonPrimitive?.contentOrNull?.let {
                    val newDue = it == "true"
                    if (unit.due != newDue) {
                        unit.due = newDue
                        serverDueChanged.add(id)
                    }
                }
                // 编队形态同步 (军团/集团军: 图标角标/战斗力加成/拆分按钮; 服务器权威 — 2026-08-22)
                obj["formation"]?.jsonPrimitive?.contentOrNull?.let { fName ->
                    try {
                        val f = com.unciv.logic.map.mapunit.UnitFormation.valueOf(fName)
                        if (unit.formation != f) {
                            unit.formation = f
                            unitsChanged = true
                            worldScreen.shouldUpdate = true
                        }
                    } catch (ignored: Exception) {
                    }
                }
                // 单位实例名同步 (自定义改名; 可选字段 — 缺失/null 时清除本地, 防"取消改名"传不到对方)
                obj["instanceName"]?.jsonPrimitive?.contentOrNull?.let {
                    if (unit.instanceName != it) {
                        unit.instanceName = it
                        unitsChanged = true
                        worldScreen.shouldUpdate = true
                    }
                } ?: run {
                    if (unit.instanceName != null) {
                        unit.instanceName = null
                        unitsChanged = true
                        worldScreen.shouldUpdate = true
                    }
                }
                // 驻守/睡眠/苏醒等动作状态同步 (服务器权威)
                obj["action"]?.jsonPrimitive?.contentOrNull?.let {
                    if (unit.action != it) unit.action = it
                } ?: run {
                    if (unit.action != null) unit.action = null
                }
                // 自动化/编队状态同步 (对方视角实时可见; 字段只在 true 时输出 → 缺失时显式清除本地)
                var stateVisualChanged = false
                obj["automated"]?.jsonPrimitive?.contentOrNull?.let {
                    val v = it == "true"
                    if (unit.automated != v) {
                        unit.automated = v
                        stateVisualChanged = true
                    }
                } ?: run {
                    if (unit.automated) {
                        unit.automated = false
                        stateVisualChanged = true
                    }
                }
                obj["escorting"]?.jsonPrimitive?.contentOrNull?.let {
                    val v = it == "true"
                    if (unit.isEscorting() != v) {
                        if (v) unit.startEscorting() else unit.stopEscorting()
                        stateVisualChanged = true
                    }
                } ?: run {
                    if (unit.isEscorting()) {
                        unit.stopEscorting()
                        stateVisualChanged = true
                    }
                }
                // 状态图标变化 (编队/自动工作标记) → 强制重绘地图, 图标立即消失/出现 (不等下一帧广播/重载)
                if (stateVisualChanged) {
                    worldScreen.shouldUpdate = true
                    unitsChanged = true
                }
                // 单位归属同步 (俘虏/赠予后立即变; 服务器权威)
                obj["civ"]?.jsonPrimitive?.contentOrNull?.let { civName ->
                    if (unit.civ.civName != civName) {
                        val owner = gameInfo.civilizations.firstOrNull { it.civName == civName }
                        if (owner != null) {
                            try {
                                unit.civ.units.removeUnit(unit, false)
                                unit.civ = owner
                                unit.owner = civName
                                owner.units.addUnit(unit, false)
                                worldScreen.shouldUpdate = true
                                unitsChanged = true
                            } catch (e: Exception) {
                            }
                        }
                    }
                }
                // 单位宗教同步 (传教士/大先知; 创立宗教后立即生效 — 客户端传教/清异端 action 依赖;
                // 字段只在非 null 时输出 → 缺失时显式清除 (可选字段成对处理))
                obj["religion"]?.jsonPrimitive?.contentOrNull?.let { relName ->
                    if (unit.religion != relName) {
                        unit.religion = relName
                        unitsChanged = true
                    }
                } ?: run {
                    if (unit.religion != null) {
                        unit.religion = null
                        unitsChanged = true
                    }
                }
                // 升级列表同步 (升级后立即显示; 服务器权威)
                obj["promotions"]?.jsonArray?.let { pArr ->
                    val promos = pArr.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
                    if (unit.promotions.promotions != promos) {
                        unit.promotions.promotions.clear()
                        unit.promotions.promotions.addAll(promos)
                        unitsChanged = true
                    }
                }
                // 经验值同步 (升级可点性/进度显示; 服务器权威)
                obj["xp"]?.jsonPrimitive?.intOrNull?.let {
                    if (unit.promotions.XP != it) {
                        unit.promotions.XP = it
                        unitsChanged = true
                    }
                }
                // 攻击次数同步 (纯拦截后本地恒 0 → 骑兵无限攻击的根因; 服务器权威)
                obj["attacksThisTurn"]?.jsonPrimitive?.intOrNull?.let {
                    if (unit.attacksThisTurn != it) {
                        unit.attacksThisTurn = it
                        unitsChanged = true
                    }
                }
                // 状态 map 同步 (行商 Stock Up 等: 本地纯拦截不执行 setStatus → 客户端永远不知道状态; 服务器权威)
                obj["statusMap"]?.jsonArray?.let { smArr ->
                    try {
                        val server = HashMap<String, Int>()
                        for (sm in smArr) {
                            val a = sm.jsonArray ?: continue
                            if (a.size < 2) continue
                            val name = a[0].jsonPrimitive.contentOrNull ?: continue
                            val turns = a[1].jsonPrimitive.intOrNull ?: continue
                            server[name] = turns
                        }
                        val local = HashMap<String, Int>()
                        for ((name, st) in unit.statusMap) local[name] = st.turnsLeft
                        if (local != server) {
                            unit.statusMap.clear()
                            for ((name, turns) in server) {
                                val st = com.unciv.logic.map.mapunit.MapUnit.UnitStatus(name, turns)
                                try { st.setTransients(unit) } catch (ignored: Exception) {}
                                unit.statusMap[name] = st
                            }
                            try { unit.updateUniques() } catch (ignored: Exception) {}
                            unitsChanged = true
                        }
                    } catch (e: Exception) {
                    }
                }
                // 能力使用次数同步 (<once> 等: 本地不记录 → 伟人可重复建圣地; 服务器权威)
                obj["abilityToTimesUsed"]?.jsonArray?.let { atArr ->
                    try {
                        val server = HashMap<String, Int>()
                        for (at in atArr) {
                            val a = at.jsonArray ?: continue
                            if (a.size < 2) continue
                            val name = a[0].jsonPrimitive.contentOrNull ?: continue
                            val times = a[1].jsonPrimitive.intOrNull ?: continue
                            server[name] = times
                        }
                        if (unit.abilityToTimesUsed != server) {
                            unit.abilityToTimesUsed.clear()
                            unit.abilityToTimesUsed.putAll(server)
                            unitsChanged = true
                        }
                    } catch (e: Exception) {
                    }
                }
            } catch (e: Exception) {
                // 单个单位同步失败不影响其他单位 (血量/位置同步不能被中断)
            }
        }
        // 城市状态变化 (买地/人口/掉血) → 打开的城市界面立即刷新 (不用退出重进)
        if (cityStateChanged) refreshOpenCityScreen()
        // 资源缓存刷新: 单位增删/建筑变化后立即重算战略资源占用 (军事单位需求/建筑消耗),
        // 否则 cache 只在 reload 重建 → 资源数显示下回合才对 ("消耗资源有问题"根因)
        if (unitsChanged || cityStateChanged) {
            try {
                val resCiv = worldScreen.viewingCiv
                if (resCiv != null && !resCiv.isSpectator()) resCiv.cache.updateCivResources()
            } catch (e: Exception) {
            }
        }
        // 每次状态都刷新我方全部单位视野 (必须先落位再计算): 我方移动后视野立即刷新;
        // 对方单位进入视野时, 我方即使没动也要触发相遇/视野更新
        refreshMyCivVisibility(worldScreen, gameInfo)
        // 服务器上已消失的单位 (被消灭/建城消耗) → 本地同步移除
        if (isFull) {
            // 全量模式: 扫描本地单位, 不在服务器列表中的 → 移除
            for (civ in gameInfo.civilizations) {
                for (unit in civ.units.getCivUnits()) {
                    if (unit.isDestroyed || !unit.hasTile()) continue
                    if (unit.id !in stateIds) {
                        try {
                            unit.destroy()
                            unitsChanged = true
                        } catch (e: Exception) {
                        }
                    }
                }
            }
        } else {
            // 增量模式: 只处理 removedUnits 列表
            for (ruElem in removedUnits) {
                val ruId = ruElem.jsonPrimitive.intOrNull ?: continue
                for (civ in gameInfo.civilizations) {
                    for (unit in civ.units.getCivUnits()) {
                        if (unit.isDestroyed || !unit.hasTile()) continue
                        if (unit.id == ruId) {
                            try {
                                unit.destroy()
                                unitsChanged = true
                            } catch (e: Exception) {
                            }
                            break
                        }
                    }
                }
            }
        }
        worldScreen.shouldUpdate = true
        // 本地“已查看”单位标记重应用: due 被广播覆盖回 true 后恢复 (下一个单位循环不被广播打断);
        // 服务器本次驱动了 due 变化的单位除外 (跳过回合切换必须生效)
        reapplyLocalDueSeen(gameInfo, serverDueChanged)
        // 帝国概览页 (Stats/Units/Politics) 实时刷新: 仅观看文明自身数据或单位实质状态变化时重建
        // (宗教页内容变化走 syncReligions 的 recreate; 这里不再因别人金币/外交变化频繁打断概览页)
        if ((civChanged || unitsChanged)
            && com.unciv.UncivGame.Current.screen is com.unciv.ui.screens.overviewscreen.EmpireOverviewScreen) {
            try {
                (com.unciv.UncivGame.Current.screen as com.unciv.ui.screens.overviewscreen.EmpireOverviewScreen).recreate()
            } catch (e: Exception) {
            }
        }
    }

    /** 改良状态同步: 服务器全量列表覆盖本地 (建造/维修/取消立即显示; 服务器权威) */
    private fun syncImprovements(gameInfo: GameInfo, improvements: List<JsonElement>) {
        try {
            val serverImps = HashMap<Pair<Int, Int>, Pair<String, Int>>()
            for (imp in improvements) {
                val a = imp.jsonArray ?: continue
                if (a.size < 4) continue
                val x = a[0].jsonPrimitive.intOrNull ?: continue
                val y = a[1].jsonPrimitive.intOrNull ?: continue
                val name = a[2].jsonPrimitive.contentOrNull ?: continue
                val turns = a[3].jsonPrimitive.intOrNull ?: continue
                serverImps[x to y] = name to turns
            }
            var changed = false
            for (tile in gameInfo.tileMap.values) {
                val key = (tile.position.x ?: continue) to (tile.position.y ?: continue)
                val server = serverImps[key]
                val localName = tile.improvementInProgress
                if (server == null) {
                    if (localName != null) {
                        tile.improvementQueue.clear()
                        changed = true
                    }
                } else if (localName != server.first || tile.turnsToImprovement != server.second) {
                    tile.improvementQueue.clear()
                    tile.improvementQueue.add(com.unciv.logic.map.tile.Tile.ImprovementQueueEntry(server.first, server.second))
                    changed = true
                }
            }
            if (changed) {
                worldScreenRef?.get()?.let { it.shouldUpdate = true }
            }
        } catch (e: Exception) {
        }
    }

    /** 道路状态同步 (含劫掠; 2026-08-21): 服务器权威 [x,y,roadStatus,pillaged] —
     *  纯道路地块劫掠后客户端看不到 → “道路无法劫掠”; 只有 roadStatus != None 的格子输出 */
    private fun syncRoads(gameInfo: GameInfo, roads: List<JsonElement>) {
        try {
            val server = HashMap<Pair<Int, Int>, Pair<String, Boolean>>()
            for (r in roads) {
                val a = r.jsonArray ?: continue
                if (a.size < 4) continue
                val x = a[0].jsonPrimitive.intOrNull ?: continue
                val y = a[1].jsonPrimitive.intOrNull ?: continue
                server[x to y] = (a[2].jsonPrimitive.contentOrNull ?: "") to (a[3].jsonPrimitive.contentOrNull == "true")
            }
            var changed = false
            for (tile in gameInfo.tileMap.values) {
                val key = (tile.position.x ?: continue) to (tile.position.y ?: continue)
                val s = server[key]
                val localStatus = tile.roadStatus.name
                val localPillaged = tile.roadIsPillaged
                if (s == null) {
                    if (localStatus != "None" || localPillaged) {
                        tile.roadStatus = com.unciv.logic.map.tile.RoadStatus.None
                        tile.roadIsPillaged = false
                        changed = true
                    }
                } else {
                    val wantStatus = s.first
                    val wantPillaged = s.second
                    if (localStatus != wantStatus || localPillaged != wantPillaged) {
                        try {
                            tile.roadStatus = com.unciv.logic.map.tile.RoadStatus.valueOf(wantStatus)
                        } catch (ignored: Exception) {
                            continue
                        }
                        tile.roadIsPillaged = wantPillaged
                        changed = true
                    }
                }
            }
            if (changed) {
                worldScreenRef?.get()?.let { it.shouldUpdate = true }
            }
        } catch (ignored: Exception) {
        }
    }

    /** 战败检测: 由 WorldScreen.update 的 isDefeated 分支弹失败界面 (VictoryScreen, 帧同步防重),
     *  界面按钮为“回到大厅” — 玩家自己点回大厅, 不自动踢出 (用户 2026-08-20 确认) */
    private fun checkDefeatedAndOfferSpectate() {
        // 不再自动踢出 / 不再转观战 — 失败界面由 update 循环弹 (victoryShownForFsGame 防重)
    }

    /** 切观战: 停旧连接 → 重建 WorldScreen(Spectator) → 新屏 init 自动重连 (join 后 fs_server 视观战者) */
    private fun switchToSpectatorView(spectator: Civilization) {
        try {
            val oldWs = worldScreenRef?.get() ?: return
            stop()
            // 观战视野 = 全图 (spectator updateViewableTiles 全图; 否则切过去黑屏)
            try { spectator.cache.updateViewableTiles() } catch (e: Exception) {}
            val newWs = WorldScreen(oldWs.gameInfo, oldWs.autoPlay, spectator)
            worldScreenRef = java.lang.ref.WeakReference(newWs)
            com.unciv.UncivGame.Current.replaceCurrentScreen(newWs)
        } catch (e: Exception) {
        }
    }

    /** 选信仰词条页面实时刷新: 他人占用词条后可用列表变化 → 重建页面 (被占词条立即灰掉)
     *  节流 300ms: 多人同时选/频繁广播时防止界面反复重建闪烁导致点不到 */    private var lastBeliefPickerRefreshAt = 0L
    private fun refreshBeliefPickers(worldScreen: WorldScreen) {
        val now = System.currentTimeMillis()
        if (now - lastBeliefPickerRefreshAt < 300) return
        lastBeliefPickerRefreshAt = now
        try {
            val game = com.unciv.UncivGame.Current
            val cur = game.screen
            when (cur) {
                is com.unciv.ui.screens.pickerscreens.PantheonPickerScreen ->
                    game.replaceCurrentScreen(
                        com.unciv.ui.screens.pickerscreens.PantheonPickerScreen(worldScreen.viewingCiv))
                is com.unciv.ui.screens.pickerscreens.ReligiousBeliefsPickerScreen -> {
                    // 与原版三个打开入口一致: 创立(pickIconAndName)/增强/自由词条
                    val rm = worldScreen.viewingCiv.religionManager
                    val beliefs = if (cur.pickIconAndName) rm.getBeliefsToChooseAtFounding()
                        else if (rm.religionState == com.unciv.logic.civilization.managers.ReligionState.EnhancingReligion)
                            rm.getBeliefsToChooseAtEnhancing()
                        else rm.freeBeliefsAsEnums()
                    game.replaceCurrentScreen(
                        com.unciv.ui.screens.pickerscreens.ReligiousBeliefsPickerScreen(
                            worldScreen.viewingCiv, beliefs, cur.pickIconAndName))
                }
                else -> {}
            }
        } catch (e: Exception) {
        }
    }

    /** 自己选完万神殿/教义 → 自动关闭选词条页面 (防页面残留误点报错; 政策 6k 同款教训, 循环关弹窗+页面) */
    private fun closeBeliefPickers() {
        try {
            var guard = 0
            while (guard++ < 3) {
                val cur = com.unciv.UncivGame.Current.screen
                if (cur is com.unciv.ui.screens.pickerscreens.PantheonPickerScreen ||
                    cur is com.unciv.ui.screens.pickerscreens.ReligiousBeliefsPickerScreen) {
                    com.unciv.UncivGame.Current.popScreen()
                } else break
            }
        } catch (e: Exception) {
        }
    }

    private fun closeGreatPersonPickers() {
        try {
            var guard = 0
            while (guard++ < 3) {
                val cur = com.unciv.UncivGame.Current.screen
                if (cur is com.unciv.ui.screens.pickerscreens.GreatPersonPickerScreen) {
                    com.unciv.UncivGame.Current.popScreen()
                } else break
            }
        } catch (e: Exception) {
        }
    }

    /** 地形变化同步: OneTimeChangeTerrain (Turn this tile into a [terrainName] tile) 服务器权威改地形,
     *  客户端应用 setBaseTerrain (自动 normalize+刷新 features/transients) + 视野/地图刷新。
     *  轻量段: 只有触发过转化的地块才输出 [x,y,baseTerrain,features,naturalWonder,improvement] */
    private fun syncTerrainChanges(gameInfo: GameInfo, terrainChanges: List<JsonElement>, worldScreen: WorldScreen) {
        try {
            if (terrainChanges.isEmpty()) return
            var changed = false
            for (tc in terrainChanges) {
                try {
                    val arr = tc.jsonArray ?: continue
                    if (arr.size < 4) continue
                    val x = arr[0].jsonPrimitive.contentOrNull?.toIntOrNull() ?: continue
                    val y = arr[1].jsonPrimitive.contentOrNull?.toIntOrNull() ?: continue
                    val baseTerrain = arr[2].jsonPrimitive.contentOrNull ?: continue
                    val features = arr[3].jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                    val naturalWonder = arr.getOrNull(4)?.jsonPrimitive?.contentOrNull?.takeIf { it != "null" }
                    val improvement = arr.getOrNull(5)?.jsonPrimitive?.contentOrNull?.takeIf { it != "null" }
                    val tile = gameInfo.tileMap.get(x, y) ?: continue
                    val terrain = gameInfo.ruleset.terrains[baseTerrain] ?: continue
                    if (tile.baseTerrain != baseTerrain) {
                        tile.setBaseTerrain(terrain)
                        changed = true
                    }
                    // 同步其余字段 (转化可能同时改 features/奇观/改良)
                    if (tile.terrainFeatures != features) {
                        tile.setTerrainFeatures(features)
                        changed = true
                    }
                    if (tile.naturalWonder != naturalWonder) {
                        tile.naturalWonder = naturalWonder
                        changed = true
                    }
                    if (tile.improvement != improvement) {
                        tile.improvement = improvement
                        changed = true
                    }
                } catch (e: Exception) {
                }
            }
            if (changed) {
                worldScreen.shouldUpdate = true
                try {
                    val civView = worldScreen.gameView.civView
                    for (tg in worldScreen.mapHolder.tileGroups.values) tg.update(civView)
                } catch (ignored: Exception) {}
            }
        } catch (e: Exception) {
        }
    }

    /** 宗教全量同步: 服务器 religions 列表覆盖本地 gameInfo.religions
     *  (万神殿/创立宗教后宗教页立即解锁并显示内容, 不等回合末重载; 服务器权威) */
    private fun syncReligions(gameInfo: GameInfo, religions: List<JsonElement>, worldScreen: WorldScreen) {
        try {
            if (religions.isEmpty()) return  // 服务器无宗教时不用清本地 (开局双方都是空, 广播也空)
            val server = religions.mapNotNull { rj ->
                try {
                    val obj = rj.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val foundingCiv = obj["foundingCiv"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val rel = com.unciv.models.Religion(name, gameInfo, foundingCiv)
                    rel.displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull
                    val beliefs = (obj["founder"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()) +
                        (obj["follower"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList())
                    rel.addBeliefs(beliefs.mapNotNull { gameInfo.ruleset.beliefs[it] })
                    rel.setTransients(gameInfo)
                    rel
                } catch (e: Exception) {
                    null
                }
            }.associateBy { it.name }
            // 内容同步: 宗教页按钮/详情读 civ.religionManager.religion (不是 gameInfo.religions)
            // → 每个文明只赋一个 religion: 正式宗教优先, 万神殿兜底 (原版 setTransients 同语义)。
            //   服务器 GameInfo.religions 是 HashMap, stateJson 数组顺序不定 — 万神殿对象与正式宗教对象
            //   foundingCivName 相同, 若按数组顺序逐个赋值, 后遍历的会覆盖先遍历的 (万神殿排在后面 →
            //   概览只显示万神殿 + 创始信条效果丢失, 且时好时坏取决于 HashMap 迭代顺序)。
            var religionContentChanged = false
            var ownReligionChanged = false
            val relsByCiv = HashMap<Civilization, MutableList<com.unciv.models.Religion>>()
            for (rel in server.values) {
                try {
                    // 匹配创立文明: civID 在联机局可能是 UUID/文明名/playerId 任意格式 (房主存档与服务器存档格式可能不同,
                    // 观战者下载的存档版本不同 → civID 精确匹配会漏 → 宗教不显示 (实际生效但概览看不到))
                    val fc = gameInfo.civilizations.firstOrNull {
                        it.civID == rel.foundingCivName || it.civName == rel.foundingCivName
                            || it.playerId == rel.foundingCivName
                    }
                    if (fc != null) relsByCiv.getOrPut(fc) { mutableListOf() }.add(rel)
                } catch (e3: Exception) {
                    // 单个宗教处理失败不中断其他宗教 (一个异常 → 后续宗教全部不显示)
                }
            }
            for ((fc, rels) in relsByCiv) {
                try {
                    // 正式宗教 (含创始信条) 优先; 只有万神殿时用万神殿 — 与 religionManager.setTransients 一致
                    val rel = rels.firstOrNull { it.isMajorReligion() } ?: rels.first()
                    val cur = fc.religionManager.religion
                    val curBeliefs = (cur?.getFounderBeliefsForSync().orEmpty() + cur?.getFollowerBeliefsForSync().orEmpty()).toSet()
                    val newBeliefs = (rel.getFounderBeliefsForSync() + rel.getFollowerBeliefsForSync()).toSet()
                    if (cur?.name != rel.name || curBeliefs != newBeliefs) {
                        fc.religionManager.religion = rel
                        religionContentChanged = true
                        if (fc.civID == worldScreen.viewingCiv.civID) ownReligionChanged = true
                    }
                } catch (e3: Exception) {
                    // 单个宗教处理失败不中断其他宗教
                }
            }
            if (gameInfo.religions.size != server.size || gameInfo.religions.keys.any { it !in server } || religionContentChanged) {
                gameInfo.religions.clear()
                gameInfo.religions.putAll(server)
                worldScreen.shouldUpdate = true
                // 选词条页面实时刷新: 仅自己宗教内容变化时重建 (别人创立/强化宗教不应闪我正打开的选词条弹窗 → "选不了")
                if (ownReligionChanged) refreshBeliefPickers(worldScreen)
                // 概览界面打开时刷新 (宗教页解锁/内容实时显示) — 在 religion 赋值之后, 重建才能读到内容
                try {
                    val cur = com.unciv.UncivGame.Current.screen
                    if (cur is com.unciv.ui.screens.overviewscreen.EmpireOverviewScreen) {
                        cur.recreate()
                    }
                } catch (e2: Exception) {
                }
            }
        } catch (e: Exception) {
        }
    }

    /** 已完成改良同步: 服务器全量列表覆盖本地 (改良建好后立即显示图标; 服务器权威) */
    private fun syncImprovementsDone(gameInfo: GameInfo, improvementsDone: List<JsonElement>) {
        try {
            val server = HashSet<Pair<Int, Int>>()
            val serverNames = HashMap<Pair<Int, Int>, String>()
            val serverPillaged = HashMap<Pair<Int, Int>, Boolean>()
            for (imp in improvementsDone) {
                val a = imp.jsonArray ?: continue
                if (a.size < 3) continue
                val x = a[0].jsonPrimitive.intOrNull ?: continue
                val y = a[1].jsonPrimitive.intOrNull ?: continue
                val name = a[2].jsonPrimitive.contentOrNull ?: continue
                server.add(x to y)
                serverNames[x to y] = name
                serverPillaged[x to y] = a[3]?.jsonPrimitive?.contentOrNull == "true"
            }
            var changed = false
            val affectedCities = HashSet<City>()
            for (tile in gameInfo.tileMap.values) {
                var tileChanged = false
                val key = (tile.position.x ?: continue) to (tile.position.y ?: continue)
                val localName = tile.improvement
                if (key in server) {
                    val want = serverNames[key]
                    if (localName != want) {
                        tile.improvement = want
                        tileChanged = true
                    }
                    // 劫掠状态同步 (劫掠后立即显示; 服务器权威)
                    val wantPillaged = serverPillaged[key] ?: false
                    if (tile.improvementIsPillaged != wantPillaged) {
                        tile.improvementIsPillaged = wantPillaged
                        tileChanged = true
                    }
                } else if (localName != null && localName.isNotEmpty()) {
                    tile.improvement = null
                    tile.improvementIsPillaged = false
                    tileChanged = true
                }
                if (tileChanged) {
                    changed = true
                    // 变化 tile 自身城市 + 相邻 1 格城市 (Colony 的 +50% 奇观产量作用于相邻格子,
                    // 奇观被哪个城工作就影响哪个城的统计)
                    if (tile.owningCity != null) affectedCities.add(tile.owningCity!!)
                    for (adj in tile.neighbors) {
                        if (adj.owningCity != null) affectedCities.add(adj.owningCity!!)
                    }
                }
            }
            if (changed) {
                // 产量缓存失效: 原版 setImprovement 会 cityStats.update() — 直接改字段不走该路径,
                // 否则 Colony 瞬间建造/劫掠修复后产量显示不刷新 (西班牙 +50% 奇观产量“下回合才生效”的根因)
                for (city in affectedCities) {
                    try {
                        city.cityStats.update()
                    } catch (e: Exception) {
                    }
                }
                worldScreenRef?.get()?.let { it.shouldUpdate = true }
                refreshOpenCityScreen()
            }
        } catch (e: Exception) {
        }
    }

    /** 蛮族营地同步: 服务器全量列表覆盖本地 (攻下营地后双方立即看到营地消失) */
    private fun syncEncampments(gameInfo: GameInfo, encampments: List<JsonElement>) {
        try {
            val serverCamps = encampments.mapNotNull { e ->
                val arr = e.jsonArray
                val x = arr.getOrNull(0)?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val y = arr.getOrNull(1)?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                com.unciv.logic.map.HexCoord(x, y)
            }.toSet()
            for (tile in gameInfo.tileMap.values) {
                if (!tile.isBarbarianEncampment()) continue
                if (tile.position !in serverCamps) {
                    try {
                        tile.removeImprovement()
                    } catch (e: Exception) {
                    }
                }
            }
            // 服务器有但本地没有 → 新刷的蛮族营地 (守卫单位由 units 同步创建; 全量对齐)
            for (pos in serverCamps) {
                val x = pos.x ?: continue
                val y = pos.y ?: continue
                val tile = gameInfo.tileMap.get(x, y) ?: continue
                if (!tile.isBarbarianEncampment()) {
                    try {
                        tile.improvement = com.unciv.Constants.barbarianEncampment
                    } catch (e: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            // 营地同步失败绝不能中断 applyState (否则后续 hp 等同步全被跳过)
        }
    }

    /** 其他文明信息同步 (概览界面): 已采用政策 + 时代 — 对方回合中选政策/进时代立即可见 */
    /** 外交关系变化 (新见面/战争/友谊/谴责) → 实时刷新打开的外交界面: 左侧关系圆圈变色/新文明出现 + 右侧菜单更新.
     *  贸易页打开时不重建右侧 (防止清空编辑中的报价), 只刷左侧圆圈. (2026-08-22 用户反馈"友谊同意后圆圈不实时变色") */
    private fun refreshOpenDiplomacyScreen() {
        try {
            val cur = com.unciv.UncivGame.Current.screen
            if (cur is com.unciv.ui.screens.diplomacyscreen.DiplomacyScreen) {
                cur.updateLeftSideTable(cur.selectedCivForRightSide)
                if (!cur.showingTradeTable) {
                    cur.selectedCivForRightSide?.let { cur.updateRightSide(it) }
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun syncCivInfo(gameInfo: GameInfo, civs: List<JsonElement>, viewingCivId: String): Boolean {
        var changed = false
        var ownChanged = false
        for (civJson in civs) {
            val obj = civJson.jsonObject
            val name = obj["civ"]?.jsonPrimitive?.contentOrNull ?: continue
            val civ = gameInfo.civilizations.firstOrNull { it.civName == name } ?: continue
            val before = changed
            try {
                // 已见面文明同步 (纯拦截下客户端不调 meet → 本地 diplomacy 缺游戏内新遇见的文明 →
                // 下方 getDiplomacyManager 全 null → 战争/友谊/谴责全不同步; 2026-08-22 用户反馈"友谊同意后外交界面不更新")
                obj["met"]?.jsonArray?.let { metArr ->
                    for (mElem in metArr) {
                        val metId = mElem.jsonPrimitive.contentOrNull ?: continue
                        if (civ.getDiplomacyManager(metId) == null) {
                            val other = gameInfo.civilizations.firstOrNull { it.civID == metId }
                            if (other != null) {
                                civ.diplomacy[metId] =
                                    com.unciv.logic.civilization.diplomacy.DiplomacyManager(civ, other)
                                changed = true
                                refreshOpenDiplomacyScreen()
                            }
                        }
                    }
                }
                // 战争状态 (服务器权威: 宣战/和平实时生效; 不在列表但本地是战争 → 已和解)
                val atWarWith = obj["atWarWith"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                for (other in gameInfo.civilizations) {
                    if (other.civID == civ.civID) continue
                    val dm = civ.getDiplomacyManager(other) ?: continue
                    val atWar = atWarWith.contains(other.civID)
                    val isWar = dm.diplomaticStatus == DiplomaticStatus.War
                    if (atWar && !isWar) {
                        dm.diplomaticStatus = DiplomaticStatus.War
                        changed = true
                        refreshOpenDiplomacyScreen()
                    } else if (!atWar && isWar) {
                        dm.diplomaticStatus = DiplomaticStatus.Peace
                        changed = true
                        refreshOpenDiplomacyScreen()
                    }
                }
                // 友谊宣言/谴责 flag 同步 (服务器权威: 界面按钮显示/消失)
                val doF = obj["doF"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                val denounced = obj["denounced"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                for (other in gameInfo.civilizations) {
                    if (other.civID == civ.civID) continue
                    val dm = civ.getDiplomacyManager(other) ?: continue
                    val hasDoF = dm.hasFlag(com.unciv.logic.civilization.diplomacy.DiplomacyFlags.DeclarationOfFriendship)
                    val hasDenounced = dm.hasFlag(com.unciv.logic.civilization.diplomacy.DiplomacyFlags.Denunciation)
                    if (doF.contains(other.civID) && !hasDoF) {
                        dm.setFlag(com.unciv.logic.civilization.diplomacy.DiplomacyFlags.DeclarationOfFriendship, 30)
                        changed = true
                        refreshOpenDiplomacyScreen()
                    } else if (!doF.contains(other.civID) && hasDoF) {
                        dm.removeFlag(com.unciv.logic.civilization.diplomacy.DiplomacyFlags.DeclarationOfFriendship)
                        changed = true
                        refreshOpenDiplomacyScreen()
                    }
                    if (denounced.contains(other.civID) && !hasDenounced) {
                        dm.setFlag(com.unciv.logic.civilization.diplomacy.DiplomacyFlags.Denunciation, 30)
                        changed = true
                        refreshOpenDiplomacyScreen()
                    } else if (!denounced.contains(other.civID) && hasDenounced) {
                        dm.removeFlag(com.unciv.logic.civilization.diplomacy.DiplomacyFlags.Denunciation)
                        changed = true
                        refreshOpenDiplomacyScreen()
                    }
                }
                // 活跃贸易同步 (服务器权威: 成交后立即出现在双方贸易列表, 不等回合重载 — 2026-08-22 用户反馈"本回合的贸易下回合才生效")
                obj["trades"]?.jsonArray?.let { tradesArr ->
                    try {
                        val serverTrades = HashMap<String, List<Trade>>()
                        for (t in tradesArr) {
                            val a = t.jsonArray ?: continue
                            if (a.size < 3) continue
                            val otherId = a[0].jsonPrimitive.contentOrNull ?: continue
                            val trade = Trade()
                            for (sideIdx in 1..2) {
                                val sideList = if (sideIdx == 1) trade.ourOffers else trade.theirOffers
                                for (offerElem in a[sideIdx].jsonArray ?: continue) {
                                    val oa = offerElem.jsonArray ?: continue
                                    if (oa.size < 4) continue
                                    val type = try {
                                        TradeOfferType.valueOf(oa[0].jsonPrimitive.contentOrNull ?: continue)
                                    } catch (e: Exception) {
                                        continue
                                    }
                                    val name = oa[1].jsonPrimitive.contentOrNull ?: ""
                                    val amount = oa[2].jsonPrimitive.intOrNull ?: 1
                                    val duration = oa[3].jsonPrimitive.intOrNull ?: -1
                                    sideList.add(TradeOffer(name, type, amount, duration))
                                }
                            }
                            serverTrades[otherId] = (serverTrades[otherId] ?: emptyList()) + trade
                        }
                        fun sameOffer(l: TradeOffer, s: TradeOffer) =
                            l.type == s.type && l.name == s.name && l.amount == s.amount && l.duration == s.duration
                        fun sameTrade(l: Trade, s: Trade) =
                            l.ourOffers.size == s.ourOffers.size && l.theirOffers.size == s.theirOffers.size
                                && l.ourOffers.zip(s.ourOffers).all { (lo, so) -> sameOffer(lo, so) }
                                && l.theirOffers.zip(s.theirOffers).all { (lo, so) -> sameOffer(lo, so) }
                        var tradesChanged = false
                        for ((otherId, serverList) in serverTrades) {
                            val other = gameInfo.civilizations.firstOrNull { it.civID == otherId } ?: continue
                            val dm = civ.getDiplomacyManager(other) ?: continue
                            val local = dm.trades
                            val same = local.size == serverList.size && local.zip(serverList).all { (l, s) -> sameTrade(l, s) }
                            if (!same) {
                                dm.trades.clear()
                                dm.trades.addAll(serverList)
                                tradesChanged = true
                            }
                        }
                        // 本地有但服务器没有的贸易 (被宣战/到期清除等) → 移除
                        for (other in gameInfo.civilizations) {
                            if (other.civID == civ.civID) continue
                            val dm = civ.getDiplomacyManager(other) ?: continue
                            if (dm.trades.isNotEmpty() && !serverTrades.containsKey(other.civID)) {
                                dm.trades.clear()
                                tradesChanged = true
                            }
                        }
                        if (tradesChanged) {
                            changed = true
                            // 贸易效果即时生效 (纯拦截下客户端不执行 acceptTrade, 2026-08-22 用户反馈):
                            // ①开放边境缓存 (updateHasOpenBorders) ②资源供应缓存 (奢侈/战略资源快乐即时显示)
                            try {
                                for (other in gameInfo.civilizations) {
                                    if (other.civID == civ.civID) continue
                                    val dm = civ.getDiplomacyManager(other) ?: continue
                                    dm.updateHasOpenBorders()
                                }
                                civ.cache.updateCivResources()
                            } catch (ignored: Exception) {
                            }
                        }
                    } catch (e: Exception) {
                    }
                }
                // 金币 (自己文明的, 服务器权威)
                if (civ.civID == viewingCivId) {
                    obj["gold"]?.jsonPrimitive?.intOrNull?.let {
                        if (civ.gold != it) {
                            civ.addGold(it - civ.gold)
                            changed = true
                        }
                    }
                    // 信仰 (万神殿/买伟人 UI; 时代奖励 Gain Faith 即时生效; 服务器权威 —
                    // 注意真实信仰池在 religionManager.storedFaith, 不是 statsForNextTurn.faith)
                    obj["faith"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()?.let {
                        val cur = civ.religionManager.storedFaith
                        if (cur != it.toInt()) {
                            civ.religionManager.setStoredFaithForSync(it.toInt())
                            changed = true
                            // 购买/信仰变更 → 打开的城市界面立即刷新 (买伟人后信仰数字实时变)
                            if (civ.civID == viewingCivId) cityStateChanged = true
                        }
                    }
                    // 购买涨价计数同步 (BuyUnits/BuildingsIncreasingCost 词条: 每次购买价格+X):
                    // 纯拦截下本地不执行购买 → 本地计数不涨 → 价格显示不变 (2026-08-21)
                    obj["boughtInc"]?.jsonArray?.let { biArr ->
                        try {
                            val server = HashMap<String, Int>()
                            for (e in biArr) {
                                val a = e.jsonArray ?: continue
                                if (a.size < 2) continue
                                val nm = a[0].jsonPrimitive.contentOrNull ?: continue
                                server[nm] = a[1].jsonPrimitive.intOrNull ?: 0
                            }
                            val local = civ.civConstructions.boughtItemsWithIncreasingPrice
                            if (local.toMap() != server) {
                                local.clear()
                                local.putAll(server)
                                changed = true
                                if (civ.civID == viewingCivId) cityStateChanged = true
                            }
                        } catch (ignored: Exception) {
                        }
                    }
                    // 黄金时代同步 (开启后客户端立即生效/显示; 服务器权威 — 不同步则下回合才看到加成)
                    obj["goldenAgeTurns"]?.jsonPrimitive?.intOrNull?.let { gaTurns ->
                        if (civ.goldenAges.turnsLeftForCurrentGoldenAge != gaTurns) {
                            civ.goldenAges.setTurnsLeftForSync(gaTurns)
                            changed = true
                            if (civ.civID == viewingCivId) cityStateChanged = true
                        }
                    }
                    // 快乐储备同步 (顶栏黄金时代进度条 (stored/required); 服务器权威)
                    obj["goldenAgeHappiness"]?.jsonPrimitive?.intOrNull?.let { gaHappy ->
                        if (civ.goldenAges.storedHappiness != gaHappy) {
                            civ.goldenAges.storedHappiness = gaHappy
                            changed = true
                        }
                    }
                    // 免费科技计数同步 (大图书馆等; 服务器权威 — 不同步则客户端 stale 值可反复触发免费科技)
                    obj["freeTechs"]?.jsonPrimitive?.intOrNull?.let { ft ->
                        if (civ.tech.freeTechs != ft) {
                            civ.tech.freeTechs = ft
                            changed = true
                        }
                    }
                    // 免费伟人计数同步 (客户端据此弹 GreatPersonPickerScreen; 服务器权威 — 不同步则不弹)
                    obj["freeGreatPeople"]?.jsonPrimitive?.intOrNull?.let { fgp ->
                        if (civ.greatPeople.freeGreatPeople != fgp) {
                            civ.greatPeople.freeGreatPeople = fgp
                            changed = true
                            // 选完伟人后计数归零 → 自动关选择页 (等服务器广播回来再关, 防重复弹出)
                            if (civ.civID == viewingCivId && fgp <= 0) closeGreatPersonPickers()
                        }
                    }
                    // 创立宗教时是否附带万神殿选择 (无万神殿创立 → 创立界面出现万神殿词条; 服务器权威)
                    obj["choosePantheon"]?.jsonPrimitive?.contentOrNull?.let { cp ->
                        val v = cp == "true"
                        if (civ.religionManager.shouldChoosePantheonBeliefForSync() != v) {
                            civ.religionManager.setShouldChoosePantheonBeliefForSync(v)
                            changed = true
                        }
                    }
                    // 库存资源同步 (行商容量/马里物价/战争厌恶度/Seed 等 stockpile 机制;
                    // 纯拦截后本地恒 0 → "Only available <when number of [X]>" 条件全错)
                    obj["stockpiles"]?.jsonArray?.let { spArr ->
                        try {
                            val server = HashMap<String, Int>()
                            for (sp in spArr) {
                                val a = sp.jsonArray ?: continue
                                if (a.size < 2) continue
                                val name = a[0].jsonPrimitive.contentOrNull ?: continue
                                val value = a[1].jsonPrimitive.intOrNull ?: continue
                                server[name] = value
                            }
                            val local = HashMap<String, Int>()
                            for ((k, v) in civ.resourceStockpiles) if (v != 0) local[k] = v
                            if (local != server) {
                                civ.resourceStockpiles.clear()
                                for ((k, v) in server) civ.resourceStockpiles[k] = v
                                changed = true
                            }
                        } catch (e: Exception) {
                        }
                    }
                    // civ 级 flags 倒计时同步 (RecentlyBullied / TurnsTillNextDiplomaticVote /
                    // ShowDiplomaticVotingResults — 城邦进贡显示/世界会议投票流程; 服务器权威)
                    obj["flags"]?.jsonArray?.let { fArr ->
                        try {
                            val server = HashMap<String, Int>()
                            for (f in fArr) {
                                val a = f.jsonArray ?: continue
                                if (a.size < 2) continue
                                val name = a[0].jsonPrimitive.contentOrNull ?: continue
                                val turns = a[1].jsonPrimitive.intOrNull ?: continue
                                server[name] = turns
                            }
                            val local = civ.flagsCountdown
                            val localDirty = local.size != server.size
                                    || local.entries.any { (k, v) -> server[k] != v }
                            if (localDirty) {
                                local.clear()
                                local.putAll(server)
                                changed = true
                            }
                        } catch (e: Exception) {
                        }
                    }
                    // 文明级禁用建筑同步 (右键菜单"禁用全部城市"; 服务器权威)
                    obj["disabledConstructions"]?.jsonArray?.let { dcArr ->
                        try {
                            val server = dcArr.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
                            val local = civ.disabledCityConstructions
                            if (local != server) {
                                local.clear()
                                local.addAll(server)
                                changed = true
                            }
                        } catch (e: Exception) {
                        }
                    }
                    // 文明级临时词条同步 (事件"for [X] turns"加成如孙武+1移动力/军队加成; 服务器权威 —
                    // 不同步则客户端单位移动力/战力显示不变, 用户感知"不生效")
                    obj["tempUniques"]?.jsonArray?.let { tuArr ->
                        try {
                            val server = HashMap<String, Int>()
                            for (tu in tuArr) {
                                val a = tu.jsonArray ?: continue
                                if (a.size < 2) continue
                                val text = a[0].jsonPrimitive.contentOrNull ?: continue
                                val turns = a[1].jsonPrimitive.intOrNull ?: continue
                                server[text] = turns
                            }
                            val local = civ.temporaryUniques
                            val localDirty = local.size != server.size
                                    || local.any { server[it.unique] != it.turnsLeft }
                            if (localDirty) {
                                local.clear()
                                for ((text, turns) in server) {
                                    local.add(com.unciv.models.ruleset.unique.TemporaryUnique().apply {
                                        unique = text
                                        turnsLeft = turns
                                    })
                                }
                                changed = true
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
                // 本回合已发出的贸易申请数 (同文明每回合 3 次; 服务器权威 — 驱动贸易按钮灰显)
                val tradeSent = obj["tradeSent"]?.jsonArray?.mapNotNull { el ->
                    val arr = el.jsonArray ?: return@mapNotNull null
                    if (arr.size < 2) return@mapNotNull null
                    val targetId = arr[0].jsonPrimitive.contentOrNull ?: return@mapNotNull null
                    val count = arr[1].jsonPrimitive.intOrNull ?: return@mapNotNull null
                    targetId to count
                }?.toMap() ?: emptyMap()
                for ((targetId, count) in tradeSent) {
                    val key = civ.civID + "|" + targetId
                    if (tradeSentCache[key] != count) {
                        tradeSentCache[key] = count
                        changed = true
                    }
                }
                // 清理已不在列表的键 (回合重置后计数归零)
                val prefix = civ.civID + "|"
                val keys = tradeSentCache.keys.filter { it.startsWith(prefix) && it.substring(prefix.length) !in tradeSent }
                if (keys.isNotEmpty()) {
                    for (k in keys) tradeSentCache.remove(k)
                    changed = true
                }
                // 政策 (只读同步, 不动本地策略状态机)
                val policies = obj["policies"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                val adopted = civ.policies.getAdoptedPolicies()
                val wanted = policies.toSet()
                if (adopted != wanted) {
                    adopted.clear()
                    adopted.addAll(wanted)
                    changed = true
                    // 重建 policyUniques 缓存: 只同步列表不重建 → 战斗/产量计算
                    // (civ.getMatchingUniques → policies.policyUniques) 用旧 uniques →
                    // 政策效果 (Honor 对蛮族 +33% 力量/沿海 +2 产能等) 下回合才生效
                    try {
                        civ.policies.rebuildPolicyUniquesFromAdopted()
                    } catch (e: Exception) {
                    }
                    // 政策变化 → 重算本文明城市产出 (政策加成如 Piety 开门 [+1 Culture, +1 Faith] from every [Shrine]
                    // 影响城市面板显示; 只同步列表不重算则城市界面仍显示旧产出)
                    try {
                        for (c in civ.cities) c.cityStats.update()
                    } catch (e: Exception) {
                    }
                    // 政策变化 → 重算文明每回合产出 (顶栏金币/科技/文化立即更新 — 2026-08-23 用户反馈"政策不立即生效")
                    try {
                        civ.updateStatsForNextTurn()
                    } catch (e: Exception) {
                    }
                    // 政策变化也刷新打开的城市界面 (城市面板统计行实时更新)
                    if (civ.civID == viewingCivId) cityStateChanged = true
                }
                // 时代
                val eraName = obj["era"]?.jsonPrimitive?.contentOrNull
                if (eraName != null && civ.tech.era.name != eraName) {
                    val era = gameInfo.ruleset.eras[eraName]
                    if (era != null) {
                        civ.tech.era = era
                        changed = true
                    }
                }
                // 研究队列同步 (选科技立即显示; 服务器权威)
                obj["techs"]?.jsonArray?.let { tArr ->
                    val techs = tArr.mapNotNull { it.jsonPrimitive.contentOrNull }
                    if (civ.tech.techsToResearch != techs) {
                        civ.tech.techsToResearch.clear()
                        civ.tech.techsToResearch.addAll(techs)
                        changed = true
                    }
                }
                // 文化/免费政策同步 (政策可点性判断; 服务器权威)
                obj["culture"]?.jsonPrimitive?.intOrNull?.let {
                    if (civ.policies.storedCulture != it) {
                        civ.policies.storedCulture = it
                        changed = true
                    }
                }
                obj["freePolicies"]?.jsonPrimitive?.intOrNull?.let {
                    if (civ.policies.freePolicies != it) {
                        civ.policies.freePolicies = it
                        changed = true
                    }
                }
                obj["policyCount"]?.jsonPrimitive?.intOrNull?.let {
                    if (civ.policies.getNumberOfAdoptedPoliciesForSync() != it) {
                        civ.policies.setNumberOfAdoptedPoliciesForSync(it)
                        changed = true
                    }
                }
                // 宗教状态同步 (万神殿/创立/强化判断; 服务器权威)
                obj["religionState"]?.jsonPrimitive?.contentOrNull?.let { rs ->
                    try {
                        val state = com.unciv.logic.civilization.managers.ReligionState.valueOf(rs)
                        if (civ.religionManager.religionState != state) {
                            civ.religionManager.setReligionStateForSync(state)
                            changed = true
                            // 自己选完万神殿/教义 → 自动关闭选词条页面 (防页面残留误点报错; 政策 6k 同款教训)
                            if (civ.civID == viewingCivId) closeBeliefPickers()
                        }
                    } catch (e: Exception) {
                    }
                }
                // 自由词条同步 (宗教改革按钮可点性 = hasFreeBeliefs; 纯拦截本地不清 → 选完还能再点)
                obj["freeBeliefs"]?.jsonArray?.let { fbArr ->
                    try {
                        val server = fbArr.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
                        val local = civ.religionManager.freeBeliefs.keys.toSet()
                        if (local != server) {
                            civ.religionManager.freeBeliefs.clear()
                            for (fb in server) civ.religionManager.freeBeliefs[fb] = 1
                            changed = true
                            if (civ.civID == viewingCivId) closeBeliefPickers()
                        }
                    } catch (e: Exception) {
                    }
                }
                // 已研究科技同步 (免费科技/科技树显示; 服务器权威)
                obj["researched"]?.jsonArray?.let { rArr ->
                    try {
                        val server = rArr.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
                        val local = civ.tech.techsResearched
                        if (local != server) {
                            local.clear()
                            local.addAll(server)
                            changed = true
                        }
                    } catch (e: Exception) {
                    }
                }
                // 外交 flag 同步 (和平条约倒计时/宣战按钮判断; 服务器权威)
                obj["diploFlags"]?.jsonArray?.let { dfArr ->
                    var dfChanged = false
                    try {
                        for (df in dfArr) {
                            val a = df.jsonArray ?: continue
                            if (a.size < 2) continue
                            val otherName = a[0].jsonPrimitive.contentOrNull ?: continue
                            val other = gameInfo.civilizations.firstOrNull { it.civID == otherName || it.civName == otherName } ?: continue
                            val dm = civ.getDiplomacyManager(other) ?: continue
                            val serverFlags = HashMap<String, Int>()
                            for (f in a[1].jsonArray ?: continue) {
                                val fa = f.jsonArray ?: continue
                                if (fa.size < 2) continue
                                val fname = fa[0].jsonPrimitive.contentOrNull ?: continue
                                val fval = fa[1].jsonPrimitive.intOrNull ?: continue
                                serverFlags[fname] = fval
                            }
                            for (flag in com.unciv.logic.civilization.diplomacy.DiplomacyFlags.values()) {
                                val want = serverFlags[flag.name] ?: 0
                                val have = dm.getFlag(flag)
                                if (want > 0 && have <= 0) {
                                    dm.setFlag(flag, want)
                                    dfChanged = true
                                } else if (want <= 0 && have > 0) {
                                    dm.removeFlag(flag)
                                    dfChanged = true
                                }
                            }
                        }
                    } catch (e: Exception) {
                    }
                    if (dfChanged) {
                        changed = true
                        // 外交界面打开时刷新 (接受大使馆/flag 变化实时显示, 不用重开/等回合重载)
                        try {
                            val cur = com.unciv.UncivGame.Current.screen
                            if (cur is com.unciv.ui.screens.diplomacyscreen.DiplomacyScreen) {
                                cur.recreate()
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
                // 城邦 influence 同步 (城邦面板/外交界面显示; 服务器权威 — 原始值, 含战争修正的读取用 getInfluence)
                // influence 存在**城邦侧** manager: 服务器只对城邦文明输出 [玩家civID, 值], 这里 civ 必须是城邦
                obj["influence"]?.jsonArray?.let { infArr ->
                    try {
                        if (!civ.isCityState) return@let
                        for (inf in infArr) {
                            val a = inf.jsonArray ?: continue
                            if (a.size < 2) continue
                            val playerName = a[0].jsonPrimitive.contentOrNull ?: continue
                            val player = gameInfo.civilizations.firstOrNull { it.civID == playerName || it.civName == playerName } ?: continue
                            if (player.isCityState) continue
                            val value = a[1].jsonPrimitive.contentOrNull?.toFloatOrNull() ?: continue
                            val dm = civ.getDiplomacyManager(player) ?: continue
                            if (dm.getInfluenceForSync() != value) {
                                dm.setInfluenceWithoutSideEffects(value)
                                changed = true
                                // 外交界面打开时刷新 (城邦面板 influence 实时变化, 不用重开)
                                try {
                                    val cur = com.unciv.UncivGame.Current.screen
                                    if (cur is com.unciv.ui.screens.diplomacyscreen.DiplomacyScreen) {
                                        cur.recreate()
                                    }
                                } catch (e: Exception) {
                                }
                            }
                        }
                    } catch (e: Exception) {
                    }
                }
                // 科技研究进度同步 (剩余回合数实时变; 服务器权威)
                obj["research"]?.jsonArray?.let { rArr ->
                    try {
                        val server = HashMap<String, Int>()
                        for (r in rArr) {
                            val a = r.jsonArray ?: continue
                            if (a.size < 2) continue
                            val name = a[0].jsonPrimitive.contentOrNull ?: continue
                            val amount = a[1].jsonPrimitive.intOrNull ?: continue
                            server[name] = amount
                        }
                        val local = civ.tech.techsInProgress
                        if (local != server) {
                            local.clear()
                            local.putAll(server)
                            changed = true
                        }
                    } catch (e: Exception) {
                    }
                }
            } catch (e: Exception) {
            }
            // 仅统计观看文明自身的变化 → 概览页 recreate 不被别人操作频繁打断
            if (civ.civID == viewingCivId && changed != before) ownChanged = true
        }
        return ownChanged
    }

    /** 服务器城市 → 本地创建/对齐 (建城后立即在地图上出现, 不等回合末重载) */
    private fun syncCities(worldScreen: WorldScreen, cities: List<JsonElement>, isFull: Boolean = true, removedCities: List<JsonElement> = emptyList()) {
        val gameInfo = worldScreen.gameInfo ?: return
        val serverCityIds = HashSet<String>()
        for (cityJson in cities) {
            val obj = cityJson.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
            serverCityIds.add(id)
            val existing = findCity(gameInfo, id)
            val city = if (existing != null) {
                // 归属迁移 (城市被征服/易主回合内实时: 换旗子 + 列表调整; 地块由 syncOwnedTiles 对齐)
                obj["civ"]?.jsonPrimitive?.contentOrNull?.let { civName ->
                    if (civName != existing.civ.civName) {
                        val newCiv = gameInfo.civilizations.firstOrNull { it.civName == civName }
                        if (newCiv != null) {
                            try {
                                existing.civ.cities = existing.civ.cities - existing
                                existing.civ = newCiv
                                newCiv.cities = newCiv.cities.plusElement(existing)
                                cityStateChanged = true
                                worldScreen.shouldUpdate = true
                                // 城市被攻占/易主 → ①清除对它的选中 (否则 updateSelectedCiv 会把视角切到攻占者,
                                // "变成其他玩家的视角" 2026-08-21) ②若正打开该城市界面 → 关闭回世界屏
                                try {
                                    val wsSel = worldScreen.bottomUnitTable.selectedCity
                                    if (wsSel != null && wsSel.city == existing) {
                                        worldScreen.bottomUnitTable.selectUnit(null)
                                    }
                                } catch (ignored2: Exception) {
                                }
                                try {
                                    val cur = com.unciv.UncivGame.Current.screen
                                    if (cur is com.unciv.ui.screens.cityscreen.CityScreen
                                        && cur.cityView.city == existing) {
                                        com.unciv.UncivGame.Current.popScreen()
                                    }
                                } catch (ignored3: Exception) {
                                }
                            } catch (e: Exception) {
                            }
                        }
                    }
                }
                // 已有城市: 同步名字 (改名后立即显示; 服务器权威)
                obj["name"]?.jsonPrimitive?.contentOrNull?.let {
                    if (existing.name != it) {
                        existing.name = it
                        cityStateChanged = true
                        worldScreen.shouldUpdate = true
                    }
                }
                // 已有城市: 同步 hp (被攻击掉血回合中实时可见)
                obj["hp"]?.jsonPrimitive?.intOrNull?.let {
                    if (existing.health != it) {
                        existing.health = it
                        cityStateChanged = true
                        worldScreen.shouldUpdate = true
                    }
                }
                // 人口同步 (增长/损失实时显示)
                obj["pop"]?.jsonPrimitive?.intOrNull?.let {
                    if (existing.population.population != it) {
                        try {
                            existing.population.setPopulation(it)
                            cityStateChanged = true
                            worldScreen.shouldUpdate = true
                        } catch (e: Exception) {
                        }
                    }
                }
                // 食物储备同步 (人口增长进度条实时变; 服务器权威)
                obj["food"]?.jsonPrimitive?.intOrNull?.let {
                    if (existing.population.foodStored != it) {
                        existing.population.foodStored = it
                        cityStateChanged = true
                        worldScreen.shouldUpdate = true
                    }
                }
                // 城市炮击次数同步 (攻击后立即禁可轰炸高亮/按钮; 服务器权威 — 纯拦截后本地不执行,
                // attackedThisTurn 不广播则本地恒 false → 无限城炮)
                obj["attackedThisTurn"]?.jsonPrimitive?.contentOrNull?.let {
                    val v = it == "true"
                    if (existing.attackedThisTurn != v) {
                        existing.attackedThisTurn = v
                        cityStateChanged = true
                        worldScreen.shouldUpdate = true
                    }
                }
                // 生产队列同步 (建造/调整立即显示; 服务器权威 — 纯拦截后本地不执行, 必须靠广播同步)
                obj["queue"]?.jsonArray?.let { qArr ->
                    val queue = qArr.mapNotNull { it.jsonPrimitive.contentOrNull }
                    if (existing.cityConstructions.constructionQueue != queue) {
                        try {
                            existing.cityConstructions.constructionQueue.clear()
                            existing.cityConstructions.constructionQueue.addAll(queue)
                            cityStateChanged = true
                            worldScreen.shouldUpdate = true
                        } catch (e: Exception) {
                        }
                    }
                }
                // 生产进度同步 (剩余回合数实时变; 服务器权威 — 不依赖回合末重载)
                obj["inProgress"]?.jsonArray?.let { ipArr ->
                    try {
                        val server = HashMap<String, Int>()
                        for (ip in ipArr) {
                            val a = ip.jsonArray ?: continue
                            if (a.size < 2) continue
                            val name = a[0].jsonPrimitive.contentOrNull ?: continue
                            val amount = a[1].jsonPrimitive.intOrNull ?: continue
                            server[name] = amount
                        }
                        val local = existing.cityConstructions.inProgressConstructions
                        var ipChanged = false
                        if (local != server) {
                            local.clear()
                            local.putAll(server)
                            ipChanged = true
                        }
                        if (ipChanged) {
                            cityStateChanged = true
                            worldScreen.shouldUpdate = true
                        }
                    } catch (e: Exception) {
                    }
                }
                // 已建建筑列表同步 (卖建筑/完工后城市界面立即显示; 服务器权威)
                obj["built"]?.jsonArray?.let { bArr ->
                    try {
                        val server = bArr.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
                        val local = existing.cityConstructions.builtBuildings
                        if (local != server) {
                            local.clear()
                            local.addAll(server)
                            cityStateChanged = true
                            worldScreen.shouldUpdate = true
                            // 重建建筑对象缓存: 界面建筑列表读 builtBuildingObjects (缓存),
                            // 只改 HashSet 界面不更新 (购买/卖建筑"下回合才看到"根因)
                            try { existing.cityConstructions.rebuildBuiltBuildingsFromSync() } catch (ignored: Exception) {}
                            // 建筑变化 → 重算城市产出 (新建专业建筑/奇观效果立即生效, 不等下回合; 修复"建造完成后下回合才显示")
                            try { existing.cityStats.update() } catch (ignored: Exception) {}
                            // 建筑被谁建造 → 影响"if [X] is constructed by anybody" 条件 (特殊伟人项目等互斥选项) → 刷新打开的事件弹窗
                            refreshOpenEventPopups(worldScreen)
                        }
                    } catch (e: Exception) {
                    }
                }
                // 公民工作重心同步 (城市界面-管理公民; 服务器权威 — 纯拦截后本地不执行, 不广播则重开城市才恢复)
                obj["focus"]?.jsonPrimitive?.contentOrNull?.let { focusName ->
                    try {
                        val focus = com.unciv.logic.city.CityFocus.entries.firstOrNull { it.name == focusName }
                        if (focus != null && existing.getCityFocus() != focus) {
                            existing.setCityFocus(focus)
                            cityStateChanged = true
                            worldScreen.shouldUpdate = true
                        }
                    } catch (e: Exception) {
                    }
                }
                // 城市主流宗教同步 (传教/创立后客户端立即显示; 服务器权威 — 不同步则下回合重载才变)
                if (obj["religion"] != null) {
                    try {
                        val want = obj["religion"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() && it != "null" }
                        val current = existing.religion.getMajorityReligionName()
                        if (current != want) {
                            existing.religion.setPressureForSync(want)
                            cityStateChanged = true
                            worldScreen.shouldUpdate = true
                        }
                    } catch (e: Exception) {
                    }
                }
                // 城市 flags 倒计时同步 (We Love The King Day / Resistance / ResourceDemand — 城市界面横幅; 服务器权威)
                obj["flags"]?.jsonArray?.let { fArr ->
                    try {
                        val server = HashMap<String, Int>()
                        for (f in fArr) {
                            val a = f.jsonArray ?: continue
                            if (a.size < 2) continue
                            val name = a[0].jsonPrimitive.contentOrNull ?: continue
                            val turns = a[1].jsonPrimitive.intOrNull ?: continue
                            server[name] = turns
                        }
                        val local = existing.flagsCountdown
                        if (local != server) {
                            local.clear()
                            local.putAll(server)
                            cityStateChanged = true
                            worldScreen.shouldUpdate = true
                            // WeLoveTheKing/Resistance 影响城市产出 → 重算
                            try { existing.cityStats.update() } catch (ignored: Exception) {}
                        }
                    } catch (e: Exception) {
                    }
                }
                // 城市状态标记同步 (傀儡/烧城/停止增长/首都/征服/手动专家/需求资源 — 城市界面按钮与横幅; 服务器权威)
                obj["demandedResource"]?.jsonPrimitive?.contentOrNull?.let {
                    if (existing.demandedResource != it) {
                        existing.demandedResource = it
                        cityStateChanged = true
                        worldScreen.shouldUpdate = true
                    }
                }
                var cityFlagsChanged = false
                obj["puppet"]?.jsonPrimitive?.contentOrNull?.let {
                    val v = it == "true"
                    if (existing.isPuppet != v) { existing.isPuppet = v; cityFlagsChanged = true }
                }
                obj["razing"]?.jsonPrimitive?.contentOrNull?.let {
                    val v = it == "true"
                    if (existing.isBeingRazed != v) { existing.isBeingRazed = v; cityFlagsChanged = true }
                }
                obj["avoidGrowth"]?.jsonPrimitive?.contentOrNull?.let {
                    val v = it == "true"
                    if (existing.avoidGrowth != v) { existing.avoidGrowth = v; cityFlagsChanged = true }
                }
                obj["capital"]?.jsonPrimitive?.contentOrNull?.let {
                    val v = it == "true"
                    if (existing.isOriginalCapital != v) { existing.isOriginalCapital = v; cityFlagsChanged = true }
                }
                obj["justConquered"]?.jsonPrimitive?.contentOrNull?.let {
                    val v = it == "true"
                    if (existing.hasJustBeenConquered != v) { existing.hasJustBeenConquered = v; cityFlagsChanged = true }
                }
                obj["manualSpecialists"]?.jsonPrimitive?.contentOrNull?.let {
                    val v = it == "true"
                    if (existing.manualSpecialists != v) { existing.manualSpecialists = v; cityFlagsChanged = true }
                }
                if (cityFlagsChanged) {
                    cityStateChanged = true
                    worldScreen.shouldUpdate = true
                    try { existing.cityStats.update() } catch (ignored: Exception) {}
                }
                // 专家分配同步 (管理公民界面; 服务器权威)
                obj["specialists"]?.jsonArray?.let { spArr ->
                    try {
                        val server = HashMap<String, Int>()
                        for (sp in spArr) {
                            val a = sp.jsonArray ?: continue
                            if (a.size < 2) continue
                            val name = a[0].jsonPrimitive.contentOrNull ?: continue
                            val n = a[1].jsonPrimitive.intOrNull ?: continue
                            server[name] = n
                        }
                        val local = existing.population.specialistAllocations
                        if (local.toMap() != server) {
                            local.clear()
                            for ((k, v) in server) local[k] = v
                            cityStateChanged = true
                            worldScreen.shouldUpdate = true
                            try { existing.cityStats.update() } catch (ignored: Exception) {}
                        }
                    } catch (e: Exception) {
                    }
                }
                // 本回合已售建筑标记 (卖完按钮灰; 服务器权威)
                obj["soldThisTurn"]?.jsonPrimitive?.contentOrNull?.let {
                    val v = it == "true"
                    if (existing.hasSoldBuildingThisTurn != v) {
                        existing.hasSoldBuildingThisTurn = v
                        cityStateChanged = true
                        worldScreen.shouldUpdate = true
                    }
                }
                // 本城禁用建筑同步 (右键菜单"禁用/启用"后列表立即更新; 服务器权威)
                obj["disabledConstructions"]?.jsonArray?.let { dcArr ->
                    try {
                        val server = dcArr.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
                        val local = existing.disabledConstructions
                        if (local != server) {
                            local.clear()
                            local.addAll(server)
                            cityStateChanged = true
                            worldScreen.shouldUpdate = true
                        }
                    } catch (e: Exception) {
                    }
                }
                // 单位"使用默认晋升"偏好同步 (建造界面勾选框; 服务器权威)
                obj["savedPromotionPrefs"]?.jsonArray?.let { sppArr ->
                    try {
                        val server = sppArr.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
                        val local = existing.unitShouldUseSavedPromotion
                        val localTrue = local.filterValues { it }.keys.toSet()
                        if (localTrue != server) {
                            local.clear()
                            for (k in server) local[k] = true
                            cityStateChanged = true
                            worldScreen.shouldUpdate = true
                        }
                    } catch (e: Exception) {
                    }
                }
                // 保存的晋升模板同步 [baseUnit, [promotions], xp] (PromotionPicker 保存后新单位自动继承; 服务器权威)
                obj["unitToPromotions"]?.jsonArray?.let { utpArr ->
                    try {
                        val server = HashMap<String, Pair<List<String>, Int>>()
                        for (u in utpArr) {
                            val a = u.jsonArray ?: continue
                            if (a.size < 3) continue
                            val baseUnit = a[0].jsonPrimitive.contentOrNull ?: continue
                            val promos = a[1].jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                            val xp = a[2].jsonPrimitive.intOrNull ?: 0
                            server[baseUnit] = promos to xp
                        }
                        val local = existing.unitToPromotions
                        val localDirty = local.entries.any { (k, v) ->
                            val s = server[k]
                            s == null || s.first.toSet() != v.promotions || s.second != v.XP
                        } || local.size != server.size
                        if (localDirty) {
                            local.clear()
                            for ((k, v) in server) {
                                val up = com.unciv.logic.map.mapunit.UnitPromotions()
                                up.promotions.addAll(v.first)
                                up.XP = v.second
                                local[k] = up
                            }
                            cityStateChanged = true
                            worldScreen.shouldUpdate = true
                        }
                    } catch (e: Exception) {
                    }
                }
                existing
            } else {
                val civName = obj["civ"]?.jsonPrimitive?.contentOrNull ?: continue
                val civ = gameInfo.civilizations.firstOrNull { it.civName == civName } ?: continue
                val x = obj["x"]?.jsonPrimitive?.intOrNull ?: continue
                val y = obj["y"]?.jsonPrimitive?.intOrNull ?: continue
                try {
                    val newCity = civ.addCity(com.unciv.logic.map.HexCoord(x, y), null)
                    newCity.id = id
                    obj["name"]?.jsonPrimitive?.contentOrNull?.let { newCity.name = it }
                    worldScreen.shouldUpdate = true
                    newCity
                } catch (e: Exception) {
                    // 创建失败 (如地块已被占) 静默 — 回合末存档重载兜底
                    null
                }
            }
            // 地块归属同步 (买地/建城即时显示; 服务器权威全量对齐)
            if (city != null) {
                syncOwnedTiles(gameInfo, city, obj, worldScreen)
                syncWorkedTiles(gameInfo, city, obj, worldScreen)
                syncLockedTiles(gameInfo, city, obj, worldScreen)
            }
        }
        // 服务器列表没有的本地城市 → 被摧毁/烧毁 (服务器权威: 旗子立即消失, 不等回合末重载)
        if (isFull) {
            // 全量模式: 扫描本地城市, 不在服务器列表中的 → 移除
            if (serverCityIds.isNotEmpty()) {
                for (localCity in gameInfo.getCities().toList()) {
                    if (localCity.id in serverCityIds) continue
                    try {
                        try {
                            val wsSel = worldScreen.bottomUnitTable.selectedCity
                            if (wsSel != null && wsSel.city == localCity) {
                                worldScreen.bottomUnitTable.selectUnit(null)
                            }
                        } catch (ignored2: Exception) {
                        }
                        try {
                            val cur = com.unciv.UncivGame.Current.screen
                            if (cur is com.unciv.ui.screens.cityscreen.CityScreen
                                && cur.cityView.city == localCity) {
                                com.unciv.UncivGame.Current.popScreen()
                            }
                        } catch (ignored3: Exception) {
                        }
                        localCity.destroyCity(overrideSafeties = true)
                        cityStateChanged = true
                        worldScreen.shouldUpdate = true
                    } catch (e: Exception) {
                    }
                }
            }
        } else {
            // 增量模式: 只处理 removedCities 列表
            for (rcElem in removedCities) {
                val rcId = rcElem.jsonPrimitive.contentOrNull ?: continue
                val localCity = gameInfo.getCities().firstOrNull { it.id == rcId } ?: continue
                try {
                    try {
                        val wsSel = worldScreen.bottomUnitTable.selectedCity
                        if (wsSel != null && wsSel.city == localCity) {
                            worldScreen.bottomUnitTable.selectUnit(null)
                        }
                    } catch (ignored2: Exception) {
                    }
                    try {
                        val cur = com.unciv.UncivGame.Current.screen
                        if (cur is com.unciv.ui.screens.cityscreen.CityScreen
                            && cur.cityView.city == localCity) {
                            com.unciv.UncivGame.Current.popScreen()
                        }
                    } catch (ignored3: Exception) {
                    }
                    localCity.destroyCity(overrideSafeties = true)
                    cityStateChanged = true
                    worldScreen.shouldUpdate = true
                } catch (e: Exception) {
                }
            }
        }
    }

    /** 城市地块归属同步: 服务器 ownedTiles 全量列表 → 本地设置/清空 (买地后立即显示, 不等回合末重载) */
    private fun syncOwnedTiles(gameInfo: GameInfo, city: com.unciv.logic.city.City, obj: JsonObject, worldScreen: WorldScreen) {
        try {
            val owned = obj["ownedTiles"]?.jsonArray
                ?.mapNotNull { arr ->
                    val a = arr.jsonArray ?: return@mapNotNull null
                    if (a.size >= 2) (a[0].jsonPrimitive.intOrNull to a[1].jsonPrimitive.intOrNull) else null
                }?.toSet() ?: emptySet()
            if (owned.isEmpty()) return
            var changed = false
            for ((x, y) in owned) {
                val px = x ?: continue
                val py = y ?: continue
                val t = gameInfo.tileMap.get(px, py) ?: continue
                if (t.owningCity != city) {
                    t.setOwningCity(city)
                    city.tiles.add(t.position)  // 更新城市地块集 (城市界面立即显示)
                    changed = true
                }
            }
            // 本地属于该城但服务器没有的 → 清空 (全量对齐, 防止本地残留)
            for (t in city.getTiles()) {
                if ((t.position.x!! to t.position.y!!) !in owned && t.owningCity == city) {
                    t.setOwningCity(null)
                    city.tiles.remove(t.position)
                    changed = true
                }
            }
            if (changed) {
                cityStateChanged = true
                worldScreen.shouldUpdate = true
            }
        } catch (e: Exception) {
        }
    }

    /** 工作格同步: 服务器 workedTiles 全量列表 → 本地增删 (公民分配立即生效, 界面刷新) */
    private fun syncWorkedTiles(gameInfo: GameInfo, city: com.unciv.logic.city.City, obj: JsonObject, worldScreen: WorldScreen) {
        try {
            val worked = obj["workedTiles"]?.jsonArray
                ?.mapNotNull { arr ->
                    val a = arr.jsonArray ?: return@mapNotNull null
                    if (a.size >= 2) (a[0].jsonPrimitive.intOrNull to a[1].jsonPrimitive.intOrNull) else null
                }?.toSet() ?: emptySet()
            var changed = false
            for ((x, y) in worked) {
                val px = x ?: continue
                val py = y ?: continue
                if (city.workedTiles.add(com.unciv.logic.map.HexCoord(px, py))) changed = true
            }
            for (pos in city.workedTiles.toList()) {
                if ((pos.x to pos.y) !in worked && city.workedTiles.remove(pos)) changed = true
            }
            if (changed) {
                cityStateChanged = true
                worldScreen.shouldUpdate = true
            }
        } catch (e: Exception) {
        }
    }

    /** 锁定地块同步: 服务器 lockedTiles 全量列表 → 本地对齐 (锁定/解锁立即生效, 界面刷新;
     *  纯拦截后本地不执行, 不广播则锁定无显示) */
    private fun syncLockedTiles(gameInfo: GameInfo, city: com.unciv.logic.city.City, obj: JsonObject, worldScreen: WorldScreen) {
        try {
            val locked = obj["lockedTiles"]?.jsonArray
                ?.mapNotNull { arr ->
                    val a = arr.jsonArray ?: return@mapNotNull null
                    if (a.size >= 2) (a[0].jsonPrimitive.intOrNull to a[1].jsonPrimitive.intOrNull) else null
                }?.toSet() ?: emptySet()
            var changed = false
            // 服务器有而本地没有 → 加锁
            for ((x, y) in locked) {
                val px = x ?: continue
                val py = y ?: continue
                if (city.lockedTiles.add(com.unciv.logic.map.HexCoord(px, py))) changed = true
            }
            // 本地有而服务器没有 → 解锁 (全量对齐)
            for (pos in city.lockedTiles.toList()) {
                if ((pos.x to pos.y) !in locked && city.lockedTiles.remove(pos)) changed = true
            }
            if (changed) {
                cityStateChanged = true
                worldScreen.shouldUpdate = true
            }
        } catch (e: Exception) {
        }
    }

    /** 当前打开的是城市界面 → 重建表格 (买地/人口/掉血立即可见) */
    private fun refreshOpenCityScreen() {
        try {
            val current = com.unciv.UncivGame.Current.screen
            if (current is com.unciv.ui.screens.cityscreen.CityScreen) {
                current.update()
            }
        } catch (e: Exception) {
        }
    }

    private fun findCity(gameInfo: GameInfo, id: String): com.unciv.logic.city.City? {
        for (civ in gameInfo.civilizations) {
            for (city in civ.cities) {
                if (city.id == id) return city
            }
        }
        return null
    }

    private fun findUnit(gameInfo: GameInfo, id: Int): MapUnit? {
        for (civ in gameInfo.civilizations) {
            for (unit in civ.units.getCivUnits()) {
                if (unit.id == id) return unit
            }
        }
        return null
    }

    // ---------- UI ----------

    private fun updateStatusLabel() {
        // 只更新暂停按钮文本 (连接状态标签已彻底移除 — 测试调试用白字)
        Concurrency.runOnGLThread {
            fsPauseButton?.setText(if (lastPaused) "Resume".tr() else "Pause".tr())
        }
    }

    private fun showToast(text: String) {
        if (text == lastErrorShown) return
        lastErrorShown = text
        val worldScreen = worldScreenRef?.get() ?: return
        Concurrency.runOnGLThread {
            ToastPopup(text, worldScreen)
        }
    }
}

/** 简易 JSON 构建 (避免引入额外依赖) */
private fun buildJson(block: MutableMap<String, Any?>.() -> Unit): String {
    val map = LinkedHashMap<String, Any?>()
    map.block()
    val sb = StringBuilder("{")
    var first = true
    for ((k, v) in map) {
        if (!first) sb.append(",")
        first = false
        sb.append('"').append(k).append("\":")
        sb.append(jsonValue(v))
    }
    sb.append("}")
    return sb.toString()
}

private fun jsonValue(v: Any?): String = when (v) {
    null -> "null"
    is Number -> v.toString()
    is Boolean -> v.toString()
    is Map<*, *> -> buildJson {
        for ((kk, vv) in v) {
            if (kk is String) put(kk, vv)
        }
    }
    is List<*> -> v.joinToString(prefix = "[", postfix = "]") { jsonValue(it) }
    is Set<*> -> v.joinToString(prefix = "[", postfix = "]") { jsonValue(it) }
    else -> "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
}

/** UncivGame 访问辅助 (避免 FrameSync 与 UncivGame 强耦合) */
private object UncivGameHelper {
    fun getUserId(): String = com.unciv.UncivGame.Current.settings.multiplayer.getUserId()
    fun getNickname(): String = com.unciv.UncivGame.Current.settings.lobbyNickname.ifBlank { "Player" }
}

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

    /** 最近一次 sim 广播处理耗时 ms (P0 体感诊断, 2026-08-30): FPS 显示右上角实时展示,
     *  跑真实对局定位卡顿来源 (广播处理慢 → 渲染被抢) */
    @Volatile
    var lastSimProcessMs = 0f

    /** 结算重载恢复通知历史上限 (2026-08-30): 防无限累积 (用户反馈"通知越来越多") */
    private const val MAX_RESTORED_NOTIFS = 20

    /** 本回合广播到达的通知 key (category|text) — 结算重载只恢复这些 (对齐原版"当回合可见",
     *  但结算瞬间生成的通知玩家来不及看 → 保留到下一回合显示, 再下回合清空) */
    private val currentTurnNotifKeys = HashSet<String>()

    /** 2026-08-30 公共/私有拆分: 服务器广播的每文明 stats 摘要
     *  (civ名 -> [food, production, gold, science, culture, faith, force])
     *  排行/概览需要"别人的每回合产出/军力" — 工作格/专家已不广播, 本地算不了别人 → 用服务器权威摘要 */
    @Volatile
    var serverStatsByCiv: HashMap<String, FloatArray> = HashMap()

    private fun parseServerStats(state: JsonObject) {
        val arr = state["stats"]?.jsonArray ?: return
        val map = HashMap<String, FloatArray>()
        for (e in arr) {
            val o = e.jsonObject ?: continue
            val name = o["civ"]?.jsonPrimitive?.contentOrNull ?: continue
            map[name] = floatArrayOf(
                o["food"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f,
                o["production"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f,
                o["gold"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f,
                o["science"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f,
                o["culture"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f,
                o["faith"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f,
                o["force"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f)
        }
        serverStatsByCiv = map
    }

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

    /** fs_server 主机 (2026-08-25 对局分离架构: 打包时改新机 IP; null = 与 lobby 同主机, 兼容旧部署;
     *  运行时 -Duncivgc.fsHost 覆盖 — 仅对局(模拟器/广播)走新机, 大厅/存档仍走 lobby 主机) */
    private val FS_HOST: String? = "YOUR_FS_HOST"
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
    /** 视野增量重算 (2026-08-23 性能优化): 单位广播条目快照 (unitId -> 条目JSON) + 位置快照 —
     *  位置没变且条目没变 → 视野不可能变 → 跳过重算 (组队后 3 文明 × 30+ 单位时每帧全量重算是主要开销) */
    private val unitEntrySnapshot = HashMap<Int, String>()  // 当前帧广播条目 (applyState 写)
    private val unitVisibilityPos = HashMap<Int, String>()  // 上次处理时的位置
    private val unitProcessedEntry = HashMap<Int, String>()  // 上次处理时的条目
    /** 相遇检测增量 (2026-08-23 性能优化): 全文明单位位置快照 + 城市快照 —
     *  不再每帧全扫可见格 (组队后视野∪大), 只检查 移动单位/新城市/视野扩展 三个变化源 */
    private val meetUnitPos = HashMap<Int, String>()  // unitId -> "x,y" (全文明)
    private val meetCitySnapshot = HashMap<String, String>()  // cityId -> "x,y,ownerCivID"

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
    private var settleBlocker: com.badlogic.gdx.scenes.scene2d.Actor? = null  // 结算全屏输入拦截层 (2026-08-27 用户要求"点都点不了")
    @Volatile private var serverSettling = false  // 服务器结算中 (turnStatus settling): 全程锁定 — 切换前停留由服务器端延迟广播实现 (2026-08-22)
    /** 2026-08-27: 结算状态持久标志 — 不受 stop()/dispose 影响 (loadGame 会 dispose 旧 WorldScreen → stop() 重置
     *  serverSettling, 导致重载后 start() 恢复提示条失败 → “提示条秒没”根因); 由 turnStatus 更新 */
    @Volatile private var settlingPersist = false
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
    /** 2026-08-29 同盟弹窗本地挂起 (value=civId): 存档重载清空 popupAlerts 后只恢复事件弹窗,
     *  同盟提议/续约/跟进弹窗完全依赖 fs 补推 — 多个同盟提议并发时 fs 单槽只补推最后一条,
     *  其余永久丢失 (对方一直等). 本地也挂起, 重载后重新恢复 (与 pendingEvents 同机制) */
    private val pendingAlliancePopups = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<com.unciv.logic.civilization.AlertType, String>>()
    /** 我是否已点“完成回合” (发送后本地立即置 true, 结算后 turnStatus 广播复位) */
    @Volatile var myTurnFinished = false

    /** 存档重载进行中 (防重入) */
    @Volatile private var reloading = false
    /** 2026-08-25 结算就绪: 重载完成后待发送的 turnReady 标记 (新连接建立时发送) */
    @Volatile private var pendingTurnReady = false
    /** 2026-08-26 重载重连标记: 连接 URL 带 reload=1 → fs_server 跳过全量拉取
     *  (重载后本地数据 = 刚下载的存档 = 全量, 再拉 451KB 全量纯属浪费; 连接成功后才清 —
     *  失败重试期间保持, 掉线自动重连时已清 → 恢复全量拉取) */
    @Volatile private var reloadReconnect = false
    /** 已重载到的服务器回合 (saveUpdated 幂等: 只重载更新的存档) */
    @Volatile private var lastReloadedTurn = -1
    /** 本次状态广播中城市状态是否有变化 (hp/人口/地块) → 打开的城市界面需要刷新 */
    private var cityStateChanged = false
    // UncivGC 商路 (2026-08-24): 上次同步的城市结构指纹 (civName:id) — 变化才失效连接缓存
    private var lastSyncedCityKeys: Set<String>? = null
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
        // 2026-08-27: 跨局 (新房间/跳海) 重置结算锁定 — 同局重载保留 (服务器可能仍在结算停留)
        if (isNewGame) {
            serverSettling = false
            settlingPersist = false
        }
        // 跨局状态重置 (跳海/换房后新局: 完成回合/倒计时/重载幂等必须清零, 否则新局失效)
        lastReloadedTurn = -1
        pendingTurnReady = false  // 新局/新连接: 不发送就绪 (仅结算重载后发送)
        reloadReconnect = false  // 新局: 不跳过全量 (防上一局重载标记残留)
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
            // UncivGC 待办事件 (实验性UI): 事件不立即弹, 排队由「事件」按钮查看 — 下回合清空, 重载不重挂
            if (com.unciv.GUI.getSettings().experimentalUi) {
                pendingEvents.clear()
                pendingAlliancePopups.clear()
            } else {
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
            // 2026-08-29 同盟弹窗重载恢复: 本地挂起的同盟提议/续约/跟进弹窗重新挂起
            // (fs 补推单槽会丢并发弹窗, 本地挂起双保险)
            if (pendingAlliancePopups.isNotEmpty()) {
                for ((atype, civId) in pendingAlliancePopups) {
                    val exists = worldScreen.viewingCiv.popupAlerts.any {
                        it.type == atype && it.value == civId
                    }
                    if (!exists) {
                        worldScreen.viewingCiv.popupAlerts.add(com.unciv.logic.civilization.PopupAlert(atype, civId))
                    }
                }
            }
            } // 非实验性UI: 重挂挂起弹窗
        } catch (e: Exception) {
        }
        updateStatusLabel()
        // 2026-08-27: 重载/重连后若服务器仍在结算中 → 立即恢复提示条+锁定 (不依赖补推/saveUpdated 时序 —
        // 用户反馈"结算提示 1 秒没": 重载时 stop()/dispose 移除提示条并重置锁定, 提示条再也没出现)
        if (settlingPersist) {
            serverSettling = true
            dbg("start() 检测到结算中 → 恢复提示条")
            // 2026-08-27 修复: 传 worldScreen 参数 — 新屏 init 时 GUI.getWorldScreen() 还是 null,
            // 旧版 currentWorldScreenOrNull() 直接 return → 提示条/拦截层挂不上 → 新屏第一帧无提示条"闪"
            showSettlingHint(worldScreen)
        }
        // 重载/开局立即恢复视野 (不等首条 state — 否则 reload 后新 gameInfo 的 viewableTiles 为空,
        // 整屏黑几百 ms 直到广播回来, 2026-08-23 用户反馈 "过回合视野黑一下")
        // doMeetCheck=false: 连接未建立, 相遇 sendOp 会静默失败但 shownMeets 已标记 → 该文明永不 meet (外交缺失)
        try {
            refreshMyCivVisibility(worldScreen, gameInfo, doMeetCheck = false)
        } catch (e: Exception) {
        }
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
            // 结算提示条/拦截层: 重载窗口保留在旧屏上 (不 remove) — 消除"有-无-恢复"空窗
            // (旧屏销毁时随 stage 一起消失; 新屏 start() 按 settlingPersist 重建, 名字防重复)
            settlingHint = null
            settleBlocker = null
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
                // 2026-08-30 修复: 结算重载会清空通知历史 (存档不含通知, 服务器结算时清内存)
                // — 备份旧通知, loadGame 后恢复 (广播通知在重载前到达的不会丢)
                val backupNotifs = oldWorldScreen?.gameInfo?.civilizations?.mapNotNull { civ ->
                    if (civ.isSpectator() || civ.isBarbarian) null
                    else civ.civName to civ.notifications.toList()
                }?.toMap()
                // 先停旧连接/UI — 否则 loadGame 后新 WorldScreen init 的 start 被 running=true 挡住
                // (statusLabel/暂停按钮不重建, 旧连接残留) — 新 start 会重建全部
                // 2026-08-27: 重载≠断线 — 服务器可能仍在结算停留 (保底未过), stop() 会重置锁定状态,
                // 重载窗口期 (stop→补推 turnStatus 到达前) 玩家可操作 + 提示条消失 (用户反馈"弹窗1秒就没/保底没过能操作")
                val wasSettling = serverSettling
                stop()
                serverSettling = wasSettling
                settlingPersist = wasSettling || settlingPersist  // 2026-08-27: 持久标志不受后续 dispose stop() 影响
                // 不走 onlineMultiplayer.downloadGame: 它会更新 preview 触发 MultiplayerGameUpdated
                // 事件 → WorldScreen 处理器再触发一次重载 (双刷根因)。直接下载+loadGame。
                val dlT0 = System.currentTimeMillis()
                val gi = com.unciv.UncivGame.Current.onlineMultiplayer.multiplayerServer.downloadGame(gameId)
                dbg("reload 下载耗时: " + (System.currentTimeMillis() - dlT0) + "ms")
                // ⚠️ 快照清空必须在 loadGame 之前! loadGame 同步触发新 WorldScreen → start() →
                // 本地视野刷新 (含队友探索历史合并), 若清空在 loadGame 后执行, start() 用的是旧快照:
                // teamExploredMerged 仍 true → 合并被跳过 → 队友历史探索永久缺失 (2026-08-23 用户实测
                // "过回合后没视野地块变灰丢失" 根因)
                unitEntrySnapshot.clear()
                unitVisibilityPos.clear()
                unitProcessedEntry.clear()
                meetUnitPos.clear()
                meetCitySnapshot.clear()
                teamExploredMerged = false  // 重载后重新全量合并队友探索历史
                val lgT0 = System.currentTimeMillis()
                com.unciv.UncivGame.Current.loadGame(gi)
                dbg("reload loadGame 耗时: " + (System.currentTimeMillis() - lgT0) + "ms")
                // 恢复通知历史 (2026-08-30): 只恢复"本回合新广播"的通知 (结算瞬间生成玩家来不及看),
                // 旧通知显示一回合后自然消失 — 对齐原版 endTurn 清空, 不无限累积
                if (backupNotifs != null) {
                    try {
                        for (civ in gi.civilizations) {
                            val saved = backupNotifs[civ.civName] ?: continue
                            val toRestore = saved.filter { n ->
                                val key = n.category.name + "|" + n.text
                                key in currentTurnNotifKeys
                            }
                            for (n in toRestore) {
                                if (civ.notifications.none { it.text == n.text && it.category == n.category }) {
                                    civ.notifications.add(n)
                                }
                            }
                            while (civ.notifications.size > MAX_RESTORED_NOTIFS) civ.notifications.removeAt(0)
                        }
                        currentTurnNotifKeys.clear()  // 下回合重新积累
                    } catch (ignored: Exception) {
                    }
                }
                if (turn >= 0) lastReloadedTurn = turn  // 成功才记录
                // 2026-08-27: 重载完成且服务器仍在结算中 → 恢复提示条 (锁定已由 serverSettling 恢复),
                // 直到 settle_finish 广播 settling=false 才解锁
                if (wasSettling) {
                    Concurrency.runOnGLThread {
                        try {
                            if (serverSettling) showSettlingHint()
                        } catch (ignored: Exception) {
                        }
                    }
                }
                // 2026-08-26 重载重连标记: 必须在 loadGame 之后设置 — loadGame 同步触发新 WorldScreen →
                // start() (单例 object) 会无条件重置标记, loadGame 前设置会被清掉 → reload=1 失效;
                // 设在成功后还能保证 loadGame 异常 (catch 恢复旧连接) 时不残留 → 旧连接正常拉全量
                reloadReconnect = true
                // 2026-08-25 结算就绪: 重载完成 → 新连接建立后通知服务器 (全员就绪才广播新回合)
                pendingTurnReady = true
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
        "civ.chooseTech", "civ.choosePolicy", "civ.chooseBeliefs"
    )

    /** 发送操作到服务器 (被暂停/结算锁定吞掉时静默无效) */
    fun sendOp(op: String, data: Map<String, Any?>) {
        sendOpChecked(op, data)
    }

    /** 2026-08-29: 同盟跟进弹窗处理完 (跟进/不跟进) → 清本地挂起, 防重载恢复复活
     *  (服务器侧由 op 清 popupAlerts + 补推缓存; 本地挂起也要清, 否则 start() 重载又加回来) */
    fun removePendingAlliancePopup(type: com.unciv.logic.civilization.AlertType, civId: String) {
        pendingAlliancePopups.remove(Pair(type, civId))
    }

    /** 发送操作到服务器; 返回是否真正发出 (false = 被暂停/结算锁定吞掉) —
     *  2026-08-27: 弹窗类操作 (事件选择等) 需知道是否被吞, 被吞不关弹窗不标记已处理 */
    fun sendOpChecked(op: String, data: Map<String, Any?>): Boolean {
        // 暂停期间: 游戏内操作全部静默无效 (不报错; 顶栏按钮仍可点) — 2026-08-21 用户要求
        if (lastPaused) {
            return false
        }
        // 回合结算中 (服务器 settling=true): 全程锁定 — 切换前停留由服务器延迟广播实现 (2026-08-22)
        if (serverSettling) {
            dbg("sendOp 被结算锁定吞掉: op=$op")
            return false
        }
        // 完成回合后 (myTurnFinished) 锁定城市配置/科技/政策/信仰类 op —
        // 结算已按旧配置入账, 再改 → 服务器状态变但本回合产出已入账 → 显示与入账不符;
        // 单位操作 (move/attack 等) 保留 — 完成回合后仍可操作闲置单位 (NextUnit 例外)
        // 2026-08-30: 回合 0 (lastTurn=0) 完成回合后不锁城市操作 — 开局准备回合允许补选生产
        // (服务器 finished 检查同样 turnNo>0 才拒绝; 完成回合有按钮反馈"取消完成回合", 可取消)
        if (myTurnFinished && lastTurn > 0 && op in TURN_LOCKED_OPS) {
            // 2026-08-30: 拒绝提示已全部移除 (用户要求 — 拒绝 toast 海量弹出加剧卡顿), 静默忽略
            return false
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
            return false
        }
        sendJson(buildJson {
            put("type", "op")
            put("playerId", playerId)
            put("op", op)
            put("data", data)
        })
        return true
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

    // ---- 商路 v2 (2026-08-26 设计稿 v2): 单向手动连接 ----
    fun sendTradeRouteOffer(cityId: String, targetCityId: String) {
        sendOp("tradeRoute.offer", mapOf("cityId" to cityId, "targetCityId" to targetCityId))
    }

    fun sendTradeRouteAcceptOffer(fromCityId: String) {
        sendOp("tradeRoute.acceptOffer", mapOf("fromCityId" to fromCityId))
    }

    fun sendTradeRouteRejectOffer(fromCityId: String) {
        sendOp("tradeRoute.rejectOffer", mapOf("fromCityId" to fromCityId))
    }

    fun sendTradeRouteDisconnect(cityId: String, targetCityId: String) {
        sendOp("tradeRoute.disconnect", mapOf("cityId" to cityId, "targetCityId" to targetCityId))
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
     *  本地立即置 myTurnFinished → 按钮变“等待剩余玩家” (不等广播, 点击即反馈).
     *  2026-08-30 回合 0 例外: 开局准备回合 (城市还没选生产) 完成回合不锁操作 —
     *  否则“选择建造/训练”(Pick construction) 按钮变灰, 等全员就绪期间无法补选生产 (用户反馈) */
    fun sendNextTurn() {
        sendJson("""{"type":"nextTurn"}""")
        // 2026-08-30: 无条件置 true (回合 0 也置 — 按钮立即变"取消完成回合", 点击有反馈);
        // 回合 0 不锁城市操作由 sendOpChecked 的 lastTurn>0 条件保证 (不是靠不置 myTurnFinished)
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
        val configuredHost = System.getProperty("uncivgc.fsHost") ?: FS_HOST
        val host = configuredHost
            ?: LobbyApi.SERVER_URL.substringAfter("://").substringBefore(':')
        val spec = if (isSpectating) "&spectator=true" else ""
        // v=2: 声明支持状态广播 gzip 压缩 (fs_server 按连接能力分发, 旧客户端不受影响)
        // reload=1: 重载重连 — 数据=刚下载的存档(全量), 跳过服务器全量拉取 (2026-08-26); 不消费标记 (连接成功才清)
        val reload = if (reloadReconnect) "&reload=1" else ""
        return "ws://$host:$FS_PORT/ws?gameId=$gameId&playerId=$playerId&nickname=${java.net.URLEncoder.encode(nickname, "UTF-8")}$spec&v=2$reload"
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
        reloadReconnect = false  // 2026-08-26: 连接成功 → 后续掉线重连恢复全量拉取
        lastErrorShown = ""
        lastPongAt = System.currentTimeMillis()
        updateStatusLabel()
        // 2026-08-25 结算就绪: 存档重载完成后的新连接 → 通知服务器 (全员就绪才广播新回合)
        if (pendingTurnReady) {
            pendingTurnReady = false
            try {
                ws.send("""{"type":"turnReady"}""")
            } catch (ignored: Exception) {
            }
        }
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
            "sim" -> {
                val t0 = System.nanoTime()
                handleSimMessage(msg)
                lastSimProcessMs = (System.nanoTime() - t0) / 1_000_000f  // P0 诊断: 广播处理耗时
            }
            "battle" -> handleBattleMessage(msg)
            "campCleared" -> handleCampCleared(msg)
            "saveUpdated" -> reloadGame(msg["turn"]?.jsonPrimitive?.intOrNull ?: -1)
            "tradeRequest" -> handleTradeRequest(msg)
            "tradeRetracted" -> handleTradeRetracted(msg)
            "tradeAccepted" -> handleTradeAccepted(msg)
            "tradeRejected" -> handleTradeRejected(msg)
            "tradeRouteOffer" -> handleTradeRouteOffer(msg)
            "tradeRouteAccepted" -> handleTradeRouteAccepted(msg)
            "tradeRouteRejected" -> handleTradeRouteRejected(msg)
            "tradeRouteDisconnected" -> handleTradeRouteDisconnected(msg)
            "tradeRouteBroken" -> handleTradeRouteBroken(msg)
            "friendshipOffer" -> handleFriendshipOffer(msg)
            "demandOffer" -> handleDemandOffer(msg)
            "denounced" -> handleDenounced(msg)
            "declaredWar" -> handleDeclaredWar(msg)
            "wonderLost" -> handleWonderLost(msg)
            "ruinReward" -> handleRuinReward(msg)
            "notification" -> handleNotification(msg)
            "eventPopup" -> handleEventPopup(msg)
            "cityConquered" -> handleCityConquered(msg)
            "allianceOffer" -> handleAllianceOffer(msg)
            "allianceAccepted" -> handleAllianceAccepted(msg)
            "allianceRejected" -> handleAllianceRejected(msg)
            "allianceRenew" -> handleAllianceRenew(msg)
            "allianceRenewed" -> handleAllianceRenewed(msg)
            "allianceEnded" -> handleAllianceEnded(msg)
            "allianceFollowUp" -> handleAllianceFollowUp(msg)
            "turnStatus" -> handleTurnStatus(msg)
            "victory" -> handleVictory(msg)
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
    /** 服务器权威胜利广播 (2026-08-27 新增): 宗教胜利等客户端本地判不出的胜利类型,
     *  由服务器 doNextTurn 检测后带赢家信息广播 → 这里设置 victoryData → WorldScreen.update
     *  checkForVictory() 见 victoryData != null 即触发胜利界面。 */
    private fun handleVictory(msg: JsonObject) {
        try {
            val gameInfo = worldScreenRef?.get()?.gameInfo ?: return
            if (gameInfo.victoryData != null) return  // 已设置过
            val winnerId = msg["winner"]?.jsonPrimitive?.contentOrNull ?: ""
            val vType = msg["victoryType"]?.jsonPrimitive?.contentOrNull ?: ""
            dbg("victory 广播: winner=$winnerId type=$vType")
            val winnerCiv = gameInfo.civilizations.firstOrNull { it.civID == winnerId }
            if (winnerCiv != null && vType.isNotEmpty()) {
                gameInfo.victoryData = com.unciv.logic.VictoryData(winnerCiv, vType, gameInfo.turns)
                victoryShownForFsGame = false  // 允许弹胜利界面 (update 循环里会置 true 防重)
                worldScreenRef?.get()?.shouldUpdate = true
            } else {
                dbg("victory: 找不到赢家文明或类型为空, winner=$winnerId")
            }
        } catch (e: Exception) {
            dbg("handleVictory 异常: ${e.message}")
        }
    }

    private fun handleTurnStatus(msg: JsonObject) {
        val tsTurn = msg["turn"]?.jsonPrimitive?.intOrNull ?: return
        if (tsTurn < lastTurn) return  // 过期广播 (网络延迟/乱序) 忽略
        // 结算状态: settling=true → 全程锁定 (提示条常驻); 结算结束 → 解锁 + 移除提示条
        // (切换前停留由服务器延迟广播实现, 2026-08-22; 这里不再做切换后锁定)
        val settling = msg["settling"]?.jsonPrimitive?.contentOrNull == "true"
        val wasSettling = serverSettling
        dbg("turnStatus turn=$tsTurn lastTurn=$lastTurn settling=$settling wasSettling=$wasSettling settleLockSec=" + (worldScreenRef?.get()?.gameInfo?.gameParameters?.fsSettleLockSeconds ?: -1))
        serverSettling = settling
        settlingPersist = settling  // 2026-08-27: turnStatus 权威更新持久标志 (锁定/解锁都同步)
        if (settling) {
            dbg("settling=true → 锁定 + 提示条")
            showSettlingHint()  // 显示提示条 + 全程锁定 (serverSettling 控制实际锁定)
            // 2026-08-30 修复: 结算窗口内断开重连 → reload 后的 turnReady 在旧连接上丢失,
            // 服务器等就绪卡 60s 保底 (用户反馈"卡在12回合"); 收到 settling=true 即补发就绪
            // (幂等: 服务器 turnReady 集合去重; 正常重载流程的 pendingTurnReady 照常发, 重复无害)
            try {
                kotlinx.coroutines.GlobalScope.launch {
                    try {
                        session?.send("""{"type":"turnReady"}""")
                        dbg("settling=true → 补发 turnReady")
                    } catch (ignored: Exception) {
                    }
                }
            } catch (ignored: Exception) {
            }
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
            // 2026-08-30: 回合 0 也置 (完成回合有按钮反馈); 不死锁 — 双端回合 0 都不锁城市 op,
            // 按钮显示"取消完成回合"可点取消 (重连补推同样可取消, 不再"大退解不了")
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

    /** 结算提示条/拦截层 actor 标记名: 跨重载/失败重连防重复 (旧屏残留 + 新屏新建) — 2026-08-27 */
    private val SETTLING_HINT_NAME = "UgcSettlingHint"
    private val SETTLING_BLOCKER_NAME = "UgcSettlingBlocker"

    /** 回合结算提示条: settling=true 期间常驻 (全程锁定), 结算结束 (settling=false) 由 hideSettlingHint 移除 — 2026-08-22
     *  2026-08-27 全屏输入锁定: 同时挂全屏触摸拦截层 — 结算期间"点都点不了" (用户要求, 而不是点了报错/静默无效) */
    private fun showSettlingHint(worldScreenParam: WorldScreen? = null) {
        val worldScreen = worldScreenParam ?: currentWorldScreenOrNull() ?: return
        val gameInfo = worldScreen.gameInfo ?: return
        if (gameInfo.gameId != gameId) return
        Concurrency.runOnGLThread {
            try {
                val stage = worldScreen.stage
                // 防重复: 旧屏残留 (重载失败未销毁) 或重复广播时, 按名字查找已存在的直接复用
                val existingHint = stage.root.findActor<com.badlogic.gdx.scenes.scene2d.Actor>(SETTLING_HINT_NAME)
                if (existingHint != null) {
                    settlingHint = existingHint as? Table
                    settleBlocker = stage.root.findActor(SETTLING_BLOCKER_NAME)
                    return@runOnGLThread
                }
                dbg("showSettlingHint 显示提示条 (source=${if (worldScreenParam != null) "start" else "turnStatus/reload"})")  // 2026-08-27 定位"1秒没"
                // 全屏输入拦截: 挡掉所有点击/滚轮 (含顶栏) — 结算期间完全不可操作
                // 纯 Actor + touchable.enabled 即可拦截 — stage hit() 只命中顶层 actor, 下面的地图/按钮收不到事件
                val blocker = com.badlogic.gdx.scenes.scene2d.Actor()
                blocker.name = SETTLING_BLOCKER_NAME
                blocker.touchable = com.badlogic.gdx.scenes.scene2d.Touchable.enabled
                blocker.setBounds(0f, 0f, stage.width, stage.height)
                stage.addActor(blocker)
                settleBlocker = blocker

                val hint = "Settling turn...".tr().toLabel(fontSize = 22)
                hint.setColor(com.badlogic.gdx.graphics.Color.WHITE)
                val table = Table()
                table.name = SETTLING_HINT_NAME
                table.background = com.unciv.ui.screens.basescreen.BaseScreen.skinStrings.getUiBackground(
                    "SettlingHint",
                    com.unciv.ui.screens.basescreen.BaseScreen.skinStrings.roundedEdgeRectangleShape,
                    com.badlogic.gdx.graphics.Color(0f, 0f, 0f, 0.6f))
                table.add(hint).pad(12f, 20f, 12f, 20f)
                table.pack()
                table.setPosition((stage.width - table.width) / 2f, stage.height * 0.42f)
                stage.addActor(table)
                settlingHint = table  // 存整个框 — remove() 时背景和文字一起移除
            } catch (e: Exception) {
            }
        }
    }

    /** 结算结束: 移除提示条 + 拦截层 (GL 线程) */
    private fun hideSettlingHint() {
        Concurrency.runOnGLThread {
            try {
                settlingHint?.remove()
            } catch (ignored: Exception) {
            }
            settlingHint = null
            try {
                settleBlocker?.remove()
            } catch (ignored: Exception) {
            }
            settleBlocker = null
            // 兜底: 旧屏残留 (重载失败路径) 按名字清
            try {
                val worldScreen = currentWorldScreenOrNull()
                if (worldScreen != null) {
                    worldScreen.stage.root.findActor<com.badlogic.gdx.scenes.scene2d.Actor>(SETTLING_BLOCKER_NAME)?.remove()
                    worldScreen.stage.root.findActor<com.badlogic.gdx.scenes.scene2d.Actor>(SETTLING_HINT_NAME)?.remove()
                }
            } catch (ignored: Exception) {
            }
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
                // 2026-08-30: 记录本回合广播通知 key — 结算重载恢复用 (只恢复当回合新产生的)
                currentTurnNotifKeys.add(cat.name + "|" + displayText)
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

    // ---- UncivGC 同盟 (2026-08-26 设计稿 v1.0): 提议/续约/跟进弹窗 + 通知 ----

    /** 同盟提议广播: 本地加 popupAlert → 世界屏弹接受/拒绝 (服务器已写存档 popupAlerts, 这里即时触发) */
    private fun handleAllianceOffer(msg: JsonObject) {
        val targetPlayerId = msg["playerId"]?.jsonPrimitive?.contentOrNull
        if (targetPlayerId != null && targetPlayerId != playerId) return
        val fromCivId = msg["fromCivId"]?.jsonPrimitive?.contentOrNull ?: return
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            try {
                val myCiv = worldScreen.viewingCiv ?: return@runOnGLThread
                if (myCiv.isSpectator()) return@runOnGLThread
                // 2026-08-27 修复: 原检查 (offers[fromCivId] != myCiv.civID) 在广播先于 stateJson 到达时
                // (op 响应 state 有 100ms 合并延迟) 误吞正常弹窗 → "提议同盟点了没用了对方收不到";
                // 改为: 仅当 offers 里已有该邀请且目标不是自己才忽略 (真·过期残留, 理论上服务器已清缓存不会发生)
                val existingOffer = gameInfo.allianceOffers[fromCivId]
                if (existingOffer != null && existingOffer != myCiv.civID) return@runOnGLThread
                if (myCiv.popupAlerts.none { it.type == com.unciv.logic.civilization.AlertType.AllianceOffer && it.value == fromCivId })
                    myCiv.popupAlerts.add(com.unciv.logic.civilization.PopupAlert(
                        com.unciv.logic.civilization.AlertType.AllianceOffer, fromCivId))
                // 2026-08-29 本地挂起 (重载后恢复, 防 fs 单槽补推丢弹窗)
                pendingAlliancePopups.add(Pair(com.unciv.logic.civilization.AlertType.AllianceOffer, fromCivId))
                worldScreen.shouldUpdate = true
            } catch (e: Exception) {}
        }
    }

    /** 结盟成功通知 */
    private fun handleAllianceAccepted(msg: JsonObject) {
        val targetPlayerId = msg["playerId"]?.jsonPrimitive?.contentOrNull
        if (targetPlayerId != null && targetPlayerId != playerId) return
        val otherCivName = msg["otherCivName"]?.jsonPrimitive?.contentOrNull ?: ""
        val level = msg["level"]?.jsonPrimitive?.contentOrNull ?: "1"
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            try {
                val myCiv = worldScreen.viewingCiv
                if (myCiv != null && !myCiv.isSpectator()) {
                    myCiv.addNotification("你与 ${otherCivName.tr()} 结成了同盟（Lv$level）",
                        com.unciv.logic.civilization.NotificationCategory.Diplomacy,
                        com.unciv.logic.civilization.NotificationIcon.Diplomacy)
                }
                // 2026-08-29: 接受后清除本地挂起 (防重载后死灰复燃)
                val otherCiv = gameInfo.civilizations.firstOrNull { it.civName == otherCivName }
                if (otherCiv != null) {
                    pendingAlliancePopups.remove(Pair(com.unciv.logic.civilization.AlertType.AllianceOffer, otherCiv.civID))
                    pendingAlliancePopups.remove(Pair(com.unciv.logic.civilization.AlertType.AllianceRenew, otherCiv.civID))
                }
                refreshOpenDiplomacyScreen()  // 2026-08-27: 同盟成立后打开的外交界面立即刷新 (不用退出重进)
                worldScreen.shouldUpdate = true
            } catch (e: Exception) {}
        }
    }

    /** 拒绝同盟提议通知 */
    private fun handleAllianceRejected(msg: JsonObject) {
        val targetPlayerId = msg["playerId"]?.jsonPrimitive?.contentOrNull
        if (targetPlayerId != null && targetPlayerId != playerId) return
        val otherCivName = msg["otherCivName"]?.jsonPrimitive?.contentOrNull ?: ""
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            try {
                val myCiv = worldScreen.viewingCiv
                if (myCiv != null && !myCiv.isSpectator()) {
                    myCiv.addNotification("${otherCivName.tr()} 拒绝了你的同盟提议（10 回合内不能再次提议）",
                        com.unciv.logic.civilization.NotificationCategory.Diplomacy,
                        com.unciv.logic.civilization.NotificationIcon.Diplomacy)
                }
                // 2026-08-29: 拒绝后清除本地挂起 (防重载后死灰复燃)
                val otherCiv = gameInfo.civilizations.firstOrNull { it.civName == otherCivName }
                if (otherCiv != null)
                    pendingAlliancePopups.remove(Pair(com.unciv.logic.civilization.AlertType.AllianceOffer, otherCiv.civID))
                worldScreen.shouldUpdate = true
            } catch (e: Exception) {}
        }
    }

    /** 到期续约弹窗广播 */
    private fun handleAllianceRenew(msg: JsonObject) {
        val targetPlayerId = msg["playerId"]?.jsonPrimitive?.contentOrNull
        if (targetPlayerId != null && targetPlayerId != playerId) return
        val otherCivId = msg["otherCivId"]?.jsonPrimitive?.contentOrNull ?: return
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            try {
                val myCiv = worldScreen.viewingCiv ?: return@runOnGLThread
                if (myCiv.isSpectator()) return@runOnGLThread
                // 2026-08-27: 补推防御 — 已无同盟 (结束/已续约) → 忽略, 防重载重连后续约弹窗反复出现
                val al = gameInfo.alliances.firstOrNull { it.contains(myCiv.civID) && it.contains(otherCivId) }
                if (al == null || al.turnsLeft > 1) return@runOnGLThread
                if (myCiv.popupAlerts.none { it.type == com.unciv.logic.civilization.AlertType.AllianceRenew && it.value == otherCivId })
                    myCiv.popupAlerts.add(com.unciv.logic.civilization.PopupAlert(
                        com.unciv.logic.civilization.AlertType.AllianceRenew, otherCivId))
                // 2026-08-29 本地挂起 (重载后恢复, 防 fs 单槽补推丢弹窗)
                pendingAlliancePopups.add(Pair(com.unciv.logic.civilization.AlertType.AllianceRenew, otherCivId))
                worldScreen.shouldUpdate = true
            } catch (e: Exception) {}
        }
    }

    /** 续约成功通知 */
    private fun handleAllianceRenewed(msg: JsonObject) {
        val targetPlayerId = msg["playerId"]?.jsonPrimitive?.contentOrNull
        if (targetPlayerId != null && targetPlayerId != playerId) return
        val otherCivName = msg["otherCivName"]?.jsonPrimitive?.contentOrNull ?: ""
        val level = msg["level"]?.jsonPrimitive?.contentOrNull ?: "1"
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            try {
                val myCiv = worldScreen.viewingCiv
                if (myCiv != null && !myCiv.isSpectator()) {
                    myCiv.addNotification("你与 ${otherCivName.tr()} 的同盟续约成功（Lv$level）",
                        com.unciv.logic.civilization.NotificationCategory.Diplomacy,
                        com.unciv.logic.civilization.NotificationIcon.Diplomacy)
                }
                // 2026-08-29: 续约后清除本地挂起 (防重载后死灰复燃)
                val otherCiv = gameInfo.civilizations.firstOrNull { it.civName == otherCivName }
                if (otherCiv != null)
                    pendingAlliancePopups.remove(Pair(com.unciv.logic.civilization.AlertType.AllianceRenew, otherCiv.civID))
                refreshOpenDiplomacyScreen()  // 2026-08-27: 续约后外交界面实时刷新
                worldScreen.shouldUpdate = true
            } catch (e: Exception) {}
        }
    }

    /** 同盟结束通知 (rejected=对方拒绝续约 / expired=到期未续约) */
    private fun handleAllianceEnded(msg: JsonObject) {
        val targetPlayerId = msg["playerId"]?.jsonPrimitive?.contentOrNull
        if (targetPlayerId != null && targetPlayerId != playerId) return
        val otherCivName = msg["otherCivName"]?.jsonPrimitive?.contentOrNull ?: ""
        val reason = msg["reason"]?.jsonPrimitive?.contentOrNull ?: ""
        val reasonText = when (reason) {
            "rejected" -> "对方拒绝续约"
            "expired" -> "到期未续约"
            else -> "同盟结束"
        }
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            try {
                val myCiv = worldScreen.viewingCiv
                if (myCiv != null && !myCiv.isSpectator()) {
                    myCiv.addNotification("你与 ${otherCivName.tr()} 的同盟结束了（$reasonText）",
                        com.unciv.logic.civilization.NotificationCategory.Diplomacy,
                        com.unciv.logic.civilization.NotificationIcon.Diplomacy)
                }
                // 2026-08-29: 同盟结束 → 清除本地挂起 (续约弹窗过期不再恢复)
                val otherCiv = gameInfo.civilizations.firstOrNull { it.civName == otherCivName }
                if (otherCiv != null) {
                    pendingAlliancePopups.remove(Pair(com.unciv.logic.civilization.AlertType.AllianceRenew, otherCiv.civID))
                    pendingAlliancePopups.remove(Pair(com.unciv.logic.civilization.AlertType.AllianceFollowUp, otherCiv.civID))
                }
                refreshOpenDiplomacyScreen()  // 2026-08-27: 同盟结束后外交界面实时刷新
                worldScreen.shouldUpdate = true
            } catch (e: Exception) {}
        }
    }

    /** 盟友宣战/被宣战跟进弹窗广播 */
    private fun handleAllianceFollowUp(msg: JsonObject) {
        val targetPlayerId = msg["playerId"]?.jsonPrimitive?.contentOrNull
        if (targetPlayerId != null && targetPlayerId != playerId) return
        val targetCivId = msg["targetCivId"]?.jsonPrimitive?.contentOrNull ?: return
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            try {
                val myCiv = worldScreen.viewingCiv ?: return@runOnGLThread
                if (myCiv.isSpectator()) return@runOnGLThread
                if (myCiv.popupAlerts.none { it.type == com.unciv.logic.civilization.AlertType.AllianceFollowUp && it.value == targetCivId })
                    myCiv.popupAlerts.add(com.unciv.logic.civilization.PopupAlert(
                        com.unciv.logic.civilization.AlertType.AllianceFollowUp, targetCivId))
                // 2026-08-29 本地挂起 (重载后恢复, 防 fs 单槽补推丢弹窗)
                pendingAlliancePopups.add(Pair(com.unciv.logic.civilization.AlertType.AllianceFollowUp, targetCivId))
                worldScreen.shouldUpdate = true
            } catch (e: Exception) {}
        }
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
                // 成交通知 (原版 TradePopup 接受路径有, 帧同步纯拦截路径缺失 — 2026-08-26 用户反馈):
                // 只给发起方 (handleTradeAccepted 已被 playerId 过滤); acceptingCiv = 接受方
                if (acceptingCiv != null) {
                    try {
                        myCiv.addNotification("[${acceptingCiv.civName}] has accepted your trade request",
                            com.unciv.logic.civilization.NotificationCategory.Trade, acceptingCiv.civName,
                            com.unciv.logic.civilization.NotificationIcon.Trade)
                    } catch (ignored: Exception) {
                    }
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
                // 使馆条款: 立即应用 Embassy modifier (服务器已应用; 本地同步避免下回合才生效 — 2026-08-24 用户反馈)
                val embassy = msg["embassy"]?.jsonPrimitive?.contentOrNull == "true"
                val providerName = msg["embassyProvider"]?.jsonPrimitive?.contentOrNull
                if (embassy && providerName != null && acceptingCiv != null && !myCiv.isSpectator()) {
                    try {
                        val myDm = myCiv.getDiplomacyManager(acceptingCiv)
                        val theirDm = acceptingCiv.getDiplomacyManager(myCiv)
                        if (myDm != null && theirDm != null) {
                            fun applyEmbassy(dm: com.unciv.logic.civilization.diplomacy.DiplomacyManager) {
                                if (dm.hasModifier(com.unciv.logic.civilization.diplomacy.DiplomaticModifiers.EstablishedEmbassy)) {
                                    dm.replaceModifier(com.unciv.logic.civilization.diplomacy.DiplomaticModifiers.EstablishedEmbassy,
                                        com.unciv.logic.civilization.diplomacy.DiplomaticModifiers.SharedEmbassies, 3f)
                                    dm.otherCivDiplomacy().replaceModifier(com.unciv.logic.civilization.diplomacy.DiplomaticModifiers.ReceivedEmbassy,
                                        com.unciv.logic.civilization.diplomacy.DiplomaticModifiers.SharedEmbassies, 3f)
                                } else {
                                    dm.addModifier(com.unciv.logic.civilization.diplomacy.DiplomaticModifiers.ReceivedEmbassy, 1f)
                                    dm.otherCivDiplomacy().addModifier(com.unciv.logic.civilization.diplomacy.DiplomaticModifiers.EstablishedEmbassy, 2f)
                                }
                            }
                            if (providerName == myCiv.civName) applyEmbassy(myDm)
                            else if (providerName == acceptingCiv.civName) applyEmbassy(theirDm)
                        }
                    } catch (ignored: Exception) {
                    }
                }
            }
            worldScreen.shouldUpdate = true
        }
    }

    private fun handleTradeRejected(msg: JsonObject) {
        // 2026-08-26: 被拒通知 (原版 TradePopup 拒绝路径有, 帧同步缺失 → 发起方不知道被拒, 反复重发)
        val rejectingCivName = msg["requestingCiv"]?.jsonPrimitive?.contentOrNull ?: return
        val targetPlayerId = msg["playerId"]?.jsonPrimitive?.contentOrNull
        if (targetPlayerId != null && targetPlayerId != playerId) return
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            val myCiv = worldScreen.viewingCiv
            if (myCiv != null && !myCiv.isSpectator()) {
                myCiv.addNotification("[$rejectingCivName] has denied your trade request",
                    com.unciv.logic.civilization.NotificationCategory.Trade, rejectingCivName,
                    com.unciv.logic.civilization.NotificationIcon.Trade)
            }
            worldScreen.shouldUpdate = true
        }
    }

    /** 商路邀请 (2026-08-26 商路 v2): 国外商路 → 弹窗 (接受/拒绝) */
    private fun handleTradeRouteOffer(msg: JsonObject) {
        val targetPlayerId = msg["playerId"]?.jsonPrimitive?.contentOrNull
        if (targetPlayerId != null && targetPlayerId != playerId) return
        val fromCityId = msg["fromCityId"]?.jsonPrimitive?.contentOrNull ?: return
        val toCityId = msg["toCityId"]?.jsonPrimitive?.contentOrNull ?: return
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            try {
                val alerts = worldScreen.viewingCiv.popupAlerts
                alerts.removeAll { it.type == com.unciv.logic.civilization.AlertType.TradeRouteOffer && it.value == "$fromCityId|$toCityId" }
                alerts.add(com.unciv.logic.civilization.PopupAlert(
                    com.unciv.logic.civilization.AlertType.TradeRouteOffer, "$fromCityId|$toCityId"))
                worldScreen.shouldUpdate = true
            } catch (e: Exception) {
            }
        }
    }

    /** 商路邀请被接受 (通知发起方) */
    private fun handleTradeRouteAccepted(msg: JsonObject) {
        val targetPlayerId = msg["playerId"]?.jsonPrimitive?.contentOrNull
        if (targetPlayerId != null && targetPlayerId != playerId) return
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            try {
                val myCiv = worldScreen.viewingCiv
                if (myCiv != null && !myCiv.isSpectator()) {
                    val fromCityId = msg["fromCityId"]?.jsonPrimitive?.contentOrNull ?: ""
                    val toCityId = msg["toCityId"]?.jsonPrimitive?.contentOrNull ?: ""
                    val otherCity = gameInfo.getCities().firstOrNull { it.id == toCityId }
                    // 2026-08-31 修复: 英文模板+城市名 → tr() 查不到带城市名的 key → 翻译丢; 改中文 (对齐拒绝通知)
                    myCiv.addNotification("你的商路请求到${otherCity?.name?.tr() ?: toCityId}已被接受",
                        com.unciv.logic.civilization.NotificationCategory.Trade, "",
                        com.unciv.logic.civilization.NotificationIcon.Trade)
                }
                // 2026-08-27: 商路成立 → 城市 stats 重算 + 打开的城市界面立即刷新 (用户反馈"发起商路后城市统计不更新")
                if (myCiv != null) scheduleFsStatsRefresh(myCiv)
                refreshOpenCityScreen()
                worldScreen.shouldUpdate = true
            } catch (e: Exception) {
            }
        }
    }

    /** 商路邀请被拒绝 (通知发起方) */
    private fun handleTradeRouteRejected(msg: JsonObject) {
        val targetPlayerId = msg["playerId"]?.jsonPrimitive?.contentOrNull
        if (targetPlayerId != null && targetPlayerId != playerId) return
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            try {
                val myCiv = worldScreen.viewingCiv
                if (myCiv != null && !myCiv.isSpectator()) {
                    val fromCityId = msg["fromCityId"]?.jsonPrimitive?.contentOrNull ?: ""
                    val toCityId = msg["toCityId"]?.jsonPrimitive?.contentOrNull ?: ""
                    val fromCity = gameInfo.getCities().firstOrNull { it.id == fromCityId }
                    val toCity = gameInfo.getCities().firstOrNull { it.id == toCityId }
                    // 2026-08-26 用户要求: 拒绝通知 (不带【】, 文明/城市名翻译)
                    val text = "${fromCity?.civ?.civName?.tr() ?: ""} 拒绝了你发起的从 ${fromCity?.name?.tr() ?: fromCityId} 到 ${toCity?.name?.tr() ?: toCityId} 的贸易路线请求"
                    myCiv.addNotification(text,
                        com.unciv.logic.civilization.NotificationCategory.Trade,
                        com.unciv.logic.civilization.NotificationIcon.Trade)
                }
                // 2026-08-27: 商路变化 → 城市 stats 重算 + 打开的城市界面立即刷新
                if (myCiv != null) scheduleFsStatsRefresh(myCiv)
                refreshOpenCityScreen()
                worldScreen.shouldUpdate = true
            } catch (e: Exception) {
            }
        }
    }

    /** 商路被对方断开 (通知) */
    private fun handleTradeRouteDisconnected(msg: JsonObject) {
        val targetPlayerId = msg["playerId"]?.jsonPrimitive?.contentOrNull
        if (targetPlayerId != null && targetPlayerId != playerId) return
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            try {
                val myCiv = worldScreen.viewingCiv
                if (myCiv != null && !myCiv.isSpectator()) {
                    val fromCityId = msg["cityId"]?.jsonPrimitive?.contentOrNull ?: ""
                    val toCityId = msg["otherCityId"]?.jsonPrimitive?.contentOrNull ?: ""
                    val fromCity = gameInfo.getCities().firstOrNull { it.id == fromCityId }
                    val toCity = gameInfo.getCities().firstOrNull { it.id == toCityId }
                    // 2026-08-26 用户要求: 断开通知 (文明/城市名翻译)
                    val text = "${fromCity?.civ?.civName?.tr() ?: ""} 断开了从 ${fromCity?.name?.tr() ?: fromCityId} 到 ${toCity?.name?.tr() ?: toCityId} 的贸易路线"
                    myCiv.addNotification(text,
                        com.unciv.logic.civilization.NotificationCategory.Trade,
                        com.unciv.logic.civilization.NotificationIcon.Trade)
                }
                // 2026-08-27: 商路变化 → 城市 stats 重算 + 打开的城市界面立即刷新
                if (myCiv != null) scheduleFsStatsRefresh(myCiv)
                refreshOpenCityScreen()
                worldScreen.shouldUpdate = true
            } catch (e: Exception) {
            }
        }
    }

    /** 商路失效断开 (宣战/距离失效, 结算时服务器断开) */
    private fun handleTradeRouteBroken(msg: JsonObject) {
        val targetPlayerId = msg["playerId"]?.jsonPrimitive?.contentOrNull
        if (targetPlayerId != null && targetPlayerId != playerId) return
        Concurrency.runOnGLThread {
            val worldScreen = currentWorldScreenOrNull() ?: return@runOnGLThread
            val gameInfo = worldScreen.gameInfo ?: return@runOnGLThread
            if (gameInfo.gameId != gameId) return@runOnGLThread
            try {
                val myCiv = worldScreen.viewingCiv
                if (myCiv != null && !myCiv.isSpectator()) {
                    // 2026-08-31 修复: 英文 → 中文 (原 key 无翻译)
                    myCiv.addNotification("贸易路线已断开",
                        com.unciv.logic.civilization.NotificationCategory.Trade, "",
                        com.unciv.logic.civilization.NotificationIcon.Trade)
                }
                // 2026-08-27: 商路变化 → 城市 stats 重算 + 打开的城市界面立即刷新
                if (myCiv != null) scheduleFsStatsRefresh(myCiv)
                refreshOpenCityScreen()
                worldScreen.shouldUpdate = true
            } catch (e: Exception) {
            }
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
            // op 结果失败 (如移动被拒): 拒绝提示已全部移除 — 卡顿时疯狂点击产生海量拒绝 toast
            // → GL 弹窗风暴 → 更卡 (2026-08-30 用户要求); 静默忽略, 状态由后续广播收敛
            return
        }
        val worldScreen = worldScreenRef?.get() ?: return
        // 2026-08-30 公共/私有拆分: 每文明每回合产出摘要 (排行/概览用 — 别人的工作格/专家被裁后本地算不了)
        parseServerStats(state)
        val newTurn = state["turn"]?.jsonPrimitive?.intOrNull
        if (newTurn != null && newTurn > lastTurn) {
            // 新回合: “已查看”闲置单位标记重置 — 上回合点过“下一个单位”的单位本回合重新参与循环
            // (不重置 → reapplyLocalDueSeen 把本回合 due=true 的单位设回 false → 闲置循环漏单位)
            localDueSeen.clear()
            // 新回合 state 到达 = 结算完成 (settle_finish 广播): 移除提示条/解锁 — 2026-08-22 修复“回合正在结算”残留
            // (旧逻辑这里无条件 showSettlingHint, 而 turnStatus 隐藏需 wasSettling → 状态对不上时提示条永不消失)
            // 2026-08-27: 重载中 (reloading) 跳过重置 — 下载存档期间收到结算后 state (turn>旧lastTurn) 会
            // 误重置锁定 → 重载后 start() 恢复提示条失败 → “提示条秒没”根因
            if (!reloading) {
                dbg("handleSimMessage 新回合 state → 解锁 (turn=" + newTurn + ")")
                serverSettling = false
                settlingPersist = false
                hideSettlingHint()
            }
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
        // 只取自己所在队伍的成员 (此前把所有队合并 → 全员队友 → 全员视野共享 bug, 2026-08-23 用户实测)
        try {
            val teams = state["teams"]?.jsonArray
            if (teams != null) {
                val newTeam = HashSet<String>()
                var myTeamIdx = -1
                teams.forEachIndexed { idx, teamArr ->
                    val arr = teamArr.jsonArray ?: return@forEachIndexed
                    for (pid in arr) {
                        if (pid.jsonPrimitive.contentOrNull == playerId) myTeamIdx = idx
                    }
                }
                if (myTeamIdx >= 0) {
                    val arr = teams[myTeamIdx].jsonArray ?: emptyList()
                    for (pid in arr) pid.jsonPrimitive.contentOrNull?.let { newTeam.add(it) }
                }
                if (newTeam != myTeamPlayerIds) {
                    myTeamPlayerIds.clear()
                    myTeamPlayerIds.addAll(newTeam)
                    teamExploredMerged = false  // 队伍变化 → 重新一次性合并探索历史
                }
            }
        } catch (e: Exception) {}
        // UncivGC 商路 (2026-08-24, 设计稿 §5.2): 屏蔽状态 + 恢复请求同步 → 本地连接网络失效重算
        // (blocked/restore 数据量极小, 服务器每帧全量携带)
        // 商路连接同步 (2026-08-26 设计稿 v2): 已建立连接 + 邀请 — 服务器权威全量替换 (旧屏蔽字段废弃)
        try {
            val gi = worldScreen.gameInfo
            var changed = false
            state["tradeRoutes"]?.jsonArray?.let { routesArr ->
                val newRoutes = HashMap<String, ArrayList<String>>()
                for (pair in routesArr) {
                    val arr = pair.jsonArray ?: continue
                    val from = arr.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: continue
                    val to = arr.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: continue
                    newRoutes.getOrPut(from) { ArrayList() }.add(to)
                }
                if (newRoutes != gi.tradeRoutes) {
                    gi.tradeRoutes = newRoutes
                    changed = true
                    // 2026-08-27: 商路变化 → 城市 stats 后台重算 + 打开的城市界面刷新 (用户反馈"发起商路后城市统计不更新")
                    try {
                        val myCiv2 = gi.civilizations.firstOrNull { it.playerId == playerId }
                        dbg("商路同步: playerId=$playerId myCiv2=${myCiv2?.civName} routes=${newRoutes.size}条")
                        if (myCiv2 != null) scheduleFsStatsRefresh(myCiv2)
                    } catch (ignored: Exception) {
                    }
                    cityStateChanged = true
                }
            }
            // 2026-08-29: 服务器权威商路收益 (tradeRouteStats) — 商路页显示用, 双方一致 (以发起方为准)
            state["tradeRouteStats"]?.jsonArray?.let { statsArr ->
                val newStats = HashMap<Pair<String, String>, com.unciv.models.stats.Stats>()
                for (item in statsArr) {
                    val arr = item.jsonArray ?: continue
                    val from = arr.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: continue
                    val to = arr.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: continue
                    val gold = arr.getOrNull(3)?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
                    val food = arr.getOrNull(4)?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
                    val prod = arr.getOrNull(5)?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
                    val culture = arr.getOrNull(6)?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
                    newStats[Pair(from, to)] = com.unciv.models.stats.Stats().apply {
                        this.gold = gold; this.food = food; this.production = prod; this.culture = culture
                    }
                }
                if (newStats.isNotEmpty()) {
                    gi.tradeRouteStats.clear()
                    gi.tradeRouteStats.putAll(newStats)
                }
            }
            state["tradeRouteOffers"]?.jsonArray?.let { offersArr ->
                val newOffers = HashMap<String, ArrayList<String>>()
                for (pair in offersArr) {
                    val arr = pair.jsonArray ?: continue
                    val from = arr.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: continue
                    val to = arr.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: continue
                    newOffers.getOrPut(from) { ArrayList() }.add(to)
                }
                if (newOffers != gi.tradeRouteOffers) {
                    gi.tradeRouteOffers = newOffers
                    changed = true
                }
            }
            // 商路断开冷却 (2026-08-26 用户要求): 服务器权威 (断开后 3 回合内不能发起) — 客户端用于红色冷却显示
            state["tradeRouteCooldowns"]?.jsonArray?.let { cdArr ->
                val newCd = HashMap<String, Int>()
                for (pair in cdArr) {
                    val arr = pair.jsonArray ?: continue
                    val cid = arr.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: continue
                    val turn = arr.getOrNull(1)?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: continue
                    newCd[cid] = turn
                }
                if (newCd != gi.tradeRouteCooldowns) {
                    gi.tradeRouteCooldowns = newCd
                    changed = true
                }
            }
            // 同盟 (2026-08-26 设计稿 v1.0): 全量替换 [civA,civB,level,turnsLeft] + 提议 + 冷却
            state["alliances"]?.jsonArray?.let { alArr ->
                val newAlliances = ArrayList<com.unciv.logic.diplomacy.Alliance>()
                for (pair in alArr) {
                    val arr = pair.jsonArray ?: continue
                    val a = arr.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: continue
                    val b = arr.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: continue
                    val lvl = arr.getOrNull(2)?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
                    val tl = arr.getOrNull(3)?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: com.unciv.logic.diplomacy.Alliance.DURATION
                    newAlliances.add(com.unciv.logic.diplomacy.Alliance(a, b, lvl, tl))
                }
                if (newAlliances != gi.alliances) {
                    gi.alliances = newAlliances
                    changed = true
                    // 2026-08-27: 同盟数据变化 → 打开的外交界面立即刷新 (notifyPlayer 先到、state 后到,
                    // handleAllianceAccepted 里刷新时数据还没更新 → 这里补刷新)
                    refreshOpenDiplomacyScreen()
                }
            }
            state["allianceOffers"]?.jsonArray?.let { offArr ->
                val newOffers = HashMap<String, String>()
                for (pair in offArr) {
                    val arr = pair.jsonArray ?: continue
                    val from = arr.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: continue
                    val to = arr.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: continue
                    newOffers[from] = to
                }
                if (newOffers != gi.allianceOffers) {
                    gi.allianceOffers = newOffers
                    changed = true
                }
                // 2026-08-27: 清理本地过期邀请弹窗 — 服务器结算清 offers 时客户端不知道,
                // 本地 popupAlerts 残留 AllianceOffer → WorldScreen 每帧重弹 (用户反馈"弹窗一直有")
                try {
                    for (civ in gi.civilizations) {
                        if (civ.isSpectator() || civ.isBarbarian) continue
                        civ.popupAlerts.removeIf { pa ->
                            pa.type == com.unciv.logic.civilization.AlertType.AllianceOffer
                                    && gi.allianceOffers[pa.value] != civ.civID
                        }
                        // 2026-08-29: 同步清理本地挂起集合 (过期邀请不再恢复)
                        pendingAlliancePopups.removeIf { (atype, civId) ->
                            atype == com.unciv.logic.civilization.AlertType.AllianceOffer
                                    && gi.allianceOffers[civId] != civ.civID
                        }
                    }
                } catch (ignored: Exception) {
                }
            }
            state["allianceCooldowns"]?.jsonArray?.let { cdArr ->
                val newCd = HashMap<String, Int>()
                for (pair in cdArr) {
                    val arr = pair.jsonArray ?: continue
                    val cid = arr.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: continue
                    val turn = arr.getOrNull(1)?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: continue
                    newCd[cid] = turn
                }
                if (newCd != gi.allianceCooldowns) {
                    gi.allianceCooldowns = newCd
                    changed = true
                }
            }
            if (changed) {
                gi.invalidateTradeRoutes()
                worldScreen.shouldUpdate = true
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
        // 2026-08-29 帧合并/去抖: ws 协程只更新"最新 state 快照", GL 线程专用循环消费最新一帧 —
        // 高人口局回合中每秒 5-10 帧时, GL 线程不再被逐帧 applyState 占满 (掉帧/OUTBOX 积压根因)
        // 2026-08-29 修复 (审查发现): 服务器广播的增量段 (units/cities/civs/terrainChanges 只发变化条目,
        // removed* 删除列表发出即清) 不能直接覆盖丢弃 — 中间帧的增量变化会永久丢失 (幽灵单位/城市残留,
        // 直到回合结算全量才恢复). 改为累积合并: 增量段按 id/name 取最新合并, removed* 取并集, full 帧替换
        val newSnap = StateSnapshot(units, cities, civs, encampments, improvements,
            improvementsDone, roads, religions, terrainChanges, isFull, removedUnits, removedCities,
            state["removedCivs"]?.jsonArray ?: emptyList(),
            state["removedEncampments"]?.jsonArray ?: emptyList(),
            state["removedImprovements"]?.jsonArray ?: emptyList(),
            state["removedImprovementsDone"]?.jsonArray ?: emptyList(),
            state["removedRoads"]?.jsonArray ?: emptyList(),
            state["removedReligions"]?.jsonArray ?: emptyList())
        // 2026-08-29 修复 (审查发现): read-modify-write 竞态 — ws 线程读 prev → GL 线程消费置 null →
        // ws 写回 prev.mergedWith(new) 会把已消费的旧快照重新合并进 pending (重复处理/闪烁).
        // 用锁保护"读-改-写 + 调度标记"两步 (applyState 本体在 GL 线程, 不进锁)
        synchronized(stateMergeLock) {
            val prev = pendingStateSnapshot
            pendingStateSnapshot = if (prev == null || newSnap.isFull) newSnap else prev.mergedWith(newSnap)
            if (!applyStateScheduled) {
                applyStateScheduled = true
                Concurrency.runOnGLThread {
                    applyStateLoop()
                }
            }
        }
    }

    /** 2026-08-29 帧合并: GL 线程消费最新 state 快照 — 一次只处理一帧, 处理完再取最新;
     *  2026-08-29 修复 (审查发现): 增量段不能直接覆盖 — 累积合并, removed* 取并集永不撤销,
     *  full 帧整体替换. 保证 GL 繁忙时丢弃的"中间帧"里的增量变化不丢失 */
    private class StateSnapshot(
        val units: List<JsonElement>,
        val cities: List<JsonElement>,
        val civs: List<JsonElement>,
        val encampments: List<JsonElement>,
        val improvements: List<JsonElement>,
        val improvementsDone: List<JsonElement>,
        val roads: List<JsonElement>,
        val religions: List<JsonElement>,
        val terrainChanges: List<JsonElement>,
        val isFull: Boolean,
        val removedUnits: List<JsonElement>,
        val removedCities: List<JsonElement>,
        val removedCivs: List<JsonElement>,
        val removedEncampments: List<JsonElement>,
        val removedImprovements: List<JsonElement>,
        val removedImprovementsDone: List<JsonElement>,
        val removedRoads: List<JsonElement>,
        val removedReligions: List<JsonElement>,
    ) {
        /** 累积合并: 本快照(旧) + 新帧 → 合并结果. 增量段按 id/name 取最新, removed* 并集,
         *  全量段 (encampments/improvements/improvementsDone/roads/religions 每帧全量) 取新帧值,
         *  被 removed 的条目从对应增量段剔除 (删除优先) */
        fun mergedWith(newSnap: StateSnapshot): StateSnapshot {
            // 2026-08-29 审查重构: 三段增量合并 (units/cities/civs) 结构相同, 提取泛型 helper
            fun <K> mergeIncremental(
                old: List<JsonElement>,
                new: List<JsonElement>,
                removed: List<JsonElement>,
                keyOf: (JsonObject) -> K?,
                removedKey: (JsonElement) -> K?,
            ): List<JsonElement> {
                val merged = LinkedHashMap<K, JsonElement>()
                for (e in old) {
                    val obj = e.jsonObject
                    if (obj == null) { System.err.println("[fsSync] mergedWith: 条目非对象, 跳过"); continue }
                    val key = keyOf(obj)
                    if (key != null) merged[key] = e
                }
                for (e in new) {
                    val obj = e.jsonObject ?: continue
                    val key = keyOf(obj) ?: continue
                    merged[key] = e
                }
                removed.mapNotNull { removedKey(it) }.forEach { merged.remove(it) }  // 删除优先
                return merged.values.toList()
            }
            val mergedUnits = mergeIncremental(
                units, newSnap.units, newSnap.removedUnits,
                keyOf = { it.get("id")?.jsonPrimitive?.intOrNull },
                removedKey = { it.jsonPrimitive.intOrNull },
            )
            val mergedCities = mergeIncremental(
                cities, newSnap.cities, newSnap.removedCities,
                keyOf = { it.get("id")?.jsonPrimitive?.contentOrNull },
                removedKey = { it.jsonPrimitive.contentOrNull },
            )
            val mergedCivs = mergeIncremental(
                civs, newSnap.civs, newSnap.removedCivs,
                keyOf = { it.get("civ")?.jsonPrimitive?.contentOrNull },
                removedKey = { it.jsonPrimitive.contentOrNull },
            )
            // 2026-08-30 地图段增量: 坐标 key (数组 [x,y,...]) 累积合并 + removed 剔除; full 帧整体替换
            fun coordKeyOf(e: JsonElement): String? {
                val a = e.jsonArray ?: return null
                val x = a.getOrNull(0)?.jsonPrimitive?.intOrNull ?: return null
                val y = a.getOrNull(1)?.jsonPrimitive?.intOrNull ?: return null
                return "$x,$y"
            }
            fun mergeCoordIncremental(old: List<JsonElement>, new: List<JsonElement>, removed: List<JsonElement>): List<JsonElement> {
                val merged = LinkedHashMap<String, JsonElement>()
                for (e in old) { val k = coordKeyOf(e); if (k != null) merged[k] = e }
                for (e in new) { val k = coordKeyOf(e); if (k != null) merged[k] = e }
                removed.mapNotNull { coordKeyOf(it) }.forEach { merged.remove(it) }  // 删除优先
                return merged.values.toList()
            }
            fun mergeRemovedCoords(a: List<JsonElement>, b: List<JsonElement>): List<JsonElement> {
                val m = LinkedHashMap<String, JsonElement>()
                for (e in a + b) { val k = coordKeyOf(e); if (k != null) m[k] = e }
                return m.values.toList()
            }
            val mergedReligions = if (this.isFull) newSnap.religions else mergeIncremental(
                religions, newSnap.religions, newSnap.removedReligions,
                keyOf = { it.get("name")?.jsonPrimitive?.contentOrNull },
                removedKey = { it.jsonPrimitive.contentOrNull },
            )
            return StateSnapshot(
                mergedUnits, mergedCities, mergedCivs,
                if (this.isFull) newSnap.encampments else mergeCoordIncremental(encampments, newSnap.encampments, newSnap.removedEncampments),
                if (this.isFull) newSnap.improvements else mergeCoordIncremental(improvements, newSnap.improvements, newSnap.removedImprovements),
                if (this.isFull) newSnap.improvementsDone else mergeCoordIncremental(improvementsDone, newSnap.improvementsDone, newSnap.removedImprovementsDone),
                if (this.isFull) newSnap.roads else mergeCoordIncremental(roads, newSnap.roads, newSnap.removedRoads),
                mergedReligions,
                terrainChanges + newSnap.terrainChanges,  // 地形变化是追加语义 (OneTimeChangeTerrain)
                // 2026-08-29 修复 (审查发现): 必须保留 this.isFull — 全量帧 pending 未消费时同回合增量帧到达,
                // 合并后仍是全量语义 (stateIds/serverCityIds 全量扫描校准不能丢), 否则回合结算/重连的
                // 幽灵单位/城市清除失效. 合并后 units 仍是全量集合 + 增量覆盖, 全量扫描正确
                isFull = this.isFull,
                removedUnits = (removedUnits.mapNotNull { it.jsonPrimitive.intOrNull }
                    + newSnap.removedUnits.mapNotNull { it.jsonPrimitive.intOrNull })
                    .distinct().map { kotlinx.serialization.json.JsonPrimitive(it) },
                removedCities = (removedCities.mapNotNull { it.jsonPrimitive.contentOrNull }
                    + newSnap.removedCities.mapNotNull { it.jsonPrimitive.contentOrNull })
                    .distinct().map { kotlinx.serialization.json.JsonPrimitive(it) },
                removedCivs = (removedCivs.mapNotNull { it.jsonPrimitive.contentOrNull }
                    + newSnap.removedCivs.mapNotNull { it.jsonPrimitive.contentOrNull })
                    .distinct().map { kotlinx.serialization.json.JsonPrimitive(it) },
                removedEncampments = mergeRemovedCoords(removedEncampments, newSnap.removedEncampments),
                removedImprovements = mergeRemovedCoords(removedImprovements, newSnap.removedImprovements),
                removedImprovementsDone = mergeRemovedCoords(removedImprovementsDone, newSnap.removedImprovementsDone),
                removedRoads = mergeRemovedCoords(removedRoads, newSnap.removedRoads),
                removedReligions = (removedReligions.mapNotNull { it.jsonPrimitive.contentOrNull }
                    + newSnap.removedReligions.mapNotNull { it.jsonPrimitive.contentOrNull })
                    .distinct().map { kotlinx.serialization.json.JsonPrimitive(it) },
            )
        }
    }

    @Volatile private var pendingStateSnapshot: StateSnapshot? = null
    @Volatile private var applyStateScheduled = false
    /** 2026-08-29 审查修复: 快照读-改-写锁 (ws 写 / GL 消费), 防重复合并已消费快照 */
    private val stateMergeLock = Any()

    private fun applyStateLoop() {
        // 消费端: 与 ws 写端同一把锁, 保证取-置 null 原子 (防 ws 基于旧快照合并写回)
        val snapshot: StateSnapshot?
        synchronized(stateMergeLock) {
            applyStateScheduled = false
            if (!running) return
            snapshot = pendingStateSnapshot
            pendingStateSnapshot = null
        }
        val snap = snapshot ?: return
        val worldScreen = worldScreenRef?.get() ?: return
        try {
            applyState(worldScreen, snap.units, snap.cities, snap.civs, snap.encampments,
                snap.improvements, snap.improvementsDone, snap.roads, snap.religions,
                snap.terrainChanges, isFull = snap.isFull,
                removedUnits = snap.removedUnits, removedCities = snap.removedCities,
                removedCivs = snap.removedCivs,
                removedEncampments = snap.removedEncampments, removedImprovements = snap.removedImprovements,
                removedImprovementsDone = snap.removedImprovementsDone,
                removedRoads = snap.removedRoads, removedReligions = snap.removedReligions)
        } catch (e: Exception) {
            // 状态应用绝不能崩溃 — 失败项由下一条广播/回合重载兜底
        }
        updateStatusLabel()
        // 战败检测: 弹提示 → 确认后切观战 (看海); 每局只弹一次
        checkDefeatedAndOfferSpectate()
        // 处理期间又有新帧到达 → 继续消费 (自适应: 空闲时立即处理, 繁忙时合并)
        synchronized(stateMergeLock) {
            if (pendingStateSnapshot != null && !applyStateScheduled && running) {
                applyStateScheduled = true
                Concurrency.runOnGLThread {
                    applyStateLoop()
                }
            }
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

    /** 单文明单位视野增量刷新 (2026-08-23 性能优化):
     *  位置/广播条目没变的单位跳过重算 (视野不可能变); 变化单位才调 updateVisibleTiles
     *  (语义与全量一致: 含 TriggerUponDiscoveringTile unique 触发 + civ cache 全量重建)。
     *  explorerCiv: 组队时队友单位重算后, 其新视野格永久探索给谁 (自己单位传 null = 内部已处理) */
    private fun refreshCivUnitsVisibility(civ: Civilization, explorerCiv: Civilization? = null, checkMeetTiles: MutableSet<Tile>? = null) {
        val aliveIds = HashSet<Int>()
        for (unit in civ.units.getCivUnits()) {
            if (unit.isDestroyed || !unit.hasTile()) continue
            val id = unit.id
            aliveIds.add(id)
            val pos = unit.getTile().position.x.toString() + "," + unit.getTile().position.y
            val prevPos = unitVisibilityPos[id]
            val prevEntry = unitProcessedEntry[id]
            val curEntry = unitEntrySnapshot[id]
            if (prevPos == pos && prevEntry == curEntry) continue  // 没动且条目没变 → 视野不变 → 跳过
            unitVisibilityPos[id] = pos
            unitProcessedEntry[id] = curEntry ?: ""
            try { unit.updateVisibleTiles() } catch (e: Exception) {}
            // 2026-08-31 分层重绘: 重算单位的新视野格标记静态层脏 —
            // 新探索格 (之前隐藏) 要显示地形/资源 (静态层重建; 范围=该单位视野, 远小于全图)
            try {
                worldScreenRef?.get()?.mapHolder?.markTilesDirty(unit.viewableTiles)
            } catch (ignored: Exception) {
            }
            // 组队: 队友新视野 → 我也永久探索 (幂等, 只覆盖重算过的单位)
            if (explorerCiv != null) {
                try {
                    for (tile in unit.viewableTiles) tile.setExplored(explorerCiv, true)
                } catch (e: Exception) {}
            }
            // 相遇检测: 视野扩展侧 — 重算单位的新视野格加入候选 (幂等过滤在检查处)
            checkMeetTiles?.addAll(unit.viewableTiles)
        }
        // 快照防泄漏: 单位消失后清理 (阈值触发, 避免每帧分配)
        if (unitVisibilityPos.size > aliveIds.size * 2 + 64 || unitProcessedEntry.size > aliveIds.size * 2 + 64) {
            unitVisibilityPos.keys.retainAll(aliveIds)
            unitProcessedEntry.keys.retainAll(aliveIds)
            unitEntrySnapshot.keys.retainAll(aliveIds)
        }
    }

    /** 相遇检测 (2026-08-23 性能优化版): 不再每帧全扫可见格 (组队后视野∪大 → 上千格 × getFirstUnit/getCity),
     *  改为检查三个变化源: ①全文明移动过的单位 (位置 diff) ②视野扩展侧 (重算单位的新视野格) ③新城市/被占城市 (快照 diff)。
     *  帧同步: 相遇的权威执行在服务器 (civ.meet op → makeCivilizationsMeet: 城邦见面给金币/双向建外交/通知),
     *  客户端只负责弹窗 UI — 本地调 makeCivilizationsMeet 会本地加金币, 被广播回滚 (见面金币丢失 bug 根因)。
     *  仅未认识的文明才发 op+弹窗; 幂等: shownMeets/diplomacy 过滤, 已认识不再补弹。
     *  组队: civ.viewableTiles 已含队友视野 → 队友见到的新文明我也自动遇见。 */
    private fun checkMeetCivs(worldScreen: WorldScreen, gameInfo: GameInfo, extraTiles: MutableSet<Tile>?) {
        val civ = worldScreen.viewingCiv
        if (civ.isSpectator()) return
        val candidates = extraTiles ?: HashSet()
        // ① 全文明单位位置 diff (含敌方/城邦 — 敌方单位移动进我方视野是相遇主场景)
        val aliveIds = HashSet<Int>()
        for (c in gameInfo.civilizations) {
            if (c.isSpectator() || c.isBarbarian) continue
            for (u in c.units.getCivUnits()) {
                if (u.isDestroyed || !u.hasTile()) continue
                val id = u.id
                aliveIds.add(id)
                val p = u.getTile().position.x.toString() + "," + u.getTile().position.y
                if (meetUnitPos[id] != p) {
                    meetUnitPos[id] = p
                    try { candidates.add(u.getTile()) } catch (e: Exception) {}
                }
            }
        }
        if (meetUnitPos.size > aliveIds.size * 2 + 64) meetUnitPos.keys.retainAll(aliveIds)
        // ② 城市快照 diff (新建/被占/移除 → 中心格加入候选; 城市数少, 每帧全遍历便宜)
        for (c in gameInfo.civilizations) {
            if (c.isSpectator() || c.isBarbarian) continue
            for (city in c.cities) {
                val center = city.getCenterTileOrNull() ?: continue
                val key = center.position.x.toString() + "," + center.position.y + "," + c.civID
                if (meetCitySnapshot[city.id] != key) {
                    meetCitySnapshot[city.id] = key
                    try { candidates.add(center) } catch (e: Exception) {}
                }
            }
        }
        if (meetCitySnapshot.size > 128) {
            val aliveCity = HashSet<String>()
            for (c in gameInfo.civilizations) for (city in c.cities) aliveCity.add(city.id)
            meetCitySnapshot.keys.retainAll(aliveCity)
        }
        if (candidates.isEmpty()) return
        // 相遇判定: 只检查 候选格 ∩ 我方视野 (含队友视野)
        for (tile in candidates) {
            if (tile !in civ.viewableTiles) continue
            val tileUnit = tile.getFirstUnit()
            if (tileUnit != null && tileUnit.civ != civ && !tileUnit.civ.isBarbarian
                && !tileUnit.civ.isSpectator()
                && tileUnit.civ.civID !in shownMeets) {
                if (!civ.diplomacy.containsKey(tileUnit.civ.civID)) {
                    sendOp("civ.meet", mapOf("civ" to tileUnit.civ.civID))
                    // 2026-08-31: 相遇弹窗帧同步 ban 掉 (时有时无 bug, 用户说不需要) — 只保留 meet op 建外交
                }
                shownMeets.add(tileUnit.civ.civID)
            }
            val tileCity = tile.getCity()
            if (tileCity != null && tileCity.civ != civ && !tileCity.civ.isBarbarian
                && !tileCity.civ.isSpectator()
                && tileCity.civ.civID !in shownMeets) {
                if (!civ.diplomacy.containsKey(tileCity.civ.civID)) {
                    sendOp("civ.meet", mapOf("civ" to tileCity.civ.civID))
                    // 2026-08-31: 相遇弹窗帧同步 ban 掉 (同单位分支)
                }
                shownMeets.add(tileCity.civ.civID)
            }
        }
    }

    /** 视野刷新: 我方全部单位调 updateVisibleTiles() — 游戏原生逻辑,
     *  按单位真实视野范围计算 viewableTiles 并永久探索新格子 (与单机完全一致)。
     *  敌方单位不做任何探索 — 只有进入我方视野才可见。
     *  主动相遇检测: 我方视野内出现未认识的文明 → 双向相遇 (双方客户端各自触发, 弹窗对称 —
     *  不依赖"自己视野变化"才检查, 对方走进我方已见区域也能触发)。
     *  组队 (2026-08-23): 队友单位/城市视野并入自己的 viewableTiles + 永久探索, 实现视野共享。 */
    private fun refreshMyCivVisibility(worldScreen: WorldScreen, gameInfo: GameInfo, doMeetCheck: Boolean = true) {
        val civ = worldScreen.viewingCiv
        if (civ.isSpectator()) return
        try {
            // 城市视野: 原版 viewableTiles = 领土+邻居(ourTilesAndNeighboringTiles) ∪ 单位视野;
            // 帧同步客户端从不跑 updateOurTiles → 占领/新建城市后城市中心视野缺失 → 迷雾不揭/城内单位看不见
            // 先重建 ourTilesAndNeighboringTiles (城市拥有的格+邻居, 原版语义), 再刷单位视野 (全量重算 viewableTiles)
            try { civ.cache.updateOurTiles() } catch (e0: Exception) {}
            val checkMeetTiles = HashSet<Tile>()
            refreshCivUnitsVisibility(civ, checkMeetTiles = checkMeetTiles)
            // ---- 组队: 队友视野共享 ----
            val teammates = getMyTeammates(gameInfo)
            if (teammates.isNotEmpty()) {
                for (tciv in teammates) {
                    try { tciv.cache.updateOurTiles() } catch (e1: Exception) {}
                    refreshCivUnitsVisibility(tciv, civ, checkMeetTiles)
                }
                // 队友正在看的格 → 并入我的实时视野 (单位视野 + 城市视野) + 永久探索 (单循环)
                val merged = LinkedHashSet(civ.viewableTiles)
                for (tciv in teammates) {
                    merged.addAll(tciv.viewableTiles)
                    try { merged.addAll(tciv.cache.ourTilesAndNeighboringTiles) } catch (e3: Exception) {}
                }
                civ.viewableTiles = merged
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
            // ---- 同盟 Lv3 (2026-08-26 设计稿 v1.0): 共享实时视野 (不含探索历史, 同盟是动态关系) ----
            val lv3Allies = getLv3Allies(gameInfo, civ)
            if (lv3Allies.isNotEmpty()) {
                for (aciv in lv3Allies) {
                    try { aciv.cache.updateOurTiles() } catch (e2: Exception) {}
                    refreshCivUnitsVisibility(aciv, null, checkMeetTiles)
                }
                val mergedAllies = LinkedHashSet(civ.viewableTiles)
                for (aciv in lv3Allies) {
                    mergedAllies.addAll(aciv.viewableTiles)
                    try { mergedAllies.addAll(aciv.cache.ourTilesAndNeighboringTiles) } catch (e4: Exception) {}
                }
                civ.viewableTiles = mergedAllies
            }
            // 主动相遇检测 (增量版): 只查 移动单位/新城市/视野扩展 三个变化源, 不再全扫可见格
            if (doMeetCheck) checkMeetCivs(worldScreen, gameInfo, checkMeetTiles)
            worldScreen.shouldUpdate = true
        } catch (e: Exception) {
            // 视野刷新失败不影响游戏
        }
    }

    /** 同盟 Lv3 (2026-08-26): 等级 >= 3 的未灭亡盟友 (共享实时视野) */
    private fun getLv3Allies(gameInfo: GameInfo, civ: Civilization): List<Civilization> {
        return gameInfo.alliances
            .filter { it.level >= 3 && it.contains(civ.civID) }
            .mapNotNull { al -> gameInfo.getCivilization(al.otherCiv(civ.civID) ?: "") }
            .filter { !it.isDefeated() }
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

    private fun applyState(worldScreen: WorldScreen, units: List<JsonElement>, cities: List<JsonElement> = emptyList(), civs: List<JsonElement> = emptyList(), encampments: List<JsonElement> = emptyList(), improvements: List<JsonElement> = emptyList(), improvementsDone: List<JsonElement> = emptyList(), roads: List<JsonElement> = emptyList(), religions: List<JsonElement> = emptyList(), terrainChanges: List<JsonElement> = emptyList(), isFull: Boolean = true, removedUnits: List<JsonElement> = emptyList(), removedCities: List<JsonElement> = emptyList(), removedCivs: List<JsonElement> = emptyList(), removedEncampments: List<JsonElement> = emptyList(), removedImprovements: List<JsonElement> = emptyList(), removedImprovementsDone: List<JsonElement> = emptyList(), removedRoads: List<JsonElement> = emptyList(), removedReligions: List<JsonElement> = emptyList()) {
        val gameInfo = worldScreen.gameInfo ?: return
        // 2026-08-31 分层重绘: 全量帧 → 全图静态层需重建 (回合切换/重连/视野全量变化)
        if (isFull) {
            try { worldScreen.mapHolder.markAllStaticDirty() } catch (ignored: Exception) {}
        }
        // 地形变化同步 (OneTimeChangeTerrain "Turn this tile into"): 服务器权威改地形, 本地应用 + 刷新视野/单位通行
        syncTerrainChanges(gameInfo, terrainChanges, worldScreen)
        // 回合数显示同步 (顶栏)
        if (gameInfo.turns != lastTurn) {
            gameInfo.turns = lastTurn
        }
        cityStateChanged = false
        syncCities(worldScreen, cities, isFull, removedCities)
        var civChanged = false
        // 2026-08-29 civs 增量: 服务器 civs 段只输出变化的文明 + removedCivs 列表 (转观战等不再输出)
        // 2026-08-29 修复 (审查发现): 消费 removedCivs — 文明从输出消失 = 已转观战/被移除,
        // 清空其城市+单位 → isDefeated() 为 true → 概览/政治学不显示幽灵文明
        // (文明对象保留, gameInfo.civilizations 索引依赖不能删除)
        // ⚠️ 2026-08-29 审查: cities=emptyList 绕过 destroyCity 的地块所有权/中心格/首都清理 —
        // 当前触发场景 (转观战) 文明城市通常已被服务器清空 (defeated), 此处仅兜底残余, 无害;
        // 若未来 removedCivs 用于"文明被移除但城市还在", 需改逐个 destroyCity 并同步地块所有权
        if (removedCivs.isNotEmpty()) {
            for (rcElem in removedCivs) {
                val rcId = rcElem.jsonPrimitive.contentOrNull ?: continue
                val rcCiv = gameInfo.civilizations.firstOrNull { it.civID == rcId } ?: continue
                try {
                    if (rcCiv.cities.isNotEmpty()) {
                        rcCiv.cities = emptyList()
                        civChanged = true
                        cityStateChanged = true
                    }
                    val rcUnits = rcCiv.units.getCivUnits().toList()
                    if (rcUnits.isNotEmpty()) {
                        for (u in rcUnits) u.destroy()
                        civChanged = true
                    }
                } catch (ignored: Exception) {}
            }
        }
        civChanged = syncCivInfo(gameInfo, civs, worldScreen.viewingCiv.civID) || civChanged
        var unitsChanged = false
        // 2026-08-25: 地图四类状态合并同步 (一次全图遍历替代 4 次 — 后期掉帧优化)
        syncMapLayers(gameInfo, improvements, improvementsDone, roads, encampments, isFull,
            removedImprovements, removedImprovementsDone, removedRoads, removedEncampments)
        syncReligions(gameInfo, religions, worldScreen, isFull, removedReligions)
        val stateIds = HashSet<Int>()
        // 本次广播中服务器驱动了 due 变化的单位 (跳过回合切换) — 重应用“已查看”标记时跳过, 防止取消跳过被回滚
        val serverDueChanged = HashSet<Int>()
        for (unitJson in units) {
            try {
                val obj = unitJson.jsonObject
                val id = obj["id"]?.jsonPrimitive?.intOrNull ?: continue
                stateIds.add(id)
                // 视野增量重算快照: 条目字符串 (位置没变且条目没变 → 视野不变 → 跳过重算)
                unitEntrySnapshot[id] = unitJson.toString()
                val x = obj["x"]?.jsonPrimitive?.intOrNull ?: continue
                val y = obj["y"]?.jsonPrimitive?.intOrNull ?: continue
                val unit = findUnit(gameInfo, id)
                    ?: createUnitFromState(gameInfo, obj)?.also { unitsChanged = true }
                    ?: continue
                if (!unit.hasTile()) continue
                val target = gameInfo.tileMap.get(x, y) ?: continue
                if (unit.getTile() != target) {
                    val oldTile = unit.getTile()
                    worldScreen.mapHolder.animateServerUnitMove(unit, target)
                    // 服务器移动后刷新单位 uniques 缓存 — 否则地块条件类 uniques
                    // (行商 "in [{improved} {resource}] tiles" 进货 / 城市中心售货) 仍按旧地块判断 → 当回合不可用
                    try { unit.updateUniques() } catch (ignored: Exception) {}
                    // 2026-08-25: 驻军条件效果实时刷新 — 单位进出城市中心后 isGarrisoned() 变化,
                    // 城市 stats 缓存不失效则 "in all cities with a garrison" 类加成 (产能/快乐/维护费) 不生效
                    try {
                        val affected = HashSet<com.unciv.logic.city.City>()
                        if (oldTile != null && oldTile.isCityCenter()) oldTile.getCity()?.let { affected.add(it) }
                        if (target.isCityCenter()) target.getCity()?.let { affected.add(it) }
                        if (affected.isNotEmpty()) {
                            // 后台重算 (CityStats.update 整体赋值, 线程安全) + 完成后再刷新界面 — 不卡 GL
                            for (c in affected) scheduleFsStatsRefresh(c.civ)
                            refreshOpenCityScreen()
                        }
                    } catch (ignored: Exception) {}
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
            // 2026-08-30: 单位增删 → 军力缓存失效 (cachedMilitaryMight 不自动失效 → 排行/概览军力不实时)
            try {
                for (civ in gameInfo.civilizations) civ.resetMilitaryMightCache()
            } catch (ignored: Exception) {
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
                            worldScreen.mapHolder.cancelServerUnitAnimation(unit.id)  // 取消在播动画 (2026-08-30)
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
                                worldScreen.mapHolder.cancelServerUnitAnimation(ruId)  // 取消在播动画 (2026-08-30)
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

    /** 2026-08-25: 地图四类状态合并同步 — improvements/improvementsDone/roads/encampments
     *  原本各自遍历全图 40000 格 (每次广播 ×4 遍全图遍历, 后期掉帧主因之一), 合并为一次遍历.
     *  各段对比逻辑与副作用与原函数完全一致 (shouldUpdate/affectedCities/道路商路失效). */
    private fun syncMapLayers(
        gameInfo: GameInfo,
        improvements: List<JsonElement>,
        improvementsDone: List<JsonElement>,
        roads: List<JsonElement>,
        encampments: List<JsonElement>,
        isFull: Boolean = true,
        removedImprovements: List<JsonElement> = emptyList(),
        removedImprovementsDone: List<JsonElement> = emptyList(),
        removedRoads: List<JsonElement> = emptyList(),
        removedEncampments: List<JsonElement> = emptyList(),
    ) {
        try {
            // ---- 构建 4 个服务器数据 (不遍历 tileMap) ----
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
            val serverDone = HashSet<Pair<Int, Int>>()
            val serverDoneNames = HashMap<Pair<Int, Int>, String>()
            val serverDonePillaged = HashMap<Pair<Int, Int>, Boolean>()
            for (imp in improvementsDone) {
                val a = imp.jsonArray ?: continue
                if (a.size < 3) continue
                val x = a[0].jsonPrimitive.intOrNull ?: continue
                val y = a[1].jsonPrimitive.intOrNull ?: continue
                val name = a[2].jsonPrimitive.contentOrNull ?: continue
                serverDone.add(x to y)
                serverDoneNames[x to y] = name
                serverDonePillaged[x to y] = a[3]?.jsonPrimitive?.contentOrNull == "true"
            }
            val serverRoads = HashMap<Pair<Int, Int>, Pair<String, Boolean>>()
            for (r in roads) {
                val a = r.jsonArray ?: continue
                if (a.size < 4) continue
                val x = a[0].jsonPrimitive.intOrNull ?: continue
                val y = a[1].jsonPrimitive.intOrNull ?: continue
                serverRoads[x to y] = (a[2].jsonPrimitive.contentOrNull ?: "") to (a[3].jsonPrimitive.contentOrNull == "true")
            }
            val serverCamps = encampments.mapNotNull { e ->
                val arr = e.jsonArray
                val x = arr.getOrNull(0)?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val y = arr.getOrNull(1)?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                com.unciv.logic.map.HexCoord(x, y)
            }.toSet()

            // ---- 2026-08-30 增量帧: 只处理服务器发的变化 + removed 删除 (不遍历全图对齐) ----
            if (!isFull) {
                var changed = false
                var roadsChanged = false
                val affectedCities = HashSet<com.unciv.logic.city.City>()
                fun cityAffected(tile: com.unciv.logic.map.tile.Tile) {
                    if (tile.owningCity != null) affectedCities.add(tile.owningCity!!)
                    for (adj in tile.neighbors) if (adj.owningCity != null) affectedCities.add(adj.owningCity!!)
                }
                for ((key, pair) in serverImps) {
                    val tile = gameInfo.tileMap.get(key.first, key.second) ?: continue
                    tile.improvementQueue.clear()
                    tile.improvementQueue.add(com.unciv.logic.map.tile.Tile.ImprovementQueueEntry(pair.first, pair.second))
                    changed = true
                    cityAffected(tile)
                }
                for (rem in removedImprovements) {
                    val a = rem.jsonArray ?: continue
                    val x = a.getOrNull(0)?.jsonPrimitive?.intOrNull ?: continue
                    val y = a.getOrNull(1)?.jsonPrimitive?.intOrNull ?: continue
                    val tile = gameInfo.tileMap.get(x, y) ?: continue
                    if (tile.improvementQueue.isNotEmpty() || tile.improvementInProgress != null) {
                        tile.improvementQueue.clear()
                        changed = true
                        cityAffected(tile)
                    }
                }
                for ((key, name) in serverDoneNames) {
                    val tile = gameInfo.tileMap.get(key.first, key.second) ?: continue
                    tile.improvement = name
                    tile.improvementIsPillaged = serverDonePillaged[key] ?: false
                    changed = true
                    cityAffected(tile)
                }
                for (rem in removedImprovementsDone) {
                    val a = rem.jsonArray ?: continue
                    val x = a.getOrNull(0)?.jsonPrimitive?.intOrNull ?: continue
                    val y = a.getOrNull(1)?.jsonPrimitive?.intOrNull ?: continue
                    val tile = gameInfo.tileMap.get(x, y) ?: continue
                    if (tile.improvement != null && tile.improvement?.isNotEmpty() == true) {
                        tile.improvement = null
                        tile.improvementIsPillaged = false
                        changed = true
                        cityAffected(tile)
                    }
                }
                for ((key, pair) in serverRoads) {
                    val tile = gameInfo.tileMap.get(key.first, key.second) ?: continue
                    try {
                        tile.roadStatus = com.unciv.logic.map.tile.RoadStatus.valueOf(pair.first)
                    } catch (ignored: Exception) {
                        continue
                    }
                    tile.roadIsPillaged = pair.second
                    roadsChanged = true
                }
                for (rem in removedRoads) {
                    val a = rem.jsonArray ?: continue
                    val x = a.getOrNull(0)?.jsonPrimitive?.intOrNull ?: continue
                    val y = a.getOrNull(1)?.jsonPrimitive?.intOrNull ?: continue
                    val tile = gameInfo.tileMap.get(x, y) ?: continue
                    if (tile.roadStatus != com.unciv.logic.map.tile.RoadStatus.None || tile.roadIsPillaged) {
                        tile.roadStatus = com.unciv.logic.map.tile.RoadStatus.None
                        tile.roadIsPillaged = false
                        roadsChanged = true
                    }
                }
                for (pos in serverCamps) {
                    val x = pos.x ?: continue
                    val y = pos.y ?: continue
                    val tile = gameInfo.tileMap.get(x, y) ?: continue
                    if (!tile.isBarbarianEncampment()) {
                        try {
                            tile.improvement = com.unciv.Constants.barbarianEncampment
                            changed = true
                            cityAffected(tile)
                        } catch (e: Exception) {
                        }
                    }
                }
                for (rem in removedEncampments) {
                    val a = rem.jsonArray ?: continue
                    val x = a.getOrNull(0)?.jsonPrimitive?.intOrNull ?: continue
                    val y = a.getOrNull(1)?.jsonPrimitive?.intOrNull ?: continue
                    val tile = gameInfo.tileMap.get(x, y) ?: continue
                    if (tile.isBarbarianEncampment()) {
                        try {
                            tile.removeImprovement()
                            changed = true
                            cityAffected(tile)
                        } catch (e: Exception) {
                        }
                    }
                }
                // 2026-08-31 分层重绘: 地图段变化的格子 → 静态层需重建
                try {
                    val mapHolder = worldScreenRef?.get()?.mapHolder
                    if (mapHolder != null) {
                        val changedTiles = HashSet<com.unciv.logic.map.tile.Tile>()
                        for (key in serverImps.keys) changedTiles.add(gameInfo.tileMap[key.first, key.second])
                        for (key in serverDoneNames.keys) changedTiles.add(gameInfo.tileMap[key.first, key.second])
                        for (key in serverRoads.keys) changedTiles.add(gameInfo.tileMap[key.first, key.second])
                        for (pos in serverCamps) changedTiles.add(gameInfo.tileMap.get(pos.x ?: continue, pos.y ?: continue))
                        for (rem in removedImprovements + removedImprovementsDone + removedRoads + removedEncampments) {
                            val a = rem.jsonArray ?: continue
                            val x = a.getOrNull(0)?.jsonPrimitive?.intOrNull ?: continue
                            val y = a.getOrNull(1)?.jsonPrimitive?.intOrNull ?: continue
                            gameInfo.tileMap[x, y]?.let { changedTiles.add(it) }
                        }
                        mapHolder.markTilesDirty(changedTiles)
                    }
                } catch (ignored: Exception) {
                }
                if (changed) {
                    val affectedCivs = HashSet<com.unciv.logic.civilization.Civilization>()
                    for (city in affectedCities) {
                        try {
                            city.cityStats.update()
                            affectedCivs.add(city.civ)
                        } catch (e: Exception) {
                        }
                    }
                    for (civ in affectedCivs) {
                        try { civ.updateStatsForNextTurn() } catch (ignored: Exception) {}
                    }
                    worldScreenRef?.get()?.let { it.shouldUpdate = true }
                    refreshOpenCityScreen()
                }
                if (roadsChanged) {
                    gameInfo.invalidateTradeRoutes()
                    worldScreenRef?.get()?.let { it.shouldUpdate = true }
                }
                return
            }
            // ---- 单次全图遍历, 四类对比 ----
            var changed = false
            var roadsChanged = false
            val affectedCities = HashSet<com.unciv.logic.city.City>()
            for (tile in gameInfo.tileMap.values) {
                val key = (tile.position.x ?: continue) to (tile.position.y ?: continue)
                // improvements (建造中改良)
                val sImp = serverImps[key]
                val localName = tile.improvementInProgress
                if (sImp == null) {
                    if (localName != null) {
                        tile.improvementQueue.clear()
                        changed = true
                    }
                } else if (localName != sImp.first || tile.turnsToImprovement != sImp.second) {
                    tile.improvementQueue.clear()
                    tile.improvementQueue.add(com.unciv.logic.map.tile.Tile.ImprovementQueueEntry(sImp.first, sImp.second))
                    changed = true
                }
                // improvementsDone (已建成改良 + 劫掠)
                var tileChanged = false
                val localDone = tile.improvement
                if (key in serverDone) {
                    val want = serverDoneNames[key]
                    if (localDone != want) {
                        tile.improvement = want
                        tileChanged = true
                    }
                    val wantPillaged = serverDonePillaged[key] ?: false
                    if (tile.improvementIsPillaged != wantPillaged) {
                        tile.improvementIsPillaged = wantPillaged
                        tileChanged = true
                    }
                } else if (localDone != null && localDone.isNotEmpty()) {
                    tile.improvement = null
                    tile.improvementIsPillaged = false
                    tileChanged = true
                }
                if (tileChanged) {
                    changed = true
                    if (tile.owningCity != null) affectedCities.add(tile.owningCity!!)
                    for (adj in tile.neighbors) {
                        if (adj.owningCity != null) affectedCities.add(adj.owningCity!!)
                    }
                }
                // roads (道路/铁路 + 劫掠)
                val sRoad = serverRoads[key]
                val localStatus = tile.roadStatus.name
                val localPillaged = tile.roadIsPillaged
                if (sRoad == null) {
                    if (localStatus != "None" || localPillaged) {
                        tile.roadStatus = com.unciv.logic.map.tile.RoadStatus.None
                        tile.roadIsPillaged = false
                        roadsChanged = true
                    }
                } else {
                    val wantStatus = sRoad.first
                    val wantPillaged = sRoad.second
                    if (localStatus != wantStatus || localPillaged != wantPillaged) {
                        try {
                            tile.roadStatus = com.unciv.logic.map.tile.RoadStatus.valueOf(wantStatus)
                        } catch (ignored: Exception) {
                            continue
                        }
                        tile.roadIsPillaged = wantPillaged
                        roadsChanged = true
                    }
                }
                // encampments (蛮族营地移除 — 本地残留对齐)
                if (tile.isBarbarianEncampment() && tile.position !in serverCamps) {
                    try {
                        tile.removeImprovement()
                    } catch (e: Exception) {
                    }
                }
            }
            // encampments 新增 (服务器有本地没有 → 新刷营地; server 列表小)
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
            // ---- 副作用 (与原 4 函数一致) ----
            if (changed) {
                val affectedCivs = HashSet<com.unciv.logic.civilization.Civilization>()
                for (city in affectedCities) {
                    try {
                        city.cityStats.update()
                        affectedCivs.add(city.civ)
                    } catch (e: Exception) {
                    }
                }
                // 2026-08-30: 改良/道路变 → 文明 statsForNextTurn 缓存失效 (排行读它 — 之前只更新城市级, 排行不更新)
                for (civ in affectedCivs) {
                    try { civ.updateStatsForNextTurn() } catch (ignored: Exception) {}
                }
                worldScreenRef?.get()?.let { it.shouldUpdate = true }
                refreshOpenCityScreen()
            }
            if (roadsChanged) {
                gameInfo.invalidateTradeRoutes()
                worldScreenRef?.get()?.let { it.shouldUpdate = true }
            }
        } catch (e: Exception) {
        }
    }

    /** 道路状态同步 (含劫掠; 2026-08-21): 服务器权威 [x,y,roadStatus,pillaged] —
     *  纯道路地块劫掠后客户端看不到 → “道路无法劫掠”; 只有 roadStatus != None 的格子输出 */

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
            val changedTiles = HashSet<com.unciv.logic.map.tile.Tile>()
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
                    changedTiles.add(tile)
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
                // 2026-08-31 分层重绘: 只标记变化的格子 (静态层重建; 不再全图立即刷)
                try {
                    worldScreen.mapHolder.markTilesDirty(changedTiles)
                } catch (ignored: Exception) {
                }
            }
        } catch (e: Exception) {
        }
    }

    /** 宗教全量同步: 服务器 religions 列表覆盖本地 gameInfo.religions
     *  (万神殿/创立宗教后宗教页立即解锁并显示内容, 不等回合末重载; 服务器权威) */
    private fun syncReligions(gameInfo: GameInfo, religions: List<JsonElement>, worldScreen: WorldScreen, isFull: Boolean = true, removedReligions: List<JsonElement> = emptyList()) {
        try {
            if (religions.isEmpty() && removedReligions.isEmpty()) return  // 无变化 (开局双方都是空, 广播也空)
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
            if (isFull && (gameInfo.religions.size != server.size || gameInfo.religions.keys.any { it !in server }) || religionContentChanged) {
                gameInfo.religions.clear()
                gameInfo.religions.putAll(server)
                worldScreen.shouldUpdate = true
                // 2026-08-30 增量帧: removedReligions 从本地删除 (宗教一般只增不减, 兜底)
                if (!isFull && removedReligions.isNotEmpty()) {
                    for (re in removedReligions) {
                        val rname = re.jsonPrimitive.contentOrNull ?: continue
                        if (gameInfo.religions.remove(rname) != null) worldScreen.shouldUpdate = true
                    }
                }
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

    /** 蛮族营地同步: 服务器全量列表覆盖本地 (攻下营地后双方立即看到营地消失) */

    /** 其他文明信息同步 (概览界面): 已采用政策 + 时代 — 对方回合中选政策/进时代立即可见 */
    /** 外交关系变化 (新见面/战争/友谊/谴责) → 实时刷新打开的外交界面: 左侧关系圆圈变色/新文明出现 + 右侧菜单更新.
     *  贸易页打开时不重建右侧 (防止清空编辑中的报价), 只刷左侧圆圈. (2026-08-22 用户反馈"友谊同意后圆圈不实时变色") */
    private fun refreshOpenDiplomacyScreen() {
        // 2026-08-27: 必须在 GL 线程执行 — applyState 在 ws 消息线程调用此函数,
        // 直接操作 UI 会线程违规 SIGABRT (用户接受同盟后崩溃实锤)
        Concurrency.runOnGLThread {
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
                // UncivGC 2026-08-24: 外交状态同步 (承诺保护/宣战等 diplomaticStatus) — 服务器权威广播
                obj["diplomacy"]?.jsonArray?.let { dipArr ->
                    for (dElem in dipArr) {
                        val dObj = dElem.jsonObject
                        val dCivId = dObj["civ"]?.jsonPrimitive?.contentOrNull ?: continue
                        val dStatus = dObj["status"]?.jsonPrimitive?.contentOrNull ?: continue
                        val dCiv = gameInfo.civilizations.firstOrNull { it.civID == dCivId } ?: continue
                        val dm = civ.getDiplomacyManager(dCiv) ?: continue
                        val newStatus = com.unciv.logic.civilization.diplomacy.DiplomaticStatus.entries
                            .firstOrNull { it.name == dStatus } ?: continue
                        if (dm.diplomaticStatus != newStatus) {
                            dm.diplomaticStatus = newStatus
                            changed = true
                        }
                    }
                }
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
                    // 伟人点数累计同步 (2026-08-29 修复 "伟人点数不增长"): 纯拦截下客户端本地不执行
                    // endTurn → 本地 greatPersonPointsCounter 恒 0 → 城市界面 GPP 进度条不涨;
                    // 服务器权威广播累计值, 写回本地 (服务器结算后每次 state 携带)
                    obj["greatPersonPoints"]?.jsonArray?.let { gppArr ->
                        try {
                            val server = HashMap<String, Int>()
                            for (gpp in gppArr) {
                                val a = gpp.jsonArray ?: continue
                                if (a.size < 2) continue
                                val name = a[0].jsonPrimitive.contentOrNull ?: continue
                                val value = a[1].jsonPrimitive.intOrNull ?: continue
                                server[name] = value
                            }
                            val local = civ.greatPeople.greatPersonPointsCounter
                            val keys = (local.keys + server.keys).toSet()
                            if (keys.any { local[it] != server[it] }) {
                                for (k in keys) {
                                    val v = server[k]
                                    if (v == null) local.remove(k) else local[k] = v
                                }
                                changed = true
                            }
                        } catch (_: Exception) {}
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
                    log("policies 同步: " + civ.civName + " " + adopted.size + " 条 (" + adopted.take(3) + ") 开始 rebuild")
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
                    log("policies 同步: " + civ.civName + " rebuild+城市重算+statsForNextTurn 完成")
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
                // 2026-08-25: 临时加成同步 (事件/政策等 "for [N] turns" 加成; temporaryUniques 不进存档,
                // 之前不广播 → 加成效果不显示) — 服务器列表变化才重建 + 重算产出
                obj["tempUniques"]?.jsonArray?.let { tuArr ->
                    try {
                        val server = HashMap<String, Int>()
                        for (e in tuArr) {
                            val arr = e.jsonArray ?: continue
                            if (arr.size < 2) continue
                            val text = arr[0].jsonPrimitive.contentOrNull ?: continue
                            server[text] = arr[1].jsonPrimitive.intOrNull ?: 0
                        }
                        val changedTu = civ.temporaryUniques.size != server.size ||
                            civ.temporaryUniques.any { server[it.unique] != it.turnsLeft }
                        if (changedTu) {
                            civ.temporaryUniques.clear()
                            for ((text, turns) in server) {
                                civ.temporaryUniques.add(
                                    com.unciv.models.ruleset.unique.TemporaryUnique().apply {
                                        this.unique = text
                                        this.turnsLeft = turns
                                    })
                            }
                            // 2026-08-25: 重算后台化 (临时加成/驻军变化触发) — 所有变化的文明都重算
                            // (排行面板读其他文明 statsForNextTurn), 但全部在后台线程 → GL 不卡
                            scheduleFsStatsRefresh(civ)
                            changed = true
                        }
                    } catch (e: Exception) {
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
                            // UncivGC 商路: 建筑变化 (港口完工/卖出) → 连接缓存失效 (海商立即生效)
                            try { gameInfo.invalidateTradeRoutes() } catch (ignored: Exception) {}
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
                    // 2026-08-31 分层重绘: 建城低频 → 全图静态层重建 (领土轮廓/边界跨格, 单格标记会漏)
                    if (!isFull) {
                        try { worldScreen.mapHolder.markAllStaticDirty() } catch (ignored: Exception) {}
                    }
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
                // 2026-08-31 分层重绘: 城市被移除 → 领土边界消失 → 全图静态层重建
                try { worldScreen.mapHolder.markAllStaticDirty() } catch (ignored: Exception) {}
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
        // UncivGC 商路 (2026-08-24): 城市结构变化 (新城/归属迁移/移除) → 连接缓存失效
        // 用 (civName:id) 指纹, 人口/产出变化不触发 (避免每帧重算 BFS); 服务器当回合已按新连接入账
        try {
            val newKeys = gameInfo.getCities().map { it.civ.civName + ":" + it.id }.toSet()
            if (lastSyncedCityKeys != newKeys) {
                lastSyncedCityKeys = newKeys
                gameInfo.invalidateTradeRoutes()
            }
        } catch (e: Exception) {
        }
    }

    /** 城市地块归属同步: 服务器 ownedTiles 全量列表 → 本地设置/清空 (买地后立即显示, 不等回合末重载) */
    private fun syncOwnedTiles(gameInfo: GameInfo, city: com.unciv.logic.city.City, obj: JsonObject, worldScreen: WorldScreen) {
        try {
            // 2026-08-30 裁剪版: ownedTiles 仍广播 (公共字段), 但防御: 缺失时跳过
            val ownedArr = obj["ownedTiles"]?.jsonArray ?: return
            val owned = ownedArr.mapNotNull { arr ->
                    val a = arr.jsonArray ?: return@mapNotNull null
                    if (a.size >= 2) (a[0].jsonPrimitive.intOrNull to a[1].jsonPrimitive.intOrNull) else null
                }.toSet()
            if (owned.isEmpty()) return
            var changed = false
            // 2026-08-31 分层重绘: 归属变化的格子 + 邻居标脏 (边界线跨格判断, 买地/扩张后领土立即绘制)
            val borderDirty = HashSet<com.unciv.logic.map.tile.Tile>()
            for ((x, y) in owned) {
                val px = x ?: continue
                val py = y ?: continue
                val t = gameInfo.tileMap.get(px, py) ?: continue
                if (t.owningCity != city) {
                    t.setOwningCity(city)
                    city.tiles.add(t.position)  // 更新城市地块集 (城市界面立即显示)
                    borderDirty.add(t)
                    borderDirty.addAll(t.neighbors)
                    changed = true
                }
            }
            // 本地属于该城但服务器没有的 → 清空 (全量对齐, 防止本地残留)
            for (t in city.getTiles()) {
                if ((t.position.x!! to t.position.y!!) !in owned && t.owningCity == city) {
                    t.setOwningCity(null)
                    city.tiles.remove(t.position)
                    borderDirty.add(t)
                    borderDirty.addAll(t.neighbors)
                    changed = true
                }
            }
            if (changed) {
                cityStateChanged = true
                // 2026-08-31 分层重绘: 领土边界变化 → 对应格子静态层重建
                try { worldScreen.mapHolder.markTilesDirty(borderDirty) } catch (ignored: Exception) {}
                // 2026-08-30: 地块归属变 → 城市 stats 完整重算 (含文明级; updateStatsForNextTurn 快乐条件坑)
                try { city.cityStats.update() } catch (ignored: Exception) {}
                worldScreen.shouldUpdate = true
            }
        } catch (e: Exception) {
        }
    }

    /** 工作格同步: 服务器 workedTiles 全量列表 → 本地增删 (公民分配立即生效, 界面刷新) */
    private fun syncWorkedTiles(gameInfo: GameInfo, city: com.unciv.logic.city.City, obj: JsonObject, worldScreen: WorldScreen) {
        try {
            // 2026-08-30 裁剪版: 服务器不再广播 workedTiles (字段缺失) → 本地维护, 跳过同步 —
            // 否则空列表会把本地所有工作格清掉 (回合结算重载后"人口全下" bug)
            val workedArr = obj["workedTiles"]?.jsonArray ?: return
            val worked = workedArr.mapNotNull { arr ->
                    val a = arr.jsonArray ?: return@mapNotNull null
                    if (a.size >= 2) (a[0].jsonPrimitive.intOrNull to a[1].jsonPrimitive.intOrNull) else null
                }.toSet()
            val beforeWorked = city.workedTiles.size
            val beforeCityProd = city.cityStats.currentCityStats.production
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
                // 2026-08-30: 工作格变 → 城市 stats 完整重算 (cityStats.update 默认 updateCivStats=true
                // → 内部触发文明 statsForNextTurn; 不能用 updateStatsForNextTurn() — 它只在快乐变化时重算城市,
                // 快乐不变 → 排行读旧城市产出 (15:57 日志铁证 worked 1->0 但 cityProd 5.0->5.0)
                try {
                    city.cityStats.update()
                    log("DBG workedTiles city=${city.name} civ=${city.civ.civName} worked ${beforeWorked}->${city.workedTiles.size} " +
                            "cityProd ${beforeCityProd}->${city.cityStats.currentCityStats.production} " +
                            "civProd=${city.civ.stats.statsForNextTurn.production}")
                } catch (e: Exception) {
                    log("DBG workedTiles updateStats FAIL: ${e.message}")
                }
                worldScreen.shouldUpdate = true
            }
        } catch (e: Exception) {
        }
    }

    /** 锁定地块同步: 服务器 lockedTiles 全量列表 → 本地对齐 (锁定/解锁立即生效, 界面刷新;
     *  纯拦截后本地不执行, 不广播则锁定无显示) */
    private fun syncLockedTiles(gameInfo: GameInfo, city: com.unciv.logic.city.City, obj: JsonObject, worldScreen: WorldScreen) {
        try {
            // 2026-08-30 裁剪版: lockedTiles 不再广播 → 跳过同步 (本地维护)
            val lockedArr = obj["lockedTiles"]?.jsonArray ?: return
            val locked = lockedArr.mapNotNull { arr ->
                    val a = arr.jsonArray ?: return@mapNotNull null
                    if (a.size >= 2) (a[0].jsonPrimitive.intOrNull to a[1].jsonPrimitive.intOrNull) else null
                }.toSet()
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
                // 2026-08-30: 锁定格变 (影响自动分配) → 城市 stats 完整重算 (含文明级)
                try { city.cityStats.update() } catch (ignored: Exception) {}
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

    /** 2026-08-25: 统计重算后台化 (临时加成/驻军变化触发) — 后台线程批量重算 + 去抖 + 完成后 GL 刷新.
     *  CityStats.update 整体赋值 currentCityStats (防并发异常设计) → 后台线程安全;
     *  GL 渲染读 currentCityStats 引用, 新旧整体替换无中间态.
     *  重算**所有变化的文明** (排行面板读其他文明 statsForNextTurn 的 science/culture/production),
     *  但全部在后台 → GL 线程不卡. 去抖: 计算期间的触发合并到下一批. */
    @Volatile private var fsStatsRefreshRunning = false
    private val fsStatsRefreshCivs = java.util.Collections.synchronizedSet(HashSet<com.unciv.logic.civilization.Civilization>())
    private fun scheduleFsStatsRefresh(civ: com.unciv.logic.civilization.Civilization) {
        fsStatsRefreshCivs.add(civ)
        if (fsStatsRefreshRunning) return
        fsStatsRefreshRunning = true
        Concurrency.run("FsStatsRefresh") {
            while (true) {
                val batch: List<com.unciv.logic.civilization.Civilization>
                synchronized(fsStatsRefreshCivs) {
                    if (fsStatsRefreshCivs.isEmpty()) {
                        fsStatsRefreshRunning = false
                        break
                    }
                    batch = ArrayList(fsStatsRefreshCivs)
                    fsStatsRefreshCivs.clear()
                }
                for (civ in batch) {
                    try {
                        // 先城市后文明 (civ.updateStatsForNextTurn 汇总依赖城市新 stats)
                        for (c in civ.cities) c.cityStats.update()
                        civ.updateStatsForNextTurn()
                        dbg("FsStatsRefresh 完成: ${civ.civName} goldPT=${civ.stats.statsForNextTurn.gold} sci=${civ.stats.statsForNextTurn.science}")
                    } catch (ignored: Exception) {
                    }
                }
            }
            launchOnGLThread {
                try {
                    worldScreenRef?.get()?.shouldUpdate = true
                    refreshOpenCityScreen()
                } catch (ignored: Exception) {
                }
            }
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

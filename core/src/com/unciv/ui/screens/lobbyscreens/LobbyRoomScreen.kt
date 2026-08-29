package com.unciv.ui.screens.lobbyscreens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Base64Coder
import com.unciv.UncivGame
import com.unciv.logic.github.Github
import com.unciv.logic.github.Github.folderNameToRepoName
import com.unciv.logic.github.GithubAPI
import com.unciv.logic.github.GithubAPI.downloadAndExtract
import com.unciv.logic.github.Zip
import com.unciv.logic.lobby.LobbyApi
import com.unciv.logic.lobby.LobbyRoom
import com.unciv.logic.lobby.ModMirrorEntry
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.MapSize
import com.unciv.logic.multiplayer.storage.UncivServerFileStorage
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.ActivationTypes
import com.unciv.ui.components.input.ActorAttachments
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.ui.screens.newgamescreen.LobbyPlayerStatus
import com.unciv.ui.screens.lobbyscreens.LobbyScreen
import com.unciv.ui.screens.newgamescreen.NewGameScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * UncivGC 联机大厅: 房间内界面
 * 直接复用原版「开始新游戏」界面 (游戏设置 | 地图设置 | 文明 三块 + 原版选文明弹窗),
 * 叠加大厅逻辑: 成员同步/准备/房主开始/进入游戏。
 * 设置: 统一存服务器 (房主可改/保存, 全员只读同享), 生成时用服务器设置。
 */
class LobbyRoomScreen(val roomId: String, val initialName: String, settings: Map<String, JsonElement> = emptyMap())
    : NewGameScreen(
        lobbyGameSetupInfo(settings),
        showOnlineMultiplayer = false,
        lobbyMode = true,
        lobbyCanEdit = { it.playerId == currentPlayerId() },
    ), RecreateOnResize {

    companion object {
        /** UncivGC 自建存档服务器 (与 gen_lobby.py 一致): 存档上传/下载都走这里; 本地联调用 -Duncivgc.spUrl 覆盖 */
        val SP_SERVER_URL: String
            get() = System.getProperty("uncivgc.spUrl") ?: "http://110.40.151.9:30126"

        /** 当前所在房间 ID (游戏内菜单「退出房间」用) */
        var activeRoomId: String? = null
        /** 已提示过缺失的模组集合指纹 (跨实例共享, 避免重建界面后重复弹窗) */
        var promptedMissingMods = ""
        /** 已提示过的新版模组集合指纹 (跨实例共享, 避免重复弹窗) */
        var promptedOutdatedMods = ""
        /** 当前用户是否房主 (游戏内菜单「跳海」用, 随同步更新) */
        var activeAmOwner = false
        /** 已进入游戏的房间 (跨 recreate 实例防重复进入 — 实例标志会在重建时丢失导致双加载) */
        var enteredGameForRoom: String? = null
        /** 是否正在通过菜单主动退出 (监视器不重复导航) */
        var leavingGame = false
        /** 游戏内房间监视器是否在跑 (防止重复启动) */
        private var gameWatcherRunning = false

        /** 大厅房间固定规则集: 原版 G&K, 无模组 (现阶段) */
        fun lobbyGameSetupInfo(settings: Map<String, JsonElement> = emptyMap()): GameSetupInfo =
            GameSetupInfo().apply {
                gameParameters.baseRuleset = "Civ V - Gods & Kings"
                gameParameters.mods.clear()
                gameParameters.players = ArrayList()
                gameParameters.espionageEnabled = false
                mapParameters.shape = MapShape.rectangular
                mapParameters.worldWrap = true
                applyServerSettings(this, settings)
            }

        fun currentNickname() = UncivGame.Current.settings.lobbyNickname.ifBlank { "Player" }
        fun currentPlayerId() = UncivGame.Current.settings.multiplayer.getUserId()

        /** 从房间设置解析模组列表 */
        fun parseMods(settings: Map<String, JsonElement>): List<String> {
            val gp = settings["gp"] as? JsonObject ?: return emptyList()
            return (gp["mods"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        }

        /** 本机已安装模组 (mods 目录文件夹名 + 已加载规则集) */
        fun installedMods(): Set<String> {
            val fromFolder = try {
                UncivGame.Current.files.getModsFolder().list().map { it.name() }.toSet()
            } catch (e: Exception) {
                emptySet()
            }
            return fromFolder + RulesetCache.keys
        }

        /** 房间设置里缺哪些模组 (含模组型基础规则集, 如 LM2 被选为基础规则集时)
         *  2026-08-27: 归一化匹配 (空格/连字符/大小写容错) — 镜像安装目录可能带连字符
         *  (LM2-ugc), 房间设置是空格名/原样名 → 精确匹配误报缺模组 (用户反馈更新后仍显示缺少模组) */
        fun missingModsOf(settings: Map<String, JsonElement>): List<String> {
            val installedNorm = installedMods().map { normName(it) }.toSet()
            val gp = settings["gp"] as? JsonObject ?: return emptyList()
            val missing = mutableListOf<String>()
            val base = gp["baseRuleset"]?.jsonPrimitive?.contentOrNull
            if (base != null && normName(base) !in installedNorm) missing.add(base)
            (gp["mods"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.filter { normName(it) !in installedNorm }?.let { missing.addAll(it) }
            return missing.distinct()
        }

        /** 名字归一化 (小写+去非字母数字): 镜像清单匹配用 (空格/连字符/大小写差异) */
        fun normName(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

        /** 本地模组版本状态文件 (镜像安装/更新时记录, 用于检测新版) */
        private fun mirrorStateFile() = UncivGame.Current.files.getLocalFile("mirror_mod_state.json")

        fun loadMirrorState(): Map<String, String> = try {
            val f = mirrorStateFile()
            if (f.exists()) {
                (Json.parseToJsonElement(f.readString()) as? JsonObject)
                    ?.mapValues { it.value.jsonPrimitive.contentOrNull ?: "" } ?: emptyMap()
            } else emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }

        private fun saveMirrorState(state: Map<String, String>) = try {
            val json = buildJsonObject { state.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }
            mirrorStateFile().writeString(json.toString(), false)
        } catch (e: Exception) {
        }

        /** 房间需要的全部模组 (mods + 非标准基础规则集, 如 LM2) */
        fun requiredMods(settings: Map<String, JsonElement>): List<String> {
            val gp = settings["gp"] as? JsonObject ?: return emptyList()
            val standardBases = setOf("Civ V - Vanilla", "Civ V - Gods & Kings")
            val mods: List<String> = parseMods(settings)
            val base: String? = gp["baseRuleset"]?.jsonPrimitive?.contentOrNull
            val extra: List<String> = base?.takeIf { it !in standardBases }?.let { listOf(it) } ?: emptyList()
            return (mods + extra).distinct()
        }

        /** 已装但镜像里已有新版的模组 (按本地镜像版本状态对比; 没有状态记录的旧装模组不提示) */
        fun outdatedMirrorMods(settings: Map<String, JsonElement>, manifest: List<ModMirrorEntry>): List<String> {
            val needed = requiredMods(settings).map { normName(it) }.toSet()
            val state = loadMirrorState()
            return manifest.filter { e ->
                normName(e.name) in needed && e.version.isNotEmpty() &&
                    state[e.name] != null && state[e.name] != e.version
            }.map { it.name }
        }

        fun md5Of(file: com.badlogic.gdx.files.FileHandle): String = try {
            val md = java.security.MessageDigest.getInstance("MD5")
            file.read().use { ins ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }

        /** 从镜像下载并安装模组 (覆盖已有目录 + md5 校验 + 记录版本状态). 返回失败列表.
         *  注意: suspend — 需在协程中调用; onProgress 回调由调用方负责切回 GL 线程 */
        suspend fun installFromMirror(entries: List<ModMirrorEntry>, mods: List<String>, screen: BaseScreen?,
                                      onProgress: (String, Int) -> Unit): List<String> {
            val errors = mutableListOf<String>()
            val byNorm = entries.associateBy { normName(it.name) }
            val state = loadMirrorState().toMutableMap()
            val modsFolder = UncivGame.Current.files.getModsFolder()
            for (modName in mods) {
                val entry = byNorm[normName(modName)]
                if (entry == null) {
                    errors.add("[$modName] not on the mirror".tr())
                    continue
                }
                try {
                    // 用镜像里的规范名请求 (服务器文件按镜像名命名)
                    val zipPath = LobbyApi.downloadModFromMirror(entry.name) { p ->
                        onProgress(modName, p)
                    } ?: run {
                        errors.add("[$modName] download failed".tr())
                        continue
                    }
                    // md5 校验 (防传输损坏/坏包) — 用 FileHandle 读 (相对路径字符串直接建 java.io.File 在 Android 上会解析到错误位置)
                    if (entry.md5.isNotEmpty()) {
                        val md5 = md5Of(Gdx.files.local(zipPath))
                        if (md5 != entry.md5) {
                            Gdx.files.local(zipPath).delete()
                            errors.add("[$modName] checksum failed, please retry".tr())
                            continue
                        }
                    }
                    // 覆盖安装: 先删同模组的旧目录再解压 (目录名可能因历史版本不同 — 连字符/空格, 按归一化名匹配)
                    val entryNorm = normName(entry.name)
                    for (child in modsFolder.list()) {
                        if (normName(child.name()) == entryNorm) child.deleteDirectory()
                    }
                    val zipHandle = Gdx.files.local(zipPath)
                    Zip.extractFolder(zipHandle, modsFolder)
                    zipHandle.delete()
                    state[entry.name] = entry.version
                } catch (e: Exception) {
                    errors.add("[$modName] ${e.message ?: "Download error".tr()}")
                }
            }
            saveMirrorState(state)
            return errors
        }

        /** 通过 GitHub 下载缺失模组 (搜索 + 带进度条), 完成后回调 */
        fun downloadMissingMods(mods: List<String>, screen: BaseScreen?, onDone: () -> Unit) {
            if (screen == null) return
            val popup = Popup(screen)
            popup.addGoodSizedLabel("Downloading mods...".tr()).row()
            val progressLabel = "0%".toLabel()
            popup.add(progressLabel).row()
            popup.open()
            Concurrency.runOnNonDaemonThreadPool("LobbyDownloadMods") {
                val errors = mutableListOf<String>()
                // 镜像清单 (服务器上有备份的模组优先从服务器下载, 国内快); 名字归一化匹配 (空格/连字符)
                val mirrorEntries = try {
                    LobbyApi.modMirrorManifest()
                } catch (e: Exception) {
                    emptyList()
                }
                val mirrorMods = mirrorEntries.map { normName(it.name) }.toSet()
                try {
                    val cachedRepos = UncivGame.Current.files.loadModCache().mapNotNull { it.repo }
                    val cachedByName = cachedRepos.associateBy { it.name }
                    for (modName in mods) {
                        // 1. 优先从我们的服务器镜像下载 (国内快)
                        if (normName(modName) in mirrorMods) {
                            val errs = installFromMirror(mirrorEntries, listOf(modName), screen) { m, p ->
                                launchOnGLThread { progressLabel.setText("[$m] $p% (server)") }
                            }
                            if (errs.isEmpty()) continue
                            errors.addAll(errs)
                            errors.add("[$modName] mirror download failed, trying GitHub...".tr())
                        }
                        // 2. GitHub 回退 (U 自带机制)
                        val repoName = modName.folderNameToRepoName().lowercase()
                        val repo = cachedByName[repoName]
                            ?: Github.tryGetGithubReposWithTopic(1, 10, repoName)
                                ?.items?.firstOrNull { it.name.lowercase() == repoName }
                        if (repo == null) {
                            errors.add("Mod [$modName] not found (GitHub search returned nothing)".tr())
                            continue
                        }
                        var lastPercent = -1
                        repo.downloadAndExtract { _, percent ->
                            val p = percent ?: 0
                            if (p != lastPercent) {
                                lastPercent = p
                                launchOnGLThread { progressLabel.setText("[$modName] $p% (GitHub)") }
                            }
                        } ?: run { errors.add("[$modName] download failed (404)".tr()); continue }
                    }
                } catch (e: Exception) {
                    errors.add(e.message ?: "Download error".tr())
                }
                launchOnGLThread {
                    popup.close()
                    if (errors.isNotEmpty()) {
                        ToastPopup(errors.joinToString("\n"), screen)
                    } else {
                        RulesetCache.loadRulesets()
                        // 刷新翻译缓存: loadRulesets 不重读 mod 翻译 (官方 Mod 管理器 L711 同款);
                        // 漏了则 LM2 等 mod 的翻译不生效 (显示英文原串)
                        UncivGame.Current.translations.tryReadTranslationForCurrentLanguage()
                        ToastPopup("Mod download complete".tr(), screen)
                        onDone()
                    }
                }
            }
        }

        /** 直接进入大厅游戏 (房间监视器/自动进房/正常开局共用入口); 开局前强制检查模组齐全 */
        fun enterLobbyGame(gameId: String, room: LobbyRoom, screen: BaseScreen?) {
            // 统一设置"已进入该房间"标志 (所有进入路径共用, 防 LobbyAutoJoin/LobbyPoll 双触发)
            enteredGameForRoom = room.id
            val missing = missingModsOf(room.settings)
            if (missing.isNotEmpty()) {
                if (screen != null) {
                    Concurrency.runOnGLThread("LobbyModPrompt") {
                        ConfirmPopup(screen, "Missing mods: [${missing.joinToString(", ")}]\nDownload?".tr(), "Download".tr()) {
                            downloadMissingMods(missing, screen) {
                                enterLobbyGame(gameId, room, screen)
                            }
                        }.open()
                    }
                }
                return
            }
            doEnterLobbyGame(gameId, room, screen)
        }

        /** 确认自己是否为房主 (列表摘要没有成员明细时拉详情 — 否则房主进游戏后跳海/重新开始按钮会消失) */
        suspend fun resolveAmOwner(room: LobbyRoom): Boolean {
            val direct = room.members.firstOrNull { it.playerId == currentPlayerId() }?.isOwner
            if (direct != null) return direct
            return try {
                val detail = LobbyApi.getRoom(room.id)
                detail.members.firstOrNull { it.playerId == currentPlayerId() }?.isOwner == true
            } catch (e: Exception) {
                false
            }
        }

        private fun doEnterLobbyGame(gameId: String, room: LobbyRoom, screen: BaseScreen?) {
            activeRoomId = room.id
            Concurrency.run("LobbyEnterGame") {
                try {
                    // 房主身份: 摘要无成员明细时拉详情 (否则跳海/重新开始按钮消失)
                    activeAmOwner = resolveAmOwner(room)
                    leavingGame = false
                    // 存档在自己服务器上, 客户端必须切到该服务器才能下载
                    // 记住原多人服务器, 退出大厅局时恢复 (官方多人列表不混入大厅游戏)
                    val settings = UncivGame.Current.settings.multiplayer
                    if (settings.getServer() != SP_SERVER_URL) {
                        settings.lobbyPreviousServer = settings.getServer()
                    }
                    settings.setServer(SP_SERVER_URL)
                    // 官方客户端不会自动注册: 先确保存档服务器上有本设备账号 (PUT /auth 幂等, 密码失效可自愈)
                    ensureSaveServerRegistered()
                    UncivGame.Current.onlineMultiplayer.downloadGame(gameId)
                    // 后台盯房间: 跳海新局 (gameId 变化) 自动切图; 独立守护线程, 不受屏幕销毁影响
                    startGameWatcher(room.id, gameId, isMember = true)
                } catch (e: Exception) {
                    if (screen != null) {
                        launchOnGLThread { ToastPopup("Failed to enter game: [${e.message}]".tr(), screen) }
                    }
                }
            }
        }

        /** 恢复进大厅局之前的多人服务器设置 (持久化, App 强杀后启动也能恢复) */
        fun restoreMultiplayerServer() {
            val settings = UncivGame.Current.settings.multiplayer
            val prev = settings.lobbyPreviousServer ?: return
            settings.lobbyPreviousServer = null
            try {
                settings.setServer(prev)
            } catch (e: Exception) {
            }
        }

        /** 在存档服务器上注册/刷新本设备账号, 并保存密码到设置 (下载/上传回合都要用) */
        fun ensureSaveServerRegistered() {
            val settings = UncivGame.Current.settings.multiplayer
            val userId = settings.getUserId()
            val password = settings.getPassword(SP_SERVER_URL)
                ?: UUID.randomUUID().toString().replace("-", "").take(16)
            val storage = UncivServerFileStorage.apply {
                serverUrl = SP_SERVER_URL
                authHeader = mapOf("Authorization" to "Basic " + Base64Coder.encodeString("$userId:$password"))
            }
            if (storage.setPassword(password)) {
                settings.setCurrentServerPassword(password)
            }
        }

        /** 游戏内房间监视器: 长轮询房间, 发现新 gameId (跳海) 自动下载进入新图;
         *  发现房间回到等待 (重新开始, 仅成员) → 回房间界面等房主再点开始
         *  2026-08-26 修复: 观战退出后去其他房间 — 旧 watcher 还在跑, 检测到旧房间成员移除
         *  会把用户从新房间踢回大厅/拉回旧房间 → 循环开头检查 activeRoomId, 已离开该房间即停止 */
        fun startGameWatcher(roomId: String, currentGameId: String, isMember: Boolean) {
            if (gameWatcherRunning) return
            gameWatcherRunning = true
            Concurrency.run("LobbyGameWatcher") {
                var since = -1
                var myGameId = currentGameId
                try {
                    while (true) {
                        // 已进入其他房间/已退出 (activeRoomId 变化) → 本 watcher 失效, 停止
                        if (activeRoomId != roomId) break
                        val room = LobbyApi.waitRoom(roomId, since, currentPlayerId()) ?: continue
                        since = room.version
                        // 房间解散或我被移除 (仅成员) → 回大厅并恢复服务器 (主动退出时由菜单处理, 不重复导航)
                        if (isMember && room.members.none { it.playerId == currentPlayerId() }) {
                            if (!leavingGame) {
                                launchOnGLThread {
                                    restoreMultiplayerServer()
                                    UncivGame.Current.replaceCurrentScreen(LobbyScreen())
                                }
                            }
                            break
                        }
                        if (isMember && room.status == "waiting") {
                            // 可能是跳海的中间态 (马上会变 playing), 等 3 秒确认
                            Thread.sleep(3000)
                            val confirmed = LobbyApi.getRoom(roomId)
                            since = confirmed.version
                            // 跳海生成可能 >3s: 再等一轮确认 (总共 ~11s) — 否则成员在生成完成前
                            // 判定 waiting 回房间界面, 而房间已 playing → 卡在旧房间界面“无法开房”
                            var finalStatus = confirmed.status
                            var finalGameId = confirmed.gameId
                            if (finalStatus == "waiting") {
                                Thread.sleep(8000)
                                try {
                                    val confirmed2 = LobbyApi.getRoom(roomId)
                                    since = confirmed2.version
                                    finalStatus = confirmed2.status
                                    finalGameId = confirmed2.gameId
                                } catch (e: Exception) {
                                }
                            }
                            if (finalStatus == "waiting") {
                                // 重新开始: 回房间界面 (已自动准备, 房主点开始)
                                // 重置进房标记: 旧局已结束, 房间可再次开始 (否则同房间再 start 时
                                // LobbyRoomPoll 的 enteredGameForRoom != room.id 不成立 → 卡在房间界面)
                                enteredGameForRoom = null
                                launchOnGLThread {
                                    UncivGame.Current.replaceCurrentScreen(LobbyRoomScreen(roomId, confirmed.name))
                                }
                                break
                            }
                            // 跳海进行中 (starting/playing): 给成员可见的提示, 然后等新图切图
                            if (finalStatus == "starting") {
                                launchOnGLThread {
                                    UncivGame.Current.worldScreen?.let { ws ->
                                        ToastPopup("Host is restarting the game, generating a new map...".tr(), ws)
                                    }
                                }
                            }
                            // 跳海: 等待期间新局已开 → 立即切图 (不能 continue 等下一次变化, 会永远等不到)
                            if (finalStatus == "playing" && !finalGameId.isNullOrEmpty()
                                && finalGameId != myGameId) {
                                val newGameId = finalGameId!!
                                try {
                                    UncivGame.Current.onlineMultiplayer.downloadGame(newGameId)
                                    myGameId = newGameId  // 下载成功才更新 — 失败则循环重试, 否则永远不再切图
                                } catch (e: Exception) {
                                }
                            }
                            continue
                        }
                        // 跳海: 新局已开始 → 自动切图
                        if (room.status == "playing" && !room.gameId.isNullOrEmpty() && room.gameId != myGameId) {
                            val newGameId = room.gameId!!
                            try {
                                UncivGame.Current.onlineMultiplayer.downloadGame(newGameId)
                                myGameId = newGameId  // 下载成功才更新 (失败则下一轮重试, 否则跳海后永远卡在旧图)
                            } catch (e: Exception) {
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 房间解散 (404) → 回大厅并恢复服务器 (主动退出时由菜单处理)
                    // 网络异常 (断网/切网/超时) → 只重试, 绝不弹回大厅 (掉线≠退出, 游戏继续)
                    if (!leavingGame) {
                        val gone = e.message?.contains("404") == true || e.message?.contains("Room not found") == true  // 匹配服务器 404 协议消息 (服务器已英文化)
                        if (gone) {
                            val reason = e.message?.substringAfter("Room not found: ", "")?.takeIf { it.isNotBlank() }
                            // 手机通知栏: 后台时告知房间解散 (2026-08-21)
                            com.unciv.ui.screens.worldscreen.FsNotifier.notify(
                                "disband",
                                "The room has been disbanded".tr(),
                                ("The room has been disbanded" + if (reason != null) " ($reason)" else "").tr())
                            launchOnGLThread {
                                // UncivGC: 对局中房间被解散 (只剩1人/管理员/胜利清理等) → 提示原因再回大厅 (2026-08-21)
                                // 坑: Toast/弹窗挂旧世界屏会随 replaceCurrentScreen 的 dispose() 瞬间销毁, 永远看不到
                                // → 必须挂到新的大厅屏上 (构造后先加弹窗再切屏)
                                // 2026-08-21 用户要求: 自动消失的 Toast 改弹窗, 玩家点确定才消失
                                restoreMultiplayerServer()
                                val lobbyScreen = LobbyScreen()
                                com.unciv.ui.popups.ConfirmPopup(
                                    lobbyScreen,
                                    ("The room has been disbanded" + if (reason != null) " ($reason)" else "").tr(),
                                    "OK".tr()
                                ) { }.open(force = true)
                                UncivGame.Current.replaceCurrentScreen(lobbyScreen)
                            }
                            return@run
                        }
                    }
                    try {
                        Thread.sleep(3000)
                    } catch (ie: InterruptedException) {
                        return@run
                    }
                } finally {
                    gameWatcherRunning = false
                }
            }
        }

        // ---- 服务器设置 → 本机设置 ----
        private fun JsonObject.s(key: String) = this[key]?.jsonPrimitive?.contentOrNull
        private fun JsonObject.b(key: String, def: Boolean) =
            this[key]?.jsonPrimitive?.let { if (it.isString) it.content.toBooleanStrictOrNull() else it.booleanOrNull } ?: def
        private fun JsonObject.i(key: String, def: Int) = this[key]?.jsonPrimitive?.intOrNull ?: def
        private fun JsonObject.f(key: String, def: Float) =
            this[key]?.jsonPrimitive?.let { it.floatOrNull ?: it.content.toFloatOrNull() } ?: def
        private fun JsonObject.l(key: String, def: Long) =
            this[key]?.jsonPrimitive?.let { it.longOrNull ?: it.content.toLongOrNull() } ?: def
        private val JsonElement.jsonPrimitive get() = this as JsonPrimitive

        private fun applyServerSettings(setup: GameSetupInfo, settings: Map<String, JsonElement>) {
            val gp = settings["gp"] as? JsonObject ?: return
            val mp = settings["mp"] as? JsonObject
            val g = setup.gameParameters
            g.baseRuleset = gp.s("baseRuleset") ?: g.baseRuleset
            gp["mods"]?.let { modsJson ->
                val mods = (modsJson as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                if (mods != null) g.mods = LinkedHashSet(mods)
            }
            // AI 电脑列表 (房主在房间界面添加, 随设置同步)
            gp["aiPlayers"]?.let { arr ->
                val aiCivs = (arr as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: return@let
                val kept = g.players.filterNot { it.playerType == PlayerType.AI && it.playerId.isEmpty() }
                val newAIs = aiCivs.map { civ ->
                    Player().apply {
                        playerType = PlayerType.AI
                        chosenCiv = civ
                    }
                }
                g.players = ArrayList(kept + newAIs)
            }
            g.difficulty = gp.s("difficulty") ?: g.difficulty
            g.speed = gp.s("speed") ?: g.speed
            g.startingEra = gp.s("startingEra") ?: g.startingEra
            gp["victoryTypes"]?.let { vt ->
                val arr = (vt as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                if (arr != null) g.victoryTypes = ArrayList(arr)
            }
            g.espionageEnabled = gp.b("espionageEnabled", g.espionageEnabled)
            g.simultaneousTurns = gp.b("simultaneousTurns", g.simultaneousTurns)
            gp["fsTurnTimes"]?.let { arr ->
                val list = (arr as? JsonArray)?.mapNotNull { it.jsonPrimitive.floatOrNull }
                if (list != null && list.size == 5) g.fsTurnTimes = list.toTypedArray()
            }
            g.fsSettleLockSeconds = gp.i("fsSettleLockSeconds", g.fsSettleLockSeconds)
            g.fsTeamCount = gp.i("fsTeamCount", g.fsTeamCount)
            g.noStartBias = gp.b("noStartBias", g.noStartBias)
            g.noBarbarians = gp.b("noBarbarians", g.noBarbarians)
            g.ragingBarbarians = gp.b("ragingBarbarians", g.ragingBarbarians)
            g.reRollableRandom = gp.b("reRollableRandom", g.reRollableRandom)
            g.oneCityChallenge = gp.b("oneCityChallenge", g.oneCityChallenge)
            g.nuclearWeaponsEnabled = gp.b("nuclearWeaponsEnabled", g.nuclearWeaponsEnabled)
            g.godMode = gp.b("godMode", g.godMode)
            g.maxTurns = gp.i("maxTurns", g.maxTurns)
            g.numberOfCityStates = gp.i("numberOfCityStates", g.numberOfCityStates)
            g.noCityRazing = gp.b("noCityRazing", g.noCityRazing)

            if (mp != null) {
                val m = setup.mapParameters
                m.type = mp.s("type") ?: m.type
                m.shape = mp.s("shape") ?: m.shape
                // mapSize 是 MapSize 类型: 预定义按名称转换; 自定义 (Custom) 用半径/宽/高重建
                mp.s("mapSize")?.let { sizeName ->
                    if (sizeName == com.unciv.logic.map.MapSize.custom) {
                        val w = mp.i("customMapSizeWidth", m.mapSize.width)
                        val h = mp.i("customMapSizeHeight", m.mapSize.height)
                        val r = mp.i("customMapSizeRadius", m.mapSize.radius)
                        m.mapSize = when {
                            w > 0 && h > 0 -> com.unciv.logic.map.MapSize(w, h)
                            r > 0 -> com.unciv.logic.map.MapSize(r)
                            else -> m.mapSize
                        }
                    } else {
                        MapSize.Predefined.values().firstOrNull { it.name == sizeName }?.let { m.mapSize = MapSize(it) }
                    }
                }
                m.mapResources = mp.s("mapResources") ?: m.mapResources
                m.mirroring = mp.s("mirroring") ?: m.mirroring
                m.worldWrap = mp.b("worldWrap", m.worldWrap)
                m.legendaryStart = mp.b("legendaryStart", m.legendaryStart)
                m.strategicBalance = mp.b("strategicBalance", m.strategicBalance)
                m.noRuins = mp.b("noRuins", m.noRuins)
                m.noNaturalWonders = mp.b("noNaturalWonders", m.noNaturalWonders)
                m.seed = mp.l("seed", m.seed)
                m.tilesPerBiomeArea = mp.i("tilesPerBiomeArea", m.tilesPerBiomeArea)
                m.maxCoastExtension = mp.i("maxCoastExtension", m.maxCoastExtension)
                m.elevationExponent = mp.f("elevationExponent", m.elevationExponent)
                m.temperatureintensity = mp.f("temperatureintensity", m.temperatureintensity)
                m.temperatureShift = mp.f("temperatureShift", m.temperatureShift)
                m.vegetationRichness = mp.f("vegetationRichness", m.vegetationRichness)
                m.rareFeaturesRichness = mp.f("rareFeaturesRichness", m.rareFeaturesRichness)
                m.resourceRichness = mp.f("resourceRichness", m.resourceRichness)
                m.waterThreshold = mp.f("waterThreshold", m.waterThreshold)
            }
        }
    }

    /** 设置局部刷新: 只更新设置表格内容, 不重建整个界面 (消闪烁) */
    private fun refreshSettingsTables() {
        newGameOptionsTable.update()
        mapOptionsTable.syncFullFromMapParameters()
        // 成员/文明列由 syncRoom 刷新
    }

    private var closed = false
    private var enteredGame = false
    private var voluntarilyLeft = false
    private var lastRoomVersion = -1
    private var currentRoom: LobbyRoom? = null
    /** 已做过模组新版检查的房间设置指纹 (设置变了才重新检查) */
    private var lastUpdateCheckedSettings = ""

    /** 最近一次应用/推送的服务端设置指纹 (实例级 — 屏幕重建后重新同步, 避免跨实例/跨房间串扰) */
    @Volatile
    private var lastAppliedSettings = ""
    /** 最近一次推送的设置指纹 (房主自动推送去重) */
    @Volatile
    private var lastPushedSettings = ""
    /** 是否已从服务器同步过设置 (同步前禁止推送 — 防止屏幕重建后把本地默认值推上去覆盖房间设置) */
    @Volatile
    private var serverSynced = false

    private val nickname: String
        get() = UncivGame.Current.settings.lobbyNickname.ifBlank { "Player" }
    private val playerId: String
        get() = UncivGame.Current.settings.multiplayer.getUserId()

    private val readyButton = "Ready".toTextButton()
    private val startLobbyButton = "Start game".toTextButton().apply { color = Color.GREEN }

    // ---- UncivGC 房间聊天 (2026-08-29: 复用 FsChatPanel — 与游戏内聊天同一份 UI/数据) ----
    private val chatButton = "Chat".toTextButton()
    private var chatPopup: Popup? = null
    private var chatPanel: com.unciv.ui.components.widgets.FsChatPanel? = null
    /** 房间聊天未读: 弹窗打开时清零 (与游戏内 "Chat (n)" 一致) — 2026-08-29 */
    private var chatReadSeq = 0
    private var chatUnreadCount = 0

    private fun openChatPopup() {
        if (chatPopup != null) { chatPopup!!.close(); chatPopup = null }
        val popup = Popup(this)
        popup.addGoodSizedLabel("Room chat".tr()).row()

        // 帧同步聊天弹窗共享组件: 频道列表 (世界/队伍/私聊) + 消息 + 输入框, 与游戏内完全一致
        val panel = com.unciv.ui.components.widgets.FsChatPanel(
            roomId = roomId,
            myId = playerId,
            myNick = nickname,
            memberCivOf = { pid -> currentRoom?.members?.firstOrNull { it.playerId == pid }?.civ },
            stageWidth = stage.width,
            stageHeight = stage.height,
        )
        popup.add(panel).row()
        chatPopup = popup
        chatPanel = panel
        panel.closeRequested = {
            chatPopup?.close()
            chatPopup = null
            chatPanel = null
        }
        popup.closeListeners.add {
            // 关闭前把已读基线更新到 panel 最新 (弹窗期间收到的消息已读,
            // 否则关闭后弹窗期间的消息 — 含自己发的 — 被误计未读) — 2026-08-29
            val lastRead = panel.lastMessageSeq()
            if (lastRead > chatReadSeq) chatReadSeq = lastRead
            panel.disposePolling()
            chatPopup = null
            chatPanel = null
        }
        // 打开弹窗 = 已读: 拉服务器最新 seq 作基线 (panel 轮询可能还是旧值,
        // 否则关闭后新消息被误计未读 → "刚读完又变 Chat(n)") — 2026-08-29
        chatReadSeq = 0
        com.unciv.utils.Concurrency.run("RoomChatReadBaseline") {
            try {
                val room = com.unciv.logic.lobby.LobbyApi.getRoom(roomId, playerId)
                val newBaseline = room.chat.maxOfOrNull { it.seq } ?: 0
                com.unciv.utils.Concurrency.runOnGLThread {
                    if (chatReadSeq < newBaseline) chatReadSeq = newBaseline
                    chatUnreadCount = 0
                    chatButton.setText("Chat".tr())
                }
            } catch (e: Exception) {}
        }
        chatUnreadCount = 0
        chatButton.setText("Chat".tr())
        popup.open()
    }

    /** 房间聊天未读轮询 (与游戏内 ChatButton 同款: 按钮显示 "Chat (n)") — 2026-08-29 */
    private fun startChatUnreadPolling() {
        com.unciv.utils.Concurrency.run("RoomChatUnreadPoll") {
            while (!closed) {
                try {
                    if (chatPopup == null) {  // 弹窗开着已读, 不重复计
                        val room = com.unciv.logic.lobby.LobbyApi.getRoom(roomId, playerId)
                        val maxSeq = room.chat.maxOfOrNull { it.seq } ?: 0
                        // 与游戏内 ChatButton 一致: 过滤自己发的 + 只看相关频道 (world/team/发给我的私聊)
                        val newCount = room.chat.count {
                            it.seq > chatReadSeq && it.playerId != playerId &&
                            (it.to == "world" || it.to.isEmpty() || it.to == "team" ||
                             (it.to.startsWith("player:") && it.to == "player:$playerId"))
                        }
                        if (maxSeq > chatReadSeq && newCount != chatUnreadCount) {
                            chatUnreadCount = newCount
                            com.unciv.utils.Concurrency.runOnGLThread {
                                // "Chat".tr() 翻译 + 数字拼接 (整串 tr 找不到 "Chat (n)" 条目 → 显示英文) — 2026-08-29
                                chatButton.setText(
                                    if (chatUnreadCount > 0) "Chat".tr() + " ($chatUnreadCount)" else "Chat".tr())
                            }
                        }
                    }
                } catch (e: Exception) {
                }
                try { Thread.sleep(3000) } catch (e: InterruptedException) { break }
            }
        }
    }

    init {
        activeRoomId = roomId

        // ---- 底部按钮: 替换原版 Start game! 按钮组 ----
        rightSideGroup.clearChildren()
        val bar = Table()
        bar.defaults().pad(5f)
        readyButton.enable()
        bar.add(readyButton).width(150f).fillX()
        bar.add(startLobbyButton).width(230f).fillX()
        rightSideGroup.addActor(bar)
        // 固定到最右边: 去掉 rightSideGroup 单元格的右内边距, 组内靠右
        rightSideGroup.align(com.badlogic.gdx.utils.Align.right)
        bottomTable.getCell(rightSideGroup)?.padRight(0f)

        // ---- 聊天按钮: 放「退出房间」(closeButton, 左下角) 旁边, 不放开始游戏按钮那边 ----
        // bottomTable 原布局: [closeButton][descriptionScroll(grow)][rightSideGroup] → 重建为:
        // [closeButton][chatButton][descriptionScroll(grow)][rightSideGroup]
        bottomTable.clearChildren()
        bottomTable.add(closeButton).pad(10f)
        bottomTable.add(chatButton).pad(10f)
        bottomTable.add(descriptionScroll).grow()
        bottomTable.add(rightSideGroup)
        chatButton.onClick { openChatPopup() }
        startChatUnreadPolling()  // 2026-08-29: 房间聊天按钮未读提示 "Chat (n)"

        // ---- 退出房间 (替换原版返回动作: 先清掉原版 Tap 激活, 避免双重弹屏) ----
        closeButton.setText("Leave room".tr())
        ActorAttachments.get(closeButton).clearActivationActions(ActivationTypes.Tap)
        closeButton.onActivation {
            voluntarilyLeft = true
            activeRoomId = null  // 明确退出房间: 清除房间 ID, 避免游戏内菜单误操作旧房间
            // 主动退出标记: 大厅 LobbyPoll 不再自动拉回 (防退出死循环); 下次进入游戏时复位
            LobbyRoomScreen.leavingGame = true
            Concurrency.run("LobbyLeave") {
                try {
                    // 2026-08-28: 帧同步 playing 局主动退出走 /exit (文明交 AI 的明确信号);
                    // 等待/普通局保持 leaveRoom (只移除成员 / 官方 resign 流程)
                    val playing = currentRoom?.status == "playing"
                    if (playing) {
                        LobbyApi.exitRoom(roomId, nickname, playerId)
                    } else {
                        LobbyApi.leaveRoom(roomId, nickname, playerId)
                    }
                } catch (e: Exception) {
                    // 房间可能已解散, 直接返回
                }
                launchOnGLThread {
                    // 栈里有主菜单 → 正常 pop 回上一屏 (大厅/主菜单)
                    // 栈里没有主菜单 (对局结束/异常路径 replace 进房间, 栈深 1) → popScreen 会弹
                    // "退出游戏"确认框 (挂在 stage 上关不掉) → 直接回大厅, 大厅退出按钮再回主菜单
                    val hasMainMenu = game.getScreensOfType(
                        com.unciv.ui.screens.mainmenuscreen.MainMenuScreen::class).any()
                    if (hasMainMenu) game.popScreen()
                    else game.replaceCurrentScreen(LobbyScreen())
                }
            }
        }
        closeButton.keyShortcuts.add(KeyCharAndCode.BACK)

        readyButton.onClick { toggleReady() }
        startLobbyButton.onClick { tryStart() }

        // ---- 文明变更 (仅自己) → 同步服务器 ----
        playerPickerTable.onCivChanged = { player ->
            if (player.playerId == playerId && player.chosenCiv.isNotEmpty()) {
                Concurrency.run("LobbySyncCiv") {
                    try {
                        LobbyApi.setCiv(roomId, nickname, player.chosenCiv, playerId)
                    } catch (e: Exception) {
                        launchOnGLThread { ToastPopup("Civilization sync failed: [${e.message}]".tr(), this@LobbyRoomScreen) }
                    }
                }
            }
        }

        // ---- 成员状态喂给玩家表 (昵称/准备/房主/队伍) ----
        playerPickerTable.lobbyGetStatus = { p ->
            currentRoom?.members?.firstOrNull { it.playerId == p.playerId }
                ?.let { LobbyPlayerStatus(it.nickname, it.ready, it.isOwner, it.missingMods, it.team) }
        }

        // ---- UncivGC 组队: 队伍数 (房间设置) + 换队同步服务器 (2026-08-23) ----
        playerPickerTable.lobbyTeamCount = {
            val gp = currentRoom?.settings?.get("gp") as? JsonObject
            if (gp == null) 1 else gp["fsTeamCount"]?.jsonPrimitive?.intOrNull ?: 1
        }
        playerPickerTable.onTeamChanged = { player, team ->
            if (player.playerId == playerId) {
                Concurrency.run("LobbySyncTeam") {
                    try {
                        val r = LobbyApi.setTeam(roomId, nickname, team, playerId)
                        if (!r.ok) launchOnGLThread { ToastPopup(r.msg.tr(), this@LobbyRoomScreen) }
                    } catch (e: Exception) {
                        launchOnGLThread { ToastPopup("Team sync failed: [${e.message}]".tr(), this@LobbyRoomScreen) }
                    }
                }
            }
        }

        // ---- 当前用户是否房主 (踢出按钮显示条件) ----
        playerPickerTable.lobbyAmOwner = {
            currentRoom?.members?.firstOrNull { it.playerId == playerId }?.isOwner == true
        }

        // ---- 房主踢人 ----
        playerPickerTable.lobbyOnKick = { p -> kickPlayer(p.playerId) }

        // ---- 房主设置自动推送 (实时刷新, 无保存按钮): 1 秒检测一次本地变更 ----
        Concurrency.run("LobbySettingsSync") {
            while (!closed) {
                try {
                    val room = currentRoom
                    val me = room?.members?.firstOrNull { it.playerId == playerId }
                    if (me?.isOwner == true && room?.status == "waiting" && serverSynced) {
                        val payload = buildSettingsPayload()
                        val fp = payload.toString()
                        if (fp != lastPushedSettings) {
                            val result = LobbyApi.updateSettings(roomId, nickname, playerId, payload)
                            if (result.ok) {
                                lastPushedSettings = fp
                                // 自己刚推的就是屏幕上的, 避免自己触发重建
                                lastAppliedSettings = fp
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 静默, 下轮再试
                }
                Thread.sleep(400)  // 设置推送检测间隔 (实时性优先)
            }
        }

        // ---- 模组统一性: 上报自己缺哪些模组 (服务器开始前检查); 变更才上报 ----
        var lastReportedMods = ""
        Concurrency.run("LobbyModsReport") {
            while (!closed) {
                try {
                    val room = currentRoom
                    if (room != null && room.status == "waiting") {
                        val missing = missingModsOf(room.settings)
                        val fp = missing.joinToString(",")
                        if (fp != lastReportedMods) {
                            lastReportedMods = fp
                            LobbyApi.reportMods(roomId, nickname, playerId, missing)
                        }
                    }
                } catch (e: Exception) {
                    // 静默重试
                }
                Thread.sleep(2000)
            }
        }

        // ---- 轮询(长轮询): 成员/文明/状态/设置实时同步 + 开始后进入游戏 ----
        Concurrency.run("LobbyRoomPoll") {
            var since = -1
            while (!closed) {
                try {
                    // 长轮询: 房间一变就立即返回, 25 秒无变化返回 null
                    val room = LobbyApi.waitRoom(roomId, since, currentPlayerId()) ?: continue
                    since = room.version
                    // 设置同步: 服务器设置变了 → 局部刷新设置表格 (规则集/模组变了才整屏重建, 消闪烁)
                    if (room.settings.isEmpty()) {
                        // 房间还没有任何设置 (刚创建) — 视为已同步, 房主可以直接推
                        serverSynced = true
                    }
                    val settingsStr = room.settings.toString()
                    if (settingsStr != lastAppliedSettings && room.settings.isNotEmpty()) {
                        lastAppliedSettings = settingsStr
                        serverSynced = true
                        val gp = room.settings["gp"] as? JsonObject
                        val rulesetChanged = gp?.s("baseRuleset") != null && gp.s("baseRuleset") != ruleset.name
                        val newMods = (gp?.get("mods") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet()
                        val modsChanged = gp?.get("mods") != null && newMods != gameSetupInfo.gameParameters.mods
                        if (rulesetChanged || modsChanged) {
                            // 新设置需要但本地没装的模组 → 先下载再原位应用 (避免加载失败回退 G&K, 看起来"没切换")
                            val pendingMissing = missingModsOf(room.settings)
                            if (pendingMissing.isNotEmpty()) {
                                val missingFp = pendingMissing.joinToString(",")
                                if (missingFp != promptedMissingMods) {
                                    promptedMissingMods = missingFp
                                    launchOnGLThread {
                                        if (!closed) {
                                            ConfirmPopup(this@LobbyRoomScreen, "Missing mods: [${pendingMissing.joinToString(", ")}]\nDownload?".tr(), "Download".tr()) {
                                                downloadMissingMods(pendingMissing, this@LobbyRoomScreen) {
                                                    // 装好后原位应用新设置 (不重建屏幕)
                                                    applyServerSettings(gameSetupInfo, room.settings)
                                                    tryUpdateRuleset(updateUI = true)
                                                    updateTables()
                                                    mapOptionsTable.syncFullFromMapParameters()
                                                }
                                            }.open()
                                        }
                                    }
                                }
                            } else {
                                // 模组齐全 → 原位应用 (重载规则集 + 刷新各表, 不重建屏幕)
                                launchOnGLThread {
                                    if (!closed) {
                                        applyServerSettings(gameSetupInfo, room.settings)
                                        tryUpdateRuleset(updateUI = true)
                                        updateTables()
                                        mapOptionsTable.syncFullFromMapParameters()
                                    }
                                }
                            }
                        } else {
                            launchOnGLThread {
                                if (!closed) {
                                    applyServerSettings(gameSetupInfo, room.settings)
                                    refreshSettingsTables()
                                }
                            }
                        }
                    }
                    launchOnGLThread { syncRoom(room) }
                    if (room.status == "playing" && !room.gameId.isNullOrEmpty() && !enteredGame
                        && enteredGameForRoom != room.id) {
                        enteredGame = true
                        enteredGameForRoom = room.id
                        enterGame(room.gameId!!)
                        break
                    }
                    // 被房主踢出: 成员列表里没有自己 → 回大厅并提示 (主动退出不算)
                    if (!voluntarilyLeft && room.status != "playing" && room.members.none { it.playerId == playerId }) {
                        launchOnGLThread {
                            ToastPopup("You have been kicked by the host".tr(), this@LobbyRoomScreen)
                            // 栈深 1 (异常路径 replace 进房间) → popScreen 会弹"退出游戏"确认 → 直接回大厅
                            if (game.getScreensOfType(com.unciv.ui.screens.mainmenuscreen.MainMenuScreen::class).any())
                                game.popScreen()
                            else
                                game.replaceCurrentScreen(LobbyScreen())
                        }
                        break
                    }
                } catch (e: Exception) {
                    if (e.message?.contains("404") == true || e.message?.contains("Room not found") == true) {  // 匹配服务器 404 协议消息 (服务器已英文化)
                        // UncivGC: 主动退出时房间解散是预期 (最后一人退出服务器会解散) — 不弹提示也不重复弹屏
                        if (!voluntarilyLeft) {
                            val reason = e.message?.substringAfter("Room not found: ", "")?.takeIf { it.isNotBlank() }
                            // 手机通知栏: 后台时告知房间解散 (2026-08-21)
                            com.unciv.ui.screens.worldscreen.FsNotifier.notify(
                                "disband",
                                "The room has been disbanded".tr(),
                                ("The room has been disbanded" + if (reason != null) " ($reason)" else "").tr())
                            launchOnGLThread {
                                // Toast 必须挂到切屏后的目标屏 — popScreen/replaceCurrentScreen 都会 dispose 旧屏 (2026-08-21)
                                // 2026-08-21 用户要求: 自动消失的 Toast 改弹窗, 玩家点确定才消失
                                val msg = ("The room has been disbanded" + if (reason != null) " ($reason)" else "").tr()
                                // 栈深 1 (异常路径 replace 进房间) → popScreen 会弹"退出游戏"确认 → 直接回大厅
                                if (game.getScreensOfType(com.unciv.ui.screens.mainmenuscreen.MainMenuScreen::class).any()) {
                                    val target = game.popScreen()
                                    if (target != null)
                                        com.unciv.ui.popups.ConfirmPopup(target, msg, "OK".tr()) { }.open(force = true)
                                } else {
                                    val lobby = LobbyScreen()
                                    com.unciv.ui.popups.ConfirmPopup(lobby, msg, "OK".tr()) { }.open(force = true)
                                    game.replaceCurrentScreen(lobby)
                                }
                            }
                        }
                        break
                    }
                    Thread.sleep(500)  // 网络异常退避后重试
                }
            }
        }
    }

    /** 服务器成员 → 原版玩家列表 (按 playerId 保留 Player 对象, 文明/人数变化自动反映)
     *  只有房间 version 变化才重建界面, 避免每 3 秒全量刷新导致的卡顿 */
    private fun syncRoom(room: LobbyRoom) {
        if (closed) return
        if (room.version == lastRoomVersion) return
        lastRoomVersion = room.version
        currentRoom = room
        // ---- 房间聊天: 2026-08-29 改由 FsChatPanel 内部轮询 (与游戏内聊天同组件), 这里不再增量收 ---
        val existing = gameSetupInfo.gameParameters.players
        val newPlayers = ArrayList<Player>()
        for (m in room.members) {
            val p = existing.firstOrNull { it.playerId == m.playerId }
                ?: Player().apply { playerType = PlayerType.Human }
            p.playerId = m.playerId
            p.chosenCiv = m.civ ?: "Random"
            newPlayers.add(p)
        }
        // 保留 AI 电脑 (房主在房间界面添加的, 不随成员同步被清掉)
        newPlayers.addAll(existing.filter { it.playerType == PlayerType.AI && it.playerId.isEmpty() })
        gameSetupInfo.gameParameters.players = newPlayers
        playerPickerTable.update()

        // ---- 按钮状态 ----
        val me = room.members.firstOrNull { it.playerId == playerId }
        val isOwner = me?.isOwner == true
        activeAmOwner = isOwner
        readyButton.setText(if (me?.ready == true) "Unready".tr() else "Ready".tr())
        readyButton.isVisible = room.status == "waiting"
        startLobbyButton.isVisible = room.status == "waiting" || room.status == "starting"
        val allReady = room.members.isNotEmpty() && room.members.all { it.ready }
        val allModsReady = room.members.all { it.missingMods.isEmpty() }
        val enoughPlayers = room.members.size >= 2
        val canStart = allReady && allModsReady && enoughPlayers
        if (isOwner) {
            // 房主: 至少2人 + 全员准备 + 全员模组齐全后才能开始
            startLobbyButton.isDisabled = !canStart
            startLobbyButton.setText(
                when {
                    room.status == "starting" -> "Generating map...".tr()
                    !enoughPlayers -> "Waiting for other players...".tr()
                    !allModsReady -> "Waiting for mods...".tr()
                    !allReady -> "Waiting for everyone to be ready...".tr()
                    else -> "Start game".tr()
                }
            )
        } else {
            // 非房主: 只读状态展示, 不能点
            startLobbyButton.isDisabled = true
            startLobbyButton.setText(
                when {
                    room.status == "starting" -> "Generating map...".tr()
                    !enoughPlayers -> "Waiting for other players...".tr()
                    !allModsReady -> "Waiting for mods...".tr()
                    !allReady -> "Waiting for everyone to be ready...".tr()
                    else -> "Waiting for the game to start".tr()
                }
            )
        }

        // ---- 设置权限: 只有房主能改, 非房主整个面板锁定 (touchable 禁用, 锁全部控件) ----
        newGameOptionsTable.touchable = if (isOwner) Touchable.enabled else Touchable.disabled
        mapOptionsTable.touchable = if (isOwner) Touchable.enabled else Touchable.disabled

        // ---- 模组检查: 房主选的模组本地缺了 → 提示下载 (同组缺失只提示一次) ----
        val missing = missingModsOf(room.settings)
        val missingFp = missing.joinToString(",")
        if (missing.isNotEmpty() && missingFp != promptedMissingMods) {
            promptedMissingMods = missingFp
            ConfirmPopup(this, "Missing mods: [${missing.joinToString(", ")}]\nDownload?".tr(), "Download".tr()) {
                downloadMissingMods(missing, this) {
                    // 下载完原位应用设置 (不重建屏幕, 消闪烁)
                    applyServerSettings(gameSetupInfo, room.settings)
                    tryUpdateRuleset(updateUI = true)
                    updateTables()
                    mapOptionsTable.syncFullFromMapParameters()
                }
            }.open()
        } else if (missing.isEmpty() && room.settings.isNotEmpty()) {
            // ---- 模组更新检查: 已装的模组镜像里有新版 → 提示从国内镜像更新 (每套设置只提示一次) ----
            val settingsFp = room.settings.toString()
            if (settingsFp != lastUpdateCheckedSettings) {
                lastUpdateCheckedSettings = settingsFp
                Concurrency.run("LobbyCheckUpdates") {
                    try {
                        val manifest = LobbyApi.modMirrorManifest()
                        val outdated = outdatedMirrorMods(room.settings, manifest)
                        if (outdated.isNotEmpty()) {
                            val outdatedFp = outdated.joinToString(",")
                            if (outdatedFp != promptedOutdatedMods) {
                                promptedOutdatedMods = outdatedFp
                                launchOnGLThread {
                                    if (!closed) {
                                        ConfirmPopup(this@LobbyRoomScreen,
                                            "Mod updates available: [${outdated.joinToString(", ")}]\nUpdate from the mirror?".tr(), "Update".tr()) {
                                            val loading = Popup(this@LobbyRoomScreen)
                                            loading.addGoodSizedLabel("Updating...".tr())
                                            loading.open()
                                            Concurrency.runOnNonDaemonThreadPool("LobbyUpdateMods") {
                                                val errs = installFromMirror(manifest, outdated, this@LobbyRoomScreen) { m, p ->
                                                    launchOnGLThread { loading.reuseWith("[$m] $p%", false) }
                                                }
                                                launchOnGLThread {
                                                    loading.close()
                                                    if (errs.isNotEmpty()) {
                                                        ToastPopup(errs.joinToString("\n"), this@LobbyRoomScreen)
                                                    } else {
                                                        // 重载规则集缓存, 让当前房间立即用上新版模组
                                                        RulesetCache.loadRulesets()
                                                        UncivGame.Current.translations.tryReadTranslationForCurrentLanguage()
                                                        ToastPopup("Mod update complete".tr(), this@LobbyRoomScreen)
                                                    }
                                                }
                                            }
                                        }.open()
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // 网络失败静默, 下轮设置变化再试
                    }
                }
            }
        }
    }

    // ---- 本机设置 → 服务器 (房主保存) ----
    private fun buildSettingsPayload(): Map<String, JsonElement> {
        val gp = gameSetupInfo.gameParameters
        val mp = gameSetupInfo.mapParameters
        val gpJson = buildJsonObject {
            put("baseRuleset", gp.baseRuleset)
            // 模组型基础规则集 (如 LM2) 同时进 mods, 保证生成端和缺失检测都能处理
            val standardBases = setOf("Civ V - Vanilla", "Civ V - Gods & Kings")
            val effectiveMods = LinkedHashSet(gp.mods)
            if (gp.baseRuleset !in standardBases && gp.baseRuleset.isNotEmpty()) {
                effectiveMods.add(gp.baseRuleset)
            }
            put("mods", JsonArray(effectiveMods.map { JsonPrimitive(it) }))
            // 房主添加的 AI 电脑 (随设置同步给全员 + 生成端)
            val aiPlayers = gp.players.filter { it.playerType == PlayerType.AI && it.playerId.isEmpty() }.map { it.chosenCiv }
            put("aiPlayers", JsonArray(aiPlayers.map { JsonPrimitive(it) }))
            put("difficulty", gp.difficulty)
            put("speed", gp.speed)
            put("startingEra", gp.startingEra)
            put("victoryTypes", JsonArray(gp.victoryTypes.map { JsonPrimitive(it) }))
            put("espionageEnabled", gp.espionageEnabled)
            put("simultaneousTurns", gp.simultaneousTurns)
            gp.fsTurnTimes?.let { put("fsTurnTimes", JsonArray(it.map { JsonPrimitive(it) })) }
            put("fsSettleLockSeconds", gp.fsSettleLockSeconds)
            put("fsTeamCount", gp.fsTeamCount)
            put("noStartBias", gp.noStartBias)
            put("noBarbarians", gp.noBarbarians)
            put("ragingBarbarians", gp.ragingBarbarians)
            put("reRollableRandom", gp.reRollableRandom)
            put("oneCityChallenge", gp.oneCityChallenge)
            put("nuclearWeaponsEnabled", gp.nuclearWeaponsEnabled)
            put("godMode", gp.godMode)
            put("maxTurns", gp.maxTurns)
            put("numberOfCityStates", gp.numberOfCityStates)
            put("noCityRazing", gp.noCityRazing)
        }
        val mpJson = buildJsonObject {
            put("type", mp.type)
            put("shape", mp.shape)
            put("mapSize", mp.mapSize.name)
            // 自定义地图大小: 半径/宽/高单独传 (mapSize.name 只有 "Custom", 不带尺寸)
            put("customMapSizeRadius", mp.mapSize.radius)
            put("customMapSizeWidth", mp.mapSize.width)
            put("customMapSizeHeight", mp.mapSize.height)
            put("mapResources", mp.mapResources)
            put("mirroring", mp.mirroring)
            put("worldWrap", mp.worldWrap)
            put("legendaryStart", mp.legendaryStart)
            put("strategicBalance", mp.strategicBalance)
            put("noRuins", mp.noRuins)
            put("noNaturalWonders", mp.noNaturalWonders)
            put("seed", mp.seed)
            put("tilesPerBiomeArea", mp.tilesPerBiomeArea)
            put("maxCoastExtension", mp.maxCoastExtension)
            put("elevationExponent", mp.elevationExponent)
            put("temperatureintensity", mp.temperatureintensity)
            put("temperatureShift", mp.temperatureShift)
            put("vegetationRichness", mp.vegetationRichness)
            put("rareFeaturesRichness", mp.rareFeaturesRichness)
            put("resourceRichness", mp.resourceRichness)
            put("waterThreshold", mp.waterThreshold)
        }
        return mapOf("gp" to gpJson, "mp" to mpJson)
    }

    private fun kickPlayer(targetPlayerId: String) {
        val target = currentRoom?.members?.firstOrNull { it.playerId == targetPlayerId } ?: return
        Concurrency.run("LobbyKick") {
            try {
                // 按 playerId 踢人 (昵称不唯一, 按昵称可能踢错人)
                val result = LobbyApi.kick(roomId, nickname, targetPlayerId, playerId)
                launchOnGLThread { ToastPopup(result.msg.tr(), this@LobbyRoomScreen) }
            } catch (e: Exception) {
                launchOnGLThread { ToastPopup("Kick failed: [${e.message}]".tr(), this@LobbyRoomScreen) }
            }
        }
    }

    private fun toggleReady() {
        val me = currentRoom?.members?.firstOrNull { it.playerId == playerId } ?: return
        val target = !me.ready
        // 乐观更新: 不等服务器轮询, 立即切换按钮状态; 失败再回滚
        readyButton.setText(if (target) "Unready".tr() else "Ready".tr())
        Concurrency.run("LobbyReady") {
            try {
                LobbyApi.setReady(roomId, nickname, target, playerId)
            } catch (e: Exception) {
                launchOnGLThread {
                    readyButton.setText(if (!target) "Unready".tr() else "Ready".tr())
                    ToastPopup("Operation failed: [${e.message}]".tr(), this@LobbyRoomScreen)
                }
            }
        }
    }

    private fun tryStart() {
        val loading = Popup(this)
        loading.addGoodSizedLabel("Generating map and starting...".tr())
        loading.open()
        Concurrency.run("LobbyStart") {
            try {
                val result = LobbyApi.startGame(roomId, nickname, playerId)
                launchOnGLThread {
                    loading.close()
                    if (!result.ok) {
                        ToastPopup(result.msg.tr().ifEmpty { "Start failed".tr() }, this@LobbyRoomScreen)
                    }
                    // 成功后由轮询检测到 playing 并自动进入游戏
                }
            } catch (e: Exception) {
                launchOnGLThread { loading.reuseWith("Start failed: [${e.message}]".tr(), true) }
            }
        }
    }

    private fun enterGame(gameId: String) {
        Concurrency.runOnGLThread("LobbyEnterToast") {
            ToastPopup("Game started, entering...".tr(), this@LobbyRoomScreen)
        }
        val room = currentRoom
        if (room != null) {
            enterLobbyGame(gameId, room, this)
        }
    }

    /** 重建屏幕 (旋转/键盘等) 时带上当前设置, 避免闪回默认值; 推送循环等 serverSynced 同步后才推, 不会覆盖房间设置 */
    override fun recreate(): BaseScreen = LobbyRoomScreen(roomId, initialName, buildSettingsPayload())

    override fun dispose() {
        closed = true
        // 注意: 不能清 activeRoomId — loadGame 会销毁本屏幕, 但游戏内菜单「退出房间」还需要房间 ID
        super.dispose()
    }
}

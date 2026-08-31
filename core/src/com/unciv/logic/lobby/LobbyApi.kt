package com.unciv.logic.lobby

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
data class ModMirrorEntry(val name: String = "", val size: Long = 0, val updatedAt: Double = 0.0, val md5: String = "", val version: String = "")

@Serializable
data class UpdateInfo(
    val code: Int = 0,  // 2026-09-01: 服务器构建号 (数字比较, 防 version 字符串格式漂移)
    val version: String = "",
    val notes: String = "",
    val apkSize: Long = 0,
    val apkMd5: String = "",
)
@Serializable
data class LobbyMember(
    val nickname: String,
    val playerId: String = "",
    val civ: String? = null,
    val ready: Boolean = false,
    val isOwner: Boolean = false,
    val joinedAt: Double = 0.0,
    val missingMods: List<String> = emptyList(),
    // UncivGC 组队 (2026-08-23): 队伍号 0=无 1/2/3
    val team: Int = 0,
)

@Serializable
data class LobbyRoom(
    val id: String,
    val name: String,
    val status: String = "waiting",   // waiting | starting | playing
    val gameId: String? = null,
    val version: Int = 1,
    val hasPassword: Boolean = false,  // 2026-08-31 房间密码 (列表锁标/加入需输密码)
    val playerCount: Int = 0,
    val owner: String? = null,
    val settings: Map<String, JsonElement> = emptyMap(),
    val members: List<LobbyMember> = emptyList(),
    // 列表摘要字段 (列表接口不返回完整 settings/members, 见 lobby_server.room_summary)
    val memberIds: List<String> = emptyList(),
    val baseRuleset: String? = null,
    val mods: List<String> = emptyList(),
    // UncivGC 房间聊天: 服务器只带最近 50 条, 客户端按 seq 增量渲染
    val chat: List<LobbyChatMessage> = emptyList(),
)

@Serializable
data class LobbyChatMessage(
    val seq: Int = 0,
    val playerId: String = "",
    val nickname: String = "",
    val text: String = "",
    val to: String = "world",  // UncivGC 2026-08-25 私聊: world/team/player:<playerId>
    val ts: Long = 0,
)

@Serializable
data class ApiResult(val ok: Boolean = false, val msg: String = "", val gameId: String? = null, val room: LobbyRoom? = null)

@Serializable
data class ApiError(val error: String? = null)

@Serializable
data class CreateRoomRequest(val name: String, val nickname: String, val playerId: String, val civ: String, val password: String = "")
@Serializable
data class JoinRequest(val nickname: String, val playerId: String, val civ: String, val password: String = "")
@Serializable
data class ReadyRequest(val nickname: String, val playerId: String, val ready: Boolean)
@Serializable
data class CivRequest(val nickname: String, val playerId: String, val civ: String)
@Serializable
data class TeamRequest(val nickname: String, val playerId: String, val team: Int)
@Serializable
data class LeaveRequest(val nickname: String, val playerId: String)
data class ExitConfirmRequest(val nickname: String, val playerId: String, val step: Int)
@Serializable
data class KickRequest(val nickname: String, val playerId: String, val target: String = "", val targetPlayerId: String = "")
@Serializable
data class StartRequest(val nickname: String, val playerId: String)
@Serializable
data class RestartRequest(val nickname: String, val playerId: String, val randomizeSeed: Boolean = false)
@Serializable
data class ModsRequest(val nickname: String, val playerId: String, val missingMods: List<String> = emptyList())
@Serializable
data class ChatRequest(val nickname: String, val playerId: String, val text: String, val to: String = "world")
@Serializable
data class SettingsRequest(val nickname: String, val playerId: String, val settings: Map<String, JsonElement>)

/** 联机大厅 API 客户端 (M2 服务器: unciv-lobby/lobby_server.py) */
object LobbyApi {
    // 生产默认; 本地联调可用 -Duncivgc.lobbyUrl=http://127.0.0.1:8124 覆盖
    // ⚠️ 开源仓库保持占位符 — 正式包由 release_build.sh 注入真实地址 (2026-08-31 合规)
    val SERVER_URL: String
        get() = System.getProperty("uncivgc.lobbyUrl") ?: "http://YOUR_LOBBY_HOST:8125"

    /** 房间接口鉴权 token (与服务器 LOBBY_TOKEN 一致; 防止陌生客户端建房/进房)
     *  ⚠️ 开源仓库保持占位符 — 正式包由 release_build.sh 注入真实 token (2026-08-31 合规) */
    const val LOBBY_TOKEN = "YOUR_LOBBY_TOKEN"

    private val client = HttpClient(CIO) {
        // 所有请求统一带鉴权头 (房间接口必需; 模组下载/健康检查服务器侧不强制)
        install(DefaultRequest) {
            header("X-Lobby-Token", LOBBY_TOKEN)
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 35000   // 长轮询最长挂 25s + 余量
        }
    }

    private suspend inline fun <reified T> parse(response: HttpResponse): T {
        if (!response.status.isSuccess()) {
            // 2026-08-31: 错误信息读取更稳 — ApiError 序列化失败时退回原始文本 (否则只显示连接异常)
            val err = try {
                response.body<ApiError>().error
            } catch (e: Exception) {
                try { response.bodyAsText().trim().ifEmpty { null } } catch (e2: Exception) { null }
            }
            throw RuntimeException(err ?: "HTTP ${response.status.value}")
        }
        return response.body()
    }

    suspend fun listRooms(): List<LobbyRoom> =
        parse(client.get("$SERVER_URL/api/rooms"))

    suspend fun createRoom(name: String, nickname: String, playerId: String, civ: String?, password: String = ""): LobbyRoom =
        parse(client.post("$SERVER_URL/api/rooms") {
            contentType(ContentType.Application.Json)
            setBody(CreateRoomRequest(name, nickname, playerId, civ ?: "", password))
        })

    suspend fun getRoom(roomId: String, playerId: String? = null): LobbyRoom =
        parse(client.get("$SERVER_URL/api/rooms/$roomId" + (if (playerId.isNullOrEmpty()) "" else "?playerId=${java.net.URLEncoder.encode(playerId, "UTF-8")}")))

    /** 长轮询: 房间变化立即返回; 25 秒无变化返回 null (playerId 用于私聊消息过滤) */
    suspend fun waitRoom(roomId: String, since: Int, playerId: String? = null): LobbyRoom? {
        val pid = if (playerId.isNullOrEmpty()) "" else "&playerId=${java.net.URLEncoder.encode(playerId, "UTF-8")}"
        val response = client.get("$SERVER_URL/api/rooms/$roomId/wait?since=$since$pid")
        if (response.status.value == 204) return null
        return parse(response)
    }

    suspend fun joinRoom(roomId: String, nickname: String, playerId: String, civ: String?, password: String = ""): ApiResult =
        parse(client.post("$SERVER_URL/api/rooms/$roomId/join") {
            contentType(ContentType.Application.Json)
            setBody(JoinRequest(nickname, playerId, civ ?: "", password))
        })

    suspend fun leaveRoom(roomId: String, nickname: String, playerId: String? = null): ApiResult =
        parse(client.post("$SERVER_URL/api/rooms/$roomId/leave") {
            contentType(ContentType.Application.Json)
            setBody(LeaveRequest(nickname, playerId ?: ""))
        })

    /** 主动退出房间 (独立广播, 2026-08-28): 与 leave 分离 — leave 只移除成员不转 AI (掉线/异常路径),
     *  exit 才是玩家明确"不玩了", 服务器才会把文明交 AI 托管 */
    suspend fun exitRoom(roomId: String, nickname: String, playerId: String? = null): ApiResult =
        parse(client.post("$SERVER_URL/api/rooms/$roomId/exit") {
            contentType(ContentType.Application.Json)
            setBody(LeaveRequest(nickname, playerId ?: ""))
        })

    /** 退出确认步骤广播 (2026-08-28 用户要求): 两次确认各发一次 step (1=第一次, 2=第二次),
     *  服务器日志区分主动退出 (step1+step2+/exit 三连) 与非主动路径 (只有 /exit 无确认序列) */
    suspend fun confirmExit(roomId: String, step: Int, nickname: String, playerId: String? = null): ApiResult =
        parse(client.post("$SERVER_URL/api/rooms/$roomId/exitconfirm") {
            contentType(ContentType.Application.Json)
            setBody(ExitConfirmRequest(nickname, playerId ?: "", step))
        })

    suspend fun setReady(roomId: String, nickname: String, ready: Boolean, playerId: String? = null): ApiResult =
        parse(client.post("$SERVER_URL/api/rooms/$roomId/ready") {
            contentType(ContentType.Application.Json)
            setBody(ReadyRequest(nickname, playerId ?: "", ready))
        })

    suspend fun setCiv(roomId: String, nickname: String, civ: String, playerId: String? = null): ApiResult =
        parse(client.post("$SERVER_URL/api/rooms/$roomId/civ") {
            contentType(ContentType.Application.Json)
            setBody(CivRequest(nickname, playerId ?: "", civ))
        })

    /** UncivGC 组队 (2026-08-23): 自选队伍 (0=无, 1/2/3) */
    suspend fun setTeam(roomId: String, nickname: String, team: Int, playerId: String? = null): ApiResult =
        parse(client.post("$SERVER_URL/api/rooms/$roomId/team") {
            contentType(ContentType.Application.Json)
            setBody(TeamRequest(nickname, playerId ?: "", team))
        })

    suspend fun kick(roomId: String, nickname: String, targetPlayerId: String, playerId: String? = null): ApiResult =
        parse(client.post("$SERVER_URL/api/rooms/$roomId/kick") {
            contentType(ContentType.Application.Json)
            setBody(KickRequest(nickname, playerId ?: "", target = "", targetPlayerId = targetPlayerId))
        })

    suspend fun startGame(roomId: String, nickname: String, playerId: String? = null): ApiResult =
        parse(client.post("$SERVER_URL/api/rooms/$roomId/start") {
            contentType(ContentType.Application.Json)
            setBody(StartRequest(nickname, playerId ?: ""))
            // 地图生成可能较久 (服务器端), 单独放宽超时
            timeout { requestTimeoutMillis = 180_000 }
        })

    /** 跳海/重新开始: 删旧存档, 房间重置为等待 (全员自动准备); randomizeSeed=true (跳海) → 随机新图 */
    suspend fun restartRoom(roomId: String, nickname: String, playerId: String? = null, randomizeSeed: Boolean = false): ApiResult =
        parse(client.post("$SERVER_URL/api/rooms/$roomId/restart") {
            contentType(ContentType.Application.Json)
            setBody(RestartRequest(nickname, playerId ?: "", randomizeSeed))
            timeout { requestTimeoutMillis = 60_000 }
        })

    /** 观战: 加入进行中的房间, 返回 gameId (不进成员列表) */
    suspend fun spectateRoom(roomId: String, nickname: String, playerId: String? = null): ApiResult =
        parse(client.post("$SERVER_URL/api/rooms/$roomId/spectate") {
            contentType(ContentType.Application.Json)
            setBody(JoinRequest(nickname, playerId ?: "", ""))
        })

    suspend fun updateSettings(roomId: String, nickname: String, playerId: String? = null, settings: Map<String, JsonElement>): ApiResult =
        parse(client.post("$SERVER_URL/api/rooms/$roomId/settings") {
            contentType(ContentType.Application.Json)
            setBody(SettingsRequest(nickname, playerId ?: "", settings))
        })

    /** 模组镜像清单 */
    suspend fun modMirrorManifest(): List<ModMirrorEntry> =
        parse(client.get("$SERVER_URL/api/mods"))

    @Serializable
    data class PendingModStatus(val status: String = "pending", val reason: String = "", val uploadedAt: Long = 0)

    /** 已上传模组的审核状态 (modName -> status: pending/approved/rejected + reason) */
    suspend fun pendingModStatus(): Map<String, PendingModStatus> {
        val resp = client.get("$SERVER_URL/api/mods/pending")
        if (!resp.status.isSuccess()) return emptyMap()
        val body = resp.body<JsonObject>()
        val mods = body["mods"]?.jsonObject ?: return emptyMap()
        return mods.mapValues { (_, v) ->
            val o = v.jsonObject
            PendingModStatus(
                status = o["status"]?.jsonPrimitive?.contentOrNull ?: "pending",
                reason = o["reason"]?.jsonPrimitive?.contentOrNull ?: "",
                uploadedAt = o["uploadedAt"]?.jsonPrimitive?.longOrNull ?: 0,
            )
        }
    }

    /** 上传模组 zip 到镜像 (流式, 带进度回调) → 返回服务器响应 JSON 文本; 失败抛异常 */
    fun uploadModZip(modName: String, zipPath: String, onProgress: (Long, Long) -> Unit): String =
        uploadModZipWithToken(modName, "", zipPath, onProgress)

    /** 上传模组 zip (带上传令牌: 首次设置, 同名更新需相同令牌) */
    fun uploadModZipWithToken(modName: String, token: String, zipPath: String, onProgress: (Long, Long) -> Unit): String {
        val file = java.io.File(zipPath)
        val total = file.length()
        val conn = java.net.URL("$SERVER_URL/api/mods/upload").openConnection() as java.net.HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 300000
            conn.setRequestProperty("X-Lobby-Token", LOBBY_TOKEN)
            conn.setRequestProperty("X-Mod-Name", java.net.URLEncoder.encode(modName, "UTF-8"))
            if (token.isNotEmpty()) conn.setRequestProperty("X-Mod-Token", token)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("Content-Length", total.toString())
            conn.doOutput = true
            conn.outputStream.use { out ->
                file.inputStream().use { ins ->
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        onProgress(sent, total)
                    }
                }
            }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) {
                val err = try {
                    kotlinx.serialization.json.Json.parseToJsonElement(resp).jsonObject["error"]
                        ?.jsonPrimitive?.contentOrNull
                } catch (e: Exception) {
                    null
                }
                throw RuntimeException(err ?: "HTTP $code")
            }
            return resp
        } finally {
            conn.disconnect()
        }
    }

    /** 上报自己缺失的模组 (服务器开始游戏前的统一性检查) */
    suspend fun reportMods(roomId: String, nickname: String, playerId: String? = null, missingMods: List<String>): ApiResult =
        parse(client.post("$SERVER_URL/api/rooms/$roomId/mods") {
            contentType(ContentType.Application.Json)
            setBody(ModsRequest(nickname, playerId ?: "", missingMods))
        })

    /** UncivGC 房间聊天: 发送消息, 返回最新 seq */
    suspend fun sendChat(roomId: String, nickname: String, playerId: String, text: String, to: String = "world"): ApiResult =
        parse(client.post("$SERVER_URL/api/rooms/$roomId/chat") {
            contentType(ContentType.Application.Json)
            setBody(ChatRequest(nickname, playerId, text, to))
        })

    /** 应用更新检查: 服务器 version.json; 失败返回 null (静默跳过) */
    suspend fun checkUpdate(): UpdateInfo? = try {
        parse<UpdateInfo>(client.get("$SERVER_URL/api/version"))
    } catch (e: Exception) {
        null
    }

    /** 下载最新 APK → 本地临时文件路径 (流式+进度); 失败 null.
     *  进度回调传 (received: Long, total: Long) 字节数.
     *  用 java.net.HttpURLConnection — 与 curl 同级别的系统网络栈, 慢速/抖动网络稳定
     *  (Ktor CIO 在到服务器的慢速传输中反复断连/进度卡 0) */
    suspend fun downloadApk(onProgress: (received: Long, total: Long) -> Unit): String? {
        var conn: java.net.HttpURLConnection? = null
        var out: java.io.FileOutputStream? = null
        return try {
            conn = java.net.URL("$SERVER_URL/api/download/apk").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 300_000  // 5 分钟无数据才超时 (慢速下载持续有数据不受影响)
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return null
            val total = conn.contentLength.toLong()
            val temp = com.badlogic.gdx.Gdx.files.local("update-uncivgc.apk")
            out = java.io.FileOutputStream(temp.file())
            val input = conn.inputStream
            val buf = ByteArray(64 * 1024)
            var received = 0L
            var lastReport = 0L
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                out.write(buf, 0, read)
                received += read
                if (received - lastReport >= 512 * 1024) {
                    lastReport = received
                    onProgress(received, total)
                }
            }
            onProgress(received, total)  // 最终完整回报
            if (total > 0 && received != total) null else temp.path()
        } catch (e: Exception) {
            // 下载中断 (网络/服务器断开) → 返回 null, 调用方提示重试, 不崩溃
            try {
                com.badlogic.gdx.Gdx.files.local("update-uncivgc.apk").delete()
            } catch (ignored: Exception) {
            }
            null
        } finally {
            try {
                out?.close()
            } catch (ignored: Exception) {
            }
            try {
                conn?.disconnect()
            } catch (ignored: Exception) {
            }
        }
    }

    /** 从服务器镜像下载模组 zip (流式, 带进度) → 本地临时文件路径; 失败返回 null.
     *  大模组下载单独放宽超时 (全局 35s 对几十 MB 的 zip 不够);
     *  模组名必须 URL 编码 (含空格的模组名, 如 "UCCC Mod", 裸空格会被服务器当坏请求拒掉) */
    suspend fun downloadModFromMirror(modName: String, onProgress: (Int) -> Unit): String? {
        // java.net.HttpURLConnection — 与 curl 同级, 慢速网络稳定 (Ktor CIO 在到服务器的慢速传输中反复断连)
        var conn: java.net.HttpURLConnection? = null
        var out: java.io.FileOutputStream? = null
        return try {
            conn = java.net.URL("$SERVER_URL/api/mods/${modName.encodeURLPathPart()}")
                .openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 300_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return null
            val total = conn.contentLength.toLong()
            val temp = com.badlogic.gdx.Gdx.files.local("temp-mod-$modName.zip")
            out = java.io.FileOutputStream(temp.file())
            val input = conn.inputStream
            val buf = ByteArray(64 * 1024)
            var received = 0L
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                out.write(buf, 0, read)
                received += read
                if (total > 0) onProgress(((received * 100) / total).toInt().coerceIn(0, 99))
            }
            if (total > 0 && received != total) null else temp.path()
        } catch (e: Exception) {
            try {
                com.badlogic.gdx.Gdx.files.local("temp-mod-$modName.zip").delete()
            } catch (ignored: Exception) {
            }
            null
        } finally {
            try {
                out?.close()
            } catch (ignored: Exception) {
            }
            try {
                conn?.disconnect()
            } catch (ignored: Exception) {
            }
        }
    }
}

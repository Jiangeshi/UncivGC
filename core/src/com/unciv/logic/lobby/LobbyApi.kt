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

@Serializable
data class ModMirrorEntry(val name: String = "", val size: Long = 0, val updatedAt: Double = 0.0, val md5: String = "", val version: String = "")

@Serializable
data class UpdateInfo(
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
)

@Serializable
data class LobbyRoom(
    val id: String,
    val name: String,
    val status: String = "waiting",   // waiting | starting | playing
    val gameId: String? = null,
    val version: Int = 1,
    val playerCount: Int = 0,
    val owner: String? = null,
    val settings: Map<String, JsonElement> = emptyMap(),
    val members: List<LobbyMember> = emptyList(),
    // 列表摘要字段 (列表接口不返回完整 settings/members, 见 lobby_server.room_summary)
    val memberIds: List<String> = emptyList(),
    val baseRuleset: String? = null,
    val mods: List<String> = emptyList(),
)

@Serializable
data class ApiResult(val ok: Boolean = false, val msg: String = "", val gameId: String? = null, val room: LobbyRoom? = null)

@Serializable
data class ApiError(val error: String? = null)

@Serializable
data class CreateRoomRequest(val name: String, val nickname: String, val playerId: String, val civ: String)
@Serializable
data class JoinRequest(val nickname: String, val playerId: String, val civ: String)
@Serializable
data class ReadyRequest(val nickname: String, val playerId: String, val ready: Boolean)
@Serializable
data class CivRequest(val nickname: String, val playerId: String, val civ: String)
@Serializable
data class LeaveRequest(val nickname: String, val playerId: String)
@Serializable
data class KickRequest(val nickname: String, val playerId: String, val target: String = "", val targetPlayerId: String = "")
@Serializable
data class StartRequest(val nickname: String, val playerId: String)
@Serializable
data class RestartRequest(val nickname: String, val playerId: String, val randomizeSeed: Boolean = false)
@Serializable
data class ModsRequest(val nickname: String, val playerId: String, val missingMods: List<String> = emptyList())
@Serializable
data class SettingsRequest(val nickname: String, val playerId: String, val settings: Map<String, JsonElement>)

/** 联机大厅 API 客户端 (M2 服务器: unciv-lobby/lobby_server.py) */
object LobbyApi {
    // TODO: 上架前改为可配置 (设置页)
    const val SERVER_URL = "http://110.40.151.9:8123"

    /** 房间接口鉴权 token (与服务器 LOBBY_TOKEN 一致; 防止陌生客户端建房/进房) */
    const val LOBBY_TOKEN = "fe645aeabf2862a9d70405643a849bee"

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
            val err = try { response.body<ApiError>().error } catch (e: Exception) { null }
            throw RuntimeException(err ?: "HTTP ${response.status.value}")
        }
        return response.body()
    }

    suspend fun listRooms(): List<LobbyRoom> =
        parse(client.get("$SERVER_URL/api/rooms"))

    suspend fun createRoom(name: String, nickname: String, playerId: String, civ: String?): LobbyRoom =
        parse(client.post("$SERVER_URL/api/rooms") {
            contentType(ContentType.Application.Json)
            setBody(CreateRoomRequest(name, nickname, playerId, civ ?: ""))
        })

    suspend fun getRoom(roomId: String): LobbyRoom =
        parse(client.get("$SERVER_URL/api/rooms/$roomId"))

    /** 长轮询: 房间变化立即返回; 25 秒无变化返回 null */
    suspend fun waitRoom(roomId: String, since: Int): LobbyRoom? {
        val response = client.get("$SERVER_URL/api/rooms/$roomId/wait?since=$since")
        if (response.status.value == 204) return null
        return parse(response)
    }

    suspend fun joinRoom(roomId: String, nickname: String, playerId: String, civ: String?): ApiResult =
        parse(client.post("$SERVER_URL/api/rooms/$roomId/join") {
            contentType(ContentType.Application.Json)
            setBody(JoinRequest(nickname, playerId, civ ?: ""))
        })

    suspend fun leaveRoom(roomId: String, nickname: String, playerId: String? = null): ApiResult =
        parse(client.post("$SERVER_URL/api/rooms/$roomId/leave") {
            contentType(ContentType.Application.Json)
            setBody(LeaveRequest(nickname, playerId ?: ""))
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

    /** 上报自己缺失的模组 (服务器开始游戏前的统一性检查) */
    suspend fun reportMods(roomId: String, nickname: String, playerId: String? = null, missingMods: List<String>): ApiResult =
        parse(client.post("$SERVER_URL/api/rooms/$roomId/mods") {
            contentType(ContentType.Application.Json)
            setBody(ModsRequest(nickname, playerId ?: "", missingMods))
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

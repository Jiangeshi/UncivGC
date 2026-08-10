package com.unciv.ui.screens.worldscreen

import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.worldscreen.unit.AutoPlay
import com.unciv.utils.Concurrency
import java.util.ArrayDeque

/**
 * UncivGC 撤回系统: 快照栈 (后台每 3 秒存一次状态, 状态没变不重复存).
 * 点撤回 → 回退到上一个快照; 可连续回退 (栈最多 15 份).
 * 只在自己回合内工作 — 多人局的操作只有结束回合才上传, 本地回退不会造成状态不一致.
 * 栈是静态的 (跨 WorldScreen 重建保留, 支持连续多级撤回); 按 gameId 隔离, 过回合/换局清空.
 */
class UndoManager(private val worldScreen: WorldScreen) {
    private var running = false

    val hasSnapshot: Boolean
        get() = synchronized(snapshots) { snapshots.isNotEmpty() && snapshotsGameId == currentGameId() }

    fun start() {
        if (running) return
        running = true
        Concurrency.run("UndoSnapshotLoop") {
            while (running) {
                try {
                    tick()
                } catch (_: Exception) {
                    // 快照失败直接跳过 (可能正赶上状态变更, 下一轮再试)
                }
                Thread.sleep(TICK_MS)
            }
        }
    }

    fun stop() {
        running = false
    }

    /** 过回合 / 换局时清空快照栈 */
    fun clear() {
        synchronized(snapshots) {
            snapshots.clear()
        }
        refreshButton()
    }

    private fun currentGameId(): String = UncivGame.Current.gameInfo?.gameId ?: ""

    private fun tick() {
        val gameInfo = UncivGame.Current.gameInfo ?: return
        val ws = worldScreen
        if (!ws.isPlayersTurn) return
        if (ws.isNextTurnUpdateRunning() || ws.waitingForAutosave) return
        if (ws.autoPlay.isAutoPlaying()) return

        synchronized(snapshots) {
            val gid = gameInfo.gameId
            if (snapshotsGameId != gid) {
                snapshots.clear()
                snapshotsGameId = gid
            }
            val serialized = UncivFiles.gameInfoToString(gameInfo, updateChecksum = true)
            if (snapshots.peekLast() == serialized) return
            snapshots.addLast(serialized)
            while (snapshots.size > MAX_SNAPSHOTS) snapshots.removeFirst()
            com.unciv.utils.Log.debug("Undo snapshot #%s (game %s)", snapshots.size, gid)
        }
        refreshButton()
    }

    private fun refreshButton() {
        Concurrency.runOnGLThread { worldScreen.refreshUndoButton() }
    }

    /** 撤回一级: 跳过与当前状态相同的快照 (3秒节奏下栈顶常是"操作后"状态),
     *  恢复到真正不同的上一个状态; 快照损坏则丢弃继续回退; 成功后载入 (带加载过渡, 后台执行) */
    fun undo() {
        val current = try {
            UncivFiles.gameInfoToString(UncivGame.Current.gameInfo ?: return, updateChecksum = true)
        } catch (e: Exception) {
            return
        }
        var snapshot: String? = null
        synchronized(snapshots) {
            while (snapshots.isNotEmpty()) {
                val s = snapshots.pollLast()!!
                if (s != current) {
                    snapshot = s
                    break
                }
            }
        }
        refreshButton()
        val target = snapshot ?: return
        val decoded = try {
            UncivFiles.gameInfoFromString(target)
        } catch (e: Exception) {
            undo()  // 快照损坏 → 跳过, 继续回退更早的
            return
        }
        Concurrency.run("UndoRestore") {
            try {
                UncivGame.Current.loadGame(decoded, AutoPlay(UncivGame.Current.settings.autoPlay))
            } catch (e: Exception) {
                Concurrency.runOnGLThread {
                    ToastPopup("撤回失败: ${e.message}", worldScreen)
                }
            }
        }
    }

    companion object {
        private const val MAX_SNAPSHOTS = 15
        private const val TICK_MS = 2000L
        // 静态栈: 撤回恢复会重建 WorldScreen, 静态保存才能支持连续多级撤回
        private val snapshots = ArrayDeque<String>()
        private var snapshotsGameId = ""
    }
}

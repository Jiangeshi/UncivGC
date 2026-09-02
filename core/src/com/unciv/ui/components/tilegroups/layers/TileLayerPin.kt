package com.unciv.ui.components.tilegroups.layers

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.utils.Align
import com.unciv.view.CivView
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.tilegroups.TileGroup
import com.unciv.ui.components.extensions.toLabel

/**
 * UncivGC 2026-09-02: 地图钉层 — 纯展示, 置顶, 不拦截点击不影响逻辑.
 * 数据源: tile.tileMap.mapPins (key="x,y"), 随地图文件保存, 谁打开地图都看得到.
 */
class TileLayerPin(tileGroup: TileGroup, size: Float) : TileLayer(tileGroup, size) {

    private var pinLabel: Label? = null

    override fun doUpdate(viewingCiv: CivView?) {
        // 地图编辑器外 (游戏对局) tileMap 已初始化但无 pin; 未初始化时跳过
        val tileMap = try { tile.tileMap } catch (_: Exception) { return }
        val pin = tileMap.mapPins["${tile.position.x},${tile.position.y}"]
        if (pin == null) {
            if (pinLabel != null) {
                removeOwnedActor(pinLabel!!)
                pinLabel = null
            }
            return
        }
        // 文本/字号/颜色任一变化都要重建 (只比较文本 → 改字号/颜色不刷新, 用户反馈 2026-09-02)
        val fontColor = if (pin.color == "Black") Color.BLACK else Color.WHITE
        val fontSize = (size * 0.5f * pin.fontSize).toInt().coerceAtLeast(4)
        if (pinLabel != null
            && pinLabel!!.text.toString() == pin.text
            && pinLabel!!.style.fontColor == fontColor
            && pinLabel!!.fontScaleX == fontSize / Fonts.ORIGINAL_FONT_SIZE) return

        val label = pin.text.toLabel(
            fontColor = fontColor,
            fontSize = fontSize,
            alignment = Align.center,
        ).apply {
            touchable = Touchable.disabled
            setOrigin(Align.center)
            // 居中于格子; tileX/tileY 是格子原点 (attachTo 后为绝对坐标)
            x = tileX + (tileGroup.width - width) / 2
            y = tileY + (tileGroup.height - height) / 2
            if (pinLabel != null) removeOwnedActor(pinLabel!!)
            pinLabel = this
            addOwnedActor(this)
        }
    }

    override fun determineVisibility() {
        isVisible = pinLabel != null
    }
}

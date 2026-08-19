package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 * 列表项名称 label：ellipsis 截断 + **prefWidth 上限**。
 * libGDX Label 的 ellipsis 只影响渲染，getPrefWidth() 仍按全文返回，
 * 会撑大父 Table 的 prefWidth 导致左栏溢出；本类覆写 getPrefWidth 限制宽度。
 */
class ListNameLabel(text: String, maxPrefWidth: Float, style: LabelStyle) : Label(text, style) {
    private val maxPref = maxPrefWidth
    init {
        setEllipsis(true)
    }
    override fun getPrefWidth(): Float = maxPref
}

/** 创建列表项名称 label（带 prefWidth 上限，长名省略号截断，不撑爆父容器）
 *  有翻译时显示「英文（翻译）」，无翻译只显示英文 */
fun listNameLabel(
    text: String,
    maxWidth: Float,
    fontSize: Int = 20,
    fontColor: Color = Color(1f, 1f, 1f, 0.85f)
): ListNameLabel {
    val display = bilingualUniqueText(text, text)
    val base = Label(display, BaseScreen.skin)
    base.setFontScale(fontSize / com.unciv.ui.components.fonts.Fonts.ORIGINAL_FONT_SIZE)
    base.color = fontColor
    val label = ListNameLabel(base.text.toString(), maxWidth, base.style)
    label.setFontScale(fontSize / com.unciv.ui.components.fonts.Fonts.ORIGINAL_FONT_SIZE)
    label.color = fontColor
    return label
}

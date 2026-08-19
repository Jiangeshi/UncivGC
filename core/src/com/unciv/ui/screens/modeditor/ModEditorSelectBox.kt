package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.setItems
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 * 双语下拉框：选项显示「中文翻译（英语原文）」，仅当翻译与原文不同时附加原文。
 * 选择与保存始终使用英语原文值（value），保证写入模组的数据不受语言影响。
 *
 * searchable=true 时：下拉列表顶部带搜索输入框，输入即时过滤（英文/中文都行）。
 * 浮层列表（stage 级）不改变表单布局，外观沿用 SelectBox 下拉样式。
 */
open class ModEditorSelectBox(
    values: Collection<String>,
    default: String,
    private val searchable: Boolean = false
) : SelectBox<ModEditorSelectBox.DisplayString>(BaseScreen.skin) {

    class DisplayString(val value: String) {
        val display: String
            get() {
                if (value == "(None)") return value.tr() // 哨兵值只显示翻译
                val translated = value.tr()
                return if (translated == value) value else "$translated（$value）"
            }
        override fun toString() = display
        // Equality contract needs to be implemented else setSelected won't work properly
        override fun equals(other: Any?): Boolean = other is DisplayString && value == other.value
        override fun hashCode() = value.hashCode()
    }

    private val originalItems = values.map { DisplayString(it) }
    private val floatingTable = Table(BaseScreen.skin)
    private val listTable = Table(BaseScreen.skin)
    private val listScroll = AutoScrollPane(listTable).apply {
        setOverscroll(false, false)
        fadeScrollBars = false
    }
    private var floating = false
    private var outsideClickListener: com.badlogic.gdx.scenes.scene2d.InputListener? = null

    /** 行高估算：label 14f + 上下 pad 2f×2 + 行间 1f×2 */
    private val rowHeight = 26f
    private val maxListHeight = 150f

    init {
        setItems(originalItems)
        selected = items.firstOrNull { it.value == default } ?: items.first()
        // 框内文字左对齐（SelectBox 默认居中）
        setAlignment(com.badlogic.gdx.utils.Align.left)
        if (searchable) {
            // 浮层：搜索框 + 列表，外观沿用 listStyle 背景（直角矩形）
            floatingTable.background = style.listStyle.background
            val searchField = UncivTextField("Search")
            // 搜索框改为直角矩形背景
            val rectStyle = TextField.TextFieldStyle(searchField.style)
            rectStyle.background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/SelectSearch", null, Color(1f, 1f, 1f, 0.1f))
            searchField.style = rectStyle
            searchField.setTextFieldListener { field, _ ->
                refreshList(field.text)
                // 行数变化后浮层高度需重排（浮层已显示时）
                if (floating) {
                    floatingTable.pack()
                    val h = 32f + listScroll.height.coerceIn(rowHeight, maxListHeight) + 8f
                    floatingTable.setSize(floatingTable.width, h)
                }
            }
            floatingTable.add(searchField).growX().pad(6f, 2f, 6f, 2f).row()
            floatingTable.add(listScroll).growX().pad(1f)
            refreshList("")
        }
    }

    fun setSelected(newValue: String) {
        selected = items.firstOrNull { it == DisplayString(newValue) } ?: return
    }

    // ------------------------------------------------------------------
    // 可搜索模式：重写下拉行为（浮层，不改表单布局）
    // 注意：SelectBox 内部点击监听器直接调 showScrollPane()（不是 showList()），必须重写它
    // ------------------------------------------------------------------

    override fun showScrollPane() {
        if (!searchable) {
            super.showScrollPane()
            return
        }
        if (floating) { hideList(); return }
        floating = true
        val stage = getStage() ?: return
        stage.addActor(floatingTable)

        // 点击浮层外部（或本框外部）时关闭下拉
        if (outsideClickListener == null) {
            outsideClickListener = object : com.badlogic.gdx.scenes.scene2d.InputListener() {
                override fun touchDown(
                    event: com.badlogic.gdx.scenes.scene2d.InputEvent?,
                    x: Float, y: Float, pointer: Int, button: Int
                ): Boolean {
                    val target = event?.target ?: return false
                    val inFloating = target.isDescendantOf(floatingTable)
                    val inBox = target.isDescendantOf(this@ModEditorSelectBox)
                    if (!inFloating && !inBox) {
                        hideList()
                    }
                    return false
                }
            }
            stage.addListener(outsideClickListener)
        }

        val tmp = Vector2(0f, 0f)
        localToStageCoordinates(tmp)
        val x = tmp.x
        val y = tmp.y

        floatingTable.pack()
        val width = (this.width).coerceAtLeast(220f)
        // 列表高度按内容自适应（行数少时收紧，避免大片空白）：搜索框 ~30f + 列表 + padding
        val listHeight = listScroll.height.coerceIn(rowHeight, maxListHeight)
        val height = 32f + listHeight + 8f
        floatingTable.setSize(width, height)
        // 放输入框正下方；空间不足时放上方
        val belowY = y - height - 2f
        val aboveY = y + this.height + 2f
        val finalY = if (belowY >= 4f) belowY else aboveY.coerceAtMost(stage.height - height - 4f)
        floatingTable.setPosition(x.coerceAtMost(stage.width - width - 4f), finalY)
        floatingTable.toFront()
    }

    override fun hideList() {
        if (!searchable) {
            super.hideList()
            return
        }
        if (!floating) return
        floating = false
        floatingTable.remove()
        val listener = outsideClickListener
        if (listener != null) {
            stage?.removeListener(listener)
            outsideClickListener = null
        }
    }

    /** 收尾：编辑器切页/重建表单时移除浮层 */
    fun disposeFloating() {
        if (searchable) hideList()
    }

    private fun refreshList(query: String) {
        listTable.clear()
        val q = query.trim().lowercase()
        val current = selected?.value ?: ""
        val shown = originalItems.filter {
            val v = it.value
            if (v == current) true
            else q.isEmpty() || v.lowercase().contains(q) || v.tr().lowercase().contains(q)
        }
        if (shown.isEmpty()) {
            listTable.add("No results".tr().toLabel(
                fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.4f))).pad(6f).row()
            listScroll.setHeight(rowHeight)
            return
        }
        for (item in shown) {
            val row = Table(BaseScreen.skin)
            // 直角矩形行背景（不用圆角）；选中项高亮，其余半透明；鼠标悬停行高亮（2026-08-19 用户要求）
            val isCurrent = item.value == selected?.value
            val normalTint = if (isCurrent) Color(0.3f, 0.5f, 0.8f, 1f) else Color(1f, 1f, 1f, 0.08f)
            val hoverTint = Color(0.45f, 0.65f, 0.95f, 0.9f)
            fun setRowBg(tint: Color) {
                row.background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/ComboRow", null, tint)
            }
            setRowBg(normalTint)
            row.addListener(object : InputListener() {
                override fun enter(event: InputEvent?, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                    setRowBg(hoverTint)
                }
                override fun exit(event: InputEvent?, x: Float, y: Float, pointer: Int, toActor: Actor?) {
                    setRowBg(normalTint)
                }
            })
            val label = item.display.toLabel(fontSize = 14)
            label.setEllipsis(true)
            row.add(label).growX().minWidth(160f).left().pad(2f, 6f, 2f, 6f)
            row.touchable = Touchable.enabled
            row.onActivation {
                setSelected(item.value)
                hideList()
                fire(ChangeListener.ChangeEvent())
            }
            listTable.add(row).growX().pad(0f, 1f, 0f, 1f).row()
        }
        // 列表高度按内容自适应：行少时收紧，避免大片空白；行多时才滚动
        val targetHeight = minOf(maxListHeight, shown.size * rowHeight + 2f)
        listScroll.setHeight(targetHeight)
        listScroll.layout()  // 强制布局更新，确保 ScrollPane 视口高度生效
    }
}

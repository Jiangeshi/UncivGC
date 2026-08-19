package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 * 可搜索下拉框（combobox）：
 * - editable=false（默认）：文本框只读，点文本框或 ▼ 弹出「搜索框 + 过滤列表」，输入即过滤，点选回填。
 *   用于选项很多的 list: 参数（单位/建筑/地形/城市范围等），不用在一大堆里翻。
 * - editable=true：文本框可自由输入（写算式/自定义值），▼ 弹出可搜索选项列表。用于 countable 等。
 * 输入框宽度会按文本内容自动加宽（fitFieldToText），长参数值（如完整的 cityFilter 选项）也能完整显示；
 * 上限与行内编辑器 flow 的 maxWidth 一致，避免溢出屏幕。
 */
class ModEditorComboBox(
    private val screen: BaseScreen,
    private val values: List<String>,
    initial: String,
    private val fieldWidth: Float = 340f,
    private val editable: Boolean = false,
    private val onChanged: (String) -> Unit,
    private val onPick: ((String) -> Unit)? = null   // 仅列表选中时回调（输入不触发），countable 参数替换用
) : Table(BaseScreen.skin) {

    private val field = UncivTextField("", initial)
    private val fieldCell = add(field).width(fieldWidth)
    private var popup: Popup? = null
    private var popupSearchField: UncivTextField? = null
    private var suppressDropdown = false   // 程序改值（选中/参数替换）时不触发输入即过滤

    init {
        if (editable) {
            field.setTextFieldListener { f, _ ->
                onChanged(f.text)
                fitFieldToText()
                fire(ChangeListener.ChangeEvent())
                // A：输入即过滤（2026-08-19 用户要求）——输入时自动打开/刷新下拉联想
                if (!suppressDropdown) ensureDropdown()
            }
        } else {
            field.setDisabled(true)
            field.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    toggleDropdown(values)
                }
            })
        }
        val dropdownButton = "▼".toTextButton()
        dropdownButton.onActivation { toggleDropdown(values) }
        add(dropdownButton).padLeft(2f)
        fitFieldToText()
    }

    fun getText(): String = field.text

    /** 程序设值（参数替换弹窗回填等）：同步字段 + 通知监听，但不触发输入即过滤 */
    fun setText(text: String) {
        suppressDropdown = true
        field.setText(text)
        suppressDropdown = false
        fitFieldToText()
        onChanged(text)
        fire(ChangeListener.ChangeEvent())
    }

    /** 输入即过滤：下拉未开则打开，已开则同步搜索框 */
    private fun ensureDropdown() {
        if (popup?.isVisible != true) toggleDropdown(values)
        popupSearchField?.setText(field.text)
        screen.stage.keyboardFocus = field
    }

    /** 按当前文本自动调整输入框宽度：长值加宽到完整可见，短值恢复默认宽度。
     *  用 prefWidth+minWidth 而非 width：flow 中整体超宽时元素可被压缩（TextField 内部滚动），绝不溢出。 */
    private fun fitFieldToText() {
        val text = field.text
        val desired: Float
        if (text.isEmpty()) {
            desired = fieldWidth
        } else {
            val measure = Label(text, BaseScreen.skin).apply { pack() }
            val needed = measure.prefWidth + 30f
            // 上限比 flow 换行阈值小 140f：条件 chip 由模板+combo+× 组成，否则必然超宽独立成行撑爆
            val cap = maxOf(formAvailableWidth(screen.stage.width, extraDeduction = 100f) - 140f, 160f)
            desired = minOf(maxOf(needed, fieldWidth), cap)
        }
        fieldCell.prefWidth(desired).minWidth(minOf(desired, 100f))
        invalidateHierarchy()
    }

    private fun toggleDropdown(values: List<String>) {
        if (popup?.isVisible == true) {
            popup?.close()
            popup = null
            return
        }
        val newPopup = Popup(screen, scrollable = Popup.Scrollability.None)
        val searchField = UncivTextField("")
        popupSearchField = searchField
        newPopup.add(searchField).growX().width(400f).pad(6f).row()
        val listTable = Table(BaseScreen.skin)
        val listScroll = AutoScrollPane(listTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        newPopup.add(listScroll).width(420f).height(360f).pad(6f).row()

        fun refresh(query: String) {
            listTable.clear()
            val q = query.trim().lowercase()
            // 只显示默认候选（用户 2026-08-19 要求：不要插当前值，避免 "1 Per Turn" 这类手打值混进列表）
            val items = values.distinct()
            var shown = 0
            for (item in items.distinct()) {
                if (q.isNotEmpty() && !item.lowercase().contains(q)) continue
                val row = Table(BaseScreen.skin)
                // 鼠标悬停行高亮（2026-08-19 用户要求）
                fun setRowBg(tint: Color) {
                    row.background = BaseScreen.skinStrings.getUiBackground(
                        "ModEditor/ComboRow", BaseScreen.skinStrings.roundedEdgeRectangleShape, tint)
                }
                setRowBg(BaseScreen.skinStrings.skinConfig.baseColor)
                row.addListener(object : InputListener() {
                    override fun enter(event: InputEvent?, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                        setRowBg(Color(0.45f, 0.65f, 0.95f, 0.9f))
                    }
                    override fun exit(event: InputEvent?, x: Float, y: Float, pointer: Int, toActor: Actor?) {
                        setRowBg(BaseScreen.skinStrings.skinConfig.baseColor)
                    }
                })
                // 双语：英文（翻译），翻译不存在时只显示英文
                val translated = item.tr()
                val labelText = if (translated != item) "$item（$translated）" else item
                val label = labelText.toLabel(fontSize = 15)
                label.wrap = true
                row.add(label).growX().minWidth(360f).left().pad(6f)
                row.touchable = Touchable.enabled
                row.onActivation {
                    suppressDropdown = true
                    field.setText(item)
                    suppressDropdown = false
                    onChanged(item)
                    onPick?.invoke(item)   // 仅列表选中触发（countable 模板参数替换/历史记录用）
                    fitFieldToText()
                    fire(ChangeListener.ChangeEvent())
                    newPopup.close()
                    popup = null
                    popupSearchField = null
                }
                listTable.add(row).growX().pad(2f, 4f, 2f, 4f).row()
                shown++
            }
            if (shown == 0) {
                listTable.add("No results".tr().toLabel(
                    fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.4f))).pad(10f).row()
            }
        }

        searchField.setTextFieldListener { f, _ -> refresh(f.text) }
        refresh("")
        newPopup.addCloseButton()
        popup = newPopup
        // ⚠️ force = true：countable 参数弹窗（外层 Popup）内打开下拉必须强制显示，
        // 否则 Popup.open() 默认排队（hasOpenPopups 检查），内层下拉永远不显示（2026-08-19 用户报"弹窗下拉不了"）
        newPopup.open(force = true)
    }
}

package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import kotlin.math.max
import kotlin.math.min

/**
 * 列表型字段编辑器（chips 添加）：标签 + 已选项 chips（可删除）+ "+ Add" 按钮。
 * 点击 "+ Add" 弹出搜索框 + 选项列表，选择后加入。
 *
 * 用法（如 Terrains 的 occursOn、ModOptions 的 xxxToRemove）：
 * ```
 * ModEditorListSection(
 *     screen = this,
 *     label = "occursOn",
 *     options = { ModEditorData.getTerrains(modFolder) },
 *     getValues = { item.getStringList("occursOn") },
 *     setValues = { item.setStringList("occursOn", it) }
 * ).addTo(formTable)
 * ```
 */
class ModEditorListSection(
    private val screen: BaseScreen,
    private val label: String,
    private val options: () -> List<String>,
    private val getValues: () -> List<String>,
    private val setValues: (List<String>) -> Unit
) {
    fun addTo(table: Table) {
        val row = Table(BaseScreen.skin)
        row.add(label.tr().toLabel(fontSize = 16, fontColor = Color(1f, 1f, 1f, 0.75f)))
            .left().pad(4f).width(240f).top()
        val chipsTable = Table(BaseScreen.skin)

        fun refreshChips() {
            chipsTable.clear()
            // 芯片按可用宽度换行排列（多了不会溢出）：可用宽 = 表单实际宽 - 标签列 240 - 边距/按钮
            val maxWidth = formAvailableWidth(screen.stage.width, extraDeduction = 240f)
            var currentRow = Table(BaseScreen.skin)
            var rowWidth = 0f
            for (value in getValues()) {
                val chip = Table(BaseScreen.skin)
                chip.background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/ConditionChip", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                    Color(0.15f, 0.4f, 0.7f, 0.8f))
                val chipLabel = value.toLabel(fontSize = 14)
                val chipLabelWidth = minOf(chipLabel.prefWidth, 280f)
                chipLabel.wrap = true
                chip.add(chipLabel).width(chipLabelWidth).left().pad(4f, 8f, 4f, 2f)
                val removeButton = "×".toTextButton()
                removeButton.onActivation {
                    val list = getValues().toMutableList()
                    list.remove(value)
                    setValues(list)
                    refreshChips()
                }
                chip.add(removeButton).pad(2f)
                val chipWidth = chip.prefWidth + 6f
                if (currentRow.children.size > 0 && rowWidth + chipWidth > maxWidth) {
                    chipsTable.add(currentRow).growX().left().row()
                    currentRow = Table(BaseScreen.skin)
                    rowWidth = 0f
                }
                currentRow.add(chip).left().pad(2f)
                rowWidth += chipWidth
            }
            if (currentRow.children.size > 0) chipsTable.add(currentRow).growX().left().row()
        }
        refreshChips()

        val addButton = "+ Add".toTextButton()
        addButton.onActivation { showAddToListPopup(::refreshChips) }

        val right = Table(BaseScreen.skin)
        right.add(chipsTable).growX().left().row()
        right.add(addButton).left().padTop(2f)
        row.add(right).growX().left().pad(4f)
        table.add(row).growX().left().pad(3f, 10f, 3f, 10f).row()
    }

    private fun showAddToListPopup(onChanged: () -> Unit) {
        val popup = Popup(screen, scrollable = Popup.Scrollability.None)
        popup.add((label.tr() + " · " + "Add".tr()).toLabel(fontSize = 20)).pad(8f).row()
        val searchField = UncivTextField("Search")
        popup.add(searchField).growX().width(520f).pad(6f).row()
        val listTable = Table(BaseScreen.skin)
        val listScroll = AutoScrollPane(listTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        popup.add(listScroll).grow().width(520f).height(360f).pad(6f).row()

        val current = getValues().toSet()

        fun refresh(query: String) {
            listTable.clear()
            var shown = 0
            for (item in options()) {
                if (item in current) continue
                if (query.isNotBlank() && !item.lowercase().contains(query.trim().lowercase())) continue
                val row = Table(BaseScreen.skin)
                row.background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/SearchRow", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                    BaseScreen.skinStrings.skinConfig.baseColor)
                val label = item.tr().toLabel(fontSize = 15)
                row.add(label).growX().left().pad(6f, 8f, 6f, 8f)
                row.touchable = Touchable.enabled
                row.onActivation {
                    val list = getValues().toMutableList()
                    list.add(item)
                    setValues(list)
                    popup.close()
                    onChanged()
                }
                listTable.add(row).growX().pad(2f, 4f, 2f, 4f).row()
                shown++

            }
            if (shown == 0) {
                listTable.add("No results".tr().toLabel(
                    fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.4f))).pad(10f).row()
            }
        }

        searchField.setTextFieldListener { field, _ -> refresh(field.text) }
        refresh("")
        popup.addCloseButton()
        popup.open()
    }
}

package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextArea
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 * civilopediaText 富文本编辑器（2026-08-19 用户要求"全部改"）。
 *
 * 取代各编辑器 v1 简化版（只读 text 字段的 TextArea），支持完整字段：
 * text / link / icon / extraImage / imageSize / header / size / indent / padding /
 * color / separator / starred / centered / iconCrossed。
 *
 * 用法：
 * ```
 * civilopediaEditor = CivilopediaTextEditor(screen, getRaw = { item.raw["civilopediaText"] },
 *     setRaw = { item.raw["civilopediaText"] = it })
 * civilopediaEditor.addTo(formTable, "Civilopedia text")
 * ```
 * 保存时调用 [buildEntries] 得到重建后的 List<Map<String, Any?>>。
 */
class CivilopediaTextEditor(
    private val screen: BaseScreen,
    private val getRaw: () -> Any?,
    private val setRaw: (List<Map<String, Any?>>?) -> Unit
) {
    /** 单条目数据：raw 保持原始字段顺序与值 */
    private class EntryData {
        val raw = LinkedHashMap<String, Any?>()
        fun getString(key: String): String = raw[key]?.toString() ?: ""
        fun getBool(key: String): Boolean = raw[key] == true
        fun getInt(key: String): Int = (raw[key] as? Number)?.toInt() ?: 0
        fun getFloat(key: String): Float = (raw[key] as? Number)?.toFloat() ?: 0f
    }

    private val entries = mutableListOf<EntryData>()
    private var container = Table(BaseScreen.skin)
    private var summaryLabel = "".toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.4f))

    init {
        val raw = getRaw()
        val rawList = raw as? List<*>
        if (rawList != null) {
            for (item in rawList) {
                if (item !is Map<*, *>) continue
                val entry = EntryData()
                for ((k, v) in item) entry.raw[k.toString()] = v
                entries.add(entry)
            }
        }
    }

    fun addTo(table: Table, labelKey: String) {
        // 大标题（与各模块 sectionHeader 一致），文字居中
        val header = Table(BaseScreen.skin)
        header.add(labelKey.tr().toLabel(fontSize = 20, fontColor = Color(0.55f, 0.85f, 1f, 1f)))
            .center().padTop(12f).padBottom(2f).padLeft(2f)
        header.row()
        header.add(separatorLine()).fillX().height(2f)
        table.add(header).fillX().row()
        // 大总框：背景 + 滚动条目列表
        val box = Table(BaseScreen.skin)
        box.background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/CivilopediaBox", BaseScreen.skinStrings.roundedEdgeRectangleShape,
            Color(1f, 1f, 1f, 0.06f))
        container = Table(BaseScreen.skin)
        val scroll = AutoScrollPane(container).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        box.add(scroll).grow().pad(8f)
        table.add(box).growX().height(280f).left().pad(6f).row()
        refresh()

        val buttonRow = Table(BaseScreen.skin)
        val addTextButton = "+ Add text".toTextButton()
        addTextButton.onActivation { addEntry(EntryData().apply { raw["text"] = "" }); refresh() }
        buttonRow.add(addTextButton).left().pad(4f)
        val addSeparatorButton = "+ Add separator".toTextButton()
        addSeparatorButton.onActivation { addEntry(EntryData().apply { raw["separator"] = true }); refresh() }
        buttonRow.add(addSeparatorButton).left().pad(4f)
        val addImageButton = "+ Add image".toTextButton()
        addImageButton.onActivation { addEntry(EntryData().apply { raw["extraImage"] = "" }); refresh() }
        buttonRow.add(addImageButton).left().pad(4f)
        table.add(buttonRow).growX().left().row()
        summaryLabel = ("Rich text: text, links, headers, colors, separators, images".tr())
            .toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.4f))
        summaryLabel.wrap = true
        table.add(summaryLabel).growX().left().pad(0f, 8f, 6f, 8f).row()
    }

    private fun sectionHeader(text: String): Table {
        val header = Table(BaseScreen.skin)
        header.add(text.toLabel(fontSize = 20, fontColor = Color(0.55f, 0.85f, 1f, 1f)))
            .left().padTop(12f).padBottom(2f).padLeft(2f)
        header.row()
        header.add(separatorLine()).fillX().height(2f)
        return header
    }

    private fun separatorLine(): Table = Table(BaseScreen.skin).apply {
        background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/Separator", null, Color(1f, 1f, 1f, 0.18f))
    }

    private fun refresh() {
        container.clear()
        if (entries.isEmpty()) {
            container.add("(no entries)".toLabel(fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.4f)))
                .left().pad(4f).row()
            return
        }
        for ((index, entry) in entries.withIndex()) {
            container.add(entryCard(entry, index)).growX().left().pad(3f, 4f, 3f, 4f).row()
        }
    }

    /** 单条目卡片：摘要 + 展开编辑全部字段 */
    private fun entryCard(entry: EntryData, index: Int): Table {
        val card = Table(BaseScreen.skin)
        card.background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/CpEntry", BaseScreen.skinStrings.roundedEdgeRectangleShape,
            Color(1f, 1f, 1f, 0.06f))
        card.defaults().pad(3f)

        // 摘要行
        val summary = StringBuilder()
        if (entry.getBool("separator")) summary.append("— separator —")
        else if (entry.getString("extraImage").isNotBlank()) summary.append("🖼 ").append(entry.getString("extraImage"))
        else summary.append(entry.getString("text").lineSequence().firstOrNull()?.take(60) ?: "(empty)")
        if (entry.raw.containsKey("header")) summary.append("  [h").append(entry.getInt("header")).append("]")
        if (entry.getString("link").isNotBlank()) summary.append("  → ").append(entry.getString("link"))
        if (entry.getString("color").isNotBlank()) summary.append("  ♥").append(entry.getString("color"))
        val summaryLabel = summary.toString().toLabel(fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.75f))
        summaryLabel.wrap = true
        val summaryRow = Table(BaseScreen.skin)
        summaryRow.add(summaryLabel).growX().left().pad(4f)
        val expandButton = "Edit".toTextButton()
        expandButton.onActivation { showEntryEditor(entry, index) }
        summaryRow.add(expandButton).pad(4f)
        val removeButton = "×".toTextButton()
        removeButton.onActivation {
            entries.removeAt(index)
            refresh()
        }
        summaryRow.add(removeButton).pad(4f)
        card.add(summaryRow).growX().left().row()
        return card
    }

    /** 条目编辑弹窗：全部字段 */
    private fun showEntryEditor(entry: EntryData, index: Int) {
        val popup = com.unciv.ui.popups.Popup(screen, scrollable = com.unciv.ui.popups.Popup.Scrollability.None)
        popup.add("Civilopedia entry".tr().toLabel(fontSize = 20)).pad(6f).row()

        val form = Table(BaseScreen.skin)
        form.defaults().pad(3f)

        val textArea = TextArea(entry.getString("text"), BaseScreen.skin)
        val textScroll = AutoScrollPane(textArea).apply {
            setOverscroll(false, false)
            setScrollingDisabled(true, false)
        }
        addLabeled(form, "Text", textScroll, height = 90f)

        val linkField = UncivTextField("", entry.getString("link"))
        addLabeled(form, "Link", linkField)
        val linkHint = "Category/Name or external link (http://, https://, mailto:)".tr()
            .toLabel(fontSize = 11, fontColor = Color(1f, 1f, 1f, 0.4f))
        linkHint.wrap = true
        form.add(linkHint).growX().colspan(2).left().row()

        val iconField = UncivTextField("", entry.getString("icon"))
        addLabeled(form, "Icon", iconField)

        val extraImageField = UncivTextField("", entry.getString("extraImage"))
        addLabeled(form, "Extra image", extraImageField)
        val imageHint = "Path in a texture atlas or a png/jpg name in the ExtraImages folder".tr()
            .toLabel(fontSize = 11, fontColor = Color(1f, 1f, 1f, 0.4f))
        imageHint.wrap = true
        form.add(imageHint).growX().colspan(2).left().row()

        // 数值行：header / size / imageSize / indent / padding
        val headerField = numberField(entry.raw["header"]?.toString() ?: "")
        val sizeField = numberField(entry.raw["size"]?.toString() ?: "")
        val imageSizeField = decimalField(entry.raw["imageSize"]?.toString() ?: "")
        val indentField = numberField(entry.raw["indent"]?.toString() ?: "")
        val paddingField = decimalField(entry.raw["padding"]?.toString() ?: "")

        val numRow = Table(BaseScreen.skin)
        addSmallPair(numRow, "Header", headerField)
        addSmallPair(numRow, "Size", sizeField)
        addSmallPair(numRow, "Image size", imageSizeField)
        form.add(numRow).growX().colspan(2).row()
        val numRow2 = Table(BaseScreen.skin)
        addSmallPair(numRow2, "Indent", indentField)
        addSmallPair(numRow2, "Padding", paddingField)
        form.add(numRow2).growX().colspan(2).row()

        val colorField = UncivTextField("", entry.getString("color"))
        addLabeled(form, "Color", colorField)
        val colorHint = "Name or 6/3-digit web color, e.g. red or #FFA040".tr()
            .toLabel(fontSize = 11, fontColor = Color(1f, 1f, 1f, 0.4f))
        colorHint.wrap = true
        form.add(colorHint).growX().colspan(2).left().row()

        // 布尔行
        val separatorCheck = CheckBox("Separator".tr(), BaseScreen.skin).apply { isChecked = entry.getBool("separator") }
        val starredCheck = CheckBox("Starred".tr(), BaseScreen.skin).apply { isChecked = entry.getBool("starred") }
        val centeredCheck = CheckBox("Centered".tr(), BaseScreen.skin).apply { isChecked = entry.getBool("centered") }
        val iconCrossedCheck = CheckBox("Icon crossed".tr(), BaseScreen.skin).apply { isChecked = entry.getBool("iconCrossed") }
        val boolRow = Table(BaseScreen.skin)
        boolRow.add(separatorCheck).left().pad(4f)
        boolRow.add(starredCheck).left().pad(4f)
        boolRow.add(centeredCheck).left().pad(4f)
        boolRow.add(iconCrossedCheck).left().pad(4f)
        form.add(boolRow).growX().colspan(2).left().row()

        val formScroll = AutoScrollPane(form).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        popup.add(formScroll).grow().width(560f).height(430f).pad(6f).row()

        popup.addButton("Save".tr()) {
            entry.raw.clear()
            val text = textArea.text.trim()
            if (text.isNotEmpty()) entry.raw["text"] = text
            val link = linkField.text.trim()
            if (link.isNotEmpty()) entry.raw["link"] = link
            val icon = iconField.text.trim()
            if (icon.isNotEmpty()) entry.raw["icon"] = icon
            val extraImage = extraImageField.text.trim()
            if (extraImage.isNotEmpty()) entry.raw["extraImage"] = extraImage
            headerField.text.trim().toIntOrNull()?.let { entry.raw["header"] = it }
            sizeField.text.trim().toIntOrNull()?.let { entry.raw["size"] = it }
            imageSizeField.text.trim().toFloatOrNull()?.let { entry.raw["imageSize"] = it }
            indentField.text.trim().toIntOrNull()?.let { entry.raw["indent"] = it }
            paddingField.text.trim().toFloatOrNull()?.let { entry.raw["padding"] = it }
            val color = colorField.text.trim()
            if (color.isNotEmpty()) entry.raw["color"] = color
            if (separatorCheck.isChecked) entry.raw["separator"] = true
            if (starredCheck.isChecked) entry.raw["starred"] = true
            if (centeredCheck.isChecked) entry.raw["centered"] = true
            if (iconCrossedCheck.isChecked) entry.raw["iconCrossed"] = true
            popup.close()
            refresh()
        }
        popup.addCloseButton()
        popup.open()
    }

    private fun addLabeled(form: Table, labelKey: String, widget: com.badlogic.gdx.scenes.scene2d.Actor, height: Float? = null) {
        form.add(labelKey.tr().toLabel(fontSize = 13)).left().pad(4f).width(110f).top()
        val cell = form.add(widget).growX().minWidth(200f).pad(4f)
        if (height != null) cell.height(height)
        form.row()
    }

    private fun addSmallPair(row: Table, labelKey: String, field: TextField) {
        row.add(labelKey.tr().toLabel(fontSize = 12)).left().pad(3f).width(90f)
        row.add(field).width(80f).pad(3f)
    }

    private fun numberField(value: String): UncivTextField {
        val field = UncivTextField("", value)
        field.textFieldFilter = object : TextField.TextFieldFilter {
            override fun acceptChar(textField: TextField, c: Char): Boolean {
                if (c in '0'..'9') return true
                if (c == '-' && textField.text.isEmpty()) return true
                return false
            }
        }
        return field
    }

    private fun decimalField(value: String): UncivTextField {
        val field = UncivTextField("", value)
        field.textFieldFilter = object : TextField.TextFieldFilter {
            override fun acceptChar(textField: TextField, c: Char): Boolean {
                if (c in '0'..'9') return true
                if (c == '-' && textField.text.isEmpty()) return true
                if (c == '.' && !textField.text.contains('.')) return true
                return false
            }
        }
        return field
    }

    private fun addEntry(entry: EntryData) {
        entries.add(entry)
    }

    /** 保存时调用：重建 raw["civilopediaText"]；无条目时返回 null（调用方负责 remove） */
    fun buildEntries(): List<Map<String, Any?>>? {
        if (entries.isEmpty()) return null
        val result = mutableListOf<Map<String, Any?>>()
        for (entry in entries) {
            if (entry.raw.isEmpty()) continue
            result.add(LinkedHashMap(entry.raw))
        }
        return if (result.isEmpty()) null else result
    }
}

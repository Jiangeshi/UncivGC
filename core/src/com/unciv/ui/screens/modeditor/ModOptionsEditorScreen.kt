package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextArea
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Align
import com.unciv.models.ModConstants
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import java.lang.reflect.Modifier

/**
 * ModOptions 表单编辑器：与 Units 编辑器同款结构。
 * - 基本信息: isBaseRuleset / tileset / unitset
 * - 词条: 模组级 uniques（行内编辑 + 选择器）
 * - 移除列表: 8 个 ToRemove 列表（搜索添加 + 芯片删除）
 * - 常量: ModConstants 全部字段反射生成（默认值 = 游戏源码 ModConstants()），
 *   只写与默认值不同的项（与游戏 merge 语义一致）；unitUpgradeCost 子结构单独一块
 * - 元数据: modUrl / defaultBranch / author / lastUpdated / modSize / topics
 */
class ModOptionsEditorScreen(private val modFolder: FileHandle) : BaseScreen() {

    private val data = ModEditorData.loadModOptions(modFolder)
    private val uniqueCatalog = UniqueCatalog.load()
    private val formTable = FillWidthTable(BaseScreen.skin)
    private val statusLabel = "".toLabel(fontSize = 16)

    private lateinit var baseRulesetCheck: CheckBox
    private lateinit var tilesetBox: ModEditorSelectBox
    private lateinit var unitsetBox: ModEditorSelectBox
    private lateinit var uniquesTable: Table
    private lateinit var uniquesButtonRow: Table
    private val constantFields = LinkedHashMap<String, UncivTextField>()
    private val upgradeCostFields = LinkedHashMap<String, UncivTextField>()
    private val metadataFields = LinkedHashMap<String, UncivTextField>()

    init {
        val root = Table(BaseScreen.skin)
        root.setFillParent(true)
        stage.addActor(root)

        val topBar = Table(BaseScreen.skin)
        val backButton = ("‹ " + "Back".tr()).toTextButton()
        backButton.onActivation { game.popScreen() }
        topBar.add(backButton).pad(8f)
        topBar.add("ModOptions".toLabel(fontSize = 28)).padLeft(20f).expandX().left()
        val saveButton = "Save".toTextButton()
        saveButton.onActivation { save() }
        topBar.add(saveButton).pad(8f)
        topBar.add(statusLabel).pad(8f)
        root.add(topBar).fillX().row()

        val rightScroll = AutoScrollPane(formTable).apply {
            setOverscroll(false, false)
            setScrollingDisabled(true, false)
        }
        root.add(rightScroll).expand().grow().pad(4f)

        rebuildForm()
    }

    // ------------------------------------------------------------------
    // 表单
    // ------------------------------------------------------------------

    private fun rebuildForm() {
        formTable.clear()
        constantFields.clear()
        upgradeCostFields.clear()

        // 扩展规则集（isBaseRuleset=false 或未设置）不显示基本信息区，防止误改
        val isBase = data.getBool("isBaseRuleset")
        if (isBase) {
            formTable.add(sectionHeader("Basic info".tr())).fillX().row()

            val basicRow = Table(BaseScreen.skin)
            baseRulesetCheck = CheckBox("isBaseRuleset".tr(), BaseScreen.skin).apply {
                isChecked = true
                addListener(object : ChangeListener() {
                    override fun changed(event: ChangeEvent?, actor: Actor?) {
                        tilesetBox.isDisabled = !isChecked
                        unitsetBox.isDisabled = !isChecked
                    }
                })
            }
            basicRow.add(baseRulesetCheck).left().pad(4f).colspan(2)
            basicRow.row()
            tilesetBox = optionalBox(ImageGetter.getAvailableTilesets().toList(), data.getString("tileset"))
            unitsetBox = optionalBox(ImageGetter.getAvailableUnitsets().toList(), data.getString("unitset"))
            addBoxWithLabel(basicRow, "tileset", tilesetBox, "Only applicable for base rulesets".tr())
            addBoxWithLabel(basicRow, "unitset", unitsetBox, "Only applicable for base rulesets".tr())
            formTable.add(basicRow).growX().left().pad(4f, 10f, 4f, 10f).row()
        }

        formTable.add(sectionHeader("Uniques".tr())).fillX().row()

        uniquesTable = Table(BaseScreen.skin)
        val uniquesBox = Table(BaseScreen.skin)
        uniquesBox.background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/UniquesBox", BaseScreen.skinStrings.roundedEdgeRectangleShape,
            Color(1f, 1f, 1f, 0.07f))
        val uniquesScroll = AutoScrollPane(uniquesTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        uniquesBox.add(uniquesScroll).grow().pad(10f)
        formTable.add(uniquesBox).growX().height(400f).left().pad(6f).row()

        // 添加/原文编辑按钮：放框外（不随内容滚动，始终可见）
        uniquesButtonRow = Table(BaseScreen.skin)
        formTable.add(uniquesButtonRow).growX().left().pad(4f, 6f, 4f, 6f).row()
        rebuildUniquesTable()

        formTable.add(sectionHeader("Removals".tr())).fillX().row()
        addRemovalRow("techsToRemove", ModEditorData.getTechs(modFolder))
        addRemovalRow("buildingsToRemove", ModEditorData.getBuildings(modFolder))
        addRemovalRow("unitsToRemove", ModEditorData.getUnits(modFolder))
        addRemovalRow("nationsToRemove", ModEditorData.getNations(modFolder))
        addRemovalRow("policyBranchesToRemove", ModEditorData.getPolicies(modFolder))
        addRemovalRow("policiesToRemove", ModEditorData.getPolicies(modFolder))
        addRemovalRow("beliefsToRemove", ModEditorData.getBeliefs(modFolder))
        addRemovalRow("religionsToRemove", ModEditorData.getReligions(modFolder))

        formTable.add(sectionHeader("Constants".tr())).fillX().row()
        addConstantsSection()

        formTable.add(sectionHeader("Metadata".tr())).fillX().row()
        addMetadataSection()
    }

    private fun fieldWithLabel(
        table: Table, label: String, value: String,
        hint: String? = null
    ): UncivTextField {        val row = Table(BaseScreen.skin)
        row.add(label.toLabel()).left().pad(4f).width(220f)
        val field = UncivTextField("", value)
        row.add(field).growX().minWidth(200f).pad(4f)
        if (hint != null) {
            row.row()
            val hintLabel = hint.toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f))
            hintLabel.wrap = true
            row.add(hintLabel).colspan(2).growX().left().pad(0f, 8f, 4f, 8f)
        }
        table.add(row).growX().left().colspan(2).row()
        return field
    }

    private fun optionalBox(values: List<String>, current: String): ModEditorSelectBox {
        val items = mutableListOf("(None)")
        items.addAll(values)
        val cur = current
        if (cur.isNotBlank() && cur !in items) items.add(cur)
        return ModEditorSelectBox(items, if (cur.isBlank()) "(None)" else cur, searchable = true)
    }

    private fun addBoxWithLabel(table: Table, labelKey: String, box: ModEditorSelectBox, hint: String) {
        val row = Table(BaseScreen.skin)
        row.add(labelKey.toLabel()).left().pad(4f).width(220f)
        row.add(box).growX().minWidth(200f).pad(4f)
        row.row()
        val hintLabel = hint.toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f))
        hintLabel.wrap = true
        row.add(hintLabel).colspan(2).growX().left().pad(0f, 8f, 4f, 8f)
        table.add(row).growX().left().colspan(2).row()
    }

    // ------------------------------------------------------------------
    // 词条（模组级 uniques）
    // ------------------------------------------------------------------

    private fun rebuildUniquesTable() {
        uniquesTable.clear()
        uniquesButtonRow.clear()
        if (data.uniques.isEmpty()) {
            uniquesTable.add("(no uniques)".toLabel(fontSize = 16, fontColor = Color(1f, 1f, 1f, 0.4f)))
                .left().pad(4f).row()
        }
        for ((index, rawString) in data.uniques.withIndex()) {
            val parsed = uniqueCatalog.parseRaw(rawString)
            if (parsed != null) {
                lateinit var editor: UniqueInlineEditor
                editor = UniqueInlineEditor(
                    screen = this, modFolder = modFolder, catalog = uniqueCatalog,
                    unique = parsed.unique, values = parsed.values, conditions = parsed.conditions,
                    onValueChange = { data.uniques[index] = editor.buildRaw() },
                    onStructureChange = {
                        data.uniques[index] = editor.buildRaw()
                        rebuildUniquesTable()
                    },
                    onDuplicate = {
                        val copyValues = parsed.values.toMutableMap()
                        val copyConditions = parsed.conditions
                            .map { (c, v) -> c to v.toMutableMap() }.toMutableList()
                        data.uniques.add(index + 1,
                            uniqueCatalog.buildRawString(parsed.unique, copyValues, copyConditions))
                        rebuildUniquesTable()
                    },
                    onDelete = {
                        data.uniques.removeAt(index)
                        rebuildUniquesTable()
                    }
                )
                uniquesTable.add(editor).growX().left().pad(3f, 8f, 3f, 8f).row()
                uniquesTable.add(uniqueSeparatorLine()).growX().height(1f).pad(2f, 8f, 2f, 8f).row()
            } else {
                // 目录不认识的原始词条：只读展示 + 原文编辑 + 删除
                val row = Table(BaseScreen.skin)
                val label = Label(rawString, BaseScreen.skin).apply {
                    setFontScale(16f / Fonts.ORIGINAL_FONT_SIZE)
                    setAlignment(Align.left)
                    setColor(Color(1f, 1f, 1f, 0.8f))
                    wrap = true
                }
                row.add(label).growX().minWidth(420f).left().pad(4f)
                val editButton = "Edit".toTextButton()
                editButton.onActivation { showUniqueEditor(index, rawString) }
                row.add(editButton).pad(4f)
                val removeButton = "×".toTextButton()
                removeButton.onActivation {
                    data.uniques.removeAt(index)
                    rebuildUniquesTable()
                }
                row.add(removeButton).pad(4f)
                uniquesTable.add(row).growX().left().row()
            }
        }
        val addButton = "+ Add unique".toTextButton()
        addButton.onActivation {
            game.pushScreen(UniquePickerScreen(
                onPick = { unique ->
                    val values = unique.params
                        .filter { it.default.isNotBlank() }
                        .associate { it.id to it.default }.toMutableMap()
                    data.uniques.add(uniqueCatalog.buildRawString(unique, values, emptyList()))
                    rebuildUniquesTable()
                },
                onRawPicked = { text ->
                    data.uniques.add(text)
                    rebuildUniquesTable()
                }
            ))
        }
        uniquesButtonRow.add(addButton).left().pad(6f)
        addRawEditUniquesButton(this, uniquesButtonRow, getUniques = { data.uniques }) { rebuildUniquesTable() }
        uniquesTable.row()
    }

    private fun showUniqueEditor(index: Int?, existing: String?) {
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        popup.add("Unique (raw mode)".toLabel(fontSize = 22)).pad(10f).row()
            popup.add("No quotes needed - they are added automatically when saving".tr().toLabel(
                fontSize = 12, fontColor = com.badlogic.gdx.graphics.Color(1f, 1f, 1f, 0.45f))).pad(0f, 10f, 4f, 10f).row()
        val textArea = TextArea(existing ?: "", BaseScreen.skin)
        popup.add(textArea).width(560f).height(160f).pad(6f).row()
        popup.addButton("Save") {
            val text = textArea.text.replace('\n', ' ').replace('\r', ' ')
                .replace(Regex("\\s{2,}"), " ").trim()
            if (text.isNotEmpty()) {
                if (index == null) data.uniques.add(text)
                else if (index < data.uniques.size) data.uniques[index] = text
            } else if (index != null && index < data.uniques.size) {
                data.uniques.removeAt(index)
            }
            popup.close()
            rebuildUniquesTable()
        }
        popup.addCloseButton()
        popup.open()
    }

    // ------------------------------------------------------------------
    // 移除列表（ToRemove）
    // ------------------------------------------------------------------

    private fun addRemovalRow(key: String, options: List<String>) {
        val row = Table(BaseScreen.skin)
        row.add(key.tr().toLabel(fontSize = 16, fontColor = Color(1f, 1f, 1f, 0.75f)))
            .left().pad(4f).width(240f).top()
        val chipsTable = Table(BaseScreen.skin)

        fun refreshChips() {
            chipsTable.clear()
            // 芯片按可用宽度换行排列（多了不会溢出）：可用宽 = 表单实际宽 - 标签列 240 - 边距/按钮
            val maxWidth = formAvailableWidth(stage.width, extraDeduction = 240f)
            var currentRow = Table(BaseScreen.skin)
            var rowWidth = 0f
            for (value in data.getStringList(key)) {
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
                    val list = data.getStringList(key).toMutableList()
                    list.remove(value)
                    data.setStringList(key, list)
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
        addButton.onActivation {
            showAddToListPopup(key, options, ::refreshChips)
        }

        val right = Table(BaseScreen.skin)
        right.add(chipsTable).growX().left().row()
        right.add(addButton).left().padTop(2f)
        row.add(right).growX().left().pad(4f)
        formTable.add(row).growX().left().pad(3f, 10f, 3f, 10f).row()
    }

    private fun showAddToListPopup(key: String, options: List<String>, onChanged: () -> Unit) {
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        popup.add((key.tr() + " · " + "Add".tr()).toLabel(fontSize = 20)).pad(8f).row()
        val searchField = UncivTextField("Search")
        popup.add(searchField).growX().width(520f).pad(6f).row()
        val listTable = Table(BaseScreen.skin)
        val listScroll = AutoScrollPane(listTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        popup.add(listScroll).grow().width(520f).height(360f).pad(6f).row()

        val current = data.getStringList(key).toSet()

        fun refresh(query: String) {
            listTable.clear()
            var shown = 0
            for (item in options) {
                if (item in current) continue
                if (query.isNotBlank() && !item.lowercase().contains(query.trim().lowercase())) continue
                val row = Table(BaseScreen.skin)
                row.background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/SearchRow", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                    BaseScreen.skinStrings.skinConfig.baseColor)
                val label = bilingualUniqueLabel(item, item.tr(), 15f)
                row.add(label).growX().left().pad(6f, 8f, 6f, 8f)
                row.touchable = Touchable.enabled
                row.onActivation {
                    val list = data.getStringList(key).toMutableList()
                    list.add(item)
                    data.setStringList(key, list)
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

    // ------------------------------------------------------------------
    // 常量（ModConstants 反射生成）
    // ------------------------------------------------------------------

    private fun addConstantsSection() {
        val defaults = ModConstants()
        val current = data.getConstants()

        // 排除 unitUpgradeCost（子结构单独一块），其余字段 2 列排
        val fields = ModConstants::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && it.name != "unitUpgradeCost" }
        val grid = Table(BaseScreen.skin)
        for ((i, field) in fields.withIndex()) {
            field.isAccessible = true
            val default = field.get(defaults)
            val currentValue = current[field.name]
            val text = currentValue?.let { formatNumber(it) } ?: formatNumber(default)
            val fieldWidget = numberField(text)
            constantFields[field.name] = fieldWidget
            val label = field.name.toLabel(fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.7f))
            label.wrap = true
            val cell = Table(BaseScreen.skin)
            cell.add(label).growX().left().width(220f).pad(2f)
            cell.add(fieldWidget).width(130f).pad(2f)
            if (i % 2 == 0) {
                grid.add(cell).left().growX().pad(2f)
            } else {
                grid.add(cell).left().growX().pad(2f).row()
            }
        }
        if (fields.size % 2 == 1) grid.row()
        formTable.add(grid).growX().left().pad(4f, 10f, 4f, 10f).row()

        // unitUpgradeCost 子结构
        formTable.add("unitUpgradeCost".tr().toLabel(fontSize = 16, fontColor = Color(1f, 1f, 1f, 0.75f)))
            .left().pad(8f, 10f, 2f, 10f).row()
        val upCostBox = Table(BaseScreen.skin)
        upCostBox.background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/UniquesBox", BaseScreen.skinStrings.roundedEdgeRectangleShape,
            Color(1f, 1f, 1f, 0.07f))
        val upDefaults = ModConstants.UnitUpgradeCost()
        val upCurrent = current["unitUpgradeCost"] as? Map<*, *>
        for (sub in listOf("base", "perProduction", "eraMultiplier", "exponent", "roundTo")) {
            val default = when (sub) {
                "base" -> upDefaults.base
                "perProduction" -> upDefaults.perProduction
                "eraMultiplier" -> upDefaults.eraMultiplier
                "exponent" -> upDefaults.exponent
                else -> upDefaults.roundTo
            }
            val currentValue = upCurrent?.get(sub)
            val text = currentValue?.let { formatNumber(it) } ?: formatNumber(default)
            val fieldWidget = numberField(text)
            upgradeCostFields[sub] = fieldWidget
            upCostBox.add(sub.toLabel(fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.7f)))
                .left().pad(3f).width(130f)
            upCostBox.add(fieldWidget).width(110f).pad(3f)
        }
        formTable.add(upCostBox).growX().left().pad(4f, 10f, 2f, 10f).row()
        // 升级费用公式提示
        val formulaHint = "Upgrade gold cost formula".tr().toLabel(
            fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f))
        formulaHint.wrap = true
        formTable.add(formulaHint).growX().left().pad(0f, 12f, 6f, 12f).row()
    }

    private fun formatNumber(v: Any): String = when (v) {
        is Int -> v.toString()
        is Long -> v.toString()
        is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
        is Float -> if (v % 1f == 0f) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    private fun parseNumber(text: String, default: Any): Any? = when (default) {
        is Int -> text.toIntOrNull()
        is Long -> text.toLongOrNull()
        is Double -> text.toDoubleOrNull()
        is Float -> text.toFloatOrNull()
        else -> null
    }

    private fun numberField(value: String): UncivTextField {
        val field = UncivTextField("", value)
        field.textFieldFilter = object : TextField.TextFieldFilter {
            override fun acceptChar(textField: TextField, c: Char): Boolean {
                if (c in '0'..'9') return true
                if (c == '-' && !textField.text.contains('-')) return true
                if (c == '.' && !textField.text.contains('.')) return true
                return false
            }
        }
        return field
    }

    // ------------------------------------------------------------------
    // 元数据
    // ------------------------------------------------------------------

    private fun addMetadataSection() {
        val table = Table(BaseScreen.skin)
        metadataFields["modUrl"] = fieldWithLabel(table, "modUrl", data.getString("modUrl"))
        metadataFields["defaultBranch"] = fieldWithLabel(table, "defaultBranch", data.getString("defaultBranch"))
        metadataFields["author"] = fieldWithLabel(table, "author", data.getString("author"))
        metadataFields["lastUpdated"] = fieldWithLabel(table, "lastUpdated", data.getString("lastUpdated"))
        val modSizeField = UncivTextField("", data.getIntText("modSize"))
        modSizeField.textFieldFilter = object : TextField.TextFieldFilter {
            override fun acceptChar(textField: TextField, c: Char): Boolean = c in '0'..'9'
        }
        val modSizeRow = Table(BaseScreen.skin)
        modSizeRow.add("modSize".toLabel()).left().pad(4f).width(220f)
        modSizeRow.add(modSizeField).growX().minWidth(200f).pad(4f)
        table.add(modSizeRow).growX().left().colspan(2).row()
        val topicsField = UncivTextField("", data.getStringList("topics").joinToString(", "))
        val topicsRow = Table(BaseScreen.skin)
        topicsRow.add("topics".toLabel()).left().pad(4f).width(220f)
        topicsRow.add(topicsField).growX().minWidth(200f).pad(4f)
        table.add(topicsRow).growX().left().colspan(2).row()

        // 保存时用到的字段存起来
        metadataFields["modSize"] = modSizeField
        metadataFields["topics"] = topicsField

        formTable.add(table).growX().left().pad(4f, 10f, 4f, 10f).row()
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    private fun save() {
        // 基本信息（仅基础规则集可编辑）
        if (data.getBool("isBaseRuleset")) {
            data.setBool("isBaseRuleset", baseRulesetCheck.isChecked)
            data.setString("tileset", tilesetBox.selected?.value?.takeUnless { it == "(None)" })
            data.setString("unitset", unitsetBox.selected?.value?.takeUnless { it == "(None)" })
        }

        // 元数据
        data.setString("modUrl", metadataFields["modUrl"]?.text)
        data.setString("defaultBranch", metadataFields["defaultBranch"]?.text)
        data.setString("author", metadataFields["author"]?.text)
        data.setString("lastUpdated", metadataFields["lastUpdated"]?.text)
        val modSize = metadataFields["modSize"]?.text?.trim()?.toIntOrNull()
        data.setInt("modSize", modSize)
        data.setStringList("topics",
            metadataFields["topics"]?.text?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList())

        // 常量：只写与默认值不同的项（游戏 merge 语义：默认值被忽略）
        val constants = LinkedHashMap<String, Any?>()
        val defaults = ModConstants()
        for ((name, fieldWidget) in constantFields) {
            val text = fieldWidget.text.trim()
            if (text.isBlank()) continue
            val field = ModConstants::class.java.getDeclaredField(name).apply { isAccessible = true }
            val default = field.get(defaults)
            val parsed = parseNumber(text, default) ?: continue
            if (parsed != default) constants[name] = parsed
        }
        val upDefaults = ModConstants.UnitUpgradeCost()
        val upCost = LinkedHashMap<String, Any?>()
        for ((name, fieldWidget) in upgradeCostFields) {
            val text = fieldWidget.text.trim()
            if (text.isBlank()) continue
            val default = when (name) {
                "base" -> upDefaults.base
                "perProduction" -> upDefaults.perProduction
                "eraMultiplier" -> upDefaults.eraMultiplier
                "exponent" -> upDefaults.exponent
                else -> upDefaults.roundTo
            }
            val parsed = parseNumber(text, default) ?: continue
            if (parsed != default) upCost[name] = parsed
        }
        if (upCost.isNotEmpty()) constants["unitUpgradeCost"] = upCost
        data.setConstants(constants)

        ModEditorData.saveModOptions(modFolder, data)
        statusLabel.setText("Saved".tr())
    }

    // ------------------------------------------------------------------
    // 样式辅助
    // ------------------------------------------------------------------

    private fun separatorLine(): Table = Table(BaseScreen.skin).apply {
        background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/Separator", null, Color(1f, 1f, 1f, 0.18f))
    }

    private fun sectionHeader(text: String): Table {
        val header = Table(BaseScreen.skin)
        header.add(text.toLabel(fontSize = 20, fontColor = Color(0.55f, 0.85f, 1f, 1f)))
            .left().padTop(12f).padBottom(2f).padLeft(2f)
        header.row()
        header.add(separatorLine()).fillX().height(2f)
        return header
    }
}

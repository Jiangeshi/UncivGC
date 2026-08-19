package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextArea
import com.badlogic.gdx.utils.Align
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.addSeparatorVertical
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import kotlin.math.max

/** 单位晋升编辑器：name / prerequisites / column / row / unitTypes / uniques / colors / civilopediaText */
class UnitPromotionsEditorScreen(private val modFolder: FileHandle) : BaseScreen() {

    private val items = ModEditorData.loadPromotions(modFolder)
    private var selectedIndex = -1
    private val uniqueCatalog = UniqueCatalog.load()

    private val listTable = Table(BaseScreen.skin).apply {
        defaults().expandX().fillX()
        top()   // 列表内容不满时顶部对齐，否则垂直居中（libGDX Table 默认会居中）
    }
    private val formTable = FillWidthTable(BaseScreen.skin)
    private val statusLabel = "".toLabel(fontSize = 16)

    private lateinit var nameField: UncivTextField
    private lateinit var columnField: UncivTextField
    private lateinit var rowField: UncivTextField
    private lateinit var innerColorR: UncivTextField
    private lateinit var innerColorG: UncivTextField
    private lateinit var innerColorB: UncivTextField
    private lateinit var outerColorR: UncivTextField
    private lateinit var outerColorG: UncivTextField
    private lateinit var outerColorB: UncivTextField
    private lateinit var uniquesTable: Table
    private lateinit var uniquesButtonRow: Table
    private lateinit var civilopediaEditor: CivilopediaTextEditor
    private lateinit var commentArea: TextArea
    private lateinit var searchField: UncivTextField
    private var searchQuery = ""

    private val unitTypeChips = mutableListOf<String>()
    private val prereqChips = mutableListOf<String>()
    private lateinit var unitTypesChipTable: Table
    private lateinit var prereqsChipTable: Table

    private fun current() = items[selectedIndex]

    init {
        val root = Table(BaseScreen.skin)
        root.setFillParent(true)
        stage.addActor(root)

        val topBar = Table(BaseScreen.skin)
        val backButton = ("‹ " + "Back".tr()).toTextButton()
        backButton.onActivation { game.popScreen() }
        topBar.add(backButton).pad(8f)
        topBar.add(("UnitPromotions".tr() + " · UnitPromotions.json").toLabel(fontSize = 28))
            .padLeft(20f).expandX().left()
        val saveButton = "Save".toTextButton()
        saveButton.onActivation { save() }
        topBar.add(saveButton).pad(8f)
        topBar.add(statusLabel).pad(8f)
        root.add(topBar).fillX().row()

        // 左侧：按钮行 + 搜索 + 分隔线 + 滚动列表
        val leftPanel = Table(BaseScreen.skin)
        val buttonRow = Table(BaseScreen.skin)
        val addButton = "+ New promotion".toTextButton()
        addButton.onActivation { addItem() }
        buttonRow.add(addButton).left().pad(6f)
        val copyButton = "Copy from ruleset".toTextButton()
        copyButton.onActivation { showCopyPopup() }
        buttonRow.add(copyButton).left().pad(6f)
        leftPanel.add(buttonRow).fillX().row()
        searchField = UncivTextField("Search")
        searchField.setTextFieldListener { field, _ ->
            searchQuery = field.text.trim().lowercase()
            refreshList()
        }
        leftPanel.add(searchField).growX().pad(4f, 8f, 2f, 8f).row()
        leftPanel.add(separatorLine()).fillX().height(2f).pad(4f, 8f, 4f, 8f).row()
        val leftListScroll = AutoScrollPane(listTable).apply {
            setOverscroll(false, false)
            setScrollingDisabled(true, false)
            fadeScrollBars = false
        }
        leftPanel.add(leftListScroll).expand().fill().row()

        // 右侧表单：AutoScrollPane 悬停时自动接管滚轮焦点；横向禁用 → 内容拉伸填满整栏
        val rightScroll = AutoScrollPane(formTable).apply {
            setOverscroll(false, false)
            setScrollingDisabled(true, false)
        }
        formTable.defaults().expandX().fillX()

        val body = Table(BaseScreen.skin)
        body.add(leftPanel).width(max(280f, stage.width / 4)).growY().pad(4f)
        body.addSeparatorVertical(ImageGetter.CHARCOAL, 2f)
        body.add(rightScroll).expand().grow().pad(4f)
        root.add(body).grow()

        refreshList()
        if (items.isNotEmpty()) { selectedIndex = 0; rebuildForm() }
        else formTable.add("No promotions. Click \"+ New promotion\" in the top left.".toLabel()).pad(20f).row()
    }

    private fun refreshList() {
        listTable.clear()
        for ((i, item) in items.withIndex()) {
            if (searchQuery.isNotEmpty() && !item.name.lowercase().contains(searchQuery) &&
                !(item.name.tr().lowercase().contains(searchQuery))) continue
            val row = Table(BaseScreen.skin)
            row.defaults().pad(6f)
            row.background = if (i == selectedIndex) selectedRowBackground() else rowBackground()
            row.add(ImageGetter.getPromotionPortrait(item.name, 30f)).padRight(4f)
            val nameLabel = listNameLabel(
                item.name, maxWidth = stage.width * 0.25f - 130f, fontSize = 20)
            row.add(nameLabel).growX().left().maxWidth(stage.width * 0.25f - 130f)
            row.touchable = Touchable.enabled
            row.onActivation { selectedIndex = i; refreshList(); rebuildForm() }
            listTable.add(row).fillX().pad(2f, 6f, 2f, 6f).row()
        }
        if (items.isEmpty()) {
            listTable.add("No promotions yet.".toLabel()).pad(20f).row()
        }
    }

    private fun rebuildForm() {
        formTable.clear()
        if (selectedIndex < 0 || selectedIndex >= items.size) return
        val item = current()

        // 表单头：标题 + Duplicate + Delete（与单位/建筑编辑器一致）
        val header = Table(BaseScreen.skin)
        header.add("Edit promotion".toLabel(fontSize = 24)).left().expandX()
        val copyButton = "Duplicate".toTextButton()
        copyButton.onActivation {
            val copy = ModObjectData()
            copy.name = item.name + " copy"
            copy.comment = item.comment
            item.raw.forEach { (k, v) -> copy.raw[k] = v }
            copy.uniques.addAll(item.uniques)
            items.add(copy)
            selectedIndex = items.lastIndex
            refreshList()
            rebuildForm()
        }
        header.add(copyButton).pad(4f)
        val deleteButton = "Delete".toTextButton()
        deleteButton.onActivation { deleteItem() }
        header.add(deleteButton).pad(4f)
        formTable.add(header).fillX().row()

        nameField = formField(formTable, "Name", item.name)

        // Column / Row
        val coordRow = Table(BaseScreen.skin)
        coordRow.add("Column".toLabel()).left().pad(4f).width(100f)
        columnField = UncivTextField("", item.getIntText("column"))
        coordRow.add(columnField).growX().minWidth(80f).pad(4f)   // 行内控件必须 growX，否则右侧空白
        coordRow.add("Row".toLabel()).left().pad(4f).width(60f)
        rowField = UncivTextField("", item.getIntText("row"))
        coordRow.add(rowField).growX().minWidth(80f).pad(4f)
        coordRow.add("(position hint for the tree)".tr().toLabel(fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.5f)))
            .growX().left().pad(4f)   // 提示文字吃满剩余空间
        formTable.add(coordRow).fillX().left().pad(4f, 10f, 4f, 10f).row()

        // Unit types (chips)
        formTable.add(sectionHeader("Unit types")).fillX().row()
        unitTypeChips.clear()
        unitTypeChips.addAll(item.getStringList("unitTypes"))
        unitTypesChipTable = Table(BaseScreen.skin)
        refreshChipTable(unitTypesChipTable, unitTypeChips, "unitTypes")
        formTable.add(chipBox(unitTypesChipTable)).growX().left().pad(4f, 10f, 4f, 10f).row()

        // Prerequisites (chips)
        formTable.add(sectionHeader("Prerequisites")).fillX().row()
        prereqChips.clear()
        prereqChips.addAll(item.getStringList("prerequisites"))
        prereqsChipTable = Table(BaseScreen.skin)
        refreshChipTable(prereqsChipTable, prereqChips, "prerequisites")
        formTable.add(chipBox(prereqsChipTable)).growX().left().pad(4f, 10f, 4f, 10f).row()

        // Uniques
        formTable.add(sectionHeader("Uniques")).fillX().row()
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

        // Colors
        formTable.add(sectionHeader("Colors")).fillX().row()
        val inner = item.raw["innerColor"] as? List<*> ?: emptyList<Any>()
        val outer = item.raw["outerColor"] as? List<*> ?: emptyList<Any>()
        val ir = inner.getOrElse(0) { 255 }.toString().toIntOrNull() ?: 255
        val ig = inner.getOrElse(1) { 255 }.toString().toIntOrNull() ?: 255
        val ib = inner.getOrElse(2) { 255 }.toString().toIntOrNull() ?: 255
        val or = outer.getOrElse(0) { 255 }.toString().toIntOrNull() ?: 255
        val og = outer.getOrElse(1) { 255 }.toString().toIntOrNull() ?: 255
        val ob = outer.getOrElse(2) { 255 }.toString().toIntOrNull() ?: 255
        val innerPreview = Table(BaseScreen.skin)
        innerPreview.background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/PromoInnerColor", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
            Color(ir / 255f, ig / 255f, ib / 255f, 1f))
        val outerPreview = Table(BaseScreen.skin)
        outerPreview.background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/PromoOuterColor", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
            Color(or / 255f, og / 255f, ob / 255f, 1f))
        fun updateInnerPreview() {
            val r = innerColorR.text.toIntOrNull()?.coerceIn(0, 255) ?: 0
            val g = innerColorG.text.toIntOrNull()?.coerceIn(0, 255) ?: 0
            val b = innerColorB.text.toIntOrNull()?.coerceIn(0, 255) ?: 0
            item.raw["innerColor"] = listOf(r, g, b)
            innerPreview.background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/PromoInnerColor", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
                Color(r / 255f, g / 255f, b / 255f, 1f))
        }
        fun updateOuterPreview() {
            val r = outerColorR.text.toIntOrNull()?.coerceIn(0, 255) ?: 0
            val g = outerColorG.text.toIntOrNull()?.coerceIn(0, 255) ?: 0
            val b = outerColorB.text.toIntOrNull()?.coerceIn(0, 255) ?: 0
            item.raw["outerColor"] = listOf(r, g, b)
            outerPreview.background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/PromoOuterColor", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
                Color(r / 255f, g / 255f, b / 255f, 1f))
        }
        val colorRow = Table(BaseScreen.skin)
        colorRow.add("Inner color (RGB)".toLabel()).left().pad(4f).width(180f)
        innerColorR = UncivTextField("R", ir.toString())
        innerColorG = UncivTextField("G", ig.toString())
        innerColorB = UncivTextField("B", ib.toString())
        innerColorR.setTextFieldListener { _, _ -> updateInnerPreview() }
        innerColorG.setTextFieldListener { _, _ -> updateInnerPreview() }
        innerColorB.setTextFieldListener { _, _ -> updateInnerPreview() }
        colorRow.add(innerColorR).width(60f).pad(2f)
        colorRow.add(innerColorG).width(60f).pad(2f)
        colorRow.add(innerColorB).width(60f).pad(2f)
        colorRow.add(innerPreview).size(28f, 20f).pad(4f)
        colorRow.add("(0-255)".tr().toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f)))
            .growX().left().pad(4f)   // 行尾占位：吃满剩余空间，否则右侧空白
        formTable.add(colorRow).fillX().left().pad(4f, 10f, 2f, 10f).row()
        val colorRow2 = Table(BaseScreen.skin)
        colorRow2.add("Outer color (RGB)".toLabel()).left().pad(4f).width(180f)
        outerColorR = UncivTextField("R", or.toString())
        outerColorG = UncivTextField("G", og.toString())
        outerColorB = UncivTextField("B", ob.toString())
        outerColorR.setTextFieldListener { _, _ -> updateOuterPreview() }
        outerColorG.setTextFieldListener { _, _ -> updateOuterPreview() }
        outerColorB.setTextFieldListener { _, _ -> updateOuterPreview() }
        colorRow2.add(outerColorR).width(60f).pad(2f)
        colorRow2.add(outerColorG).width(60f).pad(2f)
        colorRow2.add(outerColorB).width(60f).pad(2f)
        colorRow2.add(outerPreview).size(28f, 20f).pad(4f)
        colorRow2.add("(0-255)".tr().toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f)))
            .growX().left().pad(4f)   // 行尾占位：吃满剩余空间，否则右侧空白
        formTable.add(colorRow2).fillX().left().pad(2f, 10f, 4f, 10f).row()

        // 晋升图标：Images/UnitPromotionIcons/<name>.png
        formTable.add(sectionHeader("Promotion icon")).fillX().row()
        val promotionImage = ModEditorImageSection(
            modFolder = modFolder,
            subDirectory = "UnitPromotionIcons",
            fileName = { current().name },
            preCheck = {
                if (current().name.isBlank()) "Enter a promotion name first, then choose an image." else null
            }
        )
        promotionImage.addImageSection(formTable)

        civilopediaEditor = CivilopediaTextEditor(
            screen = this,
            getRaw = { item.raw["civilopediaText"] },
            setRaw = { item.raw["civilopediaText"] = it }
        )
        civilopediaEditor.addTo(formTable, "Civilopedia text")
        commentArea = textAreaField(formTable, "Comment", item.comment)
    }

    private fun chipBox(chipTable: Table): Table {
        val box = Table(BaseScreen.skin)
        box.background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/ChipBox", BaseScreen.skinStrings.roundedEdgeRectangleShape,
            Color(1f, 1f, 1f, 0.05f))
        val scroll = AutoScrollPane(chipTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        box.add(scroll).grow().pad(6f)
        return box
    }

    private fun refreshChipTable(table: Table, chips: MutableList<String>, chipType: String) {
        table.clear()
        val maxWidth = formAvailableWidth(stage.width, extraDeduction = 40f)
        var x = 0f
        for ((i, name) in chips.withIndex()) {
            val chip = Table(BaseScreen.skin)
            chip.background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/Chip", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
                Color(0.25f, 0.4f, 0.55f, 1f))
            val label = name.toLabel(fontSize = 16)
            label.setEllipsis(true)
            chip.add(label).maxWidth(240f).pad(4f)
            val xBtn = "×".toLabel(fontSize = 18, fontColor = Color(1f, 0.6f, 0.6f, 1f))
            xBtn.touchable = Touchable.enabled
            xBtn.onActivation {
                chips.removeAt(i)
                refreshChipTable(table, chips, chipType)
            }
            chip.add(xBtn).pad(4f)
            chip.pack()
            if (x + chip.width > maxWidth && x > 0f) { table.row(); x = 0f }
            table.add(chip).pad(2f)
            x += chip.width + 4f
        }
        val addBtn = "+".toLabel(fontSize = 20, fontColor = Color(0.5f, 0.8f, 1f, 1f))
        addBtn.touchable = Touchable.enabled
        addBtn.onActivation { showAddChipPopup(chips, chipType, table) }
        if (x + 40f > maxWidth && x > 0f) table.row()
        table.add(addBtn).pad(4f)
    }

    private fun showAddChipPopup(chips: MutableList<String>, chipType: String, chipTable: Table) {
        val available = when (chipType) {
            "unitTypes" -> ModEditorData.getUnitTypes(modFolder)
            "prerequisites" -> items.map { it.name }.filter { it !in chips }
            else -> emptyList()
        }
        val popup = Popup(this)
        popup.add("Add ${chipType.removeSuffix("s")}".toLabel(fontSize = 20)).padBottom(8f).row()
        val search = UncivTextField("Search")
        val resultsTable = Table(BaseScreen.skin)
        val resultsScroll = AutoScrollPane(resultsTable).apply { setOverscroll(false, false) }
        fun refresh(q: String) {
            resultsTable.clear()
            val query = q.lowercase()
            for (name in available) {
                if (query.isNotEmpty() && !name.lowercase().contains(query) && !name.tr().lowercase().contains(query)) continue
                val row = Table(BaseScreen.skin)
                row.defaults().pad(6f)
                val label = name.toLabel(fontSize = 18)
                label.setEllipsis(true)
                row.add(label).growX().left()
                row.touchable = Touchable.enabled
                row.onActivation {
                    chips.add(name)
                    refreshChipTable(chipTable, chips, chipType)
                    popup.close()
                }
                resultsTable.add(row).fillX().pad(2f).row()
            }
            if (resultsTable.children.isEmpty) resultsTable.add("No results".toLabel()).pad(10f).row()
        }
        search.setTextFieldListener { field, _ -> refresh(field.text) }
        popup.add(search).fillX().pad(4f).row()
        popup.add(resultsScroll).size(400f, 300f).pad(4f).row()
        val cancelBtn = "Cancel".toTextButton()
        cancelBtn.onActivation { popup.close() }
        popup.add(cancelBtn).pad(8f)
        popup.open()
        refresh("")
    }

    // ------------------------------------------------------------------
    // 保存 / 验证
    // ------------------------------------------------------------------

    private fun save() {
        if (selectedIndex < 0) return
        val item = current()
        item.name = nameField.text.trim()
        item.raw["name"] = item.name
        item.setInt("column", columnField.text.trim().toIntOrNull())
        item.setInt("row", rowField.text.trim().toIntOrNull())
        item.setStringList("unitTypes", unitTypeChips)
        item.setStringList("prerequisites", prereqChips)
        item.syncUniques()
        saveColors(item.raw)
        val cpEntries = civilopediaEditor.buildEntries()
        if (cpEntries == null) item.raw.remove("civilopediaText") else item.raw["civilopediaText"] = cpEntries
        item.comment = commentArea.text.trim()

        val problems = ModEditorData.validatePromotion(modFolder, item, items)
        val errors = problems.filter { it.second }
        val warnings = problems.filter { !it.second }
        if (errors.isNotEmpty()) {
            showProblemsPopup("Save failed".tr(), errors.map { it.first }, true)
            return
        }
        if (warnings.isNotEmpty()) {
            showProblemsPopup("Problems found".tr(), warnings.map { it.first }, false) { doSave() }
            return
        }
        doSave()
    }

    private fun doSave() {
        ModEditorData.savePromotions(modFolder, items)
        val gameProblems = ModEditorData.runGameValidation(modFolder)
        val filtered = ModEditorData.filterGameProblems(gameProblems, "UnitPromotions.json")
        val errors = filtered.filter { it.second }
        if (errors.isNotEmpty()) {
            ModEditorData.rollbackFile(modFolder, "UnitPromotions.json")
            statusLabel.setText("Save failed (game validation)")
            showProblemsPopup("Game validation found errors".tr(), errors.map { it.first }, true)
            return
        }
        statusLabel.setText("Saved ✓")
    }

    private fun saveColors(raw: LinkedHashMap<String, Any?>) {
        fun parseColor(r: UncivTextField, g: UncivTextField, b: UncivTextField): List<Int>? {
            val rv = r.text.trim().toIntOrNull() ?: return null
            val gv = g.text.trim().toIntOrNull() ?: return null
            val bv = b.text.trim().toIntOrNull() ?: return null
            return listOf(rv.coerceIn(0, 255), gv.coerceIn(0, 255), bv.coerceIn(0, 255))
        }
        val inner = parseColor(innerColorR, innerColorG, innerColorB)
        val outer = parseColor(outerColorR, outerColorG, outerColorB)
        if (inner != null && inner.any { it != 255 }) raw["innerColor"] = inner else raw.remove("innerColor")
        if (outer != null && outer.any { it != 255 }) raw["outerColor"] = outer else raw.remove("outerColor")
    }

    // ------------------------------------------------------------------
    // Uniques 内联编辑
    // ------------------------------------------------------------------

    private fun rebuildUniquesTable() {
        uniquesTable.clear()
        uniquesButtonRow.clear()
        if (current().uniques.isEmpty()) {
            uniquesTable.add("(no uniques)".toLabel(fontSize = 16, fontColor = Color(1f, 1f, 1f, 0.4f)))
                .left().pad(4f).row()
        }
        for ((index, rawString) in current().uniques.withIndex()) {
            val parsed = uniqueCatalog.parseRaw(rawString)
            if (parsed != null) {
                lateinit var editor: UniqueInlineEditor
                editor = UniqueInlineEditor(
                    screen = this, modFolder = modFolder, catalog = uniqueCatalog,
                    unique = parsed.unique, values = parsed.values, conditions = parsed.conditions,
                    onValueChange = { current().uniques[index] = editor.buildRaw() },
                    onStructureChange = {
                        current().uniques[index] = editor.buildRaw()
                        rebuildUniquesTable()
                    },
                    onDuplicate = {
                        val copyValues = parsed.values.toMutableMap()
                        val copyConditions = parsed.conditions
                            .map { (c, v) -> c to v.toMutableMap() }.toMutableList()
                        val newRaw = uniqueCatalog.buildRawString(parsed.unique, copyValues, copyConditions)
                        current().uniques.add(index + 1, newRaw)
                        rebuildUniquesTable()
                    },
                    onDelete = {
                        current().uniques.removeAt(index)
                        rebuildUniquesTable()
                    }
                )
                uniquesTable.add(editor).growX().left().pad(3f, 8f, 3f, 8f).row()
                uniquesTable.add(uniqueSeparatorLine()).growX().height(1f).pad(2f, 8f, 2f, 8f).row()
            } else {
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
                val removeButton = "\u00d7".toTextButton()
                removeButton.onActivation {
                    current().uniques.removeAt(index)
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
                    current().uniques.add(uniqueCatalog.buildRawString(unique, values, emptyList()))
                    rebuildUniquesTable()
                },
                onRawPicked = { text ->
                    current().uniques.add(text)
                    rebuildUniquesTable()
                }
            ))
        }
        uniquesTable.add(addButton).left().pad(4f)
        addRawEditUniquesButton(this, uniquesButtonRow, getUniques = { current().uniques }) { rebuildUniquesTable() }
        uniquesTable.row()
    }

    private fun showUniqueEditor(index: Int, currentRaw: String) {
        val popup = Popup(this)
        popup.add("Edit unique (raw)".toLabel(fontSize = 20)).padBottom(8f).row()
        val textArea = TextArea(currentRaw, BaseScreen.skin)
        popup.add(textArea).growX().height(120f).pad(4f).row()
        popup.add("No quotes needed - they are added automatically when saving".toLabel(fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.5f))).padBottom(8f).row()
        val saveBtn = "Save".toTextButton()
        saveBtn.onActivation {
            val cleaned = textArea.text.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s{2,}"), " ").trim()
            current().uniques[index] = cleaned
            rebuildUniquesTable()
            popup.close()
        }
        popup.add(saveBtn).pad(8f)
        val cancelBtn = "Cancel".toTextButton()
        cancelBtn.onActivation { popup.close() }
        popup.add(cancelBtn).pad(8f)
        popup.open()
    }

    // ------------------------------------------------------------------
    // 增删 / 复制
    // ------------------------------------------------------------------

    private fun addItem() {
        val item = ModObjectData()
        item.name = "New promotion"
        item.raw["name"] = item.name
        items.add(item)
        selectedIndex = items.lastIndex
        refreshList()
        rebuildForm()
    }

    private fun deleteItem() {
        if (selectedIndex < 0) return
        val name = current().name
        val popup = Popup(this)
        popup.add("Are you sure you want to delete [$name]?".tr().toLabel(fontSize = 20)).pad(12f).row()
        popup.addButton("Delete") {
            items.removeAt(selectedIndex)
            selectedIndex = -1
            refreshList()
            formTable.clear()
            popup.close()
        }
        popup.addCloseButton()
        popup.open()
    }

    private fun showCopyPopup(initialSource: String? = null) {
        val sourceRuleset = initialSource?.takeIf { it.isNotBlank() }
            ?: ModEditorData.readBaseRulesetChoice(modFolder).ifBlank { com.unciv.models.metadata.BaseRuleset.Civ_V_GnK.fullName }
        val base = ModEditorData.loadBaseObjects(modFolder, "UnitPromotions.json", sourceRuleset)
        if (base.isEmpty()) {
            showInfoPopup("No promotions found in the base ruleset")
            return
        }
        val popup = Popup(this)
        // 来源规则集选择：切换时重建弹窗加载新来源
        val sourceNames = ModEditorData.getBaseRulesetNames()
        val sourceBox = ModEditorSelectBox(sourceNames, sourceRuleset, searchable = true)
        sourceBox.addListener(object : com.badlogic.gdx.scenes.scene2d.utils.ChangeListener() {
            override fun changed(event: com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                popup.close()
                showCopyPopup(initialSource = sourceRuleset)
            }
        })
        val sourceRow = Table(BaseScreen.skin)
        sourceRow.add("Source ruleset".tr().toLabel(fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.7f))).left().pad(4f)
        sourceRow.add(sourceBox).growX().width(360f).pad(4f)
        popup.add(sourceRow).growX().width(520f).pad(4f).row()
        popup.add("Copy promotion from ruleset".toLabel(fontSize = 22)).colspan(2).row()
        popup.add("Same name will override the original in game".toLabel(fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.6f))).colspan(2).padBottom(8f).row()
        val search = UncivTextField("Search")
        val resultsTable = Table(BaseScreen.skin)
        val resultsScroll = AutoScrollPane(resultsTable).apply { setOverscroll(false, false) }
        fun refresh(q: String) {
            resultsTable.clear()
            val query = q.lowercase()
            for (item in base) {
                if (query.isNotEmpty() && !item.name.lowercase().contains(query) && !item.name.tr().lowercase().contains(query)) continue
                val row = Table(BaseScreen.skin)
                row.defaults().pad(6f)
                val label = item.name.toLabel(fontSize = 18)
                label.setEllipsis(true)
                row.add(label).growX().left()
                row.touchable = Touchable.enabled
                row.onActivation {
                    val copy = ModObjectData()
                    for ((k, v) in item.raw) copy.raw[k] = if (v is List<*>) ArrayList(v) else v
                    copy.name = item.name
                    copy.uniques.clear()
                    copy.uniques.addAll(item.uniques)
                    items.add(copy)
                    selectedIndex = items.lastIndex
                    refreshList()
                    rebuildForm()
                    popup.close()
                }
                resultsTable.add(row).fillX().pad(2f).row()
            }
            if (resultsTable.children.isEmpty) resultsTable.add("No results".toLabel()).pad(10f).row()
        }
        search.setTextFieldListener { field, _ -> refresh(field.text) }
        popup.add(search).fillX().pad(4f).row()
        popup.add(resultsScroll).size(400f, 300f).pad(4f).row()
        val cancelBtn = "Cancel".toTextButton()
        cancelBtn.onActivation { popup.close() }
        popup.add(cancelBtn).pad(8f)
        popup.open()
        refresh("")
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun formField(table: Table, label: String, value: String): UncivTextField {
        val row = Table(BaseScreen.skin)
        row.add(label.toLabel()).left().pad(4f).width(220f)
        val field = UncivTextField("", value)
        row.add(field).growX().minWidth(200f).pad(4f)
        table.add(row).growX().left().pad(4f, 10f, 4f, 10f).row()
        return field
    }

    private fun textAreaField(table: Table, label: String, value: String): TextArea {
        table.add(label.toLabel()).left().pad(4f, 10f, 0f, 10f).row()
        val area = TextArea(value, BaseScreen.skin)
        area.setPrefRows(3f)
        table.add(area).growX().height(80f).pad(4f, 10f, 4f, 10f).row()
        return area
    }

    private fun showProblemsPopup(title: String, problems: List<String>, isError: Boolean, onContinue: (() -> Unit)? = null) {
        val popup = Popup(this)
        popup.add(title.toLabel(fontSize = 22, fontColor = if (isError) Color(1f, 0.4f, 0.4f, 1f) else Color(1f, 0.9f, 0.4f, 1f))).padBottom(8f).row()
        for (p in problems) {
            val lbl = p.toLabel(fontSize = 16)
            lbl.wrap = true
            popup.add(lbl).fillX().pad(2f, 8f, 2f, 8f).row()
        }
        if (onContinue != null) {
            val btn = "Save anyway".toTextButton()
            btn.onActivation { popup.close(); onContinue() }
            popup.add(btn).pad(8f)
        }
        val closeBtn = if (onContinue != null) "Cancel".toTextButton() else "OK".toTextButton()
        closeBtn.onActivation { popup.close() }
        popup.add(closeBtn).pad(8f)
        popup.open()
    }

    private fun showInfoPopup(message: String) {
        val popup = Popup(this)
        popup.add(message.toLabel(fontSize = 18)).pad(12f).row()
        val btn = "OK".toTextButton()
        btn.onActivation { popup.close() }
        popup.add(btn).pad(8f)
        popup.open()
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

    private fun rowBackground() = BaseScreen.skinStrings.getUiBackground(
        "ModEditor/UnitRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        BaseScreen.skinStrings.skinConfig.baseColor)

    private fun selectedRowBackground() = BaseScreen.skinStrings.getUiBackground(
        "ModEditor/UnitRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        Color(0.2f, 0.5f, 0.9f, 1f))
}

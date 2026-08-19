package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextArea
import com.badlogic.gdx.utils.Align
import com.unciv.models.translations.tr
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.extensions.addSeparatorVertical
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import kotlin.math.max

/** 单位名称池编辑器：name / unitNames (list) / uniques / civilopediaText */
class UnitNameGroupsEditorScreen(private val modFolder: FileHandle) : BaseScreen() {

    private val items = ModEditorData.loadUnitNameGroups(modFolder)
    private var selectedIndex = -1
    private val uniqueCatalog = UniqueCatalog.load()

    private val listTable = Table(BaseScreen.skin).apply {
        defaults().expandX().fillX()
        top()   // 列表内容不满时顶部对齐，否则垂直居中（libGDX Table 默认会居中）
    }
    private val formTable = FillWidthTable(BaseScreen.skin)
    private val statusLabel = "".toLabel(fontSize = 16)

    private lateinit var nameField: UncivTextField
    private lateinit var uniquesTable: Table
    private lateinit var uniquesButtonRow: Table
    private lateinit var civilopediaEditor: CivilopediaTextEditor
    private lateinit var commentArea: TextArea
    private lateinit var searchField: UncivTextField
    private var searchQuery = ""

    private val nameChips = mutableListOf<String>()
    private lateinit var namesChipTable: Table

    private fun current() = items[selectedIndex]

    init {
        val root = Table(BaseScreen.skin)
        root.setFillParent(true)
        stage.addActor(root)

        val topBar = Table(BaseScreen.skin)
        val backButton = ("‹ " + "Back".tr()).toTextButton()
        backButton.onActivation { game.popScreen() }
        topBar.add(backButton).pad(8f)
        topBar.add(("UnitNameGroups".tr() + " · UnitNameGroups.json").toLabel(fontSize = 28))
            .padLeft(20f).expandX().left()
        val saveButton = "Save".toTextButton()
        saveButton.onActivation { save() }
        topBar.add(saveButton).pad(8f)
        topBar.add(statusLabel).pad(8f)
        root.add(topBar).fillX().row()

        val leftPanel = Table(BaseScreen.skin)
        val buttonRow = Table(BaseScreen.skin)
        val addButton = "+ New name group".toTextButton()
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
        else formTable.add("No name groups. Click \"+ New name group\" in the top left.".toLabel()).pad(20f).row()
    }

    private fun refreshList() {
        listTable.clear()
        for ((i, item) in items.withIndex()) {
            if (searchQuery.isNotEmpty() && !item.name.lowercase().contains(searchQuery) &&
                !(item.name.tr().lowercase().contains(searchQuery))) continue
            val row = Table(BaseScreen.skin)
            row.defaults().pad(6f)
            row.background = if (i == selectedIndex) selectedRowBackground() else rowBackground()
            val nameLabel = listNameLabel(
                item.name, maxWidth = stage.width * 0.25f - 100f, fontSize = 20)
            row.add(nameLabel).growX().left().maxWidth(stage.width * 0.25f - 100f)
            val nameList = item.getStringList("unitNames")
            val countLabel = "${nameList.size} names".toLabel(fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.5f))
            row.add(countLabel).right().pad(4f)
            row.touchable = com.badlogic.gdx.scenes.scene2d.Touchable.enabled
            row.onActivation { selectedIndex = i; refreshList(); rebuildForm() }
            listTable.add(row).fillX().pad(2f, 6f, 2f, 6f).row()
        }
        if (items.isEmpty()) {
            listTable.add("No name groups yet.".toLabel()).pad(20f).row()
        }
    }

    private fun rebuildForm() {
        formTable.clear()
        if (selectedIndex < 0 || selectedIndex >= items.size) return
        val item = current()

        // 表单头：标题 + Duplicate + Delete（与单位/建筑编辑器一致）
        val header = Table(BaseScreen.skin)
        header.add("Edit name group".toLabel(fontSize = 24)).left().expandX()
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

        // Unit names (chips + bulk edit)
        formTable.add(sectionHeader("Unit names".tr())).fillX().row()
        nameChips.clear()
        nameChips.addAll(item.getStringList("unitNames"))
        namesChipTable = Table(BaseScreen.skin)
        refreshChipTable()

        val namesBox = Table(BaseScreen.skin)
        namesBox.background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/ChipBox", BaseScreen.skinStrings.roundedEdgeRectangleShape,
            Color(1f, 1f, 1f, 0.05f))
        val scroll = AutoScrollPane(namesChipTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        namesBox.add(scroll).grow().pad(6f)
        formTable.add(namesBox).growX().height(200f).left().pad(4f, 10f, 4f, 10f).row()

        // Bulk edit button
        val bulkBtn = "Bulk edit".toTextButton()
        bulkBtn.onActivation { showBulkEdit() }
        formTable.add(bulkBtn).fillX().pad(4f, 10f, 4f, 10f).row()

        // Uniques
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

        civilopediaEditor = CivilopediaTextEditor(
            screen = this,
            getRaw = { item.raw["civilopediaText"] },
            setRaw = { item.raw["civilopediaText"] = it }
        )
        civilopediaEditor.addTo(formTable, "Civilopedia text")
        commentArea = textAreaField(formTable, "Comment", item.comment)
    }

    private fun refreshChipTable() {
        namesChipTable.clear()
        val maxWidth = formAvailableWidth(stage.width, extraDeduction = 40f)
        var x = 0f
        for ((i, name) in nameChips.withIndex()) {
            val chip = Table(BaseScreen.skin)
            chip.background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/Chip", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
                Color(0.25f, 0.4f, 0.55f, 1f))
            val label = name.toLabel(fontSize = 16)
            label.setEllipsis(true)
            chip.add(label).maxWidth(240f).pad(4f)
            val xBtn = "×".toLabel(fontSize = 18, fontColor = Color(1f, 0.6f, 0.6f, 1f))
            xBtn.touchable = com.badlogic.gdx.scenes.scene2d.Touchable.enabled
            xBtn.onActivation {
                nameChips.removeAt(i)
                refreshChipTable()
            }
            chip.add(xBtn).pad(4f)
            chip.pack()
            if (x + chip.width > maxWidth && x > 0f) { namesChipTable.row(); x = 0f }
            namesChipTable.add(chip).pad(2f)
            x += chip.width + 4f
        }
        // Add button
        val addBtn = "+".toLabel(fontSize = 20, fontColor = Color(0.5f, 0.8f, 1f, 1f))
        addBtn.touchable = com.badlogic.gdx.scenes.scene2d.Touchable.enabled
        addBtn.onActivation { showAddNamePopup() }
        if (x + 40f > maxWidth && x > 0f) namesChipTable.row()
        namesChipTable.add(addBtn).pad(4f)
    }

    private fun showAddNamePopup() {
        val popup = Popup(this)
        popup.add("Add name".toLabel(fontSize = 20)).padBottom(8f).row()
        val field = UncivTextField("Name")
        popup.add(field).fillX().pad(4f).row()
        val btnRow = Table(BaseScreen.skin)
        val saveBtn = "Add".toTextButton()
        saveBtn.onActivation {
            val name = field.text.trim()
            if (name.isNotBlank()) {
                nameChips.add(name)
                refreshChipTable()
            }
            popup.close()
        }
        btnRow.add(saveBtn).pad(8f)
        val cancelBtn = "Cancel".toTextButton()
        cancelBtn.onActivation { popup.close() }
        btnRow.add(cancelBtn).pad(8f)
        popup.add(btnRow)
        popup.open()
    }

    private fun showBulkEdit() {
        val popup = Popup(this)
        popup.add("Bulk edit names".toLabel(fontSize = 20)).padBottom(4f).row()
        popup.add("One entry per line".toLabel(fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.6f))).padBottom(8f).row()
        val area = TextArea(nameChips.joinToString("\n"), BaseScreen.skin)
        popup.add(area).size(400f, 300f).pad(4f).row()
        val btnRow = Table(BaseScreen.skin)
        val saveBtn = "Save".toTextButton()
        saveBtn.onActivation {
            nameChips.clear()
            nameChips.addAll(area.text.lines().map { it.trim() }.filter { it.isNotBlank() })
            refreshChipTable()
            popup.close()
        }
        btnRow.add(saveBtn).pad(8f)
        val cancelBtn = "Cancel".toTextButton()
        cancelBtn.onActivation { popup.close() }
        btnRow.add(cancelBtn).pad(8f)
        popup.add(btnRow)
        popup.open()
    }

    private fun save() {
        if (selectedIndex < 0) return
        val item = current()
        item.name = nameField.text.trim()
        item.raw["name"] = item.name
        item.raw["unitNames"] = nameChips.toList()
        item.syncUniques()
        val cpEntries = civilopediaEditor.buildEntries()
        if (cpEntries == null) item.raw.remove("civilopediaText") else item.raw["civilopediaText"] = cpEntries
        item.comment = commentArea.text.trim()

        val problems = ModEditorData.validateUnitNameGroup(modFolder, item, items)
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
        ModEditorData.saveUnitNameGroups(modFolder, items)
        val gameProblems = ModEditorData.runGameValidation(modFolder)
        val filtered = ModEditorData.filterGameProblems(gameProblems, "UnitNameGroups.json")
        val errors = filtered.filter { it.second }
        if (errors.isNotEmpty()) {
            ModEditorData.rollbackFile(modFolder, "UnitNameGroups.json")
            statusLabel.setText("Save failed (game validation)")
            showProblemsPopup("Game validation found errors".tr(), errors.map { it.first }, true)
            return
        }
        statusLabel.setText("Saved ✓")
    }

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

    private fun addItem() {
        val item = ModObjectData()
        item.name = "New name group"
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
        val base = ModEditorData.loadBaseObjects(modFolder, "UnitNameGroups.json", sourceRuleset)
        if (base.isEmpty()) {
            showInfoPopup("No name groups found in the base ruleset")
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
        popup.add("Copy name group from ruleset".toLabel(fontSize = 22)).colspan(2).row()
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
                row.touchable = com.badlogic.gdx.scenes.scene2d.Touchable.enabled
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
        "ModEditor/UnitRow", BaseScreen.skinStrings.roundedEdgeRectangleShape,
        BaseScreen.skinStrings.skinConfig.baseColor)

    private fun selectedRowBackground() = BaseScreen.skinStrings.getUiBackground(
        "ModEditor/UnitRow", BaseScreen.skinStrings.roundedEdgeRectangleShape,
        Color(0.2f, 0.5f, 0.9f, 1f))
}

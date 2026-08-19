package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextArea
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.unciv.models.translations.tr
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

/** Difficulties 编辑器：平铺列表 + 表单（2026-08-19，UI 参照单位晋升） */
class DifficultiesEditorScreen(private val modFolder: FileHandle) : BaseScreen() {

    private val items = ModEditorData.loadDifficulties(modFolder)
    private var selectedIndex = -1
    private val uniqueCatalog = UniqueCatalog.load()

    private val listTable = Table(BaseScreen.skin).apply {
        defaults().expandX().fillX()
        top()
    }
    private val formTable = FillWidthTable(BaseScreen.skin)
    private val statusLabel = "".toLabel(fontSize = 16)
    private lateinit var searchField: UncivTextField
    private var searchQuery = ""

    // 表单控件
    private lateinit var nameField: UncivTextField
    private lateinit var aiDifficultyBox: ModEditorSelectBox
    private lateinit var uniquesTable: Table
    private lateinit var uniquesButtonRow: Table
    private lateinit var commentArea: TextArea
    private var selectedRowNameLabel: Label? = null
    private val floatFields = LinkedHashMap<String, UncivTextField>()
    private val intFields = LinkedHashMap<String, UncivTextField>()
    private val listFields = LinkedHashMap<String, ModEditorListSection>()

    private fun current(): ModObjectData = items[selectedIndex]

    init {
        val root = Table(BaseScreen.skin)
        root.setFillParent(true)
        stage.addActor(root)

        val topBar = Table(BaseScreen.skin)
        val backButton = ("‹ " + "Back".tr()).toTextButton()
        backButton.onActivation { game.popScreen() }
        topBar.add(backButton).pad(8f)
        topBar.add(("Difficulties".tr() + " · Difficulties.json").toLabel(fontSize = 28))
            .padLeft(20f).expandX().left()
        val saveButton = "Save".toTextButton()
        saveButton.onActivation { save() }
        topBar.add(saveButton).pad(8f)
        topBar.add(statusLabel).pad(8f)
        root.add(topBar).fillX().row()

        // 左侧：按钮行 + 搜索 + 平铺列表
        val leftPanel = Table(BaseScreen.skin)
        val buttonRow = Table(BaseScreen.skin)
        val addButton = "+ New difficulty".toTextButton()
        addButton.onActivation { addItem() }
        buttonRow.add(addButton).left().pad(6f)
        val copyButton = "Copy difficulty from ruleset".toTextButton()
        copyButton.onActivation { showCopyFromRulesetPopup() }
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

        // 右侧表单
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
        if (items.isNotEmpty()) select(0)
        else formTable.add("No difficulties. Click \"+ New difficulty\" in the top left.".tr().toLabel()).pad(20f).row()
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

    private fun rowBackground() = BaseScreen.skinStrings.getUiBackground(
        "ModEditor/DiffRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        BaseScreen.skinStrings.skinConfig.baseColor)

    private fun selectedRowBackground() = BaseScreen.skinStrings.getUiBackground(
        "ModEditor/DiffRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        Color(0.2f, 0.5f, 0.9f, 1f))

    // ------------------------------------------------------------------
    // 列表（平铺）
    // ------------------------------------------------------------------

    private fun refreshList() {
        listTable.clear()
        for ((i, item) in items.withIndex()) {
            if (searchQuery.isNotEmpty() && !item.name.lowercase().contains(searchQuery) &&
                !(item.name.tr().lowercase().contains(searchQuery))) continue
            val isSelected = i == selectedIndex
            val row = Table(BaseScreen.skin)
            row.defaults().pad(6f)
            row.background = if (isSelected) selectedRowBackground() else rowBackground()
            val nameLabel = listNameLabel(
                item.name.ifBlank { "(unnamed)".tr() },
                maxWidth = stage.width * 0.25f - 100f,
                fontSize = 20,
                fontColor = if (isSelected) Color.WHITE else Color(1f, 1f, 1f, 0.85f))
            if (isSelected) selectedRowNameLabel = nameLabel
            row.add(nameLabel).growX().left().maxWidth(stage.width * 0.25f - 60f)
            row.touchable = Touchable.enabled
            row.onActivation { selectedIndex = i; refreshList(); rebuildForm() }
            listTable.add(row).fillX().pad(2f, 6f, 2f, 6f).row()
        }
        if (items.isEmpty()) {
            listTable.add("No difficulties yet.".tr().toLabel(fontSize = 16, fontColor = Color(1f, 1f, 1f, 0.4f))).pad(20f).row()
        }
    }

    private fun select(index: Int) {
        selectedIndex = index
        refreshList()
        rebuildForm()
    }

    private fun addItem() {
        val item = ModObjectData()
        item.name = nextName()
        items.add(item)
        select(items.lastIndex)
    }

    private fun nextName(): String {
        val existing = items.map { it.name }.toSet()
        var i = 1
        while (("New difficulty" + if (i == 1) "" else " $i") in existing) i++
        return "New difficulty" + if (i == 1) "" else " $i"
    }

    // ------------------------------------------------------------------
    // 表单
    // ------------------------------------------------------------------

    private fun rebuildForm() {
        formTable.clear()
        floatFields.clear()
        intFields.clear()
        listFields.clear()
        if (::aiDifficultyBox.isInitialized) aiDifficultyBox.disposeFloating()
        val item = current()

        val header = Table(BaseScreen.skin)
        header.add("Edit difficulty".tr().toLabel(fontSize = 24)).left().expandX()
        val copyButton = "Duplicate".toTextButton()
        copyButton.onActivation {
            val copy = ModObjectData()
            copy.name = nextName()
            copy.comment = item.comment
            item.raw.forEach { (k, v) -> copy.raw[k] = v }
            copy.uniques.addAll(item.uniques)
            items.add(copy)
            select(items.lastIndex)
        }
        header.add(copyButton).pad(4f)
        val deleteButton = "Delete".toTextButton()
        deleteButton.onActivation { confirmDelete() }
        header.add(deleteButton).pad(4f)
        formTable.add(header).fillX().row()

        formTable.add(sectionHeader("Basic info".tr())).fillX().row()

        nameField = UncivTextField("Difficulty name (required)", item.name)
        nameField.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                selectedRowNameLabel?.setText(nameField.text.ifBlank { "(unnamed)".tr() })
            }
        })
        addFieldRow("Name", nameField)

        aiDifficultyBox = searchableBox(ModEditorData.getDifficulties(modFolder), item.getString("aiDifficultyLevel"))
        aiDifficultyBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                item.setString("aiDifficultyLevel", aiDifficultyBox.selected?.value?.takeUnless { it == "(None)" })
            }
        })
        addFieldRow("AI difficulty level", aiDifficultyBox)

        // 玩家侧数值（整数）
        formTable.add(sectionHeader("Player modifiers".tr())).fillX().row()
        var row = Table(BaseScreen.skin)
        for (key in listOf("baseHappiness", "extraHappinessPerLuxury", "unitSupplyBase", "unitSupplyPerCity",
            "barbarianSpawnDelay", "turnBarbariansCanEnterPlayerTiles", "clearBarbarianCampReward")) {
            val field = intField(item.getIntText(key))
            intFields[key] = field
            addStatPair(row, key, field)
            if (intFields.size % 3 == 0 && key != "clearBarbarianCampReward") {
                formTable.add(row).growX().left().row()
                row = Table(BaseScreen.skin)
            }
        }
        formTable.add(row).growX().left().row()

        // 玩家侧数值（浮点）
        var frow = Table(BaseScreen.skin)
        for (key in listOf("researchCostModifier", "unitCostModifier", "buildingCostModifier", "policyCostModifier",
            "unhappinessModifier", "barbarianBonus")) {
            val field = floatField(item.getFloatText(key))
            floatFields[key] = field
            addStatPair(frow, key, field)
            if (floatFields.size % 3 == 0 && key != "barbarianBonus") {
                formTable.add(frow).growX().left().row()
                frow = Table(BaseScreen.skin)
            }
        }
        formTable.add(frow).growX().left().row()

        // AI 侧数值（浮点）
        formTable.add(sectionHeader("AI modifiers".tr())).fillX().row()
        var arow = Table(BaseScreen.skin)
        for (key in listOf("aiCityGrowthModifier", "aiUnitCostModifier", "aiBuildingCostModifier", "aiWonderCostModifier",
            "aiBuildingMaintenanceModifier", "aiUnitMaintenanceModifier", "aiUnhappinessModifier")) {
            val field = floatField(item.getFloatText(key))
            floatFields[key] = field
            addStatPair(arow, key, field)
            if (floatFields.size % 3 == 0 && key != "aiUnhappinessModifier") {
                formTable.add(arow).growX().left().row()
                arow = Table(BaseScreen.skin)
            }
        }
        formTable.add(arow).growX().left().row()
        val aiSupplyRow = Table(BaseScreen.skin)
        val aiSupplyField = intField(item.getIntText("aiUnitSupplyModifier"))
        intFields["aiUnitSupplyModifier"] = aiSupplyField
        addStatPair(aiSupplyRow, "aiUnitSupplyModifier", aiSupplyField)
        formTable.add(aiSupplyRow).growX().left().row()

        // 起始单位 / 免费科技（chips 多选）
        formTable.add(sectionHeader("Starting units & free techs".tr())).fillX().row()
        listFields["playerBonusStartingUnits"] = ModEditorListSection(
            screen = this,
            label = "playerBonusStartingUnits",
            options = { ModEditorData.getUnits(modFolder) },
            getValues = { item.getStringList("playerBonusStartingUnits") },
            setValues = { item.setStringList("playerBonusStartingUnits", it) }
        ).also { it.addTo(formTable) }
        listFields["aiMajorCivBonusStartingUnits"] = ModEditorListSection(
            screen = this,
            label = "aiMajorCivBonusStartingUnits",
            options = { ModEditorData.getUnits(modFolder) },
            getValues = { item.getStringList("aiMajorCivBonusStartingUnits") },
            setValues = { item.setStringList("aiMajorCivBonusStartingUnits", it) }
        ).also { it.addTo(formTable) }
        listFields["aiCityStateBonusStartingUnits"] = ModEditorListSection(
            screen = this,
            label = "aiCityStateBonusStartingUnits",
            options = { ModEditorData.getUnits(modFolder) },
            getValues = { item.getStringList("aiCityStateBonusStartingUnits") },
            setValues = { item.setStringList("aiCityStateBonusStartingUnits", it) }
        ).also { it.addTo(formTable) }
        listFields["aiFreeTechs"] = ModEditorListSection(
            screen = this,
            label = "aiFreeTechs",
            options = { ModEditorData.getTechs(modFolder) },
            getValues = { item.getStringList("aiFreeTechs") },
            setValues = { item.setStringList("aiFreeTechs", it) }
        ).also { it.addTo(formTable) }

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
        val uniqueHint = "Will be treated as part of GlobalUniques when this difficulty is chosen".tr()
            .toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f))
        uniqueHint.wrap = true
        formTable.add(uniqueHint).growX().left().pad(0f, 8f, 6f, 8f).row()

        formTable.add(sectionHeader("Comment".tr())).fillX().row()
        commentArea = TextArea(item.comment, BaseScreen.skin)
        formTable.add(commentArea).growX().height(100f).left().pad(6f).row()
    }

    private fun addFieldRow(labelKey: String, widget: Actor) {
        val row = Table(BaseScreen.skin)
        row.add(labelKey.tr().toLabel()).left().pad(4f).width(180f)
        row.add(widget).growX().minWidth(160f).pad(4f)
        formTable.add(row).growX().left().row()
    }

    private fun addStatPair(row: Table, label: String, field: UncivTextField) {
        row.add(label.tr().toLabel()).left().pad(4f).width(170f)
        row.add(field).growX().minWidth(70f).pad(4f)
    }

    private fun searchableBox(values: List<String>, current: String?): ModEditorSelectBox {
        val items = mutableListOf("(None)")
        items.addAll(values)
        val cur = current ?: ""
        if (cur.isNotBlank() && cur !in items) items.add(cur)
        return ModEditorSelectBox(items, if (cur.isBlank()) "(None)" else cur, searchable = true)
    }

    private fun intField(value: String): UncivTextField {
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

    private fun floatField(value: String): UncivTextField {
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

    // ------------------------------------------------------------------
    // 词条
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
                        current().uniques.add(index + 1,
                            uniqueCatalog.buildRawString(parsed.unique, copyValues, copyConditions))
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
                    setFontScale(16f / com.unciv.ui.components.fonts.Fonts.ORIGINAL_FONT_SIZE)
                    setAlignment(com.badlogic.gdx.utils.Align.left)
                    setColor(Color(1f, 1f, 1f, 0.8f))
                    wrap = true
                }
                row.add(label).growX().minWidth(420f).left().pad(4f)
                val editButton = "Edit".toTextButton()
                editButton.onActivation { showUniqueEditor(index, rawString) }
                row.add(editButton).pad(4f)
                val removeButton = "×".toTextButton()
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
        uniquesButtonRow.add(addButton).left().pad(6f)
        addRawEditUniquesButton(this, uniquesButtonRow, getUniques = { current().uniques }) { rebuildUniquesTable() }
        uniquesTable.row()
    }

    private fun showUniqueEditor(index: Int?, existing: String?) {
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        popup.add("Unique (raw mode)".tr().toLabel(fontSize = 22)).pad(10f).row()
        val textArea = TextArea(existing ?: "", BaseScreen.skin)
        popup.add(textArea).width(560f).height(160f).pad(6f).row()
        popup.addButton("Save".tr()) {
            val text = textArea.text.replace('\n', ' ').replace('\r', ' ')
                .replace(Regex("\\s{2,}"), " ").trim()
            if (text.isNotEmpty()) {
                if (index == null) current().uniques.add(text)
                else if (index < current().uniques.size) current().uniques[index] = text
            } else if (index != null && index < current().uniques.size) {
                current().uniques.removeAt(index)
            }
            popup.close()
            rebuildUniquesTable()
        }
        popup.addCloseButton()
        popup.open()
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    private fun save() {
        val item = current()
        val newName = nameField.text.trim()
        if (newName.isBlank()) { showMessage("Difficulty name cannot be empty".tr()); return }
        item.name = newName
        item.setString("name", newName)
        for ((key, field) in floatFields) {
            val text = field.text.trim()
            if (text.isBlank()) item.raw.remove(key)
            else {
                val v = text.toDoubleOrNull()
                if (v == null) { showMessage("Invalid number:".tr() + " " + text); return }
                item.setNumber(key, v)
            }
        }
        for ((key, field) in intFields) {
            val text = field.text.trim()
            if (text.isBlank()) item.raw.remove(key)
            else {
                val v = text.toIntOrNull()
                if (v == null) { showMessage("Invalid number:".tr() + " " + text); return }
                item.setInt(key, v)
            }
        }
        item.comment = commentArea.text
        item.syncUniques()

        val problems = ModEditorData.validateDifficulty(modFolder, item, items)
        val errors = problems.filter { it.second }
        if (errors.isNotEmpty()) { showProblemsPopup(problems, onSaveAnyway = null); return }
        if (problems.isNotEmpty()) { showProblemsPopup(problems) { doSave() }; return }
        doSave()
    }

    private fun doSave() {
        ModEditorData.saveDifficulties(modFolder, items)
        val gameProblems = ModEditorData.filterGameProblems(
            ModEditorData.runGameValidation(modFolder), "Difficulties.json")
        val errors = gameProblems.filter { it.second }
        if (errors.isNotEmpty()) {
            ModEditorData.rollbackFile(modFolder, "Difficulties.json")
            statusLabel.setText("Save failed".tr())
            showGameProblemsPopup(gameProblems, saved = false)
            return
        }
        statusLabel.setText("Saved".tr())
        refreshList()
        if (gameProblems.isNotEmpty()) showGameProblemsPopup(gameProblems, saved = true)
    }

    private fun showCopyFromRulesetPopup(initialSource: String? = null) {
        val sourceRuleset = initialSource?.takeIf { it.isNotBlank() }
            ?: ModEditorData.readBaseRulesetChoice(modFolder).ifBlank { com.unciv.models.metadata.BaseRuleset.Civ_V_GnK.fullName }
        val baseItems = ModEditorData.loadBaseObjects(modFolder, "Difficulties.json", sourceRuleset)
        if (baseItems.isEmpty()) { showMessage("No difficulties found in the base ruleset".tr()); return }
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        // 来源规则集选择：切换时重建弹窗加载新来源
        val sourceNames = ModEditorData.getBaseRulesetNames()
        val sourceBox = ModEditorSelectBox(sourceNames, sourceRuleset, searchable = true)
        sourceBox.addListener(object : com.badlogic.gdx.scenes.scene2d.utils.ChangeListener() {
            override fun changed(event: com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                popup.close()
                showCopyFromRulesetPopup(initialSource = sourceRuleset)
            }
        })
        val sourceRow = Table(BaseScreen.skin)
        sourceRow.add("Source ruleset".tr().toLabel(fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.7f))).left().pad(4f)
        sourceRow.add(sourceBox).growX().width(360f).pad(4f)
        popup.add(sourceRow).growX().width(520f).pad(4f).row()
        popup.add("Copy difficulty from ruleset".tr().toLabel(fontSize = 20)).pad(8f).row()
        val searchField = UncivTextField("Search")
        popup.add(searchField).growX().width(520f).pad(6f).row()
        val listTable = Table(BaseScreen.skin)
        val listScroll = AutoScrollPane(listTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        popup.add(listScroll).grow().width(520f).height(380f).pad(6f).row()

        fun refresh(query: String) {
            listTable.clear()
            val q = query.trim().lowercase()
            var shown = 0
            for (base in baseItems) {
                if (q.isNotEmpty() && !base.name.lowercase().contains(q) &&
                    !base.name.tr().lowercase().contains(q)) continue
                val row = Table(BaseScreen.skin)
                row.background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/SearchRow", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                    BaseScreen.skinStrings.skinConfig.baseColor)
                val baseLbl = bilingualUniqueLabel(base.name, base.name.tr(), 15f)
                baseLbl.wrap = true
                row.add(baseLbl).growX().left().pad(6f, 8f, 6f, 8f)
                row.touchable = Touchable.enabled
                row.onActivation {
                    val copy = ModObjectData()
                    base.raw.forEach { (k, v) -> copy.raw[k] = v }
                    copy.name = base.name
                    val uniques = base.raw["uniques"]
                    if (uniques is List<*>) copy.uniques.addAll(uniques.filterIsInstance<String>())
                    items.add(copy)
                    popup.close()
                    select(items.lastIndex)
                    statusLabel.setText("Copied".tr() + ": " + copy.name)
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

    private fun showGameProblemsPopup(problems: List<Triple<String, Boolean, String?>>, saved: Boolean) {
        val popup = Popup(this)
        popup.add((if (saved) "Saved. Game check has warnings:" else "Game check found errors. Save was rolled back.").tr()
            .toLabel(fontSize = 20,
                fontColor = if (saved) Color(1f, 0.9f, 0.55f, 1f) else Color(1f, 0.45f, 0.45f, 1f))).pad(10f).row()
        for ((message, isError, _) in problems) {
            val label = message.toLabel(fontSize = 14,
                fontColor = if (isError) Color(1f, 0.45f, 0.45f, 1f) else Color(1f, 0.9f, 0.55f, 1f))
            label.wrap = true
            popup.add(label).growX().left().pad(2f, 10f, 2f, 10f).row()
        }
        popup.addCloseButton()
        popup.open()
    }

    private fun showProblemsPopup(problems: List<Pair<String, Boolean>>, onSaveAnyway: (() -> Unit)?) {
        val popup = Popup(this)
        popup.add("Problems found".tr().toLabel(fontSize = 22,
            fontColor = Color(1f, 0.75f, 0.4f, 1f))).pad(10f).row()
        for ((message, isError) in problems) {
            val label = message.tr().toLabel(fontSize = 15,
                fontColor = if (isError) Color(1f, 0.45f, 0.45f, 1f) else Color(1f, 0.9f, 0.55f, 1f))
            label.wrap = true
            popup.add(label).growX().left().pad(2f, 10f, 2f, 10f).row()
        }
        if (onSaveAnyway != null) {
            popup.addButton("Save anyway".tr()) {
                popup.close()
                onSaveAnyway()
            }
        }
        popup.addCloseButton()
        popup.open()
    }

    private fun confirmDelete() {
        val item = current()
        val popup = Popup(this)
        popup.add("Are you sure you want to delete [${item.name.ifBlank { "(unnamed)".tr() }}]?".tr()
            .toLabel(fontSize = 20)).pad(12f).row()
        popup.addButton("Delete".tr()) {
            items.removeAt(selectedIndex)
            popup.close()
            if (items.isEmpty()) {
                selectedIndex = -1
                refreshList()
                formTable.clear()
                formTable.add("No difficulties. Click \"+ New difficulty\" in the top left.".tr().toLabel()).pad(20f).row()
            } else {
                select(minOf(selectedIndex, items.lastIndex))
            }
        }
        popup.addCloseButton()
        popup.open()
    }

    private fun showMessage(message: String) {
        val popup = Popup(this)
        popup.add(message.toLabel()).pad(12f).row()
        popup.addCloseButton()
        popup.open()
    }
}

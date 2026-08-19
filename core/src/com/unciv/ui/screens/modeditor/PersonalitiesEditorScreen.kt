package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.addSeparatorVertical
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.badlogic.gdx.utils.Align
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import kotlin.math.max

/** AI 性格编辑器：name / preferredVictoryType / 15 项 stats+behaviors / priorities / uniques / civilopediaText */
class PersonalitiesEditorScreen(private val modFolder: FileHandle) : BaseScreen() {

    private val items = ModEditorData.loadPersonalities(modFolder)
    private var selectedIndex = -1
    private val uniqueCatalog = UniqueCatalog.load()

    private val listTable = Table(BaseScreen.skin).apply {
        defaults().expandX().fillX()
        top()   // 列表内容不满时顶部对齐，否则垂直居中（libGDX Table 默认会居中）
    }
    private val formTable = FillWidthTable(BaseScreen.skin)
    private val statusLabel = "".toLabel(fontSize = 16)

    private lateinit var searchField: UncivTextField
    private var searchQuery = ""

    // 表单控件引用（rebuildForm 重建）
    private val statFields = mutableMapOf<String, UncivTextField>()

    private fun current() = items[selectedIndex]

    // ── stats + behaviors 字段列表 ──
    private val statsFields = listOf("production", "food", "gold", "science", "culture", "happiness", "faith")
    private val behaviorFields = listOf("military", "aggressive", "declareWar", "commerce", "diplomacy", "loyal", "expansion", "denounceWillingness")

    init {
        val root = Table(BaseScreen.skin)
        root.setFillParent(true)
        stage.addActor(root)

        // ── 顶栏 ──
        val topBar = Table(BaseScreen.skin)
        val backButton = ("‹ " + "Back".tr()).toTextButton()
        backButton.onActivation { game.popScreen() }
        topBar.add(backButton).pad(8f)
        topBar.add(("Personalities".tr() + " · Personalities.json").toLabel(fontSize = 28))
            .padLeft(20f).expandX().left()
        val saveButton = "Save".toTextButton()
        saveButton.onActivation { save() }
        topBar.add(saveButton).pad(8f)
        topBar.add(statusLabel).pad(8f)
        root.add(topBar).fillX().row()

        // ── 左面板 ──
        val leftPanel = Table(BaseScreen.skin)
        val buttonRow = Table(BaseScreen.skin)
        val addButton = "+ New personality".toTextButton()
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

        // ── 右面板（不用 AutoScrollPane，避免表单宽度受限）──
        val rightScroll = AutoScrollPane(formTable).apply {
            setOverscroll(false, false)
            setScrollingDisabled(true, false)
        }
        // 确保 formTable 填满 AutoScrollPane 视口宽度
        formTable.defaults().expandX().fillX()

        val body = Table(BaseScreen.skin)
        body.add(leftPanel).width(max(280f, stage.width / 4)).growY().pad(4f)
        body.addSeparatorVertical(ImageGetter.CHARCOAL, 2f)
        body.add(rightScroll).expand().grow().pad(4f)
        root.add(body).grow()

        refreshList()
        if (items.isNotEmpty()) { selectedIndex = 0; rebuildForm() }
        else {
            val emptyLabel = "No personalities. Click \"+ New personality\" in the top left.".toLabel(fontSize = 18, fontColor = Color(1f, 1f, 1f, 0.5f))
            emptyLabel.setAlignment(Align.center)
            formTable.add(emptyLabel).center().expand().fillX().row()
        }
    }

    // ── 列表 ──

    private fun refreshList() {
        listTable.clear()
        for ((i, item) in items.withIndex()) {
            if (searchQuery.isNotEmpty() && !item.name.lowercase().contains(searchQuery) &&
                !(item.name.tr().lowercase().contains(searchQuery))) continue
            val isSelected = i == selectedIndex
            val row = Table(BaseScreen.skin)
            row.defaults().pad(12f)
            row.background = if (isSelected) selectedRowBackground() else rowBackground()
            if (isSelected) {
                val indicator = Table(BaseScreen.skin).apply {
                    background = BaseScreen.skinStrings.getUiBackground(
                        "ModEditor/PersonalitySelIndicator", null, Color(0.55f, 0.85f, 1f, 1f))
                }
                indicator.setSize(3f, 1f)
                row.add(indicator).width(3f).growY().pad(0f)
            }
            val nameLabel = listNameLabel(
                item.name,
                maxWidth = stage.width * 0.25f - 100f,
                fontSize = if (isSelected) 20 else 18,
                fontColor = if (isSelected) Color.WHITE else Color(1f, 1f, 1f, 0.85f))
            row.add(nameLabel).left().expandX().maxWidth(stage.width * 0.25f - 100f).pad(11f, 12f, 11f, 12f)
            row.touchable = Touchable.enabled
            row.onActivation { selectedIndex = i; refreshList(); rebuildForm() }
            listTable.add(row).fillX().pad(2f, 6f, 2f, 6f).row()
        }
        if (items.isEmpty()) {
            listTable.add("No personalities yet.".toLabel(fontSize = 16, fontColor = Color(1f, 1f, 1f, 0.4f))).center().expand().pad(20f).row()
        }
    }

    // ── 表单 ──

    private fun rebuildForm() {
        formTable.clear()
        statFields.clear()
        if (selectedIndex < 0 || selectedIndex >= items.size) return
        val item = current()

        // 表头
        val header = Table(BaseScreen.skin)
        header.add("Edit personality".toLabel(fontSize = 24)).left().expandX()
        val copyButton = "Duplicate".toTextButton()
        copyButton.onActivation {
            val copy = ModObjectData()
            copy.name = item.name + " copy"
            copy.comment = item.comment
            item.raw.forEach { (k, v) -> copy.raw[k] = v }
            copy.uniques.addAll(item.uniques)
            items.add(copy)
            selectedIndex = items.lastIndex
            refreshList(); rebuildForm()
        }
        header.add(copyButton).pad(4f)
        val deleteButton = "Delete".toTextButton()
        deleteButton.onActivation { deleteItem() }
        header.add(deleteButton).pad(4f)
        formTable.add(header).fillX().row()

        // ── 基本信息（name + victoryType 一行两个）──
        formTable.add(sectionHeader("Basic info")).fillX().row()

        val nameField = UncivTextField("", item.name)
        nameField.setTextFieldListener { field, _ ->
            item.name = field.text; item.setString("name", field.text); refreshList()
        }

        val victoryTypes = ModEditorData.getVictoryTypes(modFolder).toMutableList()
        val curVictory = item.getString("preferredVictoryType")
        if (curVictory.isNotBlank() && curVictory !in victoryTypes) victoryTypes.add(0, curVictory)
        val victoryItems = mutableListOf("(None)")
        victoryItems.addAll(victoryTypes)
        val victoryBox = ModEditorSelectBox(victoryItems, if (curVictory.isBlank()) "(None)" else curVictory, searchable = true)
        victoryBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                val selected = victoryBox.selected?.value
                val value = if (selected == "(None)") "" else (selected ?: "")
                item.setString("preferredVictoryType", value)
            }
        })

        val basicRow = Table(BaseScreen.skin)
        addFieldPair(basicRow, "Name", nameField)
        addFieldPair(basicRow, "Preferred victory type", victoryBox)
        formTable.add(basicRow).growX().left().row()

        // ── Stats（7 项产出偏好，每行 4 个）──
        formTable.add(sectionHeader("Stats focus")).fillX().row()
        val statsRow1 = Table(BaseScreen.skin)
        for (field in listOf("production", "food", "gold", "science")) {
            addFloatField(statsRow1, field, item)
        }
        formTable.add(statsRow1).growX().left().row()
        val statsRow2 = Table(BaseScreen.skin)
        for (field in listOf("culture", "happiness", "faith")) {
            addFloatField(statsRow2, field, item)
        }
        formTable.add(statsRow2).growX().left().row()

        // ── Behaviors（8 项行为偏好，每行 4 个）──
        formTable.add(sectionHeader("Behavior focus")).fillX().row()
        val behRow1 = Table(BaseScreen.skin)
        for (field in listOf("military", "aggressive", "declareWar", "commerce")) {
            addFloatField(behRow1, field, item)
        }
        formTable.add(behRow1).growX().left().row()
        val behRow2 = Table(BaseScreen.skin)
        for (field in listOf("diplomacy", "loyal", "expansion", "denounceWillingness")) {
            addFloatField(behRow2, field, item)
        }
        formTable.add(behRow2).growX().left().row()

        // ── Priorities（政策分支优先级）──
        formTable.add(sectionHeader("Policy priorities")).fillX().row()
        buildPrioritiesSection(item)

        // ── Uniques ──
        formTable.add(sectionHeader("Uniques")).fillX().row()
        buildUniquesSection(item)

        // ── Civilopedia text ──
        val civEditor = CivilopediaTextEditor(
            screen = this,
            getRaw = { item.raw["civilopediaText"] },
            setRaw = { item.raw["civilopediaText"] = it }
        )
        civEditor.addTo(formTable, "Civilopedia text")

        // ── Comment ──
        formTable.add(sectionHeader("Comment")).fillX().row()
        val commentArea = com.badlogic.gdx.scenes.scene2d.ui.TextField(item.comment, BaseScreen.skin)
        commentArea.setTextFieldListener { field, _ -> item.comment = field.text }
        formTable.add(commentArea).growX().pad(4f, 8f, 4f, 8f).row()
    }

    /** JSON 字段名 → 显示标签翻译 key（行为偏好各属性） */
    private val fieldLabels = mapOf(
        "production" to "Production", "food" to "Food", "gold" to "Gold",
        "science" to "Science", "culture" to "Culture", "happiness" to "Happiness",
        "faith" to "Faith",
        "military" to "Military", "aggressive" to "Aggressive", "declareWar" to "Declare war",
        "commerce" to "Commerce", "diplomacy" to "Diplomacy", "loyal" to "Loyal",
        "expansion" to "Expansion", "denounceWillingness" to "Denounce willingness"
    )

    /** 浮点数输入字段（0-10，默认 5）— 放到已有行中 */
    private fun addFloatField(row: Table, key: String, item: ModObjectData) {
        val currentValue = (item.raw[key] as? Number)?.toFloat()
        val field = UncivTextField("", if (currentValue != null) {
            if (currentValue % 1f == 0f) currentValue.toInt().toString() else currentValue.toString()
        } else "5")
        field.textFieldFilter = TextField.TextFieldFilter { _, c -> c in "0123456789." }
        field.setTextFieldListener { f, _ ->
            val value = f.text.trim().toFloatOrNull()
            if (value != null && value in 0f..10f) {
                item.raw[key] = value
            }
        }
        statFields[key] = field
        row.add((fieldLabels[key] ?: key).tr().toLabel()).left().pad(4f).width(112f)
        row.add(field).growX().minWidth(80f).pad(4f)
    }

    /** 标签 + 控件一对，放到已有行中 */
    private fun addFieldPair(row: Table, label: String, widget: Actor) {
        row.add(label.tr().toLabel()).left().pad(4f).width(112f)
        row.add(widget).growX().minWidth(160f).pad(4f)
    }

    /** 政策分支优先级编辑区 */
    private fun buildPrioritiesSection(item: ModObjectData) {
        val priorities = item.raw["priorities"]
        val map = if (priorities is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            priorities as Map<String, Any?>
        } else emptyMap()

        val prioTable = Table(BaseScreen.skin)
        prioTable.background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/PrioritiesBox", BaseScreen.skinStrings.roundedEdgeRectangleShape,
            Color(1f, 1f, 1f, 0.07f))

        // 每行放 2-3 个优先级条目
        var colCount = 0
        val rowTable = Table(BaseScreen.skin)
        for ((branch, value) in map) {
            val entry = Table(BaseScreen.skin)
            entry.add(branch.toLabel(fontSize = 14)).left().pad(2f).width(130f)
            val valueField = UncivTextField("", (value as? Number)?.toInt()?.toString() ?: "0")
            valueField.textFieldFilter = TextField.TextFieldFilter { _, c -> c in "0123456789-" }
            valueField.setTextFieldListener { f, _ ->
                val v = f.text.trim().toIntOrNull()
                @Suppress("UNCHECKED_CAST")
                (item.raw["priorities"] as? MutableMap<String, Any?>)?.let { m ->
                    if (v != null) m[branch] = v else m.remove(branch)
                }
            }
            entry.add(valueField).width(70f).pad(2f)
            val removeBtn = "×".toTextButton()
            removeBtn.onActivation {
                @Suppress("UNCHECKED_CAST")
                (item.raw["priorities"] as? MutableMap<String, Any?>)?.remove(branch)
                buildPrioritiesSection(item)
            }
            entry.add(removeBtn).pad(2f)

            rowTable.add(entry).left().pad(4f, 8f, 4f, 8f)
            colCount++
            if (colCount >= 3) {
                prioTable.add(rowTable).fillX().row()
                rowTable.clear()
                colCount = 0
            }
        }
        if (colCount > 0) {
            prioTable.add(rowTable).fillX().row()
        }

        // 添加按钮
        val addBtn = "+ Add priority".toTextButton()
        addBtn.onActivation { showAddPriorityPopup(item) }
        prioTable.add(addBtn).left().pad(6f, 8f, 6f, 8f).row()

        formTable.add(prioTable).growX().pad(4f, 8f, 4f, 8f).row()
    }

    private fun showAddPriorityPopup(item: ModObjectData) {
        val existing = (item.raw["priorities"] as? Map<*, *>)?.keys?.map { it.toString() }?.toSet() ?: emptySet()
        val branches = ModEditorData.getPolicies(modFolder).filter { it !in existing }
        if (branches.isEmpty()) return

        val popup = Popup(this)
        popup.add("Add policy priority".tr().toLabel(fontSize = 20)).pad(8f).row()
        val searchField = UncivTextField("Search")
        val listTable = Table(BaseScreen.skin)
        fun rebuildList(query: String = "") {
            listTable.clear()
            for (branch in branches) {
                if (query.isNotEmpty() && !branch.lowercase().contains(query) && !branch.tr().lowercase().contains(query)) continue
                val row = Table(BaseScreen.skin)
                row.background = rowBackground()
                row.defaults().pad(6f)
                row.add(branch.toLabel(fontSize = 16)).left().expandX()
                row.touchable = Touchable.enabled
                row.onActivation {
                    @Suppress("UNCHECKED_CAST")
                    val m = item.raw.getOrPut("priorities") { LinkedHashMap<String, Any?>() } as MutableMap<String, Any?>
                    m[branch] = 0
                    popup.close()
                    buildPrioritiesSection(item)
                }
                listTable.add(row).fillX().pad(2f, 6f, 2f, 6f).row()
            }
        }
        rebuildList()
        searchField.setTextFieldListener { f, _ -> rebuildList(f.text.trim().lowercase()) }
        popup.add(searchField).growX().width(360f).pad(4f).row()
        val scroll = AutoScrollPane(listTable)
        scroll.setScrollingDisabled(true, false)
        popup.add(scroll).grow().width(360f).height(260f).pad(4f).row()
        popup.addCloseButton()
        popup.open()
    }

    // ── 词条 ──

    private lateinit var uniquesTable: Table
    private lateinit var uniquesButtonRow: Table

    private fun buildUniquesSection(item: ModObjectData) {
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
        rebuildUniquesTable(item)
    }

    private fun rebuildUniquesTable(item: ModObjectData) {
        uniquesTable.clear()
        uniquesButtonRow.clear()
        if (item.uniques.isEmpty()) {
            uniquesTable.add("(no uniques)".toLabel(fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.5f))).left().pad(4f).row()
        }
        for ((idx, raw) in item.uniques.withIndex()) {
            val parsed = uniqueCatalog.parseRaw(raw)
            if (parsed != null) {
                lateinit var editor: UniqueInlineEditor
                editor = UniqueInlineEditor(
                    screen = this, modFolder = modFolder, catalog = uniqueCatalog,
                    unique = parsed.unique, values = parsed.values, conditions = parsed.conditions,
                    onValueChange = { item.uniques[idx] = editor.buildRaw(); item.syncUniques() },
                    onStructureChange = { item.uniques[idx] = editor.buildRaw(); item.syncUniques(); rebuildUniquesTable(item) },
                    onDuplicate = { item.uniques.add(idx + 1, editor.buildRaw()); item.syncUniques(); rebuildUniquesTable(item) },
                    onDelete = { item.uniques.removeAt(idx); item.syncUniques(); rebuildUniquesTable(item) }
                )
                uniquesTable.add(editor).growX().left().pad(3f, 8f, 3f, 8f).row()
                uniquesTable.add(uniqueSeparatorLine()).growX().height(1f).pad(2f, 8f, 2f, 8f).row()
            } else {
                val row = Table(BaseScreen.skin)
                row.add(raw.toLabel(fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.7f))).left().expandX()
                val editBtn = "Edit".toTextButton()
                editBtn.onActivation { showRawEditPopup(item, idx) }
                row.add(editBtn).pad(3f)
                val delBtn = "×".toTextButton()
                delBtn.onActivation { item.uniques.removeAt(idx); item.syncUniques(); rebuildUniquesTable(item) }
                row.add(delBtn).pad(3f)
                uniquesTable.add(row).fillX().pad(3f, 8f, 3f, 8f).row()
            }
        }
        val addBtn = "+ Add unique".toTextButton()
        addBtn.onActivation {
            game.pushScreen(UniquePickerScreen(
                onPick = { unique ->
                    val values = unique.params.filter { it.default.isNotBlank() }.associate { it.id to it.default }
                    val raw = uniqueCatalog.buildRawString(unique, values, emptyList())
                    item.uniques.add(raw); item.syncUniques(); rebuildUniquesTable(item)
                },
                onRawPicked = { text -> item.uniques.add(text); item.syncUniques(); rebuildUniquesTable(item) }
            ))
        }
        uniquesButtonRow.add(addBtn).left().pad(6f)
        addRawEditUniquesButton(this, uniquesButtonRow, getUniques = { item.uniques }) { rebuildUniquesTable(item) }
        uniquesTable.row()
    }

    private fun showRawEditPopup(item: ModObjectData, idx: Int) {
        val popup = Popup(this)
        popup.add("Edit unique (raw)".tr().toLabel(fontSize = 20)).pad(8f).row()
        val field = UncivTextField("", item.uniques[idx])
        popup.add(field).growX().pad(4f).row()
        popup.addButton("Save".tr()) {
            item.uniques[idx] = field.text; item.syncUniques(); rebuildUniquesTable(item); popup.close()
        }
        popup.addCloseButton()
        popup.open()
    }

    // ── 保存 ──

    private fun save() {
        if (selectedIndex !in items.indices) return
        val problems = ModEditorData.validatePersonality(modFolder, current(), items)
        val errors = problems.filter { it.second }
        if (errors.isNotEmpty()) {
            showProblemsPopup(problems, onSaveAnyway = null)
            return
        }
        if (problems.isNotEmpty()) {
            showProblemsPopup(problems) { doSave() }
            return
        }
        doSave()
    }

    private fun doSave() {
        ModEditorData.savePersonalities(modFolder, items)
        val gameProblems = ModEditorData.filterGameProblems(
            ModEditorData.runGameValidation(modFolder), "Personalities.json")
        val errors = gameProblems.filter { it.second }
        if (errors.isNotEmpty()) {
            ModEditorData.rollbackFile(modFolder, "Personalities.json")
            statusLabel.setText("Save failed".tr())
            showGameProblemsPopup(gameProblems, saved = false)
            return
        }
        statusLabel.setText("Saved".tr())
        if (gameProblems.isNotEmpty()) showGameProblemsPopup(gameProblems, saved = true)
    }

    private fun deleteItem() {
        if (selectedIndex < 0 || selectedIndex >= items.size) return
        val name = current().name
        val popup = Popup(this)
        popup.add(("Are you sure you want to delete [$name]?").tr().toLabel(fontSize = 20)).pad(10f).row()
        popup.addButton("Delete".tr()) {
            items.removeAt(selectedIndex)
            if (items.isEmpty()) selectedIndex = -1
            else if (selectedIndex >= items.size) selectedIndex = items.lastIndex
            popup.close(); refreshList(); rebuildForm()
        }
        popup.addCloseButton()
        popup.open()
    }

    private fun addItem() {
        val item = ModObjectData()
        item.name = "New personality"
        item.raw["name"] = item.name
        // 默认值 5（游戏默认行为）
        for (f in statsFields + behaviorFields) item.raw[f] = 5f
        items.add(item)
        selectedIndex = items.lastIndex
        refreshList(); rebuildForm()
    }

    private fun showCopyPopup(initialSource: String? = null) {
        val sourceRuleset = initialSource?.takeIf { it.isNotBlank() }
            ?: ModEditorData.readBaseRulesetChoice(modFolder).ifBlank { com.unciv.models.metadata.BaseRuleset.Civ_V_GnK.fullName }
        val baseItems = ModEditorData.loadBaseObjects(modFolder, "Personalities.json", sourceRuleset)
        if (baseItems.isEmpty()) {
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
            popup.add("No personalities found in the base ruleset".tr().toLabel(fontSize = 18)).pad(10f).row()
            popup.addCloseButton(); popup.open(); return
        }
        val popup = Popup(this)
        popup.add("Copy personality from ruleset".tr().toLabel(fontSize = 22)).pad(8f).row()
        val searchField = UncivTextField("Search")
        val listTable = Table(BaseScreen.skin)
        fun rebuildList(query: String = "") {
            listTable.clear()
            for (item in baseItems) {
                if (query.isNotEmpty() && !item.name.lowercase().contains(query) && !item.name.tr().lowercase().contains(query)) continue
                val row = Table(BaseScreen.skin)
                row.background = rowBackground()
                row.defaults().pad(6f)
                row.add(item.name.toLabel(fontSize = 16)).left().expandX()
                row.touchable = Touchable.enabled
                row.onActivation {
                    val copy = ModObjectData()
                    copy.name = item.name
                    copy.comment = item.comment
                    item.raw.forEach { (k, v) -> copy.raw[k] = v }
                    copy.uniques.addAll(item.uniques)
                    items.add(copy)
                    selectedIndex = items.lastIndex
                    popup.close(); refreshList(); rebuildForm()
                }
                listTable.add(row).fillX().pad(2f, 6f, 2f, 6f).row()
            }
        }
        rebuildList()
        searchField.setTextFieldListener { f, _ -> rebuildList(f.text.trim().lowercase()) }
        popup.add(searchField).growX().width(400f).pad(4f).row()
        val scroll = AutoScrollPane(listTable)
        scroll.setScrollingDisabled(true, false)
        popup.add(scroll).grow().width(400f).height(300f).pad(4f).row()
        popup.addCloseButton()
        popup.open()
    }

    // ── 辅助 ──

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
        "ModEditor/PersonalityRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        BaseScreen.skinStrings.skinConfig.baseColor)

    private fun selectedRowBackground() = BaseScreen.skinStrings.getUiBackground(
        "ModEditor/PersonalityRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        Color(0.2f, 0.5f, 0.9f, 1f))

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
            popup.addButton("Save anyway".tr()) { popup.close(); onSaveAnyway() }
        }
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
}

package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextArea
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
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

/** Quests 编辑器：列表 + 表单（字段对照官方 Quests 文档；UI 参照单位晋升的平铺风格，2026-08-19） */
class QuestsEditorScreen(private val modFolder: FileHandle) : BaseScreen() {

    private val quests = ModEditorData.loadQuests(modFolder)
    private var selectedIndex = -1

    private val listTable = Table(BaseScreen.skin).apply {
        defaults().expandX().fillX()
        top()   // 列表内容不满时顶部对齐，否则垂直居中
    }
    private val formTable = FillWidthTable(BaseScreen.skin)
    private val statusLabel = "".toLabel(fontSize = 16)
    private lateinit var searchField: UncivTextField
    private var searchQuery = ""

    // 表单控件
    private lateinit var nameBox: ModEditorSelectBox
    private lateinit var typeBox: ModEditorSelectBox
    private lateinit var influenceField: UncivTextField
    private lateinit var durationField: UncivTextField
    private lateinit var minimumCivsField: UncivTextField
    private lateinit var descriptionArea: TextArea
    private lateinit var commentArea: TextArea
    private val weightMap = LinkedHashMap<String, Double>()
    private var selectedRowNameLabel: Label? = null

    private fun current(): ModObjectData = quests[selectedIndex]

    init {
        val root = Table(BaseScreen.skin)
        root.setFillParent(true)
        stage.addActor(root)

        // 顶栏
        val topBar = Table(BaseScreen.skin)
        val backButton = ("‹ " + "Back".tr()).toTextButton()
        backButton.onActivation { game.popScreen() }
        topBar.add(backButton).pad(8f)
        topBar.add(("Quests".tr() + " · Quests.json").toLabel(fontSize = 28))
            .padLeft(20f).expandX().left()
        val saveButton = "Save".toTextButton()
        saveButton.onActivation { save() }
        topBar.add(saveButton).pad(8f)
        topBar.add(statusLabel).pad(8f)
        root.add(topBar).fillX().row()

        // 左侧：按钮行 + 搜索 + 平铺列表（单位晋升风格）
        val leftPanel = Table(BaseScreen.skin)
        val buttonRow = Table(BaseScreen.skin)
        val addButton = "+ New quest".toTextButton()
        addButton.onActivation { addQuest() }
        buttonRow.add(addButton).left().pad(6f)
        val copyButton = "Copy quest from ruleset".toTextButton()
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
        if (quests.isNotEmpty()) select(0)
        else formTable.add("No quests. Click \"+ New quest\" in the top left.".toLabel()).pad(20f).row()
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
        "ModEditor/QuestRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        BaseScreen.skinStrings.skinConfig.baseColor)

    private fun selectedRowBackground() = BaseScreen.skinStrings.getUiBackground(
        "ModEditor/QuestRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        Color(0.2f, 0.5f, 0.9f, 1f))

    // ------------------------------------------------------------------
    // 列表（平铺，单位晋升风格）
    // ------------------------------------------------------------------

    private fun refreshList() {
        listTable.clear()
        for ((i, quest) in quests.withIndex()) {
            if (searchQuery.isNotEmpty() && !quest.name.lowercase().contains(searchQuery) &&
                !(quest.name.tr().lowercase().contains(searchQuery))) continue
            val isSelected = i == selectedIndex
            val row = Table(BaseScreen.skin)
            row.defaults().pad(6f)
            row.background = if (isSelected) selectedRowBackground() else rowBackground()
            val nameLabel = listNameLabel(
                quest.name.ifBlank { "(unnamed)".tr() },
                maxWidth = stage.width * 0.25f - 100f,
                fontSize = 20,
                fontColor = if (isSelected) Color.WHITE else Color(1f, 1f, 1f, 0.85f))
            if (isSelected) selectedRowNameLabel = nameLabel
            row.add(nameLabel).growX().left().maxWidth(stage.width * 0.25f - 60f)
            val type = quest.getString("type")
            if (type.isNotBlank()) {
                row.add(type.toLabel(fontSize = 13, fontColor = Color(1f, 1f, 1f, 0.4f))).right().padRight(8f)
            }
            row.touchable = Touchable.enabled
            row.onActivation { selectedIndex = i; refreshList(); rebuildForm() }
            listTable.add(row).fillX().pad(2f, 6f, 2f, 6f).row()
        }
        if (quests.isEmpty()) {
            listTable.add("No quests yet.".toLabel(fontSize = 16, fontColor = Color(1f, 1f, 1f, 0.4f))).pad(20f).row()
        }
    }

    private fun select(index: Int) {
        selectedIndex = index
        refreshList()
        rebuildForm()
    }

    private fun addQuest() {
        val quest = ModObjectData()
        quest.name = nextName()
        quest.setString("type", "Individual")
        quests.add(quest)
        select(quests.lastIndex)
    }

    private fun nextName(): String {
        val existing = quests.map { it.name }.toSet()
        var i = 1
        while (("New quest" + if (i == 1) "" else " $i") in existing) i++
        return "New quest" + if (i == 1) "" else " $i"
    }

    // ------------------------------------------------------------------
    // 表单
    // ------------------------------------------------------------------

    private fun searchableBox(values: List<String>, current: String?): ModEditorSelectBox {
        val items = mutableListOf("(None)")
        items.addAll(values)
        val cur = current ?: ""
        if (cur.isNotBlank() && cur !in items) items.add(cur)
        return ModEditorSelectBox(items, if (cur.isBlank()) "(None)" else cur, searchable = true)
    }

    private fun rebuildForm() {
        formTable.clear()
        if (::nameBox.isInitialized) nameBox.disposeFloating()
        if (::typeBox.isInitialized) typeBox.disposeFloating()
        val quest = current()

        val header = Table(BaseScreen.skin)
        header.add("Edit quest".toLabel(fontSize = 24)).left().expandX()
        val copyButton = "Duplicate".toTextButton()
        copyButton.onActivation {
            val copy = ModObjectData()
            copy.name = nextName()
            copy.comment = quest.comment
            quest.raw.forEach { (k, v) -> copy.raw[k] = v }
            quests.add(copy)
            select(quests.lastIndex)
        }
        header.add(copyButton).pad(4f)
        val deleteButton = "Delete".toTextButton()
        deleteButton.onActivation { confirmDelete() }
        header.add(deleteButton).pad(4f)
        formTable.add(header).fillX().row()

        formTable.add(sectionHeader("Basic info".tr())).fillX().row()

        // name：预定义枚举下拉（决定任务行为）
        val questNames = ModEditorData.getQuestNames()
        val currentName = quest.getString("name")
        val nameItems = mutableListOf<String>()
        if (currentName.isNotBlank() && currentName !in questNames) nameItems.add(currentName)
        nameItems.addAll(questNames)
        nameBox = ModEditorSelectBox(nameItems, currentName.ifBlank { questNames.first() }, searchable = true)
        nameBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                quest.setString("name", nameBox.selected?.value)
                selectedRowNameLabel?.setText(nameBox.selected?.value?.ifBlank { "(unnamed)".tr() } ?: "(unnamed)".tr())
            }
        })
        addFieldRow("Name", nameBox)

        // description（必填，支持 [占位符] 额外信息）
        descriptionArea = TextArea(quest.getString("description"), BaseScreen.skin)
        val descScroll = AutoScrollPane(descriptionArea).apply {
            setOverscroll(false, false)
            setScrollingDisabled(true, false)
        }
        val descRow = Table(BaseScreen.skin)
        descRow.add("Description".tr().toLabel()).left().pad(4f).width(180f).top()
        descRow.add(descScroll).growX().height(80f).pad(4f)
        formTable.add(descRow).growX().left().row()
        val descHint = "Square brackets [] in the description are replaced with extra information in-game".tr()
            .toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f))
        descHint.wrap = true
        formTable.add(descHint).growX().left().pad(0f, 188f, 6f, 8f).row()

        // type：Individual / Global
        typeBox = searchableBox(listOf("Individual", "Global"), quest.getString("type"))
        typeBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                quest.setString("type", typeBox.selected?.value?.takeUnless { it == "(None)" })
                refreshList()
            }
        })
        addFieldRow("Type", typeBox)

        formTable.add(sectionHeader("Rewards & limits".tr())).fillX().row()

        influenceField = decimalField(quest.getIntText("influence"))
        durationField = numberField(quest.getIntText("duration"))
        minimumCivsField = numberField(quest.getIntText("minimumCivs"))
        val statsRow = Table(BaseScreen.skin)
        addStatPair(statsRow, "Influence", influenceField)
        addStatPair(statsRow, "Duration", durationField)
        addStatPair(statsRow, "Minimum civs", minimumCivsField)
        formTable.add(statsRow).growX().left().row()
        // 提示不含 " = "（properties 按 " = " 分割，key 里有会导致翻译 key 截断，2026-08-19 用户报没翻译）
        val durationHint = "Duration: maximum turns to complete; 0 means no limit. Minimum civs only matters for Global type".tr()
            .toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f))
        durationHint.wrap = true
        formTable.add(durationHint).growX().left().pad(0f, 8f, 6f, 8f).row()

        // weightForCityStateType：城邦类型/性格 → 权重
        formTable.add(sectionHeader("weightForCityStateType".tr())).fillX().row()
        weightMap.clear()
        val weights = quest.raw["weightForCityStateType"] as? Map<*, *>
        if (weights != null) {
            for ((k, v) in weights) {
                val d = when (v) {
                    is Number -> v.toDouble()
                    is String -> v.toDoubleOrNull() ?: 1.0
                    else -> 1.0
                }
                weightMap[k.toString()] = d
            }
        }
        val weightRow = Table(BaseScreen.skin)
        weightRow.add("Weight by city state type / personality".toLabel()).left().pad(4f).width(220f)
        val weightButton = (weightMap.entries.joinToString(", ") { (k, v) -> "$k: $v" }.ifBlank { "(None)" })
            .toTextButton()
        weightButton.onActivation { showWeightEditor(weightButton) }
        weightRow.add(weightButton).growX().minWidth(200f).pad(4f)
        formTable.add(weightRow).growX().left().row()
        val weightHint = "Initial weight 1, multiplied by these values; AI picks quests by weighted random".tr()
            .toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f))
        weightHint.wrap = true
        formTable.add(weightHint).growX().left().pad(0f, 8f, 6f, 8f).row()

        formTable.add(sectionHeader("Comment".tr()))
            .fillX().row()
        commentArea = TextArea(quest.comment, BaseScreen.skin)
        formTable.add(commentArea).growX().height(100f).left().pad(6f).row()
    }

    private fun addFieldRow(labelKey: String, widget: Actor) {
        val row = Table(BaseScreen.skin)
        row.add(labelKey.tr().toLabel()).left().pad(4f).width(180f)
        row.add(widget).growX().minWidth(160f).pad(4f)
        formTable.add(row).growX().left().row()
    }

    private fun addStatPair(row: Table, label: String, field: UncivTextField) {
        row.add(label.tr().toLabel()).left().pad(4f).width(112f)
        row.add(field).growX().minWidth(80f).pad(4f)
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

    // ------------------------------------------------------------------
    // weightForCityStateType 映射编辑器（城邦类型/性格 → 权重）
    // ------------------------------------------------------------------

    private fun showWeightEditor(button: TextButton) {
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        popup.add("weightForCityStateType".toLabel(fontSize = 20)).pad(8f).row()
        val listTable = Table(BaseScreen.skin)
        val scroll = AutoScrollPane(listTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        popup.add(scroll).grow().width(480f).height(300f).pad(6f).row()

        val keyOptions = (ModEditorData.getCityStateTypes(modFolder) +
            ModEditorData.getCityStatePersonalities()).distinct()
        val rows = mutableListOf<Triple<ModEditorSelectBox, UncivTextField, Table>>()

        fun addRow(key: String, value: Double) {
            val items = if (key.isNotBlank() && key !in keyOptions) mutableListOf(key) + keyOptions else keyOptions
            val keyBox = ModEditorSelectBox(items, key.ifBlank { keyOptions.firstOrNull() ?: "" }, searchable = true)
            val valueField = UncivTextField("", value.toString())
            valueField.textFieldFilter = object : TextField.TextFieldFilter {
                override fun acceptChar(textField: TextField, c: Char): Boolean {
                    if (c in '0'..'9') return true
                    if (c == '-' && textField.text.isEmpty()) return true
                    if (c == '.' && !textField.text.contains('.')) return true
                    return false
                }
            }
            val row = Table(BaseScreen.skin)
            row.add(keyBox).growX().minWidth(200f).pad(4f)
            row.add(valueField).width(100f).pad(4f)
            val removeButton = "×".toTextButton()
            removeButton.onActivation {
                rows.removeIf { it.first === keyBox }
                row.remove()
            }
            row.add(removeButton).pad(4f)
            listTable.add(row).growX().left().row()
            rows.add(Triple(keyBox, valueField, row))
        }

        if (weightMap.isEmpty()) addRow("", 1.0)
        else for ((key, value) in weightMap) addRow(key, value)

        popup.addButton("+ Add") { addRow("", 1.0) }
        popup.addButton("Save".tr()) {
            weightMap.clear()
            for ((keyBox, valueField, _) in rows) {
                val key = keyBox.selected?.value ?: continue
                val value = valueField.text.trim().toDoubleOrNull() ?: continue
                weightMap[key] = value
            }
            button.setText(weightMap.entries.joinToString(", ") { (k, v) -> "$k: $v" }.ifBlank { "(None)" })
            popup.close()
        }
        popup.addCloseButton()
        popup.open()
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    private fun save() {
        val quest = current()
        val oldName = quest.name
        val newName = nameBox.selected?.value?.takeUnless { it == "(None)" } ?: ""

        if (newName.isBlank()) { showMessage("Quest name cannot be empty".tr()); return }

        quest.setString("name", newName)
        quest.name = newName
        quest.setString("type", typeBox.selected?.value?.takeUnless { it == "(None)" })
        val influence = influenceField.text.trim()
        if (influence.isBlank()) quest.raw.remove("influence")
        else {
            val v = influence.toDoubleOrNull()
            if (v == null) { showMessage("Invalid number:".tr() + " " + influence); return }
            quest.setNumber("influence", v)
        }
        for ((key, field) in mapOf("duration" to durationField, "minimumCivs" to minimumCivsField)) {
            val text = field.text.trim()
            if (text.isBlank()) quest.raw.remove(key)
            else {
                val v = text.toIntOrNull()
                if (v == null) { showMessage("Invalid number:".tr() + " " + text); return }
                quest.setInt(key, v)
            }
        }
        val desc = descriptionArea.text.trim()
        if (desc.isBlank()) quest.raw.remove("description") else quest.setString("description", desc)
        if (weightMap.isEmpty()) quest.raw.remove("weightForCityStateType")
        else quest.raw["weightForCityStateType"] = LinkedHashMap(weightMap)
        quest.comment = commentArea.text

        val problems = ModEditorData.validateQuest(modFolder, quest, quests)
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
        ModEditorData.saveQuests(modFolder, quests)
        val gameProblems = ModEditorData.filterGameProblems(
            ModEditorData.runGameValidation(modFolder), "Quests.json")
        val errors = gameProblems.filter { it.second }
        if (errors.isNotEmpty()) {
            ModEditorData.rollbackFile(modFolder, "Quests.json")
            statusLabel.setText("Save failed".tr())
            showGameProblemsPopup(gameProblems, saved = false)
            return
        }
        statusLabel.setText("Saved".tr())
        refreshList()
        if (gameProblems.isNotEmpty()) showGameProblemsPopup(gameProblems, saved = true)
    }

    /** 从规则集复制任务：完整拷进模组（同名覆盖原版） */
    private fun showCopyFromRulesetPopup(initialSource: String? = null) {
        val sourceRuleset = initialSource?.takeIf { it.isNotBlank() }
            ?: ModEditorData.readBaseRulesetChoice(modFolder).ifBlank { com.unciv.models.metadata.BaseRuleset.Civ_V_GnK.fullName }
        val baseQuests = ModEditorData.loadBaseObjects(modFolder, "Quests.json", sourceRuleset)
        if (baseQuests.isEmpty()) { showMessage("No quests found in the base ruleset"); return }
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
        popup.add("Copy quest from ruleset".tr().toLabel(fontSize = 20)).pad(8f).row()
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
            for (base in baseQuests) {
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
                    quests.add(copy)
                    popup.close()
                    select(quests.lastIndex)
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
        val quest = current()
        val popup = Popup(this)
        val questName = quest.name.ifBlank { "(unnamed)".tr() }
        popup.add("Are you sure you want to delete [$questName]?".tr().toLabel(fontSize = 20)).pad(12f).row()
        popup.addButton("Delete".tr()) {
            quests.removeAt(selectedIndex)
            popup.close()
            if (quests.isEmpty()) {
                selectedIndex = -1
                refreshList()
                formTable.clear()
                formTable.add("No quests. Click \"+ New quest\" in the top left.".toLabel()).pad(20f).row()
            } else {
                select(minOf(selectedIndex, quests.lastIndex))
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

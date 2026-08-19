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

/** Events 编辑器：平铺列表 + 表单（2026-08-19，UI 参照单位晋升；choices 为嵌套编辑区） */
class EventsEditorScreen(private val modFolder: FileHandle) : BaseScreen() {

    private val items = ModEditorData.loadEvents(modFolder)
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
    private lateinit var textArea: TextArea
    private lateinit var presentationBox: ModEditorSelectBox
    private lateinit var uniquesTable: Table
    private lateinit var uniquesButtonRow: Table
    private lateinit var civilopediaEditor: CivilopediaTextEditor
    private lateinit var commentArea: TextArea
    private var selectedRowNameLabel: Label? = null
    private val choicesTables = mutableListOf<Pair<Table, MutableList<EventChoiceData>>>()
    private val choiceTextAreas = mutableListOf<Pair<UncivTextField, UncivTextField>>() // text, keyShortcut
    private lateinit var choicesBox: Table // choices 容器：卡片 + 添加按钮（防止新卡片追加到表单末尾）
    private lateinit var addChoiceButton: com.badlogic.gdx.scenes.scene2d.ui.TextButton

    /** choice 的编辑数据：raw 为 choice 对象原始字段 */
    private class EventChoiceData {
        val raw = LinkedHashMap<String, Any?>()
        val uniques = mutableListOf<String>()
        fun syncUniques() { raw["uniques"] = uniques.toList() }
        fun getString(key: String): String = raw[key] as? String ?: ""
        fun setString(key: String, value: String?) {
            if (value.isNullOrBlank()) raw.remove(key) else raw[key] = value
        }
    }

    private fun current(): ModObjectData = items[selectedIndex]

    init {
        val root = Table(BaseScreen.skin)
        root.setFillParent(true)
        stage.addActor(root)

        val topBar = Table(BaseScreen.skin)
        val backButton = ("‹ " + "Back".tr()).toTextButton()
        backButton.onActivation { game.popScreen() }
        topBar.add(backButton).pad(8f)
        topBar.add(("Events".tr() + " · Events.json").toLabel(fontSize = 28))
            .padLeft(20f).expandX().left()
        val saveButton = "Save".toTextButton()
        saveButton.onActivation { save() }
        topBar.add(saveButton).pad(8f)
        topBar.add(statusLabel).pad(8f)
        root.add(topBar).fillX().row()

        // 左侧：按钮行 + 搜索 + 平铺列表
        val leftPanel = Table(BaseScreen.skin)
        val buttonRow = Table(BaseScreen.skin)
        val addButton = "+ New event".toTextButton()
        addButton.onActivation { addItem() }
        buttonRow.add(addButton).left().pad(6f)
        val copyButton = "Copy event from ruleset".toTextButton()
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
        else formTable.add("No events. Click \"+ New event\" in the top left.".tr().toLabel()).pad(20f).row()
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
        "ModEditor/EventRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        BaseScreen.skinStrings.skinConfig.baseColor)

    private fun selectedRowBackground() = BaseScreen.skinStrings.getUiBackground(
        "ModEditor/EventRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
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
            listTable.add("No events yet.".tr().toLabel(fontSize = 16, fontColor = Color(1f, 1f, 1f, 0.4f))).pad(20f).row()
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
        while (("New event" + if (i == 1) "" else " $i") in existing) i++
        return "New event" + if (i == 1) "" else " $i"
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
        choicesTables.clear()
        choiceTextAreas.clear()
        if (::presentationBox.isInitialized) presentationBox.disposeFloating()
        val item = current()

        val header = Table(BaseScreen.skin)
        header.add("Edit event".tr().toLabel(fontSize = 24)).left().expandX()
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

        nameField = UncivTextField("Event name (required)", item.name)
        nameField.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                selectedRowNameLabel?.setText(nameField.text.ifBlank { "(unnamed)".tr() })
            }
        })
        addFieldRow("Name", nameField)
        val nameHint = "Used for triggering via \"Triggers a [event] event\" unique".tr()
            .toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f))
        nameHint.wrap = true
        formTable.add(nameHint).growX().left().pad(0f, 188f, 6f, 8f).row()

        // presentation（预定义枚举）
        presentationBox = searchableBox(listOf("Alert", "None", "Floating"), item.getString("presentation"))
        presentationBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                item.setString("presentation", presentationBox.selected?.value?.takeUnless { it == "(None)" })
            }
        })
        addFieldRow("Presentation", presentationBox)
        val presentationHint = "Presentation: Alert means regular popup, None means random choice, Floating is a tutorial indicator".tr()
            .toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f))
        presentationHint.wrap = true
        formTable.add(presentationHint).growX().left().pad(0f, 188f, 6f, 8f).row()

        // text（风味文本）
        textArea = TextArea(item.getString("text"), BaseScreen.skin)
        val textScroll = AutoScrollPane(textArea).apply {
            setOverscroll(false, false)
            setScrollingDisabled(true, false)
        }
        val textRow = Table(BaseScreen.skin)
        textRow.add("Text".tr().toLabel()).left().pad(4f).width(180f).top()
        textRow.add(textScroll).growX().height(70f).pad(4f)
        formTable.add(textRow).growX().left().row()

        // 顶层 uniques（触发条件：Only available / Unavailable）
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

        // choices（嵌套列表）
        formTable.add(sectionHeader("Choices".tr())).fillX().row()
        val choicesRaw = item.raw["choices"] as? List<*>
        val parsedChoices = mutableListOf<EventChoiceData>()
        if (choicesRaw != null) {
            for (c in choicesRaw) {
                if (c !is Map<*, *>) continue
                val choice = EventChoiceData()
                for ((k, v) in c) choice.raw[k.toString()] = v
                val u = c["uniques"]
                if (u is List<*>) choice.uniques.addAll(u.filterIsInstance<String>())
                parsedChoices.add(choice)
            }
        }
        if (parsedChoices.isEmpty()) parsedChoices.add(EventChoiceData())

        // choices 容器：卡片全部加进容器，添加按钮在容器末尾；新卡片插到按钮前
        choicesBox = Table(BaseScreen.skin)
        formTable.add(choicesBox).growX().left().row()
        addChoiceButton = "+ Add choice".toTextButton()
        addChoiceButton.onActivation { addChoiceEditor(EventChoiceData()) }
        for (choice in parsedChoices) addChoiceEditor(choice)
        choicesBox.add(addChoiceButton).left().pad(6f, 8f, 6f, 8f).row()
        val choicesHint = "Each choice shows a button to the user; its uniques trigger when chosen, \"Unavailable\" / \"Only available\" limit it".tr()
            .toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f))
        choicesHint.wrap = true
        formTable.add(choicesHint).growX().left().pad(0f, 8f, 6f, 8f).row()

        civilopediaEditor = CivilopediaTextEditor(
            screen = this,
            getRaw = { item.raw["civilopediaText"] },
            setRaw = { item.raw["civilopediaText"] = it }
        )
        civilopediaEditor.addTo(formTable, "Civilopedia text")

        formTable.add(sectionHeader("Comment".tr())).fillX().row()
        commentArea = TextArea(item.comment, BaseScreen.skin)
        formTable.add(commentArea).growX().height(100f).left().pad(6f).row()
    }

    /** 单个 choice 编辑卡片：text + keyShortcut + uniques + × */
    private fun addChoiceEditor(choice: EventChoiceData) {
        val card = Table(BaseScreen.skin)
        card.background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/ChoiceCard", BaseScreen.skinStrings.roundedEdgeRectangleShape,
            Color(1f, 1f, 1f, 0.06f))
        card.defaults().pad(4f)

        val textField = UncivTextField("Choice text (required)", choice.getString("text"))
        val keyField = UncivTextField("", choice.getString("keyShortcut"))
        keyField.textFieldFilter = object : TextField.TextFieldFilter {
            override fun acceptChar(textField: TextField, c: Char): Boolean = textField.text.isEmpty()
        }
        choiceTextAreas.add(textField to keyField)

        val textRow = Table(BaseScreen.skin)
        textRow.add("Text".tr().toLabel()).left().pad(4f).width(80f)
        textRow.add(textField).growX().minWidth(120f).pad(4f)
        textRow.add("Key".tr().toLabel()).left().pad(4f).width(50f)
        textRow.add(keyField).width(70f).pad(4f)
        card.add(textRow).growX().left().row()

        // choice uniques（行内编辑器）
        val choiceUniquesTable = Table(BaseScreen.skin)
        rebuildChoiceUniquesTable(choiceUniquesTable, choice)
        val choiceUniquesScroll = AutoScrollPane(choiceUniquesTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        card.add(choiceUniquesScroll).growX().height(220f).left().pad(4f).row()

        val removeButton = "Remove choice".toTextButton()
        removeButton.onActivation {
            choicesTables.removeIf { it.first === card }
            card.remove()
        }
        card.add(removeButton).left().pad(4f).row()

        choicesTables.add(card to mutableListOf(choice))
        // 插到添加按钮之前：先移除按钮 → 加卡片 → 加回按钮（libGDX Table 无 insert 到指定 cell 的便捷 API）
        choicesBox.removeActor(addChoiceButton)
        choicesBox.add(card).growX().left().pad(4f, 8f, 4f, 8f).row()
        choicesBox.add(addChoiceButton).left().pad(6f, 8f, 6f, 8f).row()
    }

    /** 单个 choice 的 uniques 列表（用行内编辑器） */
    private fun rebuildChoiceUniquesTable(table: Table, choice: EventChoiceData) {
        table.clear()
        if (choice.uniques.isEmpty()) {
            table.add("(no uniques)".toLabel(fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.4f)))
                .left().pad(4f).row()
        }
        for ((index, rawString) in choice.uniques.withIndex()) {
            val parsed = uniqueCatalog.parseRaw(rawString)
            if (parsed != null) {
                lateinit var editor: UniqueInlineEditor
                editor = UniqueInlineEditor(
                    screen = this, modFolder = modFolder, catalog = uniqueCatalog,
                    unique = parsed.unique, values = parsed.values, conditions = parsed.conditions,
                    onValueChange = { choice.uniques[index] = editor.buildRaw() },
                    onStructureChange = {
                        choice.uniques[index] = editor.buildRaw()
                        rebuildChoiceUniquesTable(table, choice)
                    },
                    onDuplicate = {
                        val copyValues = parsed.values.toMutableMap()
                        val copyConditions = parsed.conditions
                            .map { (c, v) -> c to v.toMutableMap() }.toMutableList()
                        choice.uniques.add(index + 1,
                            uniqueCatalog.buildRawString(parsed.unique, copyValues, copyConditions))
                        rebuildChoiceUniquesTable(table, choice)
                    },
                    onDelete = {
                        choice.uniques.removeAt(index)
                        rebuildChoiceUniquesTable(table, choice)
                    }
                )
                table.add(editor).growX().left().pad(3f, 4f, 3f, 4f).row()
                table.add(uniqueSeparatorLine()).growX().height(1f).pad(2f, 8f, 2f, 8f).row()
            } else {
                val row = Table(BaseScreen.skin)
                val label = Label(rawString, BaseScreen.skin).apply {
                    setFontScale(15f / com.unciv.ui.components.fonts.Fonts.ORIGINAL_FONT_SIZE)
                    setAlignment(com.badlogic.gdx.utils.Align.left)
                    setColor(Color(1f, 1f, 1f, 0.8f))
                    wrap = true
                }
                row.add(label).growX().minWidth(300f).left().pad(4f)
                val editButton = "Edit".toTextButton()
                editButton.onActivation { showChoiceUniqueEditor(table, choice, index, rawString) }
                row.add(editButton).pad(4f)
                val removeButton = "×".toTextButton()
                removeButton.onActivation {
                    choice.uniques.removeAt(index)
                    rebuildChoiceUniquesTable(table, choice)
                }
                row.add(removeButton).pad(4f)
                table.add(row).growX().left().row()
            }
        }
        val addButton = "+ Add unique".toTextButton()
        addButton.onActivation {
            game.pushScreen(UniquePickerScreen(
                onPick = { unique ->
                    val values = unique.params
                        .filter { it.default.isNotBlank() }
                        .associate { it.id to it.default }.toMutableMap()
                    choice.uniques.add(uniqueCatalog.buildRawString(unique, values, emptyList()))
                    rebuildChoiceUniquesTable(table, choice)
                },
                onRawPicked = { text ->
                    choice.uniques.add(text)
                    rebuildChoiceUniquesTable(table, choice)
                }
            ))
        }
        table.add(addButton).left().pad(4f)
        addRawEditUniquesButton(this, table, getUniques = { choice.uniques }) { rebuildChoiceUniquesTable(table, choice) }
        table.row()
    }

    private fun showChoiceUniqueEditor(table: Table, choice: EventChoiceData, index: Int?, existing: String?) {
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        popup.add("Unique (raw mode)".tr().toLabel(fontSize = 22)).pad(10f).row()
        val textArea = TextArea(existing ?: "", BaseScreen.skin)
        popup.add(textArea).width(560f).height(160f).pad(6f).row()
        popup.addButton("Save".tr()) {
            val text = textArea.text.replace('\n', ' ').replace('\r', ' ')
                .replace(Regex("\\s{2,}"), " ").trim()
            if (text.isNotEmpty()) {
                if (index == null) choice.uniques.add(text)
                else if (index < choice.uniques.size) choice.uniques[index] = text
            } else if (index != null && index < choice.uniques.size) {
                choice.uniques.removeAt(index)
            }
            popup.close()
            rebuildChoiceUniquesTable(table, choice)
        }
        popup.addCloseButton()
        popup.open()
    }

    private fun addFieldRow(labelKey: String, widget: Actor) {
        val row = Table(BaseScreen.skin)
        row.add(labelKey.tr().toLabel()).left().pad(4f).width(180f)
        row.add(widget).growX().minWidth(160f).pad(4f)
        formTable.add(row).growX().left().row()
    }

    // ------------------------------------------------------------------
    // 词条（顶层 uniques）
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
        if (newName.isBlank()) { showMessage("Event name cannot be empty".tr()); return }
        item.name = newName
        item.setString("name", newName)
        val text = textArea.text.trim()
        if (text.isBlank()) item.raw.remove("text") else item.setString("text", text)
        // presentation 已在 listener 里写 raw
        item.syncUniques()

        // choices
        val choicesList = mutableListOf<Map<String, Any?>>()
        for ((textField, keyField) in choiceTextAreas) {
            val text = textField.text.trim()
            if (text.isBlank()) { showMessage("Choice text cannot be empty".tr()); return }
            val map = LinkedHashMap<String, Any?>()
            map["text"] = text
            val key = keyField.text.trim()
            if (key.isNotBlank()) map["keyShortcut"] = key
            val choice = findChoiceForFields(textField, keyField) ?: continue
            choice.syncUniques()
            if (choice.uniques.isNotEmpty()) map["uniques"] = choice.uniques.toList()
            choicesList.add(map)
        }
        if (choicesList.isEmpty()) item.raw.remove("choices")
        else item.raw["choices"] = choicesList

        val cpEntries = civilopediaEditor.buildEntries()
        if (cpEntries == null) item.raw.remove("civilopediaText") else item.raw["civilopediaText"] = cpEntries
        item.comment = commentArea.text

        val problems = ModEditorData.validateEvent(modFolder, item, items)
        val errors = problems.filter { it.second }
        if (errors.isNotEmpty()) { showProblemsPopup(problems, onSaveAnyway = null); return }
        if (problems.isNotEmpty()) { showProblemsPopup(problems) { doSave() }; return }
        doSave()
    }

    private fun findChoiceForFields(textField: UncivTextField, keyField: UncivTextField): EventChoiceData? {
        // choicesTables 与 choiceTextAreas 按加入顺序一一对应；textFields 是唯一句柄
        val idx = choiceTextAreas.indexOfFirst { it.first === textField }
        if (idx < 0 || idx >= choicesTables.size) return null
        return choicesTables[idx].second.firstOrNull()
    }

    private fun doSave() {
        ModEditorData.saveEvents(modFolder, items)
        val gameProblems = ModEditorData.filterGameProblems(
            ModEditorData.runGameValidation(modFolder), "Events.json")
        val errors = gameProblems.filter { it.second }
        if (errors.isNotEmpty()) {
            ModEditorData.rollbackFile(modFolder, "Events.json")
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
        val baseItems = ModEditorData.loadBaseObjects(modFolder, "Events.json", sourceRuleset)
        if (baseItems.isEmpty()) { showMessage("No events found in the base ruleset".tr()); return }
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
        popup.add("Copy event from ruleset".tr().toLabel(fontSize = 20)).pad(8f).row()
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
                formTable.add("No events. Click \"+ New event\" in the top left.".tr().toLabel()).pad(20f).row()
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

package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextArea
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.unciv.models.translations.getPlaceholderText
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

/** VictoryTypes 编辑器：平铺列表 + 表单（2026-08-19，UI 参照单位晋升） */
class VictoryTypesEditorScreen(private val modFolder: FileHandle) : BaseScreen() {

    private val items = ModEditorData.loadVictoryTypes(modFolder)
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
    private lateinit var headerArea: TextArea
    private lateinit var victoryArea: TextArea
    private lateinit var defeatArea: TextArea
    private lateinit var hiddenCheck: com.badlogic.gdx.scenes.scene2d.ui.CheckBox
    private lateinit var uniquesTable: Table
    private lateinit var uniquesButtonRow: Table
    private lateinit var civilopediaEditor: CivilopediaTextEditor
    private lateinit var commentArea: TextArea
    private var selectedRowNameLabel: Label? = null
    private val milestones = mutableListOf<String>()
    private val spaceshipChips = mutableListOf<String>()
    private var spaceshipSectionVisible = false
    private lateinit var milestonesTable: Table
    private lateinit var spaceshipChipTable: Table

    private fun current(): ModObjectData = items[selectedIndex]

    init {
        val root = Table(BaseScreen.skin)
        root.setFillParent(true)
        stage.addActor(root)

        val topBar = Table(BaseScreen.skin)
        val backButton = ("‹ " + "Back".tr()).toTextButton()
        backButton.onActivation { game.popScreen() }
        topBar.add(backButton).pad(8f)
        topBar.add(("VictoryTypes".tr() + " · VictoryTypes.json").toLabel(fontSize = 28))
            .padLeft(20f).expandX().left()
        val saveButton = "Save".toTextButton()
        saveButton.onActivation { save() }
        topBar.add(saveButton).pad(8f)
        topBar.add(statusLabel).pad(8f)
        root.add(topBar).fillX().row()

        // 左侧：按钮行 + 搜索 + 平铺列表
        val leftPanel = Table(BaseScreen.skin)
        val buttonRow = Table(BaseScreen.skin)
        val addButton = "+ New victory type".toTextButton()
        addButton.onActivation { addItem() }
        buttonRow.add(addButton).left().pad(6f)
        val copyButton = "Copy victory type from ruleset".toTextButton()
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
        else formTable.add("No victory types. Click \"+ New victory type\" in the top left.".tr().toLabel()).pad(20f).row()
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
        "ModEditor/VictoryRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        BaseScreen.skinStrings.skinConfig.baseColor)

    private fun selectedRowBackground() = BaseScreen.skinStrings.getUiBackground(
        "ModEditor/VictoryRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
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
            listTable.add("No victory types yet.".tr().toLabel(fontSize = 16, fontColor = Color(1f, 1f, 1f, 0.4f))).pad(20f).row()
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
        while (("New victory type" + if (i == 1) "" else " $i") in existing) i++
        return "New victory type" + if (i == 1) "" else " $i"
    }

    // ------------------------------------------------------------------
    // 表单
    // ------------------------------------------------------------------

    private fun rebuildForm() {
        formTable.clear()
        val item = current()

        val header = Table(BaseScreen.skin)
        header.add("Edit victory type".tr().toLabel(fontSize = 24)).left().expandX()
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

        nameField = UncivTextField("Victory name (required)", item.name)
        nameField.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                selectedRowNameLabel?.setText(nameField.text.ifBlank { "(unnamed)".tr() })
            }
        })
        addFieldRow("Name", nameField)

        // hiddenInVictoryScreen（checkbox）
        val hiddenRow = Table(BaseScreen.skin)
        hiddenCheck = com.badlogic.gdx.scenes.scene2d.ui.CheckBox(
            "Hidden in victory screen".tr(), BaseScreen.skin)
        hiddenCheck.isChecked = item.raw["hiddenInVictoryScreen"] == true
        hiddenRow.add(hiddenCheck).left().pad(4f)
        formTable.add(hiddenRow).growX().left().row()

        // 三个字符串区
        headerArea = textAreaBlock("Victory screen header", item.getString("victoryScreenHeader"))
        victoryArea = textAreaBlock("Victory string", item.getString("victoryString"))
        defeatArea = textAreaBlock("Defeat string", item.getString("defeatString"))

        // milestones（参数化编辑：与 uniques 同款行内编辑器，catalog 有 Milestones 分类）
        formTable.add(sectionHeader("Milestones".tr())).fillX().row()
        milestones.clear()
        milestones.addAll(item.getStringList("milestones"))
        milestonesTable = Table(BaseScreen.skin)
        rebuildMilestonesTable()
        val milestonesBox = Table(BaseScreen.skin)
        milestonesBox.background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/UniquesBox", BaseScreen.skinStrings.roundedEdgeRectangleShape,
            Color(1f, 1f, 1f, 0.07f))
        val milestonesScroll = AutoScrollPane(milestonesTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        milestonesBox.add(milestonesScroll).grow().pad(10f)
        formTable.add(milestonesBox).growX().height(360f).left().pad(6f).row()
        val milestoneHint = "Supported: Build [building], Anyone should build [building], Add all [comment] in capital, Destroy all players, Capture all capitals, Complete [amount] Policy branches, Win diplomatic vote, Become the world religion, Have highest score after max turns, Have more [countable] than each player's [countable]".tr()
            .toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f))
        milestoneHint.wrap = true
        formTable.add(milestoneHint).growX().left().pad(0f, 8f, 6f, 8f).row()

        // requiredSpaceshipParts（chips）—— 仅当存在 "Add all [comment] in capital" 里程碑时才显示
        spaceshipChips.clear()
        spaceshipChips.addAll(item.getStringList("requiredSpaceshipParts"))
        val hasAddAllMilestone = milestones.any { it.getPlaceholderText() == "Add all [] in capital" }
        spaceshipSectionVisible = hasAddAllMilestone || spaceshipChips.isNotEmpty()
        if (spaceshipSectionVisible) {
            formTable.add(sectionHeader("Required spaceship parts".tr())).fillX().row()
            spaceshipChipTable = Table(BaseScreen.skin)
            refreshSpaceshipChips()
            formTable.add(chipBox(spaceshipChipTable)).growX().left().pad(4f, 10f, 4f, 10f).row()
            val shipHint = "Units that must be added to the capital for the \"Add all [comment] in capital\" milestone".tr()
                .toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f))
            shipHint.wrap = true
            formTable.add(shipHint).growX().left().pad(0f, 8f, 6f, 8f).row()
        }

        // 胜利插画：Images/VictoryIllustrations/<name>/Won.png（游戏胜利画面显示）
        formTable.add(sectionHeader("Image (VictoryIllustrations)".tr())).fillX().row()
        val victoryImage = ModEditorImageSection(
            modFolder = modFolder,
            subDirectory = "VictoryIllustrations/${current().name}",
            fileName = { "Won" }
        )
        victoryImage.addImageSection(formTable)

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

        formTable.add(sectionHeader("Comment".tr())).fillX().row()
        commentArea = TextArea(item.comment, BaseScreen.skin)
        formTable.add(commentArea).growX().height(100f).left().pad(6f).row()
    }

    private fun textAreaBlock(labelKey: String, initial: String): TextArea {
        val area = TextArea(initial, BaseScreen.skin)
        val scroll = AutoScrollPane(area).apply {
            setOverscroll(false, false)
            setScrollingDisabled(true, false)
        }
        val row = Table(BaseScreen.skin)
        row.add(labelKey.tr().toLabel()).left().pad(4f).width(180f).top()
        row.add(scroll).growX().height(70f).pad(4f)
        formTable.add(row).growX().left().row()
        return area
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

    /** 飞船部件 chips：+ 按钮弹窗列出全部单位（自动搜索） */
    private fun refreshSpaceshipChips() {
        spaceshipChipTable.clear()
        val maxWidth = formAvailableWidth(stage.width, extraDeduction = 40f)
        var x = 0f
        for ((i, name) in spaceshipChips.withIndex()) {
            val chip = Table(BaseScreen.skin)
            chip.background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/Chip", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
                Color(0.25f, 0.4f, 0.55f, 1f))
            val label = name.toLabel(fontSize = 14)
            val labelWidth = minOf(label.prefWidth, 260f)
            label.setEllipsis(true)
            label.width = labelWidth
            label.setSize(labelWidth, label.prefHeight)
            chip.add(label).width(labelWidth).pad(4f)
            val xBtn = "×".toLabel(fontSize = 18, fontColor = Color(1f, 0.6f, 0.6f, 1f))
            xBtn.touchable = Touchable.enabled
            xBtn.onActivation {
                spaceshipChips.removeAt(i)
                refreshSpaceshipChips()
            }
            chip.add(xBtn).pad(4f)
            chip.pack()
            if (x + chip.width > maxWidth && x > 0f) { spaceshipChipTable.row(); x = 0f }
            spaceshipChipTable.add(chip).pad(2f)
            x += chip.width + 4f
        }
        val addBtn = "+".toTextButton()
        addBtn.onActivation { showSpaceshipPartPopup() }
        if (x + 40f > maxWidth && x > 0f) spaceshipChipTable.row()
        spaceshipChipTable.add(addBtn).pad(4f)
    }

    /** 飞船部件选择弹窗：检索全部单位，搜索过滤，点选即加 */
    private fun showSpaceshipPartPopup() {
        val units = ModEditorData.getUnits(modFolder)
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        popup.add("Add spaceship part".tr().toLabel(fontSize = 20)).pad(8f).row()
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
            for (unit in units) {
                if (q.isNotEmpty() && !unit.lowercase().contains(q)) continue
                val row = Table(BaseScreen.skin)
                row.background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/SearchRow", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                    BaseScreen.skinStrings.skinConfig.baseColor)
                val lbl = bilingualUniqueLabel(unit, unit.tr(), 15f)
                lbl.wrap = true
                row.add(lbl).growX().left().pad(6f, 8f, 6f, 8f)
                row.touchable = Touchable.enabled
                row.onActivation {
                    if (unit !in spaceshipChips) spaceshipChips.add(unit)
                    refreshSpaceshipChips()
                    popup.close()
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

    /** 里程碑参数化编辑：与 uniques 同款行内编辑器（catalog 的 Milestones 分类） */
    private fun rebuildMilestonesTable() {
        milestonesTable.clear()
        if (milestones.isEmpty()) {
            milestonesTable.add("(no milestones)".toLabel(fontSize = 15, fontColor = Color(1f, 1f, 1f, 0.4f)))
                .left().pad(4f).row()
        }
        for ((index, rawString) in milestones.withIndex()) {
            val parsed = uniqueCatalog.parseRaw(rawString)
            if (parsed != null) {
                lateinit var editor: UniqueInlineEditor
                editor = UniqueInlineEditor(
                    screen = this, modFolder = modFolder, catalog = uniqueCatalog,
                    unique = parsed.unique, values = parsed.values, conditions = parsed.conditions,
                    onValueChange = { milestones[index] = editor.buildRaw() },
                    onStructureChange = {
                        milestones[index] = editor.buildRaw()
                        rebuildMilestonesTable()
                    },
                    onDuplicate = {
                        val copyValues = parsed.values.toMutableMap()
                        val copyConditions = parsed.conditions
                            .map { (c, v) -> c to v.toMutableMap() }.toMutableList()
                        milestones.add(index + 1,
                            uniqueCatalog.buildRawString(parsed.unique, copyValues, copyConditions))
                        rebuildMilestonesTable()
                    },
                    onDelete = {
                        milestones.removeAt(index)
                        rebuildMilestonesTable()
                    }
                )
                milestonesTable.add(editor).growX().left().pad(3f, 8f, 3f, 8f).row()
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
                editButton.onActivation { showMilestoneRawEditor(index, rawString) }
                row.add(editButton).pad(4f)
                val removeButton = "×".toTextButton()
                removeButton.onActivation {
                    milestones.removeAt(index)
                    rebuildMilestonesTable()
                }
                row.add(removeButton).pad(4f)
                milestonesTable.add(row).growX().left().row()
            }
        }
        val addButton = "+ Add milestone".toTextButton()
        addButton.onActivation {
            game.pushScreen(UniquePickerScreen(
                onPick = { unique ->
                    val values = unique.params
                        .filter { it.default.isNotBlank() }
                        .associate { it.id to it.default }.toMutableMap()
                    milestones.add(uniqueCatalog.buildRawString(unique, values, emptyList()))
                    rebuildMilestonesTable()
                },
                onRawPicked = { text ->
                    milestones.add(text)
                    rebuildMilestonesTable()
                },
                onlyCategory = "Milestones"
            ))
        }
        milestonesTable.add(addButton).left().pad(6f)
        milestonesTable.row()
    }

    private fun showMilestoneRawEditor(index: Int, existing: String) {
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        popup.add("Milestone (raw mode)".tr().toLabel(fontSize = 22)).pad(10f).row()
        val textArea = TextArea(existing, BaseScreen.skin)
        popup.add(textArea).width(560f).height(160f).pad(6f).row()
        popup.addButton("Save".tr()) {
            val text = textArea.text.replace('\n', ' ').replace('\r', ' ')
                .replace(Regex("\\s{2,}"), " ").trim()
            if (text.isNotEmpty()) milestones[index] = text
            else milestones.removeAt(index)
            popup.close()
            rebuildMilestonesTable()
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
        if (newName.isBlank()) { showMessage("Victory name cannot be empty".tr()); return }
        item.name = newName
        item.setString("name", newName)
        item.setString("victoryScreenHeader", headerArea.text.trim())
        item.setString("victoryString", victoryArea.text.trim())
        item.setString("defeatString", defeatArea.text.trim())
        if (hiddenCheck.isChecked) item.raw["hiddenInVictoryScreen"] = true
        else item.raw.remove("hiddenInVictoryScreen")
        if (milestones.isEmpty()) item.raw.remove("milestones")
        else item.raw["milestones"] = milestones.toList()
        if (spaceshipChips.isEmpty()) item.raw.remove("requiredSpaceshipParts")
        else item.raw["requiredSpaceshipParts"] = spaceshipChips.toList()
        val cpEntries = civilopediaEditor.buildEntries()
        if (cpEntries == null) item.raw.remove("civilopediaText") else item.raw["civilopediaText"] = cpEntries
        item.comment = commentArea.text
        item.syncUniques()

        val problems = ModEditorData.validateVictoryType(modFolder, item, items)
        val errors = problems.filter { it.second }
        if (errors.isNotEmpty()) { showProblemsPopup(problems, onSaveAnyway = null); return }
        if (problems.isNotEmpty()) { showProblemsPopup(problems) { doSave() }; return }
        doSave()
    }

    private fun doSave() {
        ModEditorData.saveVictoryTypes(modFolder, items)
        val gameProblems = ModEditorData.filterGameProblems(
            ModEditorData.runGameValidation(modFolder), "VictoryTypes.json")
        val errors = gameProblems.filter { it.second }
        if (errors.isNotEmpty()) {
            ModEditorData.rollbackFile(modFolder, "VictoryTypes.json")
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
        val baseItems = ModEditorData.loadBaseObjects(modFolder, "VictoryTypes.json", sourceRuleset)
        if (baseItems.isEmpty()) { showMessage("No victory types found in the base ruleset".tr()); return }
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
        popup.add("Copy victory type from ruleset".tr().toLabel(fontSize = 20)).pad(8f).row()
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
                formTable.add("No victory types. Click \"+ New victory type\" in the top left.".tr().toLabel()).pad(20f).row()
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

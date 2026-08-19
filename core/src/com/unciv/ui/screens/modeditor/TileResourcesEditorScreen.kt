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

/** TileResources 编辑器：按奖金/战略/奢侈分组的列表 + 表单（2026-08-19） */
class TileResourcesEditorScreen(private val modFolder: FileHandle) : BaseScreen() {

    private val items = ModEditorData.loadTileResources(modFolder)
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

    private enum class DisplayGroup(val label: String, val color: Color) {
        Bonus("奖金", Color(0.45f, 0.8f, 0.45f, 1f)),
        Strategic("战略", Color(0.45f, 0.6f, 0.95f, 1f)),
        Luxury("奢侈", Color(0.95f, 0.8f, 0.3f, 1f));
    }
    private val expandedGroups = HashSet<DisplayGroup>()
    private fun getDisplayGroup(item: ModObjectData): DisplayGroup =
        when (item.getString("resourceType")) {
            "Strategic" -> DisplayGroup.Strategic
            "Luxury" -> DisplayGroup.Luxury
            else -> DisplayGroup.Bonus
        }

    // 表单控件
    private lateinit var nameField: UncivTextField
    private lateinit var typeBox: ModEditorSelectBox
    private lateinit var revealedByBox: ModEditorSelectBox
    private lateinit var improvementBox: ModEditorSelectBox
    private lateinit var tileSetBox: ModEditorSelectBox
    private lateinit var uniquesTable: Table
    private lateinit var uniquesButtonRow: Table
    private lateinit var civilopediaEditor: CivilopediaTextEditor
    private lateinit var commentArea: TextArea
    private var selectedRowNameLabel: Label? = null
    private val statFields = LinkedHashMap<String, UncivTextField>()
    private val improvementStats = LinkedHashMap<String, Double>()
    private val depositAmountFields = LinkedHashMap<String, LinkedHashMap<String, UncivTextField>>()

    private fun current(): ModObjectData = items[selectedIndex]

    init {
        val root = Table(BaseScreen.skin)
        root.setFillParent(true)
        stage.addActor(root)

        val topBar = Table(BaseScreen.skin)
        val backButton = ("‹ " + "Back".tr()).toTextButton()
        backButton.onActivation { game.popScreen() }
        topBar.add(backButton).pad(8f)
        topBar.add(("TileResources".tr() + " · TileResources.json").toLabel(fontSize = 28))
            .padLeft(20f).expandX().left()
        val saveButton = "Save".toTextButton()
        saveButton.onActivation { save() }
        topBar.add(saveButton).pad(8f)
        topBar.add(statusLabel).pad(8f)
        root.add(topBar).fillX().row()

        // 左侧：按钮行 + 搜索 + 分组列表（手风琴）
        val leftPanel = Table(BaseScreen.skin)
        val buttonRow = Table(BaseScreen.skin)
        val addButton = "+ New resource".toTextButton()
        addButton.onActivation { addItem() }
        buttonRow.add(addButton).left().pad(6f)
        val copyButton = "Copy resource from ruleset".toTextButton()
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
        else formTable.add("No resources. Click \"+ New resource\" in the top left.".toLabel()).pad(20f).row()
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
        "ModEditor/ResRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        BaseScreen.skinStrings.skinConfig.baseColor)

    private fun selectedRowBackground() = BaseScreen.skinStrings.getUiBackground(
        "ModEditor/ResRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        Color(0.2f, 0.5f, 0.9f, 1f))

    // ------------------------------------------------------------------
    // 列表（手风琴：奖金/战略/奢侈）
    // ------------------------------------------------------------------

    private fun refreshList() {
        listTable.clear()
        for (group in DisplayGroup.entries) {
            val groupItems = items.withIndex().filter { (_, item) -> getDisplayGroup(item) == group }
                .filter { (_, item) -> searchQuery.isEmpty() ||
                    item.name.lowercase().contains(searchQuery) ||
                    item.name.tr().lowercase().contains(searchQuery) }
            if (groupItems.isEmpty() && searchQuery.isNotEmpty()) continue
            val isExpanded = group in expandedGroups

            val header = Table(BaseScreen.skin)
            header.background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/ResGroup_${group.name}",
                BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
                Color(1f, 1f, 1f, 0.08f))
            val colorBar = Table(BaseScreen.skin).apply {
                background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/ResGroupColor_${group.name}", null, group.color)
            }
            colorBar.setSize(4f, 1f)
            header.add(colorBar).width(4f).growY().pad(0f)
            header.add(((if (isExpanded) "▾ " else "▸ ") + group.label).toLabel(fontSize = 20, fontColor = group.color))
                .left().expandX().pad(10f, 10f, 10f, 8f)
            header.add(groupItems.size.toString().toLabel(fontSize = 16, fontColor = Color(1f, 1f, 1f, 0.35f)))
                .right().pad(10f, 8f, 10f, 8f)
            header.touchable = Touchable.enabled
            header.onActivation {
                if (isExpanded) expandedGroups.remove(group) else expandedGroups.add(group)
                refreshList()
            }
            listTable.add(header).fillX().pad(3f, 4f, 0f, 4f).row()

            if (!isExpanded) { listTable.add().height(4f).fillX().row(); continue }

            for ((listIdx, indexedItem) in groupItems.withIndex()) {
                val index = indexedItem.index; val item = indexedItem.value
                val isSelected = index == selectedIndex
                val row = Table(BaseScreen.skin)
                row.defaults().pad(6f)
                row.background = if (isSelected) selectedRowBackground() else rowBackground()
                if (isSelected) {
                    val indicator = Table(BaseScreen.skin).apply {
                        background = BaseScreen.skinStrings.getUiBackground(
                            "ModEditor/SelIndicator", null, group.color)
                    }
                    indicator.setSize(3f, 1f)
                    row.add(indicator).width(3f).growY().pad(0f)
                }
                val nameLabel = listNameLabel(
                    item.name.ifBlank { "(unnamed)".tr() },
                    maxWidth = stage.width * 0.25f - 100f,
                    fontSize = 18,
                    fontColor = if (isSelected) Color.WHITE else Color(1f, 1f, 1f, 0.85f))
                if (isSelected) selectedRowNameLabel = nameLabel
                row.add(nameLabel).left().expandX().maxWidth(stage.width * 0.25f - 60f).pad(0f, 10f, 0f, 10f)
                row.touchable = Touchable.enabled
                row.onActivation { selectedIndex = index; refreshList(); rebuildForm() }
                listTable.add(row).fillX().pad(1f, 8f, 1f, 8f).row()
            }
            listTable.add().height(6f).fillX().row()
        }
        if (items.isEmpty()) {
            listTable.add("No resources yet.".toLabel(fontSize = 16, fontColor = Color(1f, 1f, 1f, 0.4f))).pad(20f).row()
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
        item.setString("resourceType", "Bonus")
        items.add(item)
        expandedGroups.add(getDisplayGroup(item))
        select(items.lastIndex)
    }

    private fun nextName(): String {
        val existing = items.map { it.name }.toSet()
        var i = 1
        while (("New resource" + if (i == 1) "" else " $i") in existing) i++
        return "New resource" + if (i == 1) "" else " $i"
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
        statFields.clear()
        improvementStats.clear()
        depositAmountFields.clear()
        if (::typeBox.isInitialized) typeBox.disposeFloating()
        if (::revealedByBox.isInitialized) revealedByBox.disposeFloating()
        if (::improvementBox.isInitialized) improvementBox.disposeFloating()
        if (::tileSetBox.isInitialized) tileSetBox.disposeFloating()
        val item = current()

        val header = Table(BaseScreen.skin)
        header.add("Edit resource".toLabel(fontSize = 24)).left().expandX()
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

        nameField = UncivTextField("Resource name (required)", item.name)
        nameField.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                selectedRowNameLabel?.setText(nameField.text.ifBlank { "(unnamed)".tr() })
            }
        })
        addFieldRow("Name", nameField)

        // resourceType：预定义枚举（Bonus/Luxury/Strategic）
        typeBox = searchableBox(listOf("Bonus", "Strategic", "Luxury"), item.getString("resourceType"))
        typeBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                item.setString("resourceType", typeBox.selected?.value?.takeUnless { it == "(None)" })
                refreshList()
                expandedGroups.add(getDisplayGroup(item))
                rebuildForm() // 类型切换影响显示字段（depositAmount 仅战略资源），必须重建表单
            }
        })
        addFieldRow("Resource type", typeBox)

        ModEditorListSection(
            screen = this,
            label = "terrainsCanBeFoundOn",
            options = { ModEditorData.getTerrains(modFolder) },
            getValues = { item.getStringList("terrainsCanBeFoundOn") },
            setValues = { item.setStringList("terrainsCanBeFoundOn", it) }
        ).addTo(formTable)

        revealedByBox = searchableBox(ModEditorData.getTechs(modFolder), item.getString("revealedBy"))
        revealedByBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                item.setString("revealedBy", revealedByBox.selected?.value?.takeUnless { it == "(None)" })
            }
        })
        addFieldRow("Revealed by", revealedByBox)

        val allImprovements = ModEditorData.getImprovements(modFolder)
        improvementBox = searchableBox(allImprovements, item.getString("improvement"))
        improvementBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                item.setString("improvement", improvementBox.selected?.value?.takeUnless { it == "(None)" })
            }
        })
        addFieldRow("Improvement", improvementBox)

        // improvedBy（chips 多选）
        ModEditorListSection(
            screen = this,
            label = "improvedBy",
            options = { allImprovements },
            getValues = { item.getStringList("improvedBy") },
            setValues = { item.setStringList("improvedBy", it) }
        ).addTo(formTable)

        formTable.add(sectionHeader("Stats".tr())).fillX().row()

        var statsRow = Table(BaseScreen.skin)
        for (stat in listOf("production", "food", "gold", "science", "culture", "happiness", "faith")) {
            val field = decimalField(item.getIntText(stat))
            statFields[stat] = field
            addStatPair(statsRow, stat, field)
            if (statFields.size % 4 == 0 && stat != "faith") {
                formTable.add(statsRow).growX().left().row()
                statsRow = Table(BaseScreen.skin) // 必须新建 Table，不能 clear() 旧对象（会清掉已加入 formTable 的那行）
            }
        }
        formTable.add(statsRow).growX().left().row()

        // improvementStats：改良后的额外产出（specialized stats 映射）
        val impRow = Table(BaseScreen.skin)
        impRow.add("improvementStats".tr().toLabel()).left().pad(4f).width(180f)
        val impStats = item.raw["improvementStats"] as? Map<*, *>
        if (impStats != null) {
            for ((k, v) in impStats) {
                improvementStats[k.toString()] = when (v) {
                    is Number -> v.toDouble()
                    is String -> v.toDoubleOrNull() ?: 0.0
                    else -> 0.0
                }
            }
        }
        val impButton = (improvementStats.entries.joinToString(", ") { (k, v) -> "$k: ${formatInt(v)}" }.ifBlank { "(None)" })
            .toTextButton()
        impButton.onActivation { showImprovementStatsEditor(impButton) }
        impRow.add(impButton).growX().minWidth(200f).pad(4f)
        formTable.add(impRow).growX().left().row()

        // majorDepositAmount / minorDepositAmount：仅战略资源有意义的储量（sparse/default/abundant 三档）
        if (getDisplayGroup(item) == DisplayGroup.Strategic) {
            addDepositAmountRow("Major deposit amount", item, "majorDepositAmount")
            addDepositAmountRow("Minor deposit amount", item, "minorDepositAmount")
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

        civilopediaEditor = CivilopediaTextEditor(
            screen = this,
            getRaw = { item.raw["civilopediaText"] },
            setRaw = { item.raw["civilopediaText"] = it }
        )
        civilopediaEditor.addTo(formTable, "Civilopedia text")

        formTable.add(sectionHeader("Image (Images/ResourceIcons/)".tr())).fillX().row()
        ModEditorImageSection(
            modFolder = modFolder,
            subDirectory = "ResourceIcons",
            fileName = { current().name },
            preCheck = { if (nameField.text.trim().isBlank()) "Enter a resource name first, then choose an image".tr() else null }
        ).addImageSection(formTable)

        // 资源地图贴图：Images/TileSets/<图集>/Tiles/<name>.png（与地形/设施同一图集体系）
        formTable.add(sectionHeader("Tileset image (map art)".tr())).fillX().row()
        val tileSets = ImageGetter.getAvailableTilesets().toList()
        val currentTileSet = ModEditorData.readUnitSetChoice(modFolder)
        tileSetBox = ModEditorSelectBox(
            listOf("(None)") + tileSets,
            currentTileSet.ifBlank { "(None)" }, searchable = true)
        tileSetBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                ModEditorData.writeUnitSetChoice(modFolder,
                    tileSetBox.selected?.value?.takeUnless { it == "(None)" } ?: "")
            }
        })
        val tileSetRow = Table(BaseScreen.skin)
        tileSetRow.add("Tile set".tr().toLabel()).left().pad(6f)
        tileSetRow.add(tileSetBox).growX().minWidth(180f).pad(6f)
        formTable.add(tileSetRow).growX().left().row()
        ModEditorImageSection(
            modFolder = modFolder,
            subDirectory = "TileSets/${currentTileSet.ifBlank { "FantasyHex" }}/Tiles",
            fileName = { current().name },
            preCheck = {
                if (tileSetBox.selected?.value == null || tileSetBox.selected?.value == "(None)")
                    "Please choose a tile set first" else null
            }
        ).addImageSection(formTable)

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

    /** 储量编辑行：sparse/default/abundant 三个整数输入框（仅战略资源） */
    private fun addDepositAmountRow(labelKey: String, item: ModObjectData, rawKey: String) {
        val row = Table(BaseScreen.skin)
        row.add(labelKey.tr().toLabel()).left().pad(4f).width(180f).top()
        val inner = Table(BaseScreen.skin)
        val raw = item.raw[rawKey] as? Map<*, *>
        val fields = LinkedHashMap<String, UncivTextField>()
        fun read(k: String): String {
            val v = raw?.get(k)
            return when (v) {
                is Number -> v.toString()
                is String -> v
                else -> ""
            }
        }
        for (slot in listOf("Sparse", "Default", "Abundant")) {
            val field = numberField(read(slot.lowercase()))
            fields[slot.lowercase()] = field
            inner.add(slot.tr().toLabel()).left().pad(4f).width(90f)
            inner.add(field).width(70f).pad(4f)
        }
        inner.add("Sparse/default/abundant".tr().toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.4f)))
            .left().pad(4f)
        row.add(inner).growX().left().pad(4f)
        formTable.add(row).growX().left().row()

        // 保存时统一写入（避免每次输入都改 raw）
        depositAmountFields[rawKey] = fields
    }

    /** improvementStats 映射编辑器：产出 → 数值 */
    private fun showImprovementStatsEditor(button: TextButton) {
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        popup.add("improvementStats".tr().toLabel(fontSize = 20)).pad(8f).row()
        val stats = listOf("production", "food", "gold", "science", "culture", "happiness", "faith")
        val listTable = Table(BaseScreen.skin)
        val rows = mutableListOf<Pair<ModEditorSelectBox, UncivTextField>>()

        fun addRow(stat: String, value: Int) {
            val statBox = ModEditorSelectBox(stats, stat, searchable = true)
            val valueField = numberField(value.toString())
            val row = Table(BaseScreen.skin)
            row.add(statBox).growX().minWidth(200f).pad(4f)
            row.add(valueField).width(100f).pad(4f)
            val removeButton = "×".toTextButton()
            removeButton.onActivation {
                rows.removeIf { it.first === statBox }
                row.remove()
            }
            row.add(removeButton).pad(4f)
            listTable.add(row).growX().left().row()
            rows.add(statBox to valueField)
        }

        if (improvementStats.isEmpty()) addRow("gold", 1)
        else for ((stat, value) in improvementStats) addRow(stat, value.toInt())

        val scroll = AutoScrollPane(listTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        popup.add(scroll).grow().width(480f).height(300f).pad(6f).row()
        popup.addButton("+ Add") { addRow("gold", 1) }
        popup.addButton("Save".tr()) {
            improvementStats.clear()
            for ((statBox, valueField) in rows) {
                val stat = statBox.selected?.value ?: continue
                val value = valueField.text.trim().toIntOrNull() ?: continue
                improvementStats[stat] = value.toDouble()
            }
            button.setText(improvementStats.entries.joinToString(", ") { (k, v) -> "$k: ${formatInt(v)}" }.ifBlank { "(None)" })
            popup.close()
        }
        popup.addCloseButton()
        popup.open()
    }

    /** 整数值不带小数点显示（1.0 → 1） */
    private fun formatInt(v: Double): String {
        val asInt = v.toInt()
        return if (asInt.toDouble() == v) asInt.toString() else v.toString()
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
        popup.add("Unique (raw mode)".toLabel(fontSize = 22)).pad(10f).row()
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
        if (newName.isBlank()) { showMessage("Resource name cannot be empty".tr()); return }
        item.name = newName
        item.setString("name", newName)
        for ((key, field) in statFields) {
            val text = field.text.trim()
            if (text.isBlank()) item.raw.remove(key)
            else {
                val v = text.toDoubleOrNull()
                if (v == null) { showMessage("Invalid number:".tr() + " " + text); return }
                item.setNumber(key, v)
            }
        }
        if (improvementStats.isEmpty()) item.raw.remove("improvementStats")
        else item.raw["improvementStats"] = LinkedHashMap(improvementStats)
        for ((rawKey, fields) in depositAmountFields) {
            val map = LinkedHashMap<String, Any?>()
            var hasAny = false
            for ((slot, field) in fields) {
                val text = field.text.trim()
                if (text.isBlank()) continue
                val v = text.toIntOrNull()
                if (v == null) { showMessage("Invalid number:".tr() + " " + text); return }
                map[slot] = v
                hasAny = true
            }
            if (hasAny) item.raw[rawKey] = map else item.raw.remove(rawKey)
        }
        val cpEntries = civilopediaEditor.buildEntries()
        if (cpEntries == null) item.raw.remove("civilopediaText") else item.raw["civilopediaText"] = cpEntries
        item.comment = commentArea.text
        item.syncUniques()

        val problems = ModEditorData.validateTileResource(modFolder, item, items)
        val errors = problems.filter { it.second }
        if (errors.isNotEmpty()) { showProblemsPopup(problems, onSaveAnyway = null); return }
        if (problems.isNotEmpty()) { showProblemsPopup(problems) { doSave() }; return }
        doSave()
    }

    private fun doSave() {
        ModEditorData.saveTileResources(modFolder, items)
        val gameProblems = ModEditorData.filterGameProblems(
            ModEditorData.runGameValidation(modFolder), "TileResources.json")
        val errors = gameProblems.filter { it.second }
        if (errors.isNotEmpty()) {
            ModEditorData.rollbackFile(modFolder, "TileResources.json")
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
        val baseItems = ModEditorData.loadBaseObjects(modFolder, "TileResources.json", sourceRuleset)
        if (baseItems.isEmpty()) { showMessage("No resources found in the base ruleset"); return }
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
        popup.add("Copy resource from ruleset".tr().toLabel(fontSize = 20)).pad(8f).row()
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
                formTable.add("No resources. Click \"+ New resource\" in the top left.".toLabel()).pad(20f).row()
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

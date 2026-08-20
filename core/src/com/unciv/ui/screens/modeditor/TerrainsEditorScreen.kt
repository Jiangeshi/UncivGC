package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextArea
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
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

/**
 * 地形编辑器：name / type / occursOn / turnsInto / weight / 产出 stats /
 * overrideStats / unbuildable / impassable / movementCost / defenceBonus / RGB / uniques / civilopediaText
 * 注意：type 是游戏 lateinit 必需字段（缺失会让整个 mod 加载崩溃）
 */
class TerrainsEditorScreen(private val modFolder: FileHandle) : BaseScreen() {

    private val items = ModEditorData.loadTerrains(modFolder)
    private var selectedIndex = -1
    private val uniqueCatalog = UniqueCatalog.load()

    private val listTable = Table(BaseScreen.skin).apply {
        defaults().expandX().fillX()
        top()   // 列表内容不满时顶部对齐，否则垂直居中（libGDX Table 默认会居中）
    }
    private val formTable = FillWidthTable(BaseScreen.skin)
    private val statusLabel = "".toLabel(fontSize = 16)

    // 分类：按地形类型
    private enum class DisplayGroup(val label: String, val color: Color) {
        Land("Land".tr(), Color(0.75f, 0.55f, 0.25f, 1f)),
        Water("Water".tr(), Color(0.27f, 0.78f, 0.8f, 1f)),
        TerrainFeature("TerrainFeature".tr(), Color(0.45f, 0.78f, 0.3f, 1f)),
        NaturalWonder("NaturalWonder".tr(), Color(0.85f, 0.75f, 0.2f, 1f));
    }
    private val expandedGroups = HashSet<DisplayGroup>()
    private fun getDisplayGroup(item: ModObjectData): DisplayGroup = when (item.getString("type")) {
        "Water" -> DisplayGroup.Water
        "TerrainFeature" -> DisplayGroup.TerrainFeature
        "NaturalWonder" -> DisplayGroup.NaturalWonder
        else -> DisplayGroup.Land
    }

    private lateinit var nameField: UncivTextField
    private lateinit var typeBox: ModEditorSelectBox
    private lateinit var turnsIntoBox: ModEditorSelectBox
    private lateinit var weightField: UncivTextField
    private lateinit var foodField: UncivTextField
    private lateinit var goldField: UncivTextField
    private lateinit var productionField: UncivTextField
    private lateinit var scienceField: UncivTextField
    private lateinit var cultureField: UncivTextField
    private lateinit var happinessField: UncivTextField
    private lateinit var faithField: UncivTextField
    private lateinit var overrideStatsCheck: CheckBox
    private lateinit var unbuildableCheck: CheckBox
    private lateinit var impassableCheck: CheckBox
    private lateinit var movementCostField: UncivTextField
    private lateinit var defenceBonusField: UncivTextField
    private lateinit var rgbRField: UncivTextField
    private lateinit var rgbGField: UncivTextField
    private lateinit var rgbBField: UncivTextField
    private lateinit var uniquesTable: Table
    private lateinit var uniquesButtonRow: Table
    private lateinit var civilopediaEditor: CivilopediaTextEditor
    private lateinit var commentArea: TextArea
    private lateinit var searchField: UncivTextField
    private var searchQuery = ""
    private lateinit var tileSetBox: ModEditorSelectBox
    private lateinit var imageSection: ModEditorImageSection
    private lateinit var currentItemName: String

    private fun current() = items[selectedIndex]

    init {
        val root = Table(BaseScreen.skin)
        root.setFillParent(true)
        stage.addActor(root)

        val topBar = Table(BaseScreen.skin)
        val backButton = ("‹ " + "Back".tr()).toTextButton()
        backButton.onActivation { game.popScreen() }
        topBar.add(backButton).pad(8f)
        topBar.add(("Terrains".tr() + " · Terrains.json").toLabel(fontSize = 28))
            .padLeft(20f).expandX().left()
        val saveButton = "Save".toTextButton()
        saveButton.onActivation { save() }
        topBar.add(saveButton).pad(8f)
        topBar.add(statusLabel).pad(8f)
        root.add(topBar).fillX().row()

        val leftPanel = Table(BaseScreen.skin)
        val buttonRow = Table(BaseScreen.skin)
        val addButton = "+ New terrain".toTextButton()
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
            setScrollingDisabled(false, false)
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
        else formTable.add("No terrains. Click \"+ New terrain\" in the top left.".toLabel()).pad(20f).row()
    }

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
                "ModEditor/TGroup_${group.name}",
                BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
                Color(1f, 1f, 1f, 0.08f))
            val colorBar = Table(BaseScreen.skin).apply {
                background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/TGroupColor_${group.name}", null, group.color)
            }
            colorBar.setSize(4f, 1f)
            header.add(colorBar).width(4f).growY().pad(0f)
            header.add(listNameLabel((if (isExpanded) "▾ " else "▸ ") + group.label, maxWidth = max(80f, stage.width * 0.25f - 140f), fontSize = 20, fontColor = group.color))
                .left().expandX().pad(10f, 10f, 10f, 8f)
            header.add(groupItems.size.toString().toLabel(fontSize = 16, fontColor = Color(1f, 1f, 1f, 0.35f)))
                .right().pad(10f, 8f, 10f, 8f)
            header.touchable = com.badlogic.gdx.scenes.scene2d.Touchable.enabled
            header.onActivation {
                if (isExpanded) expandedGroups.remove(group) else expandedGroups.add(group)
                refreshList()
            }
            listTable.add(header).fillX().pad(3f, 4f, 0f, 4f).row()

            if (!isExpanded) { listTable.add().height(4f).fillX().row(); continue }

            for ((listIdx, indexedItem) in groupItems.withIndex()) {
                val i = indexedItem.index; val item = indexedItem.value
                val isSelected = i == selectedIndex
                val row = Table(BaseScreen.skin)
                row.defaults().pad(12f)
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
                    item.name,
                    maxWidth = stage.width * 0.25f - 100f,
                    fontSize = 20,
                    fontColor = if (isSelected) Color.WHITE else Color(1f, 1f, 1f, 0.85f))
                row.add(nameLabel).left().expandX().maxWidth(stage.width * 0.25f - 100f).pad(11f, 12f, 11f, 12f)
                row.touchable = com.badlogic.gdx.scenes.scene2d.Touchable.enabled
                row.onActivation { selectedIndex = i; refreshList(); rebuildForm() }
                listTable.add(row).fillX().pad(2f, 6f, 2f, 6f).row()
            }
            listTable.add().height(6f).fillX().row()
        }
        if (items.isEmpty()) {
            listTable.add("No terrains yet.".toLabel()).pad(20f).row()
        }
    }

    private fun rebuildForm() {
        formTable.clear()
        if (selectedIndex < 0 || selectedIndex >= items.size) return
        val item = current()

        // 表单头：标题 + Duplicate + Delete（与单位/建筑编辑器一致）
        val header = Table(BaseScreen.skin)
        header.add("Edit terrain".toLabel(fontSize = 24)).left().expandX()
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

        typeBox = ModEditorSelectBox(
            listOf("Land", "Water", "TerrainFeature", "NaturalWonder"),
            item.getString("type").ifBlank { "Land" }, searchable = true)
        typeBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                item.raw["type"] = typeBox.selected?.value ?: "Land"
                expandedGroups.add(getDisplayGroup(item))   // 先加入新组再刷新，否则新组不展开（2026-08-19）
                refreshList()
                rebuildForm()  // 不同 type 可用字段不同（occursOn/turnsInto/weight 等），重建表单
            }
        })
        val typeRow = Table(BaseScreen.skin)
        typeRow.add("type".tr().toLabel()).left().pad(4f).width(220f)
        typeRow.add(typeBox).growX().minWidth(200f).pad(4f)   // 行内控件必须 growX，否则右侧空白
        formTable.add(typeRow).fillX().left().pad(4f, 10f, 4f, 10f).row()

        val currentType = typeBox.selected?.value ?: "Land"
        val isFeatureOrWonder = currentType == "TerrainFeature" || currentType == "NaturalWonder"
        val isWonder = currentType == "NaturalWonder"

        // occursOn（仅特征/奇观有意义，chips 添加：选择基础地形）
        if (isFeatureOrWonder) {
            ModEditorListSection(
                screen = this,
                label = "occursOn",
                options = { ModEditorData.getTerrains(modFolder) },
                getValues = { item.getStringList("occursOn") },
                setValues = { item.setStringList("occursOn", it) }
            ).addTo(formTable)
        }

        // turnsInto / weight 仅自然奇观
        if (isWonder) {
            val terrains = ModEditorData.getTerrains(modFolder)
            turnsIntoBox = ModEditorSelectBox(
                listOf("(None)") + terrains,
                item.getString("turnsInto") ?: "(None)", searchable = true)
            val turnsIntoRow = Table(BaseScreen.skin)
            turnsIntoRow.add("turnsInto".tr().toLabel()).left().pad(4f).width(220f)
            turnsIntoRow.add(turnsIntoBox).growX().minWidth(200f).pad(4f)   // 行内控件必须 growX
            formTable.add(turnsIntoRow).fillX().left().pad(4f, 10f, 4f, 10f).row()

            weightField = numberField(formTable, "weight (Natural Wonder)", item.getIntText("weight"))
        }

        // 产出 stats
        formTable.add(sectionHeader("Yield stats".tr())).fillX().row()
        foodField = numberField(formTable, "food", item.getIntText("food"))
        goldField = numberField(formTable, "gold", item.getIntText("gold"))
        productionField = numberField(formTable, "production", item.getIntText("production"))
        scienceField = numberField(formTable, "science", item.getIntText("science"))
        cultureField = numberField(formTable, "culture", item.getIntText("culture"))
        happinessField = numberField(formTable, "happiness", item.getIntText("happiness"))
        faithField = numberField(formTable, "faith", item.getIntText("faith"))

        // 勾选
        val flagsRow = Table(BaseScreen.skin)
        overrideStatsCheck = CheckBox("overrideStats".tr(), BaseScreen.skin).apply {
            isChecked = item.raw["overrideStats"] == true
        }
        unbuildableCheck = CheckBox("unbuildable".tr(), BaseScreen.skin).apply {
            isChecked = item.raw["unbuildable"] == true
        }
        impassableCheck = CheckBox("impassable".tr(), BaseScreen.skin).apply {
            isChecked = item.raw["impassable"] == true
        }
        flagsRow.add(overrideStatsCheck).left().pad(6f)
        flagsRow.add(unbuildableCheck).left().pad(6f).padLeft(24f)
        flagsRow.add(impassableCheck).left().pad(6f).padLeft(24f)
        formTable.add(flagsRow).growX().left().row()

        movementCostField = numberField(formTable, "movementCost", item.getIntText("movementCost"))
        defenceBonusField = numberField(formTable, "defenceBonus (float)", item.getIntText("defenceBonus"))

        // RGB
        val rgb = item.raw["RGB"] as? List<*> ?: emptyList<Any?>()
        val rgbRow = Table(BaseScreen.skin)
        rgbRow.add("RGB".tr().toLabel()).left().pad(4f).width(220f)
        rgbRField = rgbNumberField(rgb.getOrNull(0)?.let { (it as? Number)?.toInt() }?.toString() ?: "")
        rgbGField = rgbNumberField(rgb.getOrNull(1)?.let { (it as? Number)?.toInt() }?.toString() ?: "")
        rgbBField = rgbNumberField(rgb.getOrNull(2)?.let { (it as? Number)?.toInt() }?.toString() ?: "")
        rgbRow.add(rgbRField).width(80f).pad(4f)
        rgbRow.add(rgbGField).width(80f).pad(4f)
        rgbRow.add(rgbBField).width(80f).pad(4f)
        rgbRow.add("(0-255)".tr().toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f)))
            .growX().left().pad(4f)   // 行尾占位：吃满剩余空间，否则右侧空白
        formTable.add(rgbRow).fillX().left().pad(4f, 10f, 4f, 10f).row()

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

        // 地形贴图：Images/TileSets/<图集>/Tiles/<name>.png（与单位大图同一图集体系）
        formTable.add(sectionHeader("Terrain image (tile art)".tr())).fillX().row()
        // 注意：用 getAvailableTilesets()（按 Tiles 过滤），不能用 getAvailableUnitsets()（按 Units 过滤，会漏掉 Minimal 等无 Units 的图集）
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
        currentItemName = item.name
        imageSection = ModEditorImageSection(
            modFolder = modFolder,
            subDirectory = "TileSets/${currentTileSet.ifBlank { "FantasyHex" }}/Tiles",
            fileName = { currentItemName },
            preCheck = {
                if (tileSetBox.selected?.value == null || tileSetBox.selected?.value == "(None)")
                    "Please choose a tile set first" else null
            }
        )
        imageSection.addImageSection(formTable)

        civilopediaEditor = CivilopediaTextEditor(
            screen = this,
            getRaw = { item.raw["civilopediaText"] },
            setRaw = { item.raw["civilopediaText"] = it }
        )
        civilopediaEditor.addTo(formTable, "Civilopedia text")
        commentArea = textAreaField(formTable, "Comment", item.comment)
    }

    private fun save() {
        if (selectedIndex < 0) return
        val item = current()
        item.name = nameField.text.trim()
        currentItemName = item.name
        item.raw["name"] = item.name
        item.raw["type"] = typeBox.selected?.value ?: "Land"
        // occursOn 已由 chips 即时写入 raw（ModEditorListSection）；turnsInto/weight 只在对应 type 下显示
        if (::turnsIntoBox.isInitialized) {
            val turnsInto = turnsIntoBox.selected?.value
            if (turnsInto.isNullOrBlank() || turnsInto == "(None)") item.raw.remove("turnsInto")
            else item.raw["turnsInto"] = turnsInto
        }
        if (::weightField.isInitialized) setOptionalInt(item, "weight", weightField)
        setOptionalNumber(item, "food", foodField)
        setOptionalNumber(item, "gold", goldField)
        setOptionalNumber(item, "production", productionField)
        setOptionalNumber(item, "science", scienceField)
        setOptionalNumber(item, "culture", cultureField)
        setOptionalNumber(item, "happiness", happinessField)
        setOptionalNumber(item, "faith", faithField)
        item.raw["overrideStats"] = overrideStatsCheck.isChecked
        item.raw["unbuildable"] = unbuildableCheck.isChecked
        item.raw["impassable"] = impassableCheck.isChecked
        setOptionalInt(item, "movementCost", movementCostField)
        setOptionalNumber(item, "defenceBonus", defenceBonusField)

        val r = rgbRField.text.trim().toIntOrNull()
        val g = rgbGField.text.trim().toIntOrNull()
        val b = rgbBField.text.trim().toIntOrNull()
        if (r != null && g != null && b != null) item.raw["RGB"] = listOf(r, g, b)
        else item.raw.remove("RGB")

        item.syncUniques()
        val cpEntries = civilopediaEditor.buildEntries()
        if (cpEntries == null) item.raw.remove("civilopediaText") else item.raw["civilopediaText"] = cpEntries
        item.comment = commentArea.text.trim()

        val problems = ModEditorData.validateTerrain(modFolder, item, items)
        val errors = problems.filter { it.second }
        val warnings = problems.filter { !it.second }
        if (errors.isNotEmpty()) {
            showProblemsPopup("Save failed".tr(), errors.map { it.first }, true)
            return
        }
        if (warnings.isNotEmpty()) {
            showProblemsPopup("Problems found".tr(), warnings.map { it.first }, false) {
                doSave()
            }
            return
        }
        doSave()
    }

    private fun doSave() {
        ModEditorData.saveTerrains(modFolder, items)
        val gameProblems = ModEditorData.runGameValidation(modFolder)
        val filtered = ModEditorData.filterGameProblems(gameProblems, "Terrains.json")
        val errors = filtered.filter { it.second }
        if (errors.isNotEmpty()) {
            ModEditorData.rollbackFile(modFolder, "Terrains.json")
            statusLabel.setText("Save failed (game validation)")
            showProblemsPopup("Game validation found errors".tr(), errors.map { it.first }, true)
            return
        }
        val warnings = filtered.filter { !it.second }
        if (warnings.isNotEmpty()) {
            statusLabel.setText("Saved with warnings")
            showProblemsPopup("Saved. Game validation warnings:".tr(), warnings.map { it.first }, false)
        } else {
            statusLabel.setText("Saved ✓")
        }
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
        item.name = "New terrain"
        item.raw["name"] = item.name
        item.raw["type"] = "Land"
        item.raw["movementCost"] = 1
        items.add(item)
        selectedIndex = items.lastIndex
        expandedGroups.add(DisplayGroup.Land)
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
        val base = ModEditorData.loadBaseObjects(modFolder, "Terrains.json", sourceRuleset)
        if (base.isEmpty()) {
            showInfoPopup("No terrains found in the base ruleset")
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
        popup.add("Copy terrain from ruleset".toLabel(fontSize = 22)).colspan(2).row()
        popup.add("Same name will override the original in game".toLabel(fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.6f))).colspan(2).padBottom(8f).row()
        val search = UncivTextField("Search")
        val resultsTable = Table(BaseScreen.skin)
        val resultsScroll = AutoScrollPane(resultsTable).apply { setOverscroll(false, false) }
        fun refreshResults(q: String) {
            resultsTable.clear()
            val query = q.lowercase()
            for (item in base) {
                if (query.isNotEmpty() && !item.name.lowercase().contains(query) &&
                    !item.name.tr().lowercase().contains(query)) continue
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
            if (resultsTable.children.isEmpty) {
                resultsTable.add("No results".toLabel()).pad(10f).row()
            }
        }
        search.setTextFieldListener { field, _ -> refreshResults(field.text) }
        popup.add(search).fillX().pad(4f).row()
        popup.add(resultsScroll).size(400f, 300f).pad(4f).row()
        val cancelBtn = "Cancel".toTextButton()
        cancelBtn.onActivation { popup.close() }
        popup.add(cancelBtn).pad(8f)
        popup.open()
        refreshResults("")
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun formField(table: Table, label: String, value: String): UncivTextField {
        val row = Table(BaseScreen.skin)
        row.add(label.tr().toLabel()).left().pad(4f).width(220f)
        val field = UncivTextField("", value)
        row.add(field).growX().minWidth(200f).pad(4f)
        table.add(row).growX().left().pad(4f, 10f, 4f, 10f).row()
        return field
    }

    private fun numberField(table: Table, label: String, value: String): UncivTextField {
        val row = Table(BaseScreen.skin)
        row.add(label.tr().toLabel()).left().pad(4f).width(220f)
        val field = UncivTextField("", value)
        field.textFieldFilter = object : TextField.TextFieldFilter {
            override fun acceptChar(textField: TextField, c: Char): Boolean {
                if (c in '0'..'9') return true
                if (c == '-' && textField.text.isEmpty()) return true
                if (c == '.' && !textField.text.contains('.')) return true
                return false
            }
        }
        row.add(field).growX().minWidth(200f).pad(4f)
        table.add(row).growX().left().pad(4f, 10f, 4f, 10f).row()
        return field
    }

    private fun rgbNumberField(value: String): UncivTextField {
        val field = UncivTextField("", value)
        field.textFieldFilter = object : TextField.TextFieldFilter {
            override fun acceptChar(textField: TextField, c: Char): Boolean = c in '0'..'9'
        }
        return field
    }

    private fun setOptionalInt(item: ModObjectData, key: String, field: UncivTextField) {
        val v = field.text.trim().toIntOrNull()
        if (v == null) item.raw.remove(key) else item.raw[key] = v
    }

    private fun setOptionalNumber(item: ModObjectData, key: String, field: UncivTextField) {
        val text = field.text.trim()
        if (text.isEmpty()) { item.raw.remove(key); return }
        val v = text.toDoubleOrNull() ?: return
        if (v % 1.0 == 0.0) item.raw[key] = v.toInt() else item.raw[key] = v
    }

    private fun textAreaField(table: Table, label: String, value: String): TextArea {
        table.add(label.tr().toLabel()).left().pad(4f, 10f, 0f, 10f).row()
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
        "ModEditor/TerrainRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        BaseScreen.skinStrings.skinConfig.baseColor)

    private fun selectedRowBackground() = BaseScreen.skinStrings.getUiBackground(
        "ModEditor/TerrainRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        Color(0.2f, 0.5f, 0.9f, 1f))
}

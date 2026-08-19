package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.utils.Align
import com.unciv.ui.components.fonts.Fonts
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextArea
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.addSeparatorVertical
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.TranslatedSelectBox
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import kotlin.math.max

/** S3 单位编辑页：列表 + 表单 + 词条 + 图片 + 注释 */
class UnitEditorScreen(private val modFolder: FileHandle) : BaseScreen() {

    private val units = ModEditorData.loadUnits(modFolder)
    private var selectedIndex = -1
    private val uniqueCatalog = UniqueCatalog.load()

    private val listTable = Table(BaseScreen.skin).apply {
        defaults().expandX().fillX()
        top()   // 列表内容不满时顶部对齐，否则垂直居中（libGDX Table 默认会居中）
    }
    private val formTable = FillWidthTable(BaseScreen.skin)
    private val statusLabel = "".toLabel(fontSize = 16)

    // 表单控件（每次选中单位时重建）
    private lateinit var nameField: UncivTextField
    private lateinit var unitTypeBox: ModEditorSelectBox
    private lateinit var uniqueToBox: ModEditorSelectBox
    private lateinit var requiredTechBox: ModEditorSelectBox
    private lateinit var obsoleteTechBox: ModEditorSelectBox
    private lateinit var requiredResourceBox: ModEditorSelectBox
    private lateinit var strengthField: UncivTextField
    private lateinit var rangedStrengthField: UncivTextField
    private lateinit var religiousStrengthField: UncivTextField
    private lateinit var rangeField: UncivTextField
    private lateinit var interceptRangeField: UncivTextField
    private lateinit var movementField: UncivTextField
    private lateinit var costField: UncivTextField
    private lateinit var hurryCostModifierField: UncivTextField
    private lateinit var commentArea: TextArea
    private lateinit var unitSetBox: ModEditorSelectBox
    private lateinit var uniquesTable: Table
    private lateinit var uniquesButtonRow: Table
    private lateinit var civilopediaEditor: CivilopediaTextEditor
    private lateinit var imageStatusLabel: Label
    private var selectedRowNameLabel: Label? = null
    private lateinit var searchField: UncivTextField
    private var searchQuery = ""

    // 分类：平民 / 陆地 / 水上 / 空中
    private enum class DisplayGroup(val label: String, val color: Color) {
        Civilian("平民", Color(0.45f, 0.8f, 0.27f, 1f)),
        Land("陆地", Color(0.75f, 0.55f, 0.25f, 1f)),
        Water("水上", Color(0.27f, 0.78f, 0.8f, 1f)),
        Air("空中", Color(0.55f, 0.55f, 0.9f, 1f));
    }
    private val expandedGroups = HashSet<DisplayGroup>()
    private fun getDisplayGroup(unit: ModObjectData): DisplayGroup {
        val strength = unit.getIntText("strength").toIntOrNull() ?: 0
        val ranged = unit.getIntText("rangedStrength").toIntOrNull() ?: 0
        if (strength == 0 && ranged == 0) return DisplayGroup.Civilian
        val unitType = unit.getString("unitType")
        return when (unitTypeMap[unitType]) {
            "Water" -> DisplayGroup.Water
            "Air" -> DisplayGroup.Air
            else -> DisplayGroup.Land
        }
    }
    private val unitTypeMap: Map<String, String> by lazy {
        // 先加载基础规则集的 unitType → movementType
        val base = ModEditorData.getBaseRuleset(modFolder)
        val map = HashMap<String, String>()
        for ((name, ut) in base.unitTypes) {
            val mt = ut.movementType
            if (mt != null) map[name] = mt
        }
        // 模组 UnitTypes.json 覆盖/追加
        for (ut in ModEditorData.loadUnitTypes(modFolder)) {
            val mt = ut.getString("movementType")
            if (mt.isNotBlank()) map[ut.name] = mt
        }
        map
    }

    private fun currentUnit(): ModObjectData = units[selectedIndex]

    init {
        val root = Table(BaseScreen.skin)
        root.setFillParent(true)
        stage.addActor(root)

        // 顶栏
        val topBar = Table(BaseScreen.skin)
        val backButton = ("‹ " + "Back".tr()).toTextButton()
        backButton.onActivation { game.popScreen() }
        topBar.add(backButton).pad(8f)
        topBar.add(("Units".tr() + " · Units.json").toLabel(fontSize = 28))
            .padLeft(20f).expandX().left()
        val simButton = "Simulation world".toTextButton()
        simButton.isDisabled = true
        topBar.add(simButton).pad(8f)
        val saveButton = "Save".toTextButton()
        saveButton.onActivation { save() }
        topBar.add(saveButton).pad(8f)
        topBar.add(statusLabel).pad(8f)
        root.add(topBar).fillX().row()

        // 左侧：固定按钮行 + 分隔线 + 滚动单位列表
        val leftPanel = Table(BaseScreen.skin)
        val buttonRow = Table(BaseScreen.skin)
        val addButton = "+ New unit".toTextButton()
        addButton.onActivation { addUnit(emptyMap()) }
        buttonRow.add(addButton).left().pad(6f)
        val copyButton = "Copy unit from ruleset".toTextButton()
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
        if (units.isNotEmpty()) select(0)
        else formTable.add("No units. Click \"+ New unit\" in the top left.".toLabel()).pad(20f).row()
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
        "ModEditor/UnitRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        BaseScreen.skinStrings.skinConfig.baseColor)

    private fun selectedRowBackground() = BaseScreen.skinStrings.getUiBackground(
        "ModEditor/UnitRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        Color(0.2f, 0.5f, 0.9f, 1f))

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    private fun refreshList() {
        listTable.clear()
        for (group in DisplayGroup.entries) {
            val groupUnits = units.withIndex().filter { (_, u) -> getDisplayGroup(u) == group }
                .filter { (_, u) -> searchQuery.isEmpty() ||
                    u.name.lowercase().contains(searchQuery) ||
                    u.name.tr().lowercase().contains(searchQuery) }
            if (groupUnits.isEmpty() && searchQuery.isNotEmpty()) continue
            val isExpanded = group in expandedGroups

            val header = Table(BaseScreen.skin)
            header.background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/UnitGroup_${group.name}",
                BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
                Color(1f, 1f, 1f, 0.08f))
            val colorBar = Table(BaseScreen.skin).apply {
                background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/UnitGroupColor_${group.name}", null, group.color)
            }
            colorBar.setSize(4f, 1f)
            header.add(colorBar).width(4f).growY().pad(0f)
            header.add(((if (isExpanded) "▾ " else "▸ ") + group.label).toLabel(fontSize = 20, fontColor = group.color))
                .left().expandX().pad(10f, 10f, 10f, 8f)
            header.add(groupUnits.size.toString().toLabel(fontSize = 16, fontColor = Color(1f, 1f, 1f, 0.35f)))
                .right().pad(10f, 8f, 10f, 8f)
            header.touchable = Touchable.enabled
            header.onActivation {
                if (isExpanded) expandedGroups.remove(group) else expandedGroups.add(group)
                refreshList()
            }
            listTable.add(header).fillX().pad(3f, 4f, 0f, 4f).row()

            if (!isExpanded) { listTable.add().height(4f).fillX().row(); continue }

            for ((listIdx, indexedUnit) in groupUnits.withIndex()) {
                val index = indexedUnit.index; val unit = indexedUnit.value
                val isSelected = index == selectedIndex
                val row = Table(BaseScreen.skin)
                row.defaults().pad(12f)
                row.background = if (isSelected) selectedRowBackground()
                    else rowBackground()
                if (isSelected) {
                    val indicator = Table(BaseScreen.skin).apply {
                        background = BaseScreen.skinStrings.getUiBackground(
                            "ModEditor/SelIndicator", null, group.color)
                    }
                    indicator.setSize(3f, 1f)
                    row.add(indicator).width(3f).growY().pad(0f)
                }
                val nameLabel = listNameLabel(
                    unit.name.ifBlank { "(unnamed)".tr() },
                    maxWidth = stage.width * 0.25f - 100f,
                    fontSize = 20,
                    fontColor = if (isSelected) Color.WHITE else Color(1f, 1f, 1f, 0.85f))
                if (isSelected) selectedRowNameLabel = nameLabel
                row.add(nameLabel).left().expandX().maxWidth(stage.width * 0.25f - 100f).pad(11f, 12f, 11f, 12f)
                row.touchable = Touchable.enabled
                row.onActivation { select(index) }
                listTable.add(row).fillX().pad(2f, 6f, 2f, 6f).row()
            }
            listTable.add().height(6f).fillX().row()
        }
        if (units.isEmpty()) {
            listTable.add("No results".tr().toLabel(fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.4f))).pad(10f).row()
        }
    }

    private fun select(index: Int) {
        selectedIndex = index
        refreshList()
        rebuildForm()
    }

    private fun addUnit(template: Map<String, Any?>) {
        val unit = ModObjectData()
        unit.name = nextName()
        template.forEach { (k, v) -> unit.raw[k] = v }
        val templateUniques = unit.raw["uniques"]
        if (templateUniques is List<*>) unit.uniques.addAll(templateUniques.filterIsInstance<String>())
        units.add(unit)
        expandedGroups.add(getDisplayGroup(unit))
        select(units.lastIndex)
    }

    private fun nextName(): String {
        val existing = units.map { it.name }.toSet()
        var i = 1
        while (("New unit" + if (i == 1) "" else " $i") in existing) i++
        return "New unit" + if (i == 1) "" else " $i"
    }


    // ------------------------------------------------------------------
    // 表单
    // ------------------------------------------------------------------

    private fun optionalBox(values: List<String>, current: String?): ModEditorSelectBox {
        val items = mutableListOf("(None)")
        items.addAll(values)
        val cur = current ?: ""
        if (cur.isNotBlank() && cur !in items) items.add(cur)
        return ModEditorSelectBox(items, if (cur.isBlank()) "(None)" else cur, searchable = true)
    }

    /** 可搜索下拉：下拉列表带搜索输入框（全部下拉统一用） */
    private fun searchableBox(values: List<String>, current: String?): ModEditorSelectBox {
        val items = mutableListOf("(None)")
        items.addAll(values)
        val cur = current ?: ""
        if (cur.isNotBlank() && cur !in items) items.add(cur)
        return ModEditorSelectBox(items, if (cur.isBlank()) "(None)" else cur, searchable = true)
    }

    private fun rebuildForm() {
        formTable.clear()
        if (::uniqueToBox.isInitialized) uniqueToBox.disposeFloating()
        if (::requiredTechBox.isInitialized) requiredTechBox.disposeFloating()
        if (::obsoleteTechBox.isInitialized) obsoleteTechBox.disposeFloating()
        if (::requiredResourceBox.isInitialized) requiredResourceBox.disposeFloating()
        val unit = currentUnit()

        val header = Table(BaseScreen.skin)
        header.add("Edit unit".toLabel(fontSize = 24)).left().expandX()
        val copyButton = "Duplicate".toTextButton()
        copyButton.onActivation {
            val copy = ModObjectData()
            copy.name = nextName()
            copy.comment = unit.comment
            unit.raw.forEach { (k, v) -> copy.raw[k] = v }
            copy.uniques.addAll(unit.uniques)
            units.add(copy)
            select(units.lastIndex)
        }
        header.add(copyButton).pad(4f)
        val deleteButton = "Delete".toTextButton()
        deleteButton.onActivation { confirmDelete() }
        header.add(deleteButton).pad(4f)
        formTable.add(header).fillX().row()

        formTable.add(sectionHeader("Basic info".tr())).fillX().row()

        nameField = UncivTextField("Unit name (required)", unit.name)
        nameField.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                selectedRowNameLabel?.setText(nameField.text.ifBlank { "(unnamed)".tr() })
            }
        })

        val types = ModEditorData.getUnitTypes(modFolder).toMutableList()
        val currentType = unit.getString("unitType")
        if (currentType.isNotBlank() && currentType !in types) types.add(0, currentType)
        val typeItems = if (currentType.isBlank()) mutableListOf("(None)") + types else types
        unitTypeBox = ModEditorSelectBox(typeItems, if (currentType.isBlank()) "(None)" else currentType, searchable = true)
        unitTypeBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                val v = unitTypeBox.selected?.value?.takeUnless { it == "(None)" }
                unit.setString("unitType", v)
                expandedGroups.add(getDisplayGroup(unit))   // 先加入新组再刷新，否则新组不展开（2026-08-19）
                refreshList()
            }
        })

        uniqueToBox = searchableBox(ModEditorData.getNations(modFolder), unit.getString("uniqueTo"))
        requiredTechBox = searchableBox(ModEditorData.getTechs(modFolder), unit.getString("requiredTech"))
        obsoleteTechBox = searchableBox(ModEditorData.getTechs(modFolder), unit.getString("obsoleteTech"))
        requiredResourceBox = searchableBox(ModEditorData.getResources(modFolder), unit.getString("requiredResource"))

        // 基本信息：3 对 × 2 行，填满整栏（对称网格）
        val basicRow1 = Table(BaseScreen.skin)
        addBasicPair(basicRow1, "Name", nameField)
        addBasicPair(basicRow1, "Type", unitTypeBox)
        addBasicPair(basicRow1, "Unique to", uniqueToBox)
        formTable.add(basicRow1).growX().left().row()

        val basicRow2 = Table(BaseScreen.skin)
        addBasicPair(basicRow2, "Required tech", requiredTechBox)
        addBasicPair(basicRow2, "Obsolete tech", obsoleteTechBox)
        addBasicPair(basicRow2, "Required resource", requiredResourceBox)
        formTable.add(basicRow2).growX().left().row()

        formTable.add(sectionHeader("Stats".tr())).fillX().row()

        strengthField = numberField(unit.getIntText("strength"))
        strengthField.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                val v = strengthField.text.trim().toIntOrNull()
                unit.setInt("strength", v)
                expandedGroups.add(getDisplayGroup(unit))   // 先加入新组再刷新
                refreshList()
            }
        })
        rangedStrengthField = numberField(unit.getIntText("rangedStrength"))
        rangedStrengthField.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                val v = rangedStrengthField.text.trim().toIntOrNull()
                unit.setInt("rangedStrength", v)
                expandedGroups.add(getDisplayGroup(unit))   // 先加入新组再刷新
                refreshList()
            }
        })
        movementField = numberField(unit.getIntText("movement"))
        costField = numberField(unit.getIntText("cost"))
        val statsRow1 = Table(BaseScreen.skin)
        addStatPair(statsRow1, "Strength", strengthField)
        addStatPair(statsRow1, "Ranged strength", rangedStrengthField)
        addStatPair(statsRow1, "Movement", movementField)
        addStatPair(statsRow1, "Production cost", costField)
        formTable.add(statsRow1).growX().left().row()

        religiousStrengthField = numberField(unit.getIntText("religiousStrength"))
        rangeField = numberField(unit.getIntText("range"))
        interceptRangeField = numberField(unit.getIntText("interceptRange"))
        hurryCostModifierField = numberField(unit.getIntText("hurryCostModifier"))
        val statsRow2 = Table(BaseScreen.skin)
        addStatPair(statsRow2, "Religious strength", religiousStrengthField)
        addStatPair(statsRow2, "Range", rangeField)
        addStatPair(statsRow2, "Interception range", interceptRangeField)
        addStatPair(statsRow2, "Hurry cost %", hurryCostModifierField)
        formTable.add(statsRow2).growX().left().row()

        formTable.add(sectionHeader("Uniques".tr())).fillX().row()

        uniquesTable = Table(BaseScreen.skin)

        // 独特性：整体包在一个装饰方框里，填满整栏
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

        formTable.add(sectionHeader("Image (Icon + Unit art)".tr())).fillX().row()

        // 单位图集选择（持久化到 .editor-meta.json）
        val unitSets = ImageGetter.getAvailableUnitsets().toList()
        val currentUnitSet = ModEditorData.readUnitSetChoice(modFolder)
        unitSetBox = optionalBox(unitSets, currentUnitSet)
        unitSetBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                ModEditorData.writeUnitSetChoice(modFolder,
                    unitSetBox.selected?.value?.takeUnless { it == "(None)" } ?: "")
            }
        })
        val unitSetRow = Table(BaseScreen.skin)
        unitSetRow.add("Unit art set".toLabel()).left().pad(6f)
        unitSetRow.add(unitSetBox).growX().minWidth(180f).pad(6f)
        formTable.add(unitSetRow).growX().left().row()

        // 小图标：Images/UnitIcons/
        val iconRow = Table(BaseScreen.skin)
        val chooseIconButton = "Choose image…".toTextButton()
        chooseIconButton.onActivation { chooseImage(isTileArt = false) }
        iconRow.add(chooseIconButton).pad(6f)
        val removeIconButton = "Remove image".toTextButton()
        removeIconButton.onActivation { removeImage(isTileArt = false) }
        iconRow.add(removeIconButton).pad(6f)
        val iconHint = "The image is copied to Images/UnitIcons/, shown after the game packs it".toLabel(
            fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.5f))
        iconHint.wrap = true
        iconRow.add(iconHint).growX().minWidth(180f).left().pad(6f)
        formTable.add(iconRow).left().row()

        // 图集大图：Images/TileSets/<图集>/Units/
        val tileRow = Table(BaseScreen.skin)
        val chooseArtButton = "Choose unit art…".toTextButton()
        chooseArtButton.onActivation { chooseImage(isTileArt = true) }
        tileRow.add(chooseArtButton).pad(6f)
        val removeArtButton = "Remove image".toTextButton()
        removeArtButton.onActivation { removeImage(isTileArt = true) }
        tileRow.add(removeArtButton).pad(6f)
        val tileHint = "The art is copied to Images/TileSets/, shown in game with that unit set active".toLabel(
            fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.5f))
        tileHint.wrap = true
        tileRow.add(tileHint).growX().minWidth(180f).left().pad(6f)
        formTable.add(tileRow).left().row()

        // 图片操作反馈（显示在图片区，不用顶栏状态）
        imageStatusLabel = "".toLabel(fontSize = 13, fontColor = Color(1f, 1f, 1f, 0.55f))
        imageStatusLabel.wrap = true
        formTable.add(imageStatusLabel).growX().left().pad(2f, 8f, 6f, 8f).row()

        civilopediaEditor = CivilopediaTextEditor(
            screen = this,
            getRaw = { unit.raw["civilopediaText"] },
            setRaw = { unit.raw["civilopediaText"] = it }
        )
        civilopediaEditor.addTo(formTable, "Civilopedia text")

        formTable.add(sectionHeader("Comment".tr()))
            .fillX().row()

        commentArea = TextArea(unit.comment, BaseScreen.skin)
        formTable.add(commentArea).growX().height(100f).left().pad(6f).row()
    }

    private fun addBasicPair(row: Table, label: String, widget: Actor) {
        row.add(label.toLabel()).left().pad(4f).width(112f)
        row.add(widget).growX().minWidth(160f).pad(4f)
    }

    private fun addStatPair(row: Table, label: String, field: UncivTextField) {
        row.add(label.toLabel()).left().pad(4f).width(112f)
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

    private fun rebuildUniquesTable() {
        uniquesTable.clear()
        uniquesButtonRow.clear()
        if (currentUnit().uniques.isEmpty()) {
            uniquesTable.add("(no uniques)".toLabel(fontSize = 16, fontColor = Color(1f, 1f, 1f, 0.4f)))
                .left().pad(4f).row()
        }
        for ((index, rawString) in currentUnit().uniques.withIndex()) {
            val parsed = uniqueCatalog.parseRaw(rawString)
            if (parsed != null) {
                lateinit var editor: UniqueInlineEditor
                editor = UniqueInlineEditor(
                    screen = this, modFolder = modFolder, catalog = uniqueCatalog,
                    unique = parsed.unique, values = parsed.values, conditions = parsed.conditions,
                    onValueChange = { currentUnit().uniques[index] = editor.buildRaw() },
                    onStructureChange = {
                        currentUnit().uniques[index] = editor.buildRaw()
                        rebuildUniquesTable()
                    },
                    onDuplicate = {
                        val copyValues = parsed.values.toMutableMap()
                        val copyConditions = parsed.conditions
                            .map { (c, v) -> c to v.toMutableMap() }.toMutableList()
                        val newRaw = uniqueCatalog.buildRawString(parsed.unique, copyValues, copyConditions)
                        currentUnit().uniques.add(index + 1, newRaw)
                        rebuildUniquesTable()
                    },
                    onDelete = {
                        currentUnit().uniques.removeAt(index)
                        rebuildUniquesTable()
                    }
                )
                uniquesTable.add(editor).growX().left().pad(3f, 8f, 3f, 8f).row()
                uniquesTable.add(uniqueSeparatorLine()).growX().height(1f).pad(2f, 8f, 2f, 8f).row()
            } else {
                // 目录不认识的原始词条：只读展示 + 原文编辑 + 删除
                // 原文必须显示英文（不翻译）
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
                    currentUnit().uniques.removeAt(index)
                    rebuildUniquesTable()
                }
                row.add(removeButton).pad(4f)
                uniquesTable.add(row).growX().left().row()
            }
        }
        val addButton = "+ Add unique".toTextButton()
        addButton.onActivation {
            // 添加词条：分类 → 具体词条 选择器，选中后以默认参数加入行内编辑
            game.pushScreen(UniquePickerScreen(
                onPick = { unique ->
                    val values = unique.params
                        .filter { it.default.isNotBlank() }
                        .associate { it.id to it.default }.toMutableMap()
                    currentUnit().uniques.add(uniqueCatalog.buildRawString(unique, values, emptyList()))
                    rebuildUniquesTable()
                },
                onRawPicked = { text ->
                    currentUnit().uniques.add(text)
                    rebuildUniquesTable()
                }
            ))
        }
        uniquesButtonRow.add(addButton).left().pad(6f)
        addRawEditUniquesButton(this, uniquesButtonRow, getUniques = { currentUnit().uniques }) { rebuildUniquesTable() }
        uniquesButtonRow.row()
    }

    /** 编辑已有词条：原文模式弹层（旧词条可能是手写的，走原文）；搜索已移到选词条页顶栏 */
    private fun showUniqueEditor(index: Int?, existing: String?) {
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        popup.add("Unique (raw mode, picker in development)".toLabel(fontSize = 22)).pad(10f).row()
            popup.add("No quotes needed - they are added automatically when saving".tr().toLabel(
                fontSize = 12, fontColor = com.badlogic.gdx.graphics.Color(1f, 1f, 1f, 0.45f))).pad(0f, 10f, 4f, 10f).row()

        val textArea = TextArea(existing ?: "", BaseScreen.skin)
        popup.add(textArea).width(560f).height(160f).pad(6f).row()

        popup.addButton("Save") {
            // 换行只是显示，写入代码前必须清洗为单行
            val text = textArea.text.replace('\n', ' ').replace('\r', ' ')
                .replace(Regex("\\s{2,}"), " ").trim()
            if (text.isNotEmpty()) {
                if (index == null) currentUnit().uniques.add(text)
                else if (index < currentUnit().uniques.size) currentUnit().uniques[index] = text
            } else if (index != null && index < currentUnit().uniques.size) {
                currentUnit().uniques.removeAt(index)
            }
            popup.close()
            rebuildUniquesTable()
        }
        popup.addCloseButton()
        popup.open()
    }

    private fun confirmDelete() {
        val unit = currentUnit()
        val popup = Popup(this)
        // [name] 占位符翻译（与游戏 resign 弹窗同款用法）：
        // 先插值成 "... [New unit] ..." 再 tr()，翻译表按 "... [] ..." 归一化匹配
        val unitName = unit.name.ifBlank { "(unnamed)".tr() }
        popup.add("Are you sure you want to delete [$unitName]?".tr()
            .toLabel(fontSize = 20)).pad(12f).row()
        popup.add("Nothing is written to the file until you save.".tr()
            .toLabel(fontSize = 14)).pad(6f).row()
        popup.addButton("Delete") {
            units.removeAt(selectedIndex)
            popup.close()
            if (units.isEmpty()) {
                selectedIndex = -1
                refreshList()
                formTable.clear()
                formTable.add("No units. Click \"+ New unit\" in the top left.".toLabel()).pad(20f).row()
            } else {
                select(minOf(selectedIndex, units.lastIndex))
            }
        }
        popup.addCloseButton()
        popup.open()
    }

    /** 从规则集复制单位：把基础规则集的单位完整拷进模组（同名覆盖原版，扩展模组改原版的标准方式） */
    private fun showCopyFromRulesetPopup() {
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        popup.add("Copy unit from ruleset".tr().toLabel(fontSize = 20)).pad(8f).row()
        popup.add("A unit with the same name overrides the base one in-game".tr()
            .toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f))).pad(0f, 8f, 4f, 8f).row()

        // 来源规则集选择：内置规则集 + 已安装 base ruleset mod，默认 meta 或 G&K
        val sourceNames = ModEditorData.getBaseRulesetNames()
        val defaultSource = ModEditorData.readBaseRulesetChoice(modFolder)
            .ifBlank { BaseRuleset.Civ_V_GnK.fullName }
        val sourceBox = ModEditorSelectBox(sourceNames, defaultSource, searchable = true)
        val sourceRow = Table(BaseScreen.skin)
        sourceRow.add("Source ruleset".tr().toLabel(fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.7f)))
            .left().pad(4f)
        sourceRow.add(sourceBox).growX().width(360f).pad(4f)
        popup.add(sourceRow).growX().width(520f).pad(4f).row()

        val searchField = UncivTextField("Search")
        popup.add(searchField).growX().width(520f).pad(6f).row()
        val listTable = Table(BaseScreen.skin)
        val listScroll = AutoScrollPane(listTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        popup.add(listScroll).grow().width(520f).height(360f).pad(6f).row()

        // 当前来源的列表（切换来源时重新加载）
        var baseUnits = ModEditorData.loadBaseObjects(modFolder, "Units.json", sourceBox.selected.value)

        fun refresh(query: String) {
            listTable.clear()
            val q = query.trim().lowercase()
            var shown = 0
            for (base in baseUnits) {
                if (q.isNotEmpty() && !base.name.lowercase().contains(q) &&
                    !base.name.tr().lowercase().contains(q)) continue
                val row = Table(BaseScreen.skin)
                row.background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/SearchRow", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                    BaseScreen.skinStrings.skinConfig.baseColor)
                val nameLbl = bilingualUniqueLabel(base.name, base.name.tr(), 15f)
                nameLbl.wrap = true
                row.add(nameLbl).growX().left().maxWidth(stage.width * 0.25f - 60f).pad(6f, 8f, 6f, 8f)
                row.touchable = Touchable.enabled
                row.onActivation {
                    val copy = ModObjectData()
                    base.raw.forEach { (k, v) -> copy.raw[k] = v }
                    copy.name = base.name
                    val uniques = base.raw["uniques"]
                    if (uniques is List<*>) copy.uniques.addAll(uniques.filterIsInstance<String>())
                    units.add(copy)
                    popup.close()
                    select(units.lastIndex)
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

        sourceBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                baseUnits = ModEditorData.loadBaseObjects(modFolder, "Units.json", sourceBox.selected.value)
                refresh(searchField.text)
            }
        })
        searchField.setTextFieldListener { field, _ -> refresh(field.text) }
        refresh("")
        popup.addCloseButton()
        popup.open()
    }

    // ------------------------------------------------------------------
    // 图片
    // ------------------------------------------------------------------

    private fun iconFile(): FileHandle =
        modFolder.child("Images/UnitIcons/${currentUnit().name}.png")

    private fun tileArtFile(): FileHandle {
        val unitSet = unitSetBox.selected?.value?.takeUnless { it == "(None)" } ?: ""
        return modFolder.child("Images/TileSets/$unitSet/Units/${currentUnit().name}.png")
    }

    private fun chooseImage(isTileArt: Boolean) {
        val impl = ModEditorPlatformHolder.impl
        if (impl == null) return
        if (currentUnit().name.isBlank()) {
            showMessage("Enter a unit name first, then choose an image.")
            return
        }
        if (isTileArt && unitSetBox.selected?.value == "(None)") {
            showMessage("Please choose a unit art set first")
            return
        }
        val dest = if (isTileArt) tileArtFile() else iconFile()
        impl.chooseImageFileAsync { path ->
            if (path == null) return@chooseImageFileAsync
            try {
                dest.parent().mkdirs()
                Gdx.files.absolute(path).copyTo(dest)
                imageStatusLabel.setText("Image copied to".tr() + ": " + dest.path())
            } catch (e: Exception) {
                showMessage("Image copy failed:".tr() + " " + (e.message ?: ""))
            }
        }
    }

    private fun removeImage(isTileArt: Boolean) {
        val file = if (isTileArt) tileArtFile() else iconFile()
        if (file.exists()) file.delete()
        imageStatusLabel.setText("Image removed".tr())
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    private fun save() {
        val unit = currentUnit()
        val oldName = unit.name
        val newName = nameField.text.trim()

        // 先把表单写回 unit（数字非法时提示并中止，不写文件）
        if (!applyForm(unit, newName)) return

        // 校验（对照官方 schema 文档）
        val problems = ModEditorData.validateUnit(modFolder, unit, units)
        val errors = problems.filter { it.second }
        if (errors.isNotEmpty()) {
            showProblemsPopup(problems, onSaveAnyway = null)
            return
        }
        if (problems.isNotEmpty()) {
            showProblemsPopup(problems) { doSave(unit, oldName) }
            return
        }
        doSave(unit, oldName)
    }

    /** 把表单控件值写回 ModObjectData；非法输入返回 false（已提示） */
    private fun applyForm(unit: ModObjectData, newName: String): Boolean {
        if (newName.isBlank()) { showMessage("Unit name cannot be empty".tr()); return false }
        val selectedType = unitTypeBox.selected?.value
        if (selectedType.isNullOrBlank() || selectedType == "(None)") {
            showMessage("Please choose a unit type".tr()); return false
        }

        val numeric = mapOf(
            "strength" to strengthField, "rangedStrength" to rangedStrengthField,
            "religiousStrength" to religiousStrengthField, "range" to rangeField,
            "interceptRange" to interceptRangeField, "movement" to movementField,
            "cost" to costField, "hurryCostModifier" to hurryCostModifierField
        )
        for ((key, field) in numeric) {
            val text = field.text.trim()
            if (text.isBlank()) {
                unit.setInt(key, null)
            } else {
                val value = text.toIntOrNull()
                if (value == null) { showMessage("Invalid number:".tr() + " " + text); return false }
                unit.setInt(key, value)
            }
        }

        unit.name = newName
        unit.setString("name", newName)  // name 必须写进 raw，否则保存后文件里没有名字（游戏读不到）
        unit.setString("unitType", selectedType)
        unit.setString("uniqueTo", uniqueToBox.selected?.value?.takeUnless { it == "(None)" })
        unit.setString("requiredTech", requiredTechBox.selected?.value?.takeUnless { it == "(None)" })
        unit.setString("obsoleteTech", obsoleteTechBox.selected?.value?.takeUnless { it == "(None)" })
        unit.setString("requiredResource", requiredResourceBox.selected?.value?.takeUnless { it == "(None)" })
        unit.comment = commentArea.text
        unit.syncUniques()
        val cpEntries = civilopediaEditor.buildEntries()
        if (cpEntries == null) unit.raw.remove("civilopediaText") else unit.raw["civilopediaText"] = cpEntries
        return true
    }

    /** 写文件 + 改名迁移图片 + 状态提示 */
    private fun doSave(unit: ModObjectData, oldName: String) {
        // 改名时同步图片文件（小图标 + 图集大图）
        if (oldName != unit.name && oldName.isNotBlank()) {
            val oldIcon = modFolder.child("Images/UnitIcons/$oldName.png")
            if (oldIcon.exists()) {
                val newIcon = modFolder.child("Images/UnitIcons/${unit.name}.png")
                if (newIcon.exists()) newIcon.delete()
                oldIcon.moveTo(newIcon)
            }
            val unitSet = unitSetBox.selected?.value?.takeUnless { it == "(None)" } ?: ""
            if (unitSet.isNotBlank()) {
                val oldArt = modFolder.child("Images/TileSets/$unitSet/Units/$oldName.png")
                if (oldArt.exists()) {
                    val newArt = modFolder.child("Images/TileSets/$unitSet/Units/${unit.name}.png")
                    if (newArt.exists()) newArt.delete()
                    oldArt.moveTo(newArt)
                }
            }
        }

        ModEditorData.saveUnits(modFolder, units)
        val gameProblems = ModEditorData.filterGameProblems(
            ModEditorData.runGameValidation(modFolder), "Units.json")
        val errors = gameProblems.filter { it.second }
        if (errors.isNotEmpty()) {
            ModEditorData.rollbackFile(modFolder, "Units.json")
            statusLabel.setText("Save failed".tr())
            showGameProblemsPopup(gameProblems, saved = false)
            return
        }
        statusLabel.setText("Saved".tr())
        refreshList()
        if (gameProblems.isNotEmpty()) showGameProblemsPopup(gameProblems, saved = true)
    }

    /** 游戏级校验结果弹窗：错误=红（已回滚），警告=黄（已保存） */
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

    /** 保存前的问题提示：错误=红色（阻止），警告=黄色；无错误时可「仍然保存」 */
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

    private fun showMessage(message: String) {
        val popup = Popup(this)
        popup.add(message.toLabel()).pad(12f).row()
        popup.addCloseButton()
        popup.open()
    }

    override fun dispose() {
        super.dispose()
    }
}

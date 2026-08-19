package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextArea
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
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

/** S3 建筑编辑页：列表 + 表单（字段对照官方 Buildings.schema.json） */
class BuildingsEditorScreen(private val modFolder: FileHandle) : BaseScreen() {

    private val buildings = ModEditorData.loadBuildings(modFolder)
    private var selectedIndex = -1
    private val uniqueCatalog = UniqueCatalog.load()

    private val listTable = Table(BaseScreen.skin).apply {
        defaults().expandX().fillX()
        top()   // 列表内容不满时顶部对齐，否则垂直居中（libGDX Table 默认会居中）
    }
    private val formTable = FillWidthTable(BaseScreen.skin)
    private val statusLabel = "".toLabel(fontSize = 16)

    // 表单控件（每次选中建筑时重建）
    private lateinit var nameField: UncivTextField
    private lateinit var uniqueToBox: ModEditorSelectBox
    private lateinit var requiredTechBox: ModEditorSelectBox
    private lateinit var requiredBuildingBox: ModEditorSelectBox
    private lateinit var replacesBox: ModEditorSelectBox
    private lateinit var requiredResourceBox: ModEditorSelectBox
    private lateinit var costField: UncivTextField
    private lateinit var maintenanceField: UncivTextField
    private lateinit var productionField: UncivTextField
    private lateinit var foodField: UncivTextField
    private lateinit var goldField: UncivTextField
    private lateinit var scienceField: UncivTextField
    private lateinit var cultureField: UncivTextField
    private lateinit var happinessField: UncivTextField
    private lateinit var faithField: UncivTextField
    private lateinit var cityStrengthField: UncivTextField
    private lateinit var cityHealthField: UncivTextField
    private lateinit var hurryCostModifierField: UncivTextField
    private lateinit var isWonderCheck: CheckBox
    private lateinit var isNationalWonderCheck: CheckBox
    private lateinit var quoteField: UncivTextField
    private lateinit var replacementTextField: UncivTextField
    private lateinit var civilopediaEditor: CivilopediaTextEditor
    private lateinit var commentArea: TextArea
    private lateinit var uniquesTable: Table
    private lateinit var uniquesButtonRow: Table
    private lateinit var imageStatusLabel: Label
    private var selectedRowNameLabel: Label? = null
    private lateinit var searchField: UncivTextField
    private var searchQuery = ""

    // 分类：建筑 / 国家奇观 / 世界奇观
    private enum class DisplayGroup(val label: String, val color: Color) {
        Building("建筑", Color(0.55f, 0.55f, 0.55f, 1f)),
        NationalWonder("国家奇观", Color(0.75f, 0.55f, 0.25f, 1f)),
        WorldWonder("世界奇观", Color(0.85f, 0.75f, 0.2f, 1f));
    }
    private val expandedGroups = HashSet<DisplayGroup>()
    private fun getDisplayGroup(b: ModObjectData): DisplayGroup {
        val isWonder = b.raw["isWonder"] == true
        val isNationalWonder = b.raw["isNationalWonder"] == true
        return when {
            isWonder -> DisplayGroup.WorldWonder
            isNationalWonder -> DisplayGroup.NationalWonder
            else -> DisplayGroup.Building
        }
    }

    // 高级区块的编辑态
    private val mapFields = mutableMapOf<String, MutableMap<String, Any?>>()
    private val nearbyResources = mutableListOf<String>()

    private fun currentBuilding(): ModObjectData = buildings[selectedIndex]

    init {
        val root = Table(BaseScreen.skin)
        root.setFillParent(true)
        stage.addActor(root)

        // 顶栏
        val topBar = Table(BaseScreen.skin)
        val backButton = ("‹ " + "Back".tr()).toTextButton()
        backButton.onActivation { game.popScreen() }
        topBar.add(backButton).pad(8f)
        topBar.add(("Buildings".tr() + " · Buildings.json").toLabel(fontSize = 28))
            .padLeft(20f).expandX().left()
        val saveButton = "Save".toTextButton()
        saveButton.onActivation { save() }
        topBar.add(saveButton).pad(8f)
        topBar.add(statusLabel).pad(8f)
        root.add(topBar).fillX().row()

        // 左侧：固定按钮行 + 分隔线 + 滚动建筑列表
        val leftPanel = Table(BaseScreen.skin)
        val buttonRow = Table(BaseScreen.skin)
        val addButton = "+ New building".toTextButton()
        addButton.onActivation { addBuilding() }
        buttonRow.add(addButton).left().pad(6f)
        val copyButton = "Copy building from ruleset".toTextButton()
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
        if (buildings.isNotEmpty()) select(0)
        else formTable.add("No buildings. Click \"+ New building\" in the top left.".toLabel()).pad(20f).row()
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
        "ModEditor/BldRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        BaseScreen.skinStrings.skinConfig.baseColor)

    private fun selectedRowBackground() = BaseScreen.skinStrings.getUiBackground(
        "ModEditor/BldRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        Color(0.2f, 0.5f, 0.9f, 1f))

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    private fun refreshList() {
        listTable.clear()
        for (group in DisplayGroup.entries) {
            val groupItems = buildings.withIndex().filter { (_, b) -> getDisplayGroup(b) == group }
                .filter { (_, b) -> searchQuery.isEmpty() ||
                    b.name.lowercase().contains(searchQuery) ||
                    b.name.tr().lowercase().contains(searchQuery) }
            if (groupItems.isEmpty() && searchQuery.isNotEmpty()) continue
            val isExpanded = group in expandedGroups

            val header = Table(BaseScreen.skin)
            header.background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/BldGroup_${group.name}",
                BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
                Color(1f, 1f, 1f, 0.08f))
            val colorBar = Table(BaseScreen.skin).apply {
                background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/BldGroupColor_${group.name}", null, group.color)
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
                val index = indexedItem.index; val building = indexedItem.value
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
                    building.name.ifBlank { "(unnamed)".tr() },
                    maxWidth = stage.width * 0.25f - 100f,
                    fontSize = 20,
                    fontColor = if (isSelected) Color.WHITE else Color(1f, 1f, 1f, 0.85f))
                if (isSelected) selectedRowNameLabel = nameLabel
                row.add(nameLabel).left().expandX().maxWidth(stage.width * 0.25f - 60f).pad(11f, 12f, 11f, 12f)
                row.touchable = Touchable.enabled
                row.onActivation { select(index) }
                listTable.add(row).fillX().pad(2f, 6f, 2f, 6f).row()
            }
            listTable.add().height(6f).fillX().row()
        }
        if (buildings.isEmpty()) {
            listTable.add("No results".tr().toLabel(fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.4f))).pad(10f).row()
        }
    }

    private fun select(index: Int) {
        selectedIndex = index
        refreshList()
        rebuildForm()
    }

    private fun addBuilding() {
        val building = ModObjectData()
        building.name = nextName()
        buildings.add(building)
        expandedGroups.add(getDisplayGroup(building))
        select(buildings.lastIndex)
    }

    private fun nextName(): String {
        val existing = buildings.map { it.name }.toSet()
        var i = 1
        while (("New building" + if (i == 1) "" else " $i") in existing) i++
        return "New building" + if (i == 1) "" else " $i"
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
        if (::requiredBuildingBox.isInitialized) requiredBuildingBox.disposeFloating()
        if (::replacesBox.isInitialized) replacesBox.disposeFloating()
        if (::requiredResourceBox.isInitialized) requiredResourceBox.disposeFloating()
        val building = currentBuilding()

        val header = Table(BaseScreen.skin)
        header.add("Edit building".toLabel(fontSize = 24)).left().expandX()
        val copyButton = "Duplicate".toTextButton()
        copyButton.onActivation {
            val copy = ModObjectData()
            copy.name = nextName()
            copy.comment = building.comment
            building.raw.forEach { (k, v) -> copy.raw[k] = v }
            copy.uniques.addAll(building.uniques)
            buildings.add(copy)
            select(buildings.lastIndex)
        }
        header.add(copyButton).pad(4f)
        val deleteButton = "Delete".toTextButton()
        deleteButton.onActivation { confirmDelete() }
        header.add(deleteButton).pad(4f)
        formTable.add(header).fillX().row()

        formTable.add(sectionHeader("Basic info".tr())).fillX().row()

        nameField = UncivTextField("Building name (required)", building.name)
        nameField.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                selectedRowNameLabel?.setText(nameField.text.ifBlank { "(unnamed)".tr() })
            }
        })

        uniqueToBox = searchableBox(ModEditorData.getNations(modFolder), building.getString("uniqueTo"))
        requiredTechBox = searchableBox(ModEditorData.getTechs(modFolder), building.getString("requiredTech"))
        requiredBuildingBox = searchableBox(ModEditorData.getBuildings(modFolder), building.getString("requiredBuilding"))
        replacesBox = searchableBox(ModEditorData.getBuildings(modFolder), building.getString("replaces"))
        requiredResourceBox = searchableBox(ModEditorData.getResources(modFolder), building.getString("requiredResource"))

        val basicRow1 = Table(BaseScreen.skin)
        addBasicPair(basicRow1, "Name", nameField)
        addBasicPair(basicRow1, "Unique to", uniqueToBox)
        addBasicPair(basicRow1, "Required tech", requiredTechBox)
        formTable.add(basicRow1).growX().left().row()

        val basicRow2 = Table(BaseScreen.skin)
        addBasicPair(basicRow2, "Required building", requiredBuildingBox)
        addBasicPair(basicRow2, "Replaces", replacesBox)
        addBasicPair(basicRow2, "Required resource", requiredResourceBox)
        formTable.add(basicRow2).growX().left().row()

        formTable.add(sectionHeader("Stats".tr())).fillX().row()

        costField = numberField(building.getIntText("cost"))
        maintenanceField = numberField(building.getIntText("maintenance"))
        productionField = decimalField(building.getIntText("production"))
        foodField = decimalField(building.getIntText("food"))
        val statsRow1 = Table(BaseScreen.skin)
        addStatPair(statsRow1, "Production cost", costField)
        addStatPair(statsRow1, "Maintenance", maintenanceField)
        addStatPair(statsRow1, "Production", productionField)
        addStatPair(statsRow1, "Food", foodField)
        formTable.add(statsRow1).growX().left().row()

        goldField = decimalField(building.getIntText("gold"))
        scienceField = decimalField(building.getIntText("science"))
        cultureField = decimalField(building.getIntText("culture"))
        happinessField = decimalField(building.getIntText("happiness"))
        val statsRow2 = Table(BaseScreen.skin)
        addStatPair(statsRow2, "Gold", goldField)
        addStatPair(statsRow2, "Science", scienceField)
        addStatPair(statsRow2, "Culture", cultureField)
        addStatPair(statsRow2, "Happiness", happinessField)
        formTable.add(statsRow2).growX().left().row()

        faithField = decimalField(building.getIntText("faith"))
        cityStrengthField = decimalField(building.getIntText("cityStrength"))
        cityHealthField = numberField(building.getIntText("cityHealth"))
        hurryCostModifierField = numberField(building.getIntText("hurryCostModifier"))
        val statsRow3 = Table(BaseScreen.skin)
        addStatPair(statsRow3, "Faith", faithField)
        addStatPair(statsRow3, "City strength", cityStrengthField)
        addStatPair(statsRow3, "City health", cityHealthField)
        addStatPair(statsRow3, "Hurry cost %", hurryCostModifierField)
        formTable.add(statsRow3).growX().left().row()

        formTable.add(sectionHeader("Wonder".tr())).fillX().row()

        var suppressing = false
        isWonderCheck = CheckBox("isWonder".tr(), BaseScreen.skin).apply {
            isChecked = building.raw["isWonder"] == true
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    if (suppressing) return
                    suppressing = true
                    if (isChecked) {
                        isNationalWonderCheck.isChecked = false
                        building.raw.remove("isNationalWonder")
                        building.raw["isWonder"] = true
                    } else {
                        building.raw.remove("isWonder")
                    }
                    updateQuoteEnabled()
                    expandedGroups.add(if (isChecked) DisplayGroup.WorldWonder else DisplayGroup.Building)  // 先加入新组再刷新，否则新组不展开（2026-08-19）
                    refreshList()
                    suppressing = false
                }
            })
        }
        isNationalWonderCheck = CheckBox("isNationalWonder".tr(), BaseScreen.skin).apply {
            isChecked = building.raw["isNationalWonder"] == true
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    if (suppressing) return
                    suppressing = true
                    if (isChecked) {
                        isWonderCheck.isChecked = false
                        building.raw.remove("isWonder")
                        building.raw["isNationalWonder"] = true
                    } else {
                        building.raw.remove("isNationalWonder")
                    }
                    updateQuoteEnabled()
                    expandedGroups.add(if (isChecked) DisplayGroup.NationalWonder else DisplayGroup.Building)  // 先加入新组再刷新
                    refreshList()
                    suppressing = false
                }
            })
        }
        val flagsRow = Table(BaseScreen.skin)
        flagsRow.add(isWonderCheck).left().pad(6f)
        flagsRow.add(isNationalWonderCheck).left().pad(6f).padLeft(24f)  // 左间距，不是上间距
        formTable.add(flagsRow).growX().left().row()

        val quoteRow = Table(BaseScreen.skin)
        quoteRow.add("Quote".toLabel()).left().pad(4f).width(112f)
        quoteField = UncivTextField("", building.getString("quote"))
        quoteRow.add(quoteField).growX().minWidth(200f).pad(4f)
        formTable.add(quoteRow).growX().left().row()
        updateQuoteEnabled()

        formTable.add(sectionHeader("Advanced".tr())).fillX().row()

        mapFields.clear()
        addMapRow("percentStatBonus",
            (building.raw["percentStatBonus"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap(),
            listOf("Gold", "Science", "Production", "Food", "Happiness", "Culture", "Faith"))
        addMapRow("greatPersonPoints",
            (building.raw["greatPersonPoints"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap(),
            ModEditorData.getGreatPeople(modFolder))
        addMapRow("specialistSlots",
            (building.raw["specialistSlots"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap(),
            ModEditorData.getSpecialists(modFolder))

        nearbyResources.clear()
        nearbyResources.addAll(
            (building.raw["requiredNearbyImprovedResources"] as? List<*>)?.filterIsInstance<String>() ?: emptyList())
        addResourceChipsRow()

        val replacementRow = Table(BaseScreen.skin)
        replacementRow.add("replacementTextForUniques".toLabel()).left().pad(4f).width(220f)
        replacementTextField = UncivTextField("", building.getString("replacementTextForUniques"))
        replacementRow.add(replacementTextField).growX().minWidth(200f).pad(4f)
        formTable.add(replacementRow).growX().left().row()

        addCivilopediaRow()

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

        formTable.add(sectionHeader("Image (Images/BuildingIcons/)".tr())).fillX().row()

        val imageRow = Table(BaseScreen.skin)
        val chooseImageButton = (if (imageFile().exists()) "Replace image…" else "Choose image…").toTextButton()
        chooseImageButton.onActivation { chooseImage() }
        imageRow.add(chooseImageButton).pad(6f)
        val removeImageButton = "Remove image".toTextButton()
        removeImageButton.onActivation { removeImage() }
        imageRow.add(removeImageButton).pad(6f)
        val imageHint = "The image is copied to Images/BuildingIcons/, shown after the game packs it".toLabel(
            fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.5f))
        imageHint.wrap = true
        imageRow.add(imageHint).growX().minWidth(200f).left().pad(6f)
        formTable.add(imageRow).left().row()

        // 图片操作反馈（显示在图片区，不用顶栏状态）
        imageStatusLabel = "".toLabel(fontSize = 13, fontColor = Color(1f, 1f, 1f, 0.55f))
        imageStatusLabel.wrap = true
        formTable.add(imageStatusLabel).growX().left().pad(2f, 8f, 6f, 8f).row()

        formTable.add(sectionHeader("Comment".tr()))
            .fillX().row()

        commentArea = TextArea(building.comment, BaseScreen.skin)
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

    /** 整数输入框（cost/maintenance/cityHealth/hurryCostModifier） */
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

    /** 小数输入框（产量类字段，schema 为 number） */
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
    // 词条
    // ------------------------------------------------------------------

    private fun rebuildUniquesTable() {
        uniquesTable.clear()
        uniquesButtonRow.clear()
        if (currentBuilding().uniques.isEmpty()) {
            uniquesTable.add("(no uniques)".toLabel(fontSize = 16, fontColor = Color(1f, 1f, 1f, 0.4f)))
                .left().pad(4f).row()
        }
        for ((index, rawString) in currentBuilding().uniques.withIndex()) {
            val parsed = uniqueCatalog.parseRaw(rawString)
            if (parsed != null) {
                lateinit var editor: UniqueInlineEditor
                editor = UniqueInlineEditor(
                    screen = this, modFolder = modFolder, catalog = uniqueCatalog,
                    unique = parsed.unique, values = parsed.values, conditions = parsed.conditions,
                    onValueChange = { currentBuilding().uniques[index] = editor.buildRaw() },
                    onStructureChange = {
                        currentBuilding().uniques[index] = editor.buildRaw()
                        rebuildUniquesTable()
                    },
                    onDuplicate = {
                        val copyValues = parsed.values.toMutableMap()
                        val copyConditions = parsed.conditions
                            .map { (c, v) -> c to v.toMutableMap() }.toMutableList()
                        currentBuilding().uniques.add(index + 1,
                            uniqueCatalog.buildRawString(parsed.unique, copyValues, copyConditions))
                        rebuildUniquesTable()
                    },
                    onDelete = {
                        currentBuilding().uniques.removeAt(index)
                        rebuildUniquesTable()
                    }
                )
                uniquesTable.add(editor).growX().left().pad(3f, 8f, 3f, 8f).row()
                uniquesTable.add(uniqueSeparatorLine()).growX().height(1f).pad(2f, 8f, 2f, 8f).row()
            } else {
                // 目录不认识的原始词条：只读展示 + 原文编辑 + 删除
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
                    currentBuilding().uniques.removeAt(index)
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
                    currentBuilding().uniques.add(uniqueCatalog.buildRawString(unique, values, emptyList()))
                    rebuildUniquesTable()
                },
                onRawPicked = { text ->
                    currentBuilding().uniques.add(text)
                    rebuildUniquesTable()
                }
            ))
        }
        uniquesButtonRow.add(addButton).left().pad(6f)
        addRawEditUniquesButton(this, uniquesButtonRow, getUniques = { currentBuilding().uniques }) { rebuildUniquesTable() }
        uniquesTable.row()
    }

    /** 编辑已有词条：原文模式弹层（旧词条可能是手写的，走原文） */
    private fun showUniqueEditor(index: Int?, existing: String?) {
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        popup.add("Unique (raw mode)".toLabel(fontSize = 22)).pad(10f).row()
            popup.add("No quotes needed - they are added automatically when saving".tr().toLabel(
                fontSize = 12, fontColor = com.badlogic.gdx.graphics.Color(1f, 1f, 1f, 0.45f))).pad(0f, 10f, 4f, 10f).row()
        val textArea = TextArea(existing ?: "", BaseScreen.skin)
        popup.add(textArea).width(560f).height(160f).pad(6f).row()
        popup.addButton("Save") {
            val text = textArea.text.replace('\n', ' ').replace('\r', ' ')
                .replace(Regex("\\s{2,}"), " ").trim()
            if (text.isNotEmpty()) {
                if (index == null) currentBuilding().uniques.add(text)
                else if (index < currentBuilding().uniques.size) currentBuilding().uniques[index] = text
            } else if (index != null && index < currentBuilding().uniques.size) {
                currentBuilding().uniques.removeAt(index)
            }
            popup.close()
            rebuildUniquesTable()
        }
        popup.addCloseButton()
        popup.open()
    }

    /** 只有奇观（世界或国家）才能输入引文；置灰需要 disabledFontColor（皮肤默认没设，看不出禁用） */
    private fun updateQuoteEnabled() {
        if (quoteField.style.disabledFontColor == null) {
            quoteField.style.disabledFontColor = Color(1f, 1f, 1f, 0.35f)
        }
        quoteField.isDisabled = !(isWonderCheck.isChecked || isNationalWonderCheck.isChecked)
    }

    // ------------------------------------------------------------------
    // 高级区块：映射编辑器 / 附近改良资源芯片 / 百科文本
    // ------------------------------------------------------------------

    /** 映射型字段（专家点数/专家栏位/百分比加成）：按钮显示当前内容，点开逐行编辑 */
    private fun addMapRow(labelKey: String, current: Map<String, Any?>, options: List<String>) {
        val row = Table(BaseScreen.skin)
        row.add(labelKey.toLabel()).left().pad(4f).width(220f)
        val map = current.toMutableMap()
        mapFields[labelKey] = map
        val button = (map.entries.joinToString(", ") { (k, v) -> "$k: $v" }.ifBlank { "(None)" })
            .toTextButton()
        button.onActivation { showMapEditor(button, labelKey, map, options) }
        row.add(button).growX().minWidth(200f).pad(4f)
        formTable.add(row).growX().left().row()
    }

    private fun showMapEditor(
        button: TextButton, labelKey: String,
        map: MutableMap<String, Any?>, options: List<String>
    ) {
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        popup.add(labelKey.tr().toLabel(fontSize = 20)).pad(8f).row()
        val listTable = Table(BaseScreen.skin)
        val scroll = AutoScrollPane(listTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        popup.add(scroll).grow().width(480f).height(300f).pad(6f).row()

        val rows = mutableListOf<Triple<UncivTextField, ModEditorSelectBox, Table>>()

        fun addRow(amount: String, option: String) {
            val amountField = UncivTextField("", amount)
            amountField.textFieldFilter = object : TextField.TextFieldFilter {
                override fun acceptChar(textField: TextField, c: Char): Boolean = c in '0'..'9'
            }
            val items = if (option.isNotBlank() && option !in options)
                mutableListOf(option) + options else options
            val box = ModEditorSelectBox(items, option, searchable = true)
            val row = Table(BaseScreen.skin)
            row.add(amountField).width(80f).pad(4f)
            row.add(box).growX().minWidth(260f).pad(4f)
            val removeButton = "×".toTextButton()
            removeButton.onActivation {
                rows.removeIf { it.first === amountField }
                row.remove()
            }
            row.add(removeButton).pad(4f)
            listTable.add(row).growX().left().row()
            rows.add(Triple(amountField, box, row))
        }

        if (map.isEmpty()) addRow("1", options.firstOrNull() ?: "")
        else for ((option, amount) in map) addRow(amount.toString(), option)

        popup.addButton("+ Add") { addRow("1", options.firstOrNull() ?: "") }
        popup.addButton("Save") {
            map.clear()
            for ((amountField, box, _) in rows) {
                val amount = amountField.text.trim().toIntOrNull() ?: continue
                val name = box.selected?.value ?: continue
                if (amount != 0) map[name] = amount
            }
            button.setText(map.entries.joinToString(", ") { (k, v) -> "$k: $v" }.ifBlank { "(None)" })
            popup.close()
        }
        popup.addCloseButton()
        popup.open()
    }

    /** requiredNearbyImprovedResources：资源芯片（可换行）+ 搜索添加 */
    private fun addResourceChipsRow() {
        val row = Table(BaseScreen.skin)
        row.add("requiredNearbyImprovedResources".toLabel())
            .left().pad(4f).width(220f).top()
        val chipsTable = Table(BaseScreen.skin)

        fun refreshChips() {
            chipsTable.clear()
            val maxWidth = formAvailableWidth(stage.width, extraDeduction = 220f)
            var currentRow = Table(BaseScreen.skin)
            var rowWidth = 0f
            for (value in nearbyResources) {
                val chip = Table(BaseScreen.skin)
                chip.background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/ConditionChip", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                    Color(0.15f, 0.4f, 0.7f, 0.8f))
                val chipLabel = value.toLabel(fontSize = 14)
                val chipLabelWidth = minOf(chipLabel.prefWidth, 280f)
                chipLabel.wrap = true
                chip.add(chipLabel).width(chipLabelWidth).left().pad(4f, 8f, 4f, 2f)
                val removeButton = "×".toTextButton()
                removeButton.onActivation {
                    nearbyResources.remove(value)
                    refreshChips()
                }
                chip.add(removeButton).pad(2f)
                val chipWidth = chip.prefWidth + 6f
                if (currentRow.children.size > 0 && rowWidth + chipWidth > maxWidth) {
                    chipsTable.add(currentRow).growX().left().row()
                    currentRow = Table(BaseScreen.skin)
                    rowWidth = 0f
                }
                currentRow.add(chip).left().pad(2f)
                rowWidth += chipWidth
            }
            if (currentRow.children.size > 0) chipsTable.add(currentRow).growX().left().row()
        }
        refreshChips()

        val addButton = "+ Add".toTextButton()
        addButton.onActivation { showAddResourcePopup { refreshChips() } }
        val right = Table(BaseScreen.skin)
        right.add(chipsTable).growX().left().row()
        right.add(addButton).left().padTop(2f)
        row.add(right).growX().left().pad(4f)
        formTable.add(row).growX().left().row()
    }

    private fun showAddResourcePopup(onChanged: () -> Unit) {
        val options = ModEditorData.getResources(modFolder)
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        popup.add(("requiredNearbyImprovedResources".tr() + " · " + "Add".tr()).toLabel(fontSize = 20)).pad(8f).row()
        val searchField = UncivTextField("Search")
        popup.add(searchField).growX().width(520f).pad(6f).row()
        val listTable = Table(BaseScreen.skin)
        val listScroll = AutoScrollPane(listTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        popup.add(listScroll).grow().width(520f).height(360f).pad(6f).row()

        fun refresh(query: String) {
            listTable.clear()
            var shown = 0
            for (item in options) {
                if (item in nearbyResources) continue
                if (query.isNotBlank() && !item.lowercase().contains(query.trim().lowercase())) continue
                val row = Table(BaseScreen.skin)
                row.background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/SearchRow", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                    BaseScreen.skinStrings.skinConfig.baseColor)
                val itemLbl = bilingualUniqueLabel(item, item.tr(), 15f)
                itemLbl.wrap = true
                row.add(itemLbl).growX().left().pad(6f, 8f, 6f, 8f)
                row.touchable = Touchable.enabled
                row.onActivation {
                    nearbyResources.add(item)
                    popup.close()
                    onChanged()
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

    /** civilopediaText：富文本逐条编辑（2026-08-19 用户要求） */
    private fun addCivilopediaRow() {
        val building = currentBuilding()
        civilopediaEditor = CivilopediaTextEditor(
            screen = this,
            getRaw = { building.raw["civilopediaText"] },
            setRaw = { building.raw["civilopediaText"] = it }
        )
        civilopediaEditor.addTo(formTable, "Civilopedia text")
    }

    /** 从规则集复制建筑：完整拷进模组（同名覆盖原版） */
    private fun showCopyFromRulesetPopup(initialSource: String? = null) {
        val sourceRuleset = initialSource?.takeIf { it.isNotBlank() }
            ?: ModEditorData.readBaseRulesetChoice(modFolder).ifBlank { com.unciv.models.metadata.BaseRuleset.Civ_V_GnK.fullName }
        val baseBuildings = ModEditorData.loadBaseObjects(modFolder, "Buildings.json", sourceRuleset)
        if (baseBuildings.isEmpty()) { showMessage("No buildings found in the base ruleset"); return }
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
        popup.add("Copy building from ruleset".tr().toLabel(fontSize = 20)).pad(8f).row()
        popup.add("A building with the same name overrides the base one in-game".tr()
            .toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f))).pad(0f, 8f, 4f, 8f).row()
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
            for (base in baseBuildings) {
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
                    buildings.add(copy)
                    popup.close()
                    select(buildings.lastIndex)
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

    // ------------------------------------------------------------------
    // 图片
    // ------------------------------------------------------------------

    private fun imageFile(): FileHandle =
        modFolder.child("Images/BuildingIcons/${currentBuilding().name}.png")

    private fun chooseImage() {
        val impl = ModEditorPlatformHolder.impl ?: return
        if (currentBuilding().name.isBlank()) {
            showMessage("Enter a building name first, then choose an image.")
            return
        }
        val dest = imageFile()
        impl.chooseImageFileAsync { path ->
            if (path == null) return@chooseImageFileAsync
            try {
                dest.parent().mkdirs()
                Gdx.files.absolute(path).copyTo(dest)
                imageStatusLabel.setText("Image copied to".tr() + ": " + dest.path())
                rebuildForm()
            } catch (e: Exception) {
                showMessage("Image copy failed:".tr() + " " + (e.message ?: ""))
            }
        }
    }

    private fun removeImage() {
        val file = imageFile()
        if (file.exists()) file.delete()
        imageStatusLabel.setText("Image removed".tr())
        rebuildForm()
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    private fun save() {
        val building = currentBuilding()
        val oldName = building.name
        val newName = nameField.text.trim()

        if (!applyForm(building, newName)) return

        val problems = ModEditorData.validateBuilding(modFolder, building, buildings)
        val errors = problems.filter { it.second }
        if (errors.isNotEmpty()) {
            showProblemsPopup(problems, onSaveAnyway = null)
            return
        }
        if (problems.isNotEmpty()) {
            showProblemsPopup(problems) { doSave(building, oldName) }
            return
        }
        doSave(building, oldName)
    }

    /** 把表单控件值写回 ModObjectData；非法输入返回 false（已提示） */
    private fun applyForm(building: ModObjectData, newName: String): Boolean {
        if (newName.isBlank()) { showMessage("Building name cannot be empty".tr()); return false }

        val intFields = mapOf(
            "cost" to costField, "maintenance" to maintenanceField,
            "cityHealth" to cityHealthField, "hurryCostModifier" to hurryCostModifierField
        )
        for ((key, field) in intFields) {
            val text = field.text.trim()
            if (text.isBlank()) {
                building.setInt(key, null)
            } else {
                val value = text.toIntOrNull()
                if (value == null) { showMessage("Invalid number:".tr() + " " + text); return false }
                building.setInt(key, value)
            }
        }
        val decimalFields = mapOf(
            "production" to productionField, "food" to foodField, "gold" to goldField,
            "science" to scienceField, "culture" to cultureField, "happiness" to happinessField,
            "faith" to faithField, "cityStrength" to cityStrengthField
        )
        for ((key, field) in decimalFields) {
            val text = field.text.trim()
            if (text.isBlank()) {
                building.setNumber(key, null)
            } else {
                val value = text.toDoubleOrNull()
                if (value == null) { showMessage("Invalid number:".tr() + " " + text); return false }
                building.setNumber(key, value)
            }
        }

        building.name = newName
        building.setString("name", newName)
        building.setString("uniqueTo", uniqueToBox.selected?.value?.takeUnless { it == "(None)" })
        building.setString("requiredTech", requiredTechBox.selected?.value?.takeUnless { it == "(None)" })
        building.setString("requiredBuilding", requiredBuildingBox.selected?.value?.takeUnless { it == "(None)" })
        building.setString("replaces", replacesBox.selected?.value?.takeUnless { it == "(None)" })
        building.setString("requiredResource", requiredResourceBox.selected?.value?.takeUnless { it == "(None)" })
        if (isWonderCheck.isChecked) building.raw["isWonder"] = true else building.raw.remove("isWonder")
        if (isNationalWonderCheck.isChecked) building.raw["isNationalWonder"] = true else building.raw.remove("isNationalWonder")
        building.setString("quote", quoteField.text)
        building.setString("replacementTextForUniques", replacementTextField.text)
        for ((key, map) in mapFields) {
            if (map.isEmpty()) building.raw.remove(key) else building.raw[key] = LinkedHashMap(map)
        }
        if (nearbyResources.isEmpty()) building.raw.remove("requiredNearbyImprovedResources")
        else building.raw["requiredNearbyImprovedResources"] = nearbyResources.toList()
        val cpEntries = civilopediaEditor.buildEntries()
        if (cpEntries == null) building.raw.remove("civilopediaText")
        else building.raw["civilopediaText"] = cpEntries
        building.comment = commentArea.text
        building.syncUniques()
        return true
    }

    /** 写文件 + 改名迁移图片 + 状态提示 */
    private fun doSave(building: ModObjectData, oldName: String) {
        if (oldName != building.name && oldName.isNotBlank()) {
            val oldImage = modFolder.child("Images/BuildingIcons/$oldName.png")
            if (oldImage.exists()) {
                val newImage = modFolder.child("Images/BuildingIcons/${building.name}.png")
                if (newImage.exists()) newImage.delete()
                oldImage.moveTo(newImage)
            }
        }

        ModEditorData.saveBuildings(modFolder, buildings)
        val gameProblems = ModEditorData.filterGameProblems(
            ModEditorData.runGameValidation(modFolder), "Buildings.json")
        val errors = gameProblems.filter { it.second }
        if (errors.isNotEmpty()) {
            ModEditorData.rollbackFile(modFolder, "Buildings.json")
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

    private fun confirmDelete() {
        val building = currentBuilding()
        val popup = Popup(this)
        val buildingName = building.name.ifBlank { "(unnamed)".tr() }
        popup.add("Are you sure you want to delete [$buildingName]?".tr()
            .toLabel(fontSize = 20)).pad(12f).row()
        popup.add("Nothing is written to the file until you save.".tr()
            .toLabel(fontSize = 14)).pad(6f).row()
        popup.addButton("Delete") {
            buildings.removeAt(selectedIndex)
            popup.close()
            if (buildings.isEmpty()) {
                selectedIndex = -1
                refreshList()
                formTable.clear()
                formTable.add("No buildings. Click \"+ New building\" in the top left.".toLabel()).pad(20f).row()
            } else {
                select(minOf(selectedIndex, buildings.lastIndex))
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

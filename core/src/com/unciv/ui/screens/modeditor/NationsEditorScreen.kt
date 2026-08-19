package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextArea
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

/** 文明编辑器：列表 + 表单（字段对照官方 Nations.json 文档） */
class NationsEditorScreen(private val modFolder: FileHandle) : BaseScreen() {

    private val nations = ModEditorData.loadObjects(modFolder, "Nations.json")
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

    // 分类：文明 / 城邦
    private enum class DisplayGroup(val label: String, val color: Color) {
        Nation("文明", Color(0.75f, 0.55f, 0.25f, 1f)),
        CityState("城邦", Color(0.27f, 0.78f, 0.8f, 1f));
    }
    private val expandedGroups = HashSet<DisplayGroup>()
    private fun getDisplayGroup(nation: ModObjectData): DisplayGroup {
        return if (nation.getString("cityStateType").isNotBlank()) DisplayGroup.CityState
        else DisplayGroup.Nation
    }

    private lateinit var uniquesTable: Table
    private lateinit var uniquesButtonRow: Table
    private lateinit var imageStatusLabel: Label
    private var selectedRowNameLabel: Label? = null

    // 表单控件
    private lateinit var nameField: UncivTextField
    private lateinit var leaderNameField: UncivTextField
    private lateinit var personalityBox: ModEditorSelectBox
    private lateinit var cityStateBox: ModEditorSelectBox
    private lateinit var victoryBox: ModEditorSelectBox
    private lateinit var religionBox: ModEditorSelectBox
    private lateinit var styleField: UncivTextField
    private lateinit var innerR: UncivTextField
    private lateinit var innerG: UncivTextField
    private lateinit var innerB: UncivTextField
    private lateinit var outerR: UncivTextField
    private lateinit var outerG: UncivTextField
    private lateinit var outerB: UncivTextField
    private lateinit var uniqueNameField: UncivTextField
    private lateinit var uniqueTextField: UncivTextField
    private lateinit var civilopediaEditor: CivilopediaTextEditor
    private lateinit var commentArea: TextArea
    private val diplomacyAreas = HashMap<String, TextArea>()  // 长文本
    private val diplomacyFields = HashMap<String, UncivTextField>()  // 短文本

    private fun currentNation(): ModObjectData = nations[selectedIndex]

    init {
        val root = Table(BaseScreen.skin)
        root.setFillParent(true)
        stage.addActor(root)

        val topBar = Table(BaseScreen.skin)
        val backButton = ("‹ " + "Back".tr()).toTextButton()
        backButton.onActivation { game.popScreen() }
        topBar.add(backButton).pad(8f)
        topBar.add(("Nations".tr() + " · Nations.json").toLabel(fontSize = 28))
            .padLeft(20f).expandX().left()
        val saveButton = "Save".toTextButton()
        saveButton.onActivation { save() }
        topBar.add(saveButton).pad(8f)
        topBar.add(statusLabel).pad(8f)
        root.add(topBar).fillX().row()

        // 左侧：按钮行 + 搜索 + 列表
        val leftPanel = Table(BaseScreen.skin)
        val buttons = Table(BaseScreen.skin)
        val row1 = Table(BaseScreen.skin)
        val newNationButton = "+ New nation".toTextButton()
        newNationButton.onActivation { addNation(isCityState = false) }
        row1.add(newNationButton).left().pad(4f)
        val copyNationButton = "Copy nation from ruleset".toTextButton()
        copyNationButton.onActivation { showCopyFromRulesetPopup(isCityState = false) }
        row1.add(copyNationButton).left().pad(4f)
        val row2 = Table(BaseScreen.skin)
        val newCsButton = "+ New city-state".toTextButton()
        newCsButton.onActivation { addNation(isCityState = true) }
        row2.add(newCsButton).left().pad(4f)
        val copyCsButton = "Copy city-state from ruleset".toTextButton()
        copyCsButton.onActivation { showCopyFromRulesetPopup(isCityState = true) }
        row2.add(copyCsButton).left().pad(4f)
        buttons.add(row1).fillX().row()
        buttons.add(row2).fillX().row()
        leftPanel.add(buttons).fillX().row()
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
        if (nations.isNotEmpty()) select(0)
        else formTable.add("No nations. Click \"+ New nation\" in the top left.".toLabel()).pad(20f).row()
    }

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    private fun refreshList() {
        listTable.clear()
        selectedRowNameLabel = null
        for (group in DisplayGroup.entries) {
            val groupItems = nations.withIndex().filter { (_, n) -> getDisplayGroup(n) == group }
                .filter { (_, n) -> searchQuery.isEmpty() ||
                    n.name.lowercase().contains(searchQuery) ||
                    n.name.tr().lowercase().contains(searchQuery) }
            if (groupItems.isEmpty() && searchQuery.isNotEmpty()) continue
            val isExpanded = group in expandedGroups

            val header = Table(BaseScreen.skin)
            header.background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/NatGroup_${group.name}",
                BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
                Color(1f, 1f, 1f, 0.08f))
            val colorBar = Table(BaseScreen.skin).apply {
                background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/NatGroupColor_${group.name}", null, group.color)
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
                val index = indexedItem.index; val nation = indexedItem.value
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
                    nation.name.ifBlank { "(unnamed)".tr() },
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
        if (nations.isEmpty()) {
            listTable.add("No nations. Click \"+ New nation\" in the top left.".toLabel()).pad(20f).row()
        }
    }

    private fun select(index: Int) {
        selectedIndex = index
        refreshList()
        rebuildForm()
    }

    private fun addNation(isCityState: Boolean) {
        val nation = ModObjectData()
        nation.name = nextName(isCityState)
        if (isCityState) {
            val defaultType = ModEditorData.getCityStateTypes(modFolder).firstOrNull()
            if (defaultType != null) nation.setString("cityStateType", defaultType)
        }
        nations.add(nation)
        expandedGroups.add(getDisplayGroup(nation))
        select(nations.lastIndex)
    }

    private fun nextName(isCityState: Boolean): String {
        val base = if (isCityState) "New city-state" else "New nation"
        val existing = nations.map { it.name }.toSet()
        var i = 1
        while (("$base" + if (i == 1) "" else " $i") in existing) i++
        return "$base" + if (i == 1) "" else " $i"
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
        if (::cityStateBox.isInitialized) cityStateBox.disposeFloating()
        if (::personalityBox.isInitialized) personalityBox.disposeFloating()
        if (::victoryBox.isInitialized) victoryBox.disposeFloating()
        if (::religionBox.isInitialized) religionBox.disposeFloating()
        diplomacyAreas.clear()
        diplomacyFields.clear()
        val nation = currentNation()

        val header = Table(BaseScreen.skin)
        header.add("Edit nation".toLabel(fontSize = 24)).left().expandX()
        val copyButton = "Duplicate".toTextButton()
        copyButton.onActivation {
            val copy = ModObjectData()
            copy.name = nextName(nation.getString("cityStateType").isNotBlank())
            copy.comment = nation.comment
            nation.raw.forEach { (k, v) -> copy.raw[k] = v }
            copy.uniques.addAll(nation.uniques)
            nations.add(copy)
            select(nations.lastIndex)
        }
        header.add(copyButton).pad(4f)
        val deleteButton = "Delete".toTextButton()
        deleteButton.onActivation { confirmDelete() }
        header.add(deleteButton).pad(4f)
        formTable.add(header).fillX().row()

        formTable.add(sectionHeader("Basic info")).fillX().row()

        nameField = UncivTextField("Nation name (required)", nation.name)
        nameField.setTextFieldListener { field, _ ->
            nation.name = field.text
            nation.setString("name", field.text)
            selectedRowNameLabel?.setText(field.text.ifBlank { "(unnamed)".tr() })
        }
        addFieldRow("Name", nameField)

        // 类型切换：主要文明 / 城邦（= cityStateType 是否为空）
        val isCityState = nation.getString("cityStateType").isNotBlank()
        val typeRow = Table(BaseScreen.skin)
        typeRow.add("Type".toLabel(fontSize = 14)).left().pad(3f).width(180f)
        val majorCheck = CheckBox("Major civilization".tr(), BaseScreen.skin)
        val cityStateCheck = CheckBox("City-state".tr(), BaseScreen.skin)
        majorCheck.isChecked = !isCityState
        cityStateCheck.isChecked = isCityState
        majorCheck.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                if (!majorCheck.isChecked) return
                cityStateCheck.isChecked = false
                if (nation.getString("cityStateType").isNotBlank()) {
                    nation.raw.remove("cityStateType")
                    expandedGroups.add(getDisplayGroup(nation))   // 先展开新组再刷新列表（2026-08-19）
                    refreshList()
                    rebuildForm()
                }
            }
        })
        cityStateCheck.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                if (!cityStateCheck.isChecked) return
                majorCheck.isChecked = false
                if (nation.getString("cityStateType").isBlank()) {
                    val defaultType = ModEditorData.getCityStateTypes(modFolder).firstOrNull()
                    if (defaultType != null) nation.setString("cityStateType", defaultType)
                    expandedGroups.add(getDisplayGroup(nation))   // 先展开新组再刷新列表
                    refreshList()
                    rebuildForm()
                }
            }
        })
        typeRow.add(majorCheck).left().pad(3f)
        typeRow.add(cityStateCheck).left().pad(3f)
        formTable.add(typeRow).growX().left().row()

        if (isCityState) {
            cityStateBox = searchableBox(ModEditorData.getCityStateTypes(modFolder), nation.getString("cityStateType"))
            cityStateBox.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    val v = cityStateBox.selected?.value?.takeUnless { it == "(None)" }
                    if (v == null) nation.raw.remove("cityStateType") else nation.setString("cityStateType", v)
                    expandedGroups.add(getDisplayGroup(nation))   // 先加入新组再刷新，否则新组不展开（2026-08-19）
                    refreshList()
                }
            })
            addFieldRow("City state type", cityStateBox)
        }

        // 形容词（主要文明和城邦都有，官方是数组）
        addChipRow("Adjective", nation, "adjective", firstMarked = false,
            onChipTap = { value, refresh ->
                showTextInputPopup("Edit", value) { newValue ->
                    val list = (nation.raw["adjective"] as? List<*>)
                        ?.filterIsInstance<String>()?.toMutableList() ?: mutableListOf()
                    val idx = list.indexOf(value)
                    if (idx >= 0) list[idx] = newValue
                    nation.raw["adjective"] = list
                    refresh()
                }
            },
            onAdd = { refresh ->
                showTextInputPopup("Add", null) { newValue ->
                    val list = (nation.raw["adjective"] as? List<*>)
                        ?.filterIsInstance<String>()?.toMutableList() ?: mutableListOf()
                    list.add(newValue)
                    nation.raw["adjective"] = list
                    refresh()
                }
            })

        // 仅主要文明：领袖/性格/胜利类型/宗教/风格
        if (!isCityState) {
            leaderNameField = UncivTextField("", nation.getString("leaderName"))
            leaderNameField.setTextFieldListener { field, _ -> nation.setString("leaderName", field.text) }
            addFieldRow("Leader name", leaderNameField)

            personalityBox = searchableBox(ModEditorData.getPersonalities(modFolder), nation.getString("personality"))
            personalityBox.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    nation.setString("personality", personalityBox.selected?.value?.takeUnless { it == "(None)" })
                }
            })
            addFieldRow("Personality", personalityBox)

            victoryBox = searchableBox(ModEditorData.getVictoryTypes(modFolder), nation.getString("preferredVictoryType"))
            victoryBox.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    nation.setString("preferredVictoryType", victoryBox.selected?.value?.takeUnless { it == "(None)" })
                }
            })
            addFieldRow("Preferred victory type", victoryBox)

            religionBox = searchableBox(ModEditorData.getReligions(modFolder), nation.getString("favoredReligion"))
            religionBox.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    nation.setString("favoredReligion", religionBox.selected?.value?.takeUnless { it == "(None)" })
                }
            })
            addFieldRow("Favored religion", religionBox)

            styleField = UncivTextField("", nation.getString("style"))
            styleField.setTextFieldListener { field, _ -> nation.setString("style", field.text) }
            addFieldRow("Style", styleField)
            val styleHint = "Style example: e.g. European — appended to image names for visual differentiation".tr().toLabel(
                fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f))
            styleHint.wrap = true
            formTable.add(styleHint).growX().left().pad(0f, 188f, 6f, 8f).row()
        }

        formTable.add(sectionHeader("Colors")).fillX().row()

        fun colorFields(key: String, label: String): Triple<UncivTextField, UncivTextField, UncivTextField> {
            val list = nation.raw[key] as? List<*>
            fun get(i: Int): String = (list?.getOrNull(i) as? Number)?.toInt()?.toString() ?: ""
            val r0 = (list?.getOrNull(0) as? Number)?.toInt() ?: 0
            val g0 = (list?.getOrNull(1) as? Number)?.toInt() ?: 0
            val b0 = (list?.getOrNull(2) as? Number)?.toInt() ?: 0
            val preview = Table(BaseScreen.skin)
            preview.background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/NatColor_${key}", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
                Color(r0 / 255f, g0 / 255f, b0 / 255f, 1f))
            fun updatePreview(rF: UncivTextField, gF: UncivTextField, bF: UncivTextField) {
                val r = rF.text.toIntOrNull()?.coerceIn(0, 255) ?: 0
                val g = gF.text.toIntOrNull()?.coerceIn(0, 255) ?: 0
                val b = bF.text.toIntOrNull()?.coerceIn(0, 255) ?: 0
                nation.raw[key] = listOf(r, g, b)
                preview.background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/NatColor_${key}", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
                    Color(r / 255f, g / 255f, b / 255f, 1f))
            }
            val r = colorNumberField(get(0)) { v -> setColor(key, 0, v) }
            val g = colorNumberField(get(1)) { v -> setColor(key, 1, v) }
            val b = colorNumberField(get(2)) { v -> setColor(key, 2, v) }
            r.setTextFieldListener { _, _ -> updatePreview(r, g, b) }
            g.setTextFieldListener { _, _ -> updatePreview(r, g, b) }
            b.setTextFieldListener { _, _ -> updatePreview(r, g, b) }
            val row = Table(BaseScreen.skin)
            row.add((label).toLabel(fontSize = 14)).left().pad(3f).width(180f)
            row.add(r).width(60f).pad(3f)
            row.add("G".toLabel(fontSize = 14)).left().pad(3f)
            row.add(g).width(60f).pad(3f)
            row.add("B".toLabel(fontSize = 14)).left().pad(3f)
            row.add(b).width(60f).pad(3f)
            row.add(preview).size(28f, 20f).pad(4f)
            row.add("(0-255)".tr().toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f)))
                .growX().left().pad(3f)   // 行尾占位：吃满剩余空间，否则右侧空白
            formTable.add(row).growX().left().row()
            return Triple(r, g, b)
        }
        val inner = colorFields("innerColor", "innerColor".tr() + " R")
        innerR = inner.first; innerG = inner.second; innerB = inner.third
        val outer = colorFields("outerColor", "outerColor".tr() + " R")
        outerR = outer.first; outerG = outer.second; outerB = outer.third

        formTable.add(sectionHeader("Start bias")).fillX().row()
        addChipRow("startBias", nation, "startBias", firstMarked = false,
            onChipTap = null,
            onAdd = { refresh -> showAddStartBiasPopup(nation) { refresh() } })

        formTable.add(sectionHeader("Uniques")).fillX().row()

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

        // 仅主要文明：特性名称/说明
        if (!isCityState) {
            uniqueNameField = UncivTextField("", nation.getString("uniqueName"))
            uniqueNameField.setTextFieldListener { field, _ -> nation.setString("uniqueName", field.text) }
            addFieldRow("Unique name", uniqueNameField)
            uniqueTextField = UncivTextField("", nation.getString("uniqueText"))
            uniqueTextField.setTextFieldListener { field, _ -> nation.setString("uniqueText", field.text) }
            addFieldRow("Unique text", uniqueTextField)
        }

        formTable.add(sectionHeader("Cities")).fillX().row()
        addChipRow("Cities", nation, "cities", firstMarked = true,
            onChipTap = { value, refresh ->
                showTextInputPopup("Edit", value) { newValue ->
                    val list = (nation.raw["cities"] as? List<*>)
                        ?.filterIsInstance<String>()?.toMutableList() ?: mutableListOf()
                    val idx = list.indexOf(value)
                    if (idx >= 0) list[idx] = newValue
                    nation.raw["cities"] = list
                    refresh()
                }
            },
            onAdd = { refresh ->
                showTextInputPopup("Add city", null) { newValue ->
                    val list = (nation.raw["cities"] as? List<*>)
                        ?.filterIsInstance<String>()?.toMutableList() ?: mutableListOf()
                    list.add(newValue)
                    nation.raw["cities"] = list
                    refresh()
                }
            },
            extraButton = { refresh -> showBulkEditPopup(nation, "cities", "Cities") { refresh() } })
        formTable.add("The first city is the capital".tr().toLabel(
            fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f))).left().pad(0f, 8f, 6f, 8f).row()

        // 仅主要文明：间谍名字（游戏随机间谍命名用）
        if (!isCityState) {
            formTable.add(sectionHeader("Spy names")).fillX().row()
            addChipRow("Spy names", nation, "spyNames", firstMarked = false,
                onChipTap = { value, refresh ->
                    showTextInputPopup("Edit", value) { newValue ->
                        val list = (nation.raw["spyNames"] as? List<*>)
                            ?.filterIsInstance<String>()?.toMutableList() ?: mutableListOf()
                        val idx = list.indexOf(value)
                        if (idx >= 0) list[idx] = newValue
                        nation.raw["spyNames"] = list
                        refresh()
                    }
                },
                onAdd = { refresh ->
                    showTextInputPopup("Add spy name", null) { newValue ->
                        val list = (nation.raw["spyNames"] as? List<*>)
                            ?.filterIsInstance<String>()?.toMutableList() ?: mutableListOf()
                        list.add(newValue)
                        nation.raw["spyNames"] = list
                        refresh()
                    }
                },
                extraButton = { refresh -> showBulkEditPopup(nation, "spyNames", "Spy names") { refresh() } })
        }

        formTable.add(sectionHeader("Diplomacy")).fillX().row()

        // 战争台词：主要文明和城邦都有
        for (key in listOf("declaringWar", "attacked", "defeated")) {
            val area = TextArea(nation.getString(key), BaseScreen.skin)
            area.setTextFieldListener { f, _ -> nation.setString(key, f.text) }
            diplomacyAreas[key] = area
            val scroll = AutoScrollPane(area).apply {
                setOverscroll(false, false)
                setScrollingDisabled(true, false)
            }
            val row = Table(BaseScreen.skin)
            row.add(key.toLabel(fontSize = 14)).left().pad(3f).width(180f).top()
            row.add(scroll).growX().height(56f).pad(3f)
            formTable.add(row).growX().left().row()
        }
        // 仅主要文明：领袖问候/贸易/谴责等
        if (!isCityState) {
            for (key in listOf("startIntroPart1", "startIntroPart2", "introduction",
                    "denounced", "declaringFriendship")) {
                val area = TextArea(nation.getString(key), BaseScreen.skin)
                area.setTextFieldListener { f, _ -> nation.setString(key, f.text) }
                diplomacyAreas[key] = area
                val scroll = AutoScrollPane(area).apply {
                    setOverscroll(false, false)
                    setScrollingDisabled(true, false)
                }
                val row = Table(BaseScreen.skin)
                row.add(key.toLabel(fontSize = 14)).left().pad(3f).width(180f).top()
                row.add(scroll).growX().height(56f).pad(3f)
                formTable.add(row).growX().left().row()
            }
            for (key in listOf("neutralHello", "hateHello", "tradeRequest",
                    "neutralDenouncing", "hateDenouncing", "neutralRejectingDemand", "hateRejectingDemand")) {
                val field = UncivTextField("", nation.getString(key))
                field.setTextFieldListener { f, _ -> nation.setString(key, f.text) }
                diplomacyFields[key] = field
                addFieldRow(key, field)
            }
        }

        formTable.add(sectionHeader("civilopediaText".tr())).fillX().row()

        civilopediaEditor = CivilopediaTextEditor(
            screen = this,
            getRaw = { nation.raw["civilopediaText"] },
            setRaw = { nation.raw["civilopediaText"] = it }
        )
        civilopediaEditor.addTo(formTable, "civilopediaText")

        formTable.add(sectionHeader("Images")).fillX().row()

        val iconRow = Table(BaseScreen.skin)
        val chooseIconButton = "Choose image…".toTextButton()
        chooseIconButton.onActivation { chooseImage(isLeader = false) }
        iconRow.add(chooseIconButton).pad(6f)
        val removeIconButton = "Remove image".toTextButton()
        removeIconButton.onActivation { removeImage(isLeader = false) }
        iconRow.add(removeIconButton).pad(6f)
        val iconHint = "Nation icon: Images/NationIcons/<name>.png".toLabel(
            fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.5f))
        iconHint.wrap = true
        iconRow.add(iconHint).growX().minWidth(180f).left().pad(6f)
        formTable.add(iconRow).fillX().row()

        val leaderRow = Table(BaseScreen.skin)
        if (!isCityState) {
            val chooseLeaderButton = "Choose image…".toTextButton()
            chooseLeaderButton.onActivation { chooseImage(isLeader = true) }
            leaderRow.add(chooseLeaderButton).pad(6f)
            val removeLeaderButton = "Remove image".toTextButton()
            removeLeaderButton.onActivation { removeImage(isLeader = true) }
            leaderRow.add(removeLeaderButton).pad(6f)
            val leaderHint = "The leader portrait is copied to Images/LeaderIcons/, the file name must match the leader name exactly".toLabel(
                fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.5f))
            leaderHint.wrap = true
            leaderRow.add(leaderHint).growX().minWidth(180f).left().pad(6f)
            formTable.add(leaderRow).fillX().row()
        }

        imageStatusLabel = "".toLabel(fontSize = 13, fontColor = Color(1f, 1f, 1f, 0.55f))
        imageStatusLabel.wrap = true
        formTable.add(imageStatusLabel).growX().left().pad(2f, 8f, 6f, 8f).row()

        formTable.add(sectionHeader("Comment".tr()))
            .fillX().row()

        commentArea = TextArea(nation.comment, BaseScreen.skin)
        formTable.add(commentArea).growX().height(100f).left().pad(6f).row()
    }

    private fun setColor(key: String, index: Int, value: Int?) {
        val nation = currentNation()
        val list = (nation.raw[key] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }?.toMutableList()
            ?: mutableListOf(0, 0, 0)
        while (list.size < 3) list.add(0)
        if (value == null) return
        list[index] = value.coerceIn(0, 255)
        nation.raw[key] = list
    }

    private fun colorNumberField(value: String, onChange: (Int?) -> Unit): UncivTextField {
        val field = UncivTextField("", value)
        field.textFieldFilter = object : TextField.TextFieldFilter {
            override fun acceptChar(textField: TextField, c: Char): Boolean = c in '0'..'9'
        }
        field.setTextFieldListener { f, _ -> onChange(f.text.trim().toIntOrNull()) }
        return field
    }

    private fun addFieldRow(labelKey: String, widget: Actor) {
        val row = Table(BaseScreen.skin)
        row.add(labelKey.toLabel(fontSize = 14)).left().pad(3f).width(180f)
        row.add(widget).growX().minWidth(160f).pad(3f)
        formTable.add(row).growX().left().row()
    }

    // ------------------------------------------------------------------
    // 出生地偏好芯片
    // ------------------------------------------------------------------

    private fun addChipRow(
        labelKey: String,
        nation: ModObjectData,
        rawKey: String,
        firstMarked: Boolean,
        onChipTap: ((String, () -> Unit) -> Unit)?,
        onAdd: ((() -> Unit) -> Unit),
        extraButton: ((() -> Unit) -> Unit)? = null,
    ) {
        val row = Table(BaseScreen.skin)
        row.add(labelKey.toLabel(fontSize = 14)).left().pad(3f).width(180f).top()
        val chipsTable = Table(BaseScreen.skin)

        fun refreshChips() {
            chipsTable.clear()
            val values = (nation.raw[rawKey] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            val maxWidth = formAvailableWidth(stage.width, extraDeduction = 180f)
            var currentRow = Table(BaseScreen.skin)
            var rowWidth = 0f
            for ((index, value) in values.withIndex()) {
                val chip = Table(BaseScreen.skin)
                chip.background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/ConditionChip", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                    Color(0.15f, 0.4f, 0.7f, 0.8f))
                val display = if (firstMarked && index == 0) "★ " + value else value
                val chipLabel = display.toLabel(fontSize = 13)
                val chipLabelWidth = minOf(chipLabel.prefWidth, 280f)
                chipLabel.wrap = true
                chip.add(chipLabel).width(chipLabelWidth).left().pad(4f, 8f, 4f, 2f)
                if (onChipTap != null) {
                    chip.touchable = Touchable.enabled
                    chip.onActivation { onChipTap(value) { refreshChips() } }
                }
                val removeButton = "×".toTextButton()
                removeButton.onActivation {
                    val list = (nation.raw[rawKey] as? List<*>)
                        ?.filterIsInstance<String>()?.toMutableList() ?: mutableListOf()
                    list.remove(value)
                    if (list.isEmpty()) nation.raw.remove(rawKey) else nation.raw[rawKey] = list
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
        addButton.onActivation { onAdd { refreshChips() } }
        val right = Table(BaseScreen.skin)
        right.add(chipsTable).growX().left().row()
        val buttonRow = Table(BaseScreen.skin)
        buttonRow.add(addButton).left().padTop(2f)
        if (extraButton != null) {
            val bulkButton = "Bulk edit".toTextButton()
            bulkButton.onActivation { extraButton { refreshChips() } }
            buttonRow.add(bulkButton).left().padTop(2f).padLeft(8f)
        }
        right.add(buttonRow).left().row()
        row.add(right).growX().left().pad(3f)
        formTable.add(row).growX().left().row()
    }

    /** 批量处理：弹多行输入框，预填当前已有条目（每行一个），可调整顺序，保存后整体替换 */
    private fun showBulkEditPopup(nation: ModObjectData, rawKey: String, titleKey: String, refresh: () -> Unit) {
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        popup.add(("Bulk edit".tr() + " · " + titleKey.tr()).toLabel(fontSize = 20)).pad(8f).row()
        val existing = (nation.raw[rawKey] as? List<*>)
            ?.filterIsInstance<String>()?.joinToString("\n") ?: ""
        val area = TextArea(existing, BaseScreen.skin)
        val scroll = AutoScrollPane(area).apply {
            setOverscroll(false, false)
            setScrollingDisabled(true, false)
        }
        popup.add(scroll).width(560f).height(300f).pad(6f).row()
        popup.add("One entry per line".tr().toLabel(
            fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f))).pad(0f, 8f, 4f, 8f).row()
        popup.addButton("Save") {
            val lines = area.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) nation.raw.remove(rawKey) else nation.raw[rawKey] = lines
            popup.close()
            refresh()
        }
        popup.addCloseButton()
        popup.open()
        stage.keyboardFocus = area
    }

    private fun showTextInputPopup(title: String, existing: String?, onSave: (String) -> Unit) {
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        popup.add(title.tr().toLabel(fontSize = 20)).pad(8f).row()
        val field = UncivTextField("", existing ?: "")
        popup.add(field).growX().width(460f).pad(6f).row()
        popup.addButton("Save") {
            val text = field.text.trim()
            if (text.isNotEmpty()) onSave(text)
            popup.close()
        }
        popup.addCloseButton()
        popup.open()
        stage.keyboardFocus = field
    }

    private fun showAddStartBiasPopup(nation: ModObjectData, onChanged: () -> Unit) {
        val terrains = ModEditorData.getTerrains(modFolder)
        val options = (terrains + listOf("Coast"))
            .flatMap { listOf(it, "Avoid [$it]") }
            .sorted()
        val current = (nation.raw["startBias"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        popup.add(("startBias".tr() + " · " + "Add".tr()).toLabel(fontSize = 20)).pad(8f).row()
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
                if (item in current) continue
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
                    val list = (nation.raw["startBias"] as? List<*>)?.filterIsInstance<String>()?.toMutableList()
                        ?: mutableListOf()
                    list.add(item)
                    nation.raw["startBias"] = list
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

    // ------------------------------------------------------------------
    // 词条
    // ------------------------------------------------------------------

    private fun rebuildUniquesTable() {
        uniquesTable.clear()
        uniquesButtonRow.clear()
        if (currentNation().uniques.isEmpty()) {
            uniquesTable.add("(no uniques)".toLabel(fontSize = 16, fontColor = Color(1f, 1f, 1f, 0.4f)))
                .left().pad(4f).row()
        }
        for ((index, rawString) in currentNation().uniques.withIndex()) {
            val parsed = uniqueCatalog.parseRaw(rawString)
            if (parsed != null) {
                lateinit var editor: UniqueInlineEditor
                editor = UniqueInlineEditor(
                    screen = this, modFolder = modFolder, catalog = uniqueCatalog,
                    unique = parsed.unique, values = parsed.values, conditions = parsed.conditions,
                    onValueChange = { currentNation().uniques[index] = editor.buildRaw() },
                    onStructureChange = {
                        currentNation().uniques[index] = editor.buildRaw()
                        rebuildUniquesTable()
                    },
                    onDuplicate = {
                        val copyValues = parsed.values.toMutableMap()
                        val copyConditions = parsed.conditions
                            .map { (c, v) -> c to v.toMutableMap() }.toMutableList()
                        currentNation().uniques.add(index + 1,
                            uniqueCatalog.buildRawString(parsed.unique, copyValues, copyConditions))
                        rebuildUniquesTable()
                    },
                    onDelete = {
                        currentNation().uniques.removeAt(index)
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
                row.add(label).growX().minWidth(220f).left().pad(4f)
                val editButton = "Edit".toTextButton()
                editButton.onActivation { showUniqueEditor(index, rawString) }
                row.add(editButton).pad(4f)
                val removeButton = "×".toTextButton()
                removeButton.onActivation {
                    currentNation().uniques.removeAt(index)
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
                    currentNation().uniques.add(uniqueCatalog.buildRawString(unique, values, emptyList()))
                    rebuildUniquesTable()
                },
                onRawPicked = { text ->
                    currentNation().uniques.add(text)
                    rebuildUniquesTable()
                }
            ))
        }
        uniquesButtonRow.add(addButton).left().pad(6f)
        addRawEditUniquesButton(this, uniquesButtonRow, getUniques = { currentNation().uniques }) { rebuildUniquesTable() }
        uniquesTable.row()
    }

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
                if (index == null) currentNation().uniques.add(text)
                else if (index < currentNation().uniques.size) currentNation().uniques[index] = text
            } else if (index != null && index < currentNation().uniques.size) {
                currentNation().uniques.removeAt(index)
            }
            popup.close()
            rebuildUniquesTable()
        }
        popup.addCloseButton()
        popup.open()
    }

    // ------------------------------------------------------------------
    // 图片
    // ------------------------------------------------------------------

    private fun iconFile(): FileHandle =
        modFolder.child("Images/NationIcons/${currentNation().name}.png")

    private fun leaderPortraitFile(): FileHandle =
        modFolder.child("Images/LeaderIcons/${currentNation().getString("leaderName")}.png")

    private fun chooseImage(isLeader: Boolean) {
        val impl = ModEditorPlatformHolder.impl ?: return
        val nation = currentNation()
        if (isLeader) {
            if (nation.getString("leaderName").isBlank()) {
                showMessage("Enter a leader name first, then choose a portrait")
                return
            }
        } else if (nation.name.isBlank()) {
            showMessage("Enter a nation name first, then choose an icon")
            return
        }
        val dest = if (isLeader) leaderPortraitFile() else iconFile()
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

    private fun removeImage(isLeader: Boolean) {
        val file = if (isLeader) leaderPortraitFile() else iconFile()
        if (file.exists()) file.delete()
        imageStatusLabel.setText("Image removed".tr())
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    private fun save() {
        val nation = currentNation()

        // 百科文本
        val cpEntries = civilopediaEditor.buildEntries()
        if (cpEntries == null) nation.raw.remove("civilopediaText")
        else nation.raw["civilopediaText"] = cpEntries
        nation.comment = commentArea.text
        nation.syncUniques()

        val problems = ModEditorData.validateNation(modFolder, nation, nations)
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
        ModEditorData.saveObjects(modFolder, "Nations.json", nations)
        val gameProblems = ModEditorData.filterGameProblems(
            ModEditorData.runGameValidation(modFolder), "Nations.json")
        val errors = gameProblems.filter { it.second }
        if (errors.isNotEmpty()) {
            ModEditorData.rollbackFile(modFolder, "Nations.json")
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
        val nation = currentNation()
        val popup = Popup(this)
        val nationName = nation.name.ifBlank { "(unnamed)".tr() }
        popup.add("Are you sure you want to delete [name]?".tr().replace("[name]", nationName).toLabel(fontSize = 20)).pad(12f).row()
        popup.addButton("Delete") {
            nations.removeAt(selectedIndex)
            popup.close()
            if (nations.isEmpty()) {
                selectedIndex = -1
                refreshList()
                formTable.clear()
                formTable.add("No nations. Click \"+ New nation\" in the top left.".toLabel()).pad(20f).row()
            } else {
                select(minOf(selectedIndex, nations.lastIndex))
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

    // ------------------------------------------------------------------
    // 从规则集复制
    // ------------------------------------------------------------------

    private fun showCopyFromRulesetPopup(isCityState: Boolean, initialSource: String? = null) {
        val sourceRuleset = initialSource?.takeIf { it.isNotBlank() }
            ?: ModEditorData.readBaseRulesetChoice(modFolder).ifBlank { com.unciv.models.metadata.BaseRuleset.Civ_V_GnK.fullName }
        val baseNations = ModEditorData.loadBaseObjects(modFolder, "Nations.json", sourceRuleset)
            .filter { it.getString("cityStateType").isNotBlank() == isCityState }
        if (baseNations.isEmpty()) {
            showMessage(if (isCityState) "No city-states found in the base ruleset"
                else "No nations found in the base ruleset")
            return
        }
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        // 来源规则集选择：切换时重建弹窗加载新来源
        val sourceNames = ModEditorData.getBaseRulesetNames()
        val sourceBox = ModEditorSelectBox(sourceNames, sourceRuleset, searchable = true)
        sourceBox.addListener(object : com.badlogic.gdx.scenes.scene2d.utils.ChangeListener() {
            override fun changed(event: com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                popup.close()
                showCopyFromRulesetPopup(isCityState, initialSource = sourceRuleset)
            }
        })
        val sourceRow = Table(BaseScreen.skin)
        sourceRow.add("Source ruleset".tr().toLabel(fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.7f))).left().pad(4f)
        sourceRow.add(sourceBox).growX().width(360f).pad(4f)
        popup.add(sourceRow).growX().width(520f).pad(4f).row()
        popup.add((if (isCityState) "Copy city-state from ruleset" else "Copy nation from ruleset").tr()
            .toLabel(fontSize = 20)).pad(8f).row()
        popup.add("A nation with the same name overrides the base one in-game".tr()
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
            for (base in baseNations) {
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
                    nations.add(copy)
                    popup.close()
                    select(nations.lastIndex)
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
    // 样式辅助
    // ------------------------------------------------------------------

    private fun separatorLine(): Table = Table(BaseScreen.skin).apply {
        background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/Separator", null, Color(1f, 1f, 1f, 0.18f))
    }

    private fun rowBackground() = BaseScreen.skinStrings.getUiBackground(
        "ModEditor/NatRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        BaseScreen.skinStrings.skinConfig.baseColor)

    private fun selectedRowBackground() = BaseScreen.skinStrings.getUiBackground(
        "ModEditor/NatRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        Color(0.2f, 0.5f, 0.9f, 1f))

    private fun sectionHeader(text: String): Table {
        val header = Table(BaseScreen.skin)
        header.add(text.toLabel(fontSize = 20, fontColor = Color(0.55f, 0.85f, 1f, 1f)))
            .left().padTop(12f).padBottom(2f).padLeft(2f)
        header.row()
        header.add(separatorLine()).fillX().height(2f)
        return header
    }
}

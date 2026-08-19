package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.Gdx
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

/** 城邦类型编辑器：name / friendBonusUniques / allyBonusUniques / uniques / color */
class CityStateTypesEditorScreen(private val modFolder: FileHandle) : BaseScreen() {

    private val items = ModEditorData.loadCityStateTypes(modFolder)
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

    private fun current() = items[selectedIndex]

    init {
        val root = Table(BaseScreen.skin)
        root.setFillParent(true)
        stage.addActor(root)

        // ── 顶栏 ──
        val topBar = Table(BaseScreen.skin)
        val backButton = ("‹ " + "Back".tr()).toTextButton()
        backButton.onActivation { game.popScreen() }
        topBar.add(backButton).pad(8f)
        topBar.add(("CityStateTypes".tr() + " · CityStateTypes.json").toLabel(fontSize = 28))
            .padLeft(20f).expandX().left()
        val saveButton = "Save".toTextButton()
        saveButton.onActivation { save() }
        topBar.add(saveButton).pad(8f)
        topBar.add(statusLabel).pad(8f)
        root.add(topBar).fillX().row()

        // ── 左面板 ──
        val leftPanel = Table(BaseScreen.skin)
        val buttonRow = Table(BaseScreen.skin)
        val addButton = "+ New city state type".toTextButton()
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
            val emptyLabel = "No city state types. Click \"+ New city state type\" in the top left.".toLabel(fontSize = 18, fontColor = Color(1f, 1f, 1f, 0.5f))
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
                        "ModEditor/CityStateSelIndicator", null, Color(0.27f, 0.78f, 0.8f, 1f))
                }
                indicator.setSize(3f, 1f)
                row.add(indicator).width(3f).growY().pad(0f)
            }
            // 图标
            val iconName = "CityStateIcons/" + item.name
            if (ImageGetter.imageExists(iconName)) {
                val icon = ImageGetter.getImage(iconName)
                icon.setSize(30f, 30f)
                row.add(icon).size(30f).padRight(8f)
            } else {
                // 色块回退
                val color = getColor(item)
                val swatch = Table(BaseScreen.skin)
                swatch.background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/ColorSwatch", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape, color)
                row.add(swatch).size(30f).padRight(8f)
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
            listTable.add("No city state types yet.".toLabel(fontSize = 16, fontColor = Color(1f, 1f, 1f, 0.4f))).center().pad(20f).row()
        }
    }

    private fun getColor(item: ModObjectData): Color {
        val color = item.raw["color"]
        if (color is List<*> && color.size >= 3) {
            val r = (color[0] as? Number)?.toInt()?.coerceIn(0, 255) ?: 255
            val g = (color[1] as? Number)?.toInt()?.coerceIn(0, 255) ?: 255
            val b = (color[2] as? Number)?.toInt()?.coerceIn(0, 255) ?: 255
            return Color(r / 255f, g / 255f, b / 255f, 1f)
        }
        return Color(1f, 1f, 1f, 1f)
    }

    // ── 表单 ──

    private fun rebuildForm() {
        formTable.clear()
        if (selectedIndex < 0 || selectedIndex >= items.size) return
        val item = current()

        // 表头
        val header = Table(BaseScreen.skin)
        header.add("Edit city state type".toLabel(fontSize = 24)).left().expandX()
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

        // ── Name + Color（一行两个）──
        formTable.add(sectionHeader("Basic info")).fillX().row()
        val nameField = UncivTextField("", item.name)
        nameField.setTextFieldListener { field, _ ->
            item.name = field.text; item.setString("name", field.text); refreshList()
        }

        // Color fields
        val color = item.raw["color"]
        val rgb = if (color is List<*> && color.size >= 3) {
            listOf(
                (color[0] as? Number)?.toInt()?.coerceIn(0, 255) ?: 255,
                (color[1] as? Number)?.toInt()?.coerceIn(0, 255) ?: 255,
                (color[2] as? Number)?.toInt()?.coerceIn(0, 255) ?: 255
            )
        } else listOf(255, 255, 255)

        val preview = Table(BaseScreen.skin)
        preview.background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/ColorPreview", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
            Color(rgb[0] / 255f, rgb[1] / 255f, rgb[2] / 255f, 1f))

        val rField = numberField(rgb[0].toString())
        val gField = numberField(rgb[1].toString())
        val bField = numberField(rgb[2].toString())

        fun updatePreview() {
            val r = rField.text.toIntOrNull()?.coerceIn(0, 255) ?: 0
            val g = gField.text.toIntOrNull()?.coerceIn(0, 255) ?: 0
            val b = bField.text.toIntOrNull()?.coerceIn(0, 255) ?: 0
            item.raw["color"] = listOf(r, g, b)
            preview.background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/ColorPreview", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
                Color(r / 255f, g / 255f, b / 255f, 1f))
            refreshList()
        }
        rField.setTextFieldListener { _, _ -> updatePreview() }
        gField.setTextFieldListener { _, _ -> updatePreview() }
        bField.setTextFieldListener { _, _ -> updatePreview() }

        // Name + Color 行
        val basicRow = Table(BaseScreen.skin)
        basicRow.add("Name".tr().toLabel()).left().pad(4f).width(112f)
        basicRow.add(nameField).growX().minWidth(160f).pad(4f)

        val colorRow = Table(BaseScreen.skin)
        colorRow.add("R".toLabel(fontSize = 14)).left().pad(2f)
        colorRow.add(rField).width(70f).pad(2f)
        colorRow.add("G".toLabel(fontSize = 14)).left().pad(2f)
        colorRow.add(gField).width(70f).pad(2f)
        colorRow.add("B".toLabel(fontSize = 14)).left().pad(2f)
        colorRow.add(bField).width(70f).pad(2f)
        colorRow.add(preview).size(28f, 20f).pad(4f)

        basicRow.add("Color".tr().toLabel()).left().pad(4f).width(112f)
        basicRow.add(colorRow).growX().minWidth(160f).pad(4f)
        formTable.add(basicRow).growX().left().row()

        // ── Friend bonus uniques ──
        formTable.add(sectionHeader("Friend bonus uniques")).fillX().row()
        buildBonusSection(item, "friendBonusUniques")

        // ── Ally bonus uniques ──
        formTable.add(sectionHeader("Ally bonus uniques")).fillX().row()
        buildBonusSection(item, "allyBonusUniques")

        // ── Uniques ──
        formTable.add(sectionHeader("Uniques")).fillX().row()
        buildUniquesSection(item)

        // ── Image ──
        formTable.add(sectionHeader("Image (CityStateIcons)" )).fillX().row()
        val imageRow = Table(BaseScreen.skin)
        val chooseIconButton = (if (iconFile().exists()) "Replace image…" else "Choose image…").toTextButton()
        chooseIconButton.onActivation { chooseImage() }
        imageRow.add(chooseIconButton).pad(6f)
        val removeIconButton = "Remove image".toTextButton()
        removeIconButton.onActivation { removeImage() }
        imageRow.add(removeIconButton).pad(6f)
        val iconHint = "City state icon: Images/CityStateIcons/<name>.png".toLabel(
            fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.5f))
        iconHint.wrap = true
        imageRow.add(iconHint).growX().minWidth(180f).left().pad(6f)
        formTable.add(imageRow).fillX().row()
        imageStatusLabel = "".toLabel(fontSize = 13, fontColor = Color(1f, 1f, 1f, 0.55f))
        imageStatusLabel.wrap = true
        formTable.add(imageStatusLabel).growX().left().pad(2f, 8f, 6f, 8f).row()

        // ── Comment ──
        formTable.add(sectionHeader("Comment")).fillX().row()
        val commentArea = com.badlogic.gdx.scenes.scene2d.ui.TextField(item.comment, BaseScreen.skin)
        commentArea.setTextFieldListener { field, _ -> item.comment = field.text }
        formTable.add(commentArea).growX().pad(4f, 8f, 4f, 8f).row()
    }

    // ── Bonus uniques section ──

    private fun buildBonusSection(item: ModObjectData, field: String) {
        val bonusTable = Table(BaseScreen.skin)
        bonusTable.background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/BonusBox", BaseScreen.skinStrings.roundedEdgeRectangleShape,
            Color(1f, 1f, 1f, 0.07f))

        val list = item.getStringList(field).toMutableList()

        for ((idx, unique) in list.withIndex()) {
            val row = Table(BaseScreen.skin)
            row.add(unique.toLabel(fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.8f)))
                .left().expandX().pad(3f, 8f, 3f, 8f)
            val editBtn = "Edit".toTextButton()
            editBtn.onActivation { showBonusEditPopup(item, field, idx) }
            row.add(editBtn).pad(3f)
            val delBtn = "×".toTextButton()
            delBtn.onActivation {
                val current = item.getStringList(field).toMutableList()
                current.removeAt(idx)
                item.raw[field] = current
                rebuildForm()
            }
            row.add(delBtn).pad(3f)
            bonusTable.add(row).fillX().pad(2f, 4f, 2f, 4f).row()
        }

        val addBtn = "+ Add unique".toTextButton()
        addBtn.onActivation {
            val popup = Popup(this)
            popup.add("Add bonus unique".tr().toLabel(fontSize = 20)).pad(8f).row()
            val field_widget = UncivTextField("", "")
            popup.add(field_widget).growX().pad(4f).row()
            popup.addButton("Add".tr()) {
                val text = field_widget.text.trim()
                if (text.isNotEmpty()) {
                    val current = item.getStringList(field).toMutableList()
                    current.add(text)
                    item.raw[field] = current
                    rebuildForm()
                }
                popup.close()
            }
            popup.addCloseButton()
            popup.open()
        }
        bonusTable.add(addBtn).left().pad(6f, 8f, 6f, 8f).row()

        formTable.add(bonusTable).growX().pad(4f, 8f, 4f, 8f).row()
    }

    private fun showBonusEditPopup(item: ModObjectData, field: String, idx: Int) {
        val list = item.getStringList(field)
        if (idx >= list.size) return
        val popup = Popup(this)
        popup.add("Edit bonus unique".tr().toLabel(fontSize = 20)).pad(8f).row()
        val fieldWidget = UncivTextField("", list[idx])
        popup.add(fieldWidget).growX().pad(4f).row()
        popup.addButton("Save".tr()) {
            val current = item.getStringList(field).toMutableList()
            current[idx] = fieldWidget.text
            item.raw[field] = current
            popup.close(); rebuildForm()
        }
        popup.addCloseButton()
        popup.open()
    }

    // ── Uniques ──

    private lateinit var uniquesTable: Table
    private lateinit var uniquesButtonRow: Table
    private lateinit var imageStatusLabel: com.badlogic.gdx.scenes.scene2d.ui.Label

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
        val problems = ModEditorData.validateCityStateType(modFolder, current(), items)
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
        ModEditorData.saveCityStateTypes(modFolder, items)
        val gameProblems = ModEditorData.filterGameProblems(
            ModEditorData.runGameValidation(modFolder), "CityStateTypes.json")
        val errors = gameProblems.filter { it.second }
        if (errors.isNotEmpty()) {
            ModEditorData.rollbackFile(modFolder, "CityStateTypes.json")
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
        item.name = "New city state type"
        item.raw["name"] = item.name
        item.raw["color"] = listOf(255, 255, 255)
        items.add(item)
        selectedIndex = items.lastIndex
        refreshList(); rebuildForm()
    }

    private fun showCopyPopup(initialSource: String? = null) {
        val sourceRuleset = initialSource?.takeIf { it.isNotBlank() }
            ?: ModEditorData.readBaseRulesetChoice(modFolder).ifBlank { com.unciv.models.metadata.BaseRuleset.Civ_V_GnK.fullName }
        val baseItems = ModEditorData.loadBaseObjects(modFolder, "CityStateTypes.json", sourceRuleset)
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
            popup.add("No city state types found in the base ruleset".tr().toLabel(fontSize = 18)).pad(10f).row()
            popup.addCloseButton(); popup.open(); return
        }
        val popup = Popup(this)
        popup.add("Copy city state type from ruleset".tr().toLabel(fontSize = 22)).pad(8f).row()
        val searchField = UncivTextField("Search")
        val listTable = Table(BaseScreen.skin)
        fun rebuildList(query: String = "") {
            listTable.clear()
            for (item in baseItems) {
                if (query.isNotEmpty() && !item.name.lowercase().contains(query) && !item.name.tr().lowercase().contains(query)) continue
                val row = Table(BaseScreen.skin)
                row.background = rowBackground()
                row.defaults().pad(6f)
                // 图标
                val iconName = "CityStateIcons/" + item.name
                if (ImageGetter.imageExists(iconName)) {
                    val icon = ImageGetter.getImage(iconName)
                    icon.setSize(24f, 24f)
                    row.add(icon).size(24f).padRight(6f)
                } else {
                    val color = getColor(item)
                    val swatch = Table(BaseScreen.skin)
                    swatch.background = BaseScreen.skinStrings.getUiBackground(
                        "ModEditor/CopyColorSwatch", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape, color)
                    row.add(swatch).size(24f).padRight(6f)
                }
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

    private fun numberField(initial: String): UncivTextField {
        val field = UncivTextField("", initial)
        field.textFieldFilter = TextField.TextFieldFilter { _, c -> c in "0123456789" }
        return field
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
        "ModEditor/CityStateRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        BaseScreen.skinStrings.skinConfig.baseColor)

    private fun selectedRowBackground() = BaseScreen.skinStrings.getUiBackground(
        "ModEditor/CityStateRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
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

    // ── Image helpers ──

    private fun currentItem(): ModObjectData = items[selectedIndex]

    private fun iconFile(): FileHandle =
        modFolder.child("Images/CityStateIcons/${currentItem().name}.png")

    private fun chooseImage() {
        val impl = ModEditorPlatformHolder.impl ?: return
        val item = currentItem()
        if (item.name.isBlank()) {
            showMessage("Enter a name first, then choose an icon")
            return
        }
        val dest = iconFile()
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
        val file = iconFile()
        if (file.exists()) file.delete()
        imageStatusLabel.setText("Image removed".tr())
        rebuildForm()
    }

    private fun showMessage(message: String) {
        val popup = Popup(this)
        popup.add(message.toLabel()).pad(12f).row()
        popup.addCloseButton()
        popup.open()
    }
}

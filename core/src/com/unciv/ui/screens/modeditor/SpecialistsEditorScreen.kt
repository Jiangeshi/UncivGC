package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
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

/**
 * 专业人员编辑器：name / 7 项 stats / color(RGB) / greatPersonPoints
 * Specialist extends NamedStats → name 是 lateinit 必需字段（缺失会让 mod 加载崩溃）
 */
class SpecialistsEditorScreen(private val modFolder: FileHandle) : BaseScreen() {

    private val items = ModEditorData.loadSpecialists(modFolder)
    private var selectedIndex = -1

    private val listTable = Table(BaseScreen.skin).apply {
        defaults().expandX().fillX()
        top()   // 列表内容不满时顶部对齐，否则垂直居中（libGDX Table 默认会居中）
    }
    private val formTable = FillWidthTable(BaseScreen.skin)
    private val statusLabel = "".toLabel(fontSize = 16)

    private lateinit var nameField: UncivTextField
    private lateinit var productionField: UncivTextField
    private lateinit var foodField: UncivTextField
    private lateinit var goldField: UncivTextField
    private lateinit var scienceField: UncivTextField
    private lateinit var cultureField: UncivTextField
    private lateinit var happinessField: UncivTextField
    private lateinit var faithField: UncivTextField
    private lateinit var rgbRField: UncivTextField
    private lateinit var rgbGField: UncivTextField
    private lateinit var rgbBField: UncivTextField
    private lateinit var commentArea: TextArea
    private lateinit var searchField: UncivTextField
    private var searchQuery = ""

    private fun current() = items[selectedIndex]

    init {
        val root = Table(BaseScreen.skin)
        root.setFillParent(true)
        stage.addActor(root)

        val topBar = Table(BaseScreen.skin)
        val backButton = ("‹ " + "Back".tr()).toTextButton()
        backButton.onActivation { game.popScreen() }
        topBar.add(backButton).pad(8f)
        topBar.add(("Specialists".tr() + " · Specialists.json").toLabel(fontSize = 28))
            .padLeft(20f).expandX().left()
        val saveButton = "Save".toTextButton()
        saveButton.onActivation { save() }
        topBar.add(saveButton).pad(8f)
        topBar.add(statusLabel).pad(8f)
        root.add(topBar).fillX().row()

        val leftPanel = Table(BaseScreen.skin)
        val buttonRow = Table(BaseScreen.skin)
        val addButton = "+ New specialist".toTextButton()
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
        else formTable.add("No specialists. Click \"+ New specialist\" in the top left.".toLabel()).pad(20f).row()
    }

    private fun refreshList() {
        listTable.clear()
        for ((i, item) in items.withIndex()) {
            if (searchQuery.isNotEmpty() && !item.name.lowercase().contains(searchQuery) &&
                !(item.name.tr().lowercase().contains(searchQuery))) continue
            val row = Table(BaseScreen.skin)
            row.defaults().pad(6f)
            row.background = if (i == selectedIndex) selectedRowBackground() else rowBackground()
            val nameLabel = listNameLabel(
                item.name, maxWidth = stage.width * 0.25f - 100f, fontSize = 20)
            row.add(nameLabel).growX().left().maxWidth(stage.width * 0.25f - 100f)
            row.touchable = com.badlogic.gdx.scenes.scene2d.Touchable.enabled
            row.onActivation { selectedIndex = i; refreshList(); rebuildForm() }
            listTable.add(row).fillX().pad(2f, 6f, 2f, 6f).row()
        }
        if (items.isEmpty()) {
            listTable.add("No specialists yet.".toLabel()).pad(20f).row()
        }
    }

    private fun rebuildForm() {
        formTable.clear()
        if (selectedIndex < 0 || selectedIndex >= items.size) return
        val item = current()

        // 表单头：标题 + Duplicate + Delete
        val header = Table(BaseScreen.skin)
        header.add("Edit specialist".toLabel(fontSize = 24)).left().expandX()
        val copyButton = "Duplicate".toTextButton()
        copyButton.onActivation {
            val copy = ModObjectData()
            copy.name = item.name + " copy"
            copy.comment = item.comment
            item.raw.forEach { (k, v) ->
                copy.raw[k] = when (v) {
                    is List<*> -> ArrayList(v)
                    is Map<*, *> -> LinkedHashMap(v)
                    else -> v
                }
            }
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

        // 产出 stats
        formTable.add(sectionHeader("Yield stats".tr())).fillX().row()
        productionField = numberField(formTable, "production", item.getIntText("production"))
        foodField = numberField(formTable, "food", item.getIntText("food"))
        goldField = numberField(formTable, "gold", item.getIntText("gold"))
        scienceField = numberField(formTable, "science", item.getIntText("science"))
        cultureField = numberField(formTable, "culture", item.getIntText("culture"))
        happinessField = numberField(formTable, "happiness", item.getIntText("happiness"))
        faithField = numberField(formTable, "faith", item.getIntText("faith"))

        // RGB color
        val rgb = item.raw["color"] as? List<*> ?: emptyList<Any?>()
        val r0 = rgb.getOrNull(0)?.let { (it as? Number)?.toInt() } ?: 0
        val g0 = rgb.getOrNull(1)?.let { (it as? Number)?.toInt() } ?: 0
        val b0 = rgb.getOrNull(2)?.let { (it as? Number)?.toInt() } ?: 0
        val preview = Table(BaseScreen.skin)
        preview.background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/SpecialistColor", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
            Color(r0 / 255f, g0 / 255f, b0 / 255f, 1f))
        fun updateColorPreview() {
            val r = rgbRField.text.toIntOrNull()?.coerceIn(0, 255) ?: 0
            val g = rgbGField.text.toIntOrNull()?.coerceIn(0, 255) ?: 0
            val b = rgbBField.text.toIntOrNull()?.coerceIn(0, 255) ?: 0
            item.raw["color"] = arrayListOf(r, g, b)
            preview.background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/SpecialistColor", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
                Color(r / 255f, g / 255f, b / 255f, 1f))
        }
        val rgbRow = Table(BaseScreen.skin)
        rgbRow.add("color".tr().toLabel()).left().pad(4f).width(220f)
        rgbRField = rgbNumberField(r0.toString())
        rgbGField = rgbNumberField(g0.toString())
        rgbBField = rgbNumberField(b0.toString())
        rgbRField.setTextFieldListener { _, _ -> updateColorPreview() }
        rgbGField.setTextFieldListener { _, _ -> updateColorPreview() }
        rgbBField.setTextFieldListener { _, _ -> updateColorPreview() }
        rgbRow.add(rgbRField).width(80f).pad(4f)
        rgbRow.add(rgbGField).width(80f).pad(4f)
        rgbRow.add(rgbBField).width(80f).pad(4f)
        rgbRow.add(preview).size(28f, 20f).pad(4f)
        rgbRow.add("(0-255)".tr().toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f)))
            .growX().left().pad(4f)   // 行尾占位：吃满剩余空间，否则右侧空白
        formTable.add(rgbRow).fillX().left().pad(4f, 10f, 4f, 10f).row()

        // Great Person Points
        formTable.add(sectionHeader("Great person points".tr())).fillX().row()
        buildGreatPersonPointsSection()

        commentArea = textAreaField(formTable, "Comment", item.comment)
    }

    /** 伟人点数区块：每个条目一行（伟人名 + 点数 + × 删除），末尾 + Add 按钮 */
    private fun buildGreatPersonPointsSection() {
        val gpp = getGreatPersonPoints()
        val gppTable = Table(BaseScreen.skin)

        for ((gpName, count) in gpp) {
            val row = Table(BaseScreen.skin)
            // 伟人名下拉（可搜索）
            val greatPeople = ModEditorData.getGreatPeople(modFolder)
            val gpList: List<String> = greatPeople + (if (gpName !in greatPeople) listOf(gpName) else emptyList())
            val gpBox = ModEditorSelectBox(
                gpList,
                gpName, searchable = true)
            gpBox.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    val newName = gpBox.selected?.value ?: return
                    val oldPoints = getGreatPersonPoints()
                    // 移除旧 key，设置新 key
                    val mutable = LinkedHashMap<String, Int>()
                    for ((k, v) in oldPoints) {
                        if (k == gpName) mutable[newName] = v else mutable[k] = v
                    }
                    setGreatPersonPoints(mutable)
                    rebuildForm()
                }
            })
            row.add(gpBox).growX().minWidth(240f).pad(4f)   // 行内控件必须 growX，否则右侧空白

            val countField = UncivTextField("", count.toString())
            countField.textFieldFilter = object : TextField.TextFieldFilter {
                override fun acceptChar(textField: TextField, c: Char): Boolean = c in '0'..'9'
            }
            countField.setTextFieldListener { _, _ ->
                val v = countField.text.trim().toIntOrNull() ?: return@setTextFieldListener
                val mutable = getGreatPersonPoints().toMutableMap()
                mutable[gpName] = v
                setGreatPersonPoints(mutable)
            }
            row.add(countField).width(80f).pad(4f)
            row.add("points".tr().toLabel(fontSize = 14)).growX().left().pad(4f)   // 吃满剩余空间

            val removeBtn = "\u00d7".toTextButton()
            removeBtn.onActivation {
                val mutable = getGreatPersonPoints().toMutableMap()
                mutable.remove(gpName)
                setGreatPersonPoints(mutable)
                rebuildForm()
            }
            row.add(removeBtn).pad(4f)
            gppTable.add(row).fillX().left().row()
        }

        // + Add 按钮
        val addRow = Table(BaseScreen.skin)
        val addBtn = "+ Add great person".toTextButton()
        addBtn.onActivation {
            val greatPeople = ModEditorData.getGreatPeople(modFolder)
            val existing = getGreatPersonPoints().keys
            val available = greatPeople.filter { it !in existing }
            val default = available.firstOrNull() ?: greatPeople.firstOrNull() ?: "Great Scientist"
            val mutable = getGreatPersonPoints().toMutableMap()
            mutable[default] = 1
            setGreatPersonPoints(mutable)
            rebuildForm()
        }
        addRow.add(addBtn).left().pad(4f)
        gppTable.add(addRow).fillX().left().row()

        formTable.add(gppTable).fillX().left().pad(4f, 10f, 4f, 10f).row()
    }

    private fun getGreatPersonPoints(): LinkedHashMap<String, Int> {
        val raw = current().raw["greatPersonPoints"]
        if (raw is Map<*, *>) {
            val result = LinkedHashMap<String, Int>()
            for ((k, v) in raw) {
                result[k.toString()] = (v as? Number)?.toInt() ?: 0
            }
            return result
        }
        return LinkedHashMap()
    }

    private fun setGreatPersonPoints(points: Map<String, Int>) {
        if (points.isEmpty()) current().raw.remove("greatPersonPoints")
        else current().raw["greatPersonPoints"] = LinkedHashMap(points)
    }

    private fun save() {
        if (selectedIndex < 0) return
        val item = current()
        item.name = nameField.text.trim()
        item.raw["name"] = item.name
        setOptionalNumber(item, "production", productionField)
        setOptionalNumber(item, "food", foodField)
        setOptionalNumber(item, "gold", goldField)
        setOptionalNumber(item, "science", scienceField)
        setOptionalNumber(item, "culture", cultureField)
        setOptionalNumber(item, "happiness", happinessField)
        setOptionalNumber(item, "faith", faithField)

        val r = rgbRField.text.trim().toIntOrNull()
        val g = rgbGField.text.trim().toIntOrNull()
        val b = rgbBField.text.trim().toIntOrNull()
        if (r != null && g != null && b != null) item.raw["color"] = arrayListOf(r, g, b)
        else item.raw.remove("color")

        item.comment = commentArea.text.trim()

        val problems = ModEditorData.validateSpecialist(modFolder, item, items)
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
        ModEditorData.saveSpecialists(modFolder, items)
        val gameProblems = ModEditorData.runGameValidation(modFolder)
        val filtered = ModEditorData.filterGameProblems(gameProblems, "Specialists.json")
        val errors = filtered.filter { it.second }
        if (errors.isNotEmpty()) {
            ModEditorData.rollbackFile(modFolder, "Specialists.json")
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

    private fun addItem() {
        val item = ModObjectData()
        item.name = "New specialist"
        item.raw["name"] = item.name
        items.add(item)
        selectedIndex = items.lastIndex
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
        val base = ModEditorData.loadBaseObjects(modFolder, "Specialists.json", sourceRuleset)
        if (base.isEmpty()) {
            showInfoPopup("No specialists found in the base ruleset")
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
        popup.add("Copy specialist from ruleset".toLabel(fontSize = 22)).colspan(2).row()
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
                    for ((k, v) in item.raw) copy.raw[k] = when (v) {
                        is List<*> -> ArrayList(v)
                        is Map<*, *> -> LinkedHashMap(v)
                        else -> v
                    }
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
        "ModEditor/SpecialistRow", BaseScreen.skinStrings.roundedEdgeRectangleShape,
        BaseScreen.skinStrings.skinConfig.baseColor)

    private fun selectedRowBackground() = BaseScreen.skinStrings.getUiBackground(
        "ModEditor/SpecialistRow", BaseScreen.skinStrings.roundedEdgeRectangleShape,
        Color(0.2f, 0.5f, 0.9f, 1f))
}

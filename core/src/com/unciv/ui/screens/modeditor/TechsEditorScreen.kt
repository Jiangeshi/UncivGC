package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextArea
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Align
import com.unciv.models.ruleset.tech.Technology
import com.unciv.models.translations.tr
import com.unciv.ui.components.NonTransformGroup
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

/**
 * 科技编辑器：左「列表」+ 右「树状视图」同时显示（不是切换）。
 * 列表 = 手风琴：新建科技列 → 列行（序号/时代/展开）→ 展开显示「列的属性 + 新增科技框 + 科技列表」
 * → 科技行再点开显示「科技属性」。任何修改实时重绘右侧树状视图。
 */
class TechsEditorScreen(private val modFolder: FileHandle) : BaseScreen() {

    private val groups = ModEditorData.loadTechs(modFolder)
    private val baseRuleset = ModEditorData.getBaseRuleset(modFolder)
    private val uniqueCatalog = UniqueCatalog.load()

    private val leftTable = Table(BaseScreen.skin)
    private val treeTable = Table(BaseScreen.skin)
    private lateinit var treeScroll: AutoScrollPane

    companion object {
        // 科技树节点统一尺寸：所有节点/空格同宽同高，连线才能对齐
        private const val NODE_WIDTH = 258f
        private const val NODE_HEIGHT = 48f
    }

    /** 一次性画线（树表局部坐标，随表滚动；线段有真实尺寸不会被裁剪） */
    private fun drawConnectingLines(nodeByName: Map<String, Table>, mergedList: List<MergedTech>) {
        val tmp = Vector2()
        val lineColor = Color(1f, 1f, 1f, 0.45f)
        // 删除上一轮画的线（避免重复叠加）
        treeTable.children.filterIsInstance<com.badlogic.gdx.scenes.scene2d.ui.Image>()
            .filter { it.name == "TechLine" }.forEach { it.remove() }
        fun hseg(x1: Float, y1: Float, x2: Float) {
            val img = ImageGetter.getWhiteDot()
            img.color = lineColor
            img.name = "TechLine"
            img.setBounds(minOf(x1, x2), y1 - 1f, kotlin.math.abs(x2 - x1), 2f)
            treeTable.addActor(img)
        }
        fun vseg(x: Float, y1: Float, y2: Float) {
            val img = ImageGetter.getWhiteDot()
            img.color = lineColor
            img.name = "TechLine"
            img.setBounds(x - 1f, minOf(y1, y2), 2f, kotlin.math.abs(y2 - y1))
            treeTable.addActor(img)
        }
        for (info in mergedList) {
            val node = nodeByName[info.name] ?: continue
            for (prereq in info.prerequisites) {
                val from = nodeByName[prereq] ?: continue
                tmp.set(from.width, from.height / 2f)
                from.localToStageCoordinates(tmp)
                treeTable.stageToLocalCoordinates(tmp)
                val sx = tmp.x; val sy = tmp.y
                tmp.set(0f, node.height / 2f)
                node.localToStageCoordinates(tmp)
                treeTable.stageToLocalCoordinates(tmp)
                val ex = tmp.x; val ey = tmp.y
                val midX = (sx + ex) / 2f
                hseg(sx, sy, midX)
                vseg(midX, sy, ey)
                hseg(midX, ey, ex)
            }
        }
    }
    private val statusLabel = "".toLabel(fontSize = 16)

    private val expandedColumns = HashSet<TechGroupData>()
    private val expandedTechs = HashSet<TechData>()
    // 字段修改时局部更新的引用（避免整个列表重建导致输入框失焦）
    private val techNameLabels = HashMap<TechData, Label>()
    private val techInfoLabels = HashMap<TechData, Label>()

    init {
        val root = Table(BaseScreen.skin)
        root.setFillParent(true)
        stage.addActor(root)

        val topBar = Table(BaseScreen.skin)
        val backButton = ("‹ " + "Back".tr()).toTextButton()
        backButton.onActivation { game.popScreen() }
        topBar.add(backButton).pad(8f)
        topBar.add(("Techs".tr() + " · Techs.json").toLabel(fontSize = 28))
            .padLeft(20f).expandX().left()
        val saveButton = "Save".toTextButton()
        saveButton.onActivation { save() }
        topBar.add(saveButton).pad(8f)
        topBar.add(statusLabel).pad(8f)
        root.add(topBar).fillX().row()

        // 左：列表（新建按钮 + 滚动手风琴）
        val leftPanel = Table(BaseScreen.skin)
        val buttonRow = Table(BaseScreen.skin)
        val addColumnButton = "+ New column".toTextButton()
        addColumnButton.onActivation { showNewColumnPopup() }
        buttonRow.add(addColumnButton).left().pad(6f)
        val copyColumnButton = "Copy column from ruleset".toTextButton()
        copyColumnButton.onActivation { showCopyColumnPopup() }
        buttonRow.add(copyColumnButton).left().pad(6f)
        leftPanel.add(buttonRow).fillX().row()
        leftPanel.add(separatorLine()).fillX().height(2f).pad(4f, 8f, 4f, 8f).row()
        val leftScroll = AutoScrollPane(leftTable).apply {
            setOverscroll(false, false)
            setScrollingDisabled(false, false)
            fadeScrollBars = false
        }
        leftPanel.add(leftScroll).expand().grow().row()

        // 右：树状视图（ScrollPane + 每帧强制滚动同步）
        treeScroll = AutoScrollPane(treeTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        // ScrollPane 的 visualAmount 默认只在动画模式更新 → widget 位置不随滚动变化（节点/线段都不动）。
        // 每帧手动同步，让 updateActorPosition 能真正移动内容。
        root.addActor(object : Actor() {
            override fun act(delta: Float) {
                super.act(delta)
                treeScroll.updateVisualScroll()
            }
        })

        val body = Table(BaseScreen.skin)
        body.add(leftPanel).width(stage.width / 2f).growY().pad(4f)
        body.addSeparatorVertical(ImageGetter.CHARCOAL, 2f)
        body.add(treeScroll).expand().grow().pad(4f)
        root.add(body).grow()

        rebuildLeft()
        rebuildTree()
    }

    // ------------------------------------------------------------------
    // 左侧列表（手风琴）
    // ------------------------------------------------------------------

    private fun rebuildLeft() {
        leftTable.clear()
        techNameLabels.clear()
        techInfoLabels.clear()
        if (groups.isEmpty()) {
            leftTable.add("No columns yet. Click \"+ New column\" above.".tr().toLabel(
                fontColor = Color(1f, 1f, 1f, 0.5f))).pad(12f).row()
        }
        for (group in groups) {
            val expanded = group in expandedColumns
            // 列行：▸/▾ 序号 时代 展开
            val header = Table(BaseScreen.skin)
            header.background = rowBackground(if (expanded) Color(0.2f, 0.5f, 0.9f, 1f)
                else BaseScreen.skinStrings.skinConfig.baseColor)
            header.add(((if (expanded) "▾ " else "▸ ") + "Column".tr() + " " + group.columnNumber)
                .toLabel(fontSize = 20)).left().expandX().pad(6f)
            header.add(listNameLabel(group.era.tr().ifBlank { "(no era)".tr() },
                maxWidth = max(60f, stage.width * 0.25f - 100f),
                fontSize = 16, fontColor = Color(1f, 1f, 1f, 0.7f))).right().pad(6f)
            header.add((group.techs.size.toString() + " " + "Techs".tr()).toLabel(
                fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.6f))).right().pad(6f)
            header.touchable = Touchable.enabled
            header.onActivation {
                if (expanded) expandedColumns.remove(group) else expandedColumns.add(group)
                rebuildLeft()
                rebuildTree()
            }
            leftTable.add(header).fillX().pad(3f, 8f, 0f, 8f).row()

            if (expanded) {
                // 列的属性（框）
                val propsBox = Table(BaseScreen.skin)
                propsBox.background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/UniquesBox", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                    Color(1f, 1f, 1f, 0.07f))
                propsBox.add("Column properties".tr().toLabel(
                    fontSize = 16, fontColor = Color(0.55f, 0.85f, 1f, 1f)))
                    .left().pad(6f, 8f, 2f, 8f).row()

                val eras = ModEditorData.getEras(modFolder).toMutableList()
                if (group.era.isNotBlank() && group.era !in eras) eras.add(0, group.era)
                val eraBox = ModEditorSelectBox(eras, group.era, searchable = true)
                eraBox.addListener(object : ChangeListener() {
                    override fun changed(event: ChangeEvent?, actor: Actor?) {
                        group.era = eraBox.selected?.value ?: ""
                        rebuildLeft()  // 下拉选择是离散动作，整体重建安全
                        rebuildTree()
                    }
                })
                addInlineRow(propsBox, "Era", eraBox)

                val columnField = inlineNumberField(group.getIntText("columnNumber")) { value ->
                    group.columnNumber = value ?: 0
                    rebuildTree()
                }
                addInlineRow(propsBox, "Column number", columnField)

                val techCostField = inlineNumberField(group.getIntText("techCost")) { value ->
                    group.setInt("techCost", value)
                    rebuildTree()
                }
                addInlineRow(propsBox, "Tech cost", techCostField)
                val buildingCostField = inlineNumberField(group.getIntText("buildingCost")) { value ->
                    group.setInt("buildingCost", value)
                }
                addInlineRow(propsBox, "Building cost", buildingCostField)
                val wonderCostField = inlineNumberField(group.getIntText("wonderCost")) { value ->
                    group.setInt("wonderCost", value)
                }
                addInlineRow(propsBox, "Wonder cost", wonderCostField)

                val deleteColumnButton = "Delete column".toTextButton()
                deleteColumnButton.onActivation { confirmDeleteColumn(group) }
                propsBox.add(deleteColumnButton).left().pad(6f, 8f, 8f, 8f).row()
                leftTable.add(propsBox).growX().pad(2f, 16f, 2f, 8f).row()

                // 一个框：新增科技
                val addBox = Table(BaseScreen.skin)
                addBox.background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/UniquesBox", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                    Color(0.15f, 0.45f, 0.75f, 0.25f))
                val addTechButton = "+ New tech".toTextButton()
                addTechButton.onActivation { addTechToColumn(group) }
                addBox.add(addTechButton).left().pad(6f)
                leftTable.add(addBox).growX().pad(2f, 16f, 2f, 8f).row()

                // 科技列表
                for (tech in group.techs) {
                    val techExpanded = tech in expandedTechs
                    val techRow = Table(BaseScreen.skin)
                    techRow.background = rowBackground(if (techExpanded) Color(0.2f, 0.5f, 0.9f, 1f)
                        else Color(1f, 1f, 1f, 0.06f))
                    val nameLabel = (if (techExpanded) "▾ " else "▸ ") +
                        tech.name.tr().ifBlank { "(unnamed)".tr() }
                    val nameLbl = listNameLabel(
                        nameLabel,
                        maxWidth = stage.width * 0.5f - 150f, fontSize = 16)
                    techNameLabels[tech] = nameLbl
                    techRow.add(ImageGetter.getTechIconPortrait(tech.name, 30f)).left().pad(4f).padLeft(6f)
                    techRow.add(nameLbl).left().expandX().maxWidth(stage.width * 0.5f - 150f).pad(4f).padLeft(6f)
                    val infoLbl = (("Row".tr() + " " + tech.getIntText("row")) +
                        " · " + "Cost".tr() + " " + tech.getIntText("cost").ifBlank { "-" })
                        .toLabel(fontSize = 13, fontColor = Color(1f, 1f, 1f, 0.55f))
                    techInfoLabels[tech] = infoLbl
                    techRow.add(infoLbl).right().pad(4f)
                    techRow.touchable = Touchable.enabled
                    techRow.onActivation {
                        if (techExpanded) expandedTechs.remove(tech) else expandedTechs.add(tech)
                        rebuildLeft()
                        rebuildTree()
                    }
                    leftTable.add(techRow).fillX().pad(2f, 28f, 0f, 8f).row()

                    if (techExpanded) {
                        val techBox = Table(BaseScreen.skin)
                        techBox.background = BaseScreen.skinStrings.getUiBackground(
                            "ModEditor/UniquesBox", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                            Color(1f, 1f, 1f, 0.07f))
                        techBox.add("Tech properties".tr().toLabel(
                            fontSize = 16, fontColor = Color(0.55f, 0.85f, 1f, 1f)))
                            .left().pad(6f, 8f, 2f, 8f).row()

                        val nameField = UncivTextField("", tech.name)
                        nameField.setTextFieldListener { field, _ ->
                            tech.name = field.text
                            tech.setString("name", field.text)
                            techNameLabels[tech]?.setText((if (tech in expandedTechs) "▾ " else "▸ ") +
                                tech.name.tr().ifBlank { "(unnamed)".tr() })
                            rebuildTree()
                        }
                        addInlineRow(techBox, "Name", nameField)

                        val rowField = inlineNumberField(tech.getIntText("row")) { value ->
                            tech.setInt("row", value)
                            techInfoLabels[tech]?.setText(("Row".tr() + " " + tech.getIntText("row")) +
                                " · " + "Cost".tr() + " " + tech.getIntText("cost").ifBlank { "-" })
                            rebuildTree()
                        }
                        addInlineRow(techBox, "Row", rowField)

                        val costField = inlineNumberField(tech.getIntText("cost")) { value ->
                            tech.setInt("cost", value)
                            techInfoLabels[tech]?.setText(("Row".tr() + " " + tech.getIntText("row")) +
                                " · " + "Cost".tr() + " " + tech.getIntText("cost").ifBlank { "-" })
                            rebuildTree()
                        }
                        addInlineRow(techBox, "Cost", costField)

                        val quoteField = UncivTextField("", tech.getString("quote"))
                        quoteField.setTextFieldListener { field, _ -> tech.setString("quote", field.text) }
                        addInlineRow(techBox, "Quote", quoteField)

                        addPrereqChipsRow(techBox, tech)

                        addUniquesSection(techBox, tech)

                        addCivilopediaSection(techBox, tech)

                        // 科技图标：Images/TechIcons/<name>.png
                        val techImage = ModEditorImageSection(
                            modFolder = modFolder,
                            subDirectory = "TechIcons",
                            fileName = { tech.name },
                            preCheck = {
                                if (tech.name.isBlank()) "Enter a tech name first, then choose an image." else null
                            }
                        )
                        techImage.addImageSection(techBox)

                        val deleteButton = "Delete".toTextButton()
                        deleteButton.onActivation { confirmDeleteTech(group, tech) }
                        techBox.add(deleteButton).left().pad(8f)
                        leftTable.add(techBox).growX().pad(2f, 40f, 4f, 8f).row()
                    }
                }
            }
        }
    }

    private fun addInlineRow(table: Table, labelKey: String, widget: Actor) {
        val row = Table(BaseScreen.skin)
        row.add(labelKey.toLabel(fontSize = 14)).left().pad(3f).width(110f)
        row.add(widget).growX().minWidth(140f).pad(3f)
        table.add(row).growX().left().row()
    }

    private fun inlineNumberField(value: String, onChange: (Int?) -> Unit): UncivTextField {
        val field = UncivTextField("", value)
        field.textFieldFilter = object : TextField.TextFieldFilter {
            override fun acceptChar(textField: TextField, c: Char): Boolean {
                if (c in '0'..'9') return true
                if (c == '-' && textField.text.isEmpty()) return true
                return false
            }
        }
        field.setTextFieldListener { f, _ ->
            onChange(f.text.trim().toIntOrNull())
        }
        return field
    }

    // ------------------------------------------------------------------
    // 科技子区块：前置 / 词条 / 百科
    // ------------------------------------------------------------------

    private fun addPrereqChipsRow(table: Table, tech: TechData) {
        val row = Table(BaseScreen.skin)
        row.add("Prerequisites".tr().toLabel(fontSize = 14)).left().pad(3f).width(110f).top()
        val chipsTable = Table(BaseScreen.skin)

        fun refreshChips() {
            chipsTable.clear()
            val maxWidth = formAvailableWidth(stage.width, leftFraction = 0.5f, extraDeduction = 110f)
            var currentRow = Table(BaseScreen.skin)
            var rowWidth = 0f
            for (value in tech.prerequisites) {
                val chip = Table(BaseScreen.skin)
                chip.background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/ConditionChip", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                    Color(0.15f, 0.4f, 0.7f, 0.8f))
                val chipLabel = value.toLabel(fontSize = 13)
                val chipLabelWidth = minOf(chipLabel.prefWidth, 280f)
                chipLabel.wrap = true
                chip.add(chipLabel).width(chipLabelWidth).left().pad(4f, 8f, 4f, 2f)
                val removeButton = "×".toTextButton()
                removeButton.onActivation {
                    tech.prerequisites.remove(value)
                    refreshChips()
                    rebuildTree()
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
        addButton.onActivation { showAddPrereqPopup(tech) { refreshChips() } }
        val right = Table(BaseScreen.skin)
        right.add(chipsTable).growX().left().row()
        right.add(addButton).left().padTop(2f)
        row.add(right).growX().left().pad(3f)
        table.add(row).growX().left().row()
    }

    private fun showAddPrereqPopup(tech: TechData, onChanged: () -> Unit) {
        val (_, _, mergedList) = buildMerged()
        val options = mergedList.map { it.name }.filter { it != tech.name }.distinct().sorted()
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        popup.add(("Prerequisites".tr() + " · " + "Add".tr()).toLabel(fontSize = 20)).pad(8f).row()
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
                if (item in tech.prerequisites) continue
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
                    tech.prerequisites.add(item)
                    popup.close()
                    onChanged()
                    rebuildTree()
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

    private fun addUniquesSection(table: Table, tech: TechData) {
        val uniquesTable = Table(BaseScreen.skin)
        table.add("Uniques".tr().toLabel(fontSize = 14)).left().pad(4f, 8f, 0f, 8f).row()
        rebuildUniquesTable(uniquesTable, tech)
        val uniquesScroll = AutoScrollPane(uniquesTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        table.add(uniquesScroll).growX().height(300f).left().pad(2f, 8f, 4f, 8f).row()
    }

    private fun rebuildUniquesTable(uniquesTable: Table, tech: TechData) {
        uniquesTable.clear()
        if (tech.uniques.isEmpty()) {
            uniquesTable.add("(no uniques)".toLabel(fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.4f)))
                .left().pad(2f).row()
        }
        for ((index, rawString) in tech.uniques.withIndex()) {
            val parsed = uniqueCatalog.parseRaw(rawString)
            if (parsed != null) {
                lateinit var editor: UniqueInlineEditor
                editor = UniqueInlineEditor(
                    screen = this, modFolder = modFolder, catalog = uniqueCatalog,
                    unique = parsed.unique, values = parsed.values, conditions = parsed.conditions,
                    onValueChange = { tech.uniques[index] = editor.buildRaw() },
                    onStructureChange = {
                        tech.uniques[index] = editor.buildRaw()
                        rebuildUniquesTable(uniquesTable, tech)
                    },
                    onDuplicate = {
                        val copyValues = parsed.values.toMutableMap()
                        val copyConditions = parsed.conditions
                            .map { (c, v) -> c to v.toMutableMap() }.toMutableList()
                        tech.uniques.add(index + 1,
                            uniqueCatalog.buildRawString(parsed.unique, copyValues, copyConditions))
                        rebuildUniquesTable(uniquesTable, tech)
                    },
                    onDelete = {
                        tech.uniques.removeAt(index)
                        rebuildUniquesTable(uniquesTable, tech)
                    }
                )
                uniquesTable.add(editor).growX().left().pad(2f, 4f, 2f, 4f).row()
                uniquesTable.add(uniqueSeparatorLine()).growX().height(1f).pad(2f, 8f, 2f, 8f).row()
            } else {
                val row = Table(BaseScreen.skin)
                val label = Label(rawString, BaseScreen.skin).apply {
                    setFontScale(14f / Fonts.ORIGINAL_FONT_SIZE)
                    setAlignment(Align.left)
                    setColor(Color(1f, 1f, 1f, 0.8f))
                    wrap = true
                }
                row.add(label).growX().minWidth(200f).left().pad(2f)
                val editButton = "Edit".toTextButton()
                editButton.onActivation { showUniqueEditor(uniquesTable, tech, index, rawString) }
                row.add(editButton).pad(2f)
                val removeButton = "×".toTextButton()
                removeButton.onActivation {
                    tech.uniques.removeAt(index)
                    rebuildUniquesTable(uniquesTable, tech)
                }
                row.add(removeButton).pad(2f)
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
                    tech.uniques.add(uniqueCatalog.buildRawString(unique, values, emptyList()))
                    rebuildUniquesTable(uniquesTable, tech)
                },
                onRawPicked = { text ->
                    tech.uniques.add(text)
                    rebuildUniquesTable(uniquesTable, tech)
                }
            ))
        }
        uniquesTable.add(addButton).left().pad(4f)
        addRawEditUniquesButton(this, uniquesTable, getUniques = { tech.uniques }) { rebuildUniquesTable(uniquesTable, tech) }
        uniquesTable.row()
    }

    private fun showUniqueEditor(uniquesTable: Table, tech: TechData, index: Int?, existing: String?) {
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
                if (index == null) tech.uniques.add(text)
                else if (index < tech.uniques.size) tech.uniques[index] = text
            } else if (index != null && index < tech.uniques.size) {
                tech.uniques.removeAt(index)
            }
            popup.close()
            rebuildUniquesTable(uniquesTable, tech)
        }
        popup.addCloseButton()
        popup.open()
    }

    private fun addCivilopediaSection(table: Table, tech: TechData) {
        val editor = CivilopediaTextEditor(
            screen = this,
            getRaw = { tech.raw["civilopediaText"] },
            setRaw = { tech.raw["civilopediaText"] = it }
        )
        editor.addTo(table, "civilopediaText")
        // 保存时按 tech 读取：重建富文本条目
        techSaveReaders[tech] = { ->
            val entries = editor.buildEntries()
            if (entries == null) tech.raw.remove("civilopediaText") else tech.raw["civilopediaText"] = entries
            ""
        }
    }

    private val techSaveReaders = HashMap<TechData, () -> String>()

    // ------------------------------------------------------------------
    // 右侧树状视图
    // ------------------------------------------------------------------

    private class MergedTech(
        val name: String,
        val row: Int,
        val costText: String,
        val baseTech: Technology?,
        val modTech: TechData?,
        val modGroup: TechGroupData?,
        val prerequisites: List<String>
    )

    private fun buildMerged(): Triple<Array<Array<MergedTech?>>, List<Pair<String, IntRange>>, List<MergedTech>> {
        val mergedList = mutableListOf<MergedTech>()
        var maxColumn = 0
        var maxRow = 0
        // 基础规则集 mod（isBaseRuleset=true）：科技树是独立完整的，不合并 G&K
        val isBaseRulesetMod = ModEditorData.readIsBaseRuleset(modFolder)
        if (!isBaseRulesetMod) {
            for (tech in baseRuleset.technologies.values) {
                val col = tech.column?.columnNumber ?: 0
                maxColumn = maxOf(maxColumn, col)
                maxRow = maxOf(maxRow, tech.row)
                mergedList.add(MergedTech(tech.name, tech.row, tech.cost.toString(), tech, null, null, tech.prerequisites.toList()))
            }
        }
        for (group in groups) {
            maxColumn = maxOf(maxColumn, group.columnNumber)
            for (tech in group.techs) {
                val row = tech.getIntText("row").toIntOrNull() ?: 1
                maxRow = maxOf(maxRow, row)
                val cost = tech.getIntText("cost").ifBlank { group.getIntText("techCost") }
                mergedList.add(MergedTech(tech.name, row, cost, null, tech, group, tech.prerequisites))
            }
        }
        val columns = maxColumn + 1
        val rows = maxOf(maxRow, 1)
        val matrix = Array(columns) { arrayOfNulls<MergedTech>(rows) }
        for (info in mergedList) {
            val col = info.modGroup?.columnNumber ?: (info.baseTech?.column?.columnNumber ?: 0)
            if (col in 0 until columns && info.row in 1..rows) matrix[col][info.row - 1] = info
        }
        val eraOfColumn = HashMap<Int, String>()
        if (!isBaseRulesetMod) {
            for (tech in baseRuleset.technologies.values) {
                val col = tech.column?.columnNumber ?: continue
                eraOfColumn[col] = tech.column!!.era
            }
        }
        for (group in groups) eraOfColumn[group.columnNumber] = group.era
        val runs = mutableListOf<Pair<String, IntRange>>()
        var i = 0
        while (i < columns) {
            val era = eraOfColumn[i] ?: ""
            var j = i
            while (j + 1 < columns && (eraOfColumn[j + 1] ?: "") == era) j++
            runs.add(era to i..j)
            i = j + 1
        }
        return Triple(matrix, runs, mergedList)
    }

    private fun rebuildTree() {
        treeTable.clear()
        val (matrix, runs, mergedList) = buildMerged()
        val columns = matrix.size
        val rows = if (columns == 0) 0 else matrix[0].size
        val nodeByName = HashMap<String, Table>()

        for ((era, range) in runs) {
            if (era.isEmpty()) treeTable.add().colspan(range.last - range.first + 1)
            else treeTable.add(era.tr().toLabel(fontSize = 18, fontColor = Color(0.6f, 0.85f, 1f, 1f)))
                .colspan(range.last - range.first + 1).center().pad(4f)
        }
        treeTable.row()

        for (rowIndex in 0 until rows) {
            for (colIndex in 0 until columns) {
                val info = matrix[colIndex][rowIndex]
                if (info == null) {
                    treeTable.add().width(NODE_WIDTH).height(NODE_HEIGHT).pad(2f).padRight(24f).padLeft(8f)
                } else {
                    val node = buildNode(info)
                    nodeByName[info.name] = node
                    treeTable.add(node).width(NODE_WIDTH).height(NODE_HEIGHT).pad(2f).padRight(24f).padLeft(8f)
                }
            }
            treeTable.row()
        }

        treeTable.pack()
        treeTable.validate() // 强制布局：pack 只算尺寸，节点坐标需 validate 后才就位（否则连线错位）
        treeScroll.updateVisualScroll()
        // 延迟一帧画线：确保 AutoScrollPane/Stage 完整布局后节点坐标才最终确定
        Gdx.app.postRunnable {
            drawConnectingLines(nodeByName, mergedList)
        }
    }

    private fun buildNode(info: MergedTech): Table {
        val isMod = info.modTech != null
        val isExpanded = info.modTech != null && info.modTech in expandedTechs
        val color = when {
            isExpanded -> Color(1f, 0.65f, 0.2f, 1f)
            isMod -> Color(0.15f, 0.45f, 0.75f, 0.9f)
            else -> Color(1f, 1f, 1f, 0.14f)
        }
        val node = Table(BaseScreen.skin)
        node.background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/TechNode", BaseScreen.skinStrings.roundedEdgeRectangleMidShape, color)
        node.add(ImageGetter.getTechIconPortrait(info.name, 32f)).pad(2f).padLeft(4f)
        // 科技名：双语显示 + 省略号截断；固定宽度保证所有节点同宽（连线才能对齐）
        val nameLabel = listNameLabel(
            info.name, maxWidth = 180f, fontSize = 14)
        node.add(nameLabel).width(180f).left().pad(2f)
        node.add(info.costText.toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.6f)))
            .width(30f).right().pad(2f).padRight(4f)
        node.touchable = Touchable.enabled
        node.onActivation {
            if (info.modTech != null && info.modGroup != null) {
                expandedColumns.add(info.modGroup)
                expandedTechs.add(info.modTech)
                rebuildLeft()
                rebuildTree()
            } else showBaseTechPopup(info)
        }
        return node
    }

    private fun showBaseTechPopup(info: MergedTech) {
        val popup = Popup(this)
        popup.add(info.name.tr().toLabel(fontSize = 20)).pad(10f).row()
        popup.add("This tech is from the base ruleset and cannot be edited here".tr()
            .toLabel(fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.6f))).pad(6f).row()
        popup.addCloseButton()
        popup.open()
    }

    // ------------------------------------------------------------------
    // 新建列 / 新增科技 / 删除
    // ------------------------------------------------------------------

    private fun showCopyColumnPopup() {
        val baseColumns = ModEditorData.loadBaseTechColumns(modFolder)
        if (baseColumns.isEmpty()) { showMessage("No columns found in the base ruleset"); return }
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        popup.add("Copy column from ruleset".tr().toLabel(fontSize = 20)).pad(8f).row()
        popup.add("Techs with the same name override the base ones in-game".tr()
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
            for (base in baseColumns) {
                val era = base.era.ifBlank { "(no era)".tr() }
                val techNames = base.techs.joinToString(", ") { it.name }
                val title = "$era · " + "Column".tr() + " ${base.columnNumber}"
                if (q.isNotEmpty() && !title.lowercase().contains(q) &&
                    !techNames.lowercase().contains(q) && !era.lowercase().contains(q)) continue
                val row = Table(BaseScreen.skin)
                row.background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/SearchRow", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                    BaseScreen.skinStrings.skinConfig.baseColor)
                val textTable = Table(BaseScreen.skin)
                textTable.add(title.toLabel(fontSize = 15)).growX().left().pad(2f, 8f, 0f, 8f).row()
                val techLabel = techNames.toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.55f))
                techLabel.wrap = true
                textTable.add(techLabel).growX().left().pad(0f, 8f, 4f, 8f)
                row.add(textTable).growX().left().pad(4f)
                row.touchable = Touchable.enabled
                row.onActivation {
                    val copy = ModEditorData.deepCopyTechColumn(base)
                    groups.add(copy)
                    popup.close()
                    expandedColumns.add(copy)
                    rebuildLeft()
                    rebuildTree()
                    statusLabel.setText("Copied".tr() + ": " + title)
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

    private fun showMessage(message: String) {
        val popup = Popup(this)
        popup.add(message.tr().toLabel()).pad(12f).row()
        popup.addCloseButton()
        popup.open()
    }

    private fun showNewColumnPopup() {
        val eras = ModEditorData.getEras(modFolder)
        if (eras.isEmpty()) return
        val popup = Popup(this)
        popup.add("New column".tr().toLabel(fontSize = 22)).pad(10f).row()
        val eraBox = ModEditorSelectBox(eras, eras.first(), searchable = true)
        popup.add("Era".tr().toLabel()).left().pad(6f)
        popup.add(eraBox).width(280f).row()
        popup.addButton("Create") {
            var maxColumn = 0
            for (tech in baseRuleset.technologies.values)
                maxColumn = maxOf(maxColumn, tech.column?.columnNumber ?: 0)
            for (group in groups) maxColumn = maxOf(maxColumn, group.columnNumber)
            val group = TechGroupData().apply {
                era = eraBox.selected?.value ?: ""
                columnNumber = maxColumn + 1
            }
            groups.add(group)
            expandedColumns.add(group)
            popup.close()
            rebuildLeft()
            rebuildTree()
        }
        popup.addCloseButton()
        popup.open()
    }

    private fun addTechToColumn(group: TechGroupData) {
        val maxRow = group.techs.mapNotNull { it.getIntText("row").toIntOrNull() }.maxOrNull() ?: 0
        val tech = TechData().apply {
            name = "New tech"
            raw["row"] = maxRow + 1
            raw["cost"] = 20
        }
        group.techs.add(tech)
        expandedTechs.add(tech)
        rebuildLeft()
        rebuildTree()
    }

    private fun confirmDeleteTech(group: TechGroupData, tech: TechData) {
        val popup = Popup(this)
        // 注意：必须用 ${tech.name}（$tech.name 会被解析成 $tech + 字面量 .name，显示对象 toString）
        popup.add("Are you sure you want to delete [name]?".tr().replace("[name]", tech.name).toLabel(fontSize = 20)).pad(12f).row()
        popup.addButton("Delete") {
            group.techs.remove(tech)
            expandedTechs.remove(tech)
            popup.close()
            rebuildLeft()
            rebuildTree()
        }
        popup.addCloseButton()
        popup.open()
    }

    private fun confirmDeleteColumn(group: TechGroupData) {
        val popup = Popup(this)
        popup.add("Are you sure you want to delete this column and its [count] techs?"
            .replace("[count]", group.techs.size.toString()).tr().toLabel(fontSize = 20)).pad(12f).row()
        popup.addButton("Delete") {
            groups.remove(group)
            expandedColumns.remove(group)
            for (tech in group.techs) expandedTechs.remove(tech)
            popup.close()
            rebuildLeft()
            rebuildTree()
        }
        popup.addCloseButton()
        popup.open()
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    private fun save() {
        // 百科文本：从富文本编辑器写回 raw（reader 内已处理）
        for ((_, reader) in techSaveReaders) reader()
        val problems = ModEditorData.validateTechs(modFolder, groups)
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
        ModEditorData.saveTechs(modFolder, groups)
        val gameProblems = ModEditorData.filterGameProblems(
            ModEditorData.runGameValidation(modFolder), "Techs.json")
        val errors = gameProblems.filter { it.second }
        if (errors.isNotEmpty()) {
            ModEditorData.rollbackFile(modFolder, "Techs.json")
            statusLabel.setText("Save failed".tr())
            showGameProblemsPopup(gameProblems, saved = false)
            return
        }
        statusLabel.setText("Saved".tr())
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

    // ------------------------------------------------------------------
    // 样式辅助
    // ------------------------------------------------------------------

    private fun separatorLine(): Table = Table(BaseScreen.skin).apply {
        background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/Separator", null, Color(1f, 1f, 1f, 0.18f))
    }

    private fun rowBackground(tint: Color = BaseScreen.skinStrings.skinConfig.baseColor) =
        BaseScreen.skinStrings.getUiBackground(
            "ModEditor/UnitRow", BaseScreen.skinStrings.roundedEdgeRectangleShape, tint)
}

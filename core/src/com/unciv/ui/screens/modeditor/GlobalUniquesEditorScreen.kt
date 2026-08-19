package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextArea
import com.badlogic.gdx.utils.Align
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 * GlobalUniques 编辑器：上下两个板块
 * - 上：Global uniques（uniques 字段）——模组级全局词条
 * - 下：Unit uniques（unitUniques 字段）——应用到所有单位的词条
 * 单对象文件（非数组），name 固定 "Global Uniques"。
 */
class GlobalUniquesEditorScreen(private val modFolder: FileHandle) : BaseScreen() {

    private val data = ModEditorData.loadGlobalUniques(modFolder)
    private val uniqueCatalog = UniqueCatalog.load()
    private val formTable = FillWidthTable(BaseScreen.skin)
    private val statusLabel = "".toLabel(fontSize = 16)

    private lateinit var globalUniquesTable: Table
    private lateinit var unitUniquesTable: Table
    private lateinit var globalButtonRow: Table
    private lateinit var unitButtonRow: Table
    private lateinit var civilopediaEditor: CivilopediaTextEditor
    /** unitUniques 的编辑引用：与 data.raw["unitUniques"] 同步 */
    private val unitUniquesList = mutableListOf<String>()

    init {
        val root = Table(BaseScreen.skin)
        root.setFillParent(true)
        stage.addActor(root)

        val topBar = Table(BaseScreen.skin)
        val backButton = ("‹ " + "Back".tr()).toTextButton()
        backButton.onActivation { game.popScreen() }
        topBar.add(backButton).pad(8f)
        topBar.add(("GlobalUniques".tr() + " · GlobalUniques.json").toLabel(fontSize = 28))
            .padLeft(20f).expandX().left()
        val saveButton = "Save".toTextButton()
        saveButton.onActivation { save() }
        topBar.add(saveButton).pad(8f)
        topBar.add(statusLabel).pad(8f)
        root.add(topBar).fillX().row()

        val rightScroll = AutoScrollPane(formTable).apply {
            setOverscroll(false, false)
            setScrollingDisabled(true, false)
        }
        root.add(rightScroll).expand().grow().pad(4f)

        rebuildForm()
    }

    private fun rebuildForm() {
        formTable.clear()

        // ============ 板块 1：Global uniques ============
        formTable.add(sectionHeader("Global uniques".tr())).fillX().row()
        formTable.add("Ruleset-wide modifiers that apply to all civilizations".tr()
            .toLabel(fontSize = 13, fontColor = Color(1f, 1f, 1f, 0.5f)))
            .left().pad(2f, 10f, 4f, 10f).row()
        globalUniquesTable = Table(BaseScreen.skin)
        globalButtonRow = Table(BaseScreen.skin)
        rebuildUniquesTable(globalUniquesTable, globalButtonRow, data.uniques) { data.uniques }
        formTable.add(uniquesBox(globalUniquesTable)).growX().height(400f).left().pad(6f).row()
        formTable.add(globalButtonRow).growX().left().pad(4f, 6f, 4f, 6f).row()

        // ============ 板块 2：Unit uniques ============
        formTable.add(sectionHeader("Unit uniques".tr())).fillX().row()
        formTable.add("Applied to all units (unitUniques)".tr()
            .toLabel(fontSize = 13, fontColor = Color(1f, 1f, 1f, 0.5f)))
            .left().pad(2f, 10f, 4f, 10f).row()
        unitUniquesTable = Table(BaseScreen.skin)
        unitUniquesList.clear()
        unitUniquesList.addAll(data.getStringList("unitUniques"))
        unitButtonRow = Table(BaseScreen.skin)
        rebuildUniquesTable(unitUniquesTable, unitButtonRow, unitUniquesList) { unitUniquesList }
        formTable.add(uniquesBox(unitUniquesTable)).growX().height(400f).left().pad(6f).row()
        formTable.add(unitButtonRow).growX().left().pad(4f, 6f, 4f, 6f).row()

        // ============ 百科文本 ============
        civilopediaEditor = CivilopediaTextEditor(
            screen = this,
            getRaw = { data.raw["civilopediaText"] },
            setRaw = { data.raw["civilopediaText"] = it }
        )
        civilopediaEditor.addTo(formTable, "Civilopedia text")
    }

    private fun uniquesBox(uniquesTable: Table): Table {
        val box = Table(BaseScreen.skin)
        box.background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/UniquesBox", BaseScreen.skinStrings.roundedEdgeRectangleShape,
            Color(1f, 1f, 1f, 0.07f))
        val scroll = AutoScrollPane(uniquesTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        box.add(scroll).grow().pad(10f)
        return box
    }

    /** 通用词条列表重建：list = 源列表引用（data.uniques 或 unitUniques 的 MutableList 包装） */
    private fun rebuildUniquesTable(
        table: Table,
        buttonRow: Table,
        list: MutableList<String>,
        listProvider: () -> MutableList<String>
    ) {
        table.clear()
        buttonRow.clear()
        if (list.isEmpty()) {
            table.add("(no uniques)".toLabel(fontSize = 16, fontColor = Color(1f, 1f, 1f, 0.4f)))
                .left().pad(4f).row()
        }
        for ((index, rawString) in list.withIndex()) {
            val parsed = uniqueCatalog.parseRaw(rawString)
            if (parsed != null) {
                lateinit var editor: UniqueInlineEditor
                editor = UniqueInlineEditor(
                    screen = this, modFolder = modFolder, catalog = uniqueCatalog,
                    unique = parsed.unique, values = parsed.values, conditions = parsed.conditions,
                    onValueChange = { list[index] = editor.buildRaw() },
                    onStructureChange = {
                        list[index] = editor.buildRaw()
                        rebuildUniquesTable(table, buttonRow, listProvider(), listProvider)
                    },
                    onDuplicate = {
                        val copyValues = parsed.values.toMutableMap()
                        val copyConditions = parsed.conditions
                            .map { (c, v) -> c to v.toMutableMap() }.toMutableList()
                        list.add(index + 1, uniqueCatalog.buildRawString(parsed.unique, copyValues, copyConditions))
                        rebuildUniquesTable(table, buttonRow, listProvider(), listProvider)
                    },
                    onDelete = {
                        list.removeAt(index)
                        rebuildUniquesTable(table, buttonRow, listProvider(), listProvider)
                    }
                )
                table.add(editor).growX().left().pad(3f, 8f, 3f, 8f).row()
                table.add(uniqueSeparatorLine()).growX().height(1f).pad(2f, 8f, 2f, 8f).row()
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
                editButton.onActivation { showUniqueEditor(list, index, rawString, table, buttonRow, listProvider) }
                row.add(editButton).pad(4f)
                val removeButton = "×".toTextButton()
                removeButton.onActivation {
                    list.removeAt(index)
                    rebuildUniquesTable(table, buttonRow, listProvider(), listProvider)
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
                    list.add(uniqueCatalog.buildRawString(unique, values, emptyList()))
                    rebuildUniquesTable(table, buttonRow, listProvider(), listProvider)
                },
                onRawPicked = { text ->
                    list.add(text)
                    rebuildUniquesTable(table, buttonRow, listProvider(), listProvider)
                }
            ))
        }
        buttonRow.add(addButton).left().pad(4f)
        addRawEditUniquesButton(this, buttonRow, getUniques = { list }) { rebuildUniquesTable(table, buttonRow, listProvider(), listProvider) }
        table.row()
    }

    private fun showUniqueEditor(
        list: MutableList<String>,
        index: Int,
        currentRaw: String,
        table: Table,
        buttonRow: Table,
        listProvider: () -> MutableList<String>
    ) {
        val popup = Popup(this)
        popup.add("Edit unique (raw)".toLabel(fontSize = 20)).padBottom(8f).row()
        val textArea = TextArea(currentRaw, BaseScreen.skin)
        popup.add(textArea).growX().height(120f).pad(4f).row()
        popup.add("No quotes needed - they are added automatically when saving".toLabel(fontSize = 14, fontColor = Color(1f, 1f, 1f, 0.5f))).padBottom(8f).row()
        val saveBtn = "Save".toTextButton()
        saveBtn.onActivation {
            val cleaned = textArea.text.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s{2,}"), " ").trim()
            list[index] = cleaned
            rebuildUniquesTable(table, buttonRow, listProvider(), listProvider)
            popup.close()
        }
        popup.add(saveBtn).pad(8f)
        val cancelBtn = "Cancel".toTextButton()
        cancelBtn.onActivation { popup.close() }
        popup.add(cancelBtn).pad(8f)
        popup.open()
    }

    // ------------------------------------------------------------------
    // 保存 / 校验
    // ------------------------------------------------------------------

    private fun save() {
        data.syncUniques()
        data.setStringList("unitUniques", unitUniquesList)
        val cpEntries = civilopediaEditor.buildEntries()
        if (cpEntries == null) data.raw.remove("civilopediaText") else data.raw["civilopediaText"] = cpEntries

        val problems = ModEditorData.validateGlobalUniques(data)
        val errors = problems.filter { it.second }
        if (errors.isNotEmpty()) {
            showProblemsPopup("Save failed".tr(), errors.map { it.first }, true)
            return
        }
        doSave()
    }

    private fun doSave() {
        ModEditorData.saveGlobalUniques(modFolder, data)
        val gameProblems = ModEditorData.filterGameProblems(
            ModEditorData.runGameValidation(modFolder), "GlobalUniques.json")
        val errors = gameProblems.filter { it.second }
        if (errors.isNotEmpty()) {
            ModEditorData.rollbackFile(modFolder, "GlobalUniques.json")
            statusLabel.setText("Save failed".tr())
            showGameProblemsPopup(gameProblems, saved = false)
            return
        }
        statusLabel.setText("Saved".tr())
        if (gameProblems.isNotEmpty()) showGameProblemsPopup(gameProblems, saved = true)
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

    // ------------------------------------------------------------------
    // 样式辅助（与所有编辑器一致）
    // ------------------------------------------------------------------

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

    private fun textAreaField(table: Table, label: String, value: String): TextArea {
        table.add(label.toLabel()).left().pad(4f, 10f, 0f, 10f).row()
        val area = TextArea(value, BaseScreen.skin)
        area.setPrefRows(3f)
        table.add(area).growX().height(80f).pad(4f, 10f, 4f, 10f).row()
        return area
    }
}

package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
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

/** Religions 编辑器：纯字符串列表（Religions.json 就是预定义宗教名数组，图标按名字对应） */
class ReligionsEditorScreen(private val modFolder: FileHandle) : BaseScreen() {

    private val religions = ModEditorData.loadReligions(modFolder)
    private var selectedIndex = -1

    private val listTable = Table(BaseScreen.skin).apply {
        defaults().expandX().fillX()
        top()   // 列表内容不满时顶部对齐，否则垂直居中
    }
    private val formTable = FillWidthTable(BaseScreen.skin)
    private val statusLabel = "".toLabel(fontSize = 16)
    private lateinit var searchField: UncivTextField
    private var searchQuery = ""
    private lateinit var nameField: UncivTextField
    private var selectedRowNameLabel: Label? = null

    init {
        val root = Table(BaseScreen.skin)
        root.setFillParent(true)
        stage.addActor(root)

        // 顶栏
        val topBar = Table(BaseScreen.skin)
        val backButton = ("‹ " + "Back".tr()).toTextButton()
        backButton.onActivation { game.popScreen() }
        topBar.add(backButton).pad(8f)
        topBar.add(("Religions".tr() + " · Religions.json").toLabel(fontSize = 28))
            .padLeft(20f).expandX().left()
        val saveButton = "Save".toTextButton()
        saveButton.onActivation { save() }
        topBar.add(saveButton).pad(8f)
        topBar.add(statusLabel).pad(8f)
        root.add(topBar).fillX().row()

        // 左侧：按钮行 + 搜索 + 平铺列表
        val leftPanel = Table(BaseScreen.skin)
        val buttonRow = Table(BaseScreen.skin)
        val addButton = "+ New religion".toTextButton()
        addButton.onActivation { addReligion() }
        buttonRow.add(addButton).left().pad(6f)
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
        if (religions.isNotEmpty()) select(0)
        else formTable.add("No religions. Click \"+ New religion\" in the top left.".toLabel()).pad(20f).row()
    }

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
        "ModEditor/ReligionRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        BaseScreen.skinStrings.skinConfig.baseColor)

    private fun selectedRowBackground() = BaseScreen.skinStrings.getUiBackground(
        "ModEditor/ReligionRow", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        Color(0.2f, 0.5f, 0.9f, 1f))

    private fun refreshList() {
        listTable.clear()
        for ((i, name) in religions.withIndex()) {
            if (searchQuery.isNotEmpty() && !name.lowercase().contains(searchQuery) &&
                !(name.tr().lowercase().contains(searchQuery))) continue
            val isSelected = i == selectedIndex
            val row = Table(BaseScreen.skin)
            row.defaults().pad(6f)
            row.background = if (isSelected) selectedRowBackground() else rowBackground()
            val nameLabel = listNameLabel(
                name,
                maxWidth = stage.width * 0.25f - 100f,
                fontSize = 20,
                fontColor = if (isSelected) Color.WHITE else Color(1f, 1f, 1f, 0.85f))
            if (isSelected) selectedRowNameLabel = nameLabel
            row.add(nameLabel).growX().left().maxWidth(stage.width * 0.25f - 60f)
            row.touchable = Touchable.enabled
            row.onActivation { selectedIndex = i; refreshList(); rebuildForm() }
            listTable.add(row).fillX().pad(2f, 6f, 2f, 6f).row()
        }
        if (religions.isEmpty()) {
            listTable.add("No religions yet.".toLabel(fontSize = 16, fontColor = Color(1f, 1f, 1f, 0.4f))).pad(20f).row()
        }
    }

    private fun select(index: Int) {
        selectedIndex = index
        refreshList()
        rebuildForm()
    }

    private fun addReligion() {
        val name = nextName()
        religions.add(name)
        select(religions.lastIndex)
    }

    private fun nextName(): String {
        val existing = religions.toSet()
        var i = 1
        while (("New religion" + if (i == 1) "" else " $i") in existing) i++
        return "New religion" + if (i == 1) "" else " $i"
    }

    private fun rebuildForm() {
        formTable.clear()
        if (selectedIndex < 0 || selectedIndex >= religions.size) return
        val name = religions[selectedIndex]

        val header = Table(BaseScreen.skin)
        header.add("Edit religion".toLabel(fontSize = 24)).left().expandX()
        val deleteButton = "Delete".toTextButton()
        deleteButton.onActivation { confirmDelete() }
        header.add(deleteButton).pad(4f)
        formTable.add(header).fillX().row()

        nameField = UncivTextField("Religion name (required)", name)
        nameField.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                selectedRowNameLabel?.setText(nameField.text.ifBlank { "(unnamed)".tr() })
            }
        })
        val row = Table(BaseScreen.skin)
        row.add("Name".tr().toLabel()).left().pad(4f).width(180f)
        row.add(nameField).growX().minWidth(200f).pad(4f)
        formTable.add(row).growX().left().row()
        // 提示不带尖括号：<name> 会被 tr() 当条件处理导致翻译失败（2026-08-19 用户报没翻译）
        val hint = "Religions are just containers for beliefs; the icon must exist as ReligionIcons/NAME.png".tr()
            .toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f))
        hint.wrap = true
        formTable.add(hint).growX().left().pad(0f, 8f, 6f, 8f).row()

        // 图片上传：Images/ReligionIcons/<name>.png（2026-08-19 用户要求）
        formTable.add(sectionHeader("Image (ReligionIcons/)".tr())).fillX().row()
        val imageSection = ModEditorImageSection(
            modFolder = modFolder,
            subDirectory = "ReligionIcons",
            fileName = { religions[selectedIndex] },
            preCheck = {
                if (nameField.text.trim().isBlank()) "Enter a religion name first, then choose an image".tr() else null
            }
        )
        imageSection.addImageSection(formTable)
    }

    private fun save() {
        if (selectedIndex < 0) return
        val newName = nameField.text.trim()
        if (newName.isBlank()) {
            showMessage("Religion name cannot be empty".tr())
            return
        }
        religions[selectedIndex] = newName
        ModEditorData.saveReligions(modFolder, religions)
        val gameProblems = ModEditorData.filterGameProblems(
            ModEditorData.runGameValidation(modFolder), "Religions.json")
        val errors = gameProblems.filter { it.second }
        if (errors.isNotEmpty()) {
            ModEditorData.rollbackFile(modFolder, "Religions.json")
            statusLabel.setText("Save failed".tr())
            showGameProblemsPopup(gameProblems, saved = false)
            return
        }
        statusLabel.setText("Saved".tr())
        refreshList()
        if (gameProblems.isNotEmpty()) showGameProblemsPopup(gameProblems, saved = true)
    }

    private fun confirmDelete() {
        val popup = Popup(this)
        val name = religions[selectedIndex]
        popup.add("Are you sure you want to delete [$name]?".tr().toLabel(fontSize = 20)).pad(12f).row()
        popup.addButton("Delete".tr()) {
            religions.removeAt(selectedIndex)
            popup.close()
            if (religions.isEmpty()) {
                selectedIndex = -1
                refreshList()
                formTable.clear()
                formTable.add("No religions. Click \"+ New religion\" in the top left.".toLabel()).pad(20f).row()
            } else {
                select(minOf(selectedIndex, religions.lastIndex))
            }
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

    private fun showMessage(message: String) {
        val popup = Popup(this)
        popup.add(message.toLabel()).pad(12f).row()
        popup.addCloseButton()
        popup.open()
    }
}

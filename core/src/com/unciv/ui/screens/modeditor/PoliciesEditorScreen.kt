package com.unciv.ui.screens.modeditor

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
import com.unciv.models.ruleset.PolicyBranch
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

/** 政策编辑器：左列表（分支→政策两级手风琴）| 右政策树（分支块 + row/column 网格 + requires 连线） */
class PoliciesEditorScreen(private val modFolder: FileHandle) : BaseScreen() {

    private val branches = ModEditorData.loadPolicies(modFolder)
    private val baseRuleset = ModEditorData.getBaseRuleset(modFolder)
    private val basePolicyNames = HashSet<String>()
    private val uniqueCatalog = UniqueCatalog.load()

    private var selectedBranch = -1
    private var selectedPolicy = -1   // 分支内政策索引；-1 = 选中的是分支本身
    private val expandedBranches = HashSet<Int>()
    private val expandedPolicies = HashSet<Pair<Int, Int>>()

    private val listTable = Table(BaseScreen.skin)
    private val treeTable = Table(BaseScreen.skin)
    private lateinit var treeScroll: AutoScrollPane
    private val statusLabel = "".toLabel(fontSize = 16)
    private val branchNameLabels = HashMap<Int, Label>()
    private val policyNameLabels = HashMap<Pair<Int, Int>, Label>()
    private val nodeByName = HashMap<String, Table>()
    private val lineImages = mutableListOf<com.badlogic.gdx.scenes.scene2d.ui.Image>()
    private var linesDirty = false
    private val civilopediaSavers = HashMap<Any, () -> Unit>()

    init {
        for (branch in baseRuleset.policyBranches.values)
            for (policy in branch.policies) basePolicyNames.add(policy.name)

        val root = Table(BaseScreen.skin)
        root.setFillParent(true)
        stage.addActor(root)

        val topBar = Table(BaseScreen.skin)
        val backButton = ("‹ " + "Back".tr()).toTextButton()
        backButton.onActivation { game.popScreen() }
        topBar.add(backButton).pad(8f)
        topBar.add(("Policies".tr() + " · Policies.json").toLabel(fontSize = 28))
            .padLeft(20f).expandX().left()
        val saveButton = "Save".toTextButton()
        saveButton.onActivation { save() }
        topBar.add(saveButton).pad(8f)
        topBar.add(statusLabel).pad(8f)
        root.add(topBar).fillX().row()

        // 左侧面板
        val leftPanel = Table(BaseScreen.skin)
        val buttonRow = Table(BaseScreen.skin)
        val addBranchButton = "+ New branch".toTextButton()
        addBranchButton.onActivation { addBranch() }
        buttonRow.add(addBranchButton).left().pad(6f)
        val copyBranchButton = "Copy branch from ruleset".toTextButton()
        copyBranchButton.onActivation { showCopyBranchPopup() }
        buttonRow.add(copyBranchButton).left().pad(6f)
        leftPanel.add(buttonRow).fillX().row()
        val listScroll = AutoScrollPane(listTable).apply {
            setOverscroll(false, false)
            setScrollingDisabled(false, false)
        }
        leftPanel.add(listScroll).expand().grow().row()

        // 右侧政策树
        treeScroll = AutoScrollPane(treeTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        // 每帧同步滚动位置（科技树同款：visualAmount 默认不更新 → 节点/连线随滚动移动）
        root.addActor(object : Actor() {
            override fun act(delta: Float) {
                super.act(delta)
                treeScroll.updateVisualScroll()
                // 连线延迟到布局完成后画（rebuildTree 里 pack 后嵌套 block/grid 坐标可能未就绪）
                if (linesDirty) {
                    linesDirty = false
                    drawConnectingLines()
                }
            }
        })

        val body = Table(BaseScreen.skin)
        body.add(leftPanel).width(stage.width / 2f).growY().pad(4f)
        body.addSeparatorVertical(ImageGetter.CHARCOAL, 2f)
        body.add(treeScroll).expand().grow().pad(4f)
        root.add(body).grow()

        rebuildLeft()
        if (branches.isEmpty()) {
            listTable.add("No policy branches. Click \"+ New branch\" above.".toLabel()).pad(20f).row()
        }
    }

    // ------------------------------------------------------------------
    // 选中与展开
    // ------------------------------------------------------------------

    private fun selectBranch(index: Int) {
        selectedBranch = index
        selectedPolicy = -1
        expandedBranches.add(index)
        rebuildLeft()
        rebuildTree()
    }

    private fun selectPolicy(branchIndex: Int, policyIndex: Int) {
        selectedBranch = branchIndex
        selectedPolicy = policyIndex
        expandedBranches.add(branchIndex)
        expandedPolicies.add(branchIndex to policyIndex)
        rebuildLeft()
        rebuildTree()
    }

    private fun toggleBranch(index: Int) {
        if (!expandedBranches.remove(index)) expandedBranches.add(index)
        rebuildLeft()
    }

    private fun togglePolicy(branchIndex: Int, policyIndex: Int) {
        val key = branchIndex to policyIndex
        if (!expandedPolicies.remove(key)) expandedPolicies.add(key)
        rebuildLeft()
    }

    // ------------------------------------------------------------------
    // 左列表
    // ------------------------------------------------------------------

    private fun rebuildLeft() {
        listTable.clear()
        branchNameLabels.clear()
        policyNameLabels.clear()

        for ((branchIndex, branch) in branches.withIndex()) {
            val isBranchSelected = selectedBranch == branchIndex && selectedPolicy == -1
            // 分支头
            val header = Table(BaseScreen.skin)
            header.background = if (isBranchSelected)
                rowBackground(Color(0.2f, 0.5f, 0.9f, 1f))
            else rowBackground()
            val arrow = (if (branchIndex in expandedBranches) "▾ " else "▸ ").toLabel(fontSize = 14)
            header.add(arrow).pad(4f)
            val nameLabel = listNameLabel(
                branch.name.ifBlank { "(unnamed)".tr() },
                maxWidth = stage.width * 0.5f - 140f, fontSize = 22)
            if (isBranchSelected) branchNameLabels[branchIndex] = nameLabel
            header.add(nameLabel).left().pad(4f).width(stage.width * 0.5f - 140f)
            header.add(branch.era.tr().toLabel(fontSize = 13, fontColor = Color(1f, 1f, 1f, 0.55f)))
                .left().pad(4f)
            header.add(("${branch.policies.size} " + "Policies".tr()).toLabel(fontSize = 13))
                .left().pad(4f).expandX()
            header.touchable = Touchable.enabled
            header.onActivation { toggleBranch(branchIndex) }
            listTable.add(header).fillX().pad(2f, 8f, 2f, 8f).row()

            if (branchIndex !in expandedBranches) continue

            // 分支属性框
            val branchForm = Table(BaseScreen.skin)
            branchForm.background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/BranchForm", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                Color(1f, 1f, 1f, 0.06f))
            addBranchForm(branchForm, branch, branchIndex)
            listTable.add(branchForm).growX().pad(2f, 20f, 2f, 8f).row()

            // 新增政策框
            val addPolicyRow = Table(BaseScreen.skin)
            val addPolicyButton = "+ New policy".toTextButton()
            addPolicyButton.onActivation { addPolicy(branchIndex) }
            addPolicyRow.add(addPolicyButton).left().pad(4f)
            listTable.add(addPolicyRow).growX().pad(2f, 20f, 2f, 8f).row()

            // 政策行
            for ((policyIndex, policy) in branch.policies.withIndex()) {
                val key = branchIndex to policyIndex
                val isPolicySelected = selectedBranch == branchIndex && selectedPolicy == policyIndex
                val policyRow = Table(BaseScreen.skin)
                policyRow.background = if (isPolicySelected)
                    rowBackground(Color(0.9f, 0.6f, 0.2f, 1f))
                else rowBackground()
                val pArrow = (if (key in expandedPolicies) "▾ " else "▸ ").toLabel(fontSize = 12)
                policyRow.add(pArrow).pad(4f).padLeft(16f)
                policyRow.add(ImageGetter.getImage("PolicyIcons/" + policy.name)).size(26f).pad(2f)
                val pName = listNameLabel(
                    policy.name.ifBlank { "(unnamed)".tr() },
                    maxWidth = stage.width * 0.5f - 170f, fontSize = 18)
                if (isPolicySelected) policyNameLabels[key] = pName
                policyRow.add(pName).left().pad(4f).width(stage.width * 0.5f - 170f)
                val rowText = policy.getIntText("row")
                val colText = policy.getIntText("column")
                policyRow.add(("${rowText.ifBlank { "?" }} · ${colText.ifBlank { "?" }}")
                    .toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.5f))).left().pad(4f).expandX()
                policyRow.touchable = Touchable.enabled
                policyRow.onActivation { togglePolicy(branchIndex, policyIndex) }
                listTable.add(policyRow).fillX().pad(2f, 20f, 2f, 8f).row()

                if (key in expandedPolicies) {
                    val policyForm = Table(BaseScreen.skin)
                    policyForm.background = BaseScreen.skinStrings.getUiBackground(
                        "ModEditor/PolicyForm", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                        Color(1f, 1f, 1f, 0.06f))
                    addPolicyForm(policyForm, branchIndex, policyIndex)
                    listTable.add(policyForm).growX().pad(2f, 36f, 2f, 8f).row()
                }
            }
        }
    }

    private fun addBranchForm(table: Table, branch: PolicyBranchData, branchIndex: Int) {
        // 名称
        val nameField = UncivTextField("", branch.name)
        nameField.setTextFieldListener { field, _ ->
            branch.name = field.text
            branchNameLabels[branchIndex]?.setText(field.text.ifBlank { "(unnamed)".tr() })
            rebuildTree()
        }
        addInlineRow(table, "Name", nameField)

        // 时代
        val eraBox = ModEditorSelectBox(ModEditorData.getEras(modFolder), branch.era.ifBlank { "" }, searchable = true)
        eraBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                branch.era = eraBox.selected?.value ?: ""
                rebuildLeft()
            }
        })
        addInlineRow(table, "Era", eraBox)

        // AI 优先级（胜利类型 → 数字映射）
        val prioritiesButton = buildPrioritiesButton(table, branch)
        addInlineRow(table, "Priorities", prioritiesButton)

        // 分支词条
        val branchUniquesTable = Table(BaseScreen.skin)
        rebuildUniquesTable(branchUniquesTable, branch.uniques) { rebuildTree() }
        val branchUniquesScroll = AutoScrollPane(branchUniquesTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        table.add("Uniques".tr().toLabel(fontSize = 20, fontColor = Color(0.55f, 0.85f, 1f, 1f)))
            .left().padTop(12f).padBottom(2f).padLeft(2f).colspan(2).row()
        table.add(branchUniquesScroll).growX().height(300f).left().colspan(2).pad(4f).row()
        val branchButtonRow = Table(BaseScreen.skin)
        addUniquesButtons(branchButtonRow, branch.uniques) { rebuildUniquesTable(branchUniquesTable, branch.uniques) { rebuildTree() } }
        table.add(branchButtonRow).growX().left().colspan(2).pad(4f, 6f, 4f, 6f).row()

        // 分支图标（政策树图标）：Images/PolicyBranchIcons/<name>.png
        val branchImage = ModEditorImageSection(
            modFolder = modFolder,
            subDirectory = "PolicyBranchIcons",
            fileName = { branch.name }
        )
        branchImage.addImageSection(table)

        // 百科
        addCivilopediaSection(table, branch, branch.raw)

        // 删除分支
        val deleteButton = "Delete branch".toTextButton()
        deleteButton.onActivation { confirmDeleteBranch(branchIndex) }
        table.add(deleteButton).left().pad(4f).colspan(2).row()
    }

    private fun addPolicyForm(table: Table, branchIndex: Int, policyIndex: Int) {
        val branch = branches[branchIndex]
        val policy = branch.policies[policyIndex]
        val key = branchIndex to policyIndex

        val nameField = UncivTextField("", policy.name)
        nameField.setTextFieldListener { field, _ ->
            policy.name = field.text
            policyNameLabels[key]?.setText(field.text.ifBlank { "(unnamed)".tr() })
            rebuildTree()
        }
        addInlineRow(table, "Name", nameField)

        val rowField = inlineNumberField(policy.getIntText("row")) { v ->
            if (v == null) policy.raw.remove("row") else policy.raw["row"] = v
            rebuildTree()
        }
        addInlineRow(table, "Row", rowField)

        val columnField = inlineNumberField(policy.getIntText("column")) { v ->
            if (v == null) policy.raw.remove("column") else policy.raw["column"] = v
            rebuildTree()
        }
        addInlineRow(table, "Column", columnField)

        // 前置政策
        addRequiresChipsRow(table, branchIndex, policyIndex)

        // 词条
        val uniquesTable = Table(BaseScreen.skin)
        rebuildUniquesTable(uniquesTable, policy.uniques) { rebuildTree() }
        val policyUniquesScroll = AutoScrollPane(uniquesTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        table.add("Uniques".tr().toLabel(fontSize = 20, fontColor = Color(0.55f, 0.85f, 1f, 1f)))
            .left().padTop(12f).padBottom(2f).padLeft(2f).colspan(2).row()
        table.add(policyUniquesScroll).growX().height(300f).left().colspan(2).pad(4f).row()
        val policyButtonRow = Table(BaseScreen.skin)
        addUniquesButtons(policyButtonRow, policy.uniques) { rebuildUniquesTable(uniquesTable, policy.uniques) { rebuildTree() } }
        table.add(policyButtonRow).growX().left().colspan(2).pad(4f, 6f, 4f, 6f).row()

        // 百科
        addCivilopediaSection(table, policy, policy.raw)

        // 政策图标：Images/PolicyIcons/<name>.png
        val policyImage = ModEditorImageSection(
            modFolder = modFolder,
            subDirectory = "PolicyIcons",
            fileName = { policy.name },
            preCheck = {
                if (policy.name.isBlank()) "Enter a policy name first, then choose an image." else null
            }
        )
        policyImage.addImageSection(table)

        val deleteButton = "Delete policy".toTextButton()
        deleteButton.onActivation { confirmDeletePolicy(branchIndex, policyIndex) }
        table.add(deleteButton).left().pad(4f).colspan(2).row()
    }

    private fun buildPrioritiesButton(table: Table, branch: PolicyBranchData): Table {
        val button = Table(BaseScreen.skin)
        fun refreshLabel() {
            button.clear()
            val map = branch.raw["priorities"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val text = if (map.isEmpty()) "Edit".tr()
            else map.entries.joinToString(", ") { "${it.key}: ${it.value}" }
            button.background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/Priorities", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                Color(0.15f, 0.4f, 0.7f, 0.8f))
            button.add(text.toLabel(fontSize = 13)).left().pad(4f, 8f, 4f, 8f)
        }
        refreshLabel()
        button.touchable = Touchable.enabled
        button.onActivation { showPrioritiesPopup(branch) { refreshLabel() } }
        return button
    }

    private fun showPrioritiesPopup(branch: PolicyBranchData, onChanged: () -> Unit) {
        val victoryTypes = ModEditorData.getVictoryTypes(modFolder)
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        popup.add(("Priorities".tr() + " · " + branch.name).toLabel(fontSize = 20)).pad(8f).row()
        val listTable = Table(BaseScreen.skin)
        val listScroll = AutoScrollPane(listTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        popup.add(listScroll).grow().width(520f).height(320f).pad(6f).row()

        fun refreshList() {
            listTable.clear()
            val map = (branch.raw["priorities"] as? Map<*, *>)
                ?.mapKeys { it.key.toString() }?.mapValues { (it.value as? Number)?.toInt() ?: 0 }
                ?.toMutableMap() ?: mutableMapOf()
            for ((victory, value) in map.toSortedMap()) {
                val row = Table(BaseScreen.skin)
                val victoryBox = ModEditorSelectBox(victoryTypes, victory, searchable = true)
                victoryBox.addListener(object : ChangeListener() {
                    override fun changed(event: ChangeEvent?, actor: Actor?) {
                        val old = victoryBox.selected?.value ?: return
                        val oldValue = map.remove(victory) ?: 0
                        map[old] = oldValue
                        branch.raw["priorities"] = map
                        refreshList()
                    }
                })
                row.add(victoryBox).width(240f).left().pad(4f)
                val valueField = numberField(value.toString()) { v ->
                    map[victory] = v ?: 0
                    if (map.values.all { it == 0 }) branch.raw.remove("priorities")
                    else branch.raw["priorities"] = map
                    refreshList()
                }
                row.add(valueField).width(80f).left().pad(4f)
                val removeButton = "×".toTextButton()
                removeButton.onActivation {
                    map.remove(victory)
                    if (map.isEmpty()) branch.raw.remove("priorities")
                    else branch.raw["priorities"] = map
                    refreshList()
                }
                row.add(removeButton).left().pad(4f)
                listTable.add(row).growX().left().row()
            }
            val addButton = "+ Add".toTextButton()
            addButton.onActivation {
                val unused = victoryTypes.firstOrNull { it !in map }
                if (unused != null) map[unused] = 0
                branch.raw["priorities"] = map
                refreshList()
            }
            listTable.add(addButton).left().pad(6f).row()
        }
        refreshList()
        popup.addButton("Save") {
            popup.close()
            onChanged()
        }
        popup.addCloseButton()
        popup.open()
    }

    private fun addRequiresChipsRow(table: Table, branchIndex: Int, policyIndex: Int) {
        val policy = branches[branchIndex].policies[policyIndex]
        val row = Table(BaseScreen.skin)
        row.add("Requires".tr().toLabel(fontSize = 14)).left().pad(3f).width(90f).top()
        val chipsTable = Table(BaseScreen.skin)

        fun refreshChips() {
            chipsTable.clear()
            val maxWidth = formAvailableWidth(stage.width, leftFraction = 0.5f, extraDeduction = 90f)
            var currentRow = Table(BaseScreen.skin)
            var rowWidth = 0f
            for (value in policy.requires) {
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
                    policy.requires.remove(value)
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
        addButton.onActivation { showAddRequiresPopup(branchIndex, policyIndex) { refreshChips() } }
        val right = Table(BaseScreen.skin)
        right.add(chipsTable).growX().left().row()
        right.add(addButton).left().padTop(2f)
        row.add(right).growX().left().pad(3f)
        table.add(row).growX().left().colspan(2).row()
    }

    private fun allPolicyNames(): List<String> {
        val names = LinkedHashSet<String>()
        for (branch in baseRuleset.policyBranches.values)
            for (policy in branch.policies) names.add(policy.name)
        for (branch in branches)
            for (policy in branch.policies) names.add(policy.name)
        return names.toList()
    }

    private fun showAddRequiresPopup(branchIndex: Int, policyIndex: Int, onChanged: () -> Unit) {
        val policy = branches[branchIndex].policies[policyIndex]
        val options = allPolicyNames().filter { it !in policy.requires }
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        popup.add("Requires".tr().toLabel(fontSize = 20)).pad(8f).row()
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
            val q = query.trim().lowercase()
            var shown = 0
            for (item in options) {
                if (q.isNotEmpty() && !item.lowercase().contains(q)) continue
                val row = Table(BaseScreen.skin)
                row.background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/SearchRow", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                    BaseScreen.skinStrings.skinConfig.baseColor)
                val itemLbl = item.toLabel(fontSize = 15)
                itemLbl.wrap = true
                row.add(itemLbl).growX().left().pad(6f, 8f, 6f, 8f)
                row.touchable = Touchable.enabled
                row.onActivation {
                    policy.requires.add(item)
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

    // ------------------------------------------------------------------
    // 词条（分支/政策共用）
    // ------------------------------------------------------------------

    private fun rebuildUniquesTable(uniquesTable: Table, uniques: MutableList<String>, onTreeChange: () -> Unit) {
        uniquesTable.clear()
        if (uniques.isEmpty()) {
            uniquesTable.add("(no uniques)".toLabel(fontSize = 16, fontColor = Color(1f, 1f, 1f, 0.4f)))
                .left().pad(4f).row()
        }
        for ((index, rawString) in uniques.withIndex()) {
            val parsed = uniqueCatalog.parseRaw(rawString)
            if (parsed != null) {
                lateinit var editor: UniqueInlineEditor
                editor = UniqueInlineEditor(
                    screen = this, modFolder = modFolder, catalog = uniqueCatalog,
                    unique = parsed.unique, values = parsed.values, conditions = parsed.conditions,
                    onValueChange = { uniques[index] = editor.buildRaw() },
                    onStructureChange = {
                        uniques[index] = editor.buildRaw()
                        rebuildUniquesTable(uniquesTable, uniques, onTreeChange)
                        onTreeChange()
                    },
                    onDuplicate = {
                        val copyValues = parsed.values.toMutableMap()
                        val copyConditions = parsed.conditions
                            .map { (c, v) -> c to v.toMutableMap() }.toMutableList()
                        uniques.add(index + 1,
                            uniqueCatalog.buildRawString(parsed.unique, copyValues, copyConditions))
                        rebuildUniquesTable(uniquesTable, uniques, onTreeChange)
                        onTreeChange()
                    },
                    onDelete = {
                        uniques.removeAt(index)
                        rebuildUniquesTable(uniquesTable, uniques, onTreeChange)
                        onTreeChange()
                    }
                )
                uniquesTable.add(editor).growX().left().pad(3f, 8f, 3f, 8f).row()
                uniquesTable.add(uniqueSeparatorLine()).growX().height(1f).pad(2f, 8f, 2f, 8f).row()
            } else {
                val row = Table(BaseScreen.skin)
                val label = Label(rawString, BaseScreen.skin).apply {
                    setFontScale(16f / com.unciv.ui.components.fonts.Fonts.ORIGINAL_FONT_SIZE)
                    setAlignment(Align.left)
                    setColor(Color(1f, 1f, 1f, 0.8f))
                    wrap = true
                }
                row.add(label).growX().minWidth(220f).left().pad(4f)
                val editButton = "Edit".toTextButton()
                editButton.onActivation { showRawUniqueEditor(uniques, index) { rebuildUniquesTable(uniquesTable, uniques, onTreeChange) } }
                row.add(editButton).pad(4f)
                val removeButton = "×".toTextButton()
                removeButton.onActivation {
                    uniques.removeAt(index)
                    rebuildUniquesTable(uniquesTable, uniques, onTreeChange)
                }
                row.add(removeButton).pad(4f)
                uniquesTable.add(row).growX().left().row()
            }
        }
    }

    /** 词条按钮行（外部）：添加 + 原文编辑（不随内容滚动） */
    private fun addUniquesButtons(buttonRow: Table, uniques: MutableList<String>, onChanged: () -> Unit) {
        val addButton = "+ Add unique".toTextButton()
        addButton.onActivation {
            game.pushScreen(UniquePickerScreen(
                onPick = { unique ->
                    val values = unique.params
                        .filter { it.default.isNotBlank() }
                        .associate { it.id to it.default }.toMutableMap()
                    uniques.add(uniqueCatalog.buildRawString(unique, values, emptyList()))
                    onChanged()
                },
                onRawPicked = { text ->
                    uniques.add(text)
                    onChanged()
                }
            ))
        }
        buttonRow.add(addButton).left().pad(6f)
        addRawEditUniquesButton(this, buttonRow, getUniques = { uniques }) { onChanged() }
        buttonRow.row()
    }

    private fun showRawUniqueEditor(uniques: MutableList<String>, index: Int?, onDone: () -> Unit) {
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        popup.add("Unique (raw mode)".toLabel(fontSize = 22)).pad(10f).row()
        popup.add("No quotes needed - they are added automatically when saving".tr().toLabel(
            fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f))).pad(0f, 10f, 4f, 10f).row()
        val textArea = TextArea(index?.let { uniques.getOrNull(it) } ?: "", BaseScreen.skin)
        popup.add(textArea).width(560f).height(160f).pad(6f).row()
        popup.addButton("Save") {
            val text = textArea.text.replace('\n', ' ').replace('\r', ' ')
                .replace(Regex("\\s{2,}"), " ").trim()
            if (text.isNotEmpty()) {
                if (index == null) uniques.add(text)
                else if (index < uniques.size) uniques[index] = text
            } else if (index != null && index < uniques.size) {
                uniques.removeAt(index)
            }
            popup.close()
            onDone()
        }
        popup.addCloseButton()
        popup.open()
    }

    // ------------------------------------------------------------------
    // 百科文本
    // ------------------------------------------------------------------

    private fun addCivilopediaSection(table: Table, key: Any, raw: LinkedHashMap<String, Any?>) {
        val editor = CivilopediaTextEditor(
            screen = this,
            getRaw = { raw["civilopediaText"] },
            setRaw = { raw["civilopediaText"] = it }
        )
        editor.addTo(table, "civilopediaText")
        // 保存时写回（重建表单时按 key 覆盖，无重复）
        civilopediaSavers[key] = {
            val entries = editor.buildEntries()
            if (entries == null) raw.remove("civilopediaText") else raw["civilopediaText"] = entries
        }
    }

    // ------------------------------------------------------------------
    // 新建/删除
    // ------------------------------------------------------------------

    private fun showCopyBranchPopup() {
        val baseBranches = ModEditorData.loadBasePolicyBranches(modFolder)
        if (baseBranches.isEmpty()) { showMessage("No policy branches found in the base ruleset"); return }
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        popup.add("Copy branch from ruleset".tr().toLabel(fontSize = 20)).pad(8f).row()
        popup.add("A branch with the same name replaces the base one in-game".tr()
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
            for (base in baseBranches) {
                if (q.isNotEmpty() && !base.name.lowercase().contains(q) &&
                    !base.name.tr().lowercase().contains(q) &&
                    !base.era.lowercase().contains(q)) continue
                val row = Table(BaseScreen.skin)
                row.background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/SearchRow", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                    BaseScreen.skinStrings.skinConfig.baseColor)
                val textTable = Table(BaseScreen.skin)
                val branchNameLbl = base.name.toLabel(fontSize = 15)
                branchNameLbl.wrap = true
                textTable.add(branchNameLbl).growX().left().pad(2f, 8f, 0f, 8f).row()
                textTable.add((base.era.tr() + " · " + base.policies.size + " " + "Policies".tr())
                    .toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.55f)))
                    .growX().left().pad(0f, 8f, 4f, 8f)
                row.add(textTable).growX().left().pad(4f)
                row.touchable = Touchable.enabled
                row.onActivation {
                    val copy = ModEditorData.deepCopyPolicyBranch(base)
                    branches.add(copy)
                    popup.close()
                    selectBranch(branches.lastIndex)
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

    private fun showMessage(message: String) {
        val popup = Popup(this)
        popup.add(message.tr().toLabel()).pad(12f).row()
        popup.addCloseButton()
        popup.open()
    }

    private fun addBranch() {
        val branch = PolicyBranchData()
        branch.name = nextBranchName()
        val defaultEra = ModEditorData.getEras(modFolder).firstOrNull() ?: ""
        if (defaultEra.isNotBlank()) branch.era = defaultEra
        branches.add(branch)
        selectBranch(branches.lastIndex)
    }

    private fun nextBranchName(): String {
        val existing = branches.map { it.name }.toSet()
        var i = 1
        while (("New branch" + if (i == 1) "" else " $i") in existing) i++
        return "New branch" + if (i == 1) "" else " $i"
    }

    private fun addPolicy(branchIndex: Int) {
        val branch = branches[branchIndex]
        val policy = PolicyData()
        policy.name = nextPolicyName(branch)
        policy.raw["row"] = 1
        val maxColumn = branch.policies.maxOfOrNull { (it.raw["column"] as? Number)?.toInt() ?: 0 } ?: 0
        policy.raw["column"] = maxColumn + 1
        branch.policies.add(policy)
        selectPolicy(branchIndex, branch.policies.lastIndex)
    }

    private fun nextPolicyName(branch: PolicyBranchData): String {
        val existing = branch.policies.map { it.name }.toSet()
        var i = 1
        while (("New policy" + if (i == 1) "" else " $i") in existing) i++
        return "New policy" + if (i == 1) "" else " $i"
    }

    private fun confirmDeleteBranch(branchIndex: Int) {
        val branch = branches[branchIndex]
        val popup = Popup(this)
        popup.add("Are you sure you want to delete [name]?".tr().replace("[name]", branch.name).toLabel(fontSize = 20)).pad(12f).row()
        popup.addButton("Delete") {
            branches.removeAt(branchIndex)
            popup.close()
            if (branches.isEmpty()) {
                selectedBranch = -1
                selectedPolicy = -1
                expandedBranches.clear()
                expandedPolicies.clear()
                rebuildLeft()
                rebuildTree()
            } else {
                selectBranch(minOf(branchIndex, branches.lastIndex))
            }
        }
        popup.addCloseButton()
        popup.open()
    }

    private fun confirmDeletePolicy(branchIndex: Int, policyIndex: Int) {
        val policy = branches[branchIndex].policies[policyIndex]
        val popup = Popup(this)
        popup.add("Are you sure you want to delete [name]?".tr().replace("[name]", policy.name).toLabel(fontSize = 20)).pad(12f).row()
        popup.addButton("Delete") {
            branches[branchIndex].policies.removeAt(policyIndex)
            popup.close()
            selectedPolicy = -1
            expandedPolicies.remove(branchIndex to policyIndex)
            rebuildLeft()
            rebuildTree()
        }
        popup.addCloseButton()
        popup.open()
    }

    // ------------------------------------------------------------------
    // 政策树
    // ------------------------------------------------------------------

    private fun rebuildTree() {
        treeTable.clear()
        nodeByName.clear()

        for ((branchIndex, branch) in branches.withIndex()) {
            val block = Table(BaseScreen.skin)
            // 分支头
            val header = Table(BaseScreen.skin)
            header.background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/PolicyBranchHeader", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                if (selectedBranch == branchIndex && selectedPolicy == -1) Color(0.9f, 0.6f, 0.2f, 1f)
                else Color(0.12f, 0.3f, 0.5f, 1f))
            val treeBranchName = branch.name.ifBlank { "(unnamed)".tr() }.toLabel(fontSize = 20,
                fontColor = Color(1f, 1f, 1f, 1f))
            treeBranchName.wrap = true
            header.add(treeBranchName).left().growX().minWidth(60f).pad(6f, 10f, 6f, 6f)
            header.add(branch.era.tr().toLabel(fontSize = 13, fontColor = Color(1f, 1f, 1f, 0.6f)))
                .left().pad(6f)
            header.add(("${branch.policies.size} " + "Policies".tr()).toLabel(fontSize = 13,
                fontColor = Color(1f, 1f, 1f, 0.6f))).left().pad(6f).expandX()
            header.touchable = Touchable.enabled
            header.onActivation { selectBranch(branchIndex) }
            block.add(header).growX().row()

            // 网格：政策按 (column, row) 放置
            val maxCol = max(5, branch.policies.maxOfOrNull { (it.raw["column"] as? Number)?.toInt() ?: 0 } ?: 1)
            val maxRow = max(1, branch.policies.maxOfOrNull { (it.raw["row"] as? Number)?.toInt() ?: 0 } ?: 1)
            val grid = Table(BaseScreen.skin)
            val iconSize = 72f
            for (row in 1..maxRow) {
                for (col in 1..maxCol) {
                    val policy = branch.policies.firstOrNull {
                        (it.raw["row"] as? Number)?.toInt() == row && (it.raw["column"] as? Number)?.toInt() == col
                    }
                    if (policy == null) {
                        grid.add().size(iconSize).pad(3f)
                    } else {
                        val button = buildPolicyButton(branchIndex, policy)
                        nodeByName[policy.name] = button
                        grid.add(button).size(iconSize).pad(3f)
                    }
                }
                grid.row()
            }
            block.add(grid).growX().pad(6f).row()
            treeTable.add(block).growX().pad(4f).row()
        }

        treeTable.pack()
        treeScroll.updateVisualScroll()
        linesDirty = true  // 延迟到 act 钩子里画（布局完成后坐标才正确）
    }

    private fun buildPolicyButton(branchIndex: Int, policy: PolicyData): Table {
        val policyIndex = branches[branchIndex].policies.indexOf(policy)
        val isSelected = selectedBranch == branchIndex && selectedPolicy == policyIndex
        val isBase = policy.name in basePolicyNames
        val tint = when {
            isSelected -> Color(0.9f, 0.6f, 0.2f, 1f)
            isBase -> Color(0.35f, 0.38f, 0.42f, 1f)
            else -> Color(0.2f, 0.45f, 0.85f, 1f)
        }
        val button = Table(BaseScreen.skin)
        button.background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/PolicyButton", BaseScreen.skinStrings.roundedEdgeRectangleShape, tint)
        val nameLabel = policy.name.ifBlank { "(unnamed)".tr() }.toLabel(fontSize = 11)
        nameLabel.wrap = true
        button.add(nameLabel).grow().center().pad(3f)
        button.touchable = Touchable.enabled
        button.onActivation { selectPolicy(branchIndex, policyIndex) }
        return button
    }

    private fun drawConnectingLines() {
        // 清除旧线
        for (img in lineImages) img.remove()
        lineImages.clear()

        // requires 连线：前置政策底部中心 → 当前政策顶部中心（三段式），树表局部坐标
        val tmp = com.badlogic.gdx.math.Vector2()
        val lineColor = Color(1f, 1f, 1f, 0.45f)
        fun hseg(x1: Float, y1: Float, x2: Float) {
            val img = ImageGetter.getWhiteDot()
            img.color = lineColor
            img.setBounds(minOf(x1, x2), y1 - 1f, kotlin.math.abs(x2 - x1), 2f)
            treeTable.addActor(img)
            lineImages.add(img)
        }
        fun vseg(x: Float, y1: Float, y2: Float) {
            val img = ImageGetter.getWhiteDot()
            img.color = lineColor
            img.setBounds(x - 1f, minOf(y1, y2), 2f, kotlin.math.abs(y2 - y1))
            treeTable.addActor(img)
            lineImages.add(img)
        }
        for (branch in branches) {
            for (policy in branch.policies) {
                val node = nodeByName[policy.name] ?: continue
                for (prereq in policy.requires) {
                    val from = nodeByName[prereq] ?: continue
                    // 前置政策底部中心（局部 (width/2, 0)）→ treeTable 局部坐标
                    tmp.set(from.width / 2f, 0f)
                    from.localToAscendantCoordinates(treeTable, tmp)
                    val sx = tmp.x; val sy = tmp.y
                    // 当前政策顶部中心（局部 (width/2, height)）→ treeTable 局部坐标
                    tmp.set(node.width / 2f, node.height)
                    node.localToAscendantCoordinates(treeTable, tmp)
                    val ex = tmp.x; val ey = tmp.y
                    val midY = (sy + ey) / 2f
                    vseg(sx, sy, midY)   // 前置底部 → 中点（竖）
                    hseg(sx, midY, ex)   // 中点 → 当前 x（横）
                    vseg(ex, midY, ey)   // 中点 → 当前顶部（竖）
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    private fun save() {
        // 百科文本写回（所有展开过的表单）
        for (saver in civilopediaSavers.values) saver()
        val problems = ModEditorData.validatePolicies(modFolder, branches)
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
        ModEditorData.savePolicies(modFolder, branches)
        val gameProblems = ModEditorData.filterGameProblems(
            ModEditorData.runGameValidation(modFolder), "Policies.json")
        val errors = gameProblems.filter { it.second }
        if (errors.isNotEmpty()) {
            ModEditorData.rollbackFile(modFolder, "Policies.json")
            statusLabel.setText("Save failed".tr())
            showGameProblemsPopup(gameProblems, saved = false)
            return
        }
        statusLabel.setText("Saved".tr())
        if (gameProblems.isNotEmpty()) showGameProblemsPopup(gameProblems, saved = true)
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
    // 样式辅助
    // ------------------------------------------------------------------

    private fun addInlineRow(table: Table, labelKey: String, widget: Actor) {
        val row = Table(BaseScreen.skin)
        row.add(labelKey.toLabel(fontSize = 14)).left().pad(3f).width(90f)
        row.add(widget).growX().minWidth(140f).pad(3f)
        table.add(row).growX().left().colspan(2).row()
    }

    private fun inlineNumberField(value: String, onChange: (Int?) -> Unit): UncivTextField {
        val field = UncivTextField("", value)
        field.textFieldFilter = object : TextField.TextFieldFilter {
            override fun acceptChar(textField: TextField, c: Char): Boolean = c in '0'..'9'
        }
        field.setTextFieldListener { f, _ -> onChange(f.text.trim().toIntOrNull()) }
        return field
    }

    private fun numberField(value: String, onChange: (Int?) -> Unit): UncivTextField {
        val field = UncivTextField("", value)
        field.textFieldFilter = object : TextField.TextFieldFilter {
            override fun acceptChar(textField: TextField, c: Char): Boolean = c in '0'..'9' || c == '-'
        }
        field.setTextFieldListener { f, _ -> onChange(f.text.trim().toIntOrNull()) }
        return field
    }

    private fun rowBackground(tint: Color = BaseScreen.skinStrings.skinConfig.baseColor) =
        BaseScreen.skinStrings.getUiBackground(
            "ModEditor/PolicyRow", BaseScreen.skinStrings.roundedEdgeRectangleShape, tint)
}

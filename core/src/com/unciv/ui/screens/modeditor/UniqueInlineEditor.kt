package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextArea
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.Layout
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
 * 词条行内编辑器：英文模板中 [参数] 直接渲染为输入控件（下拉/数字框/文本框）。
 * 布局（flow）：效果模板 + 所有条件（<范围>）连续排列，一行放不下自动换行；
 * 按钮组（添加条件/复制/×）在同一行末尾右对齐。
 * 所有修改即时通过 onValueChange 同步回父级的原始字符串（不重建 UI，保住输入焦点）。
 */
class UniqueInlineEditor(
    private val screen: BaseScreen,
    private val modFolder: FileHandle,
    private val catalog: UniqueCatalog,
    val unique: CatalogUnique,
    private val values: MutableMap<String, String>,
    val conditions: MutableList<Pair<CatalogCondition, MutableMap<String, String>>>,
    private val onValueChange: () -> Unit,
    private val onStructureChange: () -> Unit,
    private val onDuplicate: () -> Unit,
    private val onDelete: () -> Unit
) : Table(BaseScreen.skin) {

    init {
        // 词条卡片：浅色背景框（每个词条一个独立框，视觉分隔）
        background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/UniqueCard", BaseScreen.skinStrings.roundedEdgeRectangleShape,
            com.badlogic.gdx.graphics.Color(1f, 1f, 1f, 0.06f))
        defaults().pad(2f)

        // 效果模板元素（literal + 内嵌参数控件）
        val effectItems = buildTemplateParts(unique.key, unique.params, values)
        // 条件 chips（<范围>，含 ×）
        val chipItems = conditions.map { (condition, conditionValues) ->
            buildConditionChip(condition, conditionValues)
        }
        // 按钮组（添加条件/复制/×）
        val buttons = buildButtons()

        // 换行阈值下限保护（窗口很窄时不至于逐字符换行）；内嵌控件用保守估算
        val maxWidth = formAvailableWidth(screen.stage.width, extraDeduction = 100f)
        val rows = flowGroup(effectItems + chipItems + listOf(buttons), maxWidth)

        for (rowItems in rows) {
            val row = Table(BaseScreen.skin)
            val hasButtons = rowItems.any { it === buttons }
            for (item in rowItems) {
                if (item === buttons) {
                    if (hasButtons && rowItems.size > 1) row.add().expandX() // 按钮组贴右
                    row.add(item).right()
                } else if (item is Layout && item.prefWidth > maxWidth) {
                    // 超长元素独立成行时约束宽度上限（可压缩）；文本按词换行（仅显示，不影响原始字符串）
                    // ⚠️ 必须 growX() + minWidth：wrap 的 Label prefWidth 为 0，只设 maxWidth 会被压到逐字符换行
                    if (item is Label) item.wrap = true
                    row.add(item).growX().minWidth(200f).maxWidth(maxWidth).left().pad(2f)
                } else {
                    row.add(item).left().pad(2f)
                }
            }
            add(row).growX().left().row()
        }
    }

    /** 根据当前值重建原始词条字符串 */
    fun buildRaw(): String = catalog.buildRawString(unique, values, conditions)

    // ------------------------------------------------------------------
    // flow 工具
    // ------------------------------------------------------------------

    private fun flowGroup(items: List<Actor>, maxWidth: Float): List<List<Actor>> {
        val rows = mutableListOf<MutableList<Actor>>()
        var current = mutableListOf<Actor>()
        var width = 0f
        for (item in items) {
            val w = (if (item is Layout) item.prefWidth else item.width) + 8f
            if (w > maxWidth) {
                // 单个元素超宽：独立成行，渲染时约束宽度换行
                if (current.isNotEmpty()) { rows.add(current); current = mutableListOf() }
                rows.add(mutableListOf(item))
                width = 0f
                continue
            }
            if (current.isNotEmpty() && width + w > maxWidth) {
                rows.add(current)
                current = mutableListOf()
                width = 0f
            }
            current.add(item)
            width += w
        }
        if (current.isNotEmpty()) rows.add(current)
        return rows
    }

    // ------------------------------------------------------------------
    // 模板渲染：把 [id] 替换成内嵌控件，返回元素列表（不直接布局）
    // ------------------------------------------------------------------

    private fun buildTemplateParts(
        template: String,
        params: List<CatalogParam>,
        target: MutableMap<String, String>
    ): List<Actor> {
        val parts = mutableListOf<Actor>()
        val paramPattern = Regex("\\[([A-Za-z]+(?:\\/[A-Za-z]+)*)\\]")
        val paramIds = paramPattern.findAll(template).map { it.groupValues[1] }.toList()
        val literalParts = template.split(paramPattern)
        for ((index, part) in literalParts.withIndex()) {
            // 英文原词条文本：必须用不翻译的 Label（toLabel 会命中翻译表显示中文）
            // 默认不换行；flow 渲染时只有超长文本才启用 wrap（见 init 渲染分支）
            if (part.isNotEmpty()) parts.add(Label(part, BaseScreen.skin).apply {
                setFontScale(17f / Fonts.ORIGINAL_FONT_SIZE)
                setAlignment(Align.left)
            })
            if (index < literalParts.lastIndex) {
                val param = params.firstOrNull { it.id == paramIds[index] }
                val current = target[paramIds[index]] ?: param?.default ?: ""
                val widget = createParamWidget(param, current, target)
                attachValueListener(widget, paramIds[index], target)
                parts.add(widget)
            }
        }
        return parts
    }

    private fun attachValueListener(widget: Actor, id: String, target: MutableMap<String, String>) {
        when (widget) {
            is TextField -> widget.setTextFieldListener { field, _ ->
                val value = field.text.trim()
                if (value.isBlank()) target.remove(id) else target[id] = value
                onValueChange()
            }
            is ModEditorSelectBox, is ModEditorComboBox -> widget.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    val value = readParamValue(widget)
                    if (value.isBlank()) target.remove(id) else target[id] = value
                    onValueChange()
                }
            })
            // 其他控件（stats 按钮等）自己管理值更新
        }
    }

    private fun createParamWidget(
        param: CatalogParam?,
        current: String,
        target: MutableMap<String, String>
    ): Actor {
        if (param == null) return UncivTextField("", current)
        return when (param.type) {
            "number" -> UncivTextField("", current).apply {
                textFieldFilter = object : TextField.TextFieldFilter {
                    override fun acceptChar(textField: TextField, c: Char): Boolean {
                        if (c in '0'..'9') return true
                        // 正/负号：只要还没输过符号就允许（不限位置，值以符号开头即可）
                        if ((c == '-' || c == '+') &&
                            !textField.text.contains('-') && !textField.text.contains('+')) return true
                        if (c == '.' && !textField.text.contains('.')) return true
                        return false
                    }
                }
            }
            "choice" -> makeSearchableBox(
                if (param.options.isNotEmpty()) param.options else listOf("(None)"), current)
            "list:terrain" -> makeSearchableBox(ModEditorData.getTerrains(modFolder), current)
            "list:terrainFilter" -> makeSearchableBox(
                ModEditorData.getTerrains(modFolder) + ModEditorData.getResources(modFolder) +
                    listOf("All", "all", "Water", "Land", "Coastal", "River", "Open terrain",
                        "Rough terrain", "Fresh Water", "Impassable", "Friendly", "Foreign",
                        "Enemy", "Unowned", "Natural Wonder", "Terrain Feature", "Featureless"),
                current)
            "list:tileFilter" -> makeSearchableBox(
                ModEditorData.getTerrains(modFolder) + ModEditorData.getImprovements(modFolder) +
                    listOf("unimproved", "improved", "pillaged", "worked"), current)
            "list:simpleTerrain" -> makeSearchableBox(
                ModEditorData.getTerrains(modFolder) + listOf("Land", "Water", "Elevated"), current)
            "list:unitFilter" -> makeSearchableBox(
                ModEditorData.getUnits(modFolder) + ModEditorData.getUnitTypes(modFolder) +
                    listOf("Land", "Water", "Air", "Military", "Civilian", "Melee", "Ranged",
                        "Nuclear Weapon", "Great Person", "Embarked", "All", "all"), current)
            "list:buildingFilter" -> makeSearchableBox(
                ModEditorData.getBuildings(modFolder) +
                    listOf("All", "all", "Building", "Wonder", "National Wonder", "World Wonder",
                        "Culture", "Gold", "Science", "Production", "Food", "Happiness", "Faith"),
                current)
            "list:improvementFilter" -> makeSearchableBox(
                ModEditorData.getImprovements(modFolder) +
                    listOf("All", "all", "Improvement", "Great Improvement", "All Road"), current)
            "list:policyFilter" -> makeSearchableBox(
                ModEditorData.getPolicies(modFolder) + listOf("All", "all"), current)
            "list:techFilter" -> makeSearchableBox(
                ModEditorData.getTechs(modFolder) + ModEditorData.getEras(modFolder) +
                    listOf("All", "all"), current)
            "list:countable" -> makeEditableBox(
                // 官方 countable 合法值（Uniques.md 2026-08-19）：直接值 + 类型模板（带 [参数] 的选中后弹参数窗）
                listOf(
                    "turns", "year", "Cities", "Units", "Era number",
                    "Completed Policy branches",
                    "[Stat/Resource] Per Turn",
                    "[cityFilter] Cities",
                    "[mapUnitFilter] Units",
                    "Carried [mapUnitFilter] units",
                    "[buildingFilter] Buildings",
                    "[buildingFilter] Buildings by [civFilter] Civilizations",
                    "[cityFilter] Cities of [civFilter] Civilizations",
                    "Adopted [policyFilter] Policies",
                    "Adopted [policyFilter] Policies by [civFilter] Civilizations",
                    "Researched [techFilter] Technologies",
                    "Remaining [civFilter] Civilizations",
                    "Worked [tileFilter] Tiles in this city",
                    "Worked [tileFilter] Tiles",
                    "Owned [tileFilter] Tiles",
                    "[tileFilter] Tiles",
                    "[resourceFilter] resource of [civFilter] Civilizations",
                    "Speed modifier for [stat]"
                ) +
                listOf("Gold", "Science", "Production", "Food", "Happiness", "Culture", "Faith") +
                ModEditorData.getResources(modFolder), current)
            "list:stockpiledResource" -> makeSearchableBox(
                ModEditorData.getStockpiledResources(modFolder), current)
            "list:stockpile" -> makeSearchableBox(
                ModEditorData.getStockpiledResources(modFolder) +
                    listOf("Gold", "Science", "Production", "Food", "Happiness", "Culture", "Faith",
                        "Stored Food", "Golden Age points"), current)
            "list:regionType" -> makeSearchableBox(ModEditorData.getRegionTypes(modFolder), current)
            "list:improvement" -> makeSearchableBox(ModEditorData.getImprovements(modFolder), current)
            "list:unit" -> makeSearchableBox(ModEditorData.getUnits(modFolder), current)
            "list:greatPerson" -> makeSearchableBox(ModEditorData.getGreatPeople(modFolder), current)
            "list:resource" -> makeSearchableBox(ModEditorData.getResources(modFolder), current)
            "list:promotion" -> makeSearchableBox(ModEditorData.getPromotions(modFolder), current)
            "list:building" -> makeSearchableBox(ModEditorData.getBuildings(modFolder), current)
            "list:tech" -> makeSearchableBox(ModEditorData.getTechs(modFolder), current)
            "list:policy" -> makeSearchableBox(ModEditorData.getPolicies(modFolder), current)
            "list:era" -> makeSearchableBox(ModEditorData.getEras(modFolder), current)
            "list:speed" -> makeSearchableBox(ModEditorData.getSpeeds(modFolder), current)
            "list:difficulty" -> makeSearchableBox(ModEditorData.getDifficulties(modFolder), current)
            "list:victoryType" -> makeSearchableBox(ModEditorData.getVictoryTypes(modFolder), current)
            "list:event" -> makeSearchableBox(ModEditorData.getEvents(modFolder), current)
            "list:unitNameGroup" -> makeSearchableBox(ModEditorData.getUnitNameGroups(modFolder), current)
            "list:mod" -> makeSearchableBox(ModEditorData.getInstalledMods(), current)
            "list:unitOrBuilding" -> makeSearchableBox(ModEditorData.getUnitsAndBuildings(modFolder), current)
            "list:combatant" -> makeSearchableBox(
                listOf("City", "All") + ModEditorData.getUnits(modFolder), current)
            "list:cityFilter" -> makeSearchableBox(
                listOf("in this city", "in all cities", "All", "all", "in your cities",
                    "in all coastal cities", "in capital", "in all non-occupied cities",
                    "in all cities with a world wonder", "in all cities connected to capital",
                    "in all cities with a garrison", "in non-enemy foreign cities",
                    "in enemy cities", "in foreign cities", "in annexed cities",
                    "in puppeted cities", "in resisting cities", "in cities being razed",
                    "in holy cities", "in City-State cities",
                    "in cities following this religion", "in cities following our religion",
                    "in all cities in which the majority religion is a major religion",
                    "in all cities in which the majority religion is an enhanced religion"),
                current)
            "list:eraFilter" -> makeSearchableBox(
                listOf("any era", "Starting Era") + ModEditorData.getEras(modFolder), current)
            "list:populationFilter" -> makeSearchableBox(
                listOf("Population", "Specialists", "Unemployed", "Followers of this Religion"),
                current)
            "list:religionFilter" -> makeSearchableBox(
                ModEditorData.getReligions(modFolder) +
                    listOf("any", "major", "enhanced", "your", "foreign", "enemy"), current)
            "list:spyAction" -> makeSearchableBox(
                listOf("Counter-intelligence", "Stealing Tech", "Siphon Gold",
                    "Investigate City", "Sway City-State", "Diplomatic Mission", "Recruit Partisans"), current)
            "list:terrainQuality" -> makeSearchableBox(
                listOf("Undesirable", "Food", "Desirable", "Production"), current)
            "list:resourceFilter" -> makeSearchableBox(
                ModEditorData.getResources(modFolder) +
                    listOf("any", "All", "all", "Strategic", "Luxury", "Bonus",
                        "Gold", "Science", "Production", "Food", "Happiness", "Culture", "Faith"),
                current)
            "list:civFilter" -> makeSearchableBox(
                ModEditorData.getNations(modFolder) +
                    listOf("Human player", "AI player", "Friendly", "Hostile", "Open Borders",
                        "Known", "City-States", "City-State", "Major", "All", "all"), current)
            "list:policyBelief" -> makeSearchableBox(
                ModEditorData.getPolicies(modFolder) + ModEditorData.getReligions(modFolder), current)
            "list:tileOrBuilding" -> makeSearchableBox(
                ModEditorData.getTerrains(modFolder) + ModEditorData.getBuildings(modFolder), current)
            "list:improvementOrBuilding" -> makeSearchableBox(
                ModEditorData.getImprovements(modFolder) + ModEditorData.getBuildings(modFolder), current)
            "list:improvementOrTerrain" -> makeSearchableBox(
                ModEditorData.getImprovements(modFolder) + ModEditorData.getTerrains(modFolder), current)
            "list:statOrResource" -> makeSearchableBox(
                listOf("Gold", "Science", "Production", "Food", "Happiness", "Culture", "Faith")
                    + ModEditorData.getResources(modFolder), current)
            "stats" -> makeStatsButton("stats", current, target)
            else -> UncivTextField("", current)
        }
    }

    /** 可输入 + 可搜索（countable 写算式用）
     *  A：输入即过滤（ComboBox editable 内置）；B：模板参数自动替换；C：最近使用置顶（2026-08-19 用户要求） */
    private fun makeEditableBox(values: List<String>, current: String): ModEditorComboBox {
        // C：countable 最近使用置顶（持久化 .editor-meta.json，最新在前）
        val history = ModEditorData.readCountableHistory(modFolder)
        val candidates = (history + values).distinct()
        lateinit var combo: ModEditorComboBox
        combo = ModEditorComboBox(
            screen, candidates, current, editable = true, onChanged = {},
            onPick = { picked -> handleCountablePick(picked) { filled -> combo.setText(filled) } }
        )
        return combo
    }

    /** countable 模板参数名 → 控件类型（官方 countable 文档 2026-08-19） */
    private val countableParamTypes = mapOf(
        "Stat/Resource" to "list:statOrResource",
        "stat" to "list:statOrResource",
        "cityFilter" to "list:cityFilter",
        "mapUnitFilter" to "list:unitFilter",
        "buildingFilter" to "list:buildingFilter",
        "policyFilter" to "list:policyFilter",
        "techFilter" to "list:techFilter",
        "tileFilter" to "list:tileFilter",
        "civFilter" to "list:civFilter",
        "resourceFilter" to "list:resourceFilter"
    )

    /** B：选中含 [占位符] 的 countable 模板 → 弹参数填写（控件类型按参数名映射），填完拼好回填并记录历史 */
    private fun handleCountablePick(picked: String, onFilled: (String) -> Unit) {
        val paramPattern = Regex("\\[([^\\]]+)\\]")
        val params = paramPattern.findAll(picked).map { it.groupValues[1] }.toList()
        if (params.isEmpty()) {
            recordCountableValue(picked)
            return
        }
        val popup = Popup(screen, scrollable = Popup.Scrollability.None)
        popup.add("Fill in parameters".tr().toLabel(fontSize = 22)).pad(8f).row()
        // 模板显示英文原文：不走 toLabel（tr() 会把 "[Stat/Resource] Per Turn" 整串翻成纯中文）
        popup.add(Label(picked, BaseScreen.skin).apply {
            setFontScale(13f / Fonts.ORIGINAL_FONT_SIZE)
            setColor(Color(1f, 1f, 1f, 0.5f))
            wrap = true
        }).growX().left().pad(0f, 8f, 4f, 8f).row()
        // 参数值临时收集：只在 Save 时回填，不触发父级 onValueChange
        val tempValues = LinkedHashMap<String, String>()
        val widgets = mutableListOf<Pair<String, Actor>>()
        for (param in params) {
            val row = Table(BaseScreen.skin)
            // 参数名双语（英文原文（中文）），不走 toLabel 整串翻译
            row.add(bilingualUniqueLabel(param, param.tr(), 14f)).left().pad(4f).width(180f)
            val paramDef = CatalogParam(param, countableParamTypes[param] ?: "text", param, "", emptyList())
            val widget = createParamWidget(paramDef, "", tempValues)
            when (widget) {
                is ModEditorComboBox -> widget.addListener(object : ChangeListener() {
                    override fun changed(event: ChangeEvent?, actor: Actor?) {
                        tempValues[param] = widget.getText().trim()
                    }
                })
                is UncivTextField -> widget.setTextFieldListener { f, _ ->
                    tempValues[param] = f.text.trim()
                }
                else -> {}
            }
            row.add(widget).growX().minWidth(200f).pad(4f)
            widgets.add(param to widget)
            popup.add(row).growX().left().row()
        }
        popup.addButton("Save".tr()) {
            var result = picked
            for ((param, _) in widgets) {
                val v = tempValues[param]?.takeIf { it.isNotBlank() } ?: param
                result = result.replace("[$param]", "[$v]")
            }
            recordCountableValue(result)
            onFilled(result)
            popup.close()
        }
        popup.addCloseButton()
        popup.open()
        (widgets.firstOrNull()?.second as? UncivTextField)?.let { screen.stage.keyboardFocus = it }
    }

    /** C：countable 最近使用记录（最新在前，上限 20） */
    private fun recordCountableValue(value: String) {
        val history = ModEditorData.readCountableHistory(modFolder).toMutableList()
        history.remove(value)
        history.add(0, value)
        ModEditorData.writeCountableHistory(modFolder, history.take(20))
    }

    /** 只读可搜索下拉（选项多时输入过滤）；值变化经 ChangeEvent 由 attachValueListener 同步 */
    private fun makeSearchableBox(values: List<String>, current: String): ModEditorComboBox =
        ModEditorComboBox(screen, values, current, editable = false, onChanged = {})


    private fun readParamValue(widget: Actor): String = when (widget) {
        is UncivTextField -> widget.text.trim()
        is ModEditorSelectBox -> widget.selected?.value ?: ""
        is ModEditorComboBox -> widget.getText().trim()
        else -> ""
    }

    // ------------------------------------------------------------------
    // 条件 chip（<范围>，蓝色背景，末尾 ×）
    // ------------------------------------------------------------------

    private fun buildConditionChip(
        condition: CatalogCondition,
        conditionValues: MutableMap<String, String>
    ): Table {
        val chip = Table(BaseScreen.skin)
        chip.background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/ConditionChip", BaseScreen.skinStrings.roundedEdgeRectangleShape,
            Color(0.15f, 0.4f, 0.7f, 0.8f))
        for (part in buildTemplateParts(condition.key, condition.params, conditionValues)) {
            chip.add(part).left().pad(2f)
        }
        val removeButton = "×".toTextButton()
        removeButton.onActivation {
            val pairIndex = conditions.indexOfFirst { it.first === condition && it.second === conditionValues }
            if (pairIndex >= 0) conditions.removeAt(pairIndex)
            onStructureChange()
        }
        chip.add(removeButton).pad(2f)
        return chip
    }

    // ------------------------------------------------------------------
    // 按钮组：添加条件 / 复制 / ×
    // ------------------------------------------------------------------

    private fun buildButtons(): Table {
        val buttons = Table(BaseScreen.skin)
        val addConditionButton = "+ Add condition".toTextButton()
        addConditionButton.onActivation { showConditionSearchPopup() }
        buttons.add(addConditionButton).pad(3f)
        val duplicateButton = "Duplicate".toTextButton()
        duplicateButton.onActivation { onDuplicate() }
        buttons.add(duplicateButton).pad(3f)
        val deleteButton = "×".toTextButton()
        deleteButton.onActivation { onDelete() }
        buttons.add(deleteButton).pad(3f)
        return buttons
    }

    // ------------------------------------------------------------------
    // 条件搜索弹层（无分类，支持搜索；只列出该词条允许的条件）
    // ------------------------------------------------------------------

    private fun showConditionSearchPopup() {
        val popup = Popup(screen, scrollable = Popup.Scrollability.None)
        popup.add("Search conditions".tr().toLabel(fontSize = 22)).pad(8f).row()
        val searchField = UncivTextField("Search")
        popup.add(searchField).growX().pad(6f).row()

        val allowedConditions = if (unique.conditions.isEmpty())
            catalog.conditions
        else catalog.conditions.filter {
            // ⚠️ catalog 里 unique 声明的条件值有不带尖括号的（如 "upon turn end"），
            // 而 conditions 表的 key 带尖括号（"<upon turn end>"）→ 直接 in 匹配永远为空，
            // 导致这些词条「添加条件」列表空白（2026-08-19 用户发现）
            it.key.removeSurrounding("<", ">") in unique.conditions
        }

        val listTable = Table(BaseScreen.skin)
        val listScroll = AutoScrollPane(listTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false // 滚动条常驻可见
        }
        popup.add(listScroll).grow().width(560f).height(380f).pad(6f).row()

        fun refresh(query: String) {
            listTable.clear()
            val q = query.trim().lowercase()
            for (condition in allowedConditions) {
                if (q.isNotEmpty() &&
                    !condition.display.lowercase().contains(q) &&
                    !condition.key.lowercase().contains(q) &&
                    !condition.desc.lowercase().contains(q)) continue
                val row = Table(BaseScreen.skin)
                row.background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/ConditionRow", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                    BaseScreen.skinStrings.skinConfig.baseColor)
                // 双语：英文原词条（中文翻译）；目录翻译缺失时回退游戏翻译表；都没有只显示英文
                // 注意：必须用 bilingualUniqueLabel（Label 直接构造不走 tr()），
                // 否则 "when attacking" 整行会被翻译表翻成纯中文「攻击时」
                val displayLabel = bilingualUniqueLabel(condition.key, condition.display, 17f)
                row.add(displayLabel).growX().left().pad(6f, 8f, 6f, 8f).row()
                row.touchable = Touchable.enabled
                row.onActivation {
                    val defaults = LinkedHashMap<String, String>()
                    for (param in condition.params) if (param.default.isNotBlank()) defaults[param.id] = param.default
                    conditions.add(condition to defaults)
                    popup.close()
                    onStructureChange()
                }
                listTable.add(row).growX().pad(3f, 6f, 3f, 6f).row()
            }
            if (listTable.children.size == 0) {
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
    // stats 组合产出编辑器（如 "+1 Gold, +2 Production"）
    // ------------------------------------------------------------------

    private fun makeStatsButton(id: String, current: String, target: MutableMap<String, String>): Actor {
        // 直接用 TextButton 构造（不走 toTextButton 的 tr()）——stats 值如 "+1 Gold" 必须保持英文
        val button = TextButton(current.ifBlank { "+0 Gold" }, BaseScreen.skin)
        button.label.setWrap(false)
        button.onActivation { showStatsEditor(id, target, button) }
        return button
    }

    private fun showStatsEditor(
        id: String,
        target: MutableMap<String, String>,
        button: TextButton
    ) {
        val popup = Popup(screen, scrollable = Popup.Scrollability.None)
        popup.add("Edit stats".tr().toLabel(fontSize = 22)).pad(8f).row()

        val statsTable = Table(BaseScreen.skin)
        val rows = mutableListOf<Pair<UncivTextField, ModEditorSelectBox>>()
        val current = target[id] ?: ""

        fun parsePart(part: String): Pair<String, String> {
            val m = Regex("^([+-]?\\d+)\\s*(.+)$").find(part.trim())
            return if (m != null) m.groupValues[1] to m.groupValues[2].trim() else "1" to part.trim()
        }

        fun addRow(amount: String, stat: String) {
            val amountField = UncivTextField("", amount)
            amountField.textFieldFilter = object : TextField.TextFieldFilter {
                override fun acceptChar(textField: TextField, c: Char): Boolean {
                    if (c in '0'..'9') return true
                    // 正/负号：只要还没输过符号就允许
                    if ((c == '-' || c == '+') &&
                        !textField.text.contains('-') && !textField.text.contains('+')) return true
                    return false
                }
            }
            val statBox = ModEditorSelectBox(
                listOf("Gold", "Science", "Production", "Food", "Happiness", "Culture", "Faith"), stat,
                searchable = true)
            val rowT = Table(BaseScreen.skin)
            rowT.add(amountField).width(90f).pad(4f)
            rowT.add(statBox).width(220f).pad(4f)
            val removeButton = "×".toTextButton()
            removeButton.onActivation {
                rows.remove(amountField to statBox)
                rowT.remove()
            }
            rowT.add(removeButton).pad(4f)
            rows.add(amountField to statBox)
            statsTable.add(rowT).growX().left().row()
        }

        val parts = current.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) addRow("1", "Gold")
        else for (part in parts) {
            val (amount, stat) = parsePart(part)
            addRow(amount, stat)
        }

        val scroll = AutoScrollPane(statsTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        popup.add(scroll).grow().height(300f).pad(6f).row()
        popup.addButton("+ Add stat") { addRow("1", "Gold") }
        popup.addButton("Save") {
            val value = rows.mapNotNull { (field, box) ->
                val amount = field.text.trim().ifBlank { "1" }
                val stat = box.selected?.value ?: ""
                if (stat.isBlank()) null else "$amount $stat"
            }.joinToString(", ")
            if (value.isBlank()) target.remove(id) else target[id] = value
            button.setText(value.ifBlank { "+0 Gold" })
            onValueChange()
            popup.close()
        }
        popup.addCloseButton()
        popup.open()
    }
}

/**
 * 「以原文模式修改」按钮：批量编辑 uniques 列表（弹窗 TextArea 一行一个，预填现有词条），
 * 保存后整体替换。所有有 uniques 的编辑器统一使用（2026-08-19 用户要求，放在 "+ Add unique" 后面）。
 */
fun addRawEditUniquesButton(
    screen: BaseScreen,
    table: Table,
    getUniques: () -> MutableList<String>,
    onChanged: () -> Unit
) {
    val rawEditButton = "Edit raw".toTextButton()
    rawEditButton.onActivation {
        val popup = Popup(screen, scrollable = Popup.Scrollability.None)
        popup.add("Uniques (raw mode)".tr().toLabel(fontSize = 22)).pad(8f).row()
        popup.add("One unique per line - no quotes needed, they are added automatically when saving".tr()
            .toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f))).pad(0f, 8f, 4f, 8f).row()
        val area = TextArea(getUniques().joinToString("\n"), BaseScreen.skin)
        val scroll = AutoScrollPane(area).apply {
            setOverscroll(false, false)
            setScrollingDisabled(true, false)
        }
        popup.add(scroll).growX().width(560f).height(300f).pad(6f).row()
        popup.addButton("Save".tr()) {
            val lines = area.text.lines().map { it.trim() }
                .map { it.replace(Regex("\\s{2,}"), " ") }
                .filter { it.isNotEmpty() }
            val uniques = getUniques()
            uniques.clear()
            uniques.addAll(lines)
            onChanged()
            popup.close()
        }
        popup.addCloseButton()
        popup.open()
        screen.stage.keyboardFocus = area
    }
    table.add(rawEditButton).left().pad(6f)
}

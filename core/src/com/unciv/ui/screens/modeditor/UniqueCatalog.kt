package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.translations.getPlaceholderParameters
import com.unciv.models.translations.getPlaceholderText
import com.unciv.models.translations.tr
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.screens.basescreen.BaseScreen

/** 词条目录：效果 + 范围（条件）的元数据，从 unique_catalog.json 加载 */
class CatalogParam(
    val id: String,
    val type: String,
    val label: String,
    val default: String,
    val options: List<String>
)

class CatalogUnique(
    val key: String,
    val category: String,
    val display: String,
    val desc: String,
    val params: List<CatalogParam>,
    val conditions: List<String>
)

class CatalogCondition(
    val key: String,
    val category: String,
    val display: String,
    val desc: String,
    val params: List<CatalogParam>
)

/** 解析结果：效果 + 已填参数 + 条件列表（用于行内编辑） */
class ParsedUnique(
    val unique: CatalogUnique,
    val values: MutableMap<String, String>,
    val conditions: MutableList<Pair<CatalogCondition, MutableMap<String, String>>>
)

class UniqueCatalog(
    val uniques: List<CatalogUnique>,
    val conditions: List<CatalogCondition>,
    val categories: List<String>
) {
    fun byCategory(category: String) = uniques.filter { it.category == category }

    fun condition(key: String): CatalogCondition? = conditions.firstOrNull { it.key == key }

    /** 把效果 + 范围拼成原始词条字符串 */
    fun buildRawString(
        unique: CatalogUnique,
        values: Map<String, String>,
        conditions: List<Pair<CatalogCondition, Map<String, String>>>
    ): String {
        // 占位符替换必须保留方括号："[relativeAmount]" → "[+33]"（否则生成无括号词条，官方解析器不识别）
        var result = unique.key
        for ((id, value) in values) result = result.replace("[$id]", "[$value]")
        for ((condition, cValues) in conditions) {
            var conditionText = condition.key
            for ((id, value) in cValues) conditionText = conditionText.replace("[$id]", "[$value]")
            // ⚠️ catalog 的 condition.key 自带尖括号（官方词条原文风格，如 "<when attacking>"），
            // 这里必须先去掉外层尖括号再包一层，否则保存成 "xxx <<when attacking>>"（2026-08-19 用户发现）
            conditionText = conditionText.removeSurrounding("<", ">").trim()
            result += " <$conditionText>"
        }
        return result
    }

    // ------------------------------------------------------------------
    // 解析：把原始词条字符串还原为 效果+参数+条件（行内编辑/预填用）
    // ------------------------------------------------------------------

    fun parseRaw(raw: String): ParsedUnique? {
        // 官方解析器（用户规则 2026-08-17：不自创匹配，用 Unciv 自己的 Unique 解析）：
        // - getPlaceholderParameters 按括号深度提取参数值（支持嵌套，如 [[Culture] Per Turn]）
        // - UniqueType.uniqueTypeMap 按 placeholder 文本精确查表——没有 [] 的裸文本绝不会变成参数，
        //   模板 literal 必须完全一致；官方不认识的词条（type==null）直接回退原文行，不强行拆分
        val unique = Unique(raw)
        val type = unique.type
        // 里程碑：官方 Unique 解析器不认识（MilestoneType 是独立枚举），fallback 用 placeholder 匹配 catalog 的 Milestones 分类
        if (type == null) {
            val placeholder = raw.getPlaceholderText()
            val catalogMilestone = uniques.firstOrNull {
                it.category == "Milestones" && it.key.getPlaceholderText() == placeholder
            } ?: return null
            val values = LinkedHashMap<String, String>()
            val params = raw.getPlaceholderParameters()
            for ((i, id) in catalogMilestone.params.map { it.id }.withIndex()) {
                if (i < params.size) values[id] = params[i]
            }
            return ParsedUnique(catalogMilestone, values, mutableListOf())
        }
        val placeholder = type.text.getPlaceholderText()
        val catalogUnique = uniques.firstOrNull { it.key.getPlaceholderText() == placeholder } ?: return null
        val paramIds = catalogUnique.params.map { it.id }
        val values = LinkedHashMap<String, String>()
        for ((i, id) in paramIds.withIndex()) {
            if (i < unique.params.size) values[id] = unique.params[i]
        }
        // 条件（<...>）：官方 modifiers 递归解析，同样按 placeholder 关联 catalog
        val parsedConditions = mutableListOf<Pair<CatalogCondition, MutableMap<String, String>>>()
        for (modifier in unique.modifiers) {
            val modifierType = modifier.type ?: return null
            val cond = conditions.firstOrNull {
                // ⚠️ catalog 的 condition.key 自带尖括号（官方风格 "<when attacking>"），而 getPlaceholderText()
                // 内部 removeConditionals() 会把整个 <...> 字符串剥成空串 → 必须先去外层尖括号再匹配，
                // 否则带范围的词条 parseRaw 永远失败 → 全部退化成原文模式（2026-08-19 用户发现）
                it.key.removeSurrounding("<", ">").trim().getPlaceholderText() ==
                    modifierType.text.getPlaceholderText()
            } ?: return null
            val condIds = cond.params.map { it.id }
            val condValues = LinkedHashMap<String, String>()
            for ((i, id) in condIds.withIndex()) {
                if (i < modifier.params.size) condValues[id] = modifier.params[i]
            }
            parsedConditions.add(cond to condValues)
        }
        return ParsedUnique(catalogUnique, values, parsedConditions)
    }

    companion object {
        fun load(): UniqueCatalog {
            val file = Gdx.files.internal("ModEditor/unique_catalog.json")
            val root = JsonReader().parse(file.readString(Charsets.UTF_8.name()))
            val uniques = mutableListOf<CatalogUnique>()
            for (entry in root.get("uniques")) {
                uniques.add(CatalogUnique(
                    key = entry.getString("key", ""),
                    category = entry.getString("category", "Unit uniques"),
                    display = entry.getString("display", entry.getString("key", "")),
                    desc = entry.getString("desc", ""),
                    params = parseParams(entry),
                    conditions = if (entry.has("conditions"))
                        entry.get("conditions").map { it.asString() } else emptyList()
                ))
            }
            val conditions = mutableListOf<CatalogCondition>()
            for (entry in root.get("conditions")) {
                conditions.add(CatalogCondition(
                    key = entry.getString("key", ""),
                    category = entry.getString("category", "Conditional uniques"),
                    display = entry.getString("display", entry.getString("key", "")),
                    desc = entry.getString("desc", ""),
                    params = parseParams(entry)
                ))
            }
            return UniqueCatalog(uniques, conditions, loadCategories(root))
        }

        private fun loadCategories(root: JsonValue): List<String> {
            if (root.has("categories")) return root.get("categories").map { it.asString() }
            // 兜底：从词条推导（按出现顺序去重）
            return root.get("uniques").map { it.getString("category", "Unit uniques") }.distinct()
        }

        private fun parseParams(entry: JsonValue): List<CatalogParam> {
            if (!entry.has("params")) return emptyList()
            val params = mutableListOf<CatalogParam>()
            for (p in entry.get("params")) {
                val id = p.getString("id", "")
                val default = when {
                    p.has("default") && p.get("default").isNumber ->
                        p.get("default").asDouble().toInt().toString()
                    p.has("default") -> p.get("default").asString()
                    else -> ""
                }
                val options = if (p.has("options"))
                    p.get("options").map { it.asString() } else emptyList()
                params.add(CatalogParam(
                    id = id,
                    type = p.getString("type", "text"),
                    label = p.getString("label", id),
                    default = default,
                    options = options
                ))
            }
            return params
        }
    }
}

/**
 * 双语显示文本：英文原词条（中文翻译）。
 * 优先用目录里的 display（效果/条件的中文翻译）；display 缺失（等于英文 key）时回退游戏翻译表；
 * 都没有翻译时只显示英文。
 */
fun bilingualUniqueText(english: String, catalogDisplay: String): String {
    val translated = catalogDisplay.takeIf { it != english }
        ?: english.tr().takeIf { it != english }
    return if (translated == null) english else "$english（$translated）"
}

/**
 * 双语 Label：直接构造（不走 tr()），防止整串文本被翻译表二次翻译成纯中文。
 * 之前条件弹层用 toLabel() 时 "when attacking" 整行被 tr() 成「攻击时」——必须用这个。
 */
fun bilingualUniqueLabel(english: String, catalogDisplay: String, fontSize: Float): Label =
    Label(bilingualUniqueText(english, catalogDisplay), BaseScreen.skin).apply {
        setFontScale(fontSize / Fonts.ORIGINAL_FONT_SIZE)
        setAlignment(Align.left)
        wrap = true
    }

/** 词条间分隔线（浅色细线，提示不同词条的边界） */
fun uniqueSeparatorLine(): Table = Table(BaseScreen.skin).apply {
    background = BaseScreen.skinStrings.getUiBackground(
        "ModEditor/UniqueSeparator", null, com.badlogic.gdx.graphics.Color(1f, 1f, 1f, 0.12f))
}

package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.badlogic.gdx.utils.JsonWriter
import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.PolicyBranch
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.Specialist
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.tech.Technology
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.validation.RulesetErrorSeverity
import com.unciv.models.translations.TranslationEntry
import com.unciv.models.translations.TranslationFileReader
import com.unciv.models.translations.Translations
import com.unciv.models.translations.tr

/** 一个规则集对象（单位/建筑通用）：raw 保存所有字段（含未知字段），表单只读写已知字段 */
open class ModObjectData {
    var name = ""
    var comment = ""
    val uniques = mutableListOf<String>()

    /** 所有字段的原始值（含未知字段），写回文件时以此为准 */
    val raw = LinkedHashMap<String, Any?>()

    fun syncUniques() {
        raw["uniques"] = uniques.toList()
    }

    fun getString(key: String): String = raw[key] as? String ?: ""
    fun getIntText(key: String): String {
        val v = raw[key] ?: return ""
        return when (v) {
            is Int -> v.toString()
            is Long -> v.toString()
            is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
            is Float -> if (v % 1f == 0f) v.toLong().toString() else v.toString()
            is Number -> v.toLong().toString()
            is String -> v.toIntOrNull()?.toString()
                ?: v.toDoubleOrNull()?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
                ?: ""
            else -> ""
        }
    }
    fun setString(key: String, value: String?) {
        if (value.isNullOrBlank()) raw.remove(key) else raw[key] = value
    }
    fun getFloatText(key: String): String {
        val v = raw[key] ?: return ""
        return when (v) {
            is Number -> {
                val d = v.toDouble()
                if (d % 1.0 == 0.0 && d in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) d.toInt().toString() else d.toString()
            }
            is String -> v
            else -> ""
        }
    }
    fun setInt(key: String, value: Int?) {
        if (value == null) raw.remove(key) else raw[key] = value
    }
    fun setNumber(key: String, value: Double?) {
        if (value == null) raw.remove(key) else raw[key] = value
    }
    fun getStringList(key: String): List<String> {
        val v = raw[key] ?: return emptyList()
        if (v is List<*>) return v.filterIsInstance<String>()
        return emptyList()
    }
    fun setStringList(key: String, list: List<String>) {
        if (list.isEmpty()) raw.remove(key) else raw[key] = list
    }
}

/** 一个科技：ModObjectData（name/raw/uniques）+ prerequisites */
class TechData : ModObjectData() {
    val prerequisites = mutableListOf<String>()
    fun syncPrerequisites() {
        if (prerequisites.isEmpty()) raw.remove("prerequisites") else raw["prerequisites"] = prerequisites.toList()
    }
}

/** Techs.json 的一列（columnNumber/era/techCost/buildingCost/wonderCost + 未知字段） */
class TechGroupData {
    val raw = LinkedHashMap<String, Any?>()
    val techs = mutableListOf<TechData>()
    var era: String
        get() = raw["era"] as? String ?: ""
        set(value) { if (value.isBlank()) raw.remove("era") else raw["era"] = value }
    var columnNumber: Int
        get() = (raw["columnNumber"] as? Number)?.toInt() ?: 0
        set(value) { if (value <= 0) raw.remove("columnNumber") else raw["columnNumber"] = value }
    fun getIntText(key: String): String {
        val v = raw[key] ?: return ""
        return when (v) {
            is Int -> v.toString()
            is Long -> v.toString()
            is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
            is Float -> if (v % 1f == 0f) v.toLong().toString() else v.toString()
            else -> ""
        }
    }
    fun setInt(key: String, value: Int?) {
        if (value == null) raw.remove(key) else raw[key] = value
    }
}

/** 一个政策：ModObjectData（name/raw/uniques）+ requires（前置政策） */
class PolicyData : ModObjectData() {
    val requires = mutableListOf<String>()
    fun syncRequires() {
        if (requires.isEmpty()) raw.remove("requires") else raw["requires"] = requires.toList()
    }
}

/** Policies.json 的一个分支（name/era/priorities/uniques + policies[]） */
class PolicyBranchData {
    val raw = LinkedHashMap<String, Any?>()
    val policies = mutableListOf<PolicyData>()
    val uniques = mutableListOf<String>()
    var name: String
        get() = raw["name"] as? String ?: ""
        set(value) { if (value.isBlank()) raw.remove("name") else raw["name"] = value }
    var era: String
        get() = raw["era"] as? String ?: ""
        set(value) { if (value.isBlank()) raw.remove("era") else raw["era"] = value }
    fun getIntText(key: String): String {
        val v = raw[key] ?: return ""
        return when (v) {
            is Int -> v.toString()
            is Long -> v.toString()
            is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
            is Float -> if (v % 1f == 0f) v.toLong().toString() else v.toString()
            else -> ""
        }
    }
    fun setInt(key: String, value: Int?) {
        if (value == null) raw.remove(key) else raw[key] = value
    }
    fun syncUniques() {
        if (uniques.isEmpty()) raw.remove("uniques") else raw["uniques"] = uniques.toList()
        if (policies.isEmpty()) raw.remove("policies") else raw["policies"] = policies.map { policy ->
            policy.syncRequires()
            policy.syncUniques()
            policy.raw
        }
    }
}

/** ModOptions.json 数据：raw 保留未知字段，已知字段通过访问器读写 */
class ModOptionsData {
    val raw = LinkedHashMap<String, Any?>()
    val uniques = mutableListOf<String>()

    fun syncRaw() {
        if (uniques.isEmpty()) raw.remove("uniques") else raw["uniques"] = uniques.toList()
    }

    fun getBool(key: String): Boolean = raw[key] as? Boolean ?: false
    fun setBool(key: String, value: Boolean) {
        if (value) raw[key] = true else raw.remove(key)
    }
    fun getString(key: String): String = raw[key] as? String ?: ""
    fun setString(key: String, value: String?) {
        if (value.isNullOrBlank()) raw.remove(key) else raw[key] = value
    }
    fun getStringList(key: String): List<String> =
        (raw[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
    fun setStringList(key: String, list: List<String>) {
        if (list.isEmpty()) raw.remove(key) else raw[key] = list
    }
    fun getIntText(key: String): String {
        val v = raw[key] ?: return ""
        return when (v) {
            is Int -> v.toString()
            is Long -> v.toString()
            is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
            is Float -> if (v % 1f == 0f) v.toLong().toString() else v.toString()
            else -> ""
        }
    }
    fun setInt(key: String, value: Int?) {
        if (value == null) raw.remove(key) else raw[key] = value
    }
    fun getConstants(): Map<String, Any?> =
        (raw["constants"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
    /** 写 constants：全部等于默认值（空 map）时整个字段移除 */
    fun setConstants(values: Map<String, Any?>) {
        if (values.isEmpty()) raw.remove("constants") else raw["constants"] = LinkedHashMap(values)
    }
}

/** 单位编辑器数据层：读/写 Units.json（保留注释与未知字段）、模组元数据、模板 */
object ModEditorData {

    // ------------------------------------------------------------------
    // Units.json 读写
    // ------------------------------------------------------------------

    /** 提取行首 // 注释，挂到下一个 { 块上；返回(去掉注释的文本, 每块注释) */
    private fun extractComments(text: String): Pair<String, List<String>> {
        val clean = StringBuilder()
        val comments = mutableListOf<String>()
        var pending = mutableListOf<String>()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("//")) {
                pending.add(trimmed.removePrefix("//").trim())
                continue
            }
            clean.append(line).append('\n')
            if (trimmed.startsWith("{")) {
                comments.add(pending.joinToString("\n"))
                pending = mutableListOf()
            }
        }
        return clean.toString() to comments
    }

    private fun JsonValue.toPlain(): Any? = when {
        isObject -> {
            val map = LinkedHashMap<String, Any?>()
            for (child in this) map[child.name] = child.toPlain()
            map
        }
        isArray -> {
            val list = mutableListOf<Any?>()
            for (child in this) list.add(child.toPlain())
            list
        }
        isBoolean -> asBoolean()
        isNumber -> {
            val d = asDouble()
            if (d == Math.floor(d) && !d.isInfinite()) d.toLong() else d
        }
        else -> asString()
    }

    /** 去掉 JSON 中的尾逗号（字符串感知）——基础规则集文件里有尾逗号，Gdx JsonReader 不接受 */
    fun removeTrailingCommasPublic(text: String): String {
        val sb = StringBuilder(text.length)
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '"') { inString = !inString; sb.append(c); i++; continue }
            if (!inString && c == ',') {
                var j = i + 1
                while (j < text.length && text[j] in " \t\n\r") j++
                if (j < text.length && (text[j] == ']' || text[j] == '}')) { i++; continue }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    fun loadUnits(modFolder: FileHandle): MutableList<ModObjectData> = loadObjects(modFolder, "Units.json")
    fun loadBuildings(modFolder: FileHandle): MutableList<ModObjectData> = loadObjects(modFolder, "Buildings.json")

    /**
     * 从基础规则集文件读取对象（单位/建筑）——「从规则集复制」数据源。
     * 同名对象在游戏合并规则集时会覆盖原版（扩展模组修改原版的标准方式）。
     */
    fun loadBaseObjects(modFolder: FileHandle, fileName: String): MutableList<ModObjectData> {
        return loadBaseObjects(modFolder, fileName, readBaseRulesetChoice(modFolder).ifBlank { BaseRuleset.Civ_V_GnK.fullName })
    }

    /** 从指定规则集读取对象（单位/建筑）——「从规则集复制」数据源。
     *  @param baseName 规则集全名或已安装 base ruleset mod 名；null 时用编辑器元数据或默认 G&K */
    fun loadBaseObjects(modFolder: FileHandle, fileName: String, baseName: String?): MutableList<ModObjectData> {
        val result = mutableListOf<ModObjectData>()
        val resolved = baseName?.takeIf { it.isNotBlank() }
            ?: readBaseRulesetChoice(modFolder).ifBlank { BaseRuleset.Civ_V_GnK.fullName }
        // 来源可能是内置规则集（internal）或已安装的 base ruleset mod（mods 文件夹）
        val internal = Gdx.files.internal("jsons/$resolved/$fileName")
        val file = if (internal.exists()) internal
            else UncivGame.Current.files.getModFolder(resolved).child("jsons/$fileName")
        println("[ModEditor] loadBaseObjects: baseName=$resolved file=${file.path()} exists=${file.exists()}")
        if (!file.exists()) return result
        try {
            val parsed = JsonReader().parse(removeTrailingCommasPublic(stripCommentsPublic(file.readString(Charsets.UTF_8.name()))))
            if (!parsed.isArray) return result
            println("[ModEditor] loadBaseObjects: $fileName 解析成功 ${parsed.size} 个")
            for (entry in parsed) {
                val obj = ModObjectData()
                for (field in entry) obj.raw[field.name] = field.toPlain()
                obj.name = obj.getString("name")
                val existingUniques = obj.raw["uniques"]
                if (existingUniques is List<*>) obj.uniques.addAll(existingUniques.filterIsInstance<String>())
                result.add(obj)
            }
        } catch (e: Exception) {
            // 解析失败：空列表
        }
        return result
    }

    /** 去掉 // 和块注释（含行内注释，字符串内的 // 不动） */
    fun stripCommentsPublic(text: String): String {
        val sb = StringBuilder(text.length)
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '"' && (i == 0 || text[i - 1] != '\\')) inString = !inString
            if (!inString && c == '/' && i + 1 < text.length) {
                val next = text[i + 1]
                if (next == '/') {
                    while (i < text.length && text[i] != '\n') i++
                    continue
                }
                if (next == '*') {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i += 2
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    fun loadTechs(modFolder: FileHandle): MutableList<TechGroupData> {
        val result = mutableListOf<TechGroupData>()
        val file = modFolder.child("jsons/Techs.json")
        if (!file.exists()) return result
        try {
            val parsed = JsonReader().parse(file.readString(Charsets.UTF_8.name()))
            if (!parsed.isArray) return result
            // 基础规则集同列已占用的行号（mod 没写 row 时自动分配后续行）
            val usedRows = HashMap<Int, MutableSet<Int>>()
            for (tech in getBaseRuleset(modFolder).technologies.values) {
                val col = tech.column?.columnNumber ?: continue
                usedRows.getOrPut(col) { mutableSetOf() }.add(tech.row)
            }
            for (entry in parsed) {
                val group = TechGroupData()
                for (field in entry) group.raw[field.name] = field.toPlain()
                val techs = entry.get("techs")
                if (techs != null && techs.isArray) {
                    var nextRow = (usedRows[group.columnNumber]?.maxOrNull() ?: 0) + 1
                    for (techEntry in techs) {
                        val tech = TechData()
                        for (field in techEntry) tech.raw[field.name] = field.toPlain()
                        tech.name = tech.getString("name")
                        val existingUniques = tech.raw["uniques"]
                        if (existingUniques is List<*>) tech.uniques.addAll(existingUniques.filterIsInstance<String>())
                        val existingPrereqs = tech.raw["prerequisites"]
                        if (existingPrereqs is List<*>) tech.prerequisites.addAll(existingPrereqs.filterIsInstance<String>())
                        val row = (tech.raw["row"] as? Number)?.toInt()
                        if (row == null) {
                            tech.raw["row"] = nextRow
                            usedRows.getOrPut(group.columnNumber) { mutableSetOf() }.add(nextRow)
                            nextRow++
                        } else {
                            nextRow = maxOf(nextRow, row + 1)
                            usedRows.getOrPut(group.columnNumber) { mutableSetOf() }.add(row)
                        }
                        group.techs.add(tech)
                    }
                }
                result.add(group)
            }
        } catch (e: Exception) {
            // 解析失败：空列表，由界面提示
        }
        return result
    }

    fun saveTechs(modFolder: FileHandle, groups: List<TechGroupData>) {
        val dir = modFolder.child("jsons")
        if (!dir.exists()) dir.mkdirs()
        val file = dir.child("Techs.json")
        if (file.exists()) file.copyTo(dir.child("Techs.json.bak"))

        val sb = StringBuilder("[\n")
        for ((index, group) in groups.withIndex()) {
            group.raw["techs"] = group.techs.map { tech ->
                tech.syncUniques()
                tech.syncPrerequisites()
                tech.raw
            }
            appendJson(sb, group.raw, 1)
            if (index != groups.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append(']')
        file.writeString(sb.toString(), false, Charsets.UTF_8.name())
        mirrorToDebugFolder(modFolder)
    }

    /**
     * 科技校验（对照官方 Techs.schema.json）：
     * 名称必填且不重复（含基础规则集）；前置科技必须存在（基础+模组）；同列行号不能冲突。
     * 返回 (消息, 是否错误)。
     */
    fun validateTechs(modFolder: FileHandle, groups: List<TechGroupData>): List<Pair<String, Boolean>> {
        val problems = mutableListOf<Pair<String, Boolean>>()
        val knownNames = HashSet<String>()
        for (tech in getBaseRuleset(modFolder).technologies.values) knownNames.add(tech.name)

        for (group in groups) {
            val rowsInColumn = HashMap<Int, String>()
            for (tech in group.techs) {
                val name = tech.name.trim()
                if (name.isEmpty()) {
                    problems.add("Tech name cannot be empty" to true)
                } else if (!knownNames.add(name)) {
                    problems.add("Tech name [$name] is used by multiple techs" to false)
                }
                val row = (tech.raw["row"] as? Number)?.toInt() ?: 0
                val existing = rowsInColumn[row]
                if (existing != null && existing != name)
                    problems.add("Techs [$existing] and [$name] share row [$row] in column [${group.columnNumber}]" to false)
                else rowsInColumn[row] = name

                for (prereq in tech.prerequisites) {
                    if (prereq !in knownNames && prereq != name)
                        problems.add("Tech [$prereq] not found in Techs.json" to false)
                }
                problems.addAll(checkUniquesRecognized(tech.uniques))
            }
        }
        return problems
    }

    // ------------------------------------------------------------------
    // 政策（Policies.json: 分支 → 成员政策两级结构）
    // ------------------------------------------------------------------

    fun loadPolicies(modFolder: FileHandle): MutableList<PolicyBranchData> {
        val file = modFolder.child("jsons/Policies.json")
        if (!file.exists()) return mutableListOf()
        val result = mutableListOf<PolicyBranchData>()
        try {
            val text = file.readString(Charsets.UTF_8.name())
            val (clean, comments) = extractComments(text)
            val parsed = JsonReader().parse(removeTrailingCommasPublic(clean))
            if (!parsed.isArray) return result
            var branchIndex = 0
            for (entry in parsed) {
                val branch = PolicyBranchData()
                for (field in entry) branch.raw[field.name] = field.toPlain()
                val branchUniques = branch.raw["uniques"]
                if (branchUniques is List<*>) branch.uniques.addAll(branchUniques.filterIsInstance<String>())
                val policyEntries = entry.get("policies")
                if (policyEntries != null && policyEntries.isArray) {
                    var policyIndex = 0
                    for (policyEntry in policyEntries) {
                        val policy = PolicyData()
                        for (field in policyEntry) policy.raw[field.name] = field.toPlain()
                        policy.name = policy.getString("name")
                        val existingUniques = policy.raw["uniques"]
                        if (existingUniques is List<*>) policy.uniques.addAll(existingUniques.filterIsInstance<String>())
                        val existingRequires = policy.raw["requires"]
                        if (existingRequires is List<*>) policy.requires.addAll(existingRequires.filterIsInstance<String>())
                        policy.comment = comments.getOrElse(branchIndex) { "" }
                        branch.policies.add(policy)
                        policyIndex++
                    }
                }
                result.add(branch)
                branchIndex++
            }
        } catch (e: Exception) {
            // 解析失败：空列表，由界面提示
        }
        return result
    }

    fun savePolicies(modFolder: FileHandle, branches: List<PolicyBranchData>) {
        val dir = modFolder.child("jsons")
        if (!dir.exists()) dir.mkdirs()
        val file = dir.child("Policies.json")
        if (file.exists()) file.copyTo(dir.child("Policies.json.bak"))
        val sb = StringBuilder("[\n")
        for ((index, branch) in branches.withIndex()) {
            for (line in branch.name.ifBlank { branch.raw["name"] as? String ?: "" }.let { listOf("") }) {
                // 分支注释：用分支名占位（政策编辑器暂不单独存分支注释）
            }
            branch.syncUniques()
            appendJson(sb, branch.raw, 1)
            if (index != branches.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append(']')
        file.writeString(sb.toString(), false, Charsets.UTF_8.name())
        mirrorToDebugFolder(modFolder)
    }

    /** 政策校验（对照官方 Policies.schema.json）：分支名称/时代必填；政策名称必填；requires 必须存在；行/列提示 */
    fun validatePolicies(modFolder: FileHandle, branches: List<PolicyBranchData>): List<Pair<String, Boolean>> {
        val problems = mutableListOf<Pair<String, Boolean>>()
        val base = getBaseRuleset(modFolder)
        val knownPolicyNames = HashSet<String>()
        for (branch in base.policyBranches.values)
            for (policy in branch.policies) knownPolicyNames.add(policy.name)
        val knownBranchNames = HashSet(base.policyBranches.keys)
        val eras = getEras(modFolder)

        for (branch in branches) {
            val branchName = branch.name.trim()
            if (branchName.isEmpty()) {
                problems.add("Policy branch name cannot be empty" to true)
            } else if (branchName in knownBranchNames) {
                problems.add("Policy branch [$branchName] is used by multiple branches" to false)
            } else {
                knownBranchNames.add(branchName)
            }
            if (branch.era.isBlank()) {
                problems.add("Policy branch [$branchName] needs an era" to true)
            } else if (branch.era !in eras) {
                problems.add("Era [${branch.era}] not found in Eras.json" to false)
            }

            val rowsInBranch = HashMap<Pair<Int, Int>, String>()
            for (policy in branch.policies) {
                val name = policy.name.trim()
                if (name.isEmpty()) {
                    problems.add("Policy name cannot be empty (branch [$branchName])" to true)
                } else if (!knownPolicyNames.add(name)) {
                    problems.add("Policy [$name] is used by multiple policies" to false)
                }
                val row = (policy.raw["row"] as? Number)?.toInt() ?: 0
                val column = (policy.raw["column"] as? Number)?.toInt() ?: 0
                if (row <= 0 || column <= 0)
                    problems.add("Policy [$name] needs row and column numbers for UI placement" to false)
                else {
                    val at = rowsInBranch.putIfAbsent(row to column, name)
                    if (at != null && at != name)
                        problems.add("Policies [$at] and [$name] share row [$row] column [$column]" to false)
                }
                for (prereq in policy.requires) {
                    if (prereq !in knownPolicyNames && prereq != branchName)
                        problems.add("Policy [$prereq] not found in Policies.json" to false)
                }
                problems.addAll(checkUniquesRecognized(policy.uniques))
            }
            problems.addAll(checkUniquesRecognized(branch.uniques))
        }
        return problems
    }

    /** 从基础规则集读取科技列（「从规则集复制」数据源，保留分组结构） */
    fun loadBaseTechColumns(modFolder: FileHandle): MutableList<TechGroupData> {
        return loadBaseTechColumns(modFolder, readBaseRulesetChoice(modFolder).ifBlank { BaseRuleset.Civ_V_GnK.fullName })
    }

    /** 从指定规则集读取科技列（支持内置规则集和已安装 base mod）；null 时用编辑器元数据或默认 G&K */
    fun loadBaseTechColumns(modFolder: FileHandle, baseName: String?): MutableList<TechGroupData> {
        val result = mutableListOf<TechGroupData>()
        val file = resolveBaseFile(modFolder, baseName, "Techs.json") ?: return result
        try {
            val parsed = JsonReader().parse(
                removeTrailingCommasPublic(stripCommentsPublic(file.readString(Charsets.UTF_8.name()))))
            if (!parsed.isArray) return result
            for (entry in parsed) {
                val group = TechGroupData()
                for (field in entry) group.raw[field.name] = field.toPlain()
                val techs = entry.get("techs")
                if (techs != null && techs.isArray) {
                    for (techEntry in techs) {
                        val tech = TechData()
                        for (field in techEntry) tech.raw[field.name] = field.toPlain()
                        tech.name = tech.getString("name")
                        val existingUniques = tech.raw["uniques"]
                        if (existingUniques is List<*>) tech.uniques.addAll(existingUniques.filterIsInstance<String>())
                        val existingPrereqs = tech.raw["prerequisites"]
                        if (existingPrereqs is List<*>) tech.prerequisites.addAll(existingPrereqs.filterIsInstance<String>())
                        group.techs.add(tech)
                    }
                }
                result.add(group)
            }
        } catch (e: Exception) { }
        return result
    }

    fun deepCopyTechColumn(group: TechGroupData): TechGroupData {
        val copy = TechGroupData()
        group.raw.forEach { (k, v) -> copy.raw[k] = v }
        for (tech in group.techs) {
            val t = TechData()
            tech.raw.forEach { (k, v) -> t.raw[k] = v }
            t.name = tech.name
            t.uniques.addAll(tech.uniques)
            t.prerequisites.addAll(tech.prerequisites)
            copy.techs.add(t)
        }
        return copy
    }

    /** 解析基础规则集文件：内置规则集（internal）或已安装 base mod（mods 文件夹）
     *  @param baseName 规则集全名或 mod 名；null 时用编辑器元数据或默认 G&K */
    private fun resolveBaseFile(modFolder: FileHandle, baseName: String?, fileName: String): FileHandle? {
        val resolved = baseName?.takeIf { it.isNotBlank() }
            ?: readBaseRulesetChoice(modFolder).ifBlank { BaseRuleset.Civ_V_GnK.fullName }
        val internal = Gdx.files.internal("jsons/$resolved/$fileName")
        if (internal.exists()) return internal
        val modFile = UncivGame.Current.files.getModFolder(resolved).child("jsons/$fileName")
        return modFile.takeIf { it.exists() }
    }

    /** 从基础规则集读取政策分支（「从规则集复制」数据源，支持已安装 base mod） */
    fun loadBasePolicyBranches(modFolder: FileHandle): MutableList<PolicyBranchData> {
        return loadBasePolicyBranches(modFolder, readBaseRulesetChoice(modFolder).ifBlank { BaseRuleset.Civ_V_GnK.fullName })
    }

    /** 从指定规则集读取政策分支；null 时用编辑器元数据或默认 G&K */
    fun loadBasePolicyBranches(modFolder: FileHandle, baseName: String?): MutableList<PolicyBranchData> {
        val result = mutableListOf<PolicyBranchData>()
        val file = resolveBaseFile(modFolder, baseName, "Policies.json") ?: return result
        try {
            val parsed = JsonReader().parse(
                removeTrailingCommasPublic(stripCommentsPublic(file.readString(Charsets.UTF_8.name()))))
            if (!parsed.isArray) return result
            for (entry in parsed) {
                val branch = PolicyBranchData()
                for (field in entry) branch.raw[field.name] = field.toPlain()
                val branchUniques = branch.raw["uniques"]
                if (branchUniques is List<*>) branch.uniques.addAll(branchUniques.filterIsInstance<String>())
                val policyEntries = entry.get("policies")
                if (policyEntries != null && policyEntries.isArray) {
                    for (policyEntry in policyEntries) {
                        val policy = PolicyData()
                        for (field in policyEntry) policy.raw[field.name] = field.toPlain()
                        policy.name = policy.getString("name")
                        val existingUniques = policy.raw["uniques"]
                        if (existingUniques is List<*>) policy.uniques.addAll(existingUniques.filterIsInstance<String>())
                        val existingRequires = policy.raw["requires"]
                        if (existingRequires is List<*>) policy.requires.addAll(existingRequires.filterIsInstance<String>())
                        branch.policies.add(policy)
                    }
                }
                result.add(branch)
            }
        } catch (e: Exception) { }
        return result
    }

    fun deepCopyPolicyBranch(branch: PolicyBranchData): PolicyBranchData {
        val copy = PolicyBranchData()
        branch.raw.forEach { (k, v) -> copy.raw[k] = v }
        copy.uniques.addAll(branch.uniques)
        for (policy in branch.policies) {
            val p = PolicyData()
            policy.raw.forEach { (k, v) -> p.raw[k] = v }
            p.name = policy.name
            p.uniques.addAll(policy.uniques)
            p.requires.addAll(policy.requires)
            copy.policies.add(p)
        }
        return copy
    }

    /** 将 libGDX JsonValue 转为正确的 Java 类型（toPlain() 全返 String，导致类型丢失） */
    private fun jsonValueToPlain(jv: com.badlogic.gdx.utils.JsonValue): Any? {
        return when {
            jv.isNull -> null
            jv.isBoolean -> jv.asBoolean()
            jv.isLong -> jv.asLong()
            jv.isDouble -> jv.asDouble()
            jv.isString -> jv.asString()
            jv.isObject -> {
                val map = LinkedHashMap<String, Any?>()
                for (child in jv) map[child.name] = jsonValueToPlain(child)
                map
            }
            jv.isArray -> {
                val list = mutableListOf<Any?>()
                for (child in jv) list.add(jsonValueToPlain(child))
                list
            }
            else -> null
        }
    }

    fun loadObjects(modFolder: FileHandle, fileName: String): MutableList<ModObjectData> {
        val file = modFolder.child("jsons/$fileName")
        println("[ModEditor] loadObjects: ${file.path()} exists=${file.exists()}")
        if (!file.exists()) return mutableListOf()
        val result = mutableListOf<ModObjectData>()
        try {
            val text = file.readString(Charsets.UTF_8.name())
            val (clean, comments) = extractComments(text)
            val parsed = JsonReader().parse(removeTrailingCommasPublic(clean))
            if (!parsed.isArray) {
                println("[ModEditor] $fileName: 非数组结构，类型=${parsed.type()}")
                return result
            }
            println("[ModEditor] $fileName: 解析成功，数组元素=${parsed.size}")
            var index = 0
            for (entry in parsed) {
                val unit = ModObjectData()
                for (field in entry) unit.raw[field.name] = field.toPlain()
                unit.name = unit.getString("name")
                val existingUniques = unit.raw["uniques"]
                if (existingUniques is List<*>) unit.uniques.addAll(existingUniques.filterIsInstance<String>())
                unit.comment = comments.getOrElse(index) { "" }
                result.add(unit)
                index++
            }
        } catch (e: Exception) {
            // 解析失败时返回空列表，由界面提示用户（打印日志便于诊断）
            println("[ModEditor] 解析 $fileName 失败: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
        }
        return result
    }

    /**
     * 单位校验（对照官方 Units.schema.json + 4-Unit-related-JSON-files.md）：
     * - required: name / unitType；引用必须存在于对应 json（unitType/Techs/TileResources/Nations）
     * - rangedStrength 使用时必须同时有 strength（文档明确规则）
     * - 淘汰科技早于/等于需求科技 → 单位永远无法建造（用户要求）
     * - 同名单位
     * 返回 (消息, 是否错误)：true=阻止保存；false=警告（提示但可继续）。
     * 消息里直接嵌真实值到 [方括号]，展示时 tr() 会按占位符翻译（无翻译回退英文）。
     */
    fun validateUnit(modFolder: FileHandle, unit: ModObjectData, allUnits: List<ModObjectData>): List<Pair<String, Boolean>> {
        val problems = mutableListOf<Pair<String, Boolean>>()
        val name = unit.name.trim()
        if (name.isEmpty()) {
            problems.add("Unit name cannot be empty" to true)
        } else if (allUnits.count { it.name.trim().equals(name, ignoreCase = true) } > 1) {
            problems.add("Unit name [$name] is used by multiple units" to false)
        }

        val unitType = unit.getString("unitType")
        if (unitType.isBlank()) {
            problems.add("Please choose a unit type" to true)
        } else if (unitType !in getUnitTypes(modFolder)) {
            problems.add("Unit type [$unitType] not found in UnitTypes.json" to false)
        }

        // 对齐游戏 RulesetValidator: isMilitary(有 strength 或 rangedStrength) && strength==0 → 报错
        val strength = unit.getIntText("strength")
        val rangedStrength = unit.getIntText("rangedStrength")
        if ((strength.toIntOrNull() ?: 0) == 0 && (rangedStrength.toIntOrNull() ?: 0) > 0)
            problems.add("Military units must have a strength value" to true)

        val requiredTech = unit.getString("requiredTech")
        val obsoleteTech = unit.getString("obsoleteTech")
        val techs = getTechs(modFolder)
        if (requiredTech.isNotBlank() && requiredTech !in techs)
            problems.add("Tech [$requiredTech] not found in Techs.json" to false)
        if (obsoleteTech.isNotBlank() && obsoleteTech !in techs)
            problems.add("Tech [$obsoleteTech] not found in Techs.json" to false)
        if (requiredTech.isNotBlank() && obsoleteTech.isNotBlank()) {
            if (requiredTech == obsoleteTech) {
                problems.add("Required tech and obsolete tech are the same ([$requiredTech]) - the unit can never be built" to false)
            } else {
                val requiredIndex = techs.indexOf(requiredTech)
                val obsoleteIndex = techs.indexOf(obsoleteTech)
                // getTechs 已按（时代, 列）排序 → 位置即科技树顺序
                if (requiredIndex >= 0 && obsoleteIndex >= 0 && obsoleteIndex < requiredIndex)
                    problems.add("Obsolete tech [$obsoleteTech] comes before required tech [$requiredTech] - the unit can never be built" to false)
            }
        }

        val requiredResource = unit.getString("requiredResource")
        if (requiredResource.isNotBlank() && requiredResource !in getResources(modFolder))
            problems.add("Resource [$requiredResource] not found in TileResources.json" to false)

        val uniqueTo = unit.getString("uniqueTo")
        if (uniqueTo.isNotBlank() && uniqueTo !in getNations(modFolder))
            problems.add("Nation [$uniqueTo] not found in Nations.json" to false)

        problems.addAll(checkUniquesRecognized(unit.uniques))
        return problems
    }

    /**
     * 建筑校验（对照官方 Buildings.schema.json）：
     * required: name；引用必须存在（Techs/Buildings/TileResources/Nations）；
     * 世界奇观与国家奇观不能同时勾选；同名建筑。
     * 返回 (消息, 是否错误)，与 validateUnit 同语义。
     */
    fun validateBuilding(modFolder: FileHandle, building: ModObjectData, allBuildings: List<ModObjectData>): List<Pair<String, Boolean>> {
        val problems = mutableListOf<Pair<String, Boolean>>()
        val name = building.name.trim()
        if (name.isEmpty()) {
            problems.add("Building name cannot be empty" to true)
        } else if (allBuildings.count { it.name.trim().equals(name, ignoreCase = true) } > 1) {
            problems.add("Building name [$name] is used by multiple buildings" to false)
        }

        val requiredTech = building.getString("requiredTech")
        val techs = getTechs(modFolder)
        if (requiredTech.isNotBlank() && requiredTech !in techs)
            problems.add("Tech [$requiredTech] not found in Techs.json" to false)

        val requiredBuilding = building.getString("requiredBuilding")
        val buildings = getBuildings(modFolder)
        if (requiredBuilding.isNotBlank() && requiredBuilding !in buildings)
            problems.add("Building [$requiredBuilding] not found in Buildings.json" to false)
        val replaces = building.getString("replaces")
        if (replaces.isNotBlank() && replaces !in buildings)
            problems.add("Building [$replaces] not found in Buildings.json" to false)

        val requiredResource = building.getString("requiredResource")
        if (requiredResource.isNotBlank() && requiredResource !in getResources(modFolder))
            problems.add("Resource [$requiredResource] not found in TileResources.json" to false)

        val uniqueTo = building.getString("uniqueTo")
        if (uniqueTo.isNotBlank() && uniqueTo !in getNations(modFolder))
            problems.add("Nation [$uniqueTo] not found in Nations.json" to false)

        if (building.raw["isWonder"] == true && building.raw["isNationalWonder"] == true)
            problems.add("A building cannot be both a world wonder and a national wonder" to false)

        problems.addAll(checkUniquesRecognized(building.uniques))
        return problems
    }

    /**
     * 文明校验（对照官方 Nations.json 文档）：名称必填且不重复；
     * 主要文明（无城邦类型）应有领袖；personality/城邦类型/宗教/胜利类型引用必须存在。
     */
    fun validateNation(modFolder: FileHandle, nation: ModObjectData, allNations: List<ModObjectData>): List<Pair<String, Boolean>> {
        val problems = mutableListOf<Pair<String, Boolean>>()
        val name = nation.name.trim()
        if (name.isEmpty()) {
            problems.add("Nation name cannot be empty" to true)
        } else if (allNations.count { it.name.trim().equals(name, ignoreCase = true) } > 1) {
            problems.add("Nation name [$name] is used by multiple nations" to false)
        }

        val cityStateType = nation.getString("cityStateType")
        if (cityStateType.isBlank()) {
            // 主要文明需要领袖
            if (nation.getString("leaderName").isBlank())
                problems.add("Major civilizations should have a leader name" to false)
        } else if (cityStateType !in getCityStateTypes(modFolder)) {
            problems.add("City state type [$cityStateType] not found in CityStateTypes.json" to false)
        }

        // outerColor 是游戏必需的 lateinit 字段，缺失会导致整个 mod 加载崩溃（Mods 列表不显示）
        val outerColorRaw = nation.raw["outerColor"]
        val hasOuterColor = outerColorRaw is List<*> && outerColorRaw.isNotEmpty()
        if (!hasOuterColor)
            problems.add("outerColor is required (game crashes loading the mod if missing)" to true)

        val personality = nation.getString("personality")
        if (personality.isNotBlank() && personality !in getPersonalities(modFolder))
            problems.add("Personality [$personality] not found in Personalities.json" to false)

        val religion = nation.getString("favoredReligion")
        if (religion.isNotBlank() && religion !in getReligions(modFolder))
            problems.add("Religion [$religion] not found in Religions.json" to false)

        val victoryType = nation.getString("preferredVictoryType")
        if (victoryType.isNotBlank() && victoryType !in getVictoryTypes(modFolder))
            problems.add("Victory type [$victoryType] not found in VictoryTypes.json" to false)

        problems.addAll(checkUniquesRecognized(nation.uniques))
        return problems
    }

    fun saveUnits(modFolder: FileHandle, units: List<ModObjectData>) = saveObjects(modFolder, "Units.json", units)

    fun saveBuildings(modFolder: FileHandle, buildings: List<ModObjectData>) = saveObjects(modFolder, "Buildings.json", buildings)

    // ------------------------------------------------------------------
    // UnitPromotions / UnitTypes / UnitNameGroups 读写（复用 loadObjects/saveObjects）
    // ------------------------------------------------------------------

    fun loadPromotions(modFolder: FileHandle) = loadObjects(modFolder, "UnitPromotions.json")
    fun savePromotions(modFolder: FileHandle, items: List<ModObjectData>) = saveObjects(modFolder, "UnitPromotions.json", items)
    fun validatePromotion(modFolder: FileHandle, promo: ModObjectData, allPromos: List<ModObjectData>): List<Pair<String, Boolean>> {
        val problems = mutableListOf<Pair<String, Boolean>>()
        val name = promo.name.trim()
        if (name.isEmpty()) {
            problems.add("Promotion name cannot be empty" to true)
        } else if (allPromos.count { it.name.trim().equals(name, ignoreCase = true) } > 1) {
            problems.add("Promotion name [$name] is used by multiple promotions" to false)
        }
        val unitTypes = promo.getStringList("unitTypes")
        val validTypes = getUnitTypes(modFolder)
        for (ut in unitTypes) {
            if (ut !in validTypes) problems.add("Unit type [$ut] not found in UnitTypes.json" to false)
        }
        val prereqs = promo.getStringList("prerequisites")
        val allNames = allPromos.map { it.name.trim() }.toSet()
        for (p in prereqs) {
            if (p !in allNames) problems.add("Prerequisite promotion [$p] not found" to false)
        }
        problems.addAll(checkUniquesRecognized(promo.uniques))
        return problems
    }

    fun loadUnitTypes(modFolder: FileHandle) = loadObjects(modFolder, "UnitTypes.json")
    fun saveUnitTypes(modFolder: FileHandle, items: List<ModObjectData>) = saveObjects(modFolder, "UnitTypes.json", items)

    // ------------------------------------------------------------------
    // Terrains.json 读写（基础地形/地形特征/自然奇观）
    // 必填字段：name, type（Land/Water/TerrainFeature/NaturalWonder，游戏 lateinit 必需）
    // ------------------------------------------------------------------

    fun loadTerrains(modFolder: FileHandle) = loadObjects(modFolder, "Terrains.json")
    fun saveTerrains(modFolder: FileHandle, items: List<ModObjectData>) = saveObjects(modFolder, "Terrains.json", items)
    fun validateTerrain(modFolder: FileHandle, terrain: ModObjectData, allTerrains: List<ModObjectData>): List<Pair<String, Boolean>> {
        val problems = mutableListOf<Pair<String, Boolean>>()
        val name = terrain.name.trim()
        if (name.isEmpty()) {
            problems.add("Terrain name cannot be empty" to true)
        } else if (allTerrains.count { it.name.trim().equals(name, ignoreCase = true) } > 1) {
            problems.add("Terrain name [$name] is used by multiple terrains" to false)
        }

        val type = terrain.getString("type")
        val validTypes = listOf("Land", "Water", "TerrainFeature", "NaturalWonder")
        if (type.isBlank()) {
            // 游戏里 Terrain.type 是 lateinit 必需字段，缺失会导致整个 mod 加载崩溃（Mods 列表不显示）
            problems.add("type is required (Land/Water/TerrainFeature/NaturalWonder - game crashes loading the mod if missing)" to true)
        } else if (type !in validTypes) {
            problems.add("type must be one of Land/Water/TerrainFeature/NaturalWonder (got [$type])" to true)
        }

        if (type == "TerrainFeature" || type == "NaturalWonder") {
            val occursOn = terrain.getStringList("occursOn")
            if (occursOn.isEmpty())
                problems.add("occursOn is recommended for terrain features / natural wonders (base terrain it can be placed on)" to false)
            for (t in occursOn) {
                if (t !in getTerrains(modFolder))
                    problems.add("occursOn entry [$t] not found in Terrains.json" to false)
            }
        }

        val turnsInto = terrain.getString("turnsInto")
        if (turnsInto.isNotBlank() && turnsInto !in getTerrains(modFolder))
            problems.add("turnsInto [$turnsInto] not found in Terrains.json" to false)

        val rgb = terrain.raw["RGB"]
        if (rgb != null && !(rgb is List<*> && rgb.size == 3 && rgb.all { (it as? Number)?.toInt() in 0..255 }))
            problems.add("RGB must be a list of three integers in the 0..255 range" to true)

        problems.addAll(checkUniquesRecognized(terrain.uniques))
        return problems
    }

    fun validateUnitType(modFolder: FileHandle, ut: ModObjectData, allUTs: List<ModObjectData>): List<Pair<String, Boolean>> {
        val problems = mutableListOf<Pair<String, Boolean>>()
        val name = ut.name.trim()
        if (name.isEmpty()) {
            problems.add("Unit type name cannot be empty" to true)
        } else if (allUTs.count { it.name.trim().equals(name, ignoreCase = true) } > 1) {
            problems.add("Unit type name [$name] is used by multiple unit types" to false)
        }
        val mt = ut.getString("movementType")
        if (mt.isBlank()) {
            problems.add("movementType is required (Land/Water/Air)" to true)
        } else if (mt !in listOf("Land", "Water", "Air")) {
            problems.add("movementType must be Land, Water or Air (got [$mt])" to true)
        }
        problems.addAll(checkUniquesRecognized(ut.uniques))
        return problems
    }

    // ------------------------------------------------------------------
    // Specialists.json 读写（专业人员）
    // Specialist extends NamedStats → name 是 lateinit 必需字段（缺失会让 mod 加载崩溃）
    // 字段：name + 7 项 stats（production/food/gold/science/culture/happiness/faith）
    //        + color (ArrayList<Int>) + greatPersonPoints (Counter<String>)
    // ------------------------------------------------------------------

    fun loadSpecialists(modFolder: FileHandle) = loadObjects(modFolder, "Specialists.json")
    fun saveSpecialists(modFolder: FileHandle, items: List<ModObjectData>) = saveObjects(modFolder, "Specialists.json", items)
    fun validateSpecialist(modFolder: FileHandle, specialist: ModObjectData, allSpecialists: List<ModObjectData>): List<Pair<String, Boolean>> {
        val problems = mutableListOf<Pair<String, Boolean>>()
        val name = specialist.name.trim()
        if (name.isEmpty()) {
            // NamedStats.name 是 lateinit，缺失会导致游戏加载 mod 时崩溃（Mods 列表消失）
            problems.add("Specialist name cannot be empty" to true)
        } else if (allSpecialists.count { it.name.trim().equals(name, ignoreCase = true) } > 1) {
            problems.add("Specialist name [$name] is used by multiple specialists" to false)
        }
        // color 校验：三个 0..255 整数（必填）
        val color = specialist.raw["color"]
        if (color == null) {
            problems.add("color is required (RGB list of three integers)" to true)
        } else if (!(color is List<*> && color.size == 3 && color.all { (it as? Number)?.toInt() in 0..255 })) {
            problems.add("color must be a list of three integers in the 0..255 range" to true)
        }
        // greatPersonPoints 校验：key 必须存在于基础规则集+模组的伟人列表
        val gpp = specialist.raw["greatPersonPoints"]
        if (gpp is Map<*, *>) {
            val greatPeople = getGreatPeople(modFolder)
            for (key in gpp.keys) {
                val gpName = key.toString()
                if (gpName !in greatPeople)
                    problems.add("Great person [$gpName] not found in units (needs 'Great Person -' unique)" to false)
            }
        }
        return problems
    }

    // ------------------------------------------------------------------
    // Beliefs.json 读写（信条）
    // Belief extends RulesetObject → name 是继承的 var（空字符串不崩溃但无意义）
    // 字段：name, type (Pantheon/Founder/Follower/Enhancer), uniques, civilopediaText
    // ------------------------------------------------------------------

    fun loadBeliefs(modFolder: FileHandle) = loadObjects(modFolder, "Beliefs.json")
    fun saveBeliefs(modFolder: FileHandle, items: List<ModObjectData>) = saveObjects(modFolder, "Beliefs.json", items)
    fun validateBelief(modFolder: FileHandle, belief: ModObjectData, allBeliefs: List<ModObjectData>): List<Pair<String, Boolean>> {
        val problems = mutableListOf<Pair<String, Boolean>>()
        val name = belief.name.trim()
        if (name.isEmpty()) {
            problems.add("Belief name cannot be empty" to true)
        } else if (allBeliefs.count { it.name.trim().equals(name, ignoreCase = true) } > 1) {
            problems.add("Belief name [$name] is used by multiple beliefs" to false)
        }
        val type = belief.getString("type")
        val validTypes = listOf("Pantheon", "Founder", "Follower", "Enhancer")
        if (type.isBlank()) {
            // Belief.type 无默认值，缺失导致游戏加载时 type=None，属于无效数据
            problems.add("type is required (Pantheon/Founder/Follower/Enhancer)" to true)
        } else if (type !in validTypes) {
            problems.add("type must be one of Pantheon/Founder/Follower/Enhancer (got [$type])" to true)
        }
        problems.addAll(checkUniquesRecognized(belief.uniques))
        return problems
    }

    // ------------------------------------------------------------------
    // Personalities.json 读写（AI 性格）
    // Personality extends RulesetObject → name 是继承的 var
    // 字段：name, preferredVictoryType, 7 项 stats (production..faith, Float 0-10, 默认 5)
    //       8 项 behaviors (military..denounceWillingness, Float 0-10, 默认 5)
    //       priorities (Map<String, Int>), uniques, civilopediaText
    // ------------------------------------------------------------------

    fun loadPersonalities(modFolder: FileHandle) = loadObjects(modFolder, "Personalities.json")
    fun savePersonalities(modFolder: FileHandle, items: List<ModObjectData>) = saveObjects(modFolder, "Personalities.json", items)
    fun validatePersonality(modFolder: FileHandle, item: ModObjectData, allItems: List<ModObjectData>): List<Pair<String, Boolean>> {
        val problems = mutableListOf<Pair<String, Boolean>>()
        val name = item.name.trim()
        if (name.isEmpty()) {
            problems.add("Personality name cannot be empty" to true)
        } else if (allItems.count { it.name.trim().equals(name, ignoreCase = true) } > 1) {
            problems.add("Personality name [$name] is used by multiple personalities" to false)
        }
        val victoryType = item.getString("preferredVictoryType")
        if (victoryType.isNotBlank() && victoryType !in getVictoryTypes(modFolder))
            problems.add("Victory type [$victoryType] not found in VictoryTypes.json" to false)
        // stats/behaviors 范围 0-10
        val floatFields = listOf("production", "food", "gold", "science", "culture", "happiness", "faith",
            "military", "aggressive", "declareWar", "commerce", "diplomacy", "loyal", "expansion", "denounceWillingness")
        for (field in floatFields) {
            val v = item.raw[field] as? Number
            if (v != null && (v.toFloat() < 0f || v.toFloat() > 10f))
                problems.add("$field must be between 0 and 10 (got [${v.toFloat()}])" to false)
        }
        // priorities 校验：value 必须是整数
        val priorities = item.raw["priorities"]
        if (priorities is Map<*, *>) {
            for ((key, value) in priorities) {
                if (key !is String) continue
                if (value !is Number)
                    problems.add("Priority for [$key] must be a number" to false)
            }
        }
        problems.addAll(checkUniquesRecognized(item.uniques))
        return problems
    }

    // ------------------------------------------------------------------
    // CityStateTypes.json 读写（城邦类型）
    // CityStateType extends RulesetObject → name 是继承的 var
    // 字段：name, friendBonusUniques, allyBonusUniques, uniques, color (RGB)
    // ------------------------------------------------------------------

    fun loadCityStateTypes(modFolder: FileHandle) = loadObjects(modFolder, "CityStateTypes.json")
    fun saveCityStateTypes(modFolder: FileHandle, items: List<ModObjectData>) = saveObjects(modFolder, "CityStateTypes.json", items)
    fun validateCityStateType(modFolder: FileHandle, item: ModObjectData, allItems: List<ModObjectData>): List<Pair<String, Boolean>> {
        val problems = mutableListOf<Pair<String, Boolean>>()
        val name = item.name.trim()
        if (name.isEmpty()) {
            problems.add("City state type name cannot be empty" to true)
        } else if (allItems.count { it.name.trim().equals(name, ignoreCase = true) } > 1) {
            problems.add("City state type name [$name] is used by multiple city state types" to false)
        }
        // color 校验：三个 0..255 整数
        val color = item.raw["color"]
        if (color != null && !(color is List<*> && color.size == 3 && color.all { (it as? Number)?.toInt() in 0..255 }))
            problems.add("color must be a list of three integers in the 0..255 range" to true)
        // friendBonusUniques / allyBonusUniques 内容校验
        for (u in item.getStringList("friendBonusUniques")) {
            if (Unique(u).type == null)
                problems.add("Friend bonus unique [${u.take(60)}] is not recognized by the game" to false)
        }
        for (u in item.getStringList("allyBonusUniques")) {
            if (Unique(u).type == null)
                problems.add("Ally bonus unique [${u.take(60)}] is not recognized by the game" to false)
        }
        problems.addAll(checkUniquesRecognized(item.uniques))
        return problems
    }

    fun loadUnitNameGroups(modFolder: FileHandle) = loadObjects(modFolder, "UnitNameGroups.json")
    fun saveUnitNameGroups(modFolder: FileHandle, items: List<ModObjectData>) = saveObjects(modFolder, "UnitNameGroups.json", items)
    fun validateUnitNameGroup(modFolder: FileHandle, group: ModObjectData, allGroups: List<ModObjectData>): List<Pair<String, Boolean>> {
        val problems = mutableListOf<Pair<String, Boolean>>()
        val name = group.name.trim()
        if (name.isEmpty()) {
            problems.add("Name group name cannot be empty" to true)
        } else if (allGroups.count { it.name.trim().equals(name, ignoreCase = true) } > 1) {
            problems.add("Name group [$name] is used by multiple groups" to false)
        }
        val names = group.getStringList("unitNames")
        if (names.isEmpty()) {
            problems.add("Unit name group should have at least one name" to false)
        }
        problems.addAll(checkUniquesRecognized(group.uniques))
        return problems
    }

    // ------------------------------------------------------------------
    // GlobalUniques.json 读写（单对象：name + uniques + civilopediaText）
    // ------------------------------------------------------------------

    fun loadGlobalUniques(modFolder: FileHandle): ModObjectData {
        val result = ModObjectData()
        result.name = "Global Uniques"
        result.raw["name"] = result.name
        val file = modFolder.child("jsons/GlobalUniques.json")
        if (!file.exists()) return result
        try {
            var text = file.readString(Charsets.UTF_8.name())
            text = text.lineSequence()
                .filterNot { it.trimStart().startsWith("//") }
                .joinToString("\n")
                .replace(Regex(",\\s*([}\\]])"), "$1")
            val parsed = JsonReader().parse(text)
            if (!parsed.isObject) return result
            for (child in parsed) result.raw[child.name] = child.toPlain()
            val existingUniques = result.raw["uniques"]
            if (existingUniques is List<*>) result.uniques.addAll(existingUniques.filterIsInstance<String>())
        } catch (e: Exception) {
            // 解析失败：返回空表单，保存时会覆盖
        }
        return result
    }

    fun saveGlobalUniques(modFolder: FileHandle, data: ModObjectData) {
        val dir = modFolder.child("jsons")
        if (!dir.exists()) dir.mkdirs()
        val file = dir.child("GlobalUniques.json")
        if (file.exists()) file.copyTo(dir.child("GlobalUniques.json.bak"))
        data.syncUniques()
        data.raw["name"] = "Global Uniques"
        val sb = StringBuilder()
        appendJson(sb, data.raw, 0)
        file.writeString(sb.toString(), false, Charsets.UTF_8.name())
        mirrorToDebugFolder(modFolder)
    }

    fun validateGlobalUniques(data: ModObjectData): List<Pair<String, Boolean>> =
        checkUniquesRecognized(data.uniques) + checkUniquesRecognized(data.getStringList("unitUniques"))

    fun saveObjects(modFolder: FileHandle, fileName: String, objects: List<ModObjectData>) {
        val dir = modFolder.child("jsons")
        if (!dir.exists()) dir.mkdirs()
        val file = dir.child(fileName)
        if (file.exists()) file.copyTo(dir.child("$fileName.bak"))

        val sb = StringBuilder("[\n")
        for ((index, unit) in objects.withIndex()) {
            for (line in unit.comment.lines()) {
                if (line.isBlank()) sb.append("//\n") else sb.append("// ").append(line).append('\n')
            }
            appendJson(sb, unit.raw, 1)
            if (index != objects.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append(']')
        file.writeString(sb.toString(), false, Charsets.UTF_8.name())
        mirrorToDebugFolder(modFolder)
    }

    // ------------------------------------------------------------------
    // ModOptions.json 读写
    // ------------------------------------------------------------------

    fun loadModOptions(modFolder: FileHandle): ModOptionsData {
        val result = ModOptionsData()
        val file = modFolder.child("jsons/ModOptions.json")
        if (!file.exists()) return result
        try {
            val text = stripCommentsPublic(
                removeTrailingCommasPublic(file.readString(Charsets.UTF_8.name())))
            val parsed = JsonReader().parse(text)
            if (!parsed.isObject) return result
            for (child in parsed) result.raw[child.name] = child.toPlain()
            val existingUniques = result.raw["uniques"]
            if (existingUniques is List<*>) result.uniques.addAll(existingUniques.filterIsInstance<String>())
        } catch (e: Exception) {
            // 解析失败：返回空表单，保存时会覆盖
        }
        return result
    }

    fun saveModOptions(modFolder: FileHandle, data: ModOptionsData) {
        val dir = modFolder.child("jsons")
        if (!dir.exists()) dir.mkdirs()
        val file = dir.child("ModOptions.json")
        if (file.exists()) file.copyTo(dir.child("ModOptions.json.bak"))
        data.syncRaw()
        val sb = StringBuilder()
        appendJson(sb, data.raw, 0)
        file.writeString(sb.toString(), false, Charsets.UTF_8.name())
        mirrorToDebugFolder(modFolder)
    }

    // ------------------------------------------------------------------
    // 模组元数据
    // ------------------------------------------------------------------

    fun readIsBaseRuleset(modFolder: FileHandle): Boolean {
        val file = modFolder.child("jsons/ModOptions.json")
        if (!file.exists()) return false
        return try {
            val text = stripCommentsPublic(
                removeTrailingCommasPublic(file.readString(Charsets.UTF_8.name())))
            val root = JsonReader().parse(text)
            // 官方格式：isBaseRuleset 在顶层；兼容旧 mod 的 modOptions 包装
            if (root.has("isBaseRuleset")) root.getBoolean("isBaseRuleset", false)
            else root.get("modOptions")?.getBoolean("isBaseRuleset", false) ?: false
        } catch (e: Exception) { false }
    }

    fun readAuthor(modFolder: FileHandle): String {
        val file = modFolder.child("jsons/ModOptions.json")
        if (!file.exists()) return ""
        return try {
            val text = stripCommentsPublic(
                removeTrailingCommasPublic(file.readString(Charsets.UTF_8.name())))
            val root = JsonReader().parse(text)
            // 官方格式：author 在顶层；兼容旧 mod 的 modOptions 包装
            if (root.has("author")) root.getString("author", "")
            else root.get("modOptions")?.getString("author", "") ?: ""
        } catch (e: Exception) { "" }
    }

    /** 编辑器元数据：模组 jsons/ 下的 .editor-meta.json（游戏不读；根目录旧位置会迁移，因为游戏会报错） */
    private fun metaFile(modFolder: FileHandle): FileHandle {
        val jsons = modFolder.child("jsons/.editor-meta.json")
        if (jsons.exists()) return jsons
        val legacy = modFolder.child(".editor-meta.json")
        if (legacy.exists()) {
            try {
                modFolder.child("jsons").mkdirs()
                legacy.moveTo(jsons)
                return jsons
            } catch (e: Exception) { return legacy }
        }
        return jsons
    }

    fun readBaseRulesetChoice(modFolder: FileHandle): String {
        val file = metaFile(modFolder)
        if (file.exists()) {
            return try {
                JsonReader().parse(file.readString(Charsets.UTF_8.name())).getString("baseRuleset", "")
            } catch (e: Exception) { "" }
        }
        return ""
    }

    /** 编辑器元数据：单位图集选择（.editor-meta.json，兼容旧 class 标签格式） */
    fun readUnitSetChoice(modFolder: FileHandle): String {
        val file = metaFile(modFolder)
        if (!file.exists()) return ""
        return try {
            val root = JsonReader().parse(file.readString(Charsets.UTF_8.name()))
            val v = root.get("unitSet")
            when {
                v == null || v.isNull -> ""
                v.isObject -> v.getString("value", "")  // 旧格式 class 标签
                else -> v.asString()
            }
        } catch (e: Exception) { "" }
    }

    /** 写编辑器元数据（干净 JSON，顺带修复旧 class 标签格式）；合并保留 countableHistory */
    fun writeUnitSetChoice(modFolder: FileHandle, unitSet: String) {
        val meta = LinkedHashMap<String, Any?>()
        meta["baseRuleset"] = readBaseRulesetChoice(modFolder)
        val history = readCountableHistory(modFolder)
        if (history.isNotEmpty()) meta["countableHistory"] = history
        if (unitSet.isNotBlank()) meta["unitSet"] = unitSet
        writeMetaMap(modFolder, meta)
    }

    /** countable 最近使用（最新在前，2026-08-19 用户要求） */
    fun readCountableHistory(modFolder: FileHandle): List<String> {
        val file = metaFile(modFolder)
        if (!file.exists()) return emptyList()
        return try {
            val root = JsonReader().parse(file.readString(Charsets.UTF_8.name()))
            val v = root.get("countableHistory")
            when {
                v == null || v.isNull -> emptyList()
                v.isArray -> v.map { it.asString() }
                else -> emptyList()
            }
        } catch (e: Exception) { emptyList() }
    }

    fun writeCountableHistory(modFolder: FileHandle, history: List<String>) {
        val meta = LinkedHashMap<String, Any?>()
        meta["baseRuleset"] = readBaseRulesetChoice(modFolder)
        val unitSet = readUnitSetChoice(modFolder)
        if (unitSet.isNotBlank()) meta["unitSet"] = unitSet
        if (history.isNotEmpty()) meta["countableHistory"] = history
        writeMetaMap(modFolder, meta)
    }

    private fun writeMetaMap(modFolder: FileHandle, meta: LinkedHashMap<String, Any?>) {
        val sb = StringBuilder()
        appendJson(sb, meta, 0)
        metaFile(modFolder).writeString(sb.toString(), false, Charsets.UTF_8.name())
    }

    private fun writeMeta(modFolder: FileHandle, baseRuleset: String) {
        val meta = LinkedHashMap<String, Any?>()
        meta["baseRuleset"] = baseRuleset
        val sb = StringBuilder()
        appendJson(sb, meta, 0)
        metaFile(modFolder).writeString(sb.toString(), false, Charsets.UTF_8.name())
    }

    /** 新建模组：创建目录 + jsons/ModOptions.json + jsons/Units.json + .editor-meta.json */
    /** 编辑器用的 mod 根目录: 安卓优先外部可见目录 (用户文件管理器可见), 桌面/不可用时用内部 */
    fun getModFolderForEditor(name: String): com.badlogic.gdx.files.FileHandle {
        val visible = UncivGame.Current.getVisibleModsFolder()
        return (visible ?: UncivGame.Current.files.getModsFolder()).child(name)
    }

    fun createNewMod(name: String, author: String, isBaseRuleset: Boolean, baseRuleset: String): FileHandle {
        val folder = getModFolderForEditor(name)
        folder.child("jsons").mkdirs()
        val modOptions = LinkedHashMap<String, Any?>()
        modOptions["isBaseRuleset"] = isBaseRuleset
        if (author.isNotBlank()) modOptions["author"] = author
        val sb = StringBuilder()
        appendJson(sb, modOptions, 0)
        folder.child("jsons/ModOptions.json").writeString(
            sb.toString(), false, Charsets.UTF_8.name())
        folder.child("jsons/Units.json").writeString("[]\n", false, Charsets.UTF_8.name())
        if (!isBaseRuleset) writeMeta(folder, baseRuleset)
        mirrorToDebugFolder(folder)
        return folder
    }

    // ------------------------------------------------------------------
    // 桌面调试镜像：~/Desktop/mods 存在时，保存后把整个模组复制一份过去（只写不读，方便调试）
    // ------------------------------------------------------------------

    private fun debugMirrorRoot(): FileHandle? {
        val root = Gdx.files.absolute(System.getProperty("user.home") + "/Desktop/mods")
        return root.takeIf { it.exists() && it.isDirectory }
    }

    /** 检查词条是否被游戏官方解析器识别（缺方括号/非官方词条 → 警告，不阻止保存） */
    fun checkUniquesRecognized(uniques: List<String>): List<Pair<String, Boolean>> {
        val problems = mutableListOf<Pair<String, Boolean>>()
        for (raw in uniques) {
            if (Unique(raw).type == null)
                problems.add("Unique [${raw.take(60)}] is not recognized by the game" to false)
        }
        return problems
    }

    // ------------------------------------------------------------------
    // 游戏级校验（保存写盘后调用，与「设置-定位模组错误」同一套 RulesetValidator）
    // ------------------------------------------------------------------

    /** 保存后用游戏自带 RulesetValidator 校验整个模组（此时磁盘为最新数据）。
     *  返回 (消息, 是否错误, 所属文件类型；null=模组级/其他文件)。 */
    fun runGameValidation(modFolder: FileHandle): List<Triple<String, Boolean, String?>> {
        val modName = modFolder.name()
        val result = mutableListOf<Triple<String, Boolean, String?>>()
        try {
            // 外部 mod: 先同步到内部目录 — 游戏 RulesetCache 只认内部 mods (filesDir/mods),
            // 不同步的话 reloadSingleRuleset 报 "No such mod"
            val internalMod = UncivGame.Current.files.getModFolder(modName)
            if (modFolder.file().absolutePath != internalMod.file().absolutePath) {
                try {
                    // 原子同步: 先复制到临时目录, 成功后再替换内部 — 直接 deleteDirectory+copyTo
                    // 若复制失败, 内部被删空 → 游戏里 mod 内容全部丢失 ("进游戏都没了")
                    val temp = UncivGame.Current.files.getModFolder(modName + ".tmp-sync")
                    if (temp.exists()) temp.deleteDirectory()
                    modFolder.copyTo(temp)
                    if (internalMod.exists()) internalMod.deleteDirectory()
                    temp.moveTo(internalMod)
                } catch (e: Exception) {
                    result.add(Triple("同步外部模组到内部失败: ${e.message}", true, null))
                }
            }
            // 重新加载该模组到缓存（否则校验的是保存前的旧数据）
            val reloadErrors = RulesetCache.reloadSingleRuleset(modName)
            for (line in reloadErrors) result.add(Triple(line, true, null))
            // 保存后全量重载 (等效大退): 游戏内 Mods 列表/新游戏界面立即用最新内容,
            // 避免"编辑器保存了但进游戏没生效" (用户大退才生效的缓存刷新问题)
            try {
                RulesetCache.loadRulesets()
                com.unciv.ui.images.ImageGetter.reloadImages()
                // 模组翻译刷新: loadRulesets 不重读翻译 — 改文本后游戏内仍显示旧翻译,
                // 必须重读当前语言翻译 (含 mod 翻译) — 否则"保存后要大退才生效"
                try {
                    com.unciv.UncivGame.Current.translations.tryReadTranslationForCurrentLanguage()
                } catch (ignored: Throwable) {
                }
                // 诊断日志: 保存后缓存里该模组各文件对象数 (定位"保存后开新局没内容"问题)
                try {
                    val rc = RulesetCache.get(modName)
                    if (rc != null) {
                        println("[ModEditor] 保存后 reload OK: mod=$modName units=" + rc.units.size
                            + " buildings=" + rc.buildings.size + " nations=" + rc.nations.size)
                    } else {
                        println("[ModEditor] 保存后 reload 失败: mod=$modName 不在 RulesetCache!")
                    }
                } catch (ignored: Throwable) {
                }
            } catch (ignored: Throwable) {
            }
            val baseChoice = readBaseRulesetChoice(modFolder).ifBlank { BaseRuleset.Civ_V_GnK.fullName }
            val (_, errors) = RulesetCache.checkCombinedModLinks(linkedSetOf(modName), baseChoice)
            for (e in errors) {
                if (e.errorSeverityToReport <= RulesetErrorSeverity.WarningOptionsOnly) continue
                val isError = e.errorSeverityToReport >= RulesetErrorSeverity.ErrorOptionsOnly
                val file = when (e.sourceObject) {
                    is BaseUnit -> "Units.json"
                    is Building -> "Buildings.json"
                    is Technology -> "Techs.json"
                    is Nation -> "Nations.json"
                    is Policy, is PolicyBranch -> "Policies.json"
                    is Specialist -> "Specialists.json"
                    is Belief -> "Beliefs.json"
                    is com.unciv.models.ruleset.nation.Personality -> "Personalities.json"
                    is com.unciv.models.ruleset.nation.CityStateType -> "CityStateTypes.json"
                    else -> null
                }
                result.add(Triple(e.text, isError, file))
            }
        } catch (e: Exception) {
            result.add(Triple("Game validation failed:".tr() + " " + (e.message ?: ""), true, null))
        }
        return result
    }

    /** 按当前编辑的文件过滤：本文件类型的错误 + 模组级/其他文件的错误 */
    fun filterGameProblems(problems: List<Triple<String, Boolean, String?>>, ownFile: String?):
            List<Triple<String, Boolean, String?>> =
        problems.filter { it.third == null || it.third == ownFile }
            // 忽略 Atlases.json 资源打包类错误（编辑器只管规则集数据，图集打包是发布工具的事）
            .filter { !it.first.contains("Atlases.json") }
            .filter { !it.first.contains("atlas file") }

    /** 游戏校验失败时回滚到保存前的 .bak（保存写盘前会自动备份） */
    fun rollbackFile(modFolder: FileHandle, fileName: String) {
        val dir = modFolder.child("jsons")
        val file = dir.child(fileName)
        val bak = dir.child("$fileName.bak")
        if (bak.exists()) {
            if (file.exists()) file.delete()
            bak.copyTo(file)
        }
        mirrorToDebugFolder(modFolder)
    }

    fun mirrorToDebugFolder(modFolder: FileHandle) {
        val targetRoot = debugMirrorRoot() ?: return
        val target = targetRoot.child(modFolder.name())
        copyTree(modFolder, target)
    }

    private fun copyTree(source: FileHandle, target: FileHandle) {
        if (source.isDirectory) {
            target.mkdirs()
            for (child in source.list()) {
                if (child.name().endsWith(".bak")) continue  // 调试副本不带 .bak
                copyTree(child, target.child(child.name()))
            }
        } else {
            target.parent().mkdirs()
            source.copyTo(target)
        }
    }

    // ------------------------------------------------------------------
    // 干净 JSON 序列化（手写，不用 Gdx Json）
    // Gdx Json 对 Map<String, Any?> 会输出 {"class": "java.lang.Boolean", "value": ...} 形式的 class 标签，
    // 游戏解析器读不了这种文件 → 模组在游戏里不显示/加载失败。这里手写递归序列化，永远输出纯净 JSON。
    // ------------------------------------------------------------------

    private fun appendJson(sb: StringBuilder, value: Any?, indent: Int) {
        when (value) {
            null -> sb.append("null")
            is String -> {
                sb.append('"')
                for (c in value) when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(c)
                }
                sb.append('"')
            }
            is Boolean -> sb.append(if (value) "true" else "false")
            is Number -> sb.append(value.toString())
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append('\n').append("  ".repeat(indent + 1))
                    appendJson(sb, k.toString(), indent + 1)
                    sb.append(": ")
                    appendJson(sb, v, indent + 1)
                }
                sb.append('\n').append("  ".repeat(indent)).append('}')
            }
            is List<*> -> {
                if (value.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append('\n').append("  ".repeat(indent + 1))
                    appendJson(sb, v, indent + 1)
                }
                sb.append('\n').append("  ".repeat(indent)).append(']')
            }
            else -> appendJson(sb, value.toString(), indent)
        }
    }

    // ------------------------------------------------------------------
    // 下拉数据源：当前模组 + 基础规则集合并
    // ------------------------------------------------------------------

    fun getBaseRuleset(modFolder: FileHandle): Ruleset {
        val chosen = readBaseRulesetChoice(modFolder).ifBlank { BaseRuleset.Civ_V_GnK.fullName }
        return RulesetCache[chosen] ?: RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!
    }

    private fun getModNames(modFolder: FileHandle, fileName: String): List<String> {
        val file = modFolder.child("jsons/$fileName")
        if (!file.exists()) return emptyList()
        return try {
            val parsed = JsonReader().parse(file.readString(Charsets.UTF_8.name()))
            if (!parsed.isArray) return emptyList()
            val names = mutableListOf<String>()
            for (entry in parsed) {
                names.add(if (entry.has("name")) entry.getString("name") else entry.asString())
            }
            names
        } catch (e: Exception) { emptyList() }
    }

    fun getUnitTypes(modFolder: FileHandle): List<String> =
        (getBaseRuleset(modFolder).unitTypes.keys + getModNames(modFolder, "UnitTypes.json"))
            .toSortedSet().toList()

    fun getTechs(modFolder: FileHandle): List<String> {
        val base = getBaseRuleset(modFolder)
        val merged = LinkedHashSet<String>()
        merged.addAll(base.technologies.keys)
        merged.addAll(getModNames(modFolder, "Techs.json"))

        // 模组自带科技的 era/列号（Techs.json 是分组结构：era + columnNumber + techs[]）
        val eraOrder = base.eras.keys.toList()
        val modInfo = HashMap<String, Pair<Int, Int>>()
        val modFile = modFolder.child("jsons/Techs.json")
        if (modFile.exists()) {
            try {
                val parsed = JsonReader().parse(modFile.readString(Charsets.UTF_8.name()))
                if (parsed.isArray) for (group in parsed) {
                    val eraIndex = eraOrder.indexOf(group.getString("era", ""))
                    val column = group.getInt("columnNumber", 0)
                    val techs = group.get("techs")
                    if (techs != null && techs.isArray) for (t in techs) {
                        modInfo[t.getString("name", "")] = Pair(eraIndex, column)
                    }
                }
            } catch (e: Exception) { }
        }

        fun eraIndex(name: String): Int {
            val idx = modInfo[name]?.first ?: eraOrder.indexOf(base.technologies[name]?.column?.era ?: "")
            return if (idx < 0) Int.MAX_VALUE else idx
        }
        fun column(name: String): Int =
            modInfo[name]?.second ?: base.technologies[name]?.column?.columnNumber ?: 0

        return merged.sortedWith(compareBy({ eraIndex(it) }, { column(it) }, { it }))
    }

    fun getNations(modFolder: FileHandle): List<String> =
        (getBaseRuleset(modFolder).nations.keys + getModNames(modFolder, "Nations.json"))
            .toSortedSet().toList()

    fun getResources(modFolder: FileHandle): List<String> {
        val base = getBaseRuleset(modFolder)
        val merged = LinkedHashMap<String, ResourceType?>()
        base.tileResources.forEach { (name, res) -> merged[name] = res.resourceType }
        // 模组自带资源的类型也解析
        val modFile = modFolder.child("jsons/TileResources.json")
        if (modFile.exists()) {
            try {
                val parsed = JsonReader().parse(modFile.readString(Charsets.UTF_8.name()))
                if (parsed.isArray) for (entry in parsed) {
                    val name = if (entry.has("name")) entry.getString("name") else entry.asString()
                    val typeName = entry.getString("resourceType", "")
                    merged[name] = ResourceType.entries.firstOrNull { it.name == typeName }
                }
            } catch (e: Exception) { }
        }
        // 排序：战略 → 奖励 → 奢侈 → 其他，同级按名称
        val typeOrder = mapOf(
            ResourceType.Strategic to 0, ResourceType.Bonus to 1, ResourceType.Luxury to 2)
        return merged.keys.sortedWith(compareBy(
            { typeOrder[merged[it]] ?: 3 },
            { it }
        ))
    }

    fun getUnits(modFolder: FileHandle): List<String> =
        (getBaseRuleset(modFolder).units.keys + getModNames(modFolder, "Units.json"))
            .toSortedSet().toList()

    /** 伟人列表：基础规则集 + 模组中所有带 "Great Person -" unique 标注的单位 */
    fun getGreatPeople(modFolder: FileHandle): List<String> {
        val result = LinkedHashSet<String>()
        val base = getBaseRuleset(modFolder)
        for ((name, unit) in base.units) {
            if (unit.uniques.any { it.startsWith("Great Person -") }) result.add(name)
        }
        val modFile = modFolder.child("jsons/Units.json")
        if (modFile.exists()) {
            try {
                val parsed = JsonReader().parse(modFile.readString(Charsets.UTF_8.name()))
                if (parsed.isArray) for (entry in parsed) {
                    if (!entry.has("name") || !entry.has("uniques")) continue
                    val uniques = entry.get("uniques")
                    if (uniques.isArray && uniques.any { it.asString().startsWith("Great Person -") })
                        result.add(entry.getString("name"))
                }
            } catch (e: Exception) { }
        }
        return result.toSortedSet().toList()
    }

    fun getPolicies(modFolder: FileHandle): List<String> =
        (getBaseRuleset(modFolder).policies.keys + getModNames(modFolder, "Policies.json"))
            .toSortedSet().toList()

    /** 飞船部件列表：基础规则集 + 模组中所有带 "Spaceship part" unique 的单位 */
    fun getSpaceshipParts(modFolder: FileHandle): List<String> {
        val result = LinkedHashSet<String>()
        val base = getBaseRuleset(modFolder)
        for ((name, unit) in base.units) {
            if (unit.uniques.any { it == "Spaceship part" }) result.add(name)
        }
        val modFile = modFolder.child("jsons/Units.json")
        if (modFile.exists()) {
            try {
                val parsed = JsonReader().parse(modFile.readString(Charsets.UTF_8.name()))
                if (parsed.isArray) for (entry in parsed) {
                    if (!entry.has("name") || !entry.has("uniques")) continue
                    val uniques = entry.get("uniques")
                    if (uniques.isArray && uniques.any { it.asString() == "Spaceship part" })
                        result.add(entry.getString("name"))
                }
            } catch (e: Exception) { }
        }
        return result.toSortedSet().toList()
    }

    fun getReligions(modFolder: FileHandle): List<String> =
        (getBaseRuleset(modFolder).religions + getModNames(modFolder, "Religions.json"))
            .toSortedSet().toList()

    fun getBeliefs(modFolder: FileHandle): List<String> =
        (getBaseRuleset(modFolder).beliefs.keys + getModNames(modFolder, "Beliefs.json"))
            .toSortedSet().toList()

    fun getSpecialists(modFolder: FileHandle): List<String> =
        (getBaseRuleset(modFolder).specialists.keys + getModNames(modFolder, "Specialists.json"))
            .toSortedSet().toList()

    fun getPersonalities(modFolder: FileHandle): List<String> =
        (getBaseRuleset(modFolder).personalities.keys + getModNames(modFolder, "Personalities.json"))
            .toSortedSet().toList()

    fun getCityStateTypes(modFolder: FileHandle): List<String> =
        (getBaseRuleset(modFolder).cityStateTypes.keys + getModNames(modFolder, "CityStateTypes.json"))
            .toSortedSet().toList()

    fun getEras(modFolder: FileHandle): List<String> =
        (getBaseRuleset(modFolder).eras.keys + getModNames(modFolder, "Eras.json"))
            .toSortedSet().toList()

    fun getSpeeds(modFolder: FileHandle): List<String> =
        (getBaseRuleset(modFolder).speeds.keys + getModNames(modFolder, "Speeds.json"))
            .toSortedSet().toList()

    fun getDifficulties(modFolder: FileHandle): List<String> =
        (getBaseRuleset(modFolder).difficulties.keys + getModNames(modFolder, "Difficulties.json"))
            .toSortedSet().toList()

    fun getVictoryTypes(modFolder: FileHandle): List<String> =
        (getBaseRuleset(modFolder).victories.keys + getModNames(modFolder, "VictoryTypes.json"))
            .toSortedSet().toList()

    fun getEvents(modFolder: FileHandle): List<String> =
        (getBaseRuleset(modFolder).events.keys + getModNames(modFolder, "Events.json"))
            .toSortedSet().toList()

    fun getUnitNameGroups(modFolder: FileHandle): List<String> =
        (getBaseRuleset(modFolder).unitNameGroups.keys + getModNames(modFolder, "UnitNameGroups.json"))
            .toSortedSet().toList()

    /** 已安装模组名（含基础规则集模组），用于 modFilter */
    fun getInstalledMods(): List<String> = installedBaseRulesetMods()

    /** 单位或建筑（baseUnitFilter/buildingFilter 用） */
    fun getUnitsAndBuildings(modFolder: FileHandle): List<String> =
        (getUnits(modFolder) + getBuildings(modFolder)).toSortedSet().toList()

    /** 区域类型：Hybrid + 带 "A Region is formed" 词条的地形（官方 regionType 文档） */
    fun getRegionTypes(modFolder: FileHandle): List<String> {
        val result = LinkedHashSet<String>()
        result.add("Hybrid")
        val base = getBaseRuleset(modFolder)
        for ((name, terrain) in base.terrains) {
            if (terrain.uniques.any { it.startsWith("A Region is formed") }) result.add(name)
        }
        val modFile = modFolder.child("jsons/Terrains.json")
        if (modFile.exists()) {
            try {
                val parsed = JsonReader().parse(modFile.readString(Charsets.UTF_8.name()))
                if (parsed.isArray) for (entry in parsed) {
                    if (!entry.has("name") || !entry.has("uniques")) continue
                    val uniques = entry.get("uniques")
                    if (uniques.isArray && uniques.any { it.asString().startsWith("A Region is formed") })
                        result.add(entry.getString("name"))
                }
            } catch (e: Exception) { }
        }
        return result.toSortedSet().toList()
    }

    /** 可囤积资源：基础规则集 + 模组中带 "Stockpiled" 词条的资源（官方 stockpiledResource 文档） */
    fun getStockpiledResources(modFolder: FileHandle): List<String> {
        val result = LinkedHashSet<String>()
        val base = getBaseRuleset(modFolder)
        for ((name, res) in base.tileResources) {
            if (res.uniques.any { it == "Stockpiled" }) result.add(name)
        }
        val modFile = modFolder.child("jsons/TileResources.json")
        if (modFile.exists()) {
            try {
                val parsed = JsonReader().parse(modFile.readString(Charsets.UTF_8.name()))
                if (parsed.isArray) for (entry in parsed) {
                    if (!entry.has("name") || !entry.has("uniques")) continue
                    val uniques = entry.get("uniques")
                    if (uniques.isArray && uniques.any { it.asString() == "Stockpiled" })
                        result.add(entry.getString("name"))
                }
            } catch (e: Exception) { }
        }
        return result.toSortedSet().toList()
    }

    fun getTerrains(modFolder: FileHandle): List<String> =
        (getBaseRuleset(modFolder).terrains.keys + getModNames(modFolder, "Terrains.json"))
            .toSortedSet().toList()

    fun getImprovements(modFolder: FileHandle): List<String> =
        (getBaseRuleset(modFolder).tileImprovements.keys + getModNames(modFolder, "TileImprovements.json"))
            .toSortedSet().toList()

    fun getPromotions(modFolder: FileHandle): List<String> =
        (getBaseRuleset(modFolder).unitPromotions.keys + getModNames(modFolder, "UnitPromotions.json"))
            .toSortedSet().toList()

    fun getBuildings(modFolder: FileHandle): List<String> =
        (getBaseRuleset(modFolder).buildings.keys + getModNames(modFolder, "Buildings.json"))
            .toSortedSet().toList()

    fun getBaseRulesetNames(): List<String> =
        BaseRuleset.entries.map { it.fullName } + installedBaseRulesetMods()

    private fun installedBaseRulesetMods(): List<String> {
        val modsFolder = UncivGame.Current.files.getModsFolder()
        if (!modsFolder.exists()) return emptyList()
        return modsFolder.list().filter { it.isDirectory && !it.name().startsWith("temp-") }
            .filter { readIsBaseRuleset(it) }
            .map { it.name() }
            .sorted()
    }

    // ------------------------------------------------------------------
    // TileImprovements.json / TileResources.json / Ruins.json 读写
    // ------------------------------------------------------------------

    fun loadTileImprovements(modFolder: FileHandle): MutableList<ModObjectData> =
        loadObjects(modFolder, "TileImprovements.json")

    fun saveTileImprovements(modFolder: FileHandle, items: List<ModObjectData>) =
        saveObjects(modFolder, "TileImprovements.json", items)

    fun validateTileImprovement(modFolder: FileHandle, item: ModObjectData, allItems: List<ModObjectData>): List<Pair<String, Boolean>> {
        val problems = mutableListOf<Pair<String, Boolean>>()
        if (item.getString("name").isBlank()) problems.add("Improvement name is required" to true)
        val duplicates = allItems.count { it.getString("name") == item.getString("name") && it.getString("name").isNotBlank() }
        if (duplicates > 1) problems.add("Duplicate improvement name" to false)
        val terrains = ModEditorData.getTerrains(modFolder)
        for (t in item.getStringList("terrainsCanBeBuiltOn"))
            if (t !in terrains) problems.add("Unknown terrain in terrainsCanBeBuiltOn: [$t]" to false)
        val tech = item.getString("techRequired")
        if (tech.isNotBlank() && tech !in getTechs(modFolder)) problems.add("Unknown techRequired: [$tech]" to false)
        val replaces = item.getString("replaces")
        if (replaces.isNotBlank() && allItems.none { it.getString("name") == replaces } &&
            loadBaseObjects(modFolder, "TileImprovements.json").none { it.getString("name") == replaces })
            problems.add("Unknown replaces: [$replaces]" to false)
        return problems
    }

    fun loadTileResources(modFolder: FileHandle): MutableList<ModObjectData> =
        loadObjects(modFolder, "TileResources.json")

    fun saveTileResources(modFolder: FileHandle, items: List<ModObjectData>) =
        saveObjects(modFolder, "TileResources.json", items)

    fun validateTileResource(modFolder: FileHandle, item: ModObjectData, allItems: List<ModObjectData>): List<Pair<String, Boolean>> {
        val problems = mutableListOf<Pair<String, Boolean>>()
        if (item.getString("name").isBlank()) problems.add("Resource name is required" to true)
        val type = item.getString("resourceType")
        if (type.isNotBlank() && type !in listOf("Bonus", "Strategic", "Luxury"))
            problems.add("Invalid resourceType - must be Bonus, Strategic or Luxury" to true)
        val duplicates = allItems.count { it.getString("name") == item.getString("name") && it.getString("name").isNotBlank() }
        if (duplicates > 1) problems.add("Duplicate resource name" to false)
        val terrains = ModEditorData.getTerrains(modFolder)
        for (t in item.getStringList("terrainsCanBeFoundOn"))
            if (t !in terrains) problems.add("Unknown terrain in terrainsCanBeFoundOn: [$t]" to false)
        val revealedBy = item.getString("revealedBy")
        if (revealedBy.isNotBlank() && revealedBy !in getTechs(modFolder)) problems.add("Unknown revealedBy: [$revealedBy]" to false)
        return problems
    }

    fun loadRuins(modFolder: FileHandle): MutableList<ModObjectData> =
        loadObjects(modFolder, "Ruins.json")

    fun saveRuins(modFolder: FileHandle, items: List<ModObjectData>) =
        saveObjects(modFolder, "Ruins.json", items)

    fun validateRuin(modFolder: FileHandle, item: ModObjectData, allItems: List<ModObjectData>): List<Pair<String, Boolean>> {
        val problems = mutableListOf<Pair<String, Boolean>>()
        if (item.getString("name").isBlank()) problems.add("Ruin name is required" to true)
        if (item.getString("notification").isBlank()) problems.add("Ruin notification is required" to true)
        val duplicates = allItems.count { it.getString("name") == item.getString("name") && it.getString("name").isNotBlank() }
        if (duplicates > 1) problems.add("Duplicate ruin name - names must be distinct" to true)
        val weight = item.raw["weight"]
        if (weight is Number && weight.toInt() < 0) problems.add("Ruin weight must be >= 0" to true)
        val difficulties = ModEditorData.getDifficulties(modFolder)
        for (d in item.getStringList("excludedDifficulties"))
            if (d !in difficulties) problems.add("Unknown difficulty in excludedDifficulties: [$d]" to false)
        return problems
    }

    // ------------------------------------------------------------------
    // Difficulties.json / Eras.json / Speeds.json 读写
    // ------------------------------------------------------------------

    fun loadDifficulties(modFolder: FileHandle): MutableList<ModObjectData> =
        loadObjects(modFolder, "Difficulties.json")

    fun saveDifficulties(modFolder: FileHandle, items: List<ModObjectData>) =
        saveObjects(modFolder, "Difficulties.json", items)

    fun validateDifficulty(modFolder: FileHandle, item: ModObjectData, allItems: List<ModObjectData>): List<Pair<String, Boolean>> {
        val problems = mutableListOf<Pair<String, Boolean>>()
        if (item.getString("name").isBlank()) problems.add("Difficulty name is required" to true)
        val duplicates = allItems.count { it.getString("name") == item.getString("name") && it.getString("name").isNotBlank() }
        if (duplicates > 1) problems.add("Duplicate difficulty name" to false)
        val units = ModEditorData.getUnits(modFolder)
        for (key in listOf("playerBonusStartingUnits", "aiMajorCivBonusStartingUnits", "aiCityStateBonusStartingUnits"))
            for (u in item.getStringList(key))
                if (u !in units) problems.add("Unknown unit in $key: [$u]" to false)
        val techs = ModEditorData.getTechs(modFolder)
        for (t in item.getStringList("aiFreeTechs"))
            if (t !in techs) problems.add("Unknown tech in aiFreeTechs: [$t]" to false)
        return problems
    }

    fun loadEras(modFolder: FileHandle): MutableList<ModObjectData> =
        loadObjects(modFolder, "Eras.json")

    fun saveEras(modFolder: FileHandle, items: List<ModObjectData>) =
        saveObjects(modFolder, "Eras.json", items)

    fun validateEra(modFolder: FileHandle, item: ModObjectData, allItems: List<ModObjectData>): List<Pair<String, Boolean>> {
        val problems = mutableListOf<Pair<String, Boolean>>()
        if (item.getString("name").isBlank()) problems.add("Era name is required" to true)
        val duplicates = allItems.count { it.getString("name") == item.getString("name") && it.getString("name").isNotBlank() }
        if (duplicates > 1) problems.add("Duplicate era name" to false)
        val units = ModEditorData.getUnits(modFolder)
        for (key in listOf("startingSettlerUnit", "startingWorkerUnit", "startingMilitaryUnit")) {
            val u = item.getString(key)
            if (u.isNotBlank() && u !in units) problems.add("Unknown unit in $key: [$u]" to false)
        }
        val buildings = ModEditorData.getBuildings(modFolder)
        for (key in listOf("settlerBuildings", "startingObsoleteWonders"))
            for (b in item.getStringList(key))
                if (b !in buildings) problems.add("Unknown building in $key: [$b]" to false)
        val rgb = item.raw["iconRGB"] as? List<*>
        if (rgb != null && rgb.size != 3) problems.add("iconRGB must be a list of 3 integers" to true)
        return problems
    }

    fun loadSpeeds(modFolder: FileHandle): MutableList<ModObjectData> =
        loadObjects(modFolder, "Speeds.json")

    fun saveSpeeds(modFolder: FileHandle, items: List<ModObjectData>) =
        saveObjects(modFolder, "Speeds.json", items)

    fun validateSpeed(modFolder: FileHandle, item: ModObjectData, allItems: List<ModObjectData>): List<Pair<String, Boolean>> {
        val problems = mutableListOf<Pair<String, Boolean>>()
        if (item.getString("name").isBlank()) problems.add("Speed name is required" to true)
        val duplicates = allItems.count { it.getString("name") == item.getString("name") && it.getString("name").isNotBlank() }
        if (duplicates > 1) problems.add("Duplicate speed name" to false)
        val turns = item.raw["turns"] as? List<*>
        if (turns != null) {
            for (t in turns) {
                if (t !is Map<*, *>) problems.add("Invalid turns entry - must be an object" to true)
                else {
                    if (t["yearsPerTurn"] !is Number) problems.add("turns entry missing yearsPerTurn" to true)
                    if (t["untilTurn"] !is Number) problems.add("turns entry missing untilTurn" to true)
                }
            }
        }
        return problems
    }

    // ------------------------------------------------------------------
    // Events.json 读写
    // ------------------------------------------------------------------

    fun loadEvents(modFolder: FileHandle): MutableList<ModObjectData> =
        loadObjects(modFolder, "Events.json")

    fun saveEvents(modFolder: FileHandle, items: List<ModObjectData>) =
        saveObjects(modFolder, "Events.json", items)

    fun validateEvent(modFolder: FileHandle, item: ModObjectData, allItems: List<ModObjectData>): List<Pair<String, Boolean>> {
        val problems = mutableListOf<Pair<String, Boolean>>()
        if (item.getString("name").isBlank()) problems.add("Event name is required" to true)
        val duplicates = allItems.count { it.getString("name") == item.getString("name") && it.getString("name").isNotBlank() }
        if (duplicates > 1) problems.add("Duplicate event name" to false)
        val presentation = item.getString("presentation")
        if (presentation.isNotBlank() && presentation !in listOf("None", "Alert", "Floating"))
            problems.add("Invalid presentation - must be None, Alert or Floating" to true)
        val choices = item.raw["choices"] as? List<*>
        if (choices != null) {
            for (c in choices) {
                if (c !is Map<*, *>) problems.add("Invalid choice - must be an object" to true)
                else {
                    val text = c["text"]
                    if (text !is String || text.isBlank()) problems.add("Choice text is required" to true)
                }
            }
        }
        return problems
    }

    // ------------------------------------------------------------------
    // Tutorials.json / VictoryTypes.json 读写
    // ------------------------------------------------------------------

    fun loadTutorials(modFolder: FileHandle): MutableList<ModObjectData> =
        loadObjects(modFolder, "Tutorials.json")

    fun saveTutorials(modFolder: FileHandle, items: List<ModObjectData>) =
        saveObjects(modFolder, "Tutorials.json", items)

    fun validateTutorial(modFolder: FileHandle, item: ModObjectData, allItems: List<ModObjectData>): List<Pair<String, Boolean>> {
        val problems = mutableListOf<Pair<String, Boolean>>()
        if (item.getString("name").isBlank()) problems.add("Tutorial name is required" to true)
        val duplicates = allItems.count { it.getString("name") == item.getString("name") && it.getString("name").isNotBlank() }
        if (duplicates > 1) problems.add("Duplicate tutorial name" to false)
        return problems
    }

    fun loadVictoryTypes(modFolder: FileHandle): MutableList<ModObjectData> =
        loadObjects(modFolder, "VictoryTypes.json")

    fun saveVictoryTypes(modFolder: FileHandle, items: List<ModObjectData>) =
        saveObjects(modFolder, "VictoryTypes.json", items)

    fun validateVictoryType(modFolder: FileHandle, item: ModObjectData, allItems: List<ModObjectData>): List<Pair<String, Boolean>> {
        val problems = mutableListOf<Pair<String, Boolean>>()
        if (item.getString("name").isBlank()) problems.add("Victory name is required" to true)
        val duplicates = allItems.count { it.getString("name") == item.getString("name") && it.getString("name").isNotBlank() }
        if (duplicates > 1) problems.add("Duplicate victory type name" to false)
        if (item.getStringList("milestones").isEmpty()) problems.add("At least one milestone is required" to true)
        return problems
    }

    // ------------------------------------------------------------------
    // Quests.json 读写
    // ------------------------------------------------------------------

    /** 官方预定义 quest 名（Quests 文档 2026-08-19）：name 字段决定行为，不在预定义枚举内则无效果 */
    fun getQuestNames(): List<String> = listOf(
        "Route", "Clear Barbarian Camp", "Construct Wonder", "Connect Resource",
        "Acquire Great Person", "Conquer City State", "Find Player", "Find Natural Wonder",
        "Give Gold", "Pledge to Protect", "Contest Culture", "Contest Faith",
        "Contest Technology", "Invest", "Bully City State", "Denounce Civilization",
        "Spread Religion"
    )

    /** 城邦性格（weightForCityStateType 的 key 之一） */
    fun getCityStatePersonalities(): List<String> =
        listOf("Friendly", "Neutral", "Hostile", "Irrational")

    fun loadQuests(modFolder: FileHandle): MutableList<ModObjectData> =
        loadObjects(modFolder, "Quests.json")

    fun saveQuests(modFolder: FileHandle, quests: List<ModObjectData>) =
        saveObjects(modFolder, "Quests.json", quests)

    fun validateQuest(modFolder: FileHandle, quest: ModObjectData, allQuests: List<ModObjectData>): List<Pair<String, Boolean>> {
        val problems = mutableListOf<Pair<String, Boolean>>()
        if (quest.getString("name").isBlank()) problems.add("Quest name is required" to true)
        if (quest.getString("description").isBlank()) problems.add("Quest description is required" to true)
        val duplicates = allQuests.count {
            it.getString("name") == quest.getString("name") && it.getString("name").isNotBlank()
        }
        if (duplicates > 1) problems.add("Duplicate quest name" to false)
        val type = quest.getString("type")
        if (type.isNotBlank() && type != "Individual" && type != "Global")
            problems.add("Invalid quest type - must be Individual or Global" to true)
        val weights = quest.raw["weightForCityStateType"] as? Map<*, *>
        if (weights != null) {
            for ((k, v) in weights) {
                if (v !is Number && v !is String)
                    problems.add("Weight for [$k] must be a number" to true)
            }
        }
        return problems
    }

    // ------------------------------------------------------------------
    // Religions.json 读写（纯字符串数组）
    // ------------------------------------------------------------------

    fun loadReligions(modFolder: FileHandle): MutableList<String> {
        val file = modFolder.child("jsons/Religions.json")
        if (!file.exists()) return mutableListOf()
        return try {
            val text = file.readString(Charsets.UTF_8.name())
            val parsed = JsonReader().parse(removeTrailingCommasPublic(text))
            if (!parsed.isArray) return mutableListOf()
            parsed.map { it.asString() }.toMutableList()
        } catch (e: Exception) { mutableListOf() }
    }

    fun saveReligions(modFolder: FileHandle, religions: List<String>) {
        val dir = modFolder.child("jsons")
        if (!dir.exists()) dir.mkdirs()
        val file = dir.child("Religions.json")
        if (file.exists()) file.copyTo(dir.child("Religions.json.bak"))
        val sb = StringBuilder("[\n")
        for ((index, name) in religions.withIndex()) {
            sb.append("    \"").append(name.replace("\"", "")).append('"')
            if (index != religions.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append(']')
        file.writeString(sb.toString(), false, Charsets.UTF_8.name())
        mirrorToDebugFolder(modFolder)
    }

    /** 基础规则集的纯字符串数组文件（如 Religions.json） */
    fun loadBaseStringList(modFolder: FileHandle, fileName: String): List<String> {
        val file = resolveBaseFile(modFolder, null, fileName) ?: return emptyList()
        return try {
            val parsed = JsonReader().parse(removeTrailingCommasPublic(stripCommentsPublic(file.readString(Charsets.UTF_8.name()))))
            if (!parsed.isArray) return emptyList()
            parsed.map { it.asString() }
        } catch (e: Exception) { emptyList() }
    }


    // ------------------------------------------------------------------
    // Translations 模块：扫描 mod 需要翻译的字符串 + 读写翻译文件
    // ------------------------------------------------------------------

    /** 扫描 mod 的全部 jsons，收集所有需要翻译的字符串（有序去重） */
    fun scanModTranslatableStrings(modFolder: FileHandle): LinkedHashSet<String> {
        val result = LinkedHashSet<String>()
        val jsonsDir = modFolder.child("jsons")
        if (!jsonsDir.exists()) return result
        for (file in jsonsDir.list()) {
            if (!file.name().endsWith(".json")) continue
            if (file.name() == "ModOptions.json") continue // 选项名不翻译
            try {
                val text = file.readString(Charsets.UTF_8.name())
                val parsed = JsonReader().parse(removeTrailingCommasPublic(stripCommentsPublic(text)))
                collectTranslatable(parsed, result)
            } catch (e: Exception) { }
        }
        return result
    }

    /**
     * 收集需要翻译的字符串（2026-08-19 用户修正）：
     * - 只收集「定义级」name：数组直接子元素对象的 name（条目定义），
     *   不收集引用字段（如 startingMilitaryUnit: "Worker" 这类是引用，Worker 的定义处已翻译）
     * - 文本字段：civilopediaText.text / steps / description / notification / victoryString 等
     * - 不收集 uniques（词条类暂不做）
     */
    private fun collectTranslatable(value: com.badlogic.gdx.utils.JsonValue, result: LinkedHashSet<String>) {
        when {
            value.isArray -> {
                for (child in value) {
                    if (child.isObject) {
                        // 数组元素对象 = 条目定义 → 收集 name
                        child.get("name")?.asString()?.takeIf { it.isNotBlank() }?.let { result.add(it) }
                        collectTranslatableFields(child, result)
                    }
                }
            }
            value.isObject -> collectTranslatableFields(value, result)
            else -> { }
        }
    }

    private fun collectTranslatableFields(obj: com.badlogic.gdx.utils.JsonValue, result: LinkedHashSet<String>) {
        obj.get("civilopediaText")?.let { cp ->
            if (cp.isArray) for (entry in cp) {
                if (entry.isObject) {
                    entry.get("text")?.asString()?.takeIf { it.isNotBlank() }?.let { result.add(it) }
                    entry.get("link")?.asString()?.takeIf { it.isNotBlank() }?.let { result.add(it) }
                }
            }
        }
        obj.get("steps")?.let { steps ->
            if (steps.isArray) for (s in steps) s.asString()?.takeIf { it.isNotBlank() }?.let { result.add(it) }
        }
        for (key in listOf("description", "notification", "victoryString", "defeatString", "victoryScreenHeader", "text")) {
            obj.get(key)?.asString()?.takeIf { it.isNotBlank() }?.let { result.add(it) }
        }
        // 嵌套数组（如 Techs 的 techs[]、Policies 的 policies[]、Events 的 choices[]）继续收集定义级 name
        for (child in obj) {
            if (child.isArray) {
                for (entry in child) {
                    if (entry.isObject) {
                        entry.get("name")?.asString()?.takeIf { it.isNotBlank() }?.let { result.add(it) }
                        collectTranslatableFields(entry, result)
                    }
                }
            } else if (child.isObject) {
                collectTranslatableFields(child, result)
            }
        }
    }

    /** 读取 mod 的翻译文件（不存在返回空 map） */
    fun readModTranslationFile(modFolder: FileHandle, language: String): LinkedHashMap<String, String> {
        val file = modFolder.child("jsons/translations/$language.properties")
        if (!file.exists()) return LinkedHashMap()
        val result = LinkedHashMap<String, String>()
        try {
            file.reader(Charsets.UTF_8.name()).forEachLine { line ->
                if (line.isBlank() || line.startsWith('#') || !line.contains(" = ")) return@forEachLine
                val split = line.split(" = ", limit = 2)
                if (split.size == 2) result[split[0]] = split[1]
            }
        } catch (e: Exception) { }
        return result
    }

    /** 把 mod 的翻译注入游戏翻译表（供 .tr() 双语显示）；mod 不在游戏 RulesetCache 里，需手动加载 */
    fun loadModTranslations(modFolder: FileHandle) {
        try {
            val language = UncivGame.Current.settings.language
            val translations = UncivGame.Current.translations
            val modName = modFolder.name()
            translations.translationActiveMods.add(modName)
            // 扩展 mod：先加载其基础规则集的翻译（内置规则集用全局翻译；已安装 base mod 用该 mod 的翻译）
            val baseName = readBaseRulesetChoice(modFolder)
            if (baseName.isNotBlank() && baseName !in ModEditorData.getBuiltInRulesetNames()) {
                val baseFolder = UncivGame.Current.files.getModFolder(baseName)
                if (baseFolder.exists() && baseFolder.name() != modName) {
                    loadModTranslations(baseFolder)
                }
            }
            val modTranslationFile = modFolder.child("jsons/translations/$language.properties")
            if (modTranslationFile.exists()) {
                val modTranslations = Translations()
                val map = TranslationFileReader.read(modTranslationFile)
                for ((k, v) in map) modTranslations[k] = TranslationEntry(k).apply { this[language] = v }
                translations.modsWithTranslations[modName] = modTranslations
            }
        } catch (e: Exception) {
            // 加载失败不影响编辑器使用
        }
    }

    /** 内置规则集名（G&K/Vanilla 等，翻译来自全局翻译文件） */
    fun getBuiltInRulesetNames(): Set<String> = BaseRuleset.entries.map { it.fullName }.toSet()

    /** 写 mod 翻译文件（保持现有条目 + 追加/更新） */
    fun writeModTranslationFile(modFolder: FileHandle, language: String, translations: LinkedHashMap<String, String>) {
        val dir = modFolder.child("jsons/translations")
        if (!dir.exists()) dir.mkdirs()
        val file = dir.child("$language.properties")
        val sb = StringBuilder()
        for ((key, value) in translations) {
            sb.append(key).append(" = ").append(value.replace("\n", "\\n")).append('\n')
        }
        file.writeString(sb.toString(), false, Charsets.UTF_8.name())
    }

    /** 官方翻译文件里的翻译（key → value），用于新建翻译文件时复制官方翻译 */
    fun readOfficialTranslationFile(language: String): LinkedHashMap<String, String> {
        val file = Gdx.files.internal("jsons/translations/$language.properties")
        if (!file.exists()) return LinkedHashMap()
        val result = LinkedHashMap<String, String>()
        try {
            file.reader(Charsets.UTF_8.name()).forEachLine { line ->
                if (line.isBlank() || line.startsWith('#') || !line.contains(" = ")) return@forEachLine
                val split = line.split(" = ", limit = 2)
                if (split.size == 2 && split[1].isNotEmpty()) result[split[0]] = split[1]
            }
        } catch (e: Exception) { }
        return result
    }

    /** 游戏支持的语言列表（来自官方 LocaleCode 枚举 + 翻译文件存在性检查；jar 内资源目录无法 list()） */
    fun getAvailableTranslationLanguages(): List<String> {
        return com.unciv.models.metadata.LocaleCode.getSupportedLanguages()
            .filter { Gdx.files.internal("jsons/translations/$it.properties").exists() }
            .toList().sorted()
    }
}

/**
 * 计算编辑器右侧表单的可用宽度（chips 换行阈值用）。
 *
 * 各编辑器左侧面板宽度不一：普通编辑器 = max(280, stage.width/4)，
 * Policies/Techs = stage.width/2。内嵌控件（UniqueInlineEditor 等）无法感知父布局，
 * 用保守默认（leftFraction = 1/4 且额外扣 20f）。
 * 公式：stage.width - 左栏 - 分隔线(2f) - body pad(4f*2) - 表单 pad(8f) - 标签列/余量
 */
fun formAvailableWidth(stageWidth: Float, leftFraction: Float = 0.25f, extraDeduction: Float = 0f): Float {
    val leftPanel = maxOf(280f, stageWidth * leftFraction)
    val available = stageWidth - leftPanel - 2f - 8f - 8f - 8f - extraDeduction
    return maxOf(available, 220f)
}


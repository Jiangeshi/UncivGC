package com.unciv.json

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.utils.Json
import com.badlogic.gdx.utils.JsonValue
import com.badlogic.gdx.utils.JsonWriter
import com.badlogic.gdx.utils.SerializationException
import com.unciv.logic.map.HexCoord
import com.unciv.ui.components.input.KeyCharAndCode
import java.time.Duration


/**
 * [Json] is not thread-safe. Use a new one for each parse.
 */
fun json() = Json(JsonWriter.OutputType.json).apply {
    // Gdx default output type is JsonWriter.OutputType.minimal, which generates invalid Json - e.g. most quotes removed.
    // The constructor parameter above changes that to valid Json
    // Note an instance set to json can read minimal and vice versa

    setIgnoreDeprecated(true)
    ignoreUnknownFields = true

    setSerializer(Duration::class.java, DurationSerializer())
    setSerializer(KeyCharAndCode::class.java, KeyCharAndCode.Serializer())
    setSerializer(HexCoord::class.java, HexCoord.Serializer())
    //setSerializer(String::class.java, StringInterningSerializer())
}

/**
 *  Load a json file by [filePath] from Gdx.files.internal
 *  (meaning from jar/apk for packaged release code, and not appropriate for mod files)
 *  @throws SerializationException
 */
fun <T> Json.fromJsonFile(tClass: Class<T>, filePath: String): T = fromJsonFile(tClass, Gdx.files.internal(filePath))

/**
 *  Load a json [file] - by handle, so internal/external/local is caller's decision.
 *
 *  Reminder:
 *  * `internal` for Unciv-packaged assets, loaded from jar/apk, e.g. Built-in ruleset files.
 *  * `local` for mods and settings - Android will place that under /data/data/com.unciv.app/files.
 *  * `external` for saves - Android will place that under /sdcard/Android/data/com.unciv.app/files.
 *  @throws SerializationException
 */
fun <T> Json.fromJsonFile(tClass: Class<T>, file: FileHandle): T {
    try {
        return fromJson(tClass, file)
    } catch (exception: Exception) {
        // 兼容 JSONC (jsons 带 // 或 /* */ 注释): 标准解析失败时剥离注释重试 —
        // 部分老模组 (如 Alpha Frontier) 的 jsons 带注释, 直接解析会失败导致模组不加载
        try {
            val text = file.readString(Charsets.UTF_8.name())
            val stripped = stripJsonComments(text)
            return fromJson(tClass, stripped)
        } catch (e: Exception) {
            val jsonText = file.readString(Charsets.UTF_8.name())
            throw Exception("Could not parse json of file ${file.name()}", exception)
        }
    }
}

/** 已输出内容最后一个非空白字符是否为值结尾 (需要逗号分隔) */
private fun prevValueEnded(sb: StringBuilder): Boolean {
    for (k in sb.length - 1 downTo 0) {
        val ch = sb[k]
        if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') continue
        return ch == '"' || ch == '}' || ch == ']'
    }
    return false
}

/** 注释后第一个非空白字符是否为值开始 (需要逗号分隔) — 正确跳过后续行注释/块注释 */
private fun nextStartsValue(text: String, commentStart: Int): Boolean {
    var k = commentStart
    while (k < text.length) {
        val ch = text[k]
        if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') { k++; continue }
        if (ch == '/' && k + 1 < text.length && text[k + 1] == '/') {
            while (k < text.length && text[k] != '\n') k++
            continue
        }
        if (ch == '/' && k + 1 < text.length && text[k + 1] == '*') {
            k += 2
            while (k + 1 < text.length && !(text[k] == '*' && text[k + 1] == '/')) k++
            k += 2
            continue
        }
        break
    }
    if (k >= text.length) return false
    val ch = text[k]
    return ch == '"' || ch == '{' || ch == '[' || ch == '-' || ch.isDigit() || ch == 't' || ch == 'f' || ch == 'n'
}

/** 剥离 JSON 注释 (行注释 // 和块注释 /* ... */) — 跳过字符串内的内容 (https:// 等不受影响) */
private fun stripJsonComments(text: String): String {
    val sb = StringBuilder(text.length)
    var inString = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            inString -> {
                sb.append(c)
                if (c == '\\' && i + 1 < text.length) { sb.append(text[i + 1]); i += 2; continue }
                if (c == '"') inString = false
                i++
            }
            c == '"' -> {
                // 字符串也是值开始 — 前一值结尾缺逗号时补 (Alpha Frontier 手写 jsons)
                if (prevValueEnded(sb)) sb.append(',')
                inString = true; sb.append(c); i++
            }
            c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                // JSONC 用注释做分隔符时元素间省略逗号 (如 Alpha Frontier) — 前一值结尾 + 后一值开始 → 补逗号
                if (prevValueEnded(sb) && nextStartsValue(text, i)) sb.append(',')
                while (i < text.length && text[i] != '\n') i++
                sb.append('\n')
            }
            c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                if (prevValueEnded(sb) && nextStartsValue(text, i)) sb.append(',')
                i += 2
                while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                i += 2
                sb.append(' ')
            }
            c == ',' -> {
                // 尾随逗号 (JSONC): 逗号后 (跳过空白和注释) 是 } 或 ] → 丢弃
                var j = i + 1
                while (j < text.length) {
                    val ch = text[j]
                    if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') { j++; continue }
                    if (ch == '/' && j + 1 < text.length && text[j + 1] == '/') {
                        while (j < text.length && text[j] != '\n') j++
                        continue
                    }
                    if (ch == '/' && j + 1 < text.length && text[j + 1] == '*') {
                        j += 2
                        while (j + 1 < text.length && !(text[j] == '*' && text[j + 1] == '/')) j++
                        j += 2
                        continue
                    }
                    break
                }
                if (j < text.length && (text[j] == '}' || text[j] == ']')) i++ else { sb.append(c); i++ }
            }
            else -> {
                // 通用容错 (Alpha Frontier 等手写 jsons 元素间缺逗号): 值结尾直接跟值开始 → 补逗号。
                // 仅标准解析失败后的重试路径生效, 合法 JSON 值间必有逗号, 不会误触发。
                if ((c == '"' || c == '{' || c == '[' || c == '-' || c.isDigit() || c == 't' || c == 'f' || c == 'n')
                    && prevValueEnded(sb)) sb.append(',')
                // 字符串外的反斜杠是编辑残留 (如 \"hiddenInVictoryScreen) — 合法 JSON 字符串外无反斜杠, 丢弃
                if (c != '\\') sb.append(c)
                i++
            }
        }
    }
    return sb.toString()
}

private class StringInterningSerializer : Json.Serializer<String> {
    override fun write(json: Json, key: String, knownType: Class<*>?) = json.writeValue(key as Any?, String::class.java, null)

    override fun read(json: Json, jsonData: JsonValue, type: Class<*>?): String
    = if (jsonData.type() == JsonValue.ValueType.`object`) (json.readValue("value", type, jsonData) as String)
        else jsonData.asString().intern()
}

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

/** 剥离 JSON 注释 (行注释 // 和块注释 /* *​/) — 跳过字符串内的内容 (https:// 等不受影响) */
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
            c == '"' -> { inString = true; sb.append(c); i++ }
            c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                while (i < text.length && text[i] != '\n') i++
                sb.append('\n')
            }
            c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                i += 2
                while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                i += 2
                sb.append(' ')
            }
            else -> { sb.append(c); i++ }
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

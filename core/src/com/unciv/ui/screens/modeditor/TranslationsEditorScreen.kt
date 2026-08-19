package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextArea
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.addSeparatorVertical
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 * Translations 编辑器（v3，2026-08-19 用户要求）：
 * - 点进模块直接显示语言文件选择页（列表 + 顶部搜索语言）
 * - 选定后进入词条编辑页：原文模式——一个大 TextArea，每行 key = value 直接编辑
 * - Auto-add missing 按钮：扫描 mod 自动补缺失词条（只补官方没有翻译的，原版有的沿用原版）
 */
class TranslationsEditorScreen(private val modFolder: FileHandle) : BaseScreen() {

    private var language = ""
    private var translations = LinkedHashMap<String, String>()
    private var officialTranslations = LinkedHashMap<String, String>()
    private val missingKeys = LinkedHashSet<String>()

    private val contentTable = Table(BaseScreen.skin).apply {
        defaults().expandX().fillX()
        top()
    }
    private val statusLabel = "".toLabel(fontSize = 16)
    private val titleLabel = "".toLabel(fontSize = 28)
    private val backButton = ("‹ " + "Back".tr()).toTextButton()
    private var backAction: () -> Unit = { game.popScreen() }

    private lateinit var rawArea: TextArea
    private lateinit var searchField: UncivTextField
    private lateinit var outerScroll: AutoScrollPane
    private var fullRawText = "" // 搜索时的完整文本备份（只显示匹配行，保存时合并）
    private var searchActive = false

    init {
        val root = Table(BaseScreen.skin)
        root.setFillParent(true)
        stage.addActor(root)

        val topBar = Table(BaseScreen.skin)
        backButton.onActivation { backAction() }
        topBar.add(backButton).pad(8f)
        topBar.add(titleLabel).padLeft(20f).expandX().left()
        val saveButton = "Save".toTextButton()
        saveButton.onActivation { save() }
        topBar.add(saveButton).pad(8f)
        topBar.add(statusLabel).pad(8f)
        root.add(topBar).fillX().row()

        outerScroll = AutoScrollPane(contentTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        root.add(outerScroll).expand().grow()

        showLanguagePickerPage()
    }

    // ------------------------------------------------------------------
    // 页面 1：语言文件选择
    // ------------------------------------------------------------------

    private fun showLanguagePickerPage() {
        // 语言列表页：外层滚动可用
        outerScroll.setScrollingDisabled(true, false)
        contentTable.clear()
        titleLabel.setText("Choose a translation file".tr())
        backAction = { game.popScreen() }
        refreshLanguageList("")
    }

    private fun refreshLanguageList(query: String) {
        contentTable.clear()
        titleLabel.setText("Choose a translation file".tr())
        backAction = { game.popScreen() }

        val langSearch = UncivTextField("Search")
        langSearch.setTextFieldListener { field, _ -> refreshLanguageList(field.text) }
        contentTable.add(langSearch).growX().pad(8f).row()
        contentTable.add(separatorLine()).fillX().height(2f).pad(4f, 8f, 4f, 8f).row()

        val languages = ModEditorData.getAvailableTranslationLanguages()
        val q = query.trim().lowercase()
        for (lang in languages) {
            if (q.isNotEmpty() && !lang.lowercase().contains(q)) continue
            val modFile = modFolder.child("jsons/translations/$lang.properties")
            val exists = modFile.exists()
            val row = Table(BaseScreen.skin)
            row.defaults().pad(6f)
            row.background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/LangRow", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                BaseScreen.skinStrings.skinConfig.baseColor)
            val nameLabel = lang.toLabel(fontSize = 22,
                fontColor = if (exists) Color.WHITE else Color(1f, 0.9f, 0.6f, 1f))
            row.add(nameLabel).left().expandX()
            row.add((if (exists) "Translation file: Open".tr() else "Translation file: Create".tr())
                .toLabel(fontSize = 13,
                    fontColor = if (exists) Color(0.5f, 0.9f, 0.5f, 1f) else Color(1f, 0.85f, 0.4f, 1f)))
                .right().padRight(8f)
            row.touchable = Touchable.enabled
            row.onActivation { openLanguage(lang) }
            contentTable.add(row).fillX().pad(2f, 8f, 2f, 8f).row()
        }
    }

    /** 打开（或创建）语言文件：只写官方没有翻译的 key（原版有翻译的直接用原版） */
    private fun openLanguage(lang: String) {
        language = lang
        val modFile = modFolder.child("jsons/translations/$lang.properties")
        if (!modFile.exists()) {
            officialTranslations = ModEditorData.readOfficialTranslationFile(lang)
            translations = LinkedHashMap()
            val needed = ModEditorData.scanModTranslatableStrings(modFolder)
            for (key in needed) {
                // 原版已有翻译的不加（游戏直接用原版翻译），只加官方没有翻译的
                if (officialTranslations[key] == null) translations[key] = ""
            }
            // 新建文件直接写入缺失词条（空 value），避免编辑页空白
            ModEditorData.writeModTranslationFile(modFolder, lang, translations)
        } else {
            translations = ModEditorData.readModTranslationFile(modFolder, lang)
            officialTranslations = ModEditorData.readOfficialTranslationFile(lang)
        }
        refreshMissing()
        showEntriesPage()
    }

    // ------------------------------------------------------------------
    // 页面 2：词条编辑（原文模式：一个大 TextArea）
    // ------------------------------------------------------------------

    private fun showEntriesPage() {
        contentTable.clear()
        titleLabel.setText(language + " · jsons/translations/")
        backAction = { showLanguagePickerPage() }
        refreshEntries()
    }

    private fun refreshEntries() {
        // 编辑页：外层滚轮禁用（编辑框内部滚动优先）
        outerScroll.setScrollingDisabled(true, true)
        contentTable.clear()
        titleLabel.setText(language + " · jsons/translations/")
        backAction = { showLanguagePickerPage() }

        // 顶部：按钮行（切换语言旁边放搜索框）
        val buttonRow = Table(BaseScreen.skin)
        val scanButton = "Auto-add missing".toTextButton()
        scanButton.onActivation { autoAddMissing() }
        buttonRow.add(scanButton).left().pad(6f)
        val nextButton = "Next untranslated".toTextButton()
        nextButton.onActivation { goToNextUntranslated() }
        buttonRow.add(nextButton).left().pad(6f)
        val switchButton = "Switch language".toTextButton()
        switchButton.onActivation { showLanguagePickerPage() }
        buttonRow.add(switchButton).left().pad(6f)
        val missingCount = missingKeys.size
        buttonRow.add(("Missing: $missingCount".tr()).toLabel(
            fontSize = 13,
            fontColor = if (missingCount > 0) Color(1f, 0.7f, 0.3f, 1f) else Color(0.6f, 0.9f, 0.6f, 1f)))
            .left().pad(6f)
        // 搜索框：输入不跳转（避免误操作），点 Search 按钮才跳到下一处匹配
        searchField = UncivTextField("Search")
        val searchBtn = "Search".toTextButton()
        searchBtn.onActivation { jumpToNextSearchMatch(searchField.text, fromStart = false) }
        buttonRow.add(searchBtn).left().pad(6f)
        buttonRow.add(searchField).growX().minWidth(180f).pad(6f)
        contentTable.add(buttonRow).fillX().left().row()

        // 原文模式：一个大 TextArea，每行 key = value，直接编辑
        // 不用 ScrollPane（它拦截鼠标拖动导致多选困难 + 与 TextArea 内部滚动冲突）：
        // TextArea 原生多选 + 原生光标滚动；右侧自定义滚动条（可点击/拖动跳转）
        searchActive = false
        fullRawText = ""
        val rawText = buildRawText()
        rawArea = TextArea(rawText, BaseScreen.skin)
        rawArea.setTextFieldListener { _, _ -> }
        // TextArea 高度 = 编辑区高度（内部光标滚动，不撑高页面）
        val editHeight = maxOf(400f, stage.height - 140f)
        rawArea.setPrefRows(editHeight / 24f) // 约可见行数

        // 右侧自定义滚动条：track + knob（可点击跳转、可拖动）
        val scrollBar = Table(BaseScreen.skin).apply {
            background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/ScrollTrack", null, Color(1f, 1f, 1f, 0.08f))
        }
        val knob = Table(BaseScreen.skin).apply {
            background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/ScrollKnob", null, Color(1f, 1f, 1f, 0.4f))
        }
        knob.setSize(10f, 40f)
        scrollBar.addActor(knob) // addActor：knob 独立定位（不走 Table 布局）
        scrollBar.touchable = Touchable.enabled
        var dragging = false

        /** 光标跳转到文本的指定比例位置（TextArea 原生自动滚动光标到可见） */
        fun jumpToProgress(progress: Float) {
            val text = rawArea.text
            val lines = text.split('\n')
            if (lines.isEmpty()) return
            val total = lines.size
            val targetLine = (progress * total).toInt().coerceIn(0, total - 1)
            val offset = lines.take(targetLine).sumOf { it.length + 1 }
            rawArea.setCursorPosition(offset)
            rawArea.setSelection(offset, offset)
        }

        /** 刷新滚动条滑块位置（按光标所在行比例）；已拖动时不覆盖 */
        fun updateKnob() {
            if (dragging) return
            val text = rawArea.text
            val lines = text.split('\n')
            if (lines.isEmpty()) return
            val cursorLine = text.take(rawArea.cursorPosition).count { it == '\n' }
            val progress = cursorLine.toFloat() / lines.size
            val trackH = scrollBar.height.coerceAtLeast(1f)
            val knobH = (trackH * 0.15f).coerceIn(30f, 80f)
            knob.setSize(10f, knobH)
            knob.setPosition(0f, (1f - progress) * (trackH - knobH))
        }

        /** 刷新滚动条滑块位置（按光标所在行比例）；已拖动时不覆盖 */
        scrollBar.addListener(object : com.badlogic.gdx.scenes.scene2d.InputListener() {
            override fun touchDown(
                event: com.badlogic.gdx.scenes.scene2d.InputEvent?,
                x: Float, y: Float, pointer: Int, button: Int
            ): Boolean {
                dragging = true
                val trackH = scrollBar.height.coerceAtLeast(1f)
                val knobH = (trackH * 0.15f).coerceIn(30f, 80f)
                knob.setSize(10f, knobH)
                val p = (1f - y / trackH).coerceIn(0f, 1f)
                knob.setPosition(0f, (1f - p) * (trackH - knobH))
                jumpToProgress(p)
                return true
            }
            override fun touchDragged(
                event: com.badlogic.gdx.scenes.scene2d.InputEvent?,
                x: Float, y: Float, pointer: Int
            ) {
                // 拖动：滑块直接跟随鼠标 y（更新滑块位置），光标跳转跟随
                val trackH = scrollBar.height.coerceAtLeast(1f)
                val knobH = knob.height
                val p = (1f - y / trackH).coerceIn(0f, 1f)
                knob.setPosition(0f, (1f - p) * (trackH - knobH))
                jumpToProgress(p)
            }
            override fun touchUp(
                event: com.badlogic.gdx.scenes.scene2d.InputEvent?,
                x: Float, y: Float, pointer: Int, button: Int
            ) {
                dragging = false
            }
        })
        // 滚轮：驱动光标滚动（TextArea 原生滚动到光标可见）
        rawArea.addListener(object : com.badlogic.gdx.scenes.scene2d.InputListener() {
            override fun scrolled(
                event: com.badlogic.gdx.scenes.scene2d.InputEvent?,
                x: Float, y: Float,
                scrollAmountX: Float, scrollAmountY: Float
            ): Boolean {
                val step = if (scrollAmountY > 0) 1 else -1
                repeat(5) { rawArea.moveCursorLine(step) } // 每次约 5 行
                updateKnob()
                return true
            }
        })
        rawArea.addListener(object : com.badlogic.gdx.scenes.scene2d.utils.FocusListener() {
            override fun keyboardFocusChanged(event: com.badlogic.gdx.scenes.scene2d.utils.FocusListener.FocusEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?, focused: Boolean) {
                updateKnob()
            }
        })
        updateKnob()

        val editRow = Table(BaseScreen.skin)
        editRow.add(rawArea).growX().growY().pad(6f)
        editRow.add(scrollBar).width(14f).growY().pad(6f)
        contentTable.add(editRow).growX().height(maxOf(400f, stage.height - 140f)).left().pad(6f).row()

        val hint = ("Format: key equals value, one per line. Official translations are used automatically when available; only fill in what is missing".tr())
            .toLabel(fontSize = 12, fontColor = Color(1f, 1f, 1f, 0.45f))
        hint.wrap = true
        contentTable.add(hint).growX().left().pad(0f, 8f, 6f, 8f).row()

        if (rawText.isBlank()) {
            contentTable.add(("No translatable strings found in this mod. Add content to your mod's jsons first, or check the mod folder".tr())
                .toLabel(fontSize = 16, fontColor = Color(1f, 0.85f, 0.4f, 1f)))
                .left().pad(10f).row()
        }
    }

    /** 判定一行是否为未翻译条目（key = 空 value）；不 trim 尾部（否则 key = 会变 key = 丢失判定） */
    private fun isUntranslatedLine(rawLine: String): Boolean {
        val line = rawLine.trimStart()
        if (line.startsWith('#')) return false
        val eq = line.indexOf(" = ")
        if (eq <= 0) return false
        val value = line.substring(eq + 3)
        return value.isBlank() // 等号后空白 = 未翻译
    }

    /** 跳到下一个未翻译词条（文件里 key = 空 value 的行）；从当前光标后找，循环回开头 */
    /** 搜索跳转：从光标位置之后找下一个包含关键词的行并跳转（不修改文本）；fromStart=true 从头找 */
    private fun jumpToNextSearchMatch(query: String, fromStart: Boolean) {
        if (!::rawArea.isInitialized) return
        val q = query.trim().lowercase()
        if (q.isEmpty()) return
        val text = rawArea.text
        val lines = text.split('\n')
        if (lines.isEmpty()) return
        val cursorLine = text.take(rawArea.cursorPosition).count { it == '\n' }
        // 从光标后找第一个匹配
        val target = lines.indices.firstOrNull { idx ->
            if (!fromStart && idx <= cursorLine) return@firstOrNull false
            lines[idx].lowercase().contains(q)
        } ?: lines.indices.firstOrNull { idx ->
            // 循环回开头
            lines[idx].lowercase().contains(q)
        }
        if (target == null) {
            showMessage("No matches".tr())
            return
        }
        val offset = lines.take(target).sumOf { it.length + 1 }
        rawArea.setCursorPosition(offset)
        rawArea.setSelection(offset, offset + lines[target].length)
        stage.keyboardFocus = rawArea
        // 同步滚动条
        if (::rawArea.isInitialized) {
            // 触发滚动条刷新（走 textFieldListener 同路径）
            rawArea.invalidate()
        }
    }

    private fun goToNextUntranslated() {
        if (!::rawArea.isInitialized) return
        if (searchField.text.trim().isNotEmpty()) {
            mergeFilteredBack()
            rawArea.setText(fullRawText)
        }
        val text = rawArea.text
        val lines = text.split('\n')
        val cursorLine = text.take(rawArea.cursorPosition).count { it == '\n' }
        val target = lines.indices.firstOrNull { lineIdx ->
            if (lineIdx <= cursorLine) return@firstOrNull false
            isUntranslatedLine(lines[lineIdx])
        } ?: lines.indices.firstOrNull { lineIdx ->
            // 循环回开头找第一个未翻译
            isUntranslatedLine(lines[lineIdx])
        }
        if (target == null) {
            showMessage("No untranslated lines. Use Auto-add missing to add empty entries first".tr())
            return
        }
        // 定位到该行开头（光标移动自动滚动到可见区）
        val offset = lines.take(target).sumOf { it.length + 1 }
        rawArea.setCursorPosition(offset)
        rawArea.setSelection(offset, offset + lines[target].length)
        stage.keyboardFocus = rawArea
    }

    private fun showMessage(message: String) {
        statusLabel.setText(message)
    }

    /** 搜索 = 定位：只显示匹配行（不匹配行暂藏，编辑内容保留在 fullRawText） */
    private fun applySearchFilter(query: String) {
        if (!::rawArea.isInitialized) return
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            // 清空搜索：先把当前编辑的匹配行合并回备份，再恢复完整显示
            mergeFilteredBack()
            rawArea.setText(fullRawText)
        } else {
            // 首次进入搜索（或搜索词变化）：备份当前完整文本，只显示匹配行
            if (!searchActive) {
                fullRawText = rawArea.text
                searchActive = true
            } else {
                // 搜索词变化：先合并当前编辑的匹配行，再按新词过滤
                mergeFilteredBack()
            }
            val lines = fullRawText.lines()
            val filtered = lines.filter { it.isNotBlank() && it.lowercase().contains(q) }.joinToString("\n")
            rawArea.setText(filtered)
        }
    }

    /** 把当前 TextArea（匹配行）的编辑合并回 fullRawText（按 key 匹配，value 用编辑后的） */
    private fun mergeFilteredBack() {
        if (fullRawText.isEmpty()) return
        // 解析编辑后的行：key → 完整行（保留用户编辑的 value）
        val editedByKey = LinkedHashMap<String, String>()
        for (line in rawArea.text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith('#')) continue
            val idx = trimmed.indexOf(" = ")
            if (idx <= 0) continue
            editedByKey[trimmed.substring(0, idx).trim()] = trimmed
        }
        // 按 key 替换 fullLines 中对应行（编辑过的行用新内容，未编辑的保持原样）
        val result = fullRawText.lines().map { fullLine ->
            val trimmed = fullLine.trim()
            if (trimmed.isBlank() || trimmed.startsWith('#')) return@map fullLine
            val idx = trimmed.indexOf(" = ")
            if (idx <= 0) return@map fullLine
            val key = trimmed.substring(0, idx).trim()
            editedByKey[key] ?: fullLine
        }
        fullRawText = result.joinToString("\n")
    }

    /** 原文模式文本：所有翻译条目 key = value 每行一条（缺失的 value 留空） */
    private fun buildRawText(): String {
        val sb = StringBuilder()
        for ((key, value) in translations) {
            sb.append(key).append(" = ").append(value).append('\n')
        }
        return sb.toString()
    }

    /** 解析原文 TextArea（每行 key = value）回 translations map；搜索态先合并备份 */
    private fun parseRawArea() {
        if (!::rawArea.isInitialized) return
        if (searchField.text.trim().isNotEmpty()) mergeFilteredBack()
        val source = if (searchField.text.trim().isNotEmpty()) fullRawText else rawArea.text
        val newTranslations = LinkedHashMap<String, String>()
        for (line in source.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith('#')) continue
            val idx = trimmed.indexOf(" = ")
            if (idx <= 0) continue
            val key = trimmed.substring(0, idx).trim()
            val value = trimmed.substring(idx + 3).trim()
            if (key.isNotEmpty()) newTranslations[key] = value
        }
        translations = newTranslations
        refreshMissing()
    }

    /** 自动添加缺失词条（用户点按钮触发）：只补官方没有翻译的 */
    private fun autoAddMissing() {
        if (language.isEmpty()) return
        parseRawArea()
        val needed = ModEditorData.scanModTranslatableStrings(modFolder)
        var added = 0
        for (key in needed) {
            if (key !in translations && officialTranslations[key] == null) {
                translations[key] = ""
                added++
            }
        }
        refreshMissing()
        refreshEntries()
        statusLabel.setText("Auto-added".tr() + ": $added")
    }

    private fun refreshMissing() {
        missingKeys.clear()
        if (language.isEmpty()) return
        val needed = ModEditorData.scanModTranslatableStrings(modFolder)
        for (key in needed) {
            if (key !in translations && officialTranslations[key] == null) missingKeys.add(key)
        }
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    private fun save() {
        if (language.isEmpty()) return
        parseRawArea()
        ModEditorData.writeModTranslationFile(modFolder, language, translations)
        statusLabel.setText("Saved".tr())
        refreshEntries()
    }

    private fun separatorLine(): Table = Table(BaseScreen.skin).apply {
        background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/Separator", null, Color(1f, 1f, 1f, 0.18f))
    }
}

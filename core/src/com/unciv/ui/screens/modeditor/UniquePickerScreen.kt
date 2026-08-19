package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
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
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 * 词条选择器（v2）：①官方分类 → ②词条列表（英文 key + 中文翻译）
 * 选中后直接返回给编辑器行内编辑（不再有参数/条件页）
 */
class UniquePickerScreen(
    private val onPick: (CatalogUnique) -> Unit,
    private val onRawPicked: ((String) -> Unit)? = null,
    private val onlyCategory: String? = null
) : BaseScreen() {

    private val catalog = UniqueCatalog.load()
    private val contentTable = Table(BaseScreen.skin)
    private val titleLabel = "".toLabel(fontSize = 28)
    private val backButton = ("‹ " + "Back".tr()).toTextButton()

    private var currentCategory = ""

    /** 返回按钮行为：每页只重赋值，避免 onActivation 累积导致多级回退 */
    private var backAction: () -> Unit = { game.popScreen() }

    init {
        val root = Table(BaseScreen.skin)
        root.setFillParent(true)
        stage.addActor(root)

        val topBar = Table(BaseScreen.skin)
        backButton.onActivation { backAction() }
        topBar.add(backButton).pad(8f)
        topBar.add(titleLabel).padLeft(20f).expandX().left()
        // 搜索按钮：在原文模式旁边，跨全部分类直接搜词条（英文/中文都行）
        val searchButton = "Search".toTextButton()
        searchButton.onActivation { showSearchPopup() }
        topBar.add(searchButton).pad(8f)
        if (onRawPicked != null) {
            val rawButton = "Raw mode".toTextButton()
            rawButton.onActivation { showRawModePopup() }
            topBar.add(rawButton).pad(8f)
        }
        root.add(topBar).fillX().row()

        val scroll = AutoScrollPane(contentTable)
        scroll.setOverscroll(false, false)
        root.add(scroll).expand().grow()

        showCategories()
        // 限定单一分类时（如里程碑），直接进入该分类的词条列表
        if (onlyCategory != null) {
            currentCategory = onlyCategory
            showUniques()
        }
    }

    // ------------------------------------------------------------------
    // 页面 1：官方分类
    // ------------------------------------------------------------------

    private fun showCategories() {
        contentTable.clear()
        titleLabel.setText("Select a category".tr())
        backAction = { game.popScreen() }
        for (category in catalog.categories) {
            val count = catalog.byCategory(category).size
            val row = Table(BaseScreen.skin)
            row.defaults().pad(10f)
            val available = count > 0
            row.background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/CategoryRow", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                if (available) BaseScreen.skinStrings.skinConfig.baseColor
                else Color(1f, 1f, 1f, 0.06f))
            // 双语：英文（中文），与下拉框一致；翻译相同时只显示英文
            val translated = category.tr()
            val labelText = if (translated != category) "$category（$translated）" else category
            row.add(labelText.toLabel(fontSize = 26,
                fontColor = if (available) Color.WHITE else Color(1f, 1f, 1f, 0.35f)))
                .left().expandX()
            if (available) {
                row.add("$count".toLabel(fontSize = 18, fontColor = Color(1f, 1f, 1f, 0.5f))).right()
                row.add(ImageGetter.getImage("OtherIcons/ArrowRight").apply { setSize(22f, 22f) })
                    .size(22f).padLeft(10f)
            } else {
                row.add("0 · To be added".toLabel(fontSize = 16, fontColor = Color(1f, 1f, 1f, 0.35f)))
                    .right().padRight(8f)
            }
            if (available) {
                row.touchable = Touchable.enabled
                row.onActivation {
                    currentCategory = category
                    showUniques()
                }
            }
            contentTable.add(row).growX().pad(4f, 12f, 4f, 12f).row()
        }
    }

    // ------------------------------------------------------------------
    // 页面 2：词条列表（英文 key + 中文翻译，参数名不翻译）
    // ------------------------------------------------------------------

    private fun showUniques() {
        contentTable.clear()
        titleLabel.setText(currentCategory.tr())
        backAction = { showCategories() }

        for (unique in catalog.byCategory(currentCategory)) {
            val row = Table(BaseScreen.skin)
            row.defaults().pad(6f)
            row.background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/UniqueRow", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                BaseScreen.skinStrings.skinConfig.baseColor)
            val textTable = Table(BaseScreen.skin)
            // 第一行：原始英文 key（不经 tr()，防止被游戏翻译表命中）
            val keyLabel = Label(unique.key, BaseScreen.skin).apply {
                setFontScale(20f / Fonts.ORIGINAL_FONT_SIZE)
                setAlignment(Align.left)
                wrap = true
            }
            textTable.add(keyLabel).growX().left().row()
            // 第二行：中文翻译（参数名不翻译）
            val displayLabel = unique.display.toLabel(fontSize = 14, fontColor = Color(0.7f, 0.85f, 1f, 1f))
            displayLabel.wrap = true
            textTable.add(displayLabel).growX().left().padTop(2f).row()
            row.add(textTable).growX().minWidth(400f).left().pad(6f)
            row.add(ImageGetter.getImage("OtherIcons/ArrowRight").apply { setSize(20f, 20f) })
                .size(20f).padRight(8f)
            row.touchable = Touchable.enabled
            row.onActivation {
                onPick(unique)
                game.popScreen()
            }
            contentTable.add(row).growX().pad(3f, 12f, 3f, 12f).row()
        }
    }

    /**
     * 搜索词条：英文 key 或中文翻译都行，跨全部分类；点选 = 同分类列表点选（onPick + 返回）。
     * 只搜效果词条（范围/条件在行内编辑器的「添加范围」里搜）。
     * 弹窗加宽：长参数词条（如 when number of [countable] is more than [countable]）整行显示不裁切。
     */
    private fun showSearchPopup() {
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        popup.add("Search uniques".tr().toLabel(fontSize = 22)).pad(8f).row()

        val searchField = UncivTextField("Search")
        popup.add(searchField).growX().width(720f).pad(6f).row()

        val listTable = Table(BaseScreen.skin)
        val listScroll = AutoScrollPane(listTable).apply {
            setOverscroll(false, false)
            fadeScrollBars = false
        }
        popup.add(listScroll).grow().width(720f).height(380f).pad(6f).row()

        fun refresh(query: String) {
            listTable.clear()
            val q = query.trim().lowercase()
            var shown = 0
            for (unique in catalog.uniques) {
                if (q.isNotEmpty() &&
                    !unique.key.lowercase().contains(q) &&
                    !unique.display.lowercase().contains(q)) continue
                val row = Table(BaseScreen.skin)
                row.background = BaseScreen.skinStrings.getUiBackground(
                    "ModEditor/SearchRow", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                    BaseScreen.skinStrings.skinConfig.baseColor)
                // 双语：英文 key（翻译），占满整行（不放分类提示，长词条也能完整显示）
                row.add(bilingualUniqueLabel(unique.key, unique.display, 15f))
                    .growX().left().pad(6f, 8f, 6f, 8f)
                row.touchable = Touchable.enabled
                row.onActivation {
                    onPick(unique)
                    game.popScreen()
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

    /** 原文模式弹层：直接输入英文原词条（搜索已移到顶栏 Search 按钮） */
    private fun showRawModePopup() {
        val popup = Popup(this, scrollable = Popup.Scrollability.None)
        popup.add("Unique (raw mode)".toLabel(fontSize = 22)).pad(10f).row()
            popup.add("No quotes needed - they are added automatically when saving".tr().toLabel(
                fontSize = 12, fontColor = com.badlogic.gdx.graphics.Color(1f, 1f, 1f, 0.45f))).pad(0f, 10f, 4f, 10f).row()

        val textArea = TextArea("", BaseScreen.skin)
        popup.add(textArea).width(560f).height(160f).pad(6f).row()

        popup.addButton("Save") {
            // 换行只是显示，写入代码前必须清洗为单行
            val text = textArea.text.replace('\n', ' ').replace('\r', ' ')
                .replace(Regex("\\s{2,}"), " ").trim()
            if (text.isNotEmpty()) {
                onRawPicked?.invoke(text)
                game.popScreen()
            }
            popup.close()
        }
        popup.addCloseButton()
        popup.open()
    }
}

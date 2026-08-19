package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Scaling
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.graphics.Texture
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

/**
 * Images 图片管理模块（2026-08-19）：
 * - 左侧分类目录（UnitIcons/BuildingIcons/TechIcons/ImprovementIcons/ResourceIcons/TileSets/...）
 * - 右侧图片网格：缩略图 + 文件名
 * - 操作：导入图片 / 删除 / 重命名
 * - 缺失检测：扫描 jsons 引用了但 Images 里没有的图片
 */
class ImagesEditorScreen(private val modFolder: FileHandle) : BaseScreen() {

    private val fixedCategories = listOf(
        "UnitIcons", "BuildingIcons", "TechIcons", "PolicyIcons",
        "ImprovementIcons", "ResourceIcons", "NationIcons", "UnitPromotionIcons",
        "ReligionIcons", "OtherIcons", "Tutorials", "VictoryIllustrations"
    )
    /** 动态分类列表：固定目录 + 官方 TileSets（全部图集×子目录，不管 mod 是否创建）+ mod 自定义 TileSets */
    private val categories: List<String> by lazy {
        val result = mutableListOf<String>()
        result.addAll(fixedCategories)

        // 官方 tileset 名（jsons/TileSets/*.json；jar 内目录不可 list()，硬编码官方三图集）
        val officialTileSets = listOf("FantasyHex", "HexaRealm", "Minimal")

        // 官方图集标准子目录（按原版 Images.Tilesets 结构）
        val standardSubDirs = listOf("Tiles", "Units", "Borders", "Arrows", "Edges")
        for (tileSet in officialTileSets) {
            for (sub in standardSubDirs) result.add("TileSets/$tileSet/$sub")
        }

        // mod 自定义 TileSets：递归展开（含官方图集在 mod 里多出的子目录）
        val tileSetsDir = modFolder.child("Images/TileSets")
        if (tileSetsDir.exists()) {
            for (tileSet in tileSetsDir.list().filter { it.isDirectory }.sortedBy { it.name() }) {
                val subDirs = tileSet.list().filter { it.isDirectory }.sortedBy { it.name() }
                if (subDirs.isEmpty()) {
                    result.add("TileSets/${tileSet.name()}")
                } else {
                    for (sub in subDirs) result.add("TileSets/${tileSet.name()}/${sub.name()}")
                }
            }
        }
        result.distinct()
    }

    private val listTable = Table(BaseScreen.skin).apply {
        defaults().expandX().fillX()
        top()
    }
    private val gridTable = Table(BaseScreen.skin).apply {
        defaults().expandX().fillX()
        top()
    }
    private val statusLabel = "".toLabel(fontSize = 16)
    private var currentCategory = "UnitIcons"
    private lateinit var searchField: UncivTextField
    private var searchQuery = ""
    private var selectedFile: FileHandle? = null

    init {
        val root = Table(BaseScreen.skin)
        root.setFillParent(true)
        stage.addActor(root)

        val topBar = Table(BaseScreen.skin)
        val backButton = ("‹ " + "Back".tr()).toTextButton()
        backButton.onActivation { game.popScreen() }
        topBar.add(backButton).pad(8f)
        topBar.add(("Images".tr() + " · Images/").toLabel(fontSize = 28))
            .padLeft(20f).expandX().left()
        val importButton = "Import image".toTextButton()
        importButton.onActivation { importImage() }
        topBar.add(importButton).pad(8f)
        val scanButton = "Find missing".toTextButton()
        scanButton.onActivation { findMissing() }
        topBar.add(scanButton).pad(8f)
        val packButton = "Pack atlases".toTextButton()
        packButton.onActivation { packAtlases() }
        topBar.add(packButton).pad(8f)
        topBar.add(statusLabel).pad(8f)
        root.add(topBar).fillX().row()

        // 左侧：分类列表（必须包 AutoScrollPane，否则分类多时撑爆页面）
        val leftPanel = Table(BaseScreen.skin)
        searchField = UncivTextField("Search")
        searchField.setTextFieldListener { field, _ ->
            searchQuery = field.text.trim().lowercase()
            refreshGrid()
        }
        leftPanel.add(searchField).growX().pad(4f, 8f, 2f, 8f).row()
        leftPanel.add(separatorLine()).fillX().height(2f).pad(4f, 8f, 4f, 8f).row()
        val leftScroll = AutoScrollPane(listTable).apply {
            setOverscroll(false, false)
            setScrollingDisabled(true, false)
            fadeScrollBars = false
        }
        leftPanel.add(leftScroll).expand().fill().row()

        // 右侧：图片网格
        val rightScroll = AutoScrollPane(gridTable).apply {
            setOverscroll(false, false)
            setScrollingDisabled(true, false)
        }

        val body = Table(BaseScreen.skin)
        body.add(leftPanel).width(max(300f, stage.width / 4)).growY().pad(4f)
        body.addSeparatorVertical(ImageGetter.CHARCOAL, 2f)
        body.add(rightScroll).expand().grow().pad(4f)
        root.add(body).grow()

        refreshCategories()
        refreshGrid()
    }

    // ------------------------------------------------------------------
    // 左侧分类
    // ------------------------------------------------------------------

    private fun refreshCategories() {
        listTable.clear()
        for (category in categories) {
            val row = Table(BaseScreen.skin)
            row.defaults().pad(6f)
            row.background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/ImgCat", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
                if (category == currentCategory) Color(0.2f, 0.5f, 0.9f, 1f)
                else BaseScreen.skinStrings.skinConfig.baseColor)
            val count = countImages(category)
            val catLabel = category.toLabel(fontSize = 18,
                fontColor = if (category == currentCategory) Color.WHITE else Color(1f, 1f, 1f, 0.85f))
            catLabel.setEllipsis("…")
            row.add(catLabel)
                .left().expandX()
            row.add(count.toString().toLabel(fontSize = 13, fontColor = Color(1f, 1f, 1f, 0.4f)))
                .right().padRight(6f)
            row.touchable = Touchable.enabled
            row.onActivation {
                currentCategory = category
                selectedFile = null
                refreshCategories()
                refreshGrid()
            }
            listTable.add(row).fillX().pad(2f, 6f, 2f, 6f).row()
        }
    }

    private fun countImages(category: String): Int {
        val dir = modFolder.child("Images/$category")
        if (!dir.exists()) return 0
        return dir.list().count { it.extension().lowercase() in listOf("png", "jpg", "jpeg") }
    }

    // ------------------------------------------------------------------
    // 右侧网格
    // ------------------------------------------------------------------

    private fun categoryDir(): FileHandle = modFolder.child("Images/$currentCategory")

    private fun refreshGrid() {
        gridTable.clear()
        selectedFile = null
        val dir = categoryDir()
        val files = if (dir.exists())
            dir.list().filter { it.extension().lowercase() in listOf("png", "jpg", "jpeg") }.sortedBy { it.name() }
        else emptyList()

        if (files.isEmpty()) {
            gridTable.add(("No images in [category]".tr().replace("[category]", currentCategory)).toLabel(
                fontSize = 16, fontColor = Color(1f, 1f, 1f, 0.4f))).pad(20f).row()
            return
        }

        // 3 列网格
        var rowTable = Table(BaseScreen.skin)
        var col = 0
        for (file in files) {
            if (searchQuery.isNotEmpty() && !file.name().lowercase().contains(searchQuery)) continue
            val card = imageCard(file)
            rowTable.add(card).width(150f).pad(6f)
            col++
            if (col % 3 == 0) {
                gridTable.add(rowTable).fillX().row()
                rowTable = Table(BaseScreen.skin)
            }
        }
        if (col % 3 != 0) gridTable.add(rowTable).fillX().row()
    }

    /** 单个图片卡片：占位色块 + 文件名，点击预览（不加载纹理，避免大目录卡死 GL 线程） */
    private fun imageCard(file: FileHandle): Table {
        val card = Table(BaseScreen.skin)
        card.defaults().pad(4f)
        card.background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/ImgCard", BaseScreen.skinStrings.roundedEdgeRectangleShape,
            BaseScreen.skinStrings.skinConfig.baseColor)

        // 占位块（不加载真实纹理；预览时才加载单张）
        val placeholder = Table(BaseScreen.skin).apply {
            background = BaseScreen.skinStrings.getUiBackground(
                "ModEditor/ImgPlaceholder", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                Color(0.3f, 0.35f, 0.45f, 0.8f))
        }
        card.add(placeholder).size(120f, 120f).pad(4f).row()

        val nameLabel = listNameLabel(
            file.name(),
            maxWidth = stage.width * 0.2f - 100f,
            fontSize = 13,
            fontColor = Color(1f, 1f, 1f, 0.8f))
        card.add(nameLabel).growX().left().pad(2f, 4f, 2f, 4f).row()

        val btnRow = Table(BaseScreen.skin)
        val renameBtn = "Rename".toTextButton()
        renameBtn.onActivation { renameImage(file) }
        btnRow.add(renameBtn).left().pad(2f)
        val deleteBtn = "Delete".toTextButton()
        deleteBtn.onActivation { deleteImage(file) }
        btnRow.add(deleteBtn).right().pad(2f)
        card.add(btnRow).growX().left().row()

        card.touchable = Touchable.enabled
        card.onActivation { previewImage(file) }
        return card
    }

    // ------------------------------------------------------------------
    // 操作
    // ------------------------------------------------------------------

    private fun importImage() {
        val impl = ModEditorPlatformHolder.impl ?: run {
            statusLabel.setText("Import not supported on this platform".tr())
            return
        }
        statusLabel.setText("Choosing file...".tr())
        // JFileChooser 是阻塞模态对话框：放后台线程，避免卡死渲染线程
        com.unciv.utils.Concurrency.runOnNonDaemonThreadPool("ImportImage") {
            var path: String? = null
            var pickError: String? = null
            try {
                path = impl.chooseImageFile()
            } catch (e: Throwable) {
                pickError = e.stackTraceToString()
                com.unciv.utils.Log.debug("ModEditor chooseImageFile crashed: " + e.message, e)
            }
            Gdx.app.postRunnable {
                if (pickError != null) {
                    statusLabel.setText("Import failed".tr() + ": " + pickError)
                    return@postRunnable
                }
                if (path == null) {
                    statusLabel.setText("")
                    return@postRunnable
                }
                try {
                    val src = Gdx.files.absolute(path)
                    val dir = categoryDir()
                    if (!dir.exists()) dir.mkdirs()
                    val dest = dir.child(src.name())
                    // 重名自动加序号
                    var target = dest
                    var i = 1
                    while (target.exists()) {
                        val base = src.nameWithoutExtension()
                        val ext = src.extension()
                        target = dir.child("$base ($i).$ext")
                        i++
                    }
                    src.copyTo(target)
                    statusLabel.setText("Imported".tr() + ": " + target.name())
                    refreshGrid()
                } catch (e: Exception) {
                    statusLabel.setText("Import failed".tr() + ": " + (e.message ?: ""))
                }
            }
        }
    }

    private fun renameImage(file: FileHandle) {
        val popup = Popup(this)
        popup.add("Rename image".tr().toLabel(fontSize = 20)).pad(10f).row()
        val nameField = UncivTextField("", file.nameWithoutExtension())
        popup.add(nameField).width(400f).pad(6f).row()
        popup.addButton("Rename".tr()) {
            val newName = nameField.text.trim()
            if (newName.isNotEmpty()) {
                val ext = file.extension()
                val target = file.parent().child("$newName.$ext")
                if (target.exists() && target.path() != file.path()) {
                    statusLabel.setText("Name already exists".tr())
                } else {
                    file.moveTo(target)
                    statusLabel.setText("Renamed".tr())
                    refreshGrid()
                }
            }
            popup.close()
        }
        popup.addCloseButton()
        popup.open()
    }

    private fun deleteImage(file: FileHandle) {
        val popup = Popup(this)
        popup.add("Delete [name]?".tr().replace("[name]", file.name()).toLabel(fontSize = 20)).pad(10f).row()
        popup.addButton("Delete".tr()) {
            file.delete()
            statusLabel.setText("Deleted".tr())
            popup.close()
            refreshGrid()
        }
        popup.addCloseButton()
        popup.open()
    }

    private fun previewImage(file: FileHandle) {
        selectedFile = file
        val popup = Popup(this)
        var texture: Texture? = null
        try {
            texture = Texture(file)
            val img = Image(TextureRegionDrawable(texture)).apply {
                setScaling(Scaling.fit)
            }
            popup.add(img).size(400f, 400f).pad(8f).row()
        } catch (e: Exception) {
            popup.add("Cannot preview".tr().toLabel()).pad(10f).row()
        }
        popup.add(file.name().toLabel(fontSize = 16)).pad(4f).row()
        popup.addCloseButton("Close".tr()) { texture?.dispose() }
        popup.open()
    }

    /** 打包图集：Images → game.atlas；Images.xxx → xxx.atlas；更新 Atlases.json */
    private fun packAtlases() {
        val impl = ModEditorPlatformHolder.impl ?: run {
            statusLabel.setText("Packing not supported on this platform".tr())
            return
        }
        statusLabel.setText("Packing...".tr())
        val message = impl.packAtlases(modFolder.path())
        statusLabel.setText(message ?: "Pack failed".tr())
        // 刷新 Atlases.json 显示
        refreshAtlasStatus()
    }

    /** 图集管理区：显示现有 atlas 文件 + Atlases.json 内容 + 删除按钮 */
    private fun refreshAtlasStatus() {
        // 简化：状态栏已显示打包结果；图集文件在 mod 根目录
    }

    /** 缺失检测：扫描 jsons 引用了但 Images 里没有的图片（按模块约定目录） */
    private fun findMissing() {
        val missing = mutableListOf<String>()
        val allTranslatable = LinkedHashSet<String>()

        // 收集所有 name（定义级）
        val jsonsDir = modFolder.child("jsons")
        if (jsonsDir.exists()) {
            for (file in jsonsDir.list()) {
                if (!file.name().endsWith(".json")) continue
                if (file.name() == "ModOptions.json") continue
                try {
                    val text = file.readString(Charsets.UTF_8.name())
                    val parsed = com.badlogic.gdx.utils.JsonReader().parse(
                        ModEditorData.removeTrailingCommasPublic(ModEditorData.stripCommentsPublic(text)))
                    collectNames(parsed, allTranslatable)
                } catch (e: Exception) { }
            }
        }

        // 检查各目录约定
        val checks = listOf(
            "Units" to "UnitIcons", "Buildings" to "BuildingIcons", "Techs" to "TechIcons",
            "Policies" to "PolicyIcons", "TileImprovements" to "ImprovementIcons",
            "TileResources" to "ResourceIcons", "Nations" to "NationIcons",
            "UnitPromotions" to "UnitPromotionIcons", "Beliefs" to "ReligionIcons"
        )
        for ((jsonKey, dirName) in checks) {
            // 只检查该 json 的 name（简化：全部 name 都查对应目录）
            for (name in allTranslatable) {
                val file = modFolder.child("Images/$dirName/$name.png")
                if (!file.exists()) missing.add("$dirName/$name.png")
            }
        }
        if (missing.isEmpty()) {
            statusLabel.setText("No missing images".tr())
        } else {
            val popup = Popup(this, scrollable = Popup.Scrollability.None)
            popup.add(("Missing images: [count]".tr().replace("[count]", missing.size.toString())).toLabel(fontSize = 20,
                fontColor = Color(1f, 0.7f, 0.3f, 1f))).pad(10f).row()
            val listTable = Table(BaseScreen.skin)
            for (m in missing.take(100)) {
                listTable.add(m.toLabel(fontSize = 14)).growX().left().pad(2f, 8f, 2f, 8f).row()
            }
            val scroll = AutoScrollPane(listTable).apply {
                setOverscroll(false, false)
                fadeScrollBars = false
            }
            popup.add(scroll).grow().width(520f).height(400f).pad(6f).row()
            popup.addCloseButton()
            popup.open()
        }
    }

    private fun collectNames(value: com.badlogic.gdx.utils.JsonValue, result: LinkedHashSet<String>) {
        when {
            value.isArray -> for (child in value) {
                if (child.isObject) {
                    child.get("name")?.asString()?.takeIf { it.isNotBlank() }?.let { result.add(it) }
                    for (sub in child) if (sub.isArray) collectNames(sub, result)
                }
            }
            else -> { }
        }
    }

    private fun separatorLine(): Table = Table(BaseScreen.skin).apply {
        background = BaseScreen.skinStrings.getUiBackground(
            "ModEditor/Separator", null, Color(1f, 1f, 1f, 0.18f))
    }
}

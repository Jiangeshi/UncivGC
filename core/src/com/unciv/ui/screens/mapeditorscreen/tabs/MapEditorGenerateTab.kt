package com.unciv.ui.screens.mapeditorscreen.tabs

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.unciv.Constants
import com.unciv.logic.map.MapGeneratedMainType
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.MapType
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.translations.tr
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.toCheckBox
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.mapeditorscreen.MapEditorScreen
import com.unciv.ui.screens.mapeditorscreen.MapGeneratorSteps
import com.unciv.ui.screens.newgamescreen.MapParametersTable
import com.unciv.ui.screens.victoryscreen.LoadMapPreview
import com.unciv.utils.Concurrency
import com.unciv.utils.Log

class MapEditorGenerateTab(
    private val editorScreen: MapEditorScreen,
    headerHeight: Float
): TabbedPager(capacity = 2, maximumHeight = headerHeight) {
    private val newTab = MapEditorNewMapTab(this)
    private val partialTab = MapEditorGenerateStepsTab(this)

    init {
        name = "Generate"
        top()
        addPage("New map", newTab,
            ImageGetter.getImage("OtherIcons/New"), 20f,
            shortcutKey = KeyCharAndCode.ctrl('n'))
        addPage("Partial", partialTab,
            ImageGetter.getImage("OtherIcons/Settings"), 20f,
            shortcutKey = KeyCharAndCode.ctrl('g'))
        selectPage(0)
        setButtonsEnabled(true)
        partialTab.generateButton.disable()  // Starts with choice "None"
    }

    private fun setButtonsEnabled(enable: Boolean) {
        newTab.generateButton.isEnabled = enable
        newTab.generateButton.setText( (if(enable) "Create" else Constants.working).tr())
        partialTab.generateButton.isEnabled = enable
        partialTab.generateButton.setText( (if(enable) "Generate" else Constants.working).tr())
    }

    private fun generate(step: MapGeneratorSteps) {
        if (newTab.mapParametersTable.randomizeSeed) {
            // reseed visibly if the "Randomize seed" checkbox is checked
            newTab.mapParametersTable.reseed()
        }

        val mapParameters = editorScreen.newMapParameters.clone()  // this clone is very important here
        val message = mapParameters.mapSize.fixUndesiredSizes(mapParameters.worldWrap)
        if (message != null) {
            Concurrency.runOnGLThread {
                ToastPopup( message, editorScreen, 4000 )
                newTab.mapParametersTable.run { mapParameters.mapSize.also {
                    customMapSizeRadius.intValue = it.radius
                    customMapWidth.intValue = it.width
                    customMapHeight.intValue = it.height
                } }
            }
            return
        }

        if (step == MapGeneratorSteps.Landmass && mapParameters.type == MapType.empty) {
            ToastPopup("Please don't use step 'Landmass' with map type 'Empty', create a new empty map instead.", editorScreen)
            return
        }


        Gdx.input.inputProcessor = null // remove input processing - nothing will be clicked!
        setButtonsEnabled(false)

        fun freshMapCompleted(generatedMap: TileMap, mapParameters: MapParameters, newRuleset: Ruleset, selectPage: Int) {
            MapEditorScreen.saveDefaultParameters(mapParameters)
            editorScreen.loadMap(generatedMap, newRuleset, selectPage) // also reactivates inputProcessor
            editorScreen.isDirty = true
            setButtonsEnabled(true)
        }
        fun stepCompleted(step: MapGeneratorSteps) {
            if (step == MapGeneratorSteps.NaturalWonders) editorScreen.naturalWondersNeedRefresh = true
            editorScreen.mapHolder.updateTileGroups()
            editorScreen.isDirty = true
            setButtonsEnabled(true)
            Gdx.input.inputProcessor = editorScreen.stage
        }

        // Map generation can take a while and we don't want ANRs
        editorScreen.startBackgroundJob("MapEditor.MapGenerator") {
            try {
                val (newRuleset, generator) = if (step > MapGeneratorSteps.Landmass) null to null
                    else {
                        val newRuleset = RulesetCache.getComplexRuleset(mapParameters)
                        newRuleset to MapGenerator(newRuleset)
                    }
                when (step) {
                    MapGeneratorSteps.All -> {
                        val generatedMap = generator!!.generateMap(mapParameters)
                        val savedScale = editorScreen.mapHolder.scaleX
                        Concurrency.runOnGLThread {
                            freshMapCompleted(generatedMap, mapParameters, newRuleset!!, selectPage = 0)
                            editorScreen.mapHolder.zoom(savedScale)
                        }
                    }
                    MapGeneratorSteps.Landmass -> {
                        // This step _could_ run on an existing tileMap, but that opens a loophole where you get hills on water - fixing that is more expensive than always recreating
                        mapParameters.type = MapType.empty
                        val generatedMap = generator!!.generateMap(mapParameters)
                        mapParameters.type = editorScreen.newMapParameters.type
                        generator.generateSingleStep(generatedMap, step)
                        val savedScale = editorScreen.mapHolder.scaleX
                        Concurrency.runOnGLThread {
                            freshMapCompleted(generatedMap, mapParameters, newRuleset!!, selectPage = 1)
                            editorScreen.mapHolder.zoom(savedScale)
                        }
                    }
                    else -> {
                        editorScreen.tileMap.mapParameters.seed = mapParameters.seed
                        MapGenerator(editorScreen.ruleset).generateSingleStep(editorScreen.tileMap, step)
                        Concurrency.runOnGLThread {
                            stepCompleted(step)
                        }
                    }
                }
            } catch (exception: Exception) {
                Log.error("Exception while generating map", exception)
                Concurrency.runOnGLThread {
                    setButtonsEnabled(true)
                    Gdx.input.inputProcessor = editorScreen.stage
                    Popup(editorScreen).apply {
                        addGoodSizedLabel("It looks like we can't make a map with the parameters you requested!".tr())
                        row()
                        addCloseButton()
                    }.open()
                }
            }
        }
    }

    class MapEditorNewMapTab(
        private val parent: MapEditorGenerateTab
    ): Table(BaseScreen.skin) {
        val generateButton = "".toTextButton()
        val mapParametersTable = MapParametersTable(null, parent.editorScreen.newMapParameters, MapGeneratedMainType.generated, forMapEditor = true, sizeChangedCallback = {
            parent.replacePage(0, this)  // A kludge to get the ScrollPanes to recognize changes in vertical layout??
        })

        init {
            top()
            pad(10f)
            add("Map Options".toLabel(fontSize = 24)).row()
            add(mapParametersTable).row()
            add(generateButton).padTop(15f).row()
            generateButton.onClick { parent.generate(MapGeneratorSteps.All) }
            mapParametersTable.resourceSelectBox.onChange {
                parent.editorScreen.run {
                    // normally the 'new map' parameters are independent, this needs to be an exception so strategic resource painting will use it
                    tileMap.mapParameters.mapResources = newMapParameters.mapResources
                }
            }
        }
    }

    class MapEditorGenerateStepsTab(
        private val parent: MapEditorGenerateTab
    ): Table(BaseScreen.skin), TabbedPager.IPageExtensions {
        private val optionGroup = ButtonGroup<CheckBox>()
        val generateButton = "".toTextButton()
        private var choice = MapGeneratorSteps.None
        private val newMapParameters = parent.editorScreen.newMapParameters
        private val tileMap = parent.editorScreen.tileMap
        private val actualMapParameters = tileMap.mapParameters

        // UncivGC 2026-09-02: 地图裁剪/扩展 — 基于锚点重设矩形地图大小
        private var selectedAnchor = "center"
        private lateinit var resizeWidthField: UncivTextField
        private lateinit var resizeHeightField: UncivTextField

        // UncivGC 2026-09-02: 地图钉 — 单次模式: 点 Pin 按钮进入放置模式, 点格子放置/修改/删除后自动退出
        private var pinModeActive = false
        private var pinFontScale = 1f
        private var pinColor = "White"
        private var pinButton: com.badlogic.gdx.scenes.scene2d.ui.TextButton? = null

        /** 按钮外观: 激活时变灰 (让用户知道点到了), 退出恢复 */
        private fun updatePinButtonAppearance() {
            val button = pinButton ?: return
            button.setChecked(pinModeActive)
            button.color = if (pinModeActive) com.badlogic.gdx.graphics.Color.GRAY
            else com.badlogic.gdx.graphics.Color.WHITE
        }

        override fun activated(index: Int, caption: String, pager: TabbedPager) {
            // 裁剪默认值 = 当前地图实际尺寸 (地图可能已加载/调整过, 每次进标签页刷新)
            if (::resizeWidthField.isInitialized) {
                val current = parent.editorScreen.tileMap.mapParameters.mapSize
                resizeWidthField.setText(current.width.toString())
                resizeHeightField.setText(current.height.toString())
            }
            // 进入本标签页时若 pin 模式还开着, 恢复点击 handler (EditTab 的 activated 会覆盖)
            if (pinModeActive) {
                parent.editorScreen.tileClickHandler = ::handlePinClick
                updatePinButtonAppearance()
            }
        }

        override fun deactivated(index: Int, caption: String, pager: TabbedPager) {
            // 离开标签页时退出 pin 模式, 避免残留点击 handler
            exitPinMode()
        }

        private fun exitPinMode() {
            if (!pinModeActive) return
            pinModeActive = false
            updatePinButtonAppearance()
            if (parent.editorScreen.tileClickHandler == ::handlePinClick)
                parent.editorScreen.tileClickHandler = null
        }

        private fun handlePinClick(tile: com.unciv.logic.map.tile.Tile) {
            val editorScreen = parent.editorScreen
            if (editorScreen.mapHolder.isPanning || editorScreen.mapHolder.isZooming()) return
            editorScreen.hideSelection()
            val existing = editorScreen.tileMap.mapPins["${tile.position.x},${tile.position.y}"]

            val popup = Popup(editorScreen)
            popup.addGoodSizedLabel(
                if (existing == null) "Add map pin:".tr() else "Edit map pin:".tr()
            )
            popup.row()

            val textField = UncivTextField("Pin text", existing?.text ?: "").apply {
                maxLength = 40
                selectAll()
            }
            popup.add(textField).width(260f).pad(5f)
            popup.row()

            // 字号: 输入框, 单位 = 百分数 (100 = 默认, 20-400; 实际渲染倍率 = 值/100)
            popup.add("Font size:".tr().toLabel()).row()
            val fontField = UncivTextField("100", (pinFontScale * 100).toInt().toString()).apply {
                textFieldFilter = TextField.TextFieldFilter { _, char -> char.isDigit() }
            }
            popup.add(fontField).width(100f).pad(5f).row()

            // 颜色选择
            popup.add("Color:".tr().toLabel()).row()
            val colorGroup = ButtonGroup<CheckBox>()
            colorGroup.setMinCheckCount(1); colorGroup.setMaxCheckCount(1)
            val colorTable = Table()
            for (color in listOf("White", "Black")) {
                val cb = color.toCheckBox(color == pinColor) { pinColor = color }
                colorGroup.add(cb)
                colorTable.add(cb).pad(4f)
            }
            popup.add(colorTable).row()

            popup.addButton("Apply".tr()) {
                val text = textField.text.trim()
                // 字号输入 20-400, 越界取默认 100; 转存为倍率
                pinFontScale = (fontField.text.toIntOrNull() ?: 100).coerceIn(20, 400) / 100f
                if (text.isEmpty()) {
                    editorScreen.tileMap.mapPins.remove("${tile.position.x},${tile.position.y}")
                } else {
                    editorScreen.tileMap.mapPins["${tile.position.x},${tile.position.y}"] =
                        com.unciv.logic.map.TileMap.MapPin(text, pinFontScale, pinColor)
                }
                refreshPinTile(tile)
                editorScreen.isDirty = true
                exitPinMode()
                popup.close()
            }
            if (existing != null) {
                popup.addButton("Delete pin".tr()) {
                    editorScreen.tileMap.mapPins.remove("${tile.position.x},${tile.position.y}")
                    refreshPinTile(tile)
                    editorScreen.isDirty = true
                    exitPinMode()
                    popup.close()
                }
            }
            popup.addButton("Cancel".tr()) {
                exitPinMode()
                popup.close()
            }
            popup.open()
        }

        private fun refreshPinTile(tile: com.unciv.logic.map.tile.Tile) {
            val holder = parent.editorScreen.mapHolder
            holder.tileGroups[tile]?.let { group ->
                group.layerPin.update(null)
                group.update()
            }
        }

        init {
            top()
            pad(10f)
            defaults().pad(2.5f)
            add("Generator steps".toLabel(fontSize = 24)).row()
            optionGroup.setMinCheckCount(0)
            for (option in MapGeneratorSteps.entries) {
                if (option <= MapGeneratorSteps.All) continue
                val checkBox = option.label.toCheckBox {
                        choice = option
                        generateButton.enable()
                    }
                add(checkBox).row()
                optionGroup.add(checkBox)
            }
            add(generateButton).padTop(15f).row()
            generateButton.onClick {
                parent.generate(choice)
                choice.copyParameters?.invoke(newMapParameters, actualMapParameters)
            }

            // UncivGC 2026-09-01: 手动镜像 — 以基准半为基 (左/上保留, 右/下覆盖为镜像)
            add("Mirror map (keeps left / top half):".tr().toLabel()).padTop(10f).row()
            val mirrorButtons = Table()
            mirrorButtons.add("Mirror left-right".tr().toTextButton().apply {
                onClick { mirrorMap(com.unciv.logic.map.MirroringType.leftright) }
            }).pad(5f)
            mirrorButtons.add("Mirror top-bottom".tr().toTextButton().apply {
                onClick { mirrorMap(com.unciv.logic.map.MirroringType.topbottom) }
            }).pad(5f)
            add(mirrorButtons).row()

            // UncivGC 2026-09-02: 地图裁剪/扩展 — 基于锚点重设矩形地图大小
            add("Resize map (rectangular only):".tr().toLabel()).padTop(10f).row()
            val resizeTable = Table()
            resizeWidthField = UncivTextField("Width", tileMap.mapParameters.mapSize.width.toString())
            resizeWidthField.textFieldFilter = TextField.TextFieldFilter { _, char -> char.isDigit() }
            resizeHeightField = UncivTextField("Height", tileMap.mapParameters.mapSize.height.toString())
            resizeHeightField.textFieldFilter = TextField.TextFieldFilter { _, char -> char.isDigit() }
            resizeTable.add("Width".tr().toLabel()).padRight(5f)
            resizeTable.add(resizeWidthField).width(60f)
            resizeTable.add("Height".tr().toLabel()).padLeft(10f).padRight(5f)
            resizeTable.add(resizeHeightField).width(60f)
            add(resizeTable).row()

            // 锚点选择: 3x3 网格, 单选 (默认中心)
            val anchorLabel = "Anchor:".tr().toLabel()
            add(anchorLabel).padTop(5f).row()
            val anchorGrid = Table()
            val anchors = listOf(
                "Top-left" to "topleft", "Top" to "top", "Top-right" to "topright",
                "Left" to "left", "Center" to "center", "Right" to "right",
                "Bottom-left" to "bottomleft", "Bottom" to "bottom", "Bottom-right" to "bottomright",
            )
            val anchorGroup = ButtonGroup<CheckBox>()
            anchorGroup.setMinCheckCount(1)
            anchorGroup.setMaxCheckCount(1)
            for ((i, anchor) in anchors.withIndex()) {
                val (label, value) = anchor
                val checkBox = label.toCheckBox(value == "center") { selectedAnchor = value }
                anchorGroup.add(checkBox)
                anchorGrid.add(checkBox).pad(3f)
                if (i % 3 == 2) anchorGrid.row()
            }
            add(anchorGrid).row()

            add("Preview resize".tr().toTextButton().apply {
                onClick { showResizePreview() }
            }).padTop(5f).row()

            // UncivGC 2026-09-02: 地图钉 — 点按钮进入放置模式, 点格子放置/修改/删除, 完成后自动退出 (单次模式)
            add("Map pins:".tr().toLabel()).padTop(10f).row()
            val pinEditorScreen = parent.editorScreen
            pinButton = "Place map pin".tr().toTextButton().apply {
                isChecked = false
                onClick {
                    pinModeActive = !pinModeActive
                    updatePinButtonAppearance()
                    if (pinModeActive) {
                        pinEditorScreen.tileClickHandler = ::handlePinClick
                    } else {
                        pinEditorScreen.tileClickHandler = null
                    }
                }
            }
            add(pinButton).row()
            add("Click a tile to add / edit / delete its pin.".tr().toLabel()).padTop(3f).row()
        }

        private fun showResizePreview() {
            val editorScreen = parent.editorScreen
            val map = editorScreen.tileMap
            if (map.mapParameters.shape != MapShape.rectangular) {
                ToastPopup("Resize map is only available for rectangular maps!".tr(), editorScreen)
                return
            }
            val newWidth = resizeWidthField.text.toIntOrNull()
            val newHeight = resizeHeightField.text.toIntOrNull()
            if (newWidth == null || newHeight == null || newWidth < 3 || newHeight < 3) {
                ToastPopup("Invalid map size!".tr(), editorScreen)
                return
            }

            // 预览: 克隆当前地图并应用 resize (不改动真实地图)
            // 注意 TileMap.clone() 的 mapParameters 是共享引用, 必须深拷贝, 否则预览会改到原地图尺寸
            val previewMap = editorScreen.getMapCloneForSave().apply {
                mapParameters = editorScreen.tileMap.mapParameters.clone()
            }
            MapGenerator(editorScreen.ruleset).resizeMap(previewMap, newWidth, newHeight, selectedAnchor)

            val popup = Popup(editorScreen).apply {
                addGoodSizedLabel("Resize map preview:".tr())
                row()
                add(LoadMapPreview(previewMap, 420f, 320f))
                row()
                addButton("Apply".tr()) {
                    MapGenerator(editorScreen.ruleset).resizeMap(map, newWidth, newHeight, selectedAnchor)
                    editorScreen.rebuildMapHolderAfterResize()
                    editorScreen.isDirty = true
                    close()
                }
                addButton("Cancel".tr()) { close() }
            }
            popup.open()
        }

        private fun mirrorMap(type: String) {
            val map = parent.editorScreen.tileMap
            if (map.values.isEmpty()) return
            MapGenerator(parent.editorScreen.ruleset).mirrorMap(map, type)
            parent.editorScreen.mapHolder.updateTileGroups()
            parent.editorScreen.isDirty = true
        }
    }
}

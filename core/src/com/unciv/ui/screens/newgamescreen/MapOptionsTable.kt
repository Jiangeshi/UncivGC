package com.unciv.ui.screens.newgamescreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.map.MapGeneratedMainType
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.widgets.TranslatedSelectBox
import com.unciv.ui.screens.basescreen.BaseScreen

class MapOptionsTable(
    private val newGameScreen: NewGameScreen,
    /** UncivGC 联机大厅: 折叠区默认展开 */
    private val lobbyMode: Boolean = false,
) : Table() {

    private val mapParameters = newGameScreen.gameSetupInfo.mapParameters
    private var mapTypeSpecificTable = Table()
    internal val generatedMapOptionsTable = MapParametersTable(newGameScreen, mapParameters, MapGeneratedMainType.generated, defaultExpanded = lobbyMode)
    private val randomMapOptionsTable = MapParametersTable(newGameScreen, mapParameters, MapGeneratedMainType.randomGenerated, defaultExpanded = lobbyMode)
    private val savedMapOptionsTable = MapFileSelectTable(newGameScreen, mapParameters)
    private val scenarioOptionsTable = ScenarioSelectTable(newGameScreen)
    internal val mapTypeSelectBox: TranslatedSelectBox

    init {
        //defaults().pad(5f) - each nested table having the same can give 'stairs' effects,
        // better control directly. Besides, the first Labels/Buttons should have 10f to look nice
        background = BaseScreen.skinStrings.getUiBackground("NewGameScreen/MapOptionsTable", tintColor = BaseScreen.skinStrings.skinConfig.clearColor)

        val mapTypes = arrayListOf(MapGeneratedMainType.generated, MapGeneratedMainType.randomGenerated)
        if (savedMapOptionsTable.isNotEmpty()) mapTypes.add(MapGeneratedMainType.custom)
        if (newGameScreen.game.files.getScenarioFiles().any()) mapTypes.add(MapGeneratedMainType.scenario)

        val initialMapType = mapParameters.type.takeIf { it in mapTypes } ?: MapGeneratedMainType.generated
        mapTypeSelectBox = TranslatedSelectBox(mapTypes, initialMapType)

        // activate once, so the MapGeneratedMainType.generated controls show
        updateOnMapTypeChange()

        mapTypeSelectBox.onChange { updateOnMapTypeChange() }

        val mapTypeSelectWrapper = Table()  // wrap to center-align Label and SelectBox easier
        mapTypeSelectWrapper.add("{Map Type}:".toLabel()).left().expandX()
        mapTypeSelectWrapper.add(mapTypeSelectBox).right()
        add(mapTypeSelectWrapper).pad(10f).fillX().row()
        add(mapTypeSpecificTable).row()
    }

    private fun updateOnMapTypeChange() {
        mapTypeSpecificTable.clear()
        when (mapTypeSelectBox.selected.value) {
            MapGeneratedMainType.custom -> {
                mapParameters.type = MapGeneratedMainType.custom
                mapTypeSpecificTable.add(savedMapOptionsTable)
                savedMapOptionsTable.activateCustomMaps()
                newGameScreen.unlockTables()
            }
            MapGeneratedMainType.generated -> {
                mapParameters.name = ""
                mapParameters.type = generatedMapOptionsTable.mapTypeSelectBox.selected.value
                mapTypeSpecificTable.add(generatedMapOptionsTable)
                newGameScreen.unlockTables()
            }
            MapGeneratedMainType.randomGenerated -> {
                mapParameters.name = ""
                mapTypeSpecificTable.add(randomMapOptionsTable)
                newGameScreen.unlockTables()
            }
            MapGeneratedMainType.scenario -> {
                mapParameters.name = ""
                mapTypeSpecificTable.add(scenarioOptionsTable)
                scenarioOptionsTable.selectScenario()
                newGameScreen.lockTables()
            }
        }
        newGameScreen.gameSetupInfo.gameParameters.godMode = false
        newGameScreen.updateTables()
    }

    /** UncivGC 大厅: 服务器设置同步后局部刷新地图设置 (不重建整个界面, 消闪烁).
     *  主类型选择器与同步值不一致时才重建类型区; 否则只刷新参数表的值
     *  (注意: 不能调用 update() 重建 — 它会 reseed 并把种子改成随机值, 还会打断正在拖动的滑块) */
    fun refreshFromMapParameters() {
        val currentSel = mapTypeSelectBox.selected.value
        val synced = mapParameters.type
        val syncedItem = mapTypeSelectBox.items.firstOrNull { it.value == synced }
        if (synced.isNotEmpty() && syncedItem != null && synced != currentSel) {
            mapTypeSelectBox.setSelected(syncedItem)
            updateOnMapTypeChange()
        } else {
            generatedMapOptionsTable.refreshValues()
        }
    }

    /** UncivGC 联机大厅: 房间设置同步后全量刷新 (类型联动 + 生成/随机参数全部控件) */
    fun syncFullFromMapParameters() {
        refreshFromMapParameters()
        generatedMapOptionsTable.syncFromMapParameters()
        randomMapOptionsTable.syncFromMapParameters()
    }

    internal fun getSelectedScenario(): ScenarioSelectTable.ScenarioData? {
        if (mapTypeSelectBox.selected.value != MapGeneratedMainType.scenario) return null
        return scenarioOptionsTable.selectedScenario
    }

    internal fun cancelBackgroundJobs() {
        generatedMapOptionsTable.cancelBackgroundJobs()
        randomMapOptionsTable.cancelBackgroundJobs()
        savedMapOptionsTable.cancelBackgroundJobs()
    }

    internal fun refreshExampleMap() {
        generatedMapOptionsTable.generateExampleMap()
        randomMapOptionsTable.generateExampleMap()
    }
}

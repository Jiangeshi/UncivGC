package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 * S2 模块选择：完整标准模块列表（与基础规则集 jsons 一致）。
 * 已做的可编辑（Units/ModOptions 表单编辑器），未做的上锁显示 In development。
 */
class ModModulesScreen(private val modFolder: FileHandle) : BaseScreen() {

    private enum class Kind { FORM_EDITOR, LOCKED }

    private data class Module(val labelKey: String, val fileName: String, val kind: Kind)

    private val modules = listOf(
        Module("Units", "Units.json", Kind.FORM_EDITOR),
        Module("ModOptions", "ModOptions.json", Kind.FORM_EDITOR),
        Module("Buildings", "Buildings.json", Kind.FORM_EDITOR),
        Module("Techs", "Techs.json", Kind.FORM_EDITOR),
        Module("Nations", "Nations.json", Kind.FORM_EDITOR),
        Module("Policies", "Policies.json", Kind.FORM_EDITOR),
        Module("UnitPromotions", "UnitPromotions.json", Kind.FORM_EDITOR),
        Module("UnitTypes", "UnitTypes.json", Kind.FORM_EDITOR),
        Module("Terrains", "Terrains.json", Kind.FORM_EDITOR),
        Module("TileImprovements", "TileImprovements.json", Kind.FORM_EDITOR),
        Module("TileResources", "TileResources.json", Kind.FORM_EDITOR),
        Module("Beliefs", "Beliefs.json", Kind.FORM_EDITOR),
        Module("Religions", "Religions.json", Kind.FORM_EDITOR),
        Module("Personalities", "Personalities.json", Kind.FORM_EDITOR),
        Module("Eras", "Eras.json", Kind.FORM_EDITOR),
        Module("Speeds", "Speeds.json", Kind.FORM_EDITOR),
        Module("Difficulties", "Difficulties.json", Kind.FORM_EDITOR),
        Module("CityStateTypes", "CityStateTypes.json", Kind.FORM_EDITOR),
        Module("GlobalUniques", "GlobalUniques.json", Kind.FORM_EDITOR),
        Module("Ruins", "Ruins.json", Kind.FORM_EDITOR),
        Module("Events", "Events.json", Kind.FORM_EDITOR),
        Module("Quests", "Quests.json", Kind.FORM_EDITOR),
        Module("Specialists", "Specialists.json", Kind.FORM_EDITOR),
        Module("UnitNameGroups", "UnitNameGroups.json", Kind.FORM_EDITOR),
        Module("Tutorials", "Tutorials.json", Kind.FORM_EDITOR),
        Module("VictoryTypes", "VictoryTypes.json", Kind.FORM_EDITOR),
        Module("Translations", "Translations/", Kind.FORM_EDITOR),
        Module("Images", "Images/", Kind.FORM_EDITOR)
    )

    init {
        // 加载当前 mod 的翻译（游戏规则集未加载该 mod，需手动注入，供列表/表单双语显示）
        ModEditorData.loadModTranslations(modFolder)

        val root = Table(BaseScreen.skin)
        root.setFillParent(true)
        stage.addActor(root)

        val topBar = Table(BaseScreen.skin)
        val backButton = ("‹ " + "Back".tr()).toTextButton()
        backButton.onActivation { game.popScreen() }
        topBar.add(backButton).pad(8f)
        topBar.add(("Mod Editor".tr() + " · " + modFolder.name()).toLabel(fontSize = 30))
            .padLeft(20f).expandX().left()
        root.add(topBar).fillX().row()

        val listTable = Table(BaseScreen.skin)
        listTable.add("Modules".tr().toLabel(fontSize = 20, fontColor = Color(0.6f, 0.85f, 1f, 1f)))
            .left().pad(14f, 12f, 8f, 12f).row()

        // 2 列网格：所有卡片固定统一宽度（半屏宽 - 边距），彻底一致
        val cardWidth = maxOf((stage.width - 60f) / 2f, 300f)
        var rowTable = Table(BaseScreen.skin)
        var col = 0
        for (module in modules) {
            val available = module.kind != Kind.LOCKED
            val card = Table(BaseScreen.skin)
            card.defaults().pad(8f)
            card.background = if (available) rowBackground()
                else rowBackground(Color(1f, 1f, 1f, 0.08f))
            val statusIcon = if (available) ImageGetter.getImage("OtherIcons/Checkmark")
                else ImageGetter.getImage("OtherIcons/LockSmall")
            statusIcon.setSize(22f, 22f)
            card.add(statusIcon).size(22f).padRight(6f)
            card.add(module.labelKey.toLabel(fontSize = 22,
                fontColor = if (available) Color.WHITE else Color(1f, 1f, 1f, 0.4f)))
                .left().expandX()
            card.add(module.fileName.toLabel(fontSize = 14,
                fontColor = if (available) Color(1f, 1f, 1f, 0.6f) else Color(1f, 1f, 1f, 0.3f)))
                .right().padRight(8f)
            if (available) {
                card.touchable = Touchable.enabled
                card.onActivation { openModule(module) }
            }
            // 所有卡片固定宽度 + 固定高度（不 growX，避免内容撑宽）
            rowTable.add(card).width(cardWidth).height(64f).pad(4f, 6f, 4f, 6f)
            col++
            if (col % 2 == 0) {
                listTable.add(rowTable).fillX().row()
                rowTable = Table(BaseScreen.skin)
            }
        }
        // 奇数个时补一个空 cell 占位，保证最后一行卡片宽度与其他行一致
        if (col % 2 != 0) {
            rowTable.add().width(cardWidth).height(64f).pad(4f, 6f, 4f, 6f)
            listTable.add(rowTable).fillX().row()
        }

        val scrollPane = AutoScrollPane(listTable)
        scrollPane.setScrollingDisabled(true, false)
        root.add(scrollPane).expand().grow()
    }

    private fun openModule(module: Module) {
        if (module.kind != Kind.FORM_EDITOR) return
        when (module.labelKey) {
            "Units" -> game.pushScreen(UnitEditorScreen(modFolder))
            "ModOptions" -> game.pushScreen(ModOptionsEditorScreen(modFolder))
            "Buildings" -> game.pushScreen(BuildingsEditorScreen(modFolder))
            "Techs" -> game.pushScreen(TechsEditorScreen(modFolder))
            "Nations" -> game.pushScreen(NationsEditorScreen(modFolder))
            "Policies" -> game.pushScreen(PoliciesEditorScreen(modFolder))
            "UnitPromotions" -> game.pushScreen(UnitPromotionsEditorScreen(modFolder))
            "UnitTypes" -> game.pushScreen(UnitTypesEditorScreen(modFolder))
            "UnitNameGroups" -> game.pushScreen(UnitNameGroupsEditorScreen(modFolder))
            "GlobalUniques" -> game.pushScreen(GlobalUniquesEditorScreen(modFolder))
            "Terrains" -> game.pushScreen(TerrainsEditorScreen(modFolder))
            "Specialists" -> game.pushScreen(SpecialistsEditorScreen(modFolder))
            "Beliefs" -> game.pushScreen(BeliefsEditorScreen(modFolder))
            "Personalities" -> game.pushScreen(PersonalitiesEditorScreen(modFolder))
            "CityStateTypes" -> game.pushScreen(CityStateTypesEditorScreen(modFolder))
            "Quests" -> game.pushScreen(QuestsEditorScreen(modFolder))
            "Religions" -> game.pushScreen(ReligionsEditorScreen(modFolder))
            "TileImprovements" -> game.pushScreen(TileImprovementsEditorScreen(modFolder))
            "TileResources" -> game.pushScreen(TileResourcesEditorScreen(modFolder))
            "Ruins" -> game.pushScreen(RuinsEditorScreen(modFolder))
            "Eras" -> game.pushScreen(ErasEditorScreen(modFolder))
            "Speeds" -> game.pushScreen(SpeedsEditorScreen(modFolder))
            "Difficulties" -> game.pushScreen(DifficultiesEditorScreen(modFolder))
            "Events" -> game.pushScreen(EventsEditorScreen(modFolder))
            "Tutorials" -> game.pushScreen(TutorialsEditorScreen(modFolder))
            "VictoryTypes" -> game.pushScreen(VictoryTypesEditorScreen(modFolder))
            "Translations" -> game.pushScreen(TranslationsEditorScreen(modFolder))
            "Images" -> game.pushScreen(ImagesEditorScreen(modFolder))
        }
    }

    private fun rowBackground(tint: Color = BaseScreen.skinStrings.skinConfig.baseColor) =
        BaseScreen.skinStrings.getUiBackground(
            "ModEditor/ModuleRow", BaseScreen.skinStrings.roundedEdgeRectangleShape, tint)
}

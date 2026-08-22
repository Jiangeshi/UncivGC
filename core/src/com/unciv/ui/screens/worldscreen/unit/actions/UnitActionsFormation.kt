package com.unciv.ui.screens.worldscreen.unit.actions

import com.unciv.GUI
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.UnitFormation
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.translations.tr
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.screens.worldscreen.FrameSync
import com.unciv.models.ruleset.unique.UniqueType

object UnitActionsFormation {

    /** 获取"组成军团/集团军"按钮 */
    fun getFormCorpsActions(unit: MapUnit, tile: Tile) = sequence {
        // 已是集团军 → 不显示
        if (unit.formation == UnitFormation.Army) return@sequence
        // 不是军事单位 / 有禁止 unique → 不显示
        if (!unit.isMilitary() || unit.isCivilian()) return@sequence
        if (unit.baseUnit.isWaterUnit || unit.baseUnit.isAirUnit()) return@sequence
        if (unit.hasUnique(UniqueType.CannotFormCorps)) return@sequence

        val worldScreen = GUI.getWorldScreen()
        // 按钮文字: 陆军 军团/集团军, 海军 舰队/无敌舰队 (2026-08-22)
        val isWater = unit.baseUnit.isWaterUnit
        val label = when {
            isWater && unit.formation.tier == 1 -> "Form Armada"
            isWater -> "Form Fleet"
            unit.formation.tier == 1 -> "Form Army"
            else -> "Form Corps"
        }

        // 三单位编队 (集团军/无敌舰队) 不能再合并
        if (unit.formation.tier >= 2) return@sequence

        // 只有相邻有可合并单位时才显示按钮 (2026-08-22 用户: 不要常驻)
        val mergeableNeighbors = unit.getMergeableNeighbors()
        if (mergeableNeighbors.isEmpty()) return@sequence

        yield(UnitAction(
            type = UnitActionType.FormCorps,
            title = label.tr(),
            useFrequency = 80f,
            action = {
                val target = mergeableNeighbors.first()

                if (!FrameSync.tryInterceptOp(worldScreen, "unit.formCorps",
                        mapOf("unitId" to unit.id, "targetId" to target.id))) {
                    unit.mergeWith(target)
                    target.destroy()
                    GUI.setUpdateWorldOnNextRender()
                }
            }.takeIf { unit.hasMovement() }
        ))
    }

    /** 获取"拆分编队"按钮 */
    fun getSplitFormationActions(unit: MapUnit, tile: Tile) = sequence {
        if (unit.formation == UnitFormation.Single) return@sequence
        // 移动力为 0 或相邻空格不足时按钮不显示 (点了没反应体验差 — 2026-08-22 用户确认)
        if (!unit.canSplitFormation()) return@sequence

        val worldScreen = GUI.getWorldScreen()

        yield(UnitAction(
            type = UnitActionType.SplitFormation,
            title = "Split Formation".tr(),
            useFrequency = 30f,
            action = {
                if (!unit.canSplitFormation()) return@UnitAction

                ConfirmPopup(
                    worldScreen,
                    "Are you sure you want to split this formation? This will consume all movement points.".tr(),
                    "Split Formation".tr(),
                    action = {
                        if (!FrameSync.tryInterceptOp(worldScreen, "unit.splitFormation",
                                mapOf("unitId" to unit.id))) {
                            unit.splitFormation()
                            GUI.setUpdateWorldOnNextRender()
                        }
                    }
                ).open()
            }.takeIf { unit.canSplitFormation() }
        ))
    }
}

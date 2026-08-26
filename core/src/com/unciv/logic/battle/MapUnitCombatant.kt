package com.unciv.logic.battle

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.UnitFormation
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.roundToInt
import com.unciv.models.ruleset.unit.UnitType
import yairm210.purity.annotations.Readonly

class MapUnitCombatant(val unit: MapUnit) : ICombatant {
    override fun getHealth(): Int = unit.health
    override fun getMaxHealth() = unit.maxHealth
    override fun getCivInfo(): Civilization = unit.civ
    override fun getTile(): Tile = unit.getTile()
    override fun getName(): String = unit.name
    override fun isDefeated(): Boolean = unit.health <= 0
    override fun isInvisible(to: Civilization): Boolean = unit.isInvisible(to)
    override fun canAttack(): Boolean = unit.canAttack()
    override fun matchesFilter(filter: String, multiFilter: Boolean) = unit.matchesFilter(filter, multiFilter)
    override fun getAttackSound() = unit.baseUnit.attackSound.let {
        if (it == null) UncivSound.Click else UncivSound(it)
    }

    override fun getNotificationDisplay(leadingText: String): String {
        val isUnitUnnamed = unit.instanceName.isNullOrEmpty()
        return if (isUnitUnnamed)
            leadingText + "[" + unit.name + "]"
        else
            "[" + unit.instanceName + "]"
    }


    override fun takeDamage(damage: Int) = unit.takeDamage(damage)

    override fun getAttackingStrength(defender: ICombatant?): Int {
        val state = GameContext(this, defender, this.getTile(), CombatAction.Attack)
        val extraStrength = unit.getMatchingUniques(UniqueType.StrengthAmount, state).sumOf { it.params[0].toInt() }
        val baseStrength = if (isRanged()) unit.baseUnit.rangedStrength + extraStrength
        else unit.baseUnit.strength + extraStrength

        // 军团/集团军加成 (加法，直接加在基础战斗力上)
        val formationBonus = getFormationBonus(baseStrength)
        // 同盟 Lv2 (2026-08-26): 共同对敌 +10% 战斗力
        return ((baseStrength + formationBonus) * allyWarBonus(defender)).roundToInt()
    }

    override fun getDefendingStrength(attacker: ICombatant?): Int {
        val attackedByRanged = attacker?.isRanged() == true
        val state = GameContext(this, attacker, this.getTile(), CombatAction.Defend)
        val extraStrength = unit.getMatchingUniques(UniqueType.StrengthAmount, state).sumOf { it.params[0].toInt() }
        val baseStrength = when {
            unit.isEmbarked() && !isCivilian() -> unit.civ.getEra().embarkDefense
            isRanged() && attackedByRanged -> unit.baseUnit.rangedStrength + extraStrength
            else -> unit.baseUnit.strength + extraStrength
        }

        // 军团/集团军加成 (防御同样生效)
        val formationBonus = getFormationBonus(baseStrength)
        // 同盟 Lv2 (2026-08-26): 共同对敌 +10% 战斗力
        return ((baseStrength + formationBonus) * allyWarBonus(attacker)).roundToInt()
    }

    /** 同盟 Lv2 (2026-08-26 同盟设计稿 v1.0): 与目标交战且任一盟友也在与其交战 → +10% 战斗力 */
    @Readonly
    private fun allyWarBonus(combatant: ICombatant?): Float {
        if (combatant == null) return 1f
        val enemyCiv = when (combatant) {
            is MapUnitCombatant -> combatant.unit.civ
            is CityCombatant -> combatant.city.civ
            else -> return 1f
        }
        if (!unit.civ.isAtWarWith(enemyCiv)) return 1f
        val myCivId = unit.civ.civID
        return try {
            // 2026-08-27 修复: 必须 Lv2+ (设计稿: 续约 1 次后才有共同对敌加成) — 之前任意等级都触发
            val hasAlly = unit.civ.gameInfo.alliances.any { al ->
                al.level >= 2 && al.contains(myCivId) && unit.civ.gameInfo
                    .getCivilization(al.otherCiv(myCivId) ?: "").isAtWarWith(enemyCiv)
            }
            if (hasAlly) 1.1f else 1f
        } catch (ignored: Exception) { 1f }
    }

    /** 计算编队战斗力加成 (按 tier: 军团/舰队 +33% / 集团军/无敌舰队 +50%; 四舍五入, 与显示 getDisplayStrength 一致) */
    @Readonly
    private fun getFormationBonus(baseStrength: Int): Int {
        if (baseStrength <= 0) return 0
        return when (unit.formation.tier) {
            1 -> (baseStrength * 0.33f).roundToInt()
            2 -> (baseStrength * 0.50f).roundToInt()
            else -> 0
        }
    }

    override fun getUnitType(): UnitType {
        return unit.type
    }

    override fun toString(): String {
        return unit.name + " of " + unit.civ.civID
    }

    @Readonly 
    fun getMatchingUniques(uniqueType: UniqueType, gameContext: GameContext, checkCivUniques: Boolean): Sequence<Unique> =
        unit.getMatchingUniques(uniqueType, gameContext, checkCivUniques)

    @Readonly
    override fun getTriggeredUniques(
        trigger: UniqueType,
        gameContext: GameContext,
        triggerFilter: (Unique) -> Boolean
    ): Sequence<Unique> {
        return unit.getTriggeredUniques(trigger, gameContext, triggerFilter)
    }

    @Readonly
    fun hasUnique(uniqueType: UniqueType, conditionalState: GameContext? = null): Boolean =
        if (conditionalState == null) unit.hasUnique(uniqueType)
        else unit.hasUnique(uniqueType, conditionalState)
    
    @Readonly
    override fun hashCode() = unit.hashCode()
    @Readonly
    override fun equals(other: Any?) = other is MapUnitCombatant && other.unit == unit


}

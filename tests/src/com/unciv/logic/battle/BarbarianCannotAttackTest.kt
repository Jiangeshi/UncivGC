package com.unciv.logic.battle

import com.unciv.logic.civilization.PlayerType
import com.unciv.models.ruleset.nation.Nation
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class BarbarianCannotAttackTest {

    private fun TestGame.addNamedCiv(name: String): com.unciv.logic.civilization.Civilization {
        val nation = Nation()
        nation.name = name
        nation.cities = arrayListOf("The Capital")
        return addCiv(nation)
    }

    @Test
    fun `global cannot-attack vs civ blocks barbarian attack`() {
        // 全局词条: 蛮子不能攻击 Franks
        val testGame = TestGame("Cannot attack <for [Barbarians] units> <vs [Franks]>")
        testGame.makeHexagonalMap(4)
        val frank = testGame.addNamedCiv("Franks")
        frank.playerType = PlayerType.Human
        val barb = testGame.addBarbarianCiv()
        frank.getDiplomacyManager(barb)!!.declareWar()
        testGame.addUnit("Swordsman", frank, testGame.getTile(2, 0))
        val barbUnit = testGame.addUnit("Warrior", barb, testGame.getTile(1, 0))
        val barbCombatant = MapUnitCombatant(barbUnit)
        val canAttack = TargetHelper.containsAttackableEnemy(testGame.getTile(2, 0), barbCombatant)
        assertFalse("蛮子不应能攻击法兰克单位 (全局 CannotAttack)", canAttack)
        println("蛮子攻击法兰克: containsAttackableEnemy=$canAttack (期望 false)")
    }

    @Test
    fun `without whitelist barbarian can attack`() {
        // 无全局词条 → 蛮子能攻击 (对照组, 确认测试有效)
        val testGame = TestGame()
        testGame.makeHexagonalMap(4)
        val frank = testGame.addNamedCiv("Franks")
        frank.playerType = PlayerType.Human
        val barb = testGame.addBarbarianCiv()
        frank.getDiplomacyManager(barb)!!.declareWar()
        testGame.addUnit("Swordsman", frank, testGame.getTile(2, 0))
        val barbUnit = testGame.addUnit("Warrior", barb, testGame.getTile(1, 0))
        val barbCombatant = MapUnitCombatant(barbUnit)
        val canAttack = TargetHelper.containsAttackableEnemy(testGame.getTile(2, 0), barbCombatant)
        assertTrue("无词条时蛮子应能攻击", canAttack)
        println("对照组 蛮子攻击: containsAttackableEnemy=$canAttack (期望 true)")
    }
}

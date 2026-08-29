package com.unciv.logic.map.mapunit

import com.unciv.logic.civilization.PlayerType
import com.unciv.models.ruleset.nation.Nation
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class BarbarianCaptureTest {

    private fun TestGame.addNamedCiv(name: String): com.unciv.logic.civilization.Civilization {
        val nation = Nation()
        nation.name = name
        nation.cities = arrayListOf("The Capital")
        return addCiv(nation)
    }

    @Test
    fun `barbarian moving onto frank worker does not capture when global cannot-attack`() {
        // 全局词条: 蛮子不能攻击 Franks
        val testGame = TestGame("Cannot attack <for [Barbarians] units> <vs [Franks]>")
        testGame.makeHexagonalMap(4)
        val frank = testGame.addNamedCiv("Franks")
        frank.playerType = PlayerType.Human
        val barb = testGame.addBarbarianCiv()
        frank.getDiplomacyManager(barb)!!.declareWar()

        // 法兰克建城 (首都 Palace) — getUnguardedCivilian/俘虏路径需要城市环境
        testGame.addCity(frank, testGame.getTile(0, 0))
        // 法兰克工人 + 蛮子勇士
        val worker = testGame.addUnit("Worker", frank, testGame.getTile(2, 0))
        val barbUnit = testGame.addUnit("Warrior", barb, testGame.getTile(1, 0))

        // 蛮子移动进法兰克工人所在格 (触发移动俘虏路径)
        val targetTile = testGame.getTile(2, 0)
        try {
            barbUnit.movement.moveToTile(targetTile)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }

        // 工人不应被俘虏 (仍属于法兰克)
        val workerAfter = targetTile.civilianUnit
        assertEquals("蛮子移动进格不应俘虏法兰克工人", worker, workerAfter)
        println("移动俘虏: 蛮子移动到工人格 → 工人仍属法兰克 (期望)")
    }

    @Test
    fun `barbarian moving onto worker captures without cannot-attack`() {
        // 无全局词条 → 蛮子移动进格正常俘虏 (对照组)
        val testGame = TestGame()
        testGame.makeHexagonalMap(4)
        val frank = testGame.addNamedCiv("Franks")
        frank.playerType = PlayerType.Human
        val barb = testGame.addBarbarianCiv()
        frank.getDiplomacyManager(barb)!!.declareWar()

        testGame.addCity(frank, testGame.getTile(0, 0))
        val worker = testGame.addUnit("Worker", frank, testGame.getTile(2, 0))
        val barbUnit = testGame.addUnit("Warrior", barb, testGame.getTile(1, 0))

        val targetTile = testGame.getTile(2, 0)
        try {
            barbUnit.movement.moveToTile(targetTile)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }

        val workerAfter = targetTile.civilianUnit
        assertTrue("无词条时蛮子移动进格应俘虏 (工人被带走)", workerAfter == null || workerAfter.civ == barb)
        println("对照组: 蛮子移动进格 → 工人被俘虏 (期望)")
    }
}

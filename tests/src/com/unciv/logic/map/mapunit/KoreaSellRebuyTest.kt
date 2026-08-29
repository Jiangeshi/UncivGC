package com.unciv.logic.map.mapunit

import com.unciv.logic.civilization.PlayerType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class KoreaSellRebuyTest {

    @Test
    fun `real sell - rebuy path does not re-trigger boost`() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(3)
        val civ = testGame.addCiv("Receive a tech boost when scientific buildings/wonders are built in capital")
        civ.playerType = PlayerType.Human
        val capital = testGame.addCity(civ, testGame.getTile(1, 0))
        assertTrue(capital.isCapital())

        // 设置待研究科技
        val nextTech = testGame.ruleset.technologies.values.first { it.name !in civ.tech.techsResearched }.name
        civ.tech.techsToResearch.add(nextTech)
        val currentTech = civ.tech.currentTechnologyName()!!
        val progress = { civ.tech.techsInProgress[currentTech] ?: 0 }

        // 1. 建图书馆 (真实路径: purchaseConstruction)
        capital.cityConstructions.purchaseConstruction("Library", -1, false)
        val afterFirst = progress()
        println("第一次购买后科技进度: $afterFirst (techBoost: ${civ.techBoostEverBuiltBuildings})")

        // 2. 卖 (真实路径: sellBuilding)
        capital.sellBuilding("Library")
        println("卖后 techBoost: ${civ.techBoostEverBuiltBuildings}, built: ${capital.cityConstructions.builtBuildings}")

        // 3. 买回
        capital.cityConstructions.purchaseConstruction("Library", -1, false)
        val afterRebuy = progress()
        println("买回后科技进度: $afterRebuy (techBoost: ${civ.techBoostEverBuiltBuildings})")

        assertEquals("卖后买回不应重复触发", afterFirst, afterRebuy)
    }
}

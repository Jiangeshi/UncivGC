package com.unciv.logic.map.mapunit

import com.unciv.logic.civilization.PlayerType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class KoreaBuildSellRebuildTest {

    @Test
    fun `build - sell - rebuild does not re-trigger boost`() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(3)
        val civ = testGame.addCiv("Receive a tech boost when scientific buildings/wonders are built in capital")
        civ.playerType = PlayerType.Human
        val capital = testGame.addCity(civ, testGame.getTile(1, 0))
        assertTrue(capital.isCapital())

        val nextTech = testGame.ruleset.technologies.values.first { it.name !in civ.tech.techsResearched }.name
        civ.tech.techsToResearch.add(nextTech)
        val currentTech = civ.tech.currentTechnologyName()!!
        val progress = { civ.tech.techsInProgress[currentTech] ?: 0 }

        // 1. 生产建造 (completeConstruction 路径 = 锤子造完)
        val library = testGame.ruleset.buildings["Library"]!!
        val cost = library.getProductionCost(civ, capital)
        capital.cityConstructions.setCurrentConstruction("Library")  // 入队
        capital.cityConstructions.inProgressConstructions["Library"] = cost  // 锤子攒满
        capital.cityConstructions.constructIfEnough()  // 造完 → completeConstruction → addBuilding
        val afterFirst = progress()
        println("第一次造完: progress=$afterFirst techBoost=${civ.techBoostEverBuiltBuildings} built=${capital.cityConstructions.builtBuildings}")

        // 2. 卖
        capital.sellBuilding("Library")
        println("卖后: built=${capital.cityConstructions.builtBuildings} techBoost=${civ.techBoostEverBuiltBuildings}")

        // 3. 再造 (重新入队 + 锤子攒满 + 造完)
        capital.cityConstructions.setCurrentConstruction("Library")
        capital.cityConstructions.inProgressConstructions["Library"] = cost
        capital.cityConstructions.constructIfEnough()
        val afterRebuild = progress()
        println("再造完: progress=$afterRebuild techBoost=${civ.techBoostEverBuiltBuildings} built=${capital.cityConstructions.builtBuildings}")

        assertEquals("造-卖-造不应重复触发", afterFirst, afterRebuild)
    }
}

package com.unciv.logic.map.mapunit

import com.unciv.logic.civilization.PlayerType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class KoreaTurnCycleTest {

    @Test
    fun `build - sell - rebuild across turns does not re-trigger`() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(3)
        val civ = testGame.addCiv("Receive a tech boost when scientific buildings/wonders are built in capital")
        civ.playerType = PlayerType.Human
        val capital = testGame.addCity(civ, testGame.getTile(1, 0))
        assertTrue(capital.isCapital())

        // Library 需要 Writing 科技
        civ.tech.addTechnology("Writing")
        val nextTech = testGame.ruleset.technologies.values.first { it.name !in civ.tech.techsResearched }.name
        civ.tech.techsToResearch.add(nextTech)
        val currentTech = civ.tech.currentTechnologyName()!!
        val progress = { civ.tech.techsInProgress[currentTech] ?: 0 }
        val cost = testGame.ruleset.buildings["Library"]!!.getProductionCost(civ, capital)

        // 回合 1: 入队 + 攒满锤子
        capital.cityConstructions.setCurrentConstruction("Library")
        capital.cityConstructions.inProgressConstructions["Library"] = cost
        // 造完
        capital.cityConstructions.constructIfEnough()
        val p1 = progress()
        println("第一次造完 progress=$p1 boost=${civ.techBoostEverBuiltBuildings}")

        // 模拟存档重载 (单机每回合 autosave + 读档)
        val json = com.unciv.json.json()
        val save = json.toJson(testGame.gameInfo)
        val restored = json.fromJson(com.unciv.logic.GameInfo::class.java, save)
        restored.setTransients()
        val rCiv = restored.civilizations.first { it.civName == civ.civName }
        val rCity = rCiv.cities.first()
        println("重载后 boost=${rCiv.techBoostEverBuiltBuildings}")
        assertTrue("重载后应保留记录", rCiv.techBoostEverBuiltBuildings.contains("Library"))

        // 卖
        rCity.sellBuilding("Library")
        println("卖后 built=${rCity.cityConstructions.builtBuildings}")

        // 再造
        rCity.cityConstructions.setCurrentConstruction("Library")
        rCity.cityConstructions.inProgressConstructions["Library"] = cost
        rCity.cityConstructions.constructIfEnough()
        val p2 = rCiv.tech.techsInProgress[currentTech] ?: 0
        println("再造完 progress=$p2 boost=${rCiv.techBoostEverBuiltBuildings}")
        assertEquals("跨回合造-卖-造不应重复触发", p1, p2)
    }
}

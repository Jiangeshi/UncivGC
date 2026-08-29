package com.unciv.logic.map.mapunit

import com.unciv.logic.civilization.PlayerType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class KoreaCapitalMoveTest {

    @Test
    fun `building in non-capital then capital - can re-trigger once`() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(4)
        val civ = testGame.addCiv("Receive a tech boost when scientific buildings/wonders are built in capital")
        civ.playerType = PlayerType.Human
        val capital = testGame.addCity(civ, testGame.getTile(1, 0))  // 首都 (有 Palace)
        val otherCity = testGame.addCity(civ, testGame.getTile(3, 0)) // 非首都

        val nextTech = testGame.ruleset.technologies.values.first { it.name !in civ.tech.techsResearched }.name
        civ.tech.techsToResearch.add(nextTech)
        val currentTech = civ.tech.currentTechnologyName()!!
        val progress = { civ.tech.techsInProgress[currentTech] ?: 0 }

        // 非首都城市建图书馆 → isCapital=false → 不触发, 也不记录
        otherCity.cityConstructions.purchaseConstruction("Library", -1, false)
        println("非首都建: progress=${progress()} techBoost=${civ.techBoostEverBuiltBuildings}")
        assertEquals("非首都建不应触发", 0, progress())

        // 卖
        otherCity.sellBuilding("Library")
        // 首都迁移 (原版机制: 首都丢失后其他城变首都 — 模拟: 直接删 Palace 再重建)
        capital.cityConstructions.removeBuilding("Palace")
        otherCity.cityConstructions.addBuilding("Palace")
        assertTrue("otherCity 应成为首都", otherCity.isCapital())

        // 再买回图书馆 → isCapital=true → 触发一次
        otherCity.cityConstructions.purchaseConstruction("Library", -1, false)
        println("变首脑后买回: progress=${progress()} techBoost=${civ.techBoostEverBuiltBuildings}")
        assertTrue("变首脑后应触发一次", progress() > 0)
    }
}

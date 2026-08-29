package com.unciv.logic.map.mapunit

import com.unciv.logic.civilization.PlayerType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class KoreaTechBoostTest {

    @Test
    fun `korea tech boost triggers once even after sell and rebuy`() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(3)
        // 给文明加朝鲜 UA 词条 (addCiv vararg 直接加, 走正常 unique 链路)
        val civ = testGame.addCiv(
            "Receive a tech boost when scientific buildings/wonders are built in capital")
        civ.playerType = PlayerType.Human
        // 建首都 (Palace 自动)
        val capital = testGame.addCity(civ, testGame.getTile(1, 0))
        assertTrue("城市应有 Palace 成为首都", capital.isCapital())
        // 设置一个待研究科技 (addScience 需要 currentTechnologyName 非空)
        val nextTech = testGame.ruleset.technologies.values.first { it.name !in civ.tech.techsResearched }.name
        civ.tech.techsToResearch.add(nextTech)
        // 科技进度
        val techProgress = civ.tech.techsInProgress
        val currentTech = civ.tech.currentTechnologyName()!!
        val progress0 = techProgress[currentTech] ?: 0
        // 检查条件
        val library = testGame.ruleset.buildings["Library"]!!
        println("hasUnique: ${civ.hasUnique(com.unciv.models.ruleset.unique.UniqueType.TechBoostWhenScientificBuildingsBuiltInCapital)}")
        println("isCapital: ${capital.isCapital()}")
        println("isStatRelated: ${library.isStatRelated(com.unciv.models.stats.Stat.Science, capital)}")
        println("add 前 techBoostEverBuiltBuildings: ${civ.techBoostEverBuiltBuildings}")
        // 第一次建图书馆 → 应触发加成
        capital.cityConstructions.addBuilding("Library")
        println("add 后 techBoostEverBuiltBuildings: ${civ.techBoostEverBuiltBuildings}")
        val progress1 = techProgress[currentTech] ?: 0
        assertTrue("第一次建图书馆应触发科技加成 ($progress0 → $progress1)", progress1 > progress0)
        // 卖图书馆
        capital.cityConstructions.removeBuilding("Library")
        // 买回 → 不应再触发
        capital.cityConstructions.addBuilding("Library")
        val progress2 = techProgress[currentTech] ?: 0
        assertEquals("卖后买回不应重复触发加成", progress1, progress2)
        assertTrue("techBoostEverBuiltBuildings 应记录 Library", civ.techBoostEverBuiltBuildings.contains("Library"))
    }
}

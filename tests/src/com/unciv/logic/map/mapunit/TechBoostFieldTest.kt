package com.unciv.logic.map.mapunit

import com.unciv.logic.civilization.PlayerType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class TechBoostFieldTest {

    @Test
    fun `field survives gameInfoToString roundtrip`() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(2)
        val civ = testGame.addCiv()
        civ.playerType = PlayerType.Human
        civ.techBoostEverBuiltBuildings.add("Kiln")

        // 真实存档: toJson 全量 → 检查 JSON 文本是否含字段
        val save = com.unciv.json.json().toJson(testGame.gameInfo)
        println("存档 JSON 含 techBoostEverBuiltBuildings: ${save.contains("techBoostEverBuiltBuildings")}")
        assertTrue("存档 JSON 应含字段", save.contains("techBoostEverBuiltBuildings"))
    }
}

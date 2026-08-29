package com.unciv.logic.map.mapunit

import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class TechBoostSerializationTest {

    @Test
    fun `techBoostEverBuiltBuildings survives save load`() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(2)
        val civ = testGame.addCiv()
        civ.techBoostEverBuiltBuildings.add("Library")
        // 序列化再反序列化
        val json = com.unciv.json.json()
        val save = json.toJson(testGame.gameInfo)
        val restored = json.fromJson(com.unciv.logic.GameInfo::class.java, save)
        val restoredCiv = restored.civilizations.first { it.civName == civ.civName }
        assertTrue("序列化应保留 techBoostEverBuiltBuildings", restoredCiv.techBoostEverBuiltBuildings.contains("Library"))
    }
}

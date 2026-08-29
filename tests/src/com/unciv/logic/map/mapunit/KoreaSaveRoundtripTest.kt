package com.unciv.logic.map.mapunit

import com.unciv.logic.civilization.PlayerType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class KoreaSaveRoundtripTest {

    @Test
    fun `techBoost survives real save format`() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(2)
        val civ = testGame.addCiv()
        civ.playerType = PlayerType.Human
        civ.techBoostEverBuiltBuildings.add("Kiln")
        civ.techBoostEverBuiltBuildings.add("Library")

        // 真实存档格式: gameInfoToString (json + gzip) → gameInfoFromString
        val save = com.unciv.logic.files.UncivFiles.gameInfoToString(testGame.gameInfo, false, false)
        val restored = com.unciv.logic.files.UncivFiles.gameInfoFromString(save)
        val rCiv = restored.civilizations.first { it.civName == civ.civName }
        println("恢复后 techBoostEverBuiltBuildings = ${rCiv.techBoostEverBuiltBuildings}")
        assertTrue("真实存档格式应保留 Kiln", rCiv.techBoostEverBuiltBuildings.contains("Kiln"))
        assertTrue("真实存档格式应保留 Library", rCiv.techBoostEverBuiltBuildings.contains("Library"))
    }
}

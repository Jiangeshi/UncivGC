package com.unciv.logic.map.mapunit

import com.unciv.logic.civilization.PlayerType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class FormationWhitelistTests {

    @Test
    fun `no whitelist - default allows corps`() {
        val testGame = TestGame()  // 无 AllowsFormation 词条
        testGame.makeHexagonalMap(3)
        val civ = testGame.addCiv()
        civ.playerType = PlayerType.Human
        val unit = testGame.addUnit("Swordsman", civ, testGame.getTile(1, 0))
        assertTrue("无白名单时应默认允许编队", unit.canFormCorps())
    }

    @Test
    fun `whitelist restricts to matching unit type`() {
        // 只允许 Swordsman 组军团
        val testGame = TestGame("Allows formation of [Corps] for [Swordsman] units")
        testGame.makeHexagonalMap(3)
        val civ = testGame.addCiv()
        civ.playerType = PlayerType.Human
        val swordsman = testGame.addUnit("Swordsman", civ, testGame.getTile(1, 0))
        val warrior = testGame.addUnit("Warrior", civ, testGame.getTile(2, 0))
        assertTrue("Swordsman 应在白名单内", swordsman.canFormCorps())
        assertFalse("Warrior 不在白名单内", warrior.canFormCorps())
    }

    @Test
    fun `whitelist formation type matters - corps vs army`() {
        // 只允许 Army (集团军) 不允许 Corps (军团)
        val testGame = TestGame("Allows formation of [Army] for [Swordsman] units")
        testGame.makeHexagonalMap(3)
        val civ = testGame.addCiv()
        civ.playerType = PlayerType.Human
        val swordsman = testGame.addUnit("Swordsman", civ, testGame.getTile(1, 0))
        assertFalse("只有 Army 白名单时不能直接组 Corps", swordsman.canFormCorps())
    }

    @Test
    fun `water unit whitelist uses fleet`() {
        // 只允许 Fleet — 水军组舰队应该通过 (canFormCorps 内部按 isWaterUnit 转 Fleet)
        val testGame = TestGame("Allows formation of [Fleet] for [Water] units")
        testGame.makeHexagonalMap(3, "Ocean")
        val civ = testGame.addCiv()
        civ.playerType = PlayerType.Human
        val tile = testGame.getTile(1, 0)
        tile.baseTerrain = "Coast"
        tile.isWater = true
        tile.isLand = false
        val galleass = testGame.addUnit("Galleass", civ, tile)
        assertTrue("水军应可组舰队 (Fleet 白名单)", galleass.canFormCorps())
    }
}

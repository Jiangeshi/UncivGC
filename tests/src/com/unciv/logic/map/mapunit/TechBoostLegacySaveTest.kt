package com.unciv.logic.map.mapunit

import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class TechBoostLegacySaveTest {

    @Test
    fun `legacy save without field - add works once then blocks`() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(2)
        val civ = testGame.addCiv()
        // 模拟旧存档: 字段为空 (未初始化)
        civ.techBoostEverBuiltBuildings = HashSet()  // 反序列化后实际是空集合
        assertNotNull(civ.techBoostEverBuiltBuildings)
        // 第一次 add → true (记录)
        assertTrue(civ.techBoostEverBuiltBuildings.add("Library"))
        // 第二次 add → false (已记录, 不再触发)
        assertTrue(!civ.techBoostEverBuiltBuildings.add("Library"))
    }
}

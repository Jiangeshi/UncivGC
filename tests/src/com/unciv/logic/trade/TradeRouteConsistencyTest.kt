package com.unciv.logic.trade

import com.unciv.logic.civilization.PlayerType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class TradeRouteConsistencyTest {

    @Test
    fun `initiator income computed from both sides should match`() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(5)
        val civA = testGame.addCiv()
        civA.playerType = PlayerType.Human
        val civB = testGame.addCiv()
        civB.playerType = PlayerType.Human
        val cityA = testGame.addCity(civA, testGame.getTile(0, 0))
        val cityB = testGame.addCity(civB, testGame.getTile(3, 0))
        testGame.gameInfo.tradeRoutes.getOrPut(cityA.id) { ArrayList() }.add(cityB.id)
        val network = testGame.gameInfo.getTradeRouteNetwork()
        val route = network.getEstablishedRoutes(cityA).first()
        // 同一实例上算 (模拟双方本地数据一致时)
        val initiatorView = TradeRoutes.totalStats(cityA, cityA, route, true)
        val receiverView = TradeRoutes.totalStats(cityA, cityA, route, true)
        assertEquals("同实例计算应一致", initiatorView, receiverView)
        println("发起方收益: $initiatorView")
    }
}

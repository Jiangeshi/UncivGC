package com.unciv.logic.map.mapgenerator

import com.unciv.Constants
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.TileMap
import com.unciv.models.metadata.GameParameters
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.unciv.testing.BaseTestRunner

@RunWith(BaseTestRunner::class)
class MapResizeTests {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame()
    }

    private fun makeRectangular(width: Int, height: Int, worldWrap: Boolean = false): TileMap {
        game.makeRectangularMap(height, width, Constants.grassland)
        game.tileMap.mapParameters.shape = MapShape.rectangular
        game.tileMap.mapParameters.worldWrap = worldWrap
        if (worldWrap && width % 2 != 0) game.tileMap.mapParameters.mapSize.width = width - 1
        return game.tileMap
    }

    private fun MapGenerator.resize(map: TileMap, w: Int, h: Int, anchor: String) = resizeMap(map, w, h, anchor)

    @Test
    fun `crop center keeps middle tiles`() {
        val map = makeRectangular(11, 7)
        // 标记四个角, 确认裁剪后中间保留
        val generator = MapGenerator(game.ruleset)
        generator.resize(map, 5, 3, "center")
        assertEquals(15, map.values.size)
        // 新地图范围: 列 -2..2, 行 -1..1
        val cols = map.values.map { HexMath.getColumn(it.position) }
        val rows = map.values.map { HexMath.getRow(it.position) }
        assertEquals(-2, cols.min())
        assertEquals(2, cols.max())
        assertEquals(-1, rows.min())
        assertEquals(1, rows.max())
    }

    @Test
    fun `crop topLeft keeps top left corner`() {
        val map = makeRectangular(11, 7)
        val generator = MapGenerator(game.ruleset)
        generator.resize(map, 5, 3, "topleft")
        assertEquals(15, map.values.size)
        // 上锚: 行最大=3 保留 (row 增大=向上); 左锚: 列最小=-5 保留
        val cols = map.values.map { HexMath.getColumn(it.position) }
        val rows = map.values.map { HexMath.getRow(it.position) }
        assertEquals(-5, cols.min())
        assertEquals(-1, cols.max())
        assertEquals(1, rows.min())
        assertEquals(3, rows.max())
    }

    @Test
    fun `expand bottomRight fills ocean`() {
        val map = makeRectangular(11, 7)
        val generator = MapGenerator(game.ruleset)
        generator.resize(map, 13, 9, "bottomright")
        assertEquals(117, map.values.size)
        // 下锚: 行最小=-3 保留; 右锚: 列最大=5 保留
        val cols = map.values.map { HexMath.getColumn(it.position) }
        val rows = map.values.map { HexMath.getRow(it.position) }
        assertEquals(-7, cols.min())
        assertEquals(5, cols.max())
        assertEquals(-3, rows.min())
        assertEquals(5, rows.max())
        // 新增地块应为海洋
        val originalTileCount = 11 * 7
        val oceanTiles = map.values.count { it.baseTerrain == Constants.ocean }
        assertEquals(117 - originalTileCount, oceanTiles)
    }

    @Test
    fun `starting locations outside new bounds are filtered`() {
        val map = makeRectangular(11, 7)
        map.startingLocations.add(TileMap.StartingLocation(HexMath.getTileCoordsFromColumnRow(5, 3), "CivA"))
        map.startingLocations.add(TileMap.StartingLocation(HexMath.getTileCoordsFromColumnRow(-5, -3), "CivB"))
        val generator = MapGenerator(game.ruleset)
        generator.resize(map, 5, 3, "center")
        // 两个出生点都在中心裁剪范围外 → 都被过滤
        assertEquals(0, map.startingLocations.size)
    }

    @Test
    fun `world wrap rounds odd width down`() {
        val map = makeRectangular(11, 7, worldWrap = true)
        val generator = MapGenerator(game.ruleset)
        generator.resize(map, 9, 5, "center")
        // worldWrap: 9 是奇数 → 取偶为 8
        assertEquals(8 * 5, map.values.size)
        assertEquals(8, map.mapParameters.mapSize.width)
    }

    @Test
    fun `hexagonal map is not resized`() {
        game.makeHexagonalMap(3, Constants.grassland)
        val map = game.tileMap
        map.mapParameters.shape = MapShape.hexagonal
        val originalSize = map.values.size
        MapGenerator(game.ruleset).resizeMap(map, 5, 5, "center")
        assertEquals(originalSize, map.values.size)
    }
}

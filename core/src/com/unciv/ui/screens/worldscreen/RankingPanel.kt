package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.civilization.Civilization
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.victoryscreen.RankingType
import kotlin.math.roundToInt

/**
 * 实验性 UI (2026-08-22): 文明6 式实时排行面板 — 位于「选择科技」按钮下方.
 *
 * 每个文明一列 = 一个完整的蓝底半透明长方形 (用户 2026-08-22 确认):
 * ```
 * [文明1]  [文明2]  [文明3]
 *  图标     图标     图标
 *  科技+3   科技+5   科技+4
 *  文化+2   文化+4   文化+3
 *  金钱+50  金钱+30  金钱+45
 *  产能+15  产能+20  产能+18
 *  分数100  分数200  分数150
 *  军力50   军力80   军力60
 * ```
 * 列宽均分, 总宽 = 科技按钮宽度 (update 传入); 列内居中; 按分数降序; 自己列金黄; 战败列灰.
 * 数据: 分数/军力为累计值, 科技/文化/金钱/产能为每回合增量 (与顶栏一致).
 */
class RankingPanel(private val worldScreen: WorldScreen) : Table(BaseScreen.skin) {

    private val categories = listOf(
        RankingType.Score,
        RankingType.Force,
        RankingType.Technologies,
        RankingType.Culture,
        RankingType.Gold,
        RankingType.Production
    )

    private val rowHeight = 26f
    private val gray = Color(0.55f, 0.55f, 0.55f, 0.8f)

    private val rowBackground: com.badlogic.gdx.scenes.scene2d.utils.Drawable = BaseScreen.skinStrings.getUiBackground(
        "RankingPanel/Row",
        BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        Color(0.12f, 0.25f, 0.55f, 0.25f) // 蓝底半透明 (更透明)
    )
    private val myRowBackground: com.badlogic.gdx.scenes.scene2d.utils.Drawable = BaseScreen.skinStrings.getUiBackground(
        "RankingPanel/MyRow",
        BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        Color(0.45f, 0.35f, 0.08f, 0.35f) // 自己: 金黄底
    )
    private val grayBackground: com.badlogic.gdx.scenes.scene2d.utils.Drawable = BaseScreen.skinStrings.getUiBackground(
        "RankingPanel/GrayRow",
        BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        Color(0.3f, 0.3f, 0.3f, 0.25f) // 战败: 灰底
    )

    /** 大数显示: >=10000 用 k 单位 (10k/12k — 用户 2026-08-23); 四位数内原样 */
    private fun formatValue(v: Int): String = if (v >= 10000) "${v / 1000}k" else v.toString()

    /** 取值: 分数/军力为累计值; 科技/文化/金钱/产能为每回合增量 (与顶栏一致 — 用户 2026-08-22) */
    private fun getValue(civ: Civilization, category: RankingType): Int = when (category) {
        RankingType.Score, RankingType.Force -> civ.getStatForRanking(category)
        RankingType.Technologies -> civ.stats.statsForNextTurn.science.roundToInt()
        RankingType.Culture -> civ.stats.statsForNextTurn.culture.roundToInt()
        RankingType.Gold -> civ.stats.statsForNextTurn.gold.roundToInt()
        RankingType.Production -> civ.stats.statsForNextTurn.production.roundToInt()
        else -> civ.getStatForRanking(category)
    }

    /** @param columnWidth 每列 (文明) 宽度 — 固定, 容纳 4 位数 (用户 2026-08-23); 最多 4 列 */
    fun update(columnWidth: Float) {
        clear()
        align(Align.topLeft)
        defaults().left().pad(0f)

        val gameInfo = worldScreen.gameInfo
        val myCiv = worldScreen.viewingCiv
        // 主要文明 (不含城邦/蛮族/观战); 战败包含但灰显 (文明6 式)
        // 只显示已遇到 (met) 的文明 — 未遇到不显示 (2026-08-22 用户要求); 自己/观战者总是显示全部
        val civs = gameInfo.civilizations
            .filter { it.isMajorCiv() }
            .filter { civ ->
                myCiv.isSpectator() || civ.civID == myCiv.civID || myCiv.diplomacy.containsKey(civ.civID)
            }
            .sortedByDescending { if (it.isDefeated()) Int.MIN_VALUE else it.getStatForRanking(RankingType.Score) }
            .take(4)  // 最多 4 列 (每多遇到一个加一列, 最多 4 — 用户 2026-08-23)

        if (civs.isEmpty()) return

        val colWidth = columnWidth  // 固定列宽

        // 每个文明一列 = 一个完整的长方形背景
        for (civ in civs) {
            val col = Table()
            col.background = when {
                civ.isDefeated() -> grayBackground
                civ.civID == myCiv.civID -> myRowBackground
                else -> rowBackground
            }
            col.defaults().center()

            val defeated = civ.isDefeated()

            // 行0: 文明图标
            val civIcon = ImageGetter.getNationPortrait(civ.nation, 22f)
            if (defeated) civIcon.color = gray
            col.add(civIcon).size(22f).height(rowHeight).row()

            // 行1..6: 属性图标 + 数值
            for (category in categories) {
                val icon = getCategoryIcon(category)
                val value = getValue(civ, category)
                val label = formatValue(value).toLabel(fontSize = 13)
                if (defeated) {
                    icon.color = gray
                    label.setColor(gray)
                }
                val line = Table()
                line.defaults().pad(0f)
                line.add(icon).size(13f).padRight(1f)
                line.add(label)
                col.add(line).height(rowHeight).row()
            }
            col.pack()

            add(col).width(colWidth).pad(1f) // 列间 1px 缝; 列内连续 (同一背景)
        }
        row()
        pack()
    }

    private fun getCategoryIcon(category: RankingType): Image {
        val image = category.getImage()
        if (image != null) return image
        // Production/Gold/Culture 的 RankingType 图标为 null (原版注释: 图标由翻译文本提供) → 用 StatIcons
        return when (category) {
            RankingType.Production -> ImageGetter.getStatIcon("Production")
            RankingType.Gold -> ImageGetter.getStatIcon("Gold")
            RankingType.Culture -> ImageGetter.getStatIcon("Culture")
            else -> ImageGetter.getStatIcon("Science")
        }
    }
}

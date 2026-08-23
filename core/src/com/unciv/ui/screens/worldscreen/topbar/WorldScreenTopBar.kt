package com.unciv.ui.screens.worldscreen.topbar

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Container
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.logic.civilization.Civilization
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onRightClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.overviewscreen.EmpireOverviewCategories
import com.unciv.ui.screens.worldscreen.BackgroundActor
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread
import com.unciv.ui.screens.worldscreen.mainmenu.WorldScreenMenuPopup
import kotlin.math.max


/**
 * Table consisting of the menu button, current civ, some stats and the overview button for the top of [WorldScreen].
 *
 * Calling [update] will refresh content and layout, and place the Table on the top edge of the stage, filling its width.
 *
 * [update] will also attempt geometry optimization:
 *  * When there's enough room, the top bar has the stats row ([WorldScreenTopBarStats]) and the resources
 *      row ([WorldScreenTopBarResources]), and the selected-civ ([SelectedCivilizationTable]) and overview
 *      ([OverviewAndSupplyTable]) button elements are overlaid (floating, not in a Cell) to the left and right.
 *  * When screen space gets cramped (low resolution or portrait mode) and one of the overlaid elements would
 *      cover parts of the stats and/or resources lines, we move them down accordingly - below the stats line
 *      if the resources still have enough room, below both otherwise.
 *  * But the elements should have a background - this is done with "filler cells". This Table is now 3x3,
 *      with the stats line as colspan(3) in the top row, resources also colspan(3) in the second row,
 *      and the third row is filler - empty - filler. These fillers do a background with just one rounded
 *      corner - bottom and to the screen center. The middle cell of that row has no actor and expands,
 *      and since the entire Table is Touchable.childrenOnly, completely transparent to the map below.
 *
 * Table layout in the "cramped" case:
 * ```
 * +----------------------------------------+
 * | WorldScreenTopBarStats      colspan(3) |
 * +----------------------------------------+
 * | WorldScreenTopBarResources  colspan(3) |
 * +----------------------------------------+
 * | Filler |    transparent!!!    | Filler |
 * +--------╝                      ╚--------+
 * ```
 * Reminder: Not the `Table`, the `Cell` actors (all except the transparent one) have the background.
 * To avoid gaps, _no_ padding except inside the cell actors, and all actors need to _fill_ their cell.
 */

//region Fields
class WorldScreenTopBar(internal val worldScreen: WorldScreen) : Table() {

    private val statsTable = WorldScreenTopBarStats(this)
    private val resourceTable = WorldScreenTopBarResources(this)
    private val selectedCivTable = SelectedCivilizationTable(worldScreen)
    private val overviewButton = OverviewAndSupplyTable(worldScreen)
    private val leftFiller: BackgroundActor
    private val rightFiller: BackgroundActor
    private var baseHeight = 0f
    /** UncivGC 帧同步: 暂停按钮 (顶栏自持, 生命周期随顶栏 — 重载后随顶栏重建, 无竞态) */
    private var fsPauseButton: com.badlogic.gdx.scenes.scene2d.ui.TextButton? = null

    /** UncivGC 帧同步: 玩家状态按钮 (在线/过回合/文明) — 放暂停按钮左边 */
    private var fsStatusButton: com.badlogic.gdx.scenes.scene2d.ui.TextButton? = null

    /** UncivGC: 聊天按钮 (帧同步模式移到顶栏, 与 状态/暂停/概览 并列 — 2026-08-22 用户要求)
     *  纯文字按钮 (去掉 icon, 大小与其他按钮一致); lazy: WorldScreen.chatButton 声明在 topBar 之后,
     *  init 直接访问会 NPE (开房卡住根因) */
    private val fsChatButton: com.badlogic.gdx.scenes.scene2d.Actor? by lazy {
        if (com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(worldScreen.gameInfo)) {
            val btn = com.badlogic.gdx.scenes.scene2d.ui.TextButton("Chat".tr(), BaseScreen.skin)
            btn.onClick {
                val chat = com.unciv.logic.multiplayer.chat.ChatStore.getChatByGameId(worldScreen.gameInfo.gameId)
                chat.unreadCount = 0
                com.unciv.logic.multiplayer.chat.ChatStore.hasGlobalMessage = false
                com.unciv.ui.screens.worldscreen.chat.ChatPopup(chat, worldScreen).open()
            }
            btn.pack()
            btn.setSize(maxOf(btn.width, 60f), btn.height)  // 与其他按钮同宽 (2026-08-22)
            btn
        } else null
    }

    companion object {
        /** When the "fillers" are used, this is added to the required height, alleviating the "gap" problem a little. */
        const val gapFillingExtraHeight = 1f
    }
    //endregion

    init {
        // init only prepares, the cells are created by update()

        defaults().center()
        setRound(false) // Prevent Table from doing internal rounding which would provoke gaps

        val backColor = BaseScreen.skinStrings.skinConfig.baseColor.darken(0.5f)
        statsTable.background = BaseScreen.skinStrings.getUiBackground("WorldScreen/TopBar/StatsTable", tintColor = backColor)
        resourceTable.background = BaseScreen.skinStrings.getUiBackground("WorldScreen/TopBar/ResourceTable", tintColor = backColor)

        val leftFillerBG = BaseScreen.skinStrings.getUiBackground("WorldScreen/TopBar/LeftAttachment", BaseScreen.skinStrings.roundedEdgeRectangleShape, backColor)
        leftFiller = BackgroundActor(leftFillerBG, Align.topLeft)
        val rightFillerBG = BaseScreen.skinStrings.getUiBackground("WorldScreen/TopBar/RightAttachment", BaseScreen.skinStrings.roundedEdgeRectangleShape, backColor)
        rightFiller = BackgroundActor(rightFillerBG, Align.topRight)

        // UncivGC 帧同步: 创建暂停按钮 (放概览按钮旁边; 事件走 FrameSync) — 观战者不创建 (不能暂停)
        if (com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(worldScreen.gameInfo)
            && !worldScreen.viewingCiv.isSpectator()) {
            val btn = com.badlogic.gdx.scenes.scene2d.ui.TextButton("Pause".tr(), BaseScreen.skin)
            btn.onClick { com.unciv.ui.screens.worldscreen.FrameSync.togglePause() }
            btn.pack()
            btn.setSize(maxOf(btn.width, 60f), btn.height)  // 统一最小宽, 间距视觉一致 (2026-08-22)
            fsPauseButton = btn
            com.unciv.ui.screens.worldscreen.FrameSync.registerFsPauseButton(btn)
        }

        // UncivGC 帧同步: 玩家状态按钮 (在线/过回合/文明) — 观战者也能看
        if (com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(worldScreen.gameInfo)) {
            val btn = com.badlogic.gdx.scenes.scene2d.ui.TextButton("Status".tr(), BaseScreen.skin)
            btn.onClick { showPlayerStatusPopup() }
            btn.pack()
            btn.setSize(maxOf(btn.width, 60f), btn.height)
            fsStatusButton = btn
        }

    }

    internal fun update(civInfo: Civilization) {
        setLayoutEnabled(false)
        statsTable.update(civInfo)
        resourceTable.update(civInfo)
        selectedCivTable.update(worldScreen)
        overviewButton.update(worldScreen)
        updateLayout()
        setLayoutEnabled(true)
    }

    internal fun getYForTutorialTask(): Float = y + height - baseHeight

    /** Performs the layout tricks mentioned in the class Kdoc */
    private fun updateLayout() {
        val targetWidth = stage.width
        val statsWidth = statsTable.prefWidth
        val resourceWidth = resourceTable.prefWidth
        val overviewWidth = overviewButton.minWidth
        val overviewHeight = overviewButton.minHeight
        val selectedCivWidth = selectedCivTable.minWidth
        val selectedCivHeight = selectedCivTable.minHeight
        // Since stats/resource lines are centered, the max decides when to snap the overlaid elements down
        val leftRightNeeded = max(selectedCivWidth, overviewWidth)
        // Height of the two "overlay" elements should be equal, but just in case:
        val overlayHeight = max(overviewHeight, selectedCivHeight)

        clear()
        // Without the explicit cell width, a 'stats' line wider than the stage can force the Table to
        // misbehave and place the filler actors out of bounds, even if Table.width is correct.
        add(statsTable).colspan(3).growX().width(targetWidth).row()
        // Probability of a too-wide resources line is low in Vanilla, but mods may have lots more...
        add(resourceTable).colspan(3).growX().width(targetWidth).row()
        layout()  // force rowHeight calculation - validate is not enough - Table quirks
        val statsRowHeight = getRowHeight(0)
        baseHeight = statsRowHeight + getRowHeight(1)

        fun addFillers(fillerHeight: Float) {
            add(leftFiller).size(selectedCivWidth, fillerHeight + gapFillingExtraHeight)
            add().growX()
            add(rightFiller).size(overviewWidth, fillerHeight + gapFillingExtraHeight)
        }

        // Check whether it gets cramped on narrow aspect ratios
        // UncivGC 实验性 UI: 禁用 cramped 下移 — 手机矮屏按钮全部下移 (用户反馈 APK 布局错乱);
        // 左对齐布局下按钮固定在上部, 不随 fillers 模式移动 (2026-08-22)
        val centerButtonsToHeight = if (GUI.getSettings().experimentalUi) baseHeight else when {
            leftRightNeeded * 2f > targetWidth - resourceWidth -> {
                // Need to shift buttons down to below both stats and resources
                addFillers(overlayHeight)
                overlayHeight
            }
            leftRightNeeded * 2f > targetWidth - statsWidth -> {
                // Shifting buttons down to below stats row is enough
                addFillers(statsRowHeight)
                overlayHeight
            }
            else -> {
                // Enough space to keep buttons to the left and right of stats and resources - no fillers
                baseHeight
            }
        }

        // Don't use align with setPosition as we haven't pack()ed and element dimensions might not be final
        setSize(targetWidth, prefHeight)  // sizing to prefHeight is half a pack()
        setPosition(0f, stage.height - prefHeight)

        // UncivGC 实验性 UI (2026-08-22): 左对齐 + 左侧留出 菜单+文明名+文明贴图 空间, 防止重叠;
        // 每次 update 都重新应用 (开关切换立即生效, 不用等重建); overlay 下移 (cramped/fillers 模式) 时左侧没有 overlay → 不需要留白
        if (GUI.getSettings().experimentalUi) {
            statsTable.setAlign(Align.left)
            resourceTable.setAlign(Align.left)
            if (centerButtonsToHeight == baseHeight) {
                val leftPad = selectedCivWidth + 10f
                statsTable.padLeft(leftPad)
                resourceTable.padLeft(leftPad)
            }
        } else {
            statsTable.setAlign(Align.center)
            resourceTable.setAlign(Align.center)
            statsTable.padLeft(0f)
            resourceTable.padLeft(0f)
        }

        selectedCivTable.setPosition(0f, (centerButtonsToHeight - selectedCivHeight) / 2f)
        overviewButton.setPosition(targetWidth - overviewWidth, (centerButtonsToHeight - overviewHeight) / 2f)
        addActor(selectedCivTable) // needs to be after size
        addActor(overviewButton)
        // UncivGC 帧同步: 暂停按钮放概览按钮旁边 (顶栏子 actor; updateLayout 的 clear() 会清掉 → 每次重新挂载+定位)
        // 2026-08-23 用户反馈: 概览↔暂停间距比暂停↔状态大 — 概览 Table 内部 pad(10f), 文本按钮左缘实际在 x+10 → 暂停右移 10 对齐
        fsPauseButton?.let { btn ->
            if (btn.parent !== this) addActor(btn)
            btn.setPosition(overviewButton.x - btn.width + 5f, (centerButtonsToHeight - btn.height) / 2f)
        }
        // UncivGC 帧同步: 状态按钮放暂停按钮左边 (状态 | 暂停 | 概览)
        fsStatusButton?.let { btn ->
            if (btn.parent !== this) addActor(btn)
            // 观战者没有暂停按钮: 状态直接贴概览 — 用与暂停按钮同款的 +5f 修正 (对齐 OverviewAndSupplyTable
            // 内部 pad(10) 的文本按钮; 否则状态与概览文字间距 15px, 比正常视角大, 2026-08-23 用户反馈"看海距离不对")
            val anchorX = fsPauseButton?.let { it.x - btn.width - 5f }
                ?: (overviewButton.x - btn.width + 5f)
            btn.setPosition(anchorX, (centerButtonsToHeight - btn.height) / 2f)
        }
        // UncivGC: 聊天按钮放状态按钮左边 (聊天 | 状态 | 暂停 | 概览 — 2026-08-22 用户要求并列)
        fsChatButton?.let { btn ->
            if (btn.parent !== this) addActor(btn)
            val anchorX = fsStatusButton?.let { it.x - btn.width - 5f }
                ?: fsPauseButton?.let { it.x - btn.width - 5f }
                ?: (overviewButton.x - btn.width - 5f)
            btn.setPosition(anchorX, (centerButtonsToHeight - btn.height) / 2f)
        }
    }

    /** 玩家状态弹窗: 表格排版 — 文明头像 | 昵称 | 文明 | 状态 | 回合 (纯文字, 无 emoji — 游戏字体不支持) */
    private fun showPlayerStatusPopup() {
        val fs = com.unciv.ui.screens.worldscreen.FrameSync
        val popup = com.unciv.ui.popups.Popup(worldScreen)
        popup.addGoodSizedLabel("Players".tr()).row()
        val table = com.badlogic.gdx.scenes.scene2d.ui.Table()
        popup.add(table).pad(10f).row()
        popup.addCloseButton()
        popup.open()

        // 实时刷新: 每秒重建表格内容 (在线/过回合状态变化立即可见, 不用关掉重开)
        // popup 关闭 (isVisible=false) 即停止循环
        fun buildTable() {
            table.clear()
            val online = fs.onlinePlayers.toSet()
            val ready = fs.turnReadyPlayers.toSet()
            // 我方文明排最前 (城邦不显示)
            val myCiv = worldScreen.viewingCiv
            val civs = if (myCiv.isSpectator())
                worldScreen.gameInfo.civilizations.filter { !it.isBarbarian && !it.isSpectator() && !it.isCityState }
            else
                listOf(myCiv) + worldScreen.gameInfo.civilizations.filter {
                    !it.isBarbarian && !it.isSpectator() && !it.isCityState && it.civID != myCiv.civID
                }
            // 表头: 头像 | 昵称 | 文明 | 状态 | 回合
            table.add("".toLabel(fontSize = 12)).padRight(8f)
            table.add("Nickname".tr().toLabel(fontSize = 12)).padRight(12f)
            table.add("Civilization".tr().toLabel(fontSize = 12)).padRight(12f)
            table.add("Status".tr().toLabel(fontSize = 12)).padRight(12f)
            table.add("Turn".tr().toLabel(fontSize = 12)).row()
            for (civ in civs) {
                val pid = civ.playerId
                val nick = if (pid.isNullOrEmpty()) null else fs.playerNicknames[pid]
                val isOnline = pid != null && pid in online
                val isReady = pid != null && pid in ready
                val isMe = civ.civID == myCiv.civID && !myCiv.isSpectator()
                // 文明头像
                val portrait = com.unciv.ui.images.ImageGetter.getNationPortrait(civ.nation, 32f)
                table.add(portrait).padRight(8f)
                // 昵称列 (独立列, 不再拼进文明名; 自己无昵称时显示 "You")
                val nickText = when {
                    nick != null && nick.isNotBlank() && nick != pid -> nick
                    isMe -> ("You").tr()
                    else -> "-"
                }
                val nickLabel = nickText.toLabel(fontSize = 16)
                if (isMe) nickLabel.setColor(com.badlogic.gdx.graphics.Color.YELLOW)
                table.add(nickLabel).padRight(12f)
                // 文明名 (文明名单独 tr — 整体 tr 会因拼接无翻译键而失败显示英文)
                val civNameTr = civ.civName.tr()
                val nameLabel = civNameTr.toLabel(fontSize = 16)
                if (isMe) nameLabel.setColor(com.badlogic.gdx.graphics.Color.YELLOW)
                table.add(nameLabel).padRight(12f)
                // 状态 (纯文字): 战败优先, 其次 AI (玩家退出/托管), 再在线/离线
                val statusText = when {
                    civ.isDefeated() -> "Defeated".tr()
                    civ.isAI() || pid.isNullOrEmpty() -> "AI".tr()
                    isOnline -> "Online".tr()
                    else -> "Offline".tr()
                }
                table.add(statusText.toLabel(fontSize = 14)).padRight(12f)
                // 回合状态 (纯文字): 战败/AI 也标注 (用户要求加两个参数)
                val turnText = when {
                    civ.isDefeated() -> "Defeated".tr()
                    civ.isAI() || pid.isNullOrEmpty() -> "AI".tr()
                    isReady -> "Done".tr()
                    isOnline -> "Thinking".tr()
                    else -> "-"
                }
                table.add(turnText.toLabel(fontSize = 14)).row()
            }
            table.invalidateHierarchy()
            table.pack()
        }

        com.unciv.utils.Concurrency.run("PlayerStatusUpdater") {
            while (popup.isVisible && worldScreen.stage != null) {
                try {
                    launchOnGLThread {
                        if (popup.isVisible && worldScreen.stage != null) {
                            buildTable()
                        }
                    }
                } catch (ignored: Exception) {
                }
                Thread.sleep(1000)
            }
        }
    }

    private class OverviewAndSupplyTable(worldScreen: WorldScreen) : Table(BaseScreen.skin) {
        val unitSupplyImage = ImageGetter.getImage("OtherIcons/ExclamationMark")
            .apply { color = Color.FIREBRICK }
        val unitSupplyCell: Cell<Actor?>

        init {
            unitSupplyImage.onClick {
                worldScreen.openEmpireOverview(EmpireOverviewCategories.Units)
            }

            val overviewButton = "Overview".toTextButton()
            overviewButton.onActivation(binding = KeyboardBinding.EmpireOverview) {
                worldScreen.openEmpireOverview()
            }

            unitSupplyCell = add()
            add(overviewButton).pad(10f)
            pack()
        }

        fun update(worldScreen: WorldScreen) {
            val newVisible = worldScreen.selectedCiv.stats.getUnitSupplyDeficit() > 0
            if (newVisible == unitSupplyCell.hasActor()) return
            if (newVisible) unitSupplyCell.setActor(unitSupplyImage)
                .size(50f).padLeft(10f)
            else unitSupplyCell.setActor(null).size(0f).pad(0f)
            invalidate()
            pack()
        }
    }

    private class SelectedCivilizationTable(worldScreen: WorldScreen) : Table(BaseScreen.skin) {
        private var selectedCiv = ""
        // Instead of allowing tr() to insert the nation icon - we don't want it scaled with fontSizeMultiplier
        private var selectedCivIcon = Group()
        private val selectedCivIconCell: Cell<Group>
        private val selectedCivLabel = "".toLabel()

        private val menuButton = ImageGetter.getImage("OtherIcons/MenuIcon")
        private val menuButtonWrapper = Container(menuButton)

        init {
            left()
            pad(10f)

            menuButton.color = Color.WHITE
            menuButton.onActivation(binding = KeyboardBinding.Menu) { WorldScreenMenuPopup(worldScreen) }
            menuButton.onRightClick { WorldScreenMenuPopup(worldScreen, true) }

            val onNationClick = {
                worldScreen.openCivilopedia(worldScreen.selectedCiv.nation.makeLink())
            }

            selectedCivLabel.setFontSize(Constants.headingFontSize)
            selectedCivLabel.onClick(onNationClick)
            selectedCivIcon.onClick(onNationClick)

            menuButtonWrapper.size(Constants.headingFontSize * 1.5f)
            menuButtonWrapper.center()
            add(menuButtonWrapper)

            selectedCivIconCell = add(selectedCivIcon).padLeft(Constants.defaultFontSize / 1.5f)
            add(selectedCivLabel).padTop(10f - Fonts.getDescenderHeight(Constants.headingFontSize))
                .padLeft(Constants.defaultFontSize / 2.0f)
            pack()
        }

        fun update(worldScreen: WorldScreen) {
            val newCiv = worldScreen.selectedCiv.civID
            if (this.selectedCiv == newCiv) return
            this.selectedCiv = newCiv

            selectedCivIcon = ImageGetter.getNationPortrait(worldScreen.selectedCiv.nation, 25f)
            selectedCivIconCell.setActor(selectedCivIcon)
            selectedCivLabel.setText(newCiv.tr(hideIcons = true))
            invalidate()
            pack()
        }
    }

    override fun act(delta: Float) = super.act(delta)
    override fun draw(batch: Batch?, parentAlpha: Float) = super.draw(batch, parentAlpha)
    override fun hit(x: Float, y: Float, touchable: Boolean): Actor? = super.hit(x, y, touchable)
}

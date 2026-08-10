package com.unciv.ui.screens.newgamescreen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.IdChecker
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.PlayerType.AI
import com.unciv.logic.civilization.PlayerType.Human
import com.unciv.logic.multiplayer.FriendList
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.setFontColor
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toCheckBox
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.components.widgets.WrappableLabel
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.multiplayerscreens.FriendPickerList
import com.unciv.ui.screens.pickerscreens.PickerPane
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.utils.isUUID
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane

/**
 * This [Table] is used to pick or edit players information for new game creation.
 * Could be inserted to [NewGameScreen], or any other [BaseScreen]
 * which provides [GameSetupInfo] and [Ruleset].
 * Upon player changes updates property [gameParameters]. Also updates available nations when mod changes.
 * @param previousScreen A [Screen][BaseScreen] where the player table is inserted, should provide [GameSetupInfo] as property, updated when a player is added/deleted/changed
 * @param gameParameters contains info about number of players.
 * @param blockWidth sets a width for the Civ "blocks". If too small a third of the stage is used.
 */
/** UncivGC 联机大厅: 成员在房间里的状态 (昵称/准备/房主), 由房间界面喂给玩家表 */
class LobbyPlayerStatus(val nickname: String, val ready: Boolean, val isOwner: Boolean, val missingMods: List<String> = emptyList())

class PlayerPickerTable(
    val previousScreen: IPreviousScreen,
    var gameParameters: GameParameters,
    blockWidth: Float = 0f,
    /** UncivGC 联机大厅模式: 不清理 playerId, 隐藏 AI/加减玩家等本地选项 */
    val lobbyMode: Boolean = false,
    /** 大厅模式: 是否允许编辑该玩家的文明 (一般只有自己) */
    val lobbyCanEdit: ((Player) -> Boolean)? = null,
    /** 大厅模式: 文明变更回调 (选完文明后触发, 用于同步服务器) */
    var onCivChanged: ((Player) -> Unit)? = null,
): Table() {
    val playerListTable = Table()
    val civBlocksWidth = if (blockWidth <= 10f) previousScreen.stage.width / 3 - 5f else blockWidth
    private var randomNumberLabel: WrappableLabel? = null

    /** Locks player table for editing, currently unused, was previously used for scenarios and could be useful in the future. */
    var locked = false

    /** No random civilization is available, potentially used in the future during map editing. */
    var noRandom = false

    /** UncivGC 大厅: 每个玩家的房间状态 (昵称/准备/房主), 由房间界面设置 */
    var lobbyGetStatus: ((Player) -> LobbyPlayerStatus?)? = null

    /** UncivGC 大厅: 房主踢人回调 (显示在别人的块上), 由房间界面设置 */
    var lobbyOnKick: ((Player) -> Unit)? = null

    /** UncivGC 大厅: 当前用户是否房主 (踢出按钮显示条件), 由房间界面设置 */
    var lobbyAmOwner: (() -> Boolean)? = null

    private val friendList = FriendList()

    init {
        if (!lobbyMode) {
            for (player in gameParameters.players)
                player.playerId = "" // This is to stop people from getting other users' IDs and cheating with them in multiplayer games
        }

        top()
        gameParameters.shufflePlayerOrder = false
        if (!lobbyMode) {
            add("Shuffle Civ Order at Start".toCheckBox(false) { gameParameters.shufflePlayerOrder = it }).padTop(5f).padBottom(5f).row()
        }
        add(ScrollPane(playerListTable).apply { setOverscroll(false, false) }).width(civBlocksWidth)
        update()
        background = BaseScreen.skinStrings.getUiBackground("NewGameScreen/PlayerPickerTable", tintColor = BaseScreen.skinStrings.skinConfig.clearColor)
    }

    /**
     * Updates view of main player table. Used when mod picked or player changed.
     * Also sets desired civilization, that is preferable for human players.
     * @param desiredCiv desired civilization name
     */
    fun update(desiredCiv: String = "") {
        playerListTable.clear()
        val gameBasics = previousScreen.ruleset // the mod picking changes this ruleset

        reassignRemovedModReferences()
        val newRulesetPlayableCivs = previousScreen.ruleset.nations
            .count { it.key != Constants.barbarians && !it.value.hasUnique(UniqueType.WillNotBeChosenForNewGames) }
        if (gameParameters.players.size > newRulesetPlayableCivs)
            gameParameters.players = ArrayList(gameParameters.players.subList(0, newRulesetPlayableCivs))
        if (desiredCiv.isNotEmpty()) assignDesiredCiv(desiredCiv)

        for (player in gameParameters.players) {
            val cell = playerListTable.add(getPlayerTable(player)).width(civBlocksWidth).padBottom(20f)
            // UncivGC 大厅: 块强制撑满列宽, 避免内容宽度不同导致块与块不对齐
            if (lobbyMode) cell.fillX()
            cell.row()
        }

        val isRandomNumberOfPlayers = gameParameters.randomNumberOfPlayers
        if (isRandomNumberOfPlayers) {
            randomNumberLabel = WrappableLabel("", civBlocksWidth - 20f, Color.GOLD)
            playerListTable.add(randomNumberLabel).fillX().pad(0f, 10f, 20f, 10f).row()
            updateRandomNumberLabel()
        }

        if (!lobbyMode && !locked && gameParameters.players.size < gameBasics.nations.values.count { it.isMajorCiv }) {
            val addPlayerButton = "+".toLabel(ImageGetter.CHARCOAL, 30)
                .apply { this.setAlignment(Align.center) }
                .surroundWithCircle(50f)
                .onClick {
                    // no random mode - add first not spectator civ if still available
                    val player = if (noRandom || isRandomNumberOfPlayers) {
                        val availableCiv = getAvailablePlayerCivs().firstOrNull()
                        if (availableCiv != null) Player(availableCiv)
                        // Spectators can only be Humans
                        else Player(Constants.spectator, PlayerType.Human).apply { setNationTransient(gameBasics) }
                    } else Player()  // normal: add random AI
                    gameParameters.players.add(player)
                    update()
                }
            playerListTable.add(addPlayerButton).pad(10f)
        }

        // UncivGC 大厅: 加号只允许房主添加 AI 电脑 (不能加玩家, 玩家由房间成员管理)
        if (lobbyMode && !locked && lobbyAmOwner?.invoke() == true
            && gameParameters.players.size < gameBasics.nations.values.count { it.isMajorCiv }) {
            val addAiButton = "+".toLabel(ImageGetter.CHARCOAL, 30)
                .apply { this.setAlignment(Align.center) }
                .surroundWithCircle(50f)
                .onClick {
                    val availableCiv = getAvailablePlayerCivs().firstOrNull()
                    val player = if (noRandom || isRandomNumberOfPlayers) {
                        if (availableCiv != null) Player(availableCiv) else null
                    } else Player()  // 随机文明 AI
                    if (player != null) {
                        player.playerType = PlayerType.AI
                        gameParameters.players.add(player)
                        update()
                        // 设置自动推送 (aiPlayers 变化由 LobbySettingsSync 检测)
                    }
                }
            playerListTable.add(addAiButton).pad(10f)
        }

        // enable start game when at least one human player and they're not alone
        val humanPlayerCount = gameParameters.players.count { it.playerType == PlayerType.Human }
        val isValid = humanPlayerCount >= 1 && (isRandomNumberOfPlayers || gameParameters.players.size >= 2)
        (previousScreen as? PickerScreen)?.setRightSideButtonEnabled(isValid)
    }

    fun updateRandomNumberLabel() {
        randomNumberLabel?.run {
            val playerRange = if (gameParameters.minNumberOfPlayers == gameParameters.maxNumberOfPlayers) {
                gameParameters.minNumberOfPlayers.tr()
            } else {
                "${gameParameters.minNumberOfPlayers} - ${gameParameters.maxNumberOfPlayers}"
            }
            val numberOfExplicitPlayersText = if (gameParameters.players.size == 1) {
                "The number of players will be adjusted"
            } else {
                "These [${gameParameters.players.size}] players will be adjusted"
            }
            val text = "[$numberOfExplicitPlayersText] to [$playerRange] actual players by adding random AI's or by randomly omitting AI's."
            wrap = false
            align(Align.center)
            setText(text.tr())
            wrap = true
        }
    }

    /**
     * Reassigns removed mod references to random civilization
     */
    private fun reassignRemovedModReferences() {
        for (player in gameParameters.players) {
            if (!previousScreen.ruleset.nations.containsKey(player.chosenCiv)
                || previousScreen.ruleset.nations[player.chosenCiv]!!.isCityState
                || previousScreen.ruleset.nations[player.chosenCiv]!!.hasUnique(UniqueType.WillNotBeChosenForNewGames))
                player.chosenCiv = Constants.random
        }
    }

    /**
     * Assigns desired civilization for human players with 'random' choice
     * @param desiredCiv string containing desired civilization name
     */
    private fun assignDesiredCiv(desiredCiv: String) {
        // No auto-select if desiredCiv already used
        if (gameParameters.players.any { it.chosenCiv == desiredCiv }) return
        // Do auto-select, silently no-op if no suitable slot (human with 'random' choice)
        val player = gameParameters.players.firstOrNull { it.chosenCiv == Constants.random && it.playerType == Human }
        player?.chosenCiv = desiredCiv
        player?.setNationTransient(previousScreen.ruleset)
    }

    /**
     * Creates [Table] for single player containing clickable
     * player type button ("AI" or "Human"), nation [Table]
     * and "-" remove player button.*
     * @param player for which [Table] is generated
     * @return [Table] containing the all the elements
     */
    private fun getPlayerTable(player: Player): Table {
        if (lobbyMode) return buildLobbyPlayerBlock(player)

        val playerTable = Table()
        playerTable.pad(5f)
        playerTable.background = BaseScreen.skinStrings.getUiBackground(
            "NewGameScreen/PlayerPickerTable/PlayerTable",
            tintColor = BaseScreen.skinStrings.skinConfig.baseColor.darken(0.8f)
        )

        val nationTable = getNationTable(player)
        playerTable.add(nationTable).left()

        val playerTypeTextButton: TextButton? = if (!lobbyMode) {
            val btn = player.playerType.name.toTextButton()
            playerTable.add(btn).width(100f).pad(5f).right()
            btn.onClick {
                player.playerType = if (player.playerType == AI) Human else AI
                update()
            }
            btn
        } else null

        fun updatePlayerTypeButtonEnabled() {
            playerTypeTextButton?.isEnabled = when {
                // Can always change AI to Human
                player.playerType == PlayerType.AI -> true
                // we cannot change Spectator player to AI type, robots not allowed to spectate :(
                player.chosenCiv == Constants.spectator -> false
                // In randomNumberOfPlayers mode, don't let the user choose random AI's
                gameParameters.randomNumberOfPlayers && player.chosenCiv == Constants.random -> false
                else -> true
            }
        }
        updatePlayerTypeButtonEnabled()

        nationTable.onClick {
            if (locked) return@onClick
            if (lobbyCanEdit?.invoke(player) == false) return@onClick
            val noRandom = noRandom ||
                    gameParameters.randomNumberOfPlayers && player.playerType == PlayerType.AI
            popupNationPicker(player, noRandom)
            updatePlayerTypeButtonEnabled()
        }

        if (!lobbyMode) {
            playerTable.add("-".toLabel(ImageGetter.CHARCOAL, 30, Align.center)
                .surroundWithCircle(40f)
                .onClick {
                    gameParameters.players.remove(player)
                    update()
                }
            ).pad(5f).right()
        }

        if (gameParameters.isOnlineMultiplayer && player.playerType == PlayerType.Human)
            playerTable.addPlayerTableMultiplayerControls(player)

        return playerTable
    }

    /** UncivGC 大厅模式: 成员块 (行1 原版图标+文明+Human标签 / 行2 玩家昵称+踢出 / 行3 准备状态) */
    private fun buildLobbyPlayerBlock(player: Player): Table {
        val block = Table()
        block.pad(5f)
        val status = lobbyGetStatus?.invoke(player)
        val canEdit = lobbyCanEdit?.invoke(player) == true
        // 自身高亮: 自己的块背景更亮, 方便区分
        val tint = if (canEdit) BaseScreen.skinStrings.skinConfig.baseColor.darken(0.35f)
                   else BaseScreen.skinStrings.skinConfig.baseColor.darken(0.8f)
        block.background = BaseScreen.skinStrings.getUiBackground(
            "NewGameScreen/PlayerPickerTable/PlayerTable",
            tintColor = tint
        )
        block.defaults().pad(3f)

        // 行1: 原版文明区 (图标+文明名, 真实图片) + 玩家类型按钮 (成员=Human 禁点; AI电脑=移除按钮)
        val isAI = player.playerType == PlayerType.AI && player.playerId.isEmpty()
        val amOwner = lobbyAmOwner?.invoke() == true
        val nationTable = getNationTable(player)
        nationTable.onClick {
            if (locked) return@onClick
            // 自己可以选文明; 房主可以替 AI 选文明
            if (!canEdit && !(isAI && amOwner)) return@onClick
            popupNationPicker(player, noRandom)
        }
        block.add(nationTable).left()
        // 弹性占位: 右侧按钮永远贴块右边缘 (否则按钮位置随文明名长度漂移)
        block.add("".toLabel()).expandX()
        if (isAI) {
            // AI 电脑: 移除按钮 (房主可删)
            val removeButton = "-".toLabel(ImageGetter.CHARCOAL, 30)
                .apply { this.setAlignment(Align.center) }
                .surroundWithCircle(50f)
                .onClick {
                    gameParameters.players.remove(player)
                    update()
                    // 设置自动推送 (aiPlayers 变化由 LobbySettingsSync 检测)
                }
            block.add(removeButton).pad(5f).row()
        } else {
            // 原版同款按钮, 但不挂任何点击行为 (大厅里全是人类玩家, 不需要切换)
            val playerTypeButton = player.playerType.name.toTextButton()
            block.add(playerTypeButton).width(100f).pad(5f).right().row()
        }

        // 行2: 玩家昵称 (纯显示) + 踢出 (仅房主看别人的块, 永远贴右对齐)
        val nickRow = Table()
        nickRow.defaults().padRight(6f)
        val displayName = when {
            isAI -> "AI 电脑"
            status?.nickname?.takeIf { it.isNotEmpty() } != null -> status!!.nickname
            else -> player.playerId.take(8)
        }
        nickRow.add(("玩家昵称: $displayName" + if (status?.isOwner == true) "（房主）" else "").toLabel())
        nickRow.add("".toLabel()).expandX()
        // 踢出只对真人成员显示 (AI 有自己的减号移除按钮)
        if (amOwner && !canEdit && !isAI) {
            val kickButton = "踢出".toTextButton()
            kickButton.onClick { lobbyOnKick?.invoke(player) }
            nickRow.add(kickButton)
        }
        block.add(nickRow).fillX().row()

        // 行3: 准备状态 (+ 缺模组警告) — AI 不显示 (行2 已有 "AI 电脑"); 不用 emoji, 用文字+颜色; 长文字换行防溢出
        val missingMods = status?.missingMods.orEmpty()
        if (!isAI) {
            val statusLabel = when {
                missingMods.isNotEmpty() -> "缺模组: ${missingMods.joinToString("、")}".toLabel().apply { setFontColor(Color.RED) }
                status?.ready == true -> "已准备".toLabel().apply { setFontColor(Color.GREEN) }
                else -> "未准备".toLabel().apply { setFontColor(Color.RED) }
            }
            statusLabel.wrap = true
            block.add(statusLabel).left().fillX().row()
        }

        return block
    }

    private fun Table.addPlayerTableMultiplayerControls(player: Player) {
        row()

        val playerIdTextField = UncivTextField("Please input Player ID!", player.playerId)
        add(playerIdTextField).colspan(2).fillX().pad(5f)
        val errorLabel = "✘".toLabel(Color.RED)
        add(errorLabel).pad(5f).row()

        fun onPlayerIdTextUpdated() {
            val friend = IdChecker.checkAndReturnPlayerUuid(playerIdTextField.text)
            if (friend?.playerID?.isUUID() ?: false) {
                player.playerId = playerIdTextField.text.trim()
                errorLabel.apply {
                    setText("✔") // U+2714 heavy checkmark
                    setFontColor(Color.GREEN)
                }
            } else {
                errorLabel.apply {
                    setText("✘") // U+2718 heavy ballot x
                    setFontColor(Color.RED)
                }
            }
        }
        onPlayerIdTextUpdated()
        playerIdTextField.addListener { onPlayerIdTextUpdated(); true }

        val currentUserId = UncivGame.Current.settings.multiplayer.getUserId()
        val setCurrentUserButton = "Set current user".toTextButton()
        setCurrentUserButton.onClick {
            playerIdTextField.text = currentUserId
            onPlayerIdTextUpdated()
        }
        add(setCurrentUserButton).colspan(3).fillX().pad(5f).row()

        val copyFromClipboardButton = "Player ID from clipboard".toTextButton()
        copyFromClipboardButton.onClick {
            playerIdTextField.text = Gdx.app.clipboard.contents
            onPlayerIdTextUpdated()
        }
        add(copyFromClipboardButton).right().colspan(3).fillX().pad(5f).row()

        //check if friends list is empty before adding the select friend button
        if (friendList.listOfFriends.isNotEmpty()) {
            val selectPlayerFromFriendsList = "Player ID from friends list".toTextButton()
            selectPlayerFromFriendsList.onClick {
                popupFriendPicker(player)
            }
            add(selectPlayerFromFriendsList).left().colspan(3).fillX().pad(5f)
        }
    }

    /**
     * Creates clickable icon and nation name for some [Player].
     * @param player [Player] for which generated
     * @return [Table] containing nation icon and name
     */
    private fun getNationTable(player: Player): Table {
        val nationTable = Table()
        val nationImageName = previousScreen.ruleset.nations[player.chosenCiv]
        val nationImage =
            if (nationImageName == null)
                ImageGetter.getRandomNationPortrait(40f)
            else ImageGetter.getNationPortrait(nationImageName, 40f)
        nationTable.add(nationImage).pad(5f)
        nationTable.add(player.chosenCiv.toLabel(hideIcons = true)).pad(5f)
        nationTable.touchable = Touchable.enabled
        return nationTable
    }

    /**
     * Opens Friend picking popup with all friends,
     * currently available for [player] to choose, depending on current
     * friends list and if another friend is selected.
     * @param player current player
     */
    private fun popupFriendPicker(player: Player) {
        FriendSelectionPopup(this, player, previousScreen as BaseScreen).open()
        update()
    }

    /**
     * Opens Nation picking popup with all nations,
     * currently available for [player] to choose, depending on current
     * ruleset and other players nation choice.
     * @param player current player
     */
    private fun popupNationPicker(player: Player, noRandom: Boolean) {
        NationPickerPopup(this, player, noRandom).open()
        update()
    }

    /**
     * Returns a list of available civilization for all players, according
     * to current ruleset, with exception of city states nations, spectator and barbarians.
     *
     * Skips nations already chosen by a player, unless parameter [dontSkipNation] says to keep a
     * specific one. That is used so the picker can be used to inspect and confirm the current selection.
     *
     * @return [Sequence] of available [Nation]s
     */
    internal fun getAvailablePlayerCivs(dontSkipNation: String? = null) =
        previousScreen.ruleset.nations.values.asSequence()
            .filter { it.isMajorCiv }
            .filterNot { it.hasUnique(UniqueType.WillNotBeChosenForNewGames) }
            .filter { it.name == dontSkipNation || gameParameters.players.none { player -> player.chosenCiv == it.name } }

    /**
     * Returns a list of available friends.
     * Skips friends already chosen.
     *
     * @return [Sequence] of available [FriendList.Friend]s
     */
    internal fun getAvailableFriends(): Sequence<FriendList.Friend> {
        val friendListWithRemovedFriends = friendList.listOfFriends.toMutableList()
        for (index in gameParameters.players.indices) {
            val currentFriendId = previousScreen.gameSetupInfo.gameParameters.players[index].playerId
            friendListWithRemovedFriends.remove(friendList.getFriendById(currentFriendId))
        }
        return friendListWithRemovedFriends.asSequence()
    }
}

class FriendSelectionPopup(
    private val playerPicker: PlayerPickerTable,
    player: Player,
    screen: BaseScreen,
) : Popup(screen) {

    private val pickerPane = PickerPane()
    private var selectedFriendId: String? = null

    init {
        val pickerCell = add()
            .width(700f).fillX().expandX()
            .minHeight(screen.stage.height * 0.5f)
            .maxHeight(screen.stage.height * 0.8f)

        val friendList = FriendPickerList(playerPicker, ::friendSelected)
        pickerPane.topTable.add(friendList)
        pickerPane.rightSideButton.setText("Select friend".tr())
        pickerPane.closeButton.onActivation(::close)
        pickerPane.closeButton.keyShortcuts.add(KeyCharAndCode.BACK)
        pickerCell.setActor<PickerPane>(pickerPane)
        pickerPane.rightSideButton.onClick {
            close()
            val friendId = selectedFriendId
            if (friendId != null) {
                player.playerId = selectedFriendId.toString()
                close()
                playerPicker.update()
            }
        }

        clickBehindToClose = true
    }

    private fun friendSelected(friendName: String) {
        val friendsList = FriendList()
        val friend = friendsList.getFriendByName(friendName)
        if (friend != null) {
            selectedFriendId = friend.playerID
        }
        pickerPane.setRightSideButtonEnabled(true)
        pickerPane.rightSideButton.setText("Select [$friendName]".tr())
    }

}

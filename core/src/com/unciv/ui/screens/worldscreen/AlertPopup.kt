package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.battle.BattleUnitCapture
import com.unciv.logic.city.City
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.CivilopediaAction
import com.unciv.logic.civilization.DiplomacyAction
import com.unciv.logic.civilization.LocationAction
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.*
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.fillPlaceholders
import com.unciv.models.translations.tr
import com.unciv.ui.audio.MusicMood
import com.unciv.ui.audio.MusicTrackChooserFlags
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.pad
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.diplomacyscreen.LeaderIntroTable
import com.unciv.ui.screens.victoryscreen.VictoryScreen
import yairm210.purity.annotations.Readonly
import java.util.EnumSet
import kotlin.text.ifEmpty

/**
 * [Popup] communicating events other than trade offers to the player.
 * (e.g. First Contact, Wonder built, Tech researched,...)
 *
 * **Opens itself at the end of instantiation!**
 *
 * (In rare cases, it chooses not to: Mods making a RecapturedCivilian not find the unit as it was illegal and removed after the actual capture)
 *
 * Called in [WorldScreen].update, which pulls them from viewingCiv.popupAlerts.
 *
 * @param worldScreen The parent screen
 * @param popupAlert The [PopupAlert] entry to present
 *
 * @see AlertType
 *
 * Attention developers: This is a Popup with `Scrollability.WithoutButtons`, and that means the
 * content area has two parts - one scrolls and the bottom not. Use Popup's normal `add` for stuff that
 * should go to the upper scrolling part and all typical closing buttons should *only* use Popup's
 * add*Button methods - for a good exception see `addCityConquered`.
 * That also means colspan is independent for top and bottom, and you need no row() between them.
 */
class AlertPopup(
    private val worldScreen: WorldScreen,
    private val popupAlert: PopupAlert
): Popup(worldScreen) {
    
    companion object {
        private const val SEPARATOR_LINE_TO_TEXT_PADDING = 25f
        private val LIGHTER_RED_COLOR = Color(1f, 1/3f, 1/3f, 1f)
        private val LIGHTER_GREEN_COLOR = Color(1/3f, 1f, 1/3f, 1f)
        private val LIGHTER_ORANGE_COLOR = Color(1f, 2/5f, 0f, 1f)
    }

    //region convenience getters
    private val music get() = UncivGame.Current.musicController
    private val gameInfo get() = worldScreen.gameInfo
    private val viewingCiv get() = worldScreen.viewingCiv
    private val stageWidth get() = worldScreen.stage.width
    private val stageHeight get() = worldScreen.stage.height
    @Readonly private fun getCiv(civName: String) = gameInfo.getCivilization(civName)
    @Readonly private fun getCity(cityId: String) = gameInfo.getCities().first { it.id == cityId }
    //endregion

    // This redirects all addCloseButton uses with only text and no action to accept the space key
    private fun addCloseButton(text: String = Constants.close) =
        addCloseButton(text, KeyboardBinding.NextTurnAlternate, null)

    init {
        var shouldOpen = true

        // This makes the buttons fill up available width. See comments in #9559.
        // To implement a middle ground, I would either simply replace growX() with minWidth(240f) or so,
        // or replace the Popup.equalizeLastTwoButtonWidths() function with something intelligent not
        // limited to two buttons.
        bottomTable.defaults().growX()

        when (popupAlert.type) {
            // Cities
            AlertType.CityConquered -> addCityConquered()
            AlertType.CityTraded -> addCityTraded()
            AlertType.DiplomaticMarriage -> addDiplomaticMarriage()
            // Demands and diplomacy
            AlertType.FirstContact -> addFirstContact()
            AlertType.WarDeclaration -> shouldOpen = addWarDeclaration()
            AlertType.BorderConflict -> shouldOpen = addBorderConflict()
            AlertType.TilesStolen -> shouldOpen = addTilesStolen()
            AlertType.Denounced -> shouldOpen = addDenouncement()
            
            // demands
            AlertType.DemandToStopSettlingCitiesNear -> shouldOpen = addDemand(Demand.DoNotSettleNearUs)
            AlertType.CitySettledNearOtherCivDespiteOurPromise -> shouldOpen = addDemandViolationNoticed(Demand.DoNotSettleNearUs)
            AlertType.DemandToStopSpreadingReligion -> shouldOpen = addDemand(Demand.DoNotSpreadReligion)
            AlertType.ReligionSpreadDespiteOurPromise -> shouldOpen = addDemandViolationNoticed(Demand.DoNotSpreadReligion)
            AlertType.DemandToStopSpyingOnUs -> shouldOpen = addDemand(Demand.DontSpyOnUs)
            AlertType.SpyingOnUsDespiteOurPromise -> shouldOpen = addDemand(Demand.DontSpyOnUs)
            AlertType.DemandToNotAttackUs -> shouldOpen = addDemand(Demand.DoNotAttackUs)
            AlertType.AttackedUsDespitePromise -> shouldOpen = addDemandViolationNoticed(Demand.DoNotAttackUs)
            AlertType.AcceptingDemand -> shouldOpen = addAcceptingDemand()
            AlertType.RejectingDemand -> shouldOpen = addRejectingDemand()
            
            AlertType.DeclarationOfFriendship -> shouldOpen = addDeclarationOfFriendship()
            AlertType.TradeRouteOffer -> shouldOpen = addTradeRouteOffer()
            AlertType.BulliedProtectedMinor, AlertType.AttackedProtectedMinor, AlertType.AttackedAllyMinor -> 
                shouldOpen = addBulliedOrAttackedProtectedOrAlliedMinor()
            AlertType.Defeated -> addDefeated()
            // We did stuff
            AlertType.WonderBuilt -> addWonderBuilt()
            AlertType.TechResearched -> addTechResearched()
            AlertType.GoldenAge -> addGoldenAge()
            AlertType.StartIntro -> addStartIntro()
            AlertType.RecapturedCivilian -> shouldOpen = addRecapturedCivilian()
            AlertType.GameHasBeenWon -> addGameHasBeenWon()
            AlertType.Event -> shouldOpen = addEvent()
        }
        if (shouldOpen) open()
        else viewingCiv.popupAlerts.remove(popupAlert)
    }

    //region AlertType handlers

    private fun addBorderConflict(): Boolean {
        val civInfo = getCiv(popupAlert.value)
        if (civInfo.isDefeated()) return false
        addLeaderName(civInfo)
        addGoodSizedLabel("Remove your troops in our border immediately!")
        addCloseButton("Sorry.", KeyboardBinding.Confirm)
        addCloseButton("Never!", KeyboardBinding.Cancel)
        return true
    }
    
    private fun addTilesStolen(): Boolean {
        val civInfo = getCiv(popupAlert.value)
        if (civInfo.isDefeated()) return false
        addLeaderName(civInfo)
        addGoodSizedLabel("Those lands were not yours to take. This has not gone unnoticed.")
        addCloseButton()
        return true
    }

    private fun addBulliedOrAttackedProtectedOrAlliedMinor(): Boolean {
        val involvedCivs = popupAlert.value.split('@')
        val bullyOrAttacker = getCiv(involvedCivs[0])
        if (bullyOrAttacker.isDefeated()) return false
        val cityState = getCiv(involvedCivs[1])
        val player = viewingCiv
        addLeaderName(bullyOrAttacker)

        val isAtLeastNeutral = bullyOrAttacker.getDiplomacyManager(player)!!.isRelationshipLevelGE(RelationshipLevel.Neutral)
        val text = when {
            popupAlert.type == AlertType.BulliedProtectedMinor && isAtLeastNeutral ->  // Nice message
                "I've been informed that my armies have taken tribute from [${cityState.civName}], a city-state under your protection.\nI assure you, this was quite unintentional, and I hope that this does not serve to drive us apart."
            popupAlert.type == AlertType.BulliedProtectedMinor ->  // Nasty message
                "We asked [${cityState.civName}] for a tribute recently and they gave in.\nYou promised to protect them from such things, but we both know you cannot back that up."
            isAtLeastNeutral ->  // Nice message
                "It's come to my attention that I may have attacked [${cityState.civName}].\nWhile it was not my goal to be at odds with your empire, this was deemed a necessary course of action."
            else ->  // Nasty message
                "I thought you might like to know that I've launched an invasion of one of your little pet states.\nThe lands of [${cityState.civName}] will make a fine addition to my own."
        }
        addGoodSizedLabel(text).row()
        
        if (!player.isAtWarWith(bullyOrAttacker)) {
            addCloseButton("THIS MEANS WAR!", KeyboardBinding.Confirm) {
            player.getDiplomacyManager(bullyOrAttacker)!!.sideWithCityState()
            val warReason = if (popupAlert.type == AlertType.AttackedAllyMinor) WarType.AlliedCityStateWar else WarType.ProtectedCityStateWar
            player.getDiplomacyManager(bullyOrAttacker)!!.declareWar(DeclareWarReason(warReason, cityState))
            cityState.getDiplomacyManager(player)!!.influence += 20f // You went to war for us!!
        }.row()}

        addCloseButton("You'll pay for this!", KeyboardBinding.Confirm) {
            player.getDiplomacyManager(bullyOrAttacker)!!.sideWithCityState()
        }.row()

        addCloseButton("Very well.", KeyboardBinding.Cancel) {
            player.addNotification("You have broken your Pledge to Protect [${cityState.civName}]!",
                cityState.cityStateFunctions.getNotificationActions(), NotificationCategory.Diplomacy, cityState.civName)
            cityState.cityStateFunctions.removeProtectorCiv(player, forced = true)
        }.row()
        
        return true
    }

    private fun addCityConquered() {
        val city = getCity(popupAlert.value)
        addQuestionAboutTheCity(city.name)
        // UncivGC 帧同步: 弹窗属于观看文明 (同时回合下 currentPlayer 不一定是自己); 决策由服务器权威执行
        val conqueringCiv = viewingCiv
        if (com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(city.civ.gameInfo)) {
            addFsConquerChoiceButtons(city, conqueringCiv)
            return
        }

        if (city.foundingCivObject != null
                && city.civ != city.foundingCivObject // can't liberate if the city actually belongs to those guys
                && conqueringCiv != city.foundingCivObject) { // or belongs originally to us
            addLiberateOption(city, conqueringCiv)
            addSeparator()
        }

        if (conqueringCiv.isOneCityChallenger()) {
            addDestroyOption {
                city.puppetCity(conqueringCiv)
                city.destroyCity()
            }
        } else {
            val mayAnnex = !conqueringCiv.hasUnique(UniqueType.MayNotAnnexCities)
            addAnnexOption(city, mayAnnex = mayAnnex) {
                city.puppetCity(conqueringCiv)
            }
            addSeparator()

            addPuppetOption(mayAnnex = mayAnnex) {
                city.puppetCity(conqueringCiv)
            }
            addSeparator()

            addRazeOption(city, mayAnnex = mayAnnex, conqueringCiv)
        }
    }

    /** UncivGC 帧同步: 占领城市决策按钮 — 只发 op, 本地不执行 (服务器 doCityConquerChoice 权威执行) */
    private fun addFsConquerChoiceButtons(city: com.unciv.logic.city.City, conqueringCiv: Civilization) {
        fun choiceButton(text: String, action: String): com.badlogic.gdx.scenes.scene2d.ui.TextButton {
            val button = text.toTextButton()
            button.onActivation {
                com.unciv.ui.screens.worldscreen.FrameSync.sendCityConquerChoice(city.id, action)
                close()
            }
            return button
        }
        if (city.foundingCivObject != null
                && city.civ != city.foundingCivObject
                && conqueringCiv != city.foundingCivObject) {
            add(choiceButton("Liberate (city returns to [${city.foundingCivObject!!.civName}])", "liberate")).row()
            addSeparator()
        }
        if (conqueringCiv.isOneCityChallenger()) {
            add(choiceButton("Destroy", "destroy")).row()
        } else {
            val mayAnnex = !conqueringCiv.hasUnique(UniqueType.MayNotAnnexCities)
            add(choiceButton("Annex", "annex")).row()
            addSeparator()
            add(choiceButton("Puppet", "puppet")).row()
            addSeparator()
            val canRaze = city.canBeDestroyed(justCaptured = true)
            if (canRaze && mayAnnex)
                add(choiceButton("Raze", "raze")).row()
        }
        // 不提供 Close: 必须做出选择 (与单机一致; 放弃选择会导致城市永久待决)
    }

    private fun addDemandViolationNoticed(demand: Demand): Boolean {
        val otherciv = getCiv(popupAlert.value)
        if (otherciv.isDefeated()) return false
        addLeaderName(otherciv)
        addGoodSizedLabel(demand.violationNoticedText).row()
        addCloseButton("Very well.")
        return true
    }

    private fun addCityTraded() {
        val city = getCity(popupAlert.value)
        addQuestionAboutTheCity(city.name)
        // UncivGC 帧同步: 弹窗属于观看文明 (同时回合下 currentPlayer 不一定是自己)
        val conqueringCiv = viewingCiv

        if (!conqueringCiv.isAtWarWith(city.foundingCivObject!!)) {
            addLiberateOption(city, conqueringCiv)
            addSeparator()
        }
        addCloseButton("Keep it").row()
    }

    /** UncivGC 2026-08-26 商路 v2: 玩家之间商路请求 — value = "fromCityId|toCityId"
     *  文案: 文明 的 城市1 向我们的城市 城市2 发起了贸易邀请，我们将获得 stat (不带【】, 2026-08-26 用户要求) */
    private fun addTradeRouteOffer(): Boolean {
        val parts = popupAlert.value.split("|")
        if (parts.size < 2) return false
        val fromCity = gameInfo.getCities().firstOrNull { it.id == parts[0] } ?: return false
        val toCity = gameInfo.getCities().firstOrNull { it.id == parts[1] } ?: return false
        val fromCiv = fromCity.civ
        addLeaderName(fromCiv)
        addTopicHeader("贸易邀请", com.badlogic.gdx.graphics.Color.GOLD)
        val stats = com.unciv.logic.trade.TradeRoutes.receiverStats(fromCity,
            com.unciv.logic.trade.TradeRouteNetwork.Route(toCity, 0, false, false))
        val statText = com.unciv.models.stats.Stat.entries
            .filter { stats[it] != 0f }
            .joinToString(" ") { "${stats[it]} ${it.name.tr()}" }
        val text = "${fromCiv.civName.tr()} 的 ${fromCity.name.tr()} 向我们的城市 ${toCity.name.tr()} 发起了贸易邀请" +
                (if (statText.isNotEmpty()) "，我们将获得 $statText" else "")
        addGoodSizedLabel(text).row()
        addCloseButton("接受", KeyboardBinding.Confirm) {
            if (FrameSync.isFsMode(viewingCiv.gameInfo)) {
                FrameSync.sendTradeRouteAcceptOffer(fromCity.id)
            } else {
                // 单机: 本地直接建立
                val list = viewingCiv.gameInfo.tradeRoutes.getOrPut(fromCity.id) { ArrayList() }
                if (!list.contains(toCity.id)) list.add(toCity.id)
                viewingCiv.gameInfo.tradeRouteOffers[fromCity.id]?.remove(toCity.id)
                viewingCiv.gameInfo.tradeRouteOffers[fromCity.id]?.takeIf { it.isEmpty() }?.let {
                    viewingCiv.gameInfo.tradeRouteOffers.remove(fromCity.id)
                }
                viewingCiv.gameInfo.invalidateTradeRoutes()
                try { toCity.cityStats.update() } catch (ignored: Exception) {}
                try { fromCity.cityStats.update() } catch (ignored: Exception) {}
            }
            viewingCiv.popupAlerts.remove(popupAlert)
        }.row()
        addCloseButton("拒绝", KeyboardBinding.Cancel) {
            if (FrameSync.isFsMode(viewingCiv.gameInfo)) {
                FrameSync.sendTradeRouteRejectOffer(fromCity.id)
            } else {
                viewingCiv.gameInfo.tradeRouteOffers[fromCity.id]?.remove(toCity.id)
                viewingCiv.gameInfo.tradeRouteOffers[fromCity.id]?.takeIf { it.isEmpty() }?.let {
                    viewingCiv.gameInfo.tradeRouteOffers.remove(fromCity.id)
                }
                viewingCiv.gameInfo.invalidateTradeRoutes()
                try { toCity.cityStats.update() } catch (ignored: Exception) {}
                try { fromCity.cityStats.update() } catch (ignored: Exception) {}
                // 2026-08-26 用户要求: 通知发起方 (单机热座, 文案不带【】)
                fromCity.civ.addNotification(
                    "${viewingCiv.civName.tr()} 拒绝了你发起的从 ${fromCity.name.tr()} 到 ${toCity.name.tr()} 的贸易路线请求",
                    com.unciv.logic.civilization.NotificationCategory.Trade,
                    com.unciv.logic.civilization.NotificationIcon.Trade)
            }
            viewingCiv.popupAlerts.remove(popupAlert)
        }.row()
        return true
    }

    private fun addDeclarationOfFriendship(): Boolean {
        val otherciv = getCiv(popupAlert.value)
        if (otherciv.isDefeated() || otherciv.getDiplomacyManager(viewingCiv)!!.diplomaticStatus == DiplomaticStatus.War) return false
        val playerDiploManager = viewingCiv.getDiplomacyManager(otherciv)!!
        addLeaderName(otherciv)
        addTopicHeader("DECLARATION OF FRIENDSHIP", LIGHTER_GREEN_COLOR)
        addGoodSizedLabel(
                if (otherciv.nation.declaringFriendship.isNotEmpty()) otherciv.nation.declaringFriendship else "My friend, shall we declare our friendship to the world?"
        ).row()
        addCloseButton("Declare Friendship ([30] turns)", KeyboardBinding.Confirm) {
            if (FrameSync.isFsMode(viewingCiv.gameInfo)) {
                // 帧同步: 服务器权威接受 (flag 由状态广播同步)
                FrameSync.sendFriendshipAccept(popupAlert.value)
                viewingCiv.popupAlerts.remove(popupAlert)
            } else {
                playerDiploManager.signDeclarationOfFriendship()
            }
        }.row()
        addCloseButton("We are not interested.", KeyboardBinding.Cancel) {
            if (FrameSync.isFsMode(viewingCiv.gameInfo)) {
                FrameSync.sendFriendshipDecline(popupAlert.value)
                viewingCiv.popupAlerts.remove(popupAlert)
            } else {
                playerDiploManager.otherCivDiplomacy().setFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship, 20)
            }
        }.row()
        val music = UncivGame.Current.musicController
        music.playVoice("${otherciv.nation.name}.declaringFriendship")
        return true
    }

    private fun addDenouncement(): Boolean {
        val denouncer = getCiv(popupAlert.value)
        if (denouncer.isDefeated())
            return false
        addLeaderName(denouncer)
        addTopicHeader("DENOUNCEMENT", LIGHTER_ORANGE_COLOR)
        // normal message unless we are enemies
        val leaderMessage = if (denouncer.getDiplomacyManager(viewingCiv)!!.isRelationshipLevelGE(RelationshipLevel.Competitor)) {
            music.playVoice("${denouncer.nation.name}.neutralDenouncing")
            denouncer.nation.neutralDenouncing.ifEmpty { "You have violated our bond of trust. This is intolerable!" }
        } else {
            music.playVoice("${denouncer.nation.name}.hateDenouncing")
            denouncer.nation.hateDenouncing.ifEmpty { "You are a scourge upon this earth. I denounce you!" }
        }
        addGoodSizedLabel(leaderMessage).row()
        val diplomacy = viewingCiv.getDiplomacyManager(denouncer)!!
        if (diplomacy.canDeclareWar()) {
            addCloseButton("THIS MEANS WAR! (Declare war)") {
                if (FrameSync.isFsMode(viewingCiv.gameInfo)) {
                    // 帧同步: 服务器权威宣战
                    FrameSync.sendDeclareWar(popupAlert.value)
                } else {
                    diplomacy.declareWar()
                }
            }.row()
        }
        addCloseButton("Very well.", KeyboardBinding.Cancel).row()
        return true
    }
    
    private fun addDefeated() {
        val civInfo = getCiv(popupAlert.value)
        addLeaderName(civInfo)
        addGoodSizedLabel(civInfo.nation.defeated).row()
        addCloseButton("Farewell.")
        music.chooseTrack(civInfo.civName, MusicMood.Defeat, EnumSet.of(MusicTrackChooserFlags.SuffixMustMatch))
        music.playVoice("${civInfo.civName}.defeated")
    }

    private fun addDemand(demand: Demand): Boolean {
        val otherciv = getCiv(popupAlert.value)
        if (otherciv.isDefeated()) return false
        
        val playerDiploManager = viewingCiv.getDiplomacyManager(otherciv)!!
        addLeaderName(otherciv)
        addGoodSizedLabel(demand.demandText).row()
        addCloseButton(demand.acceptDemandText, KeyboardBinding.Confirm) {
            if (FrameSync.isFsMode(viewingCiv.gameInfo)) {
                // 帧同步: 服务器权威接受 (flag 由回合末存档对齐)
                FrameSync.sendDemandAccept(popupAlert.value, demand.name)
                viewingCiv.popupAlerts.remove(popupAlert)
            } else {
                playerDiploManager.agreeToDemand(demand)
            }
        }.row()
        addCloseButton(demand.refuseDemandText, KeyboardBinding.Cancel) {
            if (FrameSync.isFsMode(viewingCiv.gameInfo)) {
                FrameSync.sendDemandRefuse(popupAlert.value, demand.name)
                viewingCiv.popupAlerts.remove(popupAlert)
            } else {
                playerDiploManager.refuseDemand(demand)
                if (demand == Demand.DoNotAttackUs)
                    viewingCiv.getDiplomacyManager(otherciv)!!.declareWar()
            }
        }
        return true
    }

    private fun addAcceptingDemand(): Boolean {
        val otherCiv = getCiv(popupAlert.value)
        if (otherCiv.isDefeated())
            return false
        addLeaderName(otherCiv)
        addTopicHeader("ACCEPTING DEMAND", Color.YELLOW)
        val leaderMessage = otherCiv.nation.acceptingDemand.ifEmpty {
            "We will comply, but our consent is given grudgingly."
        }
        addGoodSizedLabel(leaderMessage).row()
        music.playVoice("${otherCiv.civName}.acceptingDemand")
        addCloseButton("Very well.", KeyboardBinding.Cancel)
        return true
    }
    
    private fun addRejectingDemand(): Boolean {
        val otherCiv = getCiv(popupAlert.value)
        if (otherCiv.isDefeated())
            return false
        addLeaderName(otherCiv)
        addTopicHeader("REJECTING DEMAND", LIGHTER_ORANGE_COLOR)
        val theirDiplomacy = otherCiv.getDiplomacyManager(viewingCiv)!!
        val leaderMessage = if (theirDiplomacy.isRelationshipLevelGE(RelationshipLevel.Competitor)) {
            music.playVoice("${otherCiv.nation.name}.neutralRejectingDemand")
            otherCiv.nation.neutralRejectingDemand.ifEmpty {
                "Your demands are in poor taste. We shall decide this matter on our own."
            }
        } else {
            music.playVoice("${otherCiv.nation.name}.hateRejectingDemand")
            otherCiv.nation.hateRejectingDemand.ifEmpty {
                "Did you really expect us to bend to such brazen demands?"
            }
        }
        addGoodSizedLabel(leaderMessage).row()
        addCloseButton("You'll pay for this!")
        addCloseButton("Very well.", KeyboardBinding.Cancel)
        equalizeLastTwoButtonWidths()
        return true
    }

    private fun addDiplomaticMarriage() {
        val city = getCity(popupAlert.value)
        addGoodSizedLabel(city.name.tr() + ": " + "What would you like to do with the city?".tr(), Constants.headingFontSize) // Add name because there might be several cities
            .padBottom(20f).row()
        // UncivGC 帧同步: 弹窗属于观看文明 (同时回合下 currentPlayer 不一定是自己)
        val marryingCiv = viewingCiv

        if (marryingCiv.isOneCityChallenger()) {
            addDestroyOption {
                city.destroyCity(overrideSafeties = true)
            }
        } else {
            val mayAnnex = !marryingCiv.hasUnique(UniqueType.MayNotAnnexCities)
            addAnnexOption(city, mayAnnex) {}
            addSeparator()

            addPuppetOption(mayAnnex) {
                city.isPuppet = true
                city.cityStats.update()
            }
        }
    }

    private fun addFirstContact() {
        val civInfo = getCiv(popupAlert.value)
        val nation = civInfo.nation
        addLeaderName(civInfo)
        music.chooseTrack(civInfo.civName, MusicMood.themeOrPeace, MusicTrackChooserFlags.setSpecific)
        music.playVoice("${civInfo.civName}.introduction")
        if (civInfo.isCityState) {
            addGoodSizedLabel("We have encountered the City-State of [${nation.name}]!").row()
            addCloseButton("Excellent!")
        } else {
            addGoodSizedLabel(nation.introduction).row()
            addCloseButton("A pleasure to meet you.")
        }
    }

    private fun addGameHasBeenWon() {
        val victoryData = gameInfo.victoryData!!
        addGoodSizedLabel("[${victoryData.winningCivObject.civName}] has won a [${victoryData.victoryType}] Victory!").row()
        addButton("Victory status") { close(); worldScreen.game.pushScreen(VictoryScreen(worldScreen)) }.row()
        addCloseButton()
    }

    private fun addGoldenAge() {
        addGoodSizedLabel("GOLDEN AGE")
        addSeparator().padBottom(SEPARATOR_LINE_TO_TEXT_PADDING)
        addGoodSizedLabel("Your citizens have been happy with your rule for so long that the empire enters a Golden Age!").row()
        addCloseButton()
        music.chooseTrack(viewingCiv.civName, MusicMood.Golden, MusicTrackChooserFlags.setSpecific)
    }

    /** @return false to skip opening this Popup, as we're running in the initialization phase before the Popup is open */
    private fun addRecapturedCivilian(): Boolean {
        val position = HexCoord.fromString(popupAlert.value)
        val tile = gameInfo.tileMap[position]
        val capturedUnit = tile.civilianUnit  // This has got to be it
            ?: return false // the unit disappeared somehow? maybe a modded action?
        val originalOwner = capturedUnit.originalOwningCiv!!
        if (originalOwner.isDefeated()) return false
        val captor = viewingCiv

        addGoodSizedLabel("Return [${capturedUnit.name}] to [${originalOwner.civName}]?")
        addSeparator().padBottom(SEPARATOR_LINE_TO_TEXT_PADDING)
        addGoodSizedLabel("The [${capturedUnit.name}] we liberated originally belonged to [${originalOwner.civName}]. They will be grateful if we return it to them.").row()

        bottomTable.defaults().pad(0f, 30f) // Small buttons, plenty of pad so we don't fat-finger it

        addCloseButton(Constants.yes, KeyboardBinding.Confirm) {
            // Return it to original owner
            val unitName = capturedUnit.baseUnit.name
            capturedUnit.destroy()
            val closestCity = originalOwner.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }

            if (closestCity != null) {
                // Attempt to place the unit near their nearest city
                originalOwner.units.placeUnitNearTile(closestCity.location.toHexCoord(), unitName)
            }

            if (originalOwner.isCityState) {
                originalOwner.getDiplomacyManagerOrMeet(captor).addInfluence(45f)
            } else if (originalOwner.isMajorCiv()) {
                // No extra bonus from doing it several times
                originalOwner.getDiplomacyManagerOrMeet(captor)
                    .setModifier(DiplomaticModifiers.ReturnedCapturedUnits, 20f)
            }
            val notificationSequence = sequence {
                yield(LocationAction(tile.position))
                if (closestCity != null)
                    yield(LocationAction(closestCity.location))
                yield(DiplomacyAction(captor))
                yield(CivilopediaAction("Tutorial/Barbarians"))
            }
            originalOwner.addNotification("Your captured [${unitName}] has been returned by [${captor.civName}]", notificationSequence, NotificationCategory.Diplomacy, NotificationIcon.Trade, unitName, captor.civName)
        }
        addCloseButton(Constants.no, KeyboardBinding.Cancel) {
            // Take it for ourselves
            BattleUnitCapture.captureOrConvertToWorker(capturedUnit, captor)
        }
        return true
    }

    private fun addStartIntro() {
        val civInfo = viewingCiv
        addLeaderName(civInfo)
        addGoodSizedLabel(civInfo.nation.startIntroPart1).row()
        addGoodSizedLabel(civInfo.nation.startIntroPart2).row()
        addCloseButton("Let's begin!")

        // Since there's introduction text, play the startIntroPart1 voice hook with the nation's theme.
        val music = UncivGame.Current.musicController
        music.chooseTrack(civInfo.nation.name, MusicMood.themeOrPeace, MusicTrackChooserFlags.setSpecific)
        music.playVoice("${civInfo.nation.name}.startIntroPart1")
    }

    private fun addTechResearched() {
        val tech = gameInfo.ruleset.technologies[popupAlert.value]!!
        addGoodSizedLabel(tech.name)
        addSeparator().padBottom(SEPARATOR_LINE_TO_TEXT_PADDING)
        val centerTable = Table()
        centerTable.add(tech.quote.toLabel().apply { wrap = true }).width(stageWidth / 3)
        centerTable.add(ImageGetter.getTechIconPortrait(tech.name, 100f)).pad(20f)
        val descriptionScroll = ScrollPane(tech.getDescription(viewingCiv).toLabel().apply { wrap = true })
        centerTable.add(descriptionScroll).width(stageWidth / 3).maxHeight(stageHeight / 2)
        add(centerTable).row()
        addCloseButton()
        music.chooseTrack(tech.name, MusicMood.Researched, MusicTrackChooserFlags.setSpecific)
    }

    private fun addWarDeclaration(): Boolean {
        val civInfo = getCiv(popupAlert.value)
        // technically they already declared war, but if they're dead it'll be strange that they talk to us
        if (civInfo.isDefeated()) return false
        addLeaderName(civInfo)
        addTopicHeader("DECLARATION OF WAR", LIGHTER_RED_COLOR)
        val leaderMessage = civInfo.nation.declaringWar
        if (leaderMessage.isNotEmpty())
            addGoodSizedLabel(leaderMessage).row()
        addCloseButton("You'll pay for this!")
        addCloseButton("Very well.")
        equalizeLastTwoButtonWidths()
        music.chooseTrack(civInfo.civName, MusicMood.War, MusicTrackChooserFlags.setSpecific)
        music.playVoice("${civInfo.civName}.declaringWar")
        return true
    }

    private fun addTopicHeader(text: String, color: Color) {
        addGoodSizedLabel(text, color=color, size=Constants.smallerHeadingFontSize)
            .padBottom(20f).row()
    }

    private fun addWonderBuilt() {
        val wonder = gameInfo.ruleset.buildings[popupAlert.value]!!
        addGoodSizedLabel(wonder.name)
        addSeparator().padBottom(10f)
        if(ImageGetter.wonderImageExists(wonder.name)) {    // Wonder Graphic exists
            if(stageHeight * 3 > stageWidth * 4) {    // Portrait
                add(ImageGetter.getWonderImage(wonder.name))
                    .width(stageWidth / 1.5f)
                    .height(stageWidth / 3)
                    .row()
            }
            else {  // Landscape (or squareish)
                add(ImageGetter.getWonderImage(wonder.name))
                    .width(stageWidth / 2.5f)
                    .height(stageWidth / 5)
                    .row()
            }
        } else {    // Fallback
            add(ImageGetter.getConstructionPortrait(wonder.name, 100f)).pad(20f).row()
        }

        val centerTable = Table()
        val centerTableColumnWidth = stageWidth / if (wonder.quote.isEmpty()) 2 else 3
        if (wonder.quote.isNotEmpty()) {
            centerTable.add(wonder.quote.toLabel().apply { wrap = true })
                .width(centerTableColumnWidth)
                .pad(10f)
        }
        centerTable.add(wonder.getShortDescription().toLabel().apply { wrap = true })
            .width(centerTableColumnWidth)
            .pad(10f)
        add(centerTable).row()
        addCloseButton()
        music.chooseTrack(wonder.name, MusicMood.Wonder, MusicTrackChooserFlags.setSpecific)
    }

    //endregion
    //region Helpers

    private fun addLeaderName(civInfo: Civilization) {
        add(LeaderIntroTable(civInfo))
        addSeparator().padBottom(SEPARATOR_LINE_TO_TEXT_PADDING)
    }

    private fun addQuestionAboutTheCity(cityName: String) {
        addGoodSizedLabel("What would you like to do with the city of [$cityName]?",
            Constants.headingFontSize, hideIcons = true).padBottom(20f).row()
    }

    private fun addDestroyOption(destroyAction: () -> Unit) {
        val button = "Destroy".toTextButton()
        button.onActivation {
            destroyAction()
            close()
        }
        button.keyShortcuts.add('d')
        add(button).row()
        addGoodSizedLabel("Destroying the city instantly razes the city to the ground.").row()
    }

    private fun addAnnexOption(city: City, mayAnnex: Boolean, annexAction: () -> Unit) {
        val button = "Annex".toTextButton()
        button.apply {
            if (!mayAnnex) disable() else {
                button.onActivation {
                    annexAction()
                    city.annexCity()
                    close()
                }
                button.keyShortcuts.add('a')
            }
        }
        add(button).row()
        if (mayAnnex) {
            addGoodSizedLabel("Annexed cities become part of your regular empire.").row()
            addGoodSizedLabel("Their citizens generate 2x the unhappiness, unless you build a courthouse.").row()
        } else {
            addGoodSizedLabel("Your civilization may not annex this city.").row()
        }

    }

    private fun addPuppetOption(mayAnnex: Boolean, puppetAction: () -> Unit) {
        val button = "Puppet".toTextButton()
        button.onActivation {
            puppetAction()
            close()
        }
        button.keyShortcuts.add('p')
        add(button).row()
        addGoodSizedLabel("Puppeted cities do not increase your tech or policy cost.").row()
        addGoodSizedLabel("You have no control over the the production of puppeted cities.").row()
        addGoodSizedLabel("Puppeted cities also generate 25% less Science and Culture.").row()
        if (mayAnnex) addGoodSizedLabel("A puppeted city can be annexed at any time.").row()
    }

    private fun addLiberateOption(city: City, conqueringCiv: Civilization) {
        val button = "Liberate (city returns to [originalOwner])".fillPlaceholders(city.foundingCivObject!!.civName).toTextButton()
        button.onActivation {
            city.liberateCity(conqueringCiv)
            close()
        }
        button.keyShortcuts.add('l')
        add(button).row()
        addGoodSizedLabel("Liberating a city returns it to its original owner, giving you a massive relationship boost with them!")
    }

    private fun addRazeOption(city: City, mayAnnex: Boolean, conqueringCiv: Civilization) {
        val canRaze = city.canBeDestroyed(justCaptured = true)
        val button = "Raze".toTextButton()
        button.apply {
            if (!canRaze) disable()
            else {
                onActivation {
                    city.puppetCity(conqueringCiv)
                    if (mayAnnex) { city.annexCity() }
                    city.isBeingRazed = true
                    close()
                }
                keyShortcuts.add('r')
            }
        }
        add(button).row()
        if (canRaze) {
            if (mayAnnex) {
                addGoodSizedLabel("Razing the city annexes it, and starts burning the city to the ground.").row()
            } else {
                addGoodSizedLabel("Razing the city puppets it, and starts burning the city to the ground.").row()
            }
            addGoodSizedLabel("The population will gradually dwindle until the city is destroyed.").row()
        } else {
            addGoodSizedLabel("Original capitals and holy cities cannot be razed.").row()
        }
    }

    /** Returns if event was triggered correctly */
    /** UncivGC 帧同步: 事件渲染 actor 引用 (refreshForFsSync 只替换它, 不关闭整个弹窗) */
    private var eventRenderActor: com.badlogic.gdx.scenes.scene2d.Actor? = null
    /** 当前选项指纹 (选项没变就不重建 — 否则回合结算 built 广播会触发无谓重建 → 弹窗闪一下) */
    private var lastChoicesFingerprint = ""

    /** 计算当前事件可用选项指纹 (选项文本集合; 条件 "if [X] is constructed by anybody" 等变化会反映) */
    private fun computeChoicesFingerprint(): String {
        try {
            val splitString = popupAlert.value.split(Constants.stringSplitCharacter)
            val eventName = splitString[0]
            val event = gameInfo.ruleset.events[eventName] ?: return ""
            var unit: MapUnit? = null
            for (i in 1 until splitString.size) {
                if (splitString[i].startsWith("unitId=")) {
                    unit = viewingCiv.units.getUnitById(splitString[i].substringAfter("unitId=").toInt())
                }
            }
            val eventCiv = if (com.unciv.ui.screens.worldscreen.FrameSync.isFsMode(gameInfo))
                worldScreen.viewingCiv else gameInfo.currentPlayerCiv
            val choices = event.getMatchingChoices(com.unciv.models.ruleset.unique.GameContext(eventCiv, unit = unit))
            return choices?.map { it.text }?.joinToString("|") ?: ""
        } catch (e: Exception) {
            return ""
        }
    }

    private fun addEvent(): Boolean {
        // The event string is in the format "eventName" + (Constants.stringSplitCharacter + "unitId=1234")?
        // We explicitly specify that this is a unitId, to enable us to add other context info in the future - for example city id
        val splitString = popupAlert.value.split(Constants.stringSplitCharacter)
        val eventName = splitString[0]
        var unit: MapUnit? = null
        for (i in 1 until splitString.size) {
            if (splitString[i].startsWith("unitId=")){
                val unitId = splitString[i].substringAfter("unitId=").toInt()
                unit = viewingCiv.units.getUnitById(unitId)
            }
        }
        
        
        val event = gameInfo.ruleset.events[eventName] ?: return false
        val render = RenderEvent(event, worldScreen, unit) { close() }
        if (!render.isValid) return false
        // 记录渲染 actor (帧同步实时刷新时只替换它, 不关闭整个弹窗 → 不闪烁)
        val cell = add(render).pad(0f)
        eventRenderActor = cell.actor
        lastChoicesFingerprint = computeChoicesFingerprint()
        row()
        return true
    }

    //endregion

    /** UncivGC 帧同步: 事件选项可用性实时刷新 (如特殊伟人项目 "if [X] is constructed by anybody"
     *  互斥选项被他人占用后立即消失) — **弹窗内重建内容** (不关闭重开: 关闭重开会闪烁 + 触发
     *  close() 的 markEventResolved 误清挂起事件 → 重载后不再弹)。 */
    fun refreshForFsSync() {
        if (popupAlert.type != AlertType.Event) return
        try {
            // 选项没变 → 不重建 (回合结算 built 广播频繁, 重建会造成弹窗闪一下)
            val newFp = computeChoicesFingerprint()
            if (newFp.isNotEmpty() && newFp == lastChoicesFingerprint) return
            lastChoicesFingerprint = newFp
            eventRenderActor?.remove()
            eventRenderActor = null
            addEvent()
            innerTable.invalidateHierarchy()
            pack()
            worldScreen.shouldUpdate = true
        } catch (e: Exception) {
            // 重建失败不崩溃 — 下次广播再试
        }
    }

    override fun close() {
        // UncivGC 帧同步: 事件弹窗关闭 (选完或放弃) → 不再重新挂起 (防存档重载后重复弹窗)
        if (popupAlert.type == AlertType.Event) {
            com.unciv.ui.screens.worldscreen.FrameSync.markEventResolved(
                popupAlert.value.split(com.unciv.Constants.stringSplitCharacter)[0])
        }
        viewingCiv.popupAlerts.remove(popupAlert)
        worldScreen.shouldUpdate = true
        super.close()
    }
}

package com.unciv.ui.screens.civilopediascreen

import com.badlogic.gdx.scenes.scene2d.Actor
import com.unciv.json.fromJsonFile
import com.unciv.logic.GameInfo
import com.unciv.models.ruleset.Belief as BaseBelief
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.unit.UnitType as BaseUnitType
import com.unciv.ui.components.input.KeyboardBinding
import yairm210.purity.annotations.Readonly

/** Enum used as keys for Civilopedia "pages" (categories).
 *
 *  * Note names are singular on purpose - a "link" allows both key and label
 *  * **Order of values determines ordering of the categories in the Civilopedia top bar**
 *
 * @param label Translatable caption for the Civilopedia button
 */
enum class CivilopediaCategories (
    val label: String,
    val getImage: ((name: String, size: Float) -> Actor?)?,
    val binding: KeyboardBinding,
    val headerIcon: String,
    val getCategoryIterator: (ruleset: Ruleset, gameInfo: GameInfo?) -> Collection<ICivilopediaText>
) {
    Building ("Buildings",
        CivilopediaImageGetters.construction,
        KeyboardBinding.PediaBuildings,
        "OtherIcons/Cities",
        { ruleset, _ -> ruleset.buildings.values.filter { !it.isAnyWonder() } }
    ),
    Wonder ("Wonders",
        CivilopediaImageGetters.construction,
        KeyboardBinding.PediaWonders,
        "OtherIcons/Wonders",
        { ruleset, _ -> ruleset.buildings.values.filter { it.isAnyWonder() } }
    ),
    Resource ("Resources",
        CivilopediaImageGetters.resource,
        KeyboardBinding.PediaResources,
        "OtherIcons/Resources",
        { ruleset, _ -> ruleset.tileResources.values }
    ),
    Terrain ("Terrains",
        CivilopediaImageGetters.terrain,
        KeyboardBinding.PediaTerrains,
        "OtherIcons/Terrains",
        { ruleset, _ -> ruleset.terrains.values }
    ),
    Improvement ("Tile Improvements",
        CivilopediaImageGetters.improvement,
        KeyboardBinding.PediaImprovements,
        "OtherIcons/Improvements",
        { ruleset, _ -> ruleset.tileImprovements.values }
    ),
    Unit ("Units",
        CivilopediaImageGetters.construction,
        KeyboardBinding.PediaUnits,
        "OtherIcons/Shield",
        { ruleset, _ -> ruleset.units.values }
    ),
    UnitType ("Unit types",
        CivilopediaImageGetters.unitType,
        KeyboardBinding.PediaUnitTypes,
        "UnitTypeIcons/UnitTypes",
        { ruleset, _ -> BaseUnitType.getCivilopediaIterator(ruleset) }
    ),
    Nation ("Nations",
        CivilopediaImageGetters.nation,
        KeyboardBinding.PediaNations,
        "OtherIcons/Nations",
        { ruleset, _ -> ruleset.nations.values.filter { !it.isSpectator } }
    ),
    Personality("Personalities",
        getImage = null,
        KeyboardBinding.None,
        "OtherIcons/Personality",
        // The Personalities file contains unused chaff
        { ruleset, _ -> ruleset.personalities.values.filter { personality -> ruleset.nations.values.any { it.personality == personality.name } } }
    ),
    Technology ("Technologies",
        CivilopediaImageGetters.technology,
        KeyboardBinding.PediaTechnologies,
        "TechIcons/Philosophy",
        { ruleset, _ -> ruleset.technologies.values }
    ),
    Promotion ("Promotions",
        CivilopediaImageGetters.promotion,
        KeyboardBinding.PediaPromotions,
        "UnitPromotionIcons/Mobility",
        { ruleset, _ -> ruleset.unitPromotions.values }
    ),
    Policy ("Policies",
        CivilopediaImageGetters.policy,
        KeyboardBinding.PediaPolicies,
        "PolicyIcons/Constitution",
        { ruleset, _ -> ruleset.policies.values }
    ),
    Belief("Religions and Beliefs",
        CivilopediaImageGetters.belief,
        KeyboardBinding.PediaBeliefs,
        "ReligionIcons/Religion",
        { ruleset, _ -> (
            ruleset.beliefs.values.asSequence() +
            BaseBelief.getCivilopediaBeliefsEntry(ruleset) +
            BaseBelief.getCivilopediaReligionEntry(ruleset)
        ).toList() }
    ),
    Tutorial ("Tutorials",
        getImage = null,
        KeyboardBinding.PediaTutorials,
        "OtherIcons/ExclamationMark",
        { ruleset, _ -> ruleset.getCivilopediaTutorials() }
    ),
    UgcChanges ("UGC 改动内容",
        getImage = null,
        KeyboardBinding.None,
        "OtherIcons/ExclamationMark",
        { ruleset, _ -> getUgcPediaEntries("UgcPedia.json") }
    ),
    UgcCredits ("致谢",
        getImage = null,
        KeyboardBinding.None,
 "OtherIcons/ExclamationMark",
        { ruleset, _ -> getUgcPediaEntries("UgcCredits.json") }
    ),
    Victory ("Victory Types",
        CivilopediaImageGetters.victoryType,
        KeyboardBinding.PediaVictoryTypes,
        "OtherIcons/Score",
        { ruleset, gameInfo -> ruleset.victories.values
            .filter {
                // Only display active victory types
                gameInfo?.gameParameters?.victoryTypes?.contains(it.name) ?: true
            }
        }
    ),
    UnitNameGroup("Unit Names",
        CivilopediaImageGetters.unitNameGroup,
        KeyboardBinding.PediaUnitNameGroups,
        "OtherIcons/UnitNameGroups",
        { ruleset, _ -> ruleset.unitNameGroups.values
            .filter {
                it.unitNames.isNotEmpty() && it.getUnits(ruleset).isNotEmpty()
            }
        }
    ),
    Difficulty ("Difficulty levels",
        getImage = null,
        KeyboardBinding.PediaDifficulties,
        "OtherIcons/Quickstart",
        { ruleset, _ -> ruleset.difficulties.values }
    ),
    Era ("Eras",
        getImage = null,
        KeyboardBinding.PediaEras,
        "OtherIcons/Tyrannosaurus",
        { ruleset, _ -> ruleset.eras.values }
    ),
    Speed ("Speeds",
        getImage = null,
        KeyboardBinding.PediaSpeeds,
        "OtherIcons/Timer",
        { ruleset, _ -> ruleset.speeds.values }
    );

    companion object {
        @Readonly fun fromLink(name: String): CivilopediaCategories? =
            entries.firstOrNull { it.name == name }
            ?: entries.firstOrNull { it.label == name }

        /** Get all Tutorials to be displayed in the Civilopedia (hiding is done later, however) */
        private fun Ruleset.getCivilopediaTutorials() =
            /** Note: **Not** UncivGame.Current.gameInfo?.getGlobalUniques() because this is for pedia display,
             *        and showing the merged GlobalUniques + Speed uniques + Difficulty might surprise.
             */
            tutorials.values +
                // Add entry for Global Uniques only if they have anything interesting
                listOfNotNull(globalUniques.takeIf { it.hasUniques() })

        /** UncivGC (2026-08-23): 百科自定义条目 — 模仿教程写法 (civilopediaText json 结构),
         *  从 android/assets/jsons/<file> 加载 (UgcPedia.json = UGC 改动内容, UgcCredits.json = 致谢)。 */
        private fun getUgcPediaEntries(fileName: String): List<ICivilopediaText> {
            try {
                val file = com.badlogic.gdx.Gdx.files.internal("jsons/$fileName")
                if (!file.exists()) return emptyList()
                val entries = com.unciv.json.json()
                    .fromJsonFile(Array<UgcPediaEntry>::class.java, file)
                return entries.map { it as ICivilopediaText }
            } catch (e: Exception) {
                return emptyList()
            }
        }
    }

    /** UncivGC 百科自定义条目 (UGC 改动内容/致谢): 与教程同款 civilopediaText json 结构,
     *  makeLink 指向本类别 (条目展示/搜索链接正确) */
    class UgcPediaEntry(
        override var name: String = "",
        override var civilopediaText: List<FormattedLine> = emptyList()
    ) : ICivilopediaText, com.unciv.models.stats.INamed {
        override fun makeLink() = "UgcChanges/$name"
    }
}

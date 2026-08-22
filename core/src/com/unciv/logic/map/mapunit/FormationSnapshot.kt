package com.unciv.logic.map.mapunit

import com.unciv.logic.IsPartOfGameInfoSerialization

/**
 * 合并为军团/集团军时保存的副单位快照，用于拆分时恢复。
 * 军团存 1 个快照，集团军存 2 个。
 */
data class FormationSnapshot(
    /** 副单位的基础单位名 (baseUnit.name) */
    val unitName: String = "",
    /** 副单位的实例名 (自定义/默认单位名, 拆分时恢复) — 2026-08-22 */
    val name: String = "",
    /** 副单位的晋升次数 (numberOfPromotions) */
    val level: Int = 0,
    /** 副单位的晋升名称列表 */
    val promotions: List<String> = emptyList(),
    /** 副单位的经验值 (XP) */
    val xp: Int = 0
) : IsPartOfGameInfoSerialization

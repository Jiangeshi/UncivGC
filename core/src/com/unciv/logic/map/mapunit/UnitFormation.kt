package com.unciv.logic.map.mapunit

/** 军团/集团军 (陆军) 与 舰队/无敌舰队 (海军) 编队形态 — 文明6 式合并系统 */
enum class UnitFormation {
    /** 普通单体单位 */
    Single,
    /** 军团 (2个陆军单位合并) */
    Corps,
    /** 集团军 (3个陆军单位合并) */
    Army,
    /** 舰队 (2个海军单位合并) */
    Fleet,
    /** 无敌舰队 (3个海军单位合并) */
    Armada;

    /** 编队等级: 0=单体, 1=双单位 (军团/舰队), 2=三单位 (集团军/无敌舰队) — 加成/角标/升级倍率按此计算 */
    val tier: Int
        get() = when (this) {
            Single -> 0
            Corps, Fleet -> 1
            Army, Armada -> 2
        }
}

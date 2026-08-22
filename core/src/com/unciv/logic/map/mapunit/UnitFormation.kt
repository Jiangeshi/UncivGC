package com.unciv.logic.map.mapunit

/** 军团/集团军编队形态 */
enum class UnitFormation {
    /** 普通单体单位 */
    Single,
    /** 军团 (2个单位合并) */
    Corps,
    /** 集团军 (3个单位合并) */
    Army
}

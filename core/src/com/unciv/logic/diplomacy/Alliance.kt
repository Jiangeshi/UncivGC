package com.unciv.logic.diplomacy

import yairm210.purity.annotations.Readonly

/**
 * 同盟 (2026-08-26 设计稿 v1.0, 文档/同盟系统-设计稿.md)
 * - 成对 1 对 1: 一个文明可同时与多个不同文明分别结盟 (A-B、A-C 各自独立)
 * - 期限 20 回合; 到期双方弹续约窗, 本回合不响应视为拒绝; 都同意 → 等级+1 (最高 3 级), 期限重置
 * - 同盟期间无法主动退出; 不能宣战盟友; 结束后同盟冷却 10 回合
 * - 等级收益: Lv1 盟友已研究科技 +10% 科研 + 同盟间商路收益 +50%;
 *   Lv2 共同对敌 +10% 战斗力; Lv3 共享实时视野
 * - 盟友宣战/被宣战 → 弹窗询问是否跟进 (可选择参战, 2026-08-26 用户补充)
 */
class Alliance(
    val civA: String,      // 两个 civId (字典序排序保证成对唯一)
    val civB: String,
    var level: Int = 1,    // 1 = 结盟, 续约 +1, 最高 MAX_LEVEL
    var turnsLeft: Int = DURATION
) : com.unciv.logic.IsPartOfGameInfoSerialization {
    /** JSON 反序列化必需 (Unciv json 通过无参构造 + 反射设字段; 没有它读档报"无法加载") */
    @Suppress("unused")
    internal constructor() : this("", "", 1, DURATION)

    @Readonly
    fun contains(civId: String) = civId == civA || civId == civB

    /** 对方的 civId (按自己视角) */
    @Readonly
    fun otherCiv(civId: String): String? = when (civId) {
        civA -> civB
        civB -> civA
        else -> null
    }

    companion object {
        const val MAX_LEVEL = 3
        const val DURATION = 20
        /** 拒绝提议/不续约结束后的同盟冷却回合数 */
        const val COOLDOWN = 10

        /** 成对唯一 key (字典序拼接) */
        fun pairKey(a: String, b: String): String = if (a <= b) "$a|$b" else "$b|$a"
    }
}

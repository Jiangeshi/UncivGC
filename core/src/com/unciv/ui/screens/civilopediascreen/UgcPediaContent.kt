package com.unciv.ui.screens.civilopediascreen

/**
 * UncivGC 百科自定义条目内容 — UGC 改动内容 / 致谢。
 *
 * 内容以用户确认的最终文案为准 (2026-08-23 22:54)；
 * 排版: 条目名即标题 (去掉自动大标题/分隔线), 内容 = 说明行 + 要点。
 * 内容**硬编码在代码里** — 模组无法同名覆盖、玩家无法改文件。
 */
object UgcPediaContent {

    fun entries(key: String): List<ICivilopediaText> = when (key) {
        "UgcPedia" -> ugcChanges
        "UgcCredits" -> ugcCredits
        else -> emptyList()
    }

    private fun line(text: String, header: Int = 0, color: String = "") =
        FormattedLine(text, header = header, color = color)
    private fun gap() = FormattedLine()
    private fun sep() = FormattedLine(separator = true)
    /** 要点行: 圆点前缀 + 缩进 (模仿列表) */
    private fun point(text: String) = FormattedLine("• $text", indent = 1)

    private val ugcChanges = listOf(
        CivilopediaCategories.UgcPediaEntry("帧同步实时联机", listOf(
            line("同时回合（Civ6 联机模式）："),
            point("回合内所有玩家同时操作，单位移动实时可见"),
            point("全员点「完成回合」统一结算，无存档上传下载等待"),
            point("每段回合可设保底时长（2/4/5/7/10 分钟或无限），超时自动结算"),
            point("回合结算停留锁定（防结算前手速操作）"),
            point("断线重连自动恢复，掉线玩家不阻塞推进"),
        ), 1),
        CivilopediaCategories.UgcPediaEntry("组队系统（团队模式）", listOf(
            point("开房可配置 1-3 队，每队 1-3 人，玩家自选队伍"),
            point("同队共享视野：队友看到的地图实时可见，队友探索过的地块永久可见"),
            point("队友自动认识、默认开放边境；禁止攻击/宣战/谴责/劫掠队友"),
            point("同队禁止交易城市（防刷城）；赠礼、贸易正常"),
            point("共享胜利：任一队友达成胜利条件全队胜利（征服胜利按队伍算）"),
            point("出生分半：同队玩家固定出生在同一半图（两队左右分半）"),
            point("队友标注：单位/外交面板显示「（队友）」"),
        ), 2),
        CivilopediaCategories.UgcPediaEntry("战斗表现", listOf(
            point("伤害数字飘字（攻击方、旁观者都能看到）"),
            point("单位突进 + 受击闪烁动画"),
            point("攻击次数用完 → 单位图标显示小锁标记（实验性 UI）"),
        ), 3),
        CivilopediaCategories.UgcPediaEntry("实验性 UI（设置中开启）", listOf(
            point("顶部按钮组重排：科技/政策/外交/间谍/撤销固定尺寸并排"),
            point("文明排行面板：实时显示已遇到文明的金钱/科研/文化/信仰等"),
            point("政策/外交/科技按钮常开"),
            point("未开启时界面与原版完全一致"),
        ), 4),
        CivilopediaCategories.UgcPediaEntry("全图小地图", listOf(
            point("主界面小地图显示全图轮廓，未探索区域灰色显示"),
        ), 5),
        CivilopediaCategories.UgcPediaEntry("编队系统（军团/集团军）", listOf(
            point("两个相同陆军单位可合并为军团，再加一个可升级为集团军"),
            point("战斗力加成（×1.33 / ×1.50）、血量合并、可拆分"),
        ), 6),
        CivilopediaCategories.UgcPediaEntry("撤回功能", listOf(
            point("本回合内可连续多级回退（单机/联机均可用）"),
            point("帧同步房间禁用"),
        ), 7),
        CivilopediaCategories.UgcPediaEntry("存档与随机", listOf(
            point("「允许读档重试（SL）」开关：单机开启后读档随机结果可变"),
            point("联机存档确定性随机（防刷档）"),
        ), 8),
        CivilopediaCategories.UgcPediaEntry("模组编辑器", listOf(
            point("游戏内编辑模组：单位/建筑/文明/科技/政策/奇观等"),
            point("词条（uniques）编辑器，支持复杂条件"),
            point("模组打包、图片自动打包"),
        ), 9),
        CivilopediaCategories.UgcPediaEntry("模组镜像", listOf(
            point("国内模组镜像（主菜单入口），收录热门模组（LM2、Civ6 mod、DeCiv、5Hex、东方等）"),
            point("模组一键下载/更新，带进度条"),
            point("进房自动检测模组版本并提示"),
        ), 10),
        CivilopediaCategories.UgcPediaEntry("联机大厅", listOf(
            point("游戏内联机大厅：建房/搜索/加入/聊天/准备/观战"),
            point("跳海、重新开始等（重新生成地图）快捷操作"),
        ), 11),
        CivilopediaCategories.UgcPediaEntry("应用内更新", listOf(
            point("游戏内检测新版本，进度条下载，自动弹系统安装"),
            point("「安装未知应用」权限引导"),
        ), 12),
    )

    private val ugcCredits = listOf(
        CivilopediaCategories.UgcPediaEntry("致谢", listOf(
            line("感谢 LM2 模组群（780959855）所有群友、Unciv 中文社区群群友的支持！你们的支持，是 UGC 前进的动力。"),
            gap(),
            line("感谢维德、Excuse me？、机枪、明不可、浮泽云梦熙、海陆、白杨、百分之一百亿、遗封、冷雨之风、非人哉、猫猫、溯泯、西瓜、zyzzw 等在内测期间提供的宝贵建议与反馈！"),
            gap(),
            line("特别感谢：", header = 3, color = "#9cf"),
            line("感谢浮泽云梦熙、冷雨之风、维德、满堂花醉、Excuse me？等提供的额外支持！"),
        ), 1),
    )
}

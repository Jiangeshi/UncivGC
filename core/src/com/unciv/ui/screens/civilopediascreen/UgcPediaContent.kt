package com.unciv.ui.screens.civilopediascreen

/**
 * UncivGC 百科自定义条目内容 — UGC 改动内容 / 致谢。
 *
 * 写法模仿教程 (civilopediaText 结构: 分段正文 + 空行 + 分隔线 + 小标题 + 彩色强调),
 * 内容**硬编码在代码里** — 模组无法同名覆盖、玩家无法改文件 (2026-08-23 用户要求)。
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

    private val ugcChanges = listOf(
        CivilopediaCategories.UgcPediaEntry("帧同步实时联机", listOf(
            line("同时回合（Civ6 联机模式）", header = 3),
            sep(),
            line("回合内所有玩家同时操作，单位移动实时可见。"),
            gap(),
            line("服务器权威模拟：战斗/经济/外交全部由服务器执行，公正防作弊。"),
            gap(),
            line("全员点「完成回合」统一结算，无需存档上传下载等待。"),
            gap(),
            line("每段回合可设保底时长（2/4/5/7/10 分钟或无限），超时自动结算。"),
            gap(),
            line("回合结算短暂停留锁定，防止结算前手速操作。"),
            gap(),
            line("断线自动重连，掉线玩家不阻塞推进。"),
        )),
        CivilopediaCategories.UgcPediaEntry("组队系统", listOf(
            line("团队模式（视野共享）", header = 3),
            sep(),
            line("开房可配置 1-3 队，每队 1-3 人，玩家进房自选队伍。"),
            gap(),
            line("同队共享视野：队友当前看到的地图你实时可见；队友探索过的地块永久可见。"),
            gap(),
            line("队友自动认识、默认开放边境；禁止攻击/宣战/谴责/劫掠队友。"),
            gap(),
            line("同队禁止交易城市（防刷城）；赠礼与正常贸易不受影响。"),
            gap(),
            line("共享胜利：任一队友达成胜利条件，全队胜利；征服胜利按队伍计算。"),
            gap(),
            line("出生分半：同队玩家固定出生在同一半图（两队左右分半，三队三分）。"),
            gap(),
            line("队友标注：单位面板与外交界面显示「（队友）」。", color = "#9cf"),
        )),
        CivilopediaCategories.UgcPediaEntry("战斗表现", listOf(
            line("让战斗看得见", header = 3),
            sep(),
            line("伤害数字飘字：攻击方、旁观者都能看到每次攻击的伤害。"),
            gap(),
            line("单位突进与受击闪烁动画。"),
            gap(),
            line("攻击次数用完的单位，图标右上角显示小锁标记（实验性 UI）。"),
        )),
        CivilopediaCategories.UgcPediaEntry("观战系统", listOf(
            line("看海模式", header = 3),
            sep(),
            line("战败后可选择转为观战，继续看完整局游戏。"),
            gap(),
            line("观战者拥有全图视野，可开关迷雾。"),
            gap(),
            line("观战界面自动隐藏操作按钮（科技/政策/外交/暂停等）。"),
        )),
        CivilopediaCategories.UgcPediaEntry("实验性 UI", listOf(
            line("设置 → 开启「实验性 UI」后生效", header = 3),
            sep(),
            line("顶部按钮组重排：科技/政策/外交/间谍/撤销固定尺寸并排，顶部齐平。"),
            gap(),
            line("文明排行面板：科技按钮下方实时显示已遇到文明的金钱/科研/文化/信仰/快乐等。"),
            gap(),
            line("科技/政策/外交按钮常开，不再因没建城/没遇到文明而消失。"),
            gap(),
            line("未开启时界面与原版完全一致。", color = "#9cf"),
        )),
        CivilopediaCategories.UgcPediaEntry("全图小地图", listOf(
            line("未探索区域灰色显示", header = 3),
            sep(),
            line("小地图显示整张地图的轮廓，一眼看到自己在世界中的位置。"),
        )),
        CivilopediaCategories.UgcPediaEntry("编队系统", listOf(
            line("军团 / 集团军", header = 3),
            sep(),
            line("两个相同的陆军单位可以合并为军团，军团再合并一个相同单位升级为集团军。"),
            gap(),
            line("战斗力加成：军团 ×1.25，集团军 ×1.33（加法）。"),
            gap(),
            line("血量合并（上限 100），可拆分回独立单位。"),
            gap(),
            line("升级与维护费用 ×2；晋升保留发起单位。"),
            gap(),
            line("模组可通过配置关闭或调整数值。"),
        )),
        CivilopediaCategories.UgcPediaEntry("撤回功能", listOf(
            line("本回合内连续多级回退", header = 3),
            sep(),
            line("单机与普通联机均可使用「撤回」按钮，逐步撤销本回合操作。"),
            gap(),
            line("帧同步（同时回合）房间禁用，保证公平。", color = "#9cf"),
        )),
        CivilopediaCategories.UgcPediaEntry("存档与随机", listOf(
            line("SL 开关", header = 3),
            sep(),
            line("「允许读档重试（SL）」开关（创建游戏 → 高级设置）：单机开启后，读档重试随机结果可变。"),
            gap(),
            line("联机对局使用确定性随机，防止刷档。", color = "#9cf"),
        )),
        CivilopediaCategories.UgcPediaEntry("模组编辑器", listOf(
            line("游戏内编辑模组", header = 3),
            sep(),
            line("内置编辑器：单位/建筑/文明/科技/政策/奇观/教程等均可编辑。"),
            gap(),
            line("词条（uniques）编辑器支持复杂条件与参数。"),
            gap(),
            line("模组图片自动打包，改图后启动自动重新打包。"),
        )),
        CivilopediaCategories.UgcPediaEntry("模组镜像", listOf(
            line("国内镜像仓库", header = 3),
            sep(),
            line("主菜单「模组」下方入口，收录热门模组：LM2、Civ6 mod、DeCiv、5Hex、东方等。"),
            gap(),
            line("模组一键下载/更新，带进度条。"),
            gap(),
            line("进入房间自动检测模组新版本并提示。"),
        )),
        CivilopediaCategories.UgcPediaEntry("联机大厅", listOf(
            line("QQ 开房机器人", header = 3),
            sep(),
            line("游戏内联机大厅：建房/搜索/加入/聊天/准备/观战，一站完成。"),
            gap(),
            line("QQ 群机器人开房：群内指令建房、拉人、开局、跳海。"),
            gap(),
            line("房间管理：玩家状态、踢人、房主转移。"),
        )),
        CivilopediaCategories.UgcPediaEntry("应用内更新", listOf(
            line("免去手动找包", header = 3),
            sep(),
            line("游戏内检测新版本，进度条下载，下载完自动弹系统安装界面。"),
            gap(),
            line("「安装未知应用」权限一键引导。"),
        )),
        CivilopediaCategories.UgcPediaEntry("界面与语言", listOf(
            line("中英双语", header = 3),
            sep(),
            line("中文系统显示中文、英文系统显示英文，联机提示信息全面中文化。"),
        )),
        CivilopediaCategories.UgcPediaEntry("细节修复（部分）", listOf(
            line("看不见的打磨", header = 3),
            sep(),
            line("相遇金币丢失、贸易不同步、城市被攻占视角错乱、断线重连卡死等联机问题。"),
            gap(),
            line("工人修复改良闪退、单位 -2 经验、编队存档崩溃等稳定性修复。"),
            gap(),
            line("道路劫掠显示、信仰购买价格、闲置单位提醒、过回合黑屏等体验修复。"),
        )),
    )

    private val ugcCredits = listOf(
        CivilopediaCategories.UgcPediaEntry("致谢", listOf(
            line("致谢内容整理中…", header = 3),
            sep(),
            line("感谢所有支持 UGC 的玩家与测试者！"),
        )),
    )
}

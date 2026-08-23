package com.unciv.ui.screens.civilopediascreen

/**
 * UncivGC 百科自定义条目内容 — UGC 改动内容 / 致谢。
 *
 * 写法模仿教程: 以玩家视角、通俗口语、解释"这是什么/对玩家意味着什么/怎么用"。
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
            line("同时回合：大家一起动！", header = 3),
            sep(),
            line("以前玩联机，大家要轮流操作——轮到你的时候，别人只能干等着看。"),
            gap(),
            line("现在开房时勾选「同时回合」，所有人都能同时操作自己的单位，就像文明 6 联机那样。你在派侦察兵探路的时候，队友和对手的部队也在移动，互不耽误。"),
            gap(),
            line("不用担心有人作弊：所有计算都在服务器上进行，谁也没办法偷偷改数据。"),
            gap(),
            line("等到所有人都点「完成回合」，游戏才会一起进入下一回合，不需要等谁上传存档。"),
            gap(),
            line("担心有人拖时间？房主可以给每个回合设一个保底时长（比如 5 分钟），时间到了还没点完成的话，系统会自动帮你过回合；也可以选「无限制」，大家点完才过。"),
            gap(),
            line("中途掉线了也没关系，重连就能接着玩，不会拖累其他人。"),
        )),
        CivilopediaCategories.UgcPediaEntry("组队系统", listOf(
            line("和好友并肩作战", header = 3),
            sep(),
            line("想和朋友一起打别人？开房时可以设置队伍（最多 3 队，每队最多 3 人），每个玩家进房后自己选想加入的队伍。"),
            gap(),
            line("同一队的队友之间共享视野：他看到的部队和地形，你都能实时看到；他探索过的地图，你以后也一直能看到——再也不用靠猜了！"),
            gap(),
            line("队友之间自动互相认识，还可以随意进出对方的领地，方便互相支援。"),
            gap(),
            line("不过要记住：同队之间不能攻击、宣战、谴责，也不能互相交易城市（防止用小号刷城市刷分）；但送金币、送单位、正常贸易都不受影响。"),
            gap(),
            line("胜利也是共享的：只要队伍里任何一个人达成了胜利条件，全队一起赢。"),
            gap(),
            line("更有意思的是，同一队的玩家会出生在地图的同一半，开局就能互相照应。", color = "#9cf"),
            gap(),
            line("想认出谁是自己人？队友的单位和外交界面会标着「（队友）」。"),
        )),
        CivilopediaCategories.UgcPediaEntry("战斗表现", listOf(
            line("让战斗看得见", header = 3),
            sep(),
            line("以前打架只看得到结果，现在能看到过程：攻击时会有伤害数字飘出来，单位会突进、受击会闪烁，谁打了谁、打掉多少血，一目了然。"),
            gap(),
            line("连旁观的人也能看到这些动画，观战体验好了很多。"),
            gap(),
            line("另外，攻击次数用完的单位，图标右上角会有一个小锁标记（实验性 UI），一眼就能看出谁这回合还能打。"),
        )),
        CivilopediaCategories.UgcPediaEntry("观战系统", listOf(
            line("战败了？继续看海", header = 3),
            sep(),
            line("战败之后不用直接退出——可以选择转为观战，继续把整局看完，学学别人的打法。"),
            gap(),
            line("观战时拥有全图视野，还能开关迷雾，想看哪里看哪里。"),
            gap(),
            line("观战界面会自动隐藏所有操作按钮（科技/政策/外交/暂停这些），安安静静看就好。"),
        )),
        CivilopediaCategories.UgcPediaEntry("实验性 UI", listOf(
            line("尝鲜新界面", header = 3),
            sep(),
            line("在设置里打开「实验性 UI」，可以体验一些新界面：顶部按钮重排得整整齐齐，科技/政策/外交/间谍/撤销固定大小并排，不再忽高忽低。"),
            gap(),
            line("科技按钮下面会多一个文明排行面板，实时显示其他文明的金钱、科研、文化、信仰这些数据，谁领先谁落后一眼看清。"),
            gap(),
            line("科技、政策、外交按钮也改成常开了——不会因为没建城、没遇到其他文明就突然消失。"),
            gap(),
            line("不习惯？关掉开关就回到原版界面，什么都不影响。", color = "#9cf"),
        )),
        CivilopediaCategories.UgcPediaEntry("全图小地图", listOf(
            line("世界地图一目了然", header = 3),
            sep(),
            line("小地图现在会显示整张地图的轮廓，已经探索过的地方亮起来，没探索过的地方是灰色的，随时知道自己在地图上的位置。"),
        )),
        CivilopediaCategories.UgcPediaEntry("编队系统", listOf(
            line("军团 / 集团军", header = 3),
            sep(),
            line("两个相同种类的陆军单位（比如两个勇士）可以合并成一个「军团」，战斗力更强；军团再合并一个相同单位，就能升级成「集团军」。"),
            gap(),
            line("合并后战斗力有加成（军团 1.25 倍、集团军 1.33 倍），血量也会合并，残血单位合在一起又能打。"),
            gap(),
            line("想拆开也随时可以拆回原来的单位，灵活方便。"),
            gap(),
            line("代价是升级和维护费用翻倍，晋升保留发起单位的。"),
            gap(),
            line("模组作者可以通过配置关闭这个功能或调整加成。"),
        )),
        CivilopediaCategories.UgcPediaEntry("撤回功能", listOf(
            line("手滑了？撤一步", header = 3),
            sep(),
            line("单机或普通联机里，点「撤回」可以一步步撤销本回合的操作，手滑点错地方不用重开。"),
            gap(),
            line("注意：同时回合（帧同步）房间为了公平，禁用了撤回。", color = "#9cf"),
        )),
        CivilopediaCategories.UgcPediaEntry("存档与随机", listOf(
            line("读档重试开关", header = 3),
            sep(),
            line("单机开新游戏时，高级设置里有一个「允许读档重试（SL）」开关：打开后读档重试，随机结果会重新变化（比如遗迹奖励会不一样）。"),
            gap(),
            line("联机对局默认固定随机结果，防止有人反复读档刷好东西。", color = "#9cf"),
        )),
        CivilopediaCategories.UgcPediaEntry("模组编辑器", listOf(
            line("游戏里自己做模组", header = 3),
            sep(),
            line("不用再羡慕别的游戏——游戏内置了模组编辑器，单位、建筑、文明、科技、政策、奇观、教程这些都能直接编辑。"),
            gap(),
            line("想做复杂的词条（uniques）效果？编辑器支持各种条件和参数，网上能找到不少教程。"),
            gap(),
            line("改的图片会自动打包，改完重启游戏就能看到效果。"),
        )),
        CivilopediaCategories.UgcPediaEntry("模组镜像", listOf(
            line("国内模组仓库", header = 3),
            sep(),
            line("下载模组不用再翻墙了：主菜单「模组」下面有个国内镜像，收录了 LM2、Civ6 mod、DeCiv、5Hex、东方等热门模组。"),
            gap(),
            line("模组一键下载、一键更新，还带进度条。"),
            gap(),
            line("进房间的时候，如果别人用的模组你有新版本，游戏会提醒你更新，避免版本不一致进不去。"),
        )),
        CivilopediaCategories.UgcPediaEntry("联机大厅", listOf(
            line("QQ 群里开房", header = 3),
            sep(),
            line("游戏内有完整的联机大厅：建房、搜索房间、加入、聊天、准备、观战，一条龙。"),
            gap(),
            line("更省事的是 QQ 群机器人：在群里直接发指令就能建房、拉人、开局，朋友都在群里，喊一声就开打。"),
            gap(),
            line("房主还能管理房间：看谁没准备、踢人、房主退出自动转移给下一个人。"),
        )),
        CivilopediaCategories.UgcPediaEntry("应用内更新", listOf(
            line("更新不用到处找包", header = 3),
            sep(),
            line("游戏内会自动检测新版本，下载带进度条，下完自动弹出安装界面，不用再去群里找安装包。"),
            gap(),
            line("第一次安装会引导你开启「安装未知应用」权限，一次设置以后就全自动了。"),
        )),
        CivilopediaCategories.UgcPediaEntry("界面与语言", listOf(
            line("中英双语", header = 3),
            sep(),
            line("手机是中文系统就显示中文，英文系统就显示英文，不用手动切换。"),
            gap(),
            line("联机的提示信息也都翻译成中文了，不会再看不懂服务器报错。"),
        )),
        CivilopediaCategories.UgcPediaEntry("细节修复（部分）", listOf(
            line("看不见的打磨", header = 3),
            sep(),
            line("除了新功能，还修了一大批影响体验的问题：联机时见面金币丢失、贸易不同步、城市被攻占后视角错乱、断线重连卡死……"),
            gap(),
            line("还有工人修改良闪退、单位经验变负数、编队存档崩溃这类稳定性问题。"),
            gap(),
            line("以及道路劫掠看不到、信仰购买价格不涨、闲置单位不提醒、过回合黑屏这些小细节。"),
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

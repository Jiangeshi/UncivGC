# UncivGC

[简体中文说明](#中文说明) | English

**UncivGC** is an enhanced fork of [Unciv](https://github.com/yairm210/Unciv) — the open-source reimplementation of Civilization V — adding an **online lobby**, an **undo system**, a **domestic mod mirror (CN)** and more, with full Simplified Chinese UI support.

## Features

- **Online Lobby** (custom server): create/join rooms, ready-up, host starts the game, spectate, kick, "jump to new map" (跳海), room reset, AI players, live long-polling sync
- **Undo system**: undo your moves within your turn (snapshot-based, safe for both single-player and multiplayer)
- **Mod Mirror (CN)**: browse and one-click download/update 21 popular mods (LM2, DeCiv, 5Hex, ...) from a domestic server mirror, with auto-update detection in rooms
- **In-app update**: download updates with an in-game progress bar, then install via the system installer
- **Gameplay tweaks**:
  - Mirrored starting positions (left/right/four-way/center)
  - Re-rollable random option ("Allow random re-roll on reload") — enables save-scumming in single player
  - Full-map minimap with unexplored areas shown in gray
  - Two-column menu layouts for mobile
- **Privacy**: lobby API protected by token auth

## Download

APK releases are distributed through the community (QQ group). Ask the maintainer or check the releases page.

## License

[MPL-2.0](./LICENSE) — same as Unciv. The complete modified source code is available in this repository.

---

## 中文说明

**UncivGC** 是 [Unciv](https://github.com/yairm210/Unciv)（文明5 的开源复刻）的增强改版，新增**联机大厅**、**撤回系统**、**国内模组镜像**等，并完整支持简体中文界面。

### 功能

- **联机大厅**（自建服务器）：建房/加入、准备、房主开局、观战、踢人、跳海（开新图）、重新开始、AI 电脑、长轮询实时同步
- **撤回**：本回合内可连续多级撤回（快照方案，单机/联机均安全）
- **国内模组镜像**：21 个热门模组（LM2、DeCiv、5Hex 等）一键下载/更新，进房自动检测新版
- **应用内更新**：游戏内进度条下载，系统安装器安装
- **玩法增强**：
  - 镜像出生点（左右/上下/四向/中心）
  - 随机数可读档重试开关（单机可 SL）
  - 全图小地图（未探索区域灰色显示）
  - 手机端两列菜单布局
- **安全**：大厅接口 token 鉴权

### 下载

APK 通过社区（QQ 群）发布，联系维护者获取。

### 许可

[MPL-2.0](./LICENSE)（与 Unciv 一致），本仓库提供完整修改源码。

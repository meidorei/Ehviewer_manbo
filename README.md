# EhViewer manbo

![应用图标](fastlane/metadata/android/en-US/images/icon.png)

基于 EhViewer_CN_SXJ 的 Android 客户端分支，在浏览、搜索、收藏和下载功能上，增加本地追更、书签未读、阅读边界、阅读队列和 JM 资料查询。

[下载发布版本](https://github.com/meidorei/Ehviewer_CN_SXJ_manbo/releases) · [使用指南](docs/usage.md) · [开发与构建](docs/build.md) · [常见问题](docs/troubleshooting.md) · [全部文档](docs/README.md)

## 主要功能

| 功能 | 说明 |
| --- | --- |
| 本地追更 | 长按标签添加，不依赖服务器订阅；支持更新计数、管理和 JSON 导入导出 |
| 书签更新 | 检查保存的搜索条件，连续翻页记录新增画廊；相同 GID 在多个书签间联动已读 |
| 更新任务 | 支持全局中文扫描、逐项检查和单书签检查，可暂停、继续、停止 |
| 阅读边界 | 追更和书签显示“上次更新到这”；首页有可手动重置的独立分割线 |
| 自动中文条件 | 普通浏览可自动追加中文语言条件，已有正向语言条件时保留原条件 |
| 阅读队列 | 记录应用内完整下载的最近阅读与页码；可选择自动删除超出容量的本地下载 |
| JM 号查询 | 输入编号或支持的链接，查询标题、封面、作者、标签和章节等资料 |
| 归档下载 | 新下载器支持暂停、继续和完成导入，并兼容升级前的旧系统下载任务 |

书签未读记录不设 21 条持久化上限，界面超过 20 条显示 `20+`；本地追更仍保留 21 条明细。阅读队列的自动删除默认关闭，开启后会永久删除被淘汰漫画的本地下载目录。详见[使用指南](docs/usage.md)。

## 当前版本

以下为 2026-09-11 核对的源码配置，不代表发布页或手机已经安装的版本。

| 项目 | 当前值 |
| --- | --- |
| Android 最低版本 | Android 6.0（API 23） |
| 本机维护变体 | `appReleaseDebug` |
| Debug applicationId | `com.ehviewer.manbo.debug` |
| versionName / Debug versionCode | `151 / 151` |
| 数据库 schema | `13` |

历史 v151 APK 的 versionName 仍为 `2.0.2.26`；数字名称修改只完成了编译验证，尚未重新组装。历史 APK 信息与验证限制见[变更记录](说明.md)。

## 开始使用与开发

- 使用者：从发布页获取所需 APK，按照[使用指南](docs/usage.md)添加追更、管理书签和阅读队列。
- 开发者：先阅读[开发与构建](docs/build.md)，完成 JDK、SDK 和固定签名预检，再执行 `:app:assembleAppReleaseDebug`。
- 维护更新算法：[扫描与未读机制](docs/scanning.md)说明分页、checkpoint、全局游标和提交边界。
- 排查问题：[常见问题](docs/troubleshooting.md)区分基线、计数、网络、安装和测试环境问题。

## 来源与许可

本分支基于 [xiaojieonly/Ehviewer_CN_SXJ](https://github.com/xiaojieonly/Ehviewer_CN_SXJ)，项目起源于 seven332/EhViewer。最近一次本地同步基线为上游标签 `2.0.2.4`，不表示上游最新版本。

项目许可证见 [LICENSE](LICENSE)，版权声明见 [NOTICE](NOTICE)。第三方组件保留各自许可证；上游公告、鸣谢及支持资料见 [feedauthor 文档索引](feedauthor/README.md)。

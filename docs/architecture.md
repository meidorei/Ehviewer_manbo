# 架构与数据

[文档索引](README.md) · [扫描机制](scanning.md) · [开发与构建](build.md)

## 工程结构

主应用以 Java 为主、Kotlin 为辅，使用 Activity、Stage/Scene 和 Service，数据库为 SQLite/GreenDAO。局部维护应沿用现有结构。

| 目录 | 用途 |
| --- | --- |
| `app/src/main/java/com/hippo/ehviewer` | 主应用代码 |
| `app/src/main/res` | XML 布局、主题、多语言与图标 |
| `app/src/main/cpp` | CMake 与第三方原生组件 |
| `app/src/test/java`、`app/src/test/resources` | 单元测试及解析样本 |
| `daogenerator` | 旧 GreenDAO 生成器，仅供参考 |
| `tools`、`exports`、`artifacts` | 辅助脚本、导出样本、历史 APK |
| `fastlane`、`feedauthor` | 商店描述、更新清单及上游资料 |
| `.github` | CI 配置 |

## 代码入口

下表路径相对于 `app/src/main/java/com/hippo/ehviewer/`。

| 功能 | 入口 |
| --- | --- |
| 初始化、主界面 | `EhApplication.java`、`ui/MainActivity.java`、`ui/splash/SplashActivity.java` |
| 网络与分发 | `client/EhEngine.java`、`client/EhClient.java` |
| URL、解析与模型 | `client/data`、`client/parser` |
| 追更与书签刷新 | `subscription/LocalUpdateService.java`、`subscription/LocalFollowRepository.java` |
| 边界与基线 | `subscription/SubscriptionRepository.java`、`LocalBaselineQueue.java`、`LocalGlobalCursorStore.java`（后两者同包） |
| 抽屉及列表 | `ui/scene/gallery/list` |
| 数据库 | `EhDB.java`、`dao`、`subscription/SubscriptionSchema.java` |
| 阅读队列 | `reader/ReadingQueueRepository.java`、`reader/ReadingQueueManager.java`、`ui/scene/reading/ReadingQueueScene.java` |
| 下载与归档 | `download`，包括 `ArchiverDownloader.kt`、`ArchiverDownloadService.kt` 及完成处理器 |
| JM | `jm` |
| IGNEOUS | `preference/IdentityCookiePreference.java`、`client/IgneousUtils.java` |
| 应用更新 | `updater` |

UI 发起刷新，Service 串行请求与计算，Repository 提交数据库，抽屉与列表再读取状态。可测试策略优先放在现有纯 Java 类中。

## 数据库与范围

当前 `DaoMaster.SCHEMA_VERSION = 13`。追更扩展表由 `SubscriptionSchema` 管理，阅读队列表由 `ReadingQueueSchema` 管理；不能把历史 schema 11 的描述当作当前版本。

| 表 | 用途 |
| --- | --- |
| `LOCAL_FOLLOW_TAG` | 设备追更标签 |
| `QUICK_SEARCH` | 书签查询主体 |
| `FEED_CHECKPOINT` | 同步和打开边界及 previous/current |
| `LOCAL_UPDATE_STATE` | 计数、状态、检查时间、错误 |
| `LOCAL_UNREAD_GALLERY` | 各来源未读 GID |
| `LOCAL_GLOBAL_CURSOR` | 全局扫描停止位置 |
| `LOCAL_REFRESH_JOB`、`LOCAL_REFRESH_META` | 任务快照与结果时间 |
| `LOCAL_BASELINE_QUEUE` | 自动基线队列 |
| `READING_QUEUE` | 最近阅读及进度 |
| `SUBSCRIPTION_TAG_CACHE`、`SUBSCRIPTION_TAG_UPDATE_STATE` | 旧服务器聚合扫描数据 |

追更、书签主体、计数和未读记录为设备全局；同步 checkpoint 与全局游标按账号和 E/EX host 区分；打开边界使用 `shared` 跨账号共享。切换账号不等于换成一套完全隔离的未读数据。

`FEED_CHECKPOINT` 的唯一键为账号、来源类型、来源键及查询签名。`advanceCheckpoint()` 将旧 current 移至 previous；`establishCheckpoint()` 只细化基线，不制造打开历史。

删除关联状态依赖仓库方法，新增删除入口必须同时清理主记录、未读、checkpoint 和基线。数据库变更必须核对 `EhDB.DBOpenHelper.onUpgrade`、两套扩展 schema、备份恢复、存量升级及相应测试。

旧 `daogenerator` 仍声明 schema 6，生成任务会先删除 DAO 目录，禁止直接执行。

## 书签手动重置状态

`FEED_CHECKPOINT` 的 `BOOKMARK_RESET` 逻辑来源保存按书签 ID、查询签名隔离的重置下限，账号键固定 `shared`；数据库 schema 仍为 13，无新增表或字段。`SubscriptionRepository` 仅在扫描读取 `BOOKMARK_SYNC` 时应用下限；写入时读取原始 checkpoint，保存实际内容顶部和 previous，同秒合并已知 GID，允许普通自动基线细化到较早的真实顶部。原检查时间及独立阅读边界在重置时保持不变，联动已读继续使用已保存的实际同步顶部。`LocalBaselineResetPolicy` 负责纯 Java 边界合并，`LocalUpdateGate` 串行化更新与重置，`LocalFollowRepository` 在事务中完成批量写入和队列/游标失效。

重置沿用 `EhDB` 的书签操作锁顺序（先书签锁，再事务），避免与删除、改名和导入交错。删除书签和查询签名变化同时清理重置记录，改名不清理。现有数据库导出仅复制书签主体等既有数据，不导出同步 checkpoint、未读或重置记录；导入新增书签重新建立基线，保留在本机的已有书签状态不变。该功能不改变备份范围或升级/恢复流程。

## 追更手动重置状态

追更使用同表中的 `shared / LOCAL_FOLLOW_RESET / 标准化标签 / 固定中文查询签名`，schema 保持 13。`LocalBaselineResetPolicy` 由书签原策略改名而来，复用时间和同秒 GID 合并规则；追更、书签分别持有重置记录、基线队列与共享游标。`LocalFollowRepository.resetFollowBaselines()` 在同一数据库事务内读取追更列表并写入下限；`LocalUpdateGate` 防止重置与更新交错，设置页两个入口共用确认和执行反馈。

追更扫描读取有效边界，写入继续使用原始内容历史；不改变阅读队列、打开分割线、未读保留量及检查时间。删除和替换导入通过统一清理方法删除被移除标签的重置状态，保留标签的重置状态继续有效。现有备份范围不变，重置状态与同步 checkpoint 一样不导出；新增恢复标签继续按原流程初始化。

## 下载、JM 与登录

新归档任务的恢复信息存于 `Settings` 的 SharedPreferences。字段调整必须覆盖暂停、继续、进程重启和完成导入。无保存 URL 的旧系统任务由 `LegacyArchiverDownloadCompleter` 处理，新下载器跳过而不是清理它们。

JM 使用独立内存 CookieJar，清除 EH 到 WebView 的 Cookie 同步拦截器并禁用缓存；不要为 JM 错误改动 EH 登录数据。API、网页选择器和 CDN 属于外部依赖，源码中存在这些实现不代表服务当前可用。

IGNEOUS 刷新使用应用共享网络配置，删除旧值后请求新值；空值和 `mystery` 都不可用。该操作无法保证解决代理或账号权限问题。

## 上游同步

最近一次同步标签为 `2.0.2.4`，提交 `e99590da6e69e3a8a37b847a8e3df3eb8f5cb22a`，本地合并提交为 `e4fac386`。

后续合并须保留 manbo 身份与更新入口、固定 Debug 签名、追更与书签规则、阅读队列、JM、IGNEOUS 和旧归档任务兼容。请求编号中 `METHOD_SCAN_SUBSCRIPTIONS = 29`、`METHOD_GET_EDIT_COMMENT = 30`；修改分发后运行 `EhClientMethodTest`。

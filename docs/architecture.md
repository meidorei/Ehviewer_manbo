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

主页书签标签提示使用独立的 `subscription/BookmarkTagMarkerPolicy`，复用现有精确查询解析和标准化规则，将书签快照编译为只读标签规则。组合书签按正向标签逐项匹配，其他过滤条件不参与，语言采用普通浏览的自动中文策略；不复用固定中文的更新资格校验或完整条件匹配。规则和值均复制，不保留可变 DAO 实体。

`Match.displayLabel()` 统一标签标记的名称选择与翻译回退，通过注入的本地词库查询接口保持策略可独立测试。单标签书签名经现有标准化后等于命中标签时，和组合标签一样按翻译开关查词库；对“中文分类:英文标签”的旧部分译名，同时确认分类等于词库的命名空间译名、标签值等于原始标签，再用原始标签重查，兼容半角/全角冒号。自定义名称、完整译名及其他标签名称保持原样。译文为空或仍为原标签时保留原显示名称，不写回 DAO、不改搜索条件或匹配结果。卡片每次绑定读取当前已加载的词库实例，避免沿用页面创建时的空值或旧引用；不触发词库下载或其他网络请求。

`GalleryListScene` 在进入主页、书签变更、返回页面及自动中文设置变化时重建快照；绑定卡片通过 `matchGallery()` 合并 `GalleryInfo.tgList`（HTML 已解析的可见标签）和 `simpleTags`（完整/API 标签），沿用去重及语言判断，不查询数据库、不补查网络。关闭页数显示、不触发 gdata 时也能使用网页现有标签，不将可见标签写入代表完整元数据的 `simpleTags`。只有 HOME 来源且普通主页 URL 时显示，复用和回收卡片均清除旧提示。

列表与网格共用 `widget/BookmarkTagGroup`，适配器直接传名称列表，不通过分隔符拆标签。每个名称创建或复用单行、12sp 圆角框，使用独立 `bookmarkTagBackgroundColor` 的浅色/深色/黑色主题底色，边框及文字沿用主描边与主文字色。纯 Java `BookmarkTagLayout` 负责两排流式排列和 `+N` 的空间预留；长标签宽度限定在父容器内，隐藏项从绘制与子控件无障碍树移除，容器保留完整名单，支持 RTL 布局。无 schema、checkpoint、未读、网络请求或备份范围变化。

列表卡片的评分和下载/收藏图标、语言及页数共用横向行并垂直居中；评分保留自身宽度，右侧信息容器以 `0dp + layout_weight=1` 分配剩余宽度并靠末端排列，单行文字沿用省略样式。标签保持在上传者下方，分类及日期保持在底部；沿用原控件 ID 和绑定逻辑。

书签与追更的全局入口统一调用 `LocalUpdateService.startGlobal()`；`UnifiedGlobalScan` 负责无数据库副作用的共享分页、独立覆盖判断、双来源匹配、分页循环/时间戳验证及中断控制。`LOCAL_REFRESH_JOB.JOB_TYPE` 新增文本值 `ALL`，方法仍为 `GLOBAL`；沿用已有字段及单任务互斥，无表结构、schema 版本或备份范围变更。联合任务结束将同一结果与状态在事务内写入两类历史，全部成功才写两类成功时间。旧 `FOLLOW` / `BOOKMARK` 暂停快照通过兼容入口恢复原范围，新联合任务恢复时重扫。独立游标、重置记录、未读保留量及阅读边界保持原语义。

## 数据库与范围

当前 `DaoMaster.SCHEMA_VERSION = 14`。追更扩展表由 `SubscriptionSchema` 管理，阅读队列表由 `ReadingQueueSchema` 管理；`GalleryTagsSchema` 负责 LOCATION 列的增量迁移。不能把历史 schema 11、13 或上游 schema 8 的描述当作当前版本。

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

`FEED_CHECKPOINT` 的 `BOOKMARK_RESET` 逻辑来源保存按书签 ID、查询签名隔离的重置下限，账号键固定 `shared`；重置功能不新增表或字段，当前数据库 schema 为 14。`SubscriptionRepository` 仅在扫描读取 `BOOKMARK_SYNC` 时应用下限；写入时读取原始 checkpoint，保存实际内容顶部和 previous，同秒合并已知 GID，允许普通自动基线细化到较早的真实顶部。原检查时间及独立阅读边界在重置时保持不变，联动已读继续使用已保存的实际同步顶部。`LocalBaselineResetPolicy` 负责纯 Java 边界合并，`LocalUpdateGate` 串行化更新与重置，`LocalFollowRepository` 在事务中完成批量写入和队列/游标失效。

重置沿用 `EhDB` 的书签操作锁顺序（先书签锁，再事务），避免与删除、改名和导入交错。删除书签和查询签名变化同时清理重置记录，改名不清理。现有数据库导出仅复制书签主体等既有数据，不导出同步 checkpoint、未读或重置记录；导入新增书签重新建立基线，保留在本机的已有书签状态不变。该功能不改变备份范围或升级/恢复流程。

## 追更手动重置状态

追更使用同表中的 `shared / LOCAL_FOLLOW_RESET / 标准化标签 / 固定中文查询签名`，重置功能不新增 schema 变更，当前 schema 为 14。`LocalBaselineResetPolicy` 由书签原策略改名而来，复用时间和同秒 GID 合并规则；追更、书签分别持有重置记录、基线队列与共享游标。`LocalFollowRepository.resetFollowBaselines()` 在同一数据库事务内读取追更列表并写入下限；`LocalUpdateGate` 防止重置与更新交错，设置页两个入口共用确认和执行反馈。

追更扫描读取有效边界，写入继续使用原始内容历史；不改变阅读队列、打开分割线、未读保留量及检查时间。删除和替换导入通过统一清理方法删除被移除标签的重置状态，保留标签的重置状态继续有效。现有备份范围不变，重置状态与同步 checkpoint 一样不导出；新增恢复标签继续按原流程初始化。

## 下载、JM 与登录

新归档任务的恢复信息存于 `Settings` 的 SharedPreferences。字段调整必须覆盖暂停、继续、进程重启和完成导入。无保存 URL 的旧系统任务由 `LegacyArchiverDownloadCompleter` 处理，新下载器跳过而不是清理它们。

JM 使用独立内存 CookieJar，清除 EH 到 WebView 的 Cookie 同步拦截器并禁用缓存；不要为 JM 错误改动 EH 登录数据。API、网页选择器和 CDN 属于外部依赖，源码中存在这些实现不代表服务当前可用。

IGNEOUS 刷新使用应用共享网络配置，删除旧值后请求新值；空值和 `mystery` 都不可用。该操作无法保证解决代理或账号权限问题。

## 上游同步

2026-10-11 源码从上次 `2.0.2.4`（`e99590da6e69e3a8a37b847a8e3df3eb8f5cb22a`，本地合并提交 `e4fac386`）迁移到 `2.0.2.5`（`c16c1d57`）。本次尚未创建合并提交，验证记录见根目录说明。

上游新增 LOCATION 的 schema 8 不能覆盖本地 schema 13。新库由 `GalleryTagsDao` 建立含 LOCATION 的表；旧库升级沿用原有 case 7–12，再由 case 13 调用 `GalleryTagsSchema.upgradeToV14()`，检查列存在后追加。`EhDB.importDB()` 使用同一升级入口，兼容已含 LOCATION 的上游备份；不重建追更、书签、阅读队列或下载表。

下载页使用上游拆出的导入、批量操作、搜索、分页和选择控制器及 Kotlin 适配器，保留本地 `DownloadGalleryActivity` 连续阅读、队列快照和黄色已观看标记。点击和多选阅读共用本地入口，返回后刷新连续阅读经过的下载进度并使用分页控制器的同一缓存。相册复制到下载目录，保留 `local-album:` 导入标识；和外部归档一样，不进入本地自动删除阅读队列。上游阅读刷新率应用于普通和下载阅读器，Android 11 以下不显示设置。

保留本地身份、更新清单和构建配置，未迁移上游 README 下载链接及历史公告、版本号和生成器 class 二进制；已删除的 `GalleryListTagsSyncTask` 不恢复。生成器源码只追加 LOCATION 定义，仍不得直接运行旧生成任务。

后续合并须保留 manbo 身份与更新入口、固定 Debug 签名、追更与书签规则、阅读队列、JM、IGNEOUS 和旧归档任务兼容。请求编号中 `METHOD_SCAN_SUBSCRIPTIONS = 29`、`METHOD_GET_EDIT_COMMENT = 30`；修改分发后运行 `EhClientMethodTest`。

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
| `tools`、`artifacts` | 辅助脚本、保留的公开构建记录与历史 APK；隐私产物按[本地隐私规则](build.md#本地隐私产物)隔离 |
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
| 追更更新漫画页 | `subscription/LocalFollowUpdatesRepository.java`、`LocalFollowUpdateCodec.java`（同包）、`ui/scene/gallery/list/LocalFollowUpdatesScene.java` |
| 边界与基线 | `subscription/SubscriptionRepository.java`、`LocalBaselineQueue.java`、`LocalGlobalCursorStore.java`（后两者同包） |
| 抽屉及列表 | `ui/scene/gallery/list` |
| 在线书签标签标记 | `subscription/BookmarkTagMarkerPolicy.java`、`ui/scene/gallery/list/BookmarkTagMarkerLabels.java`、`client/HomeBookmarkTagSupplement.java` |
| 数据库 | `EhDB.java`、`dao`、`subscription/SubscriptionSchema.java` |
| 阅读队列 | `reader/ReadingQueueRepository.java`、`reader/ReadingQueueManager.java`、`ui/scene/reading/ReadingQueueScene.java` |
| 下载列表与连续阅读 | `ui/scene/download/DownloadsScene.java`、`ui/scene/download/part`、`reader/DownloadReadingQueue.java`、`ui/GalleryActivity.java` |
| 下载与归档 | `download`，包括 `ArchiverDownloader.kt`、`ArchiverDownloadService.kt` 及完成处理器 |
| JM | `jm` |
| IGNEOUS | `preference/IdentityCookiePreference.java`、`client/IgneousUtils.java` |
| 应用更新 | `updater` |

UI 发起刷新，Service 串行请求与计算，Repository 提交数据库，抽屉与列表再读取状态。可测试策略优先放在现有纯 Java 类中。

在线列表书签标签提示使用独立的 `subscription/BookmarkTagMarkerPolicy`，复用现有精确查询解析和标准化规则，将书签快照编译为只读标签规则。组合书签按正向标签逐项匹配，其他过滤条件不参与，语言采用普通浏览的自动中文策略；不复用固定中文的更新资格校验或完整条件匹配。规则和值均复制，不保留可变 DAO 实体。BookmarkQueryNormalizer 的分类简写解析通过显式参数仅供显示标记开启，覆盖 a、c/char、cos、f、g/circle、l/lang、loc、m、x、o、p/series、r；单标签、组合查询和标签形名称翻译判断共用该入口。原有无参数入口保持原解析规则，书签诊断和更新扫描不启用新增别名；不改写书签主体或查询签名。

`Match.displayLabel()` 统一标签标记的名称选择与翻译回退，通过注入的本地词库查询接口保持策略可独立测试。单标签书签名经现有标准化后等于命中标签时，和组合标签一样按翻译开关查词库；对“中文分类:英文标签”的旧部分译名，同时确认分类等于词库的命名空间译名、标签值等于原始标签，再用原始标签重查，兼容半角/全角冒号。自定义名称、完整译名及其他标签名称保持原样。译文为空或仍为原标签时保留原显示名称，不写回 DAO、不改搜索条件或匹配结果。卡片每次绑定读取当前已加载的词库实例，避免沿用页面创建时的空值或旧引用；不触发词库下载或其他网络请求。

`GalleryListScene` 的全部在线模式和 `FavoritesScene` 的云收藏在进入、书签变更、返回页面及自动中文设置变化时重建快照；两者共用 `BookmarkTagMarkerLabels` 进行名称和翻译显示，绑定卡片通过 `matchGallery()` 合并 `GalleryInfo.tgList`（HTML 已解析的可见标签）和 `simpleTags`（完整/API 标签），沿用去重及语言判断，绑定时不查询数据库或发网络请求。存在可编译的书签规则时，普通与 EX 分页、图片搜索、云收藏及云收藏修改返回列表的请求显式携带完整标签补取开关；EhClient 沿用原方法编号，旧参数调用与 EhEngine 原重载默认关闭，更新扫描不启用新增补取。EhEngine 在原列表处理完成后通过 HomeBookmarkTagSupplement 仅为 simpleTags 为 null 的条目补取现有 gdata（每批最多 25 项），复用已有完整数据，关闭页数显示也可补取。补取使用仅含 GID/token 的独立模型，成功仅复制标签，不覆盖 HTML 标题、时间、缩略图及其他字段；网络/解析失败保留 HTML 列表，取消和中断继续传播。未返回标签的条目仍可使用网页标签，不将可见标签冒充完整 simpleTags。gdata 的标签集合不覆盖详情页全部弱标签，不能把 API 补取成功当作详情标签完整性保证。下载、历史、本地收藏和阅读队列不显示、不新增补取；复用和回收卡片均清除旧提示。

云收藏保留旧 GalleryAdapter 的缩略图和选择交互，专用卡片布局复用首页的 BookmarkTagGroup；本地收藏仍使用原布局。云收藏与本地收藏采用不同 viewType 隔离复用池，切换分类时刷新规则和卡片，避免残留标签。GalleryHolder 的标签控件可空，其他旧适配器默认返回空标记。

列表与网格共用 `widget/BookmarkTagGroup`，适配器直接传名称列表，不通过分隔符拆标签。每个名称创建或复用单行、12sp 圆角框，使用独立 `bookmarkTagBackgroundColor` 的浅色/深色/黑色主题底色，边框及文字沿用主描边与主文字色。纯 Java `BookmarkTagLayout` 负责两排流式排列和 `+N` 的空间预留；长标签宽度限定在父容器内，隐藏项从绘制与子控件无障碍树移除，容器保留完整名单，支持 RTL 布局。布局本身无 schema、checkpoint、未读或备份范围变化；完整标签补取在列表请求层执行，不在布局或绑定时执行。

列表卡片的评分和下载/收藏图标、语言及页数共用横向行并垂直居中；评分保留自身宽度，右侧信息容器以 `0dp + layout_weight=1` 分配剩余宽度并靠末端排列，单行文字沿用省略样式。标签保持在上传者下方，分类及日期保持在底部；沿用原控件 ID 和绑定逻辑。

书签与追更的全局入口统一调用 `LocalUpdateService.startGlobal()`；`UnifiedGlobalScan` 负责无数据库副作用的共享分页、独立覆盖判断、双来源匹配、分页循环/时间戳验证及中断控制。`LOCAL_REFRESH_JOB.JOB_TYPE` 新增文本值 `ALL`，方法仍为 `GLOBAL`；沿用已有字段及单任务互斥，无表结构、schema 版本或备份范围变更。联合任务结束将同一结果与状态在事务内写入两类历史，全部成功才写两类成功时间。旧 `FOLLOW` / `BOOKMARK` 全局任务恢复时也使用 `UnifiedGlobalScan`，只传入原任务对应的来源。全局任务从第一页重扫，逐项任务复用相同的重试与失败记录逻辑。独立游标、重置记录、未读保留量及阅读边界保持原语义。

## 数据库与范围

当前 `DaoMaster.SCHEMA_VERSION = 15`。追更扩展表由 `SubscriptionSchema` 管理，阅读队列表由 `ReadingQueueSchema` 管理；`GalleryTagsSchema` 负责 LOCATION 列的增量迁移。不能把历史 schema 11、13、14 或上游 schema 8 的描述当作当前版本。

| 表 | 用途 |
| --- | --- |
| `LOCAL_FOLLOW_TAG` | 设备追更标签 |
| `QUICK_SEARCH` | 书签查询主体 |
| `FEED_CHECKPOINT` | 同步和打开边界及 previous/current |
| `LOCAL_UPDATE_STATE` | 计数、状态、检查时间、错误 |
| `LOCAL_UNREAD_GALLERY` | 各来源未读 GID |
| `LOCAL_FOLLOW_UPDATES` | 最近 1000 本追更新增漫画的展示快照及独立已读状态 |
| `LOCAL_GLOBAL_CURSOR` | 全局扫描停止位置 |
| `LOCAL_REFRESH_JOB`、`LOCAL_REFRESH_META` | 任务快照与结果时间 |
| `LOCAL_BASELINE_QUEUE` | 自动基线队列 |
| `READING_QUEUE` | 最近阅读及进度 |
| `SUBSCRIPTION_TAG_CACHE`、`SUBSCRIPTION_TAG_UPDATE_STATE` | 旧服务器聚合扫描数据 |

追更、书签主体、计数和未读记录为设备全局；同步 checkpoint 与全局游标按账号和 E/EX host 区分；打开边界使用 `shared` 跨账号共享。切换账号不等于换成一套完全隔离的未读数据。

`FEED_CHECKPOINT` 的唯一键为账号、来源类型、来源键及查询签名。`advanceCheckpoint()` 将旧 current 移至 previous；`establishCheckpoint()` 只细化基线，不制造打开历史。

边界精度通过 `FeedBoundaryCodec` 写入现有 GID 文本列的 `@fb1:` 标头，时间字段仍为 Unix 秒，schema 保持 15；旧纯 GID 列表继续可读。显示使用独立 `FeedMarkerPolicy` 的新增/旧内容/不确定判断，更新计数仍走原严格扫描策略。`FeedTimestampSupplement` 在普通浏览请求中按需补取顶部与边界分钟，仅发布成功取得的时间元数据；页面等待边界加载并使用请求来源和 helper 身份隔离迟到响应。GalleryInfo 的复制、JSON、CSV 和带标记的 Parcelable 尾部保存显式精度。

删除关联状态依赖仓库方法，新增删除入口必须同时清理主记录、未读、checkpoint 和基线。数据库变更必须核对 `EhDB.DBOpenHelper.onUpgrade`、两套扩展 schema、备份恢复、存量升级及相应测试。

旧 `daogenerator` 仍声明 schema 6，生成任务会先删除 DAO 目录，禁止直接执行。

`LOCAL_REFRESH_META` 的 `resume_queue_signature` 保存逐项任务的队列签名，用来核对暂停前后的来源、成员和顺序。`unread_last_rowid` 保存未读记录分配过的最大行号，避免删除后的行号被新作品复用。两者随各自的任务或未读事务写入，沿用本机同步状态的备份范围。旧库没有这些键时，首次恢复从头复查，新增行号从已有记录与当前毫秒时间的较大值之后分配。

## 追更更新漫画页

`LocalFollowUpdatesRepository` 同步提供事务内新增记录、全部/未读查询及 `markReadIfExists(gid)`；页面和详情页通过既有 `SubscriptionRepository` 单线程执行器调用。`LocalFollowRepository` 将同一 delta 的完整 `GalleryInfo` 传入原提交事务，先保存更新快照再裁剪原 21 条未读明细；书签传入空列表。原扫描边界、全局游标与计数策略不变。

`LOCAL_FOLLOW_UPDATES` 以 GID 为主键，保存 `FIRST_SEEN_AT`、`READ_AT`、`GALLERY_JSON`、`MATCHED_TAGS_JSON`，并建立首次发现时间/GID 排序索引。JSON 由专用 codec 在 Java 中处理，标签保存发现时的快照；不依赖 SQLite JSON 扩展，不关联当前追更表进行级联删除。重复命中只合并标签及更新展示元数据，保留首次时间和已读。记录沿用设备全局范围；详情页在完整详情绑定并恢复到前台时设置已有记录的已读时间，失败和仅有列表标题的状态不写入。

`LocalFollowUpdatesScene` 使用 `ToolbarScene`、后台查询及本地分页，每次将显示范围增加 50 后重读有序前缀，最多 1000 本。按 GID、位置和顶部偏移恢复浏览锚点，筛选与加载量随页面状态保存；返回及更新任务结束重新读取。页面不发起扫描，不显示书签命中标记，而是显示记录中的追更来源标签。

schema 14→15 只追加该表和索引；`EhDB` 的应用升级和导入升级使用同一入口，新库由 `SubscriptionSchema.createTables()` 建立。旧未读不具备 token/元数据，因此新表初始为空、不回填；导出库包含空表，导入不复制或覆盖本机更新历史，备份范围保持不变。历史淘汰只删除本表记录，不删除下载。淘汰后若同一 GID 再被其他追更边界判为新增，可重新进入记录。

## 书签手动重置状态

`FEED_CHECKPOINT` 的 `BOOKMARK_RESET` 逻辑来源保存按书签 ID、查询签名隔离的重置下限，账号键固定 `shared`；重置功能不新增表或字段，当前数据库 schema 为 15。`SubscriptionRepository` 仅在扫描读取 `BOOKMARK_SYNC` 时应用下限；写入时读取原始 checkpoint，保存实际内容顶部和 previous，同秒合并已知 GID，允许普通自动基线细化到较早的真实顶部。原检查时间及独立阅读边界在重置时保持不变，联动已读继续使用已保存的实际同步顶部。`LocalBaselineResetPolicy` 负责纯 Java 边界合并，`LocalUpdateGate` 串行化更新与重置，`LocalFollowRepository` 在事务中完成批量写入和队列/游标失效。

重置沿用 `EhDB` 的书签操作锁顺序（先书签锁，再事务），避免与删除、改名和导入交错。删除书签和查询签名变化同时清理重置记录，改名不清理。现有数据库导出仅复制书签主体等既有数据，不导出同步 checkpoint、未读或重置记录；导入新增书签重新建立基线，保留在本机的已有书签状态不变。该功能不改变备份范围或升级/恢复流程。

## 追更手动重置状态

追更使用同表中的 `shared / LOCAL_FOLLOW_RESET / 标准化标签 / 固定中文查询签名`，重置功能不新增 schema 变更，当前 schema 为 15。`LocalBaselineResetPolicy` 由书签原策略改名而来，复用时间和同秒 GID 合并规则；追更、书签分别持有重置记录、基线队列与共享游标。`LocalFollowRepository.resetFollowBaselines()` 在同一数据库事务内读取追更列表，写入扫描下限并推进 `LOCAL_FOLLOW_OPEN`，删除未读明细、将计数设为精确零并清除旧错误；保留原检查时间，缺失状态以零检查时间补齐。`LocalUpdateGate` 防止重置与更新交错，设置页两个入口共用确认和执行反馈。

追更扫描读取有效边界，写入继续使用原始内容历史；批量重置同时更新阅读分割线，不改变阅读队列、独立追更更新漫画记录、未读保留量及检查时间。后续打开提交在事务中合并现有阅读边界、重置下限和页面顶部，避免边界倒退；书签打开规则不变。作者进入时未读名单非空仍按最后一个命中 GID 显示，名单为空则读取保存的阅读边界，按时间显示；无新内容时线在第一本上方。加载与恢复保留本次进入的名单和边界，只启用一种定位方式。删除和替换导入通过统一清理方法删除被移除标签的重置状态，保留标签的重置状态继续有效。现有备份范围不变，重置状态与同步 checkpoint 一样不导出；新增恢复标签继续按原流程初始化。

## 下载、JM 与登录

新归档任务的恢复信息存于 `Settings` 的 SharedPreferences。字段调整必须覆盖暂停、继续、进程重启和完成导入。无保存 URL 的旧系统任务由 `LegacyArchiverDownloadCompleter` 处理，新下载器跳过而不是清理它们。

新任务的 ZIP 与解压目录使用 GID/taskId 唯一命名，Settings 保存文件归属和待导入阶段；旧唯一标题文件继续使用，旧共享标题文件保留并要求独立重下。新旧归档共用全页导入执行器，按原图片文件名稳定排序；完整复制到临时目标后备份并替换同页所有旧扩展，失败尝试恢复旧页。全部页面及数据库提交成功后才通知完成和清理恢复记录；失败保留 ZIP，可重试本地导入。导入占用期间同 GID 普通下载等待，任务所有权校验防止迟到回调影响新任务。旧同名冲突集合先整组保存，避免迁移中断后误认共享文件归属。

数据库导入沿用 EhDB 的同步锁，在读取当前映射后仅填补缺失或空下载目录映射，保留本机所有非空映射；不探测 SAF、不移动目录，不改变 schema 或已有备份范围。

JM 使用独立内存 CookieJar，清除 EH 到 WebView 的 Cookie 同步拦截器并禁用缓存；不要为 JM 错误改动 EH 登录数据。API、网页选择器和 CDN 属于外部依赖，源码中存在这些实现不代表服务当前可用。

IGNEOUS 刷新使用应用共享网络配置，删除旧值后请求新值；空值和 `mystery` 都不可用。该操作无法保证解决代理或账号权限问题。

## 上游同步

2026-11-09 增量移植 `2.0.2.7`（`b1eab7b4d7cced55490584e71bc3bf3bfc0d925d`）相对 `2.0.2.6` 的下载恢复与 JNI 修复，未创建上游合并提交。恢复入口依次解析文本、CBOR 和 ComicInfo，仅提取恢复身份；DownloadRestorePolicy 控制每批 25 项、失败继续、重复跳过及取消传播。GalleryApiParser 的跳过不可用项选项仅用于恢复，原默认入口和更新扫描继续严格解析。CBOR 的 tag 嵌套同样计入深度上限。既有本地导入筛选保持；未引入尚未使用的后台任务骨架及目录时间工具。schema 14、请求编号和本地身份保持不变。

此前 2026-10-25 已移植上游 `2.0.2.6`（`dfe356244d234c91f2bf65af60903f80b407a838`）相对 `2.0.2.5`（`c16c1d579808bf77aba3d61f0ee5a9d34273a28b`）的全部适用功能更新，不代表上游当前最新版本。该次为局部移植，未创建上游合并提交；后续同步应同时核对上述两次移植范围和本地实际代码，不能仅凭 Git 共同祖先重复应用。本次保留本地版本、应用更新清单及发布链接。各阶段验证记录见根目录[变更记录](../说明.md)。

2026-10-25 的移植包含：`EhHosts` 将 `ehgt.org` 的三个内置 IPv4 更新为 `185.132.132.79`、`62.112.8.152`、`185.132.132.76`，沿用原 IPv6、自定义 Hosts 优先级、EX 和 DoH 逻辑；下载页新增“本地导入”筛选，按非空 `DownloadInfo.archiveUri` 选择外部归档和 `local-album:` 相册，在现有列表快照上筛选并保留主线程删除校验、分页和连续阅读保护。不增加字段、迁移或网络请求。

Git 历史中的上次同步为：2026-10-11 从 `2.0.2.4`（`e99590da6e69e3a8a37b847a8e3df3eb8f5cb22a`，本地合并提交 `e4fac386`）同步至 `2.0.2.5`，形成以 `c16c1d57` 为第二父提交的合并提交 `57c5788c`，随后通过 `a73f92af` 合入；不能根据后一提交的旧分支名将基线误判为 `2.0.2.4`。

上游新增 LOCATION 的 schema 8 不能覆盖同步前的本地 schema 13；该次迁移升级至 schema 14（当前为 15）。新库由 `GalleryTagsDao` 建立含 LOCATION 的表；旧库升级沿用原有 case 7–12，再由 case 13 调用 `GalleryTagsSchema.upgradeToV14()`，检查列存在后追加。`EhDB.importDB()` 使用同一升级入口，兼容已含 LOCATION 的上游备份；不重建追更、书签、阅读队列或下载表。

下载页分页由纯 Java DownloadPaginationPolicy 统一计算：超过 500 本且开启分页时才分页，页码限制在当前有效范围，适配器数量与双向位置映射共用规则；其他页的条目不映射为当前页位置。下载增删（包括批量删除）先同步筛选副本并修正分页，再全量通知适配器，避免满页补位与单项删除通知冲突。搜索/排序执行器持有列表副本，结果在主线程按当前下载 GID 过滤；删除后的进度缓存与迟到回调也排除已移除项目；进度查询合并为单批在途请求，单次执行器完成后释放线程，只使用现存目录，不创建目录记录。无筛选的主线程回调保留管理器原列表，以保持新下载实时显示。连续阅读由主线程处理结束回调和复制候选元数据，后台仅检查文件，启动前主线程再次核对下载记录；销毁后的回调不再提交切换任务。自动删除规则不变，阅读文件的全生命周期保护未在此次改动中引入。

下载页使用上游拆出的导入、批量操作、搜索、分页和选择控制器及 Kotlin 适配器，保留本地 `DownloadGalleryActivity` 连续阅读、队列快照和黄色已观看标记。点击和多选阅读共用本地入口，返回后刷新连续阅读经过的下载进度并使用分页控制器的同一缓存。相册复制到下载目录，保留 `local-album:` 导入标识；和外部归档一样，不进入本地自动删除阅读队列。上游阅读刷新率应用于普通和下载阅读器，Android 11 以下不显示设置。

保留本地身份、更新清单和构建配置，未迁移上游 README 下载链接及历史公告、版本号和生成器 class 二进制；已删除的 `GalleryListTagsSyncTask` 不恢复。生成器源码只追加 LOCATION 定义，仍不得直接运行旧生成任务。

后续合并须保留 manbo 身份与更新入口、固定 Debug 签名、追更与书签规则、阅读队列、JM、IGNEOUS 和旧归档任务兼容。请求编号中 `METHOD_SCAN_SUBSCRIPTIONS = 29`、`METHOD_GET_EDIT_COMMENT = 30`；修改分发后运行 `EhClientMethodTest`。

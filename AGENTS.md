# 项目 Agent 工作规范

## 1. 适用范围与工作方式

- 本文件适用于整个仓库；子目录中的 `AGENTS.md` 可在对应范围内补充或覆盖规则。修改前检查目标目录的适用规则。
- 用中文说明判断、修改、验证和阻塞，直接写明具体原因与影响。
- 计划模式下，向用户提出的问题不得设置超时或自动选择；必须等用户亲自选择后，才能继续依赖该选择的工作。
- 修改前执行 `git status --short` 和针对性 `git diff`，识别已有改动；不得覆盖、回退或清理用户未提交的内容，与任务无关的文件保持原样。
- 按任务范围读取下表文档与相关源码，不必每次通读全部文档。文档中的历史版本、测试和 APK 记录不能作为当次验证结果。

## 2. 按任务读取资料

| 任务 | 开始相关操作前必读 | 主要入口 |
| --- | --- | --- |
| 功能入口、使用说明 | [使用指南](docs/usage.md) | `README.md`、`app/src/main/res` |
| 业务实现、目录定位 | [架构与数据](docs/architecture.md) | `app/src/main/java/com/hippo/ehviewer` |
| 追更、书签、未读、分页、阅读边界、更新任务 | [扫描机制](docs/scanning.md)、[架构与数据](docs/architecture.md) | `subscription`、`ui/scene/gallery/list` |
| 数据库、迁移、备份恢复 | [架构与数据](docs/architecture.md) | `EhDB.java`、`dao`、`subscription/SubscriptionSchema.java`、`reader/ReadingQueueSchema.java` |
| Gradle 检查、编译、测试、APK、安装 | [开发与构建](docs/build.md)，并执行本文第 5–7 节 | `app/build.gradle`、根 `build.gradle`、Gradle Wrapper |
| 构建失败、测试环境、真机异常 | [排错与限制](docs/troubleshooting.md)、[开发与构建](docs/build.md) | 当次命令输出、测试 XML、设备状态 |
| 上游合并 | [架构与数据](docs/architecture.md) 的上游同步部分 | 三方差异、请求分发、数据库及本地扩展 |
| 文档整理、历史查询 | [文档索引](docs/README.md)、[变更记录](说明.md) | `docs/archive`、`feedauthor/README.md` |

表中 Java 包内路径相对于 `app/src/main/java/com/hippo/ehviewer/`。完整目录与代码导航维护在架构文档；构建命令维护在构建文档，避免在本文件重复复制。

## 3. 实现与数据保护

### 遵循现有架构

- 主应用模块为 `app`，以 Java 为主、Kotlin 为辅，沿用 `Activity + Stage/Scene + Service`。局部修改遵循所在包写法，不擅自引入 ViewModel、Room、WorkManager 或 Jetpack Compose 来迁移架构。
- 网络入口为 `client/EhEngine.java`，URL 与模型在 `client/data`，解析在 `client/parser`。改站点协议时同时检查 E-Hentai、ExHentai、登录态、分页及现有解析样本。
- 新增或合并请求时检查 `client/EhClient.java` 的编号唯一性和分发分支，并运行 `EhClientMethodTest`；保留 `METHOD_SCAN_SUBSCRIPTIONS = 29`、`METHOD_GET_EDIT_COMMENT = 30`。
- 修改用户可见文案时，至少同步默认资源与实际涉及的语言资源；项目关闭了 `MissingTranslation` lint，不能依赖 lint 发现遗漏。
- 修改下载、阅读和存储行为时，兼顾 Android 版本差异与已有下载数据。`.gradle`、`.kotlin` 和 `build` 中的缓存、中间产物不作为源码依据。

### 数据库与归档恢复

- 修改表、字段、索引或持久化语义时，同时检查 `EhDB.DBOpenHelper.onUpgrade`、`SubscriptionSchema`、涉及的 `ReadingQueueSchema`、备份恢复、存量升级及相应 schema/迁移测试；不得只改建表 SQL。
- 禁止直接运行 `:daogenerator:executeDaoGenerator`。旧生成器声明 schema 6，会先删除 DAO 目录，而应用 schema 以当前源码为准且已高于 6。如确需生成，必须先核对生成器定义、迁移路径、全部 DAO 定制，并获得用户明确授权。
- 归档恢复字段保存在 `Settings` 的 SharedPreferences。修改字段时覆盖暂停、继续、进程重启恢复、完成导入四条路径。
- 无已保存 URL 的旧系统任务由 `LegacyArchiverDownloadCompleter` 处理；新下载器必须跳过，不能作为无效记录清理。检查 `ArchiverDownloader.kt`、`ArchiverDownloadService.kt` 和 `ArchiverDownloadCompleter.kt` 的配合。

### 追更与书签

- 修改追更、书签或更新任务时，分别回归 `LOCAL_FOLLOW` 和 `BOOKMARK`；两者的保留量、checkpoint 语义、全局扫描降级策略不能因复用代码而合并。
- 书签未读 GID 不设 21 条持久化上限，界面超过 20 显示 `20+`；本地追更保留 21 条明细策略。
- 更新扫描只有完整成功时才能提交 checkpoint、全局游标和未读结果；暂停、停止、网络失败、重复分页 URL、时间戳异常不得提交半截状态。
- 优先复用 `subscription` 中的纯 Java 策略类并补相应测试，不把可测试逻辑直接堆入 Scene、Adapter 或 Service。

### 上游合并

- 以架构文档记录的同步基线为起点核对；历史标签不代表上游最新 HEAD。
- 逐项保留 manbo 应用身份、更新入口、固定 Debug 签名、追更/书签、阅读队列、JM、IGNEOUS 和旧归档任务兼容；禁止直接用上游文件覆盖本地实现。

## 4. 按改动范围验证

| 改动 | 最低验证要求 |
| --- | --- |
| 纯说明文档、Agent 规则 | 检查链接、规则一致性、编码和差异；不递增应用版本、不构建 APK |
| 纯 Java/Kotlin 策略 | 运行对应单元测试 |
| 网络解析 | 运行对应解析器及样本测试 |
| 数据库或持久化 | 运行相关 schema、迁移及策略测试，核对存量数据和备份恢复路径 |
| UI 资源 | 至少完成 `appReleaseDebug` 编译；涉及界面效果时单独说明真机验收情况 |
| APK 交付 | 完成签名预检、版本同步、组装和最终 APK 核验 |
| 安装与交互 | 先完成 APK 核验，再执行 Debug 覆盖安装与对应真机验证 |

- 默认只编译、测试、组装和检查 `appReleaseDebug`；常用任务为 `:app:compileAppReleaseDebugJavaWithJavac`、`:app:testAppReleaseDebugUnitTest`、`:app:assembleAppReleaseDebug`。
- CI 的通用 `assembleDebug` 不改变本机变体和固定签名要求。
- 编译验证不等于组装 APK，组装成功不等于安装或真机验收成功；交付时分别报告实际完成的阶段。
- 新增测试优先纯 Java 逻辑。Android 尺寸、绘图参数或策略换算优先提取为包内纯函数；未经用户明确授权，不为测试修改全局 Gradle 配置或升级依赖。
- 旧 Robolectric 4.2.1 在 Java 21/targetSdk 环境下可能初始化失败，例如 `huh? java 11, need help!` 或 `Package targetSdkVersion=30 > maxSdkVersion=28`。区分环境阻塞和断言失败，测试主体未执行不能记为通过。
- 每次测试后从 `app/build/test-results/testAppReleaseDebugUnitTest/TEST-*.xml` 汇总 `tests`、`failures`、`errors`、`skipped`，确认报告来自当次运行。不得只凭 Gradle 退出码或历史记录声称测试通过。

## 5. Windows Gradle 环境

具体 PowerShell 命令见 [开发与构建](docs/build.md)。以下路径按当前电脑解析，不固定用户名或盘符。每次 Gradle 检查、编译或测试前，先只读探测并核实已有 JDK 21、Android SDK 和主机用户目录，再在实际命令进程显式设置环境；不修改持久环境变量。

| 项目 | 跨电脑配置规则 |
| --- | --- |
| JDK 21 | `<已核实的 JDK 21 根目录>` |
| Android SDK / `ANDROID_HOME` | `<已核实的 Android SDK 根目录>` |
| `USERPROFILE` | `<主机用户目录>` |
| Gradle 启动 | 使用上述 JDK 的 `bin\java.exe` 调用 `gradle\wrapper\gradle-wrapper.jar` 中的 `org.gradle.wrapper.GradleWrapperMain` |
| Gradle 参数 | `--no-daemon --no-problems-report --max-workers=1` |

- 可将现有 `JAVA_HOME`、`ANDROID_HOME`、`USERPROFILE`、`local.properties` 及常见安装目录作为探测线索，但必须核实实际路径、Java 版本和主机账户，不能直接信任沙箱继承值。SDK 常见位置为 `Join-Path $env:LOCALAPPDATA 'Android\Sdk'`，仅作为候选；JDK 不限定厂商或安装目录。不得先裸跑 `gradlew.bat` 再补环境。
- 路径缺失时先探测本机已安装的 JDK 21/SDK，并报告实际缺失项；不得擅自下载依赖工具或改用未经验证的 Java 版本。
- 在限制工作区外 SDK 访问的沙箱中，Gradle 编译、测试或组装从第一次调用就申请沙箱外执行权限，避免 `core-lambda-stubs.jar` 读取被拒绝；非沙箱环境按现有权限运行。
- 正常构建可能超过 60 秒；使用异步执行与后续等待，不因短超时判失败、重复启动构建或重复递增版本。

## 6. 新 APK：签名、版本与产物

### 签名预检

- 固定 Debug 证书对应的 keystore 路径：`<主机用户目录>\.android\debug.keystore`；alias 为 `androiddebugkey`，store/key password 均为 `android`。
- 每次组装前确认 `USERPROFILE = <主机用户目录>`、主机 keystore 存在、`app/build.gradle` 的 Debug signingConfig 仍通过 `USERPROFILE` 指向该文件，并用上述 JDK 的 `keytool.exe` 核对证书。
- 证书 SHA-256 必须为 `af169dcf1d3e44c326b55a1c631c371d3adfe06b8bed34839f03a89cf1f1e9df`（比较时忽略大小写和冒号）。
- 禁止使用或复制 `<沙箱临时用户目录>\.android\debug.keystore` 等临时用户签名。Gradle 自动生成 key 不代表签名可用。
- 换电脑开发时，须经用户授权安全迁移同一份 Debug keystore 到目标主机用户目录，迁移后重新核对证书；私钥不得进入 Git。keystore 缺失、alias/密码错误或证书不符时立即停止构建并报告。未经用户明确授权，不得新建、替换、删除、移动或重新生成 keystore，也不得卸载旧包绕过签名不一致。

### 版本时机

- 需要产出新 APK 的任务：先完成签名预检，再在首次构建前将 Debug `versionCode` 递增一次，并同步推进数字 `versionName`。同一任务的失败重试不得再递增。
- 只进行编译/测试、不产出新 APK 的任务，不因验证动作递增版本；如果之后决定产出新 APK，在组装前补齐签名预检和该任务的一次版本递增。
- Debug versionCode 来自 `applicationVariants` 中的 `output.versionCodeOverride`，不能只改 `defaultConfig.versionCode`。
- 版本改变时同步 `README.md`、`说明.md`、`docs/build.md` 的版本信息与 APK 输出说明。根 AGENTS 不再复制易过期的版本表；版本和依赖最终以根 `build.gradle`、`app/build.gradle`、Gradle Wrapper 及 schema 源码为准。

### 最终 APK 核验

- 固定输出：`app/build/outputs/apk/appRelease/debug/app-appRelease-debug.apk`，元数据为同目录 `output-metadata.json`。
- 使用 `<已核实的 Android SDK 根目录>\build-tools\36.0.0\apksigner.bat` 核对 v1/v2 签名和证书摘要；证书必须与主机固定值一致。
- 核对最终 APK 和元数据中的 variant、applicationId、versionCode、versionName，并记录文件大小与 SHA-256。Debug 包必须为 `com.ehviewer.manbo.debug`。
- 完成组装后，只有生产代码、资源或构建输入再次变化才需要重新组装；仅补充说明无需重建。记录必须对应最后一次组装的产物，不能沿用中间产物或历史 APK 的大小、哈希。

## 7. USB 真机操作

- 可以用 ADB 对 Debug 包安装、启动和验证。未经用户明确要求，不得构建、安装、启动、检查或卸载 Release 变体及手机正式版，也不得误操作其他 EhViewer 包。
- ADB 路径由已核实的 SDK 根目录派生，为 `<已核实的 Android SDK 根目录>\platform-tools\adb.exe`。操作前运行 `adb devices -l`，以当次结果选择已授权设备，不硬编码历史设备序列号。
- 安装前完成第 6 节 APK 核验；默认 `adb install -r` 覆盖安装并保留数据。安装前后用 `dumpsys package com.ehviewer.manbo.debug` 核对 applicationId、versionCode、versionName；启动显式指定 Debug 包。
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 首先按签名不一致排查：重查 USERPROFILE、signingConfig、主机 keystore 和 APK 证书，再用正确签名构建。除非用户明确要求，否则不得卸载旧包。
- 设备不可读、未授权、离线或系统拒绝安装时，报告具体错误并请用户处理手机端授权。
- MIUI 锁屏遮挡或注入 `KEYCODE_WAKEUP` 返回 `INJECT_EVENTS` 权限错误时，记录验收受限，请用户解锁后再截图或验证主题；不能声称视觉验收通过。
- 如果任务明确要求改变 applicationId，须说明新旧应用不会互相覆盖。

## 隐私数据、凭据与提交检查

### 采集与存放

- 手机数据库及 WAL/journal、备份包、真实书库清单、归组及排序结果、含实际数据的预览、截图和设备日志统一放在 `<仓库根目录>\.private\<任务名>\<日期时间>\`，不得放入源码、测试资源、skill、`artifacts` 或 `exports`。衍生的 JSON、HTML、Markdown 和压缩包同样属于隐私产物，不能只保护数据库扩展名。
- 写入前用 `git check-ignore` 验证预期文件被忽略，并用 `git ls-files -- .private` 确认无已跟踪文件；缺少规则先补齐。添加忽略规则不会取消已有跟踪，发现已跟踪隐私文件时停止向该位置写入并说明情况，禁止 `git add -f` 绕过。
- 只采集任务需要的数据，不为漫画整理或普通验证导出整个应用私有目录，不额外采集 Cookie、账号设置或搜索库。获取一致性数据库时保留必要旁文件，不能通过删除 WAL/journal 绕过一致性要求。
- 隐私原始及衍生数据不得提交、推送、上传、发布，或混入项目/skill 压缩包、CI 附件和公开网页。检查和交付只输出必要的文件路径、字段名、数量及脱敏摘要，不输出实际密码、令牌、账号、设备序列号或整库书目。

### 签名、CI 与测试样本

- 私钥、实际签名密码、访问令牌及账号 Cookie 不得进入源码、文档、测试样本或日志。已有主机 keystore 和本地 `test.key` 留在原位置并排除提交，不擅自删除、替换、移动、复制或重新生成签名文件；固定 Debug 签名要求继续按第 6 节执行。
- CI 密码通过平台的秘密变量注入，签名文件由受保护的 Secure Files 等机制提供，YAML 只保留变量引用。Azure 现用变量为 `AndroidSigningKeystorePassword` 和 `AndroidSigningKeyPassword`；明确说明配置要求，不能通过跳过签名、改变证书、移除任务或隐藏失败来声称功能保持不变。
- 测试优先使用合成数据。导入抓取的 HTML/JSON 前检查账号 ID、API key、Cookie、个人目录及页面内嵌数据；保留解析所需的类型、格式、长度与结构，例如详情样本的 `apiuid = 0` 和 20 位全零十六进制 `apikey`。核对现有断言及解析行为，不把随机生成的疑似真实凭据当作安全样本，也不联网验证原凭据是否有效。
- 脱敏只改需要处理的值，保留其他页面字段、代码行为、编码和换行；验证原值不再残留，并运行适用的样本或解析检查。区分公开字段名、合成值、默认 Debug 口令与真正凭据，不因关键词命中删除正常代码、第三方署名或许可证。
- 公开文档和纳入 Git 的 `AGENTS.md` 使用用户名、设备和仓库路径占位符或变量，不写入个人绝对路径、设备序列号或凭据；本机实际路径仅用于当前进程，不写回共享规范。

### 提交、打包与发现问题后的处理

- 每次提交、推送或打包前，检查实际候选和暂存内容：`git status --short`、`git diff --cached --name-only`、`git diff --cached`；首次提交还需用 `git ls-files --others --exclude-standard` 检查未跟踪候选。检查时不得把匹配出的秘密原值直接打印到聊天或日志，内容核验优先在本地完成并输出脱敏结果。
- 检查范围包括文本、二进制签名容器、归档成员、截图、测试资源、历史补丁、CI 配置和文档；不能仅凭文件名、扩展名或 `.gitignore` 判断安全。手工打包不自动遵守 Git 忽略规则，必须另外核对实际打包清单。
- 本地私钥、缓存、临时日志、无用空文件和生成的编译产物应排除提交，当前明确排除 `/test.key`、`/daogenerator/bin/`、`/123`。同时核对构建所需源码和工具输入没有被误排除，不为了清理而批量删除第三方库、Gradle Wrapper 或其他必需文件。
- 发现已确认的敏感内容时，先排除本次提交或发布并说明；根据已有授权脱敏或清理，保留其他代码和用户改动。未提交不等于已忽略；交付时说明仍未跟踪、被忽略和已脱敏提交的文件范围，不能声称工作区干净就等于没有本地隐私文件。
- 删除工作区文件、取消跟踪、断开远程连接都不会清除旧提交或远端副本。重写历史、强制推送、删除仓库或移除 `.git` 必须有用户明确授权，并说明影响范围；不得为方便清理擅自重建仓库。完成后按实际检查分别报告本地历史、远端和其他副本的处理状态。

共享的隐私存放和 CI 配置说明见 [开发与构建](docs/build.md)。

## 8. 文档与交付记录

- 修改代码、资源、构建配置或功能时，必须同步根目录 `说明.md`；修改本工作规范时也记录规则调整。
- 每次记录包含修改内容、影响范围、版本信息、当次验证结果和已知限制；未运行的编译、测试、APK 核验或真机验收明确写明。
- 功能用法维护在 `docs/usage.md`，扫描规则在 `docs/scanning.md`，架构在 `docs/architecture.md`，构建步骤在 `docs/build.md`；调整对应行为时同步有关文档，避免规则分散后互相矛盾。
- `docs/archive/说明-2026-09-11.md` 和 `feedauthor` 历史资料保留当时语境。历史测试、APK 与同步基线不代表当前状态；不追溯改写第三方文档、许可证和历史原文。
- 交付前检查本次差异和 `git diff --check`；对被忽略或未跟踪的文档另外检查内容、链接和空白，因为普通 Git diff 不覆盖它们。
- 根目录 `AGENTS.md` 纳入 Git，随提交和克隆共享。更新时保持路径可迁移，并同步本文及构建文档；签名私钥、隐私产物和本机技能目录仍按各自忽略规则处理。

# 开发、构建与验证

[文档索引](README.md) · [架构与数据](architecture.md) · [变更记录](../说明.md)

本机默认只使用 `appReleaseDebug`，包名为 `com.ehviewer.manbo.debug`。以下命令在仓库根目录的 PowerShell 中执行。公开文档使用路径占位符，执行前须替换为实际环境并预检；本机 Agent 仍须遵守未提交的 `AGENTS.md` 中的固定配置。

## 构建参数

以 [app/build.gradle](../app/build.gradle)、[build.gradle](../build.gradle) 和 [Gradle Wrapper 配置](../gradle/wrapper/gradle-wrapper.properties) 为准。

| 项目 | 2026-11-11 源码值 |
| --- | --- |
| Gradle / Android Gradle Plugin | 9.5.0 / 9.3.1 |
| Kotlin Android / Parcelize | 2.2.10 / 2.1.0 |
| Java / Kotlin JVM target | 21 / 21 |
| compileSdk / targetSdk / minSdk | 35 / 30 / 23 |
| NDK | 30.0.15729638 |
| ABI | armeabi-v7a、arm64-v8a、x86、x86_64 |
| namespace | `com.hippo.ehviewer` |
| Debug applicationId | `com.ehviewer.manbo.debug` |
| versionName / Debug versionCode | 170 / 170 |
| Release applicationId / versionCode | `com.ehviewer.manbo` / 112（仅配置参考） |
| GreenDAO schema | 15 |

2026-11-11：完成追更计数、分割线、页面来源、归档导入和备份目录映射修复；固定 JDK/SDK 离线 Java/Kotlin 编译通过。当次相关单元测试 57 份 XML 共 398 项：396 通过、0 失败、0 错误、2 项既有旧 Robolectric schema 测试跳过。SQLite 回放 26 项通过，包含新加的真实 DAO 目录映射 SQL 回放。未组装或安装 APK，版本保持 170、schema 保持 15；未做真机视觉、SAF、实际进程重启及联网验收。下方历史 APK 不包含本轮修改。

2026-11-10 源码新增追更更新页并升级数据库至 schema 15。`appReleaseDebug` Java/Kotlin 离线编译通过，当次 33 份测试 XML 共 244 项：242 通过、0 失败、0 错误、2 项既有旧 Robolectric schema 测试跳过；SQLite 生产 SQL、迁移与重置回放共 23 项通过。本轮不组装或安装 APK，未做真机视觉和联网交互验收，应用版本保持 170。以下 v170 APK 记录为此前产物，不包含新页面。

2026-11-09：已离线组装 v170，Java/Kotlin 和四种 ABI 原生编译成功。39 份测试 XML 共 278 项，276 通过、0 失败、0 错误、2 跳过（既有旧 Robolectric schema 测试）。APK 与元数据包名/版本、固定证书 v1/v2 签名核验通过。固定输出为 `app/build/outputs/apk/appRelease/debug/app-appRelease-debug.apk`，大小 33,201,377 字节，SHA-256 `aa331849372a4dc80f27cf811cd5960ba24bd38d91b41560c03086e5321bdd01`。本次未安装手机，未做真机恢复及联网验收。详见[当次记录](../说明.md)。

其他环境需要自行提供对应 JDK、SDK、NDK 与依赖访问条件，未在本次任务中验证 Linux 构建。Google Services/Crashlytics 插件仅在 `app/google-services.json` 存在时应用，Firebase 依赖仍在构建文件中声明，不能仅凭缺少该文件承诺所有构建均禁用分析功能。

## 先检查环境和签名

将下例 `<用户名>` 替换为持有原 Debug keystore 的 Windows 主机账户目录名，不要使用临时沙箱账户。JDK 和 SDK 若不在示例子目录，也须填写实际安装路径；这里只设置当前命令进程的环境变量。

```powershell
$buildUserProfile = 'C:\Users\<用户名>'
if ($buildUserProfile.Contains('<用户名>')) { throw '请先填写实际主机用户目录' }
$buildJdk = Join-Path $buildUserProfile '.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
$env:JAVA_HOME = $buildJdk
$env:ANDROID_HOME = Join-Path $buildUserProfile 'AppData\Local\Android\Sdk'
$env:USERPROFILE = $buildUserProfile
$debugKey = Join-Path $buildUserProfile '.android\debug.keystore'
$requiredPaths = @(
    "$buildJdk\bin\java.exe",
    "$buildJdk\bin\keytool.exe",
    $env:ANDROID_HOME,
    $debugKey,
    'gradle\wrapper\gradle-wrapper.jar'
)
foreach ($requiredPath in $requiredPaths) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "缺少构建依赖：$requiredPath"
    }
}
& "$buildJdk\bin\keytool.exe" -list -v -keystore $debugKey -storepass android -alias androiddebugkey
if ($LASTEXITCODE -ne 0) { throw 'Debug keystore 核验失败' }
```

核对证书 SHA-256（忽略大小写和冒号）为：

```text
af169dcf1d3e44c326b55a1c631c371d3adfe06b8bed34839f03a89cf1f1e9df
```

同时确认 `app/build.gradle` 的 Debug signingConfig 仍通过 `USERPROFILE` 绑定主机 `.android/debug.keystore`。alias 为 `androiddebugkey`，store/key password 均为 `android`。摘要、alias 或密码不符时停止，不生成或替换 keystore。

Codex 沙箱的 Java home 可能指向临时用户，其自动生成的 Debug key 不能用于覆盖主机现有 Debug 应用。Gradle 编译、测试和组装在本机需要沙箱外执行权限，避免读取 SDK 文件时遇到 AccessDeniedException。

每次 Gradle 检查、编译、测试或组装都先确认 JDK/SDK 存在，并在实际命令进程设置上述环境；不要依赖 PATH、`local.properties` 或自动发现。签名预检是新 APK 组装的前置要求。环境缺失时先只读探测现有安装；若仍需安装工具、依赖或创建环境，先说明版本、用途、来源和命令、展开后的绝对目录、可否改位置、影响及撤销方法，等待用户明确同意后执行。

已有 Gradle 分发与依赖缓存时，可给下方命令追加 `--offline`。它不会阻止 Wrapper 在分发缺失时下载 Gradle；首次使用或缓存缺失时应先确认所需安装与下载方案。正常构建可能超过 60 秒，使用异步执行并等待同一次构建完成。

## 版本与最小验证

修改前运行 `git status --short` 和针对性 `git diff`，保留已有改动。每个需要新 APK 的任务在签名预检完成后、首次构建前，将 Debug versionCode 递增一次并同步 versionName。失败重试不再递增。

Debug versionCode 来自 `applicationVariants` 中的 `output.versionCodeOverride`；同步更新 README、说明、本页版本信息和产物记录；根 AGENTS 不再维护版本表。只有文档调整不需递增或构建 APK。仅编译或测试也不因验证动作递增版本；若之后决定产出新 APK，在组装前补齐签名预检与该任务的一次版本递增。

完成上面的环境设置后，按改动范围选择任务：

```powershell
& "$buildJdk\bin\java.exe" -classpath gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:compileAppReleaseDebugJavaWithJavac --no-daemon --no-problems-report --max-workers=1
if ($LASTEXITCODE -ne 0) { throw '编译失败' }
```

单元测试任务为 `:app:testAppReleaseDebugUnitTest`，可使用 `--tests` 限定相关类。更新策略优先纯 Java 测试；解析修改需运行样本，数据库修改需运行 schema 和迁移相关测试。旧 `:daogenerator:executeDaoGenerator` 使用 schema 6，生成前会删除 DAO 目录，而应用已为 schema 15；不能直接执行，确需生成时先核对迁移与全部定制并取得用户明确授权。

测试后汇总实际 XML：

```powershell
$totals = @{ tests = 0; failures = 0; errors = 0; skipped = 0 }
$reports = @(Get-ChildItem -LiteralPath 'app/build/test-results/testAppReleaseDebugUnitTest' -Filter 'TEST-*.xml')
if ($reports.Count -eq 0) { throw '没有测试 XML，不能声明测试通过' }
foreach ($report in $reports) {
    [xml]$testXml = Get-Content -LiteralPath $report.FullName -Raw
    foreach ($metric in @('tests', 'failures', 'errors', 'skipped')) {
        $totals[$metric] += [int]$testXml.testsuite.GetAttribute($metric)
    }
}
$totals
```

确认文件来自本次运行，不能混入旧筛选任务的残留报告。旧 Robolectric 4.2.1 有 Java 21/targetSdk 兼容限制，初始化失败不代表测试主体执行；历史结果见[变更记录](../说明.md)。

## 组装与 APK 核验

签名预检及版本同步完成后：

```powershell
& "$buildJdk\bin\java.exe" -classpath gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:assembleAppReleaseDebug --no-daemon --no-problems-report --max-workers=1
if ($LASTEXITCODE -ne 0) { throw 'APK 组装失败' }
```

固定输出为 `app/build/outputs/apk/appRelease/debug/app-appRelease-debug.apk`；元数据为同目录 `output-metadata.json`。

```powershell
$env:JAVA_HOME = $buildJdk
$apkPath = 'app\build\outputs\apk\appRelease\debug\app-appRelease-debug.apk'
$apkTools = Join-Path $env:ANDROID_HOME 'build-tools\36.0.0'
& "$apkTools\apksigner.bat" verify --verbose --print-certs $apkPath
if ($LASTEXITCODE -ne 0) { throw 'APK 签名核验失败' }
Get-Content -LiteralPath 'app\build\outputs\apk\appRelease\debug\output-metadata.json' -Raw
& "$apkTools\aapt.exe" dump badging $apkPath
if ($LASTEXITCODE -ne 0) { throw 'APK manifest 核验失败' }
(Get-Item -LiteralPath $apkPath).Length
Get-FileHash -LiteralPath $apkPath -Algorithm SHA256
```

逐项核对 variant、applicationId、versionName、versionCode、v1/v2 签名、证书摘要、文件大小和 SHA-256。证书必须与上述固定值一致。不要用历史哈希描述新产物。

组装后生产代码、资源或构建输入再次改变时，重新组装并核验最终产物；仅补充说明无需重建。

## Azure Pipelines 签名凭据

`azure-pipelines.yml` 的两个签名密码只引用 `$(AndroidSigningKeystorePassword)` 和 `$(AndroidSigningKeyPassword)`。使用这份流水线前，在 Azure Pipelines 的 Variables 中配置这两个变量，并启用 **Keep this value secret**；填入对应原签名的密码，不要写回 YAML、命令或日志。配置方式见 [Azure 秘密变量文档](https://learn.microsoft.com/en-us/azure/devops/pipelines/process/set-secret-variables?view=azure-devops)。

沿用原 `AndroidSigning@2` 任务、Secure Files 中的 `text.key` 引用及原 alias；私钥文件不进入仓库，需在 Azure 的 Secure Files 中提供并授权给流水线，参见[签名任务说明](https://learn.microsoft.com/en-us/azure/devops/pipelines/tasks/reference/android-signing-v2?view=azure-pipelines)。本次仅移除硬编码密码，不调整现有触发分支、构建任务、APK 路径或签名方式；未配置秘密变量时不能完成 CI 签名。本机固定 Debug keystore 和构建流程不受影响，Azure 流水线需要另行实际运行验证。

## 本地隐私产物

手机数据库、WAL/journal、Cookie、SharedPreferences、搜索记录、书库清单、带实际数据的预览、截图和设备日志仅存放于仓库根目录 `.private/<任务名>/<日期时间>/`（即 `<仓库根目录>\.private\`），不得提交或上传。采集前用 `git check-ignore` 核实预期文件被忽略，并用 `git ls-files -- .private` 确认没有已跟踪文件；不得用 `git add -f` 绕过。优先只采集所需的单库及一致性关联文件，不为普通验证导出整个应用私有目录。

`artifacts/`、`exports/` 也已加入忽略规则，用于阻止继续误收录任务输出；已有公开构建记录和 APK 可保留，但不得向其中新增隐私数据。提交、打包与发布前检查实际文件清单，忽略规则不能防止手工 ZIP 打包或强制添加。测试使用合成数据，公开验证摘要不得包含设备序列号、账号、Cookie 或实际书库。添加忽略规则和删除工作区文件都不能清除 Git 历史及远端副本，历史清理与推送须单独授权。

## USB 覆盖安装

先完成 APK 核验，再枚举设备：

```powershell
$adbPath = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
& $adbPath devices -l
```

从当次输出选择已授权设备，将下例设备占位符替换为实际序列号：

```powershell
$deviceSerial = '<当次已授权设备序列号>'
& $adbPath -s $deviceSerial shell dumpsys package com.ehviewer.manbo.debug
& $adbPath -s $deviceSerial install -r $apkPath
if ($LASTEXITCODE -ne 0) { throw '安装失败，保留旧包并检查错误' }
& $adbPath -s $deviceSerial shell dumpsys package com.ehviewer.manbo.debug
```

安装前后核对版本。应用启动须显式指定 Debug 包；不要操作正式版或卸载旧包解决签名冲突。设备离线、未授权或系统拒绝安装时，记录具体错误并处理手机端授权。

编译、测试、APK 核验、安装和真机视觉验收是不同结果。锁屏遮挡或 MIUI 拒绝输入注入时，只能记录验收受限，不能宣称界面已验证。

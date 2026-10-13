# 开发、构建与验证

[文档索引](README.md) · [架构与数据](architecture.md) · [变更记录](../说明.md)

本机默认只使用 `appReleaseDebug`，包名为 `com.ehviewer.manbo.debug`。以下命令在仓库根目录的 PowerShell 中执行，路径为本机固定配置，每次运行仍须预检。

## 构建参数

以 [app/build.gradle](../app/build.gradle)、[build.gradle](../build.gradle) 和 [Gradle Wrapper 配置](../gradle/wrapper/gradle-wrapper.properties) 为准。

| 项目 | 2026-10-13 源码值 |
| --- | --- |
| Gradle / Android Gradle Plugin | 9.5.0 / 9.3.1 |
| Kotlin Android / Parcelize | 2.2.10 / 2.1.0 |
| Java / Kotlin JVM target | 21 / 21 |
| compileSdk / targetSdk / minSdk | 35 / 30 / 23 |
| NDK | 30.0.15729638 |
| ABI | armeabi-v7a、arm64-v8a、x86、x86_64 |
| namespace | `com.hippo.ehviewer` |
| Debug applicationId | `com.ehviewer.manbo.debug` |
| versionName / Debug versionCode | 167 / 167 |
| Release applicationId / versionCode | `com.ehviewer.manbo` / 112（仅配置参考） |
| GreenDAO schema | 14 |

最近一次交付记录为 2026-10-11 的 `167 / 167`：已组装、通过固定 Debug v1/v2 签名及 APK 版本核验，并覆盖安装和基本启动，保留应用数据。此前功能阶段 102 项相关测试通过，交付阶段未重跑测试；在线列表、云收藏及各主题的完整真机视觉与交互验收尚未完成。大小、SHA-256 及各阶段结果见[当次记录](../说明.md)。2026-10-13 的文档更新只核对源码配置，未重新执行构建、测试、APK 签名核验或手机操作。

其他环境需要自行提供对应 JDK、SDK、NDK 与依赖访问条件，未在本次任务中验证 Linux 构建。Google Services/Crashlytics 插件仅在 `app/google-services.json` 存在时应用，Firebase 依赖仍在构建文件中声明，不能仅凭缺少该文件承诺所有构建均禁用分析功能。

## 先检查环境和签名

```powershell
$buildJdk = 'C:\Users\21555\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
$env:JAVA_HOME = $buildJdk
$env:ANDROID_HOME = 'C:\Users\21555\AppData\Local\Android\Sdk'
$env:USERPROFILE = 'C:\Users\21555'
$debugKey = 'C:\Users\21555\.android\debug.keystore'
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

单元测试任务为 `:app:testAppReleaseDebugUnitTest`，可使用 `--tests` 限定相关类。更新策略优先纯 Java 测试；解析修改需运行样本，数据库修改需运行 schema 和迁移相关测试。旧 `:daogenerator:executeDaoGenerator` 使用 schema 6，生成前会删除 DAO 目录，而应用已为 schema 14；不能直接执行，确需生成时先核对迁移与全部定制并取得用户明确授权。

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
$apkTools = 'C:\Users\21555\AppData\Local\Android\Sdk\build-tools\36.0.0'
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

## USB 覆盖安装

先完成 APK 核验，再枚举设备：

```powershell
$adbPath = 'C:\Users\21555\AppData\Local\Android\Sdk\platform-tools\adb.exe'
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

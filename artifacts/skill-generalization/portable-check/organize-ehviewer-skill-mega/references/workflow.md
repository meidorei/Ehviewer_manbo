# 工作流与命令

所有示例相对于 skill 目录。将 `run` 替换为本次全新输出目录的实际路径；正式整理默认放在 Android 工程之外。命令输出不得覆盖旧结果。运行 `python scripts/organize.py COMMAND --help` 查看参数。

## 1. 导入

本地 SQLite，确认来源确实使用 EhViewer 毫秒时间字段后：

```text
python scripts/organize.py catalog --db source.db --snapshot run/source.db --time-unit milliseconds --catalog run/catalog.json --summary run/snapshot-summary.json
```

使用 SQLite backup API 获取包含已提交 WAL 内容的一致性快照；原库不被写入。`--time-unit` 不提供时仍可导出标题，但清单不允许写回。需要 `DOWNLOADS`、整数 GID，以及 TITLE 或 TITLE_JPN。TITLE_JPN、LABEL、STATE 不是必需字段；缺少 TIME 或时间语义未确认时不猜写回方式。不同表名／不支持的 schema 报错说明，不自动迁移数据库。

JSON 可为 `items` 数组或包含 `items` 的对象，每项含整数 `gid`、`title`，可含 `titleJpn` 与唯一正整数 `originalPosition`；缺少位置时取输入顺序。输出只保留安全标题字段。JSON 导入不继承写回资格；以后要写库，应对相应 SQLite 快照重新导入并核对双指纹。

```text
python scripts/organize.py catalog --json titles.json --catalog run/catalog.json --summary run/snapshot-summary.json
python scripts/organize.py migrate-v2 --catalog old-catalog.json --decisions old-decisions.json --output-catalog run/catalog.json --output-decisions run/seed.json
```

迁移后位置与类别不猜测，保留旧语义字段作历史证据，重新四轮审核。版本 2 不可直接通过新版写回。

可选 ADB 导入须提供 `--adb`、当次 `adb devices -l` 中授权的 `--serial` 和明确的 `--package`。`adb export` force-stop 指定包，检查应用数据库目录；存在 WAL、SHM 或 journal 即停止，要求应用导出或解决一致性快照后再继续，不删除 sidecar。通过 `exec-out run-as ... cat` 导出二进制；不尝试 root 或其他包。随后仍需运行上述 SQLite 导入。

## 2. Discovery 与候选

```text
python scripts/organize.py discover --catalog run/catalog.json --output-dir run --manifest run/run-manifest.json
python scripts/organize.py discovery-validate --manifest run/run-manifest.json --results-dir run --annotations run/discovery.json --report run/discovery-report.json
python scripts/organize.py audit init --catalog run/catalog.json --output run/audit-0.json
python scripts/organize.py candidates --catalog run/catalog.json --discovery run/discovery.json --decisions run/audit-0.json --candidates run/candidates-0.json --author-buckets run/authors-0.json --unresolved run/unresolved-0.json
```

由当前主模型填写每个 discovery batch 对应的 resultFile；不能用测试夹具或循环复制默认注释代替语义工作。75 是默认批大小，支持 1–100；真实模型输入默认保持最多 75 条。空库产生零批次，可正常完成空流程。

候选文件包括完整关联边 `edges`、有界 `candidates[].items`、`componentId/componentGids`、高频碰撞的有界 `batches`。作者桶含完整 GID 索引和最多 75 条的子批。大集合不得一次全部塞进模型；同时提供跨批成员 ID、边理由和已接受系列的简明索引，以免人为分批切断关系。

`--decisions` 每轮传入最新完整决策；独立或待审的条目重新进入未决索引，即便它原先在候选中。初始独立条目全部待审是正常现象。

## 3. 四轮审核与历史复用

顺序固定：`candidate` → `author` → `unresolved` → `global`。每轮检查全部 GID；不涉及变化的条目也应经过对应审核并保留原结论。

```text
python scripts/organize.py audit prepare --catalog run/catalog.json --input run/audit-0.json --output run/round-1-input.json
python scripts/organize.py audit record --catalog run/catalog.json --input run/audit-0.json --result run/round-1-result.json --round candidate --output run/audit-1.json
```

`prepare` 含安全元数据子批、最新系列索引和未决 GID。将本轮候选、作者桶、未决和碰撞材料按需一起提供给主模型。结果的 `inputArtifactDigest` 使用 prepare 中的同名值，它绑定 audit-0 输入文件。不要自行伪造摘要或把准备文件的摘要误当作决策输入摘要。

也可以把本轮分批模型输出存入只含本轮结果的目录，再合并并记录：

```text
python scripts/organize.py audit merge --catalog run/catalog.json --input run/audit-0.json --results-dir run/round-1-results --output run/audit-1.json
```

每份子结果使用同一输入摘要与双指纹，`reviewedGids` 精确匹配该文件的 decisions。跨批重复成员仅允许结论完全一致；不一致、漏项或显式 conflict 必须由主模型解决，脚本不替它选一个。该命令记录下一个应完成的轮次。以后各轮对最新 audit 文件重新 candidates 和 prepare，最终得到 audit-4。

可选历史入口：`history --catalog CURRENT --history-catalog OLD --history-decisions OLD_DECISIONS --output SEED`。旧目录与决策均须 v3 且绑定匹配；只复用同 GID、相同 title/titleJpn 的语义，保留历史摘要并重新待审。没有特定 GID 覆盖或必需作品。历史 seed 仍要走四轮。

审核链记录覆盖与工件关联，不能证明模型真的理解了作品；必须保留原始结果供检查，不能把程序生成的覆盖记录称为真实分类评测。

## 4. 构建与人工修正

```text
python scripts/organize.py build --catalog run/catalog.json --decisions run/audit-4.json --output run/model-order.json
python scripts/organize.py validate --catalog run/catalog.json --order run/model-order.json --report run/model-validation.json
python scripts/organize.py review --catalog run/catalog.json --order run/model-order.json --output run/review.html
```

默认升序；可在 audit-4 加 `direction: "descending"` 后构建，或在页面切换。变更方向不改变语义审核结论，但模型基线文件摘要随文件改变，旧页面进度不能混用。

页面支持：系列及单本移动、筛选、选中整个系列、移入目标系列、所选拆为新系列、改名、编辑类别／故事分支／卷章范围、待审确认、撤销重做、保存草稿、导入进度和完整导出。所有操作均有按钮；键盘可完成操作，拖拽仅作补充。归属或属性编辑会重新按默认规则排序；最后执行手动顺序微调。

不同翻译保留为同组同位置条目，翻译信息保留在原标题和证据，不使用 translation 当作故事分支。没有位置的条目显示在对应类别和分支的待定位置组。

用户可确认“保持独立／未知位置”来处理无法核实的条目。确认所有待审条目后还须勾选最终确认才能导出 confirmed。保存草稿不会确认，也不会操作设备。导入只接受匹配当前模型和双指纹且可重放的记录。

```text
python scripts/organize.py validate --catalog run/catalog.json --model run/model-order.json --order run/confirmed.json --confirmed --report run/human-validation.json
```

保留原模型文件原始字节；改变格式也会改变其 SHA-256。人工结果不要求机械排序连续，但所有例外必须由重放记录解释。筛选只影响显示，导出永远包含全库。

## 5. 副本写回与设备验证

先得到用户对真实书库确认结果及目标的授权。重新导出／一致性备份源数据库：

```text
python scripts/organize.py apply --db fresh.db --catalog run/catalog.json --model run/model-order.json --order run/confirmed.json --backup run/pre-write-backup.db --output run/sorted.db --report run/dry-run.json
```

校验当前标题和 GID；使用备份作为固定事务输入。写入排序副本，首条为当前毫秒时间、每条递减 1000。检查全部表及 sqlite_master、user_version、application_id；只有 DOWNLOADS.TIME 可变。触发器改到其他表也会失败。失败可能留下供排错的备份或副本，但不会生成通过报告，禁止使用该副本。

工具不会替换手机数据库。设备替换时先备份并核对手机原库与电脑原始 ADB 导出文件哈希（不要与经 SQLite backup 重建的文件混比字节哈希），检查 sidecar，使用 `adb upload` 上传到应用 cache；仅通过明确目标的 `run-as` 在同一应用文件系统内替换。保留手机备份；不卸载应用绕过权限或签名问题。

替换后启动前与启动后分别重新导出，用同一个通过 dry-run 的 sorted.db 为基线：

```text
python scripts/organize.py verify-db --db pre-launch.db --expected-db run/sorted.db --catalog run/catalog.json --model run/model-order.json --order run/confirmed.json --phase pre-launch --report run/pre-launch.json
python scripts/organize.py verify-db --db post-launch.db --expected-db run/sorted.db --catalog run/catalog.json --model run/model-order.json --order run/confirmed.json --phase post-launch --report run/post-launch.json
```

也可用 `--phase offline` 检查本地副本。只有实际完成启动才称 post-launch；脚本不会启动应用。启动后若应用改变其他表或 TIME，严格检查会失败，应分析当次变化，不能声称通过或削弱验证。

## 6. 工件登记与清理

```text
python scripts/organize.py manifest register --manifest run/run-manifest.json --path run/model-order.json --retention preserve
python scripts/organize.py manifest verify --manifest run/run-manifest.json --pre-launch-report run/pre-launch.json --post-launch-report run/post-launch.json
python scripts/organize.py cleanup --manifest run/run-manifest.json
python scripts/organize.py cleanup --manifest run/run-manifest.json --confirm
```

每个工件生成后逐一 register，模型基线、确认结果、原始快照、报告和备份均 preserve。发现批次已自动登记，不重复登记。最后两条仅在用户明确要求清理后执行，先核对预览，再确认。保留项和验证报告不可删除；清单重复路径或摘要变化将阻止清理。仅做离线整理不需要也不应伪造设备验证来启用清理。

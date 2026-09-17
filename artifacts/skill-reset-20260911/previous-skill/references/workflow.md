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

迁移后位置与类别不猜测，保留旧语义字段作历史证据，进入默认简化审核。版本 2 不可直接通过新版写回。

可选 ADB 导入须提供 `--adb`、当次 `adb devices -l` 中授权的 `--serial` 和明确的 `--package`。`adb export` force-stop 指定包，检查应用数据库目录；存在 WAL、SHM 或 journal 即停止，要求应用导出或解决一致性快照后再继续，不删除 sidecar。通过 `exec-out run-as ... cat` 导出二进制；不尝试 root 或其他包。随后仍需运行上述 SQLite 导入。

## 2. 默认简化模式：准备候选与一次语义审核

不必先运行 discovery。程序只做原题召回，完整主标题、作者身份和阅读顺序仍由主智能体判断：

```text
python scripts/organize.py manifest init --catalog run/catalog.json --manifest run/run-manifest.json
python scripts/organize.py audit init --catalog run/catalog.json --output run/seed.json
python scripts/organize.py candidates --catalog run/catalog.json --decisions run/seed.json --candidates run/candidates.json --author-buckets run/authors.json --unresolved run/unresolved.json
python scripts/organize.py audit prepare --catalog run/catalog.json --input run/seed.json --output run/semantic-input.json
```

`manifest init` 创建空运行清单并登记 catalog；后续快照、审核输入/结果等按第 6 节逐个登记。它不声称完成 discovery。若之后需要生成 discovery 批次，使用独立子目录及其清单，避免覆盖当前清单。

`audit init` 默认 `auditMode="lightweight"`。`prepare` 按每批最多 75 条给出安全原题与最新决策，并保留完整系列成员索引。主智能体结合候选、必要的作者桶与已接受系列索引，一次处理作者、系列、分支和编号。每个 GID 至少实际审核一次；不把整个作者桶重复交给多个代理。超过上下文容量时保存分批结果和真实未读范围，不能靠填写覆盖数组代替工作。

把实际完成的分批结果放进只含本次结果的目录，再合并：

```text
python scripts/organize.py audit merge --catalog run/catalog.json --input run/seed.json --results-dir run/semantic-results --output run/reviewed.json
```

也可将完整结果以 `audit record --round semantic --result run/semantic-result.json` 登记。结果须绑定 `semantic-input.json` 中的双指纹与 `inputArtifactDigest`。首轮必须完整覆盖全库；重叠记录有冲突时不能自动选一个结论。记录只能证明工件关联及覆盖声明，不能证明语义准确率。

可选 discovery：已有可靠别名注释时，给 `candidates` 增加 `--discovery run/discovery.json`；工具会校验完整覆盖与双指纹。需要新做全库别名发现时才使用 `discover` / `discovery-validate`。未提供 discovery 时仅基于原题召回，不生成冒充模型注释的文件；跨语言遗漏由本次语义阅读和针对性查看作者成员补充。

## 3. 按疑点复查、续接及深度模式

### 局部复查

归属未定、跨批遗漏、主标题冲突或编号口径不一致时，仅选受影响的 GID。`needsHumanReview=true` 是人类待审状态；不能因此不断重新审核所有条目。没有具体疑点就直接构建审核页。

```text
python scripts/organize.py audit prepare --catalog run/catalog.json --input run/reviewed.json --gids 101 205 --output run/focused-input.json
python scripts/organize.py audit record --catalog run/catalog.json --input run/reviewed.json --result run/focused-result.json --round focused --output run/revised.json
```

示例 GID 必须换成实际待查条目。此时结果的 `decisions` 和 `reviewedGids` 只含实际复查的条目；脚本把它们替换进原决策，并验证其余记录不变。也可用 `audit merge` 合并局部结果。修改一个系列的名字需要包含该系列所有受影响成员，否则全量一致性校验会拒绝。`prepare` 给出的 `contextBatches` 仅供参考，不自动算入审核覆盖；确需修改参考条目时把它加入本次选定范围。

归属或别名变化可能影响其他条目时，用最新结果重建 candidates，再检查新增关系即可。没有新证据就保持独立、未知位置和待审标记，结束本轮，不设无收益的固定重复轮次。

### 续接已有工作

保留原始文件，显式生成新种子：

```text
python scripts/organize.py audit init --catalog run/catalog.json --input old-run/audit-1.json --mode lightweight --output run/seed.json
```

它校验旧输入、保留全部决策、记录 `seedSourceDigest` 并清空新模式审核链；所有条目重新标为人类待审。它不自动声明旧工作满足新流程。主智能体可以依据已保存、确实完成的逐条审核沿用未变更结论，仅补未读或受变更影响的条目，并在新 `semantic` 结果 summary 写清复用来源和本次补查范围。禁止把未完成的旧轮次或程序默认注释算作已审。

跨书库历史入口 `history --catalog CURRENT --history-catalog OLD --history-decisions OLD --output SEED` 仍只复用同 GID 且 title/titleJpn 未变化的字段，保留历史证据并进入默认简化审核。v2 须显式 `migrate-v2`，不猜原版混用的位置及类别语义。

### 可选深度模式

仅在用户指定或确有系统性错分需要扩大检查时，`audit init --mode deep`；`prepare` / `record` / `merge` 依次进行 candidate、author、unresolved、global 四轮，每轮按最新决策刷新候选并完整覆盖。具体观察点见 [提示](prompts.md)。缺少 `auditMode` 的既有 v3 文件按 deep 解释，不会悄悄降低原结果的完成标准。不能直接改模式字段或伪造后两轮。

## 4. 构建与人工修正

一次完整 semantic 审核即可构建；若做过局部复查则使用最后一份结果。deep 模式须完成原四轮。

```text
python scripts/organize.py build --catalog run/catalog.json --decisions run/reviewed.json --output run/model-order.json
python scripts/organize.py validate --catalog run/catalog.json --order run/model-order.json --report run/model-validation.json
python scripts/organize.py review --catalog run/catalog.json --order run/model-order.json --output run/review.html
```

默认组内升序。页面可切换方向，合并、拆分、改名、编辑类别/分支/卷章范围、移动系列或单本、撤销重做、保存草稿及恢复进度。所有原题和 GID 均静态保留，筛选只影响显示，导出永远包含全库。归属或属性修改先重新规则排序，手动位置微调放最后。

不同翻译保留同类别、分支及位置；未知位置不等于第 0 章。用户可确认保留独立或未知。全部待审确认后还需勾选最终确认，才能导出 confirmed；保存草稿不确认也不操作设备。

```text
python scripts/organize.py validate --catalog run/catalog.json --model run/model-order.json --order run/confirmed.json --confirmed --report run/human-validation.json
```

保留模型原始字节。导出绑定审核模式、双指纹、审核链和原模型 SHA-256，所有人工修改须能由操作日志重放。软件确认字段不能替代真实用户对本书库的确认。

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

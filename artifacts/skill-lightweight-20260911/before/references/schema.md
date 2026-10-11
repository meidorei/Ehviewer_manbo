# v3 数据契约

统一 JSON 读取拒绝重复键、NaN、Infinity、浮点溢出和附加文本。GID 为 `1..9007199254740991` 的整数，不接受布尔值、数字字符串或小数，保证浏览器不会丢失精度。所有 JSON 输出使用 UTF-8、无 BOM；输入兼容 UTF-8 BOM。

## 目录

```json
{
  "formatVersion": 3,
  "snapshotFingerprint": "SHA-256 of sorted decimal GIDs joined with newline",
  "metadataFingerprint": "SHA-256 of canonical safe title metadata",
  "items": [{"gid": 101, "originalPosition": 1, "title": "原题", "titleJpn": null}],
  "source": {"kind": "sqlite", "timeUnit": "milliseconds", "writebackSupported": true}
}
```

摘要示意字符串由程序生成真实 64 位小写十六进制值，不手填。标题摘要将记录按 GID 升序，取 gid/title/titleJpn，再使用 `common.canonical()` 的排序键、紧凑、非 ASCII 转义 JSON 序列化。原位置唯一且为正整数；空书库合法。原题不规范化覆盖。`source` 不包含电脑路径或账号信息，SQLite 源只有兼容整数列且显式确认毫秒语义时标为支持写回；JSON 源总是标为不支持。

## Discovery

注释仍为 formatVersion 1；外层同时绑定 snapshotFingerprint、metadataFingerprint 和 batchId。注释字段必须完整且无额外项：

```json
{
  "gid": 101,
  "authorAliases": ["已知作者别名"],
  "workAliases": ["有根据的作品别名"],
  "semanticTitleKey": "compact meaning or null",
  "partKind": "chapter",
  "partNumber": "0",
  "confidence": 0.8,
  "needsHumanReview": true
}
```

作者别名最多 3、作品别名最多 5，每项最多 80 字符；semanticTitleKey 为 null 或最多 96 字符；partNumber 为 null 或最多 32 字符。partKind 仅允许 unknown/chapter/episode/volume/part/collection/extra/remaster。这些字段只服务召回，不能表示最终合并。

## 完整决策

每个 GID 恰好一个 decision，下列字段全部必需，不允许额外 decision 字段：

```json
{
  "gid": 101,
  "canonicalSeriesId": "series:stars",
  "canonicalSeriesTitle": "星之旅",
  "category": "main",
  "branch": "main",
  "position": {"volume": null, "chapter": 0, "part": null, "rangeEnd": null},
  "orderReliable": true,
  "orderConfidence": 0.95,
  "confidence": 0.9,
  "reason": "原题明确标注第0话，作者及主标题一致。",
  "needsHumanReview": true,
  "candidateSeries": [],
  "evidence": [{"kind": "title", "claim": "原题中的作者、作品名和第0话编号。"}]
}
```

- category：main/extra/collection/remaster/other。branch 是故事分支；主线 main，翻译版本不是分支。
- position：未知使用 null；有值时必须包含 volume/chapter/part/rangeEnd 四键，每个值是有限非负数字或 null，至少一个数字。第 0 章合法。
- 卷章位置按 volume、chapter、part、rangeEnd 比较；rangeEnd 不能小于起始 chapter，其次 volume，再次 part。前中后、上中下可在有明确依据时编码 part=1/2/3。合集用范围起点和终点表示，不展开或删除记录。
- 只有位置可与组内其他条目可靠比较时才能设置 orderReliable=true，同时 orderConfidence 至少 0.85。混用不同编号体系且无法换算时保留未知；不能仅靠较高数字推测较新。
- confidence 表示归属置信度，不控制顺序；orderConfidence 表示位置依据。两者都在 0..1。
- needsHumanReview 必须布尔值。没有证据支持归属时用 `item:GID` 独立 ID；candidateSeries 保存 `{seriesId, reason}` 列表，不直接改变归属。
- 同一 canonicalSeriesId 的 canonicalSeriesTitle 必须一致。
- evidence 至少一项。kind 为 title/web/history/human，claim 为非空说明。网络证据额外需要公开 http(s) URL、ISO accessedAt、sourceType="page"；禁止凭证 URL 或只引用搜索摘要。历史证据记录来源摘要和旧语义字段；人工操作自动留下相应依据。

## 审核结果与链

初始与每轮完整决策外层为 formatVersion、双指纹、decisions、auditTrail。四轮顺序为 candidate/author/unresolved/global。

模型的一轮结果需要 formatVersion、双指纹、完整 decisions、`inputArtifactDigest`、`reviewedGids`、`conflicts: []` 和非空 summary。分批输出时 decisions 与 reviewedGids 只含本批实际审核的记录；合并后必须覆盖全库。冲突不得自行填写为空，先解决语义冲突再提交。

`audit record/merge` 写入每轮：round、metadataFingerprint、inputArtifactDigest、inputDecisionsDigest、outputDecisionsDigest、reviewedGids、conflicts、summary。前后决策摘要链必须连接，末轮摘要必须等于当前 decisions。完整审核链只能证明工件关联和覆盖声明，不能代替独立语义评测。

## 模型顺序与人工导出

`build` 在完整审核结果上增加：

- reviewStatus="model-reviewed"。
- direction="ascending" 或 "descending"，默认 ascending。
- gidOrder：完整 GID 列表；decisions 与其逐项对齐。
- seriesOrder：系列 ID 按首次出现顺序排列，无重复。

模型结果必须严格符合锚点、类别、分支、已知／未知及卷章顺序。人工导出保留双指纹和原 auditTrail，增加原模型文件原始字节的 SHA-256 `baseModelDigest`、operations，以及 reviewStatus="draft" 或 "confirmed"。后端重放 operations 后，四个状态字段 decisions/gidOrder/seriesOrder/direction 必须与导出完全一致。

操作协议：

| type | 参数与行为 |
| --- | --- |
| assign | gids、seriesId、title；所选移入目标或新系列，用于合并和拆分；标回待审并重新规则排序 |
| rename | seriesId、title；整系列改名 |
| edit | gid、patch；仅可修改类别、分支、位置、可靠性、置信度、理由、待审状态、候选和证据；不能改 GID 或绕过系列一致性 |
| acknowledge | gids；确认保留当前状态，清除待审标记并记录人工依据，不重新排序 |
| direction | value；按规则切换组内升降序 |
| moveItem | gid、before；在另一个 GID 前插入，before=null 表示末尾，记录为人工排序例外 |
| pin | gid；只将单本置顶 |
| moveSeries | seriesId、before；整个系列移到目标系列前，before=null 表示末尾 |

撤销重做通过操作记录游标实现；导出仅包含当前生效的前缀。恢复模型结果清空操作记录。所有手动修改仍必须满足完整 GID、字段类型、有限数字及系列命名约束；人工排序例外必须被日志重现。只有 confirmed 且所有 needsHumanReview=false 才能写回副本。该字段检查不是授权替代，真实书库还需要用户确认。

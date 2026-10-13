# 精简工具用法

核心只依赖 Python 3 标准库。入口为 `python scripts/organize.py`，可用 `--help` 查看参数。使用新的输出目录，保留原文件；工具不会自动清理文件或操作手机。

## 1. 导入标题

从数据库创建一致性副本及标题清单：

```text
python scripts/organize.py catalog --db eh.db --out run
```

从 JSON 导入：

```text
python scripts/organize.py catalog --json titles.json --out run
```

JSON 可以是条目数组，或含 `items` 的对象；条目需要整数 gid、title，可含 titleJpn 和 originalPosition。以前导出的 v3 catalog 也可直接作为后续 `--catalog` 输入，不必重做导入。数据库导入需要 DOWNLOADS 表、GID 和至少一种标题字段；缺少 TIME 时可以预览，不能写回。

## 2. 智能体填写系列

先使用有限输出读取标题，不直接打印完整 JSON。首次从偏移 0 开始：

```text
python scripts/organize.py titles --catalog run/catalog.json --offset 0
```

输出含 `totalCount`、`matchedCount`、`items` 和 `nextOffset`。每页默认最多 40 条且包含换行在内不超过 6,000 字符，完整保留每条题名。下一页使用返回的 `nextOffset`，不要自行加 40；`null` 表示当前范围已读完。`--limit` 支持 1–100，`--max-chars` 支持 1,000–12,000。单条超预算会明确报 GID，需在本地文件查看该条，不截断题名后假装读完。

有可信旧判断时先读新增或改题条目；需要回看关联成员时按具体关键词检索：

```text
python scripts/organize.py titles --catalog run/catalog.json --previous old/catalog.json
python scripts/organize.py titles --catalog run/catalog.json --query "Aoi Niwa"
```

`--previous` 比较 GID、title 和 titleJpn，仅返回新增或改题条目；不输出移除项，也不能用它跳过以前未读条目。`--query` 对 GID 和两种标题做不区分大小写的字面子串检索，不是自动归组。筛选参数改变时从偏移 0 开始；整理过程中输入 catalog 保持固定，更新数据另起一次比较。复杂候选索引保存为文件，使用同样的有限分页方式读取，不能在嵌套循环里打印全部匹配。

每批保存 `groups.json` 和简短的 `progress.json`：记录当前/旧 catalog 路径、query、nextOffset、已读范围以及剩余疑点。分页输出本身不表示智能体已看过；进度只在实际读完并保存判断后推进。目录沿用当次任务，不为每页新建一个项目。

读标题时直接记入 `groups.json`。只列要归组或调整的条目，不需要为每本抄写一份复杂决策。未列出的 GID 会独立保留，但智能体仍须读过它们并判断是否可能漏组。

```json
{
  "series": [
    {
      "name": "蓝色庭院（作者甲）",
      "note": "原题的日文、英文和罗马音指向同一作品，作者一致。",
      "members": [
        {"gid": 101, "order": 1},
        {"gid": 205, "order": 2},
        308,
        {"gid": 410, "category": "extra", "branch": "后日谈"},
        {"gid": 512, "category": "collection", "order": 1, "end": 2}
      ]
    }
  ]
}
```

以上 GID 和系列为格式示例。`members` 中可直接写 GID；只有明确顺序时才写 `order`，从 0 开始的真实章节允许使用 0。`end` 是合集范围结束位置。`category` 可用 main/extra/collection/remaster/other，默认 main；`branch` 默认 main。备注写清合并理由或不确定处即可，不需要置信度分数和审核日志。

同名但不同作者的作品用带作者的不同系列名；不要仅因为系列名字一样而误合并。同系列多语言版本各保留一个 GID。一个 GID 不能重复分配，不认识的 GID 会报错。

## 3. 生成与查看

```text
python scripts/organize.py build --catalog run/catalog.json --groups groups.json --out preview
```

得到 `order.json` 和 `review.html`。默认网页使用固定模板 [assets/review.html](../assets/review.html)，由 scripts/organize.py 嵌入当次数据。布局保持一致，书库内容随输入变化；修改模板后须重新生成到新目录，旧网页不会自动更新。模板中的 @@STATIC@@ 和 @@DATA@@ 各保留一次，分别用于无脚本清单和安全转义后的 JSON 数据。

页面左侧提供归组、保存恢复与确认导出，右侧展示系列和成员；窄屏自动改为单列。支持全部、多本系列、单本、章号未标注和已选条目筛选，搜索与筛选只影响显示，不改变导出范围。章号未知不表示归组错误。

页面自包含，可离线搜索、选择成员合并或独立保留、修改编号标注、上下移动单本、撤销、保存/恢复进度。需要细调类别和分支时，修改 groups.json 后重新生成到新目录。

用户检查完后勾选一次“确认整个分类与顺序”，导出 `confirmed.json`；无需逐条点确认。页面导出不操作手机。构建时自动核对 GID 全量保留、原题未变、编号和系列名称有效。

## 4. 确认后生成数据库排序副本

用户确认要写回这个结果后，获取手机的最新一致性数据库副本，再执行：

```text
python scripts/organize.py apply --db fresh.db --catalog run/catalog.json --order confirmed.json --out writeback
```

工具生成 `backup.db`、`sorted.db` 和成功时的 `report.json`。只修改 DOWNLOADS.TIME，首条使用当前毫秒时间，之后每条递减一秒；核对 GID/题名未变化、所有其他表和字段未变化、SQLite 完整性及 TIME DESC 的实际顺序。没有确认或核对失败会报错，不能将失败留下的副本用于手机。

电脑副本生成不等于手机写回。ADB 设备和包名按用户目标及工作区规则选择；先备份手机原库，再替换目标应用的数据库，重读核对：

```text
python scripts/organize.py verify --db readback.db --expected writeback/sorted.db
```

启动应用后再读取核对实际状态；如果应用自行更新了数据，需要查明差异，不能把一次启动前的核对当作启动后已通过。保留电脑和手机备份，不自动删除下载或旧输出。

手机上存在 WAL/journal 等文件时，应通过应用导出或合适的一致性快照方式获取数据；不能只复制主库并删除旁文件来绕过问题。无法得到一致性副本时，先完成已有清单的整理，说明写回所缺条件。

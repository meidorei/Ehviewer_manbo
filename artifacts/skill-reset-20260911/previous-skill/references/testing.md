# 测试与评测

核心使用 Python 3.10+ 标准库；浏览器测试是可选开发依赖，工具运行与生成的 HTML 不需要 Playwright、Node 或外部 CDN。

## 脚本回归

在 skill 目录执行：

```text
python -B -m unittest discover -s tests -v
```

保留原来的 11 项回归覆盖，旧版写回及清理测试夹具改为新版确认与强绑定报告；另外覆盖 v3 类型、重复键／非有限数字、单轮默认模式、局部修正不改变未审条目、深度模式兼容、模式绑定、审核链、历史迁移、作者召回、排序分支、人工操作重放、空库、WAL 和全部表保护。测试只使用合成题名、临时 SQLite 和临时文件，不需要实际书库或设备。

`build_stable_order` 内部保留版本 2 的旧行为供原测试和旧 Python 调用兼容；CLI 构建与写回仅支持 v3，并要求显式迁移。旧回归不是新版默认排序的说明。

## 浏览器交互

先生成到新的测试输出目录：

```text
python -B tests/build_test_artifacts.py --output qa-run
node tests/test_review_browser.cjs qa-run python
```

浏览器测试需要已有的 Playwright 模块和 Edge。可用 `PLAYWRIGHT_MODULE` 指定已安装模块的绝对路径；不自动安装依赖。测试会启动 headless Edge，使用 12 条和 3184 条清单检查合并／拆分、重命名、编辑第 0 章、方向、置顶、撤销重做、筛选下完整导出、导入恢复、拒绝篡改、最终确认、键盘按钮及手机宽度。实际导出的 JSON 交给 Python 验证器，记录浏览器错误、网络请求与加载时间，保存桌面／手机宽度截图。截图还需要人工视觉检查。

`build_test_artifacts.py` 的审核链是合成测试数据，不代表真实模型已审核任何用户漫画；禁止将该工具用于冒充真实语义审核。浏览器夹具默认使用 lightweight，旧 deep 路径保留单元回归。

## 多语言评测

[合成标注样本](../tests/fixtures/multilingual-gold.json) 含 18 个题名，覆盖中日英译名、活动前缀、同作者不同作品、同名不同作者、第 0 章、不同翻译、前中后篇、卷章、故事分支、合集和重制版。样本与预设关系由主模型构造，明确标记未经独立人工标注。

```text
python scripts/organize.py evaluate --gold tests/fixtures/multilingual-gold.json --candidates qa-run/eval-candidates.json --report qa-run/recall.json
python scripts/organize.py evaluate --gold tests/fixtures/multilingual-gold.json --order evaluated-order.json --report qa-run/semantic-metrics.json
```

指标分别为候选同系列对召回率、系列对精确率与召回率、误合并对数、漏分对数、已标注顺序对错误数。没有可计算分母时输出 null，不凭空给满分。合成 discovery 的预设别名只用于验证候选传递和指标计算，不评估模型能否自行发现这些别名。

真实准确率需要用户授权的脱敏书库与独立人工审校标签。先固定标签，再让主模型仅看原始安全元数据进行盲评，最后运行 evaluate。不得把预设正确答案当作模型输出，再宣称识别准确率为 100%。没有该数据时，只报告已验证的软件行为和合成召回结果。

## 交付检查

- 每个 CLI 运行 --help；所有 Python 文件编译检查，JS 语法检查。
- 运行上述单元测试与可用的浏览器测试，报告本次实际数量和失败／错误／跳过。
- 用 skill-creator 的 quick_validate 检查入口 frontmatter 和命名，同时检查所有相对链接、UTF-8、空白和已忽略文件差异。
- 工具包只包含 SKILL.md、agents、references、scripts、assets、tests；不打包实际书库、账号信息、缓存或本次测试导出。原始 skill 备份与交付包分开保留。
- 当前工程的该 skill 被 Git 忽略，普通提交不会包含它；交付可独立复制的 ZIP，保持忽略规则。

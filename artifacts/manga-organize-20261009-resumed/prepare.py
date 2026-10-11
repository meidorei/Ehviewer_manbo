"""Reuse reviewed decisions and add the one title read during this run."""
from collections import Counter
import importlib.util
import json
from pathlib import Path
import sqlite3

RUN = Path(__file__).resolve().parent
ROOT = RUN.parents[1]
OLD = ROOT / 'artifacts/manga-organize-20261009'
SKILL = ROOT / '.codex/skills/organize-ehviewer-v3/organize-ehviewer-skill-mega'
spec = importlib.util.spec_from_file_location('organizer', SKILL / 'scripts/organize.py')
o = importlib.util.module_from_spec(spec);spec.loader.exec_module(o)
catalog = o.read(RUN / 'catalog/catalog.json')
previous = o.read(OLD / 'catalog/catalog.json')
by = {x['gid']: x for x in catalog['items']}
oldby = {x['gid']: x for x in previous['items']}
assert set(by)-set(oldby) == {4241330}
assert not set(oldby)-set(by)
assert all(all(by[g][k] == oldby[g][k] for k in ('title','titleJpn')) for g in oldby)
groups = o.read(OLD / 'groups.json')
name = 'OC Story “All the way”（Potions）'
reason = '疑缺首篇：标题明确 Chapter 8；当前库按作者 Potions、主体题名 All the way 和 OC Story 检索仅见此条，未找到前篇。其他译名或作者别名仍可能未识别，不能断言前篇一定缺失。'
groups['series'].append(dict(name='【疑缺首篇】'+name, note=reason,
                             members=[dict(gid=4241330, order=8)]))
o.write(RUN / 'groups.json', groups)
flags = o.read(OLD / 'missing-first.json')
flags['flags'].append(dict(status='suspected', label='疑缺首篇', series=name,
                          reason=reason, members=[dict(by[4241330], order=8, category='main', branch='main')]))
o.write(RUN / 'missing-first.json', flags)
o.write(RUN / 'uncertain.json', o.read(OLD / 'uncertain.json'))
plan = o.build(catalog, groups)
o.write(RUN / 'preview/order.json', plan)
(RUN / 'preview/review.html').write_text(o.review_html(plan), encoding='utf-8')
sizes = Counter(r['seriesId'] for r in plan['items'])
multi = {k for k,n in sizes.items() if n > 1}
summary = dict(itemCount=len(by), multiSeries=len(multi),
               groupedItems=sum(sizes[k] for k in multi),
               singleItems=sum(n for n in sizes.values() if n == 1),
               flags=dict(Counter(x['status'] for x in flags['flags'])),
               uncertainRelations=len(o.read(RUN / 'uncertain.json')),
               inheritedTitleJudgments=len(oldby), newTitlesRead=1,
               confirmed=False, phoneWritten=False)
o.write(RUN / 'summary.json', summary)
o.write(RUN / 'progress.json', dict(catalog='catalog/catalog.json',
    previousCatalog='../manga-organize-20261009/catalog/catalog.json',
    nextOffset=None, deltaReadGids=[4241330],
    relatedQueries=['Potions','All the way','OC Story'],
    status='offline_complete_awaiting_writeback_confirmation',
    inheritedJudgments='Unchanged titles reuse the completed 2026-10-09 run; not reread this run'))
checks = {}
checks['planValid'] = bool(o.validate_plan(catalog, plan))
checks['allGidsExactlyOnce'] = len(plan['gidOrder']) == len(by) and set(plan['gidOrder']) == set(by)
checks['originalTitlesPreserved'] = all(all(r[k]==by[r['gid']][k] for k in ('title','titleJpn')) for r in plan['items'])
checks['rawAndSnapshotContentsMatch'] = o.db_digest(RUN/'raw/eh.db') == o.db_digest(RUN/'catalog/source.db')
with o.connect(RUN/'catalog/source.db') as con:
    checks['sqliteIntegrityOk'] = con.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
checks['chapter8Retained'] = next(r for r in plan['items'] if r['gid']==4241330)['order'] == 8
checks['priorGroupsRetained'] = groups['series'][:-1] == o.read(OLD/'groups.json')['series']
checks['unconfirmed'] = plan['confirmed'] is False
page = (RUN/'preview/review.html').read_text('utf-8')
checks['templateTokensReplaced'] = '@@STATIC@@' not in page and '@@DATA@@' not in page
checks['offlineNoExternalScripts'] = '<script src=' not in page and '<link href=' not in page
assert all(checks.values()), checks
o.write(RUN / 'validation.json', dict(checks=checks, passed=len(checks), failed=0,
    currentVerification=True, phoneWritten=False, visualAcceptance=False))
chapters = ['# 缺篇与编号待核清单', '',
    '题名线索沿用此前判断并补充本次新增条目；不是正文或外部目录核验。', '']
for flag in flags['flags']:
    chapters.extend(['## '+flag['label']+'：'+flag['series'], '', flag['reason'], ''])
    chapters.extend('- GID '+str(m['gid'])+'：'+str(m['title'] or m['titleJpn']) for m in flag['members'])
    chapters.append('')
(RUN/'缺篇清单.md').write_text('\n'.join(chapters)+'\n', encoding='utf-8')
note = '''# 当前手机漫画整理（2026-10-09，待确认写回）

当次读取已授权手机的 Debug 包 com.ehviewer.manbo.debug，版本162/162。短暂停止应用，复制主库及存在的journal后重新打开；没有删除手机旁文件或改动手机排序。

- 当前3,315条，相对本日此前结果新增1条，无移除、无改题。续用3,314条既有判断，本次分批读取新增及相关作者/题名检索，未重复扫描全库。
- 新增 GID 4241330：OC Story “All the way” Chapter 8（Potions），独立保留，章节标8；按作者和主体题名未找到前篇，标“疑缺首篇”。不能排除别名未识别。
- 385个多条目系列，共1,039条成员；2,276条单本。196项疑缺首篇、5项合集待核、19项编号待核，26项既有潜在关联继续独立保留。所有GID、原标题、下载和不同译本均保留。
- [完整离线预览](preview/review.html)支持搜索、调整归组、保存进度及一次确认导出；搜索“疑缺首篇”可筛出相关条目。
- [缺篇清单](缺篇清单.md)、[当次校验](validation.json)、[进度](progress.json)、[归组输入](groups.json)保存在本目录。
- 本轮10项数据/顺序检查通过，原始数据库与一致性副本逻辑一致，SQLite完整性ok。未重跑技能单元测试、未做真机视觉验收、未核正文或公开作品页；不把数据校验当作全部系列关系证明。
- 手机数据库尚未写回。用户确认此结果后，再获取新快照，生成备份及仅修改DOWNLOADS.TIME的排序副本并校验后写回；本目录没有可供直接写回的已确认排序数据库。
- 复用现有Python/ADB，无安装、持久环境修改、编译或APK操作。
'''
(RUN/'整理说明.md').write_text(note, encoding='utf-8')
# Check JavaScript syntax without printing the full page into the chat.
(RUN/'preview-script.js').write_text(page.rsplit('<script>',1)[1].split('</script>',1)[0], encoding='utf-8')
print(json.dumps(dict(summary=summary, checksPassed=len(checks)), ensure_ascii=False))

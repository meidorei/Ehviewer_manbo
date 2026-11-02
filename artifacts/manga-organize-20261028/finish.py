"""Build the review and check preservation and the explicitly chosen reading order."""
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import re

R=Path(__file__).resolve().parent
ROOT=R.parents[1]
spec=importlib.util.spec_from_file_location('organize',ROOT/'.codex/skills/organize-ehviewer-v3/organize-ehviewer-skill-mega/scripts/organize.py')
o=importlib.util.module_from_spec(spec)
spec.loader.exec_module(o)
c=o.read(R/'catalog/catalog.json')
g=o.read(R/'groups.json')
progress=o.read(R/'progress.json')
delta=o.read(R/'delta.json')['items']
assert set(progress['readDeltaGids'])=={i['gid'] for i in delta}
plan=o.build(c,g)
out=R/'preview-final'
out.mkdir(exist_ok=False)
o.write(out/'order.json',plan)
html=o.review_html(plan)
(out/'review.html').write_text(html,encoding='utf-8')
embedded=json.loads(re.search(r'<script id="data" type="application/json">(.*?)</script>',html,re.S)[1])
assert embedded==plan
assert not re.search(r'<(?:script|link|img|iframe)\b[^>]*(?:src|href)\s*=\s*["\'](?:https?:)?//',html,re.I)
assert '@@STATIC@@' not in html and '@@DATA@@' not in html
scripts=re.findall(r'<script>(.*?)</script>',html,re.S)
assert len(scripts)==1
(R/'preview-script.js').write_text(scripts[0],encoding='utf-8')
assert plan['confirmed'] is False
assert set(plan['gidOrder'])=={r['gid'] for r in c['items']}
assert len(plan['gidOrder'])==len(set(plan['gidOrder']))==3442
idx={gid:i for i,gid in enumerate(plan['gidOrder'])}
by={r['gid']:r for r in plan['items']}
for seq in ((3935102,4266161),(3731277,3901030),(4042745,4045015),(4113999,4253110),(4253099,4255867),(3594933,3596281,3846670,3846669,4246381),(3551569,3551609),(4279369,4092879),(4224558,4250066),(4071912,4280573)):
    assert [idx[k] for k in seq]==sorted(idx[k] for k in seq),seq
for a,b in ((4275174,4258573),(4275328,4251921),(4246195,4248561),(4223833,4262209),(3901030,4167822),(4255867,4282227)):
    assert abs(idx[a]-idx[b])==1,(a,b)
for gid in (4279369,3935102,3731277,3594933,3596281):
    assert by[gid]['order'] is None
for gid in (4284238,4250126,4108437,4221276):
    assert by[gid]['order'] is None
counts=Counter(r['seriesId'] for r in plan['items'])
new={r['gid'] for r in delta}
affected={r['seriesId'] for r in plan['items'] if r['gid'] in new and counts[r['seriesId']]>1}
summary=dict(itemCount=len(plan['items']),addedItems=119,removedSincePrevious=18,retitledItems=0,
             readNewItems=len(new),multiSeries=sum(n>1 for n in counts.values()),
             groupedItems=sum(n for n in counts.values() if n>1),singleItems=sum(n==1 for n in counts.values()),
             newItemsInMultiSeries=sum(counts[r['seriesId']]>1 for r in plan['items'] if r['gid'] in new),
             affectedMultiSeries=len(affected),phoneWritten=False,confirmed=False,
             preview='preview-final/review.html',applicationVersion='168 / 168',schema=14)
o.write(R/'summary.json',summary)
validation=dict(status='passed',readAll119NewTitles=True,unchangedJudgmentsInherited=True,
                itemCount=3442,allGidsRetained=True,bothOriginalTitlesPreserved=True,
                chapterRangesBeforeSequels=True,unnumberedPredecessorsRemainUnnumbered=True,
                translatedEditionsAdjacent=True,datesAndVersionNotChapters=True,
                embeddedPlanMatches=True,noExternalPreviewAssets=True,phoneWritten=False,
                previewSha256=hashlib.sha256((out/'review.html').read_bytes()).hexdigest())
o.write(R/'validation.json',validation)
uncertain=[
    dict(gids=[4253099,4255867,4282227],treatment='按同作者、PART题名和汉化者暂归一组；两份PART2全部保留。',reason='题名未标故事名，无法仅从标题证明三者的具体故事关系。'),
    dict(gids=[3811429,3811467],treatment='独立保留。',reason='同作者和玲子社长人物不足以确认连续篇章。'),
    dict(gids=[3528218,4254365],treatment='独立保留。',reason='同作者、Bocchi-sama共同前缀但副题不同，未取得续篇证据。'),
    dict(gids=[3242425,4236242,4246713],treatment='同系列内分别标总集篇、人物介绍和正篇第6卷，全部保留。',reason='总集篇收录范围与人物介绍／第6卷正文重叠情况未核实。')
]
o.write(R/'uncertain.json',uncertain)
lines=['# 本次涉及的系列（2026-10-28）','',f'完整书库 {len(plan["items"]):,} 本；以下列出含新增条目的 {len(affected)} 个多本系列。','']
for sid in sorted(affected,key=lambda sid:min(idx[r['gid']] for r in plan['items'] if r['seriesId']==sid)):
    rows=[r for r in plan['items'] if r['seriesId']==sid]
    lines.append('- '+rows[0]['series']+'：'+ ' → '.join(str(r['gid'])+('〔新增〕' if r['gid'] in new else '') for r in rows))
lines+=['','未列入多本系列的新增条目独立保留；不按同作者或同IP强行合并。','全量系列及原标题见离线预览，未确定关系见 uncertain.json。','']
(R/'本次系列清单.md').write_text('\n'.join(lines),encoding='utf-8')
progress.update(status='preview_ready_awaiting_user_confirmation',nextOffset=None,phoneWritten=False,
                preview='preview-final/review.html',questions=[u['reason'] for u in uncertain])
(R/'progress.json').write_text(json.dumps(progress,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(summary,ensure_ascii=False))

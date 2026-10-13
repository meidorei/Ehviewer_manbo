from collections import Counter
import importlib.util,json
from pathlib import Path
R=Path(__file__).resolve().parent; ROOT=R.parents[1]; OLD=ROOT/'artifacts/manga-organize-20261009-resumed'
s=importlib.util.spec_from_file_location('o',ROOT/'.codex/skills/organize-ehviewer-v3/organize-ehviewer-skill-mega/scripts/organize.py');o=importlib.util.module_from_spec(s);s.loader.exec_module(o)
c=o.read(R/'catalog/catalog.json');by={x['gid']:x for x in c['items']};d=o.read(R/'delta.json');g=o.read(OLD/'groups.json')
for x in g['series']:x['members']=[m for m in x['members'] if (m if isinstance(m,int) else m['gid']) in by]
g['series']=[x for x in g['series'] if x['members']]
def extend(anchor,member):
 x=next(x for x in g['series'] if any((m if isinstance(m,int) else m['gid'])==anchor for m in x['members']));x['members'].append(member)
extend(2989747,dict(gid=4245442,order=4,note='主体日文彼女催眠与作者santa一致，明确第4篇；Revenge保留原支线。'))
extend(4080467,dict(gid=4245764,category='collection',order=1,end=2,note='见/見人就打署名及主体题名一致，1-2合集与现有合集相邻保留。'))
g['series'] += [dict(name='妈妈…变成了同学的淫母（NTR中毒患者）',note='作者与主体中文题名一致，00-04合集及05续篇；保留真实第0篇范围，不拆合集。',members=[dict(gid=3172696,category='collection',order=0,end=4),dict(gid=3975780,order=5)]),dict(name='路人BL／モブレBL（千桜／Chiharu）',note='日文主体题名与作者一致；空篇和凪篇为不同角色支线，不换算章号。',members=[dict(gid=4241479,branch='空篇'),dict(gid=4241483,branch='凪篇')]),dict(name="加奈子的赌注／Kanako’s Bet（粉丝同人制作）",note='同一完整英文题名及粉丝同人制作标记，PT.2明确续篇；未编号前作不强设第1章，奈绪酱的邀请另留单本。',members=[dict(gid=4244182,branch='前作',note='未编号完整前作。'),dict(gid=4244152,order=2,branch='续作')])]
o.write(R/'groups.json',g);p=o.build(c,g);o.write(R/'preview/order.json',p);(R/'preview/review.html').write_text(o.review_html(p),encoding='utf-8')
n=Counter(x['seriesId'] for x in p['items']);multi={k for k,v in n.items() if v>1}
summary=dict(itemCount=len(by),newTitlesRead=len(d['new']),removedSincePrevious=len(d['removed']),changedTitles=len(d['changed']),inheritedTitleJudgments=len(by)-len(d['new']),multiSeries=len(multi),groupedItems=sum(n[k] for k in multi),singleItems=sum(v for v in n.values() if v==1),newMultiSeries=3,extendedSeries=2,phoneWritten=False)
o.write(R/'summary.json',summary)
o.write(R/'progress.json',dict(catalog='catalog/catalog.json',previousCatalog='../manga-organize-20261009-resumed/catalog/catalog.json',deltaReadGids=d['new'],nextOffset=None,relatedPagesRead=dict(authorCandidates=71,specificTitleCandidates=30),inheritedJudgments='Unchanged titles reuse completed 2026-10-09 judgments',status='ready_for_authorized_writeback',userApproval='2026-10-11: 整理漫画系列并写入'))
u=o.read(OLD/'uncertain.json');o.write(R/'uncertain.json',u)
checks=dict(allGidsExactlyOnce=len(p['gidOrder'])==len(by) and set(p['gidOrder'])==set(by),originalTitlesPreserved=all(all(x[k]==by[x['gid']][k] for k in ('title','titleJpn')) for x in p['items']),planValid=bool(o.validate_plan(c,p)),allNewTitlesRead=set(d['new']).issubset(set(o.read(R/'progress.json')['deltaReadGids'])),rawMatchesSnapshot=o.db_digest(R/'raw/eh.db')==o.db_digest(R/'catalog/source.db'))
assert all(checks.values()),checks
o.write(R/'validation.json',dict(checks=checks,passed=len(checks),failed=0))
print(json.dumps(summary,ensure_ascii=False))

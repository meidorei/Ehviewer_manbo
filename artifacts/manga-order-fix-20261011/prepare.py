from collections import Counter
import importlib.util,json
from pathlib import Path
R=Path(__file__).resolve().parent;ROOT=R.parents[1];OLD=ROOT/'artifacts/manga-organize-20261011'
s=importlib.util.spec_from_file_location('o',ROOT/'.codex/skills/organize-ehviewer-v3/organize-ehviewer-skill-mega/scripts/organize.py');o=importlib.util.module_from_spec(s);s.loader.exec_module(o)
c=o.read(R/'catalog/catalog.json');before=o.read(OLD/'preview/order.json');g=o.read(OLD/'groups.json');by={x['gid']:x for x in c['items']};prev={x['gid']:x for x in before['items']}
assert set(by)==set(prev) and all(all(by[k][f]==prev[k][f] for f in ['title','titleJpn']) for k in by)
first={2989948,3495486,3143565,3978954,3780833,2997403,3445418,3052754,3240545,2935389,3188988,4091289,3822566,845633,3806358,3074264,2865226,2668310,3975765,2258984,3470835,2671500,3975182,2646610,2472253,3236078,2997932,2985577,3304724,3197520,2645707,651647,1148377,3748887,2949272,3647110,2953605,2701296,3497928,3799438,3872812,1817896,3924172,3366204,2273508,1633390,1304551,3549687,3162536,3841313,3899688,3880287,3295466,1736317,3014908,3594933,2291915,3074279,2928360,3179282,2813361,3085187,2915469,2760640,3091537,1588501,3240425,3852193,3731277,3712406,3422715,2425924,3815559,3897577,3806025,3565625,3694226,3660810,3659631,3581790,3690274,3788501,1714651,3499951,3508033,3581494,2347929,3073520,3467062,3645404,3446857,2361378,2606157,3197511,3220413,3252475,2941136,2732313,2703110,3500036,3130766,3315069,2779039,3090331,73741}
ranges={3172696:(0,4),3346349:(1,35),4223833:(36,40),3604449:(1,4),3743972:(1,3),3289938:(3,4),3618401:(1,2),3943697:(1,3),3855698:(1,3),3394869:(1,4),3865107:(0,3),3803189:(1,10),3806543:(1,2),3734607:(1,3),3571621:(1,8),2991582:(4,6),3331224:(1,2)}
updates=[]
for group in g['series']:
 for i,m in enumerate(group['members']):
  if isinstance(m,int):m={'gid':m};group['members'][i]=m
  gid=m['gid'];old=dict(m)
  if gid in first:
   assert m.get('order') is None, gid
   m['sortOrder']=1;m['note']=(m.get('note',group.get('note',''))+'；本次核对作者及同系列题名，按未编号前作放在续篇之前，章号仍未标注。').strip('；')
  if gid==3596281:m['sortOrder']=2;m['note']='Okawari副题明确第二个孩子，与未编号前作及3、4续篇按阅读位置排列，保留未标注章号。'
  if gid==3478576:m['order']=1;m['note']='原中文题名明确标(1)，恢复已明确的章号。'
  if gid in ranges:m['category']='collection';m['order'],m['end']=ranges[gid]
  if gid in {3145485,2486173,3145258}:m['branch']='总集篇卷号';m['note']='总集篇1/2/3为合集卷号，无法直接与正篇第14章比较，单独保留分支及原卷序。'
  if old!=m:updates.append(dict(gid=gid,before=old,after=m))
p=o.build(c,g);o.write(R/'groups.json',g);o.write(R/'preview/order.json',p);(R/'preview/review.html').write_text(o.review_html(p),encoding='utf-8');o.write(R/'adjustments.json',updates)
idx={x:i for i,x in enumerate(p['gidOrder'])};oldidx={x:i for i,x in enumerate(before['gidOrder'])};changes=[]
for group in g['series']:
 ids=[m['gid'] for m in group['members']]
 a=sorted(ids,key=oldidx.get);b=sorted(ids,key=idx.get)
 if a!=b:changes.append(dict(series=group['name'],before=a,after=b))
o.write(R/'changed-series.json',changes)
assert idx[3172696]<idx[3975780]
assert idx[2989948]<idx[2989747]<idx[2985557]<idx[4245442]
assert idx[2273508]>idx[3516052] and idx[2273508]<idx[2450251]
assert all(next(x for x in p['items'] if x['gid']==gid)['order'] is None for gid in first)
assert set(p['gidOrder'])==set(by)
n=Counter(x['seriesId'] for x in p['items']);summary=dict(itemCount=len(by),multiSeries=sum(v>1 for v in n.values()),singleItems=sum(v==1 for v in n.values()),changedSeries=len(changes),inferredPredecessors=len(first),chapterRangeCollectionsReviewed=len(ranges),phoneWritten=False)
o.write(R/'summary.json',summary);o.write(R/'progress.json',dict(catalog='catalog/catalog.json',previousCatalog='../manga-organize-20261011/catalog/catalog.json',candidatesRead=353,nextOffset=None,status='ready_for_authorized_correction',phoneWritten=False));o.write(R/'validation.json',dict(allGidsPreserved=True,titlesPreserved=True,range0To4Before5=True,unnumberedPredecessorBefore2=True,chapter0BeforeInferredPredecessor=True,inferredChapterNumbersRemainUnknown=True))
print(json.dumps(summary,ensure_ascii=False))
